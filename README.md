# Detour

A privacy-first Android app to interrupt mindless phone use and redirect you to something healthier.

Detour is a personal tool, installed from your own computer over USB, never published to the
Play Store. It is two apps built from this one project:

| App | Package | What it knows | Internet? |
|---|---|---|---|
| **Detour** (`guard`) | `dev.detour.guard` | Everything personal: which apps you use, check-ins, alternatives, intentions | **No.** The build fails if it ever gets network permission |
| **Detour Filter** (`filter`) | `dev.detour.filter` | Only the list of blocked domains | Yes, only to forward DNS lookups for Chrome |

No Accessibility service, no Device Admin, no analytics, ads or crash reporting.

> **Status:** milestone 2. Detour sorts apps into categories, watches which app is open, and
> blocks Blocked apps (including every browser except Chrome) with a full-screen pause.
> The Distracting budget arrives in milestone 3, and the Chrome filter in milestone 4.
> For now, category changes apply immediately in both directions. The 24-hour delay on
> loosening arrives in milestone 5.

---

## 1. One-time setup on your Windows PC

1. **Install Android Studio** from <https://developer.android.com/studio> (the defaults are fine).
   On first launch, let the setup wizard download the Android SDK.
2. **Install the Android 17 SDK (API 37)** if it's missing: in Android Studio, open
   *Tools → SDK Manager → SDK Platforms* and tick **Android 17.0 (API 37)**, then *Apply*.
3. **Open the project:** *File → Open…* and select this `Detour` folder. Android Studio
   runs a "Gradle sync" the first time, which downloads build tools and libraries. This
   takes a few minutes. Wait until the progress bar at the bottom finishes.

You don't need to install Java or Gradle yourself: Android Studio includes Java, and the
project includes a Gradle wrapper (`gradlew.bat`) that downloads the right Gradle version.

## 2. One-time setup on the Pixel 8

1. **Turn on Developer options:** *Settings → About phone*, tap **Build number** 7 times,
   then enter your PIN.
2. **Turn on USB debugging:** *Settings → System → Developer options → USB debugging*.
3. Plug the phone into the PC with a USB-C cable. On the phone, tap **Allow** on the
   "Allow USB debugging?" prompt (tick "Always allow from this computer").
4. In Android Studio the phone should appear in the device dropdown at the top as
   *Google Pixel 8*.

## 3. Build and install both apps

**From Android Studio (easiest):**

1. In the toolbar, pick **guard** in the run-configuration dropdown (next to the device
   dropdown) and press the green **Run ▶** button. Detour installs and opens on the phone.
2. Pick **filter** in the same dropdown and press **Run ▶** again.

**From a terminal** (in the `Detour` folder, with the phone plugged in):

```bash
.\gradlew.bat :guard:installDebug :filter:installDebug
```

To run all the tests and build both apps without installing:

```bash
.\gradlew.bat test assembleDebug
```

The APK files end up in `guard\build\outputs\apk\` and `filter\build\outputs\apk\`.

> Installing through Android Studio or `adb` usually avoids Android's "restricted settings"
> block (see section 5). Copying the APK to the phone and opening it from Files does not.

## 4. Signing (why both apps must share a key)

The two apps talk to each other through a permission declared with
`protectionLevel="signature"`. Android grants it **only if both APKs are signed with the
same certificate**, so nobody else's app can read the blocklist or fake the filter's status.

**Debug builds (what the steps above install):** Android Studio signs every debug build
with your PC's personal debug key at `%USERPROFILE%\.android\debug.keystore`, which it
creates automatically. Both apps built on the same PC therefore match, and you have
nothing to set up.

- **Back up `debug.keystore`.** Android won't let you install an update signed with a
  different key. If you lose this file (new PC, reinstalled Windows), you have to uninstall
  both apps first and then restore from your Detour backup folder (milestone 5).
- This key is never committed to the repository.

**Release builds (optional, faster and smaller):**

1. Create a keystore once. It must never be committed (`*.jks` is in `.gitignore`):

   ```bash
   & "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -keystore detour-release.jks -alias detour -keyalg RSA -keysize 4096 -validity 36500
   ```

2. Create `keystore.properties` in the `Detour` folder (also git-ignored):

   ```properties
   storeFile=detour-release.jks
   storePassword=the password you chose
   keyAlias=detour
   keyPassword=the password you chose
   ```

3. Build with `.\gradlew.bat assembleRelease`. Both apps get signed with this key. Without
   `keystore.properties`, release builds fall back to the debug key, so the two apps still
   match.

Switching an installed app between the debug key and the release key also requires
uninstalling first. Pick one and stick with it, and back up the `.jks` file and its password.

## 5. Restricted settings (Android 13 and later)

Because these apps don't come from the Play Store, Android may grey out **Usage access**
and **Display over other apps** for Detour with a "Restricted setting" message. To unlock them:

1. *Settings → Apps → See all apps → Detour*.
2. Tap the **⋮** menu (top right) → **Allow restricted settings**, then confirm with your PIN.
3. Go back and grant the permission again.

Installing through Android Studio / `adb` (section 3) often avoids this block entirely.

## 6. How blocking works (milestone 2)

- **Categories.** Open Detour → **Choose app categories**. Each app is *Allowed*, *Distracting*
  or *Blocked*. Some apps are set by Detour and can't be changed: the phone app, emergency,
  launcher, keyboard, system UI, Settings, Detour itself, Chrome (your one allowed browser), and
  every other browser (always Blocked, so all browsing goes through the filter). New apps start
  as Distracting and show up under "Needs a decision".
- **Watching.** Once Usage access and Display over other apps are on, a silent "Detour is on"
  notification appears. That's Android's required sign that a background service is running. You
  can minimize it by long-pressing it. The service checks the foreground app about every 0.75 s
  while the screen is on, and sleeps while it's off.
- **Interrupting.** Opening a Blocked app shows a full-screen pause instead. Its only way out is
  the home screen: back, the button, Home and Recents never return you to the app, and reopening
  the app just shows the pause again.
- **Why an activity plus a 1-pixel overlay:** the pause is a normal full-screen activity
  (proper focus, keyboard, back gesture). Since Android 15, an app may only open an activity from
  the background if it holds "Display over other apps" *and* has a visible overlay window, so
  Detour keeps an invisible 1×1-pixel, untouchable overlay on screen while it runs.
- **If Android stops it.** The service restarts after reboots and updates. If the system kills
  it, the next start logs a neutral note ("Detour's watcher wasn't running for about N min"),
  which appears in the weekly review later.

**To try it:** in App categories, set any app you don't need (e.g. Calculator) to **Blocked** and
open it. Afterwards, set it back to Allowed.

## 7. Onboarding

*(Milestone 6 adds the full guided flow. The steps it will cover on a Pixel 8:)*

1. Grant **Usage access** and **Display over other apps** to Detour.
2. Allow notifications. These are only used for "protection is partly off" alerts.
3. Battery: *Settings → Apps → Detour → App battery usage → **Unrestricted***.
4. Open Detour Filter, accept the VPN prompt, then *Settings → Network & internet → VPN →
   ⚙ next to Detour Filter → **Always-on VPN***.
5. Chrome → *Settings → Privacy and security → **Use secure DNS: Off***.
6. *Settings → Network & internet → **Private DNS: Off or Automatic*** (strict mode bypasses the filter).

---

## Project layout

```
core/      Plain Kotlin, no Android. Categories, tighten/loosen classification, budget and
           4 AM day logic, alternative ranking, backup rules. Unit-tested on the PC.
dnscore/   Plain Kotlin. DNS packet parsing and building, domain matching. Unit-tested.
ipc/       Android library merged into both apps: the signature permission and the
           guard <-> filter contract.
guard/     The Detour app. All personal data. No network permission.
filter/    The Detour Filter app. Browser-only DNS VPN. Knows only the blocklist.
```

## Privacy check

`guard/src/main/AndroidManifest.xml` strips `INTERNET`, `ACCESS_NETWORK_STATE` and
`CHANGE_NETWORK_STATE` even if a library tries to add them. A Gradle task,
`check<Variant>NoNetworkPermissions`, then reads guard's final merged manifest and
**fails the build** if any of them are still there. It runs automatically before every
guard APK is packaged, and as part of `gradlew check`. To run it on its own:

```bash
.\gradlew.bat :guard:checkReleaseNoNetworkPermissions
```

The Detour home screen also reads its own installed permissions on the phone and tells you
whether it has network access.
