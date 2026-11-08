package dev.detour.guard.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import dev.detour.core.AppCategory
import dev.detour.core.AppFacts
import dev.detour.core.CategoryResolver
import dev.detour.core.DefaultClassifications
import dev.detour.core.DeviceContext
import dev.detour.core.Resolution

data class InstalledApp(val packageName: String, val label: String)

/**
 * Everything guard needs to know about installed apps, read from PackageManager.
 * Only sees what the manifest's <queries> allow (launcher apps, home apps, browsers):
 * no QUERY_ALL_PACKAGES. Results are cached briefly because the detector asks every tick.
 */
class AppCatalog(private val context: Context) {
    private val pm: PackageManager = context.packageManager
    private val prefs = context.getSharedPreferences("guard_settings", Context.MODE_PRIVATE)

    private data class Snapshot(
        val launchable: Map<String, String>,   // package -> label
        val installedAt: Map<String, Long>,    // package -> first install time
        val browsers: Set<String>,
        val device: DeviceContext,
        val takenAtMs: Long,
    )

    @Volatile private var snapshot: Snapshot? = null

    /** The one browser that is allowed (default Chrome). A UI to change it comes with onboarding. */
    val allowedBrowser: String
        get() = prefs.getString(KEY_ALLOWED_BROWSER, DefaultClassifications.CHROME)!!

    fun setAllowedBrowser(packageName: String) {
        prefs.edit().putString(KEY_ALLOWED_BROWSER, packageName).apply()
        invalidate()
    }

    fun invalidate() {
        snapshot = null
    }

    fun resolve(packageName: String, choices: Map<String, AppCategory>): Resolution {
        val s = current()
        val facts = AppFacts(
            packageName = packageName,
            isLaunchable = packageName in s.launchable,
            isBrowser = packageName in s.browsers,
            installedAtMs = s.installedAt[packageName],
        )
        return CategoryResolver.resolve(facts, choices[packageName], s.device, System.currentTimeMillis())
    }

    /** Installed web browsers (apps that open any http/https link). */
    fun browsers(): List<InstalledApp> {
        val s = current()
        return s.browsers.map { InstalledApp(it, s.launchable[it] ?: label(it)) }.sortedBy { it.label.lowercase() }
    }

    fun launchableApps(): List<InstalledApp> =
        current().launchable.map { (pkg, label) -> InstalledApp(pkg, label) }.sortedBy { it.label.lowercase() }

    fun label(packageName: String): String =
        current().launchable[packageName]
            ?: runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }
                .getOrDefault(packageName)

    private fun current(): Snapshot {
        val s = snapshot
        if (s != null && System.currentTimeMillis() - s.takenAtMs < MAX_AGE_MS) return s
        return load().also { snapshot = it }
    }

    private fun load(): Snapshot {
        val launchable = query(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
            .associate { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        // firstInstallTime survives app updates, so only genuinely new installs look new.
        val installedAt = launchable.keys.mapNotNull { pkg ->
            runCatching { pkg to pm.getPackageInfo(pkg, 0).firstInstallTime }.getOrNull()
        }.toMap()

        // A browser is anything that opens a link to a made-up domain: apps that only handle
        // their own links (YouTube, Reddit) don't match, real browsers do.
        val browsers = listOf("https", "http").flatMap { scheme ->
            query(
                Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://detour.invalid/"))
                    .addCategory(Intent.CATEGORY_BROWSABLE),
            )
        }.map { it.activityInfo.packageName }.toSet()

        val homeApps = query(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            .map { it.activityInfo.packageName }
        val keyboards = context.getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList.orEmpty().map { it.packageName }
        val dialer = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        val sms = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()

        val device = DeviceContext(
            essentialPackages = (homeApps + keyboards + listOfNotNull(dialer)).toSet(),
            allowedBrowser = allowedBrowser,
            suggestions = DefaultClassifications.suggestions(extraAllowed = setOfNotNull(sms)),
        )
        return Snapshot(launchable, installedAt, browsers, device, System.currentTimeMillis())
    }

    private fun query(intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }

    companion object {
        private const val KEY_ALLOWED_BROWSER = "allowed_browser"
        private const val MAX_AGE_MS = 60_000L
    }
}
