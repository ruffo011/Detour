package dev.detour.core

/** Built-in package lists. Phone-specific ones (launcher, keyboards, dialer) are added at runtime. */
object DefaultClassifications {
    const val GUARD = "dev.detour.guard"
    const val FILTER = "dev.detour.filter"
    const val CHROME = "com.android.chrome"

    /** Never interrupted and not editable. */
    val ESSENTIAL: Set<String> = setOf(
        GUARD,
        FILTER,
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.emergency",
        "com.google.android.apps.safetyhub",       // Pixel Personal Safety / emergency
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.intentresolver",               // share sheet / "open with"
    )

    /** Suggested Allowed apps (editable). Banking apps are picked during onboarding. */
    val SUGGESTED_ALLOWED: Set<String> = setOf(
        "com.google.android.apps.messaging",          // Google Messages (SMS)
        "com.facebook.orca",                          // Messenger
        "com.google.android.apps.maps",
        "com.google.android.GoogleCamera",
        "com.android.camera2",
        "com.google.android.deskclock",
        "com.google.android.apps.youtube.music",
        "com.google.android.calculator",
        "com.google.android.calendar",
        "com.google.android.contacts",
    )

    /** Suggested Distracting apps (from onboarding answers). */
    val SUGGESTED_DISTRACTING: Set<String> = setOf(
        "com.google.android.youtube",
        "com.instagram.android",
        "com.reddit.frontpage",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.snapchat.android",
    )

    /** Suggested Blocked apps. Empty for now: nothing is fully blocked by default. */
    val SUGGESTED_BLOCKED: Set<String> = emptySet()

    fun suggestions(extraAllowed: Set<String> = emptySet()): Map<String, AppCategory> = buildMap {
        (SUGGESTED_ALLOWED + extraAllowed).forEach { put(it, AppCategory.ALLOWED) }
        SUGGESTED_DISTRACTING.forEach { put(it, AppCategory.DISTRACTING) }
        SUGGESTED_BLOCKED.forEach { put(it, AppCategory.BLOCKED) }
    }
}
