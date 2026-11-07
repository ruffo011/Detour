package dev.detour.core.setup

/**
 * Settings paths differ by manufacturer. These are the instructions shown during setup.
 * Pixel (Google) is the tested path; Samsung and a generic fallback are best effort.
 */
data class DeviceSteps(
    val deviceName: String,
    val restrictedSettings: String,
    val battery: String,
    val alwaysOnVpn: String,
    val privateDns: String,
    val notificationsForApp: String,
)

object DeviceStepsCatalog {
    fun forManufacturer(manufacturer: String, model: String = ""): DeviceSteps = when (manufacturer.lowercase()) {
        "google" -> DeviceSteps(
            deviceName = model.ifBlank { "Pixel" },
            restrictedSettings = "Settings → Apps → See all apps → Detour → ⋮ (top right) → Allow restricted settings → confirm with your PIN.",
            battery = "Settings → Apps → See all apps → Detour → App battery usage → Unrestricted.",
            alwaysOnVpn = "Settings → Network & internet → VPN → ⚙ next to Detour Filter → turn on Always-on VPN.",
            privateDns = "Settings → Network & internet → Private DNS → Automatic (or Off).",
            notificationsForApp = "Settings → Notifications → App notifications → turn off the app.",
        )
        "samsung" -> DeviceSteps(
            deviceName = model.ifBlank { "Samsung" },
            restrictedSettings = "Settings → Apps → Detour → ⋮ (top right) → Allow restricted settings.",
            battery = "Settings → Apps → Detour → Battery → Unrestricted. Also Settings → Battery → Background usage limits → Never sleeping apps → add Detour and Detour Filter.",
            alwaysOnVpn = "Settings → Connections → More connection settings → VPN → ⚙ next to Detour Filter → Always-on VPN.",
            privateDns = "Settings → Connections → More connection settings → Private DNS → Automatic (or Off).",
            notificationsForApp = "Settings → Notifications → App notifications → turn off the app.",
        )
        else -> DeviceSteps(
            deviceName = model.ifBlank { manufacturer },
            restrictedSettings = "Settings → Apps → Detour → ⋮ menu → Allow restricted settings.",
            battery = "Settings → Apps → Detour → Battery → Unrestricted (or \"Don't optimize\").",
            alwaysOnVpn = "Settings → Network → VPN → ⚙ next to Detour Filter → Always-on VPN.",
            privateDns = "Settings → Network → Private DNS → Automatic (or Off).",
            notificationsForApp = "Settings → Notifications → turn off the app.",
        )
    }

    /** Things only you can do, outside the app. Shown as a checklist during setup. */
    val OUTSIDE_THE_APP: List<Pair<String, String>> = listOf(
        "notifs" to "Turn off notifications for your Distracting and Blocked apps",
        "yt_history" to "YouTube: turn off and delete watch history (myactivity.google.com)",
        "fb_app" to "Uninstall or disable the Facebook app",
        "logout" to "Log out of Facebook and Instagram in Chrome and clear their site data",
        "router" to "Optional: a router schedule that blocks these sites on your phone at night",
    )
}
