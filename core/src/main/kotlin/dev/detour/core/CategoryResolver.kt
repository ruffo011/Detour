package dev.detour.core

/** What the phone tells us about one installed package. */
data class AppFacts(
    val packageName: String,
    /** Has an icon in the app drawer (a LAUNCHER activity). */
    val isLaunchable: Boolean,
    /** Opens arbitrary http/https links, i.e. it is a web browser. */
    val isBrowser: Boolean,
    /** When the app was first installed (Android's firstInstallTime), if known. */
    val installedAtMs: Long? = null,
)

/** Phone-wide facts that don't depend on the individual package. */
data class DeviceContext(
    /** Packages that must never be interrupted: launcher, keyboards, dialer, system UI, Detour. */
    val essentialPackages: Set<String>,
    /** The single browser that is allowed (it goes through the filter VPN). */
    val allowedBrowser: String,
    /** Suggested categories for well-known apps, used until the user decides. */
    val suggestions: Map<String, AppCategory>,
)

/** Why an app ended up in its category. Also decides whether the user can change it. */
enum class CategoryReason(val editable: Boolean) {
    /** Dialer, emergency, launcher, keyboards, system UI, Settings, Detour itself. */
    ESSENTIAL(false),

    /** The one allowed browser. Change it through the browser choice, not the category. */
    ALLOWED_BROWSER(false),

    /** Any other browser. Blocked so that all web browsing goes through the filter. */
    OTHER_BROWSER(false),

    /** The user picked this category. */
    USER_CHOICE(true),

    /** A suggested default for a well-known app; the user hasn't decided yet. */
    SUGGESTED(true),

    /** No app-drawer icon (system screens, background apps): nothing to interrupt. */
    NOT_LAUNCHABLE(true),

    /** Installed less than 24 hours ago and not classified yet: Allowed while you decide. */
    NEW_APP(true),

    /** A launchable app nobody has classified within its first 24 hours: Distracting until you decide. */
    UNCLASSIFIED(true),
}

data class Resolution(
    val category: AppCategory,
    val reason: CategoryReason,
    /** For a [CategoryReason.NEW_APP]: when it turns Distracting if you haven't chosen. */
    val decideByMs: Long? = null,
) {
    val editable: Boolean get() = reason.editable
    val needsDecision: Boolean get() = reason == CategoryReason.UNCLASSIFIED || reason == CategoryReason.NEW_APP
}

object CategoryResolver {
    /** How long a newly installed, unclassified app counts as Allowed. */
    const val NEW_APP_GRACE_MS = 24 * 60 * 60 * 1000L

    /**
     * Decides an app's category. Order matters: essentials and the browser rule can't be
     * overridden by a stored choice (a stale choice must never unblock a second browser), and a
     * suggestion (e.g. Instagram) beats the new-app grace, so reinstalling it doesn't earn a day off.
     */
    fun resolve(facts: AppFacts, userChoice: AppCategory?, device: DeviceContext, nowMs: Long = 0): Resolution {
        val pkg = facts.packageName
        return when {
            pkg in DefaultClassifications.ESSENTIAL || pkg in device.essentialPackages ->
                Resolution(AppCategory.ALLOWED, CategoryReason.ESSENTIAL)

            facts.isBrowser && pkg == device.allowedBrowser ->
                Resolution(AppCategory.ALLOWED, CategoryReason.ALLOWED_BROWSER)

            facts.isBrowser ->
                Resolution(AppCategory.BLOCKED, CategoryReason.OTHER_BROWSER)

            userChoice != null -> Resolution(userChoice, CategoryReason.USER_CHOICE)

            pkg in device.suggestions -> Resolution(device.suggestions.getValue(pkg), CategoryReason.SUGGESTED)

            !facts.isLaunchable -> Resolution(AppCategory.ALLOWED, CategoryReason.NOT_LAUNCHABLE)

            facts.installedAtMs != null && nowMs >= facts.installedAtMs && nowMs - facts.installedAtMs < NEW_APP_GRACE_MS ->
                Resolution(AppCategory.ALLOWED, CategoryReason.NEW_APP, decideByMs = facts.installedAtMs + NEW_APP_GRACE_MS)

            else -> Resolution(AppCategory.DEFAULT_FOR_UNCLASSIFIED, CategoryReason.UNCLASSIFIED)
        }
    }
}
