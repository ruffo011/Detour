# Detour

A privacy-first Android app to interrupt mindless phone use and redirect you to something healthier.

Detour is a personal tool, installed from your own computer over USB, never published to the
Play Store. It is two apps built from this one project:

| App | Package | What it knows | Internet? |
|---|---|---|---|
| **Detour** (`guard`) | `dev.detour.guard` | Everything personal: which apps you use, check-ins, alternatives, intentions | **No.** The build fails if it ever gets network permission |
| **Detour Filter** (`filter`) | `dev.detour.filter` | Only the list of blocked domains | Yes, only to forward DNS lookups for Chrome |

No Accessibility service, no Device Admin, no analytics, ads or crash reporting.

> **Status:** all six milestones are built. Blocked apps are blocked. Distracting apps share a
> daily budget and go through a pause before every open. Detour Filter blocks YouTube, Facebook,
> Instagram, Reddit and Snapchat in Chrome. Detour checks that every part of protection stays on.
> Loosening any setting waits 24 hours, Settings pages that could turn Detour off get a 60-second
> pause, everything is backed up to a folder you choose, and a weekly review and a setup
> checklist round it out.

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

## 7. Distracting apps and the shared budget (milestone 3)

- **Observe week first.** For the first 7 days Detour only measures how long and how often you
  use Distracting apps, and doesn't limit them. Then it sets a starting budget a little below
  your average (85%, rounded down to 5 minutes) and asks you to keep or adjust it. Blocked apps
  are blocked from day one. *Budget & hours → Stop observing* ends the week early.
- **Classify your apps early.** Any app you haven't classified counts as Distracting, both in
  the observe-week numbers and, afterwards, for the pause.
- **One shared budget** for all Distracting apps. The day resets at **4:00 AM**, and spending is
  only allowed **9:00–23:00**. Both can be changed in *Budget & hours*.
- **Every open goes through a pause.** An open means the app came back after more than 30
  seconds away. You can change that time in *Budget & hours → Counts as a new open after*.
  The pause shows:
  1. A line like "Opened 14 times today · 47 min of 90 used."
  2. *What's going on?* with one-tap feelings.
  3. 3–5 of your alternatives for that feeling, best first, plus **Ride it out** (60 s).
  4. A wait you can't skip, which grows with each open today (5 → 15 → 30 → 60 s), and
     *Why are you opening it?* (at least 10 characters). Then **Continue to [app]** appears.
- **During use:** "Still doing what you came for?" after 10 minutes, a note when 5 minutes are
  left, and the pause screen takes over the moment the time is used up.
- **Out of time / outside hours / opens limit reached:** only your alternatives or the home screen.
- **Learning what works:** the next time you unlock the phone, at least 10 minutes after picking
  an alternative, Detour asks once "How was it?" (better / same / worse / didn't do it, or Skip).
  It ranks alternatives by recent results per feeling and shows plain evidence such as
  "When anxious, “Take a walk” helped 9 of the last 11 times." There are no streaks, points or
  badges.
- **Alternatives** are fully editable (*Alternatives* on the home screen). Ride it out is built in.

## 8. The Chrome filter and health checks (milestone 4)

**Turning it on (once):**
1. Open **Detour Filter** → **Turn on the filter** → accept Android's VPN prompt. A key icon
   appears in the status bar.
2. Settings → Network & internet → VPN → ⚙ next to Detour Filter → turn on **Always-on VPN**,
   so Android keeps it running and restarts it.
3. Chrome → ⋮ → Settings → Privacy and security → **Use secure DNS: Off**.
4. Settings → Network & internet → **Private DNS: Automatic** (or Off). A specific provider
   hostname ("strict mode") sends DNS around the filter, and Detour warns you if it's set.

**How it works.** Detour Filter is a VPN that only Chrome goes through, and only for DNS
(looking up names like `youtube.com`). The VPN's DNS server is a made-up address
(`192.0.2.53` / `fd00:de70:f17e::53`) in a reserved range, and that address is the only route
into it. Chrome's page traffic never passes through the app.
- **Blocked name** (or any subdomain of one): answered locally with "doesn't exist" (NXDOMAIN),
  so the site doesn't load.
- **Anything else:** forwarded to your network's normal DNS server over a socket that bypasses
  the VPN, with Quad9 as the fallback. Truncated answers are retried over TCP.
- Names are never stored or logged. Filter keeps only a count of blocked lookups for today.

**Blocklist** (Detour → *Browser blocklist*). The defaults, all blocked:
- YouTube: youtube.com, youtu.be, youtube-nocookie.com, ytimg.com, googlevideo.com, youtubekids.com
- Facebook: facebook.com, fb.com, fb.watch, fb.me (not messenger.com)
- Instagram: instagram.com, instagr.am, cdninstagram.com, ig.me
- Reddit: reddit.com, redd.it, redditmedia.com, redditstatic.com
- Snapchat: snapchat.com

Shared infrastructure is left alone on purpose: generic Google domains, and `fbcdn.net` /
`facebook.net` (used by messenger.com and "Log in with Facebook"). Groups you switch off stay
listed, and their answers get a 30-second lifetime, so switching one back on works within
about a minute.

**Between the two apps.** guard serves the blocklist to filter, and filter reports back only
running / heartbeat / Private DNS strict / blocked-count. Both go through providers that
require the signature-level permission. `adb shell content query` on either is refused with
"Permission Denial", so only these two apps, signed with your key, can use them.

**Health checks** (every 30 s while the screen is on, and whenever you open Detour): Usage
access, Display over other apps, battery unrestricted, notifications, Detour's watcher, Detour
Filter installed / running / responding, and Private DNS not in strict mode. If anything is
off you get:
- a notification, "Detour protection is partly off: …", which comes back if you swipe it away
- a banner in Detour with a **Fix** button
- a neutral entry in the lapse log, which the weekly review will show later

## 9. Easy to tighten, slow to loosen (milestone 5)

Every change to a protected setting goes through one function, `classify(old, new)` in
`core/.../settings/ProtectedSettings.kt`:
- **Tighter** applies immediately: a stricter app category, less daily time, a lower opens
  limit, shorter allowed hours, longer waits, more frequent check-ins, a shorter "new open"
  time, blocking a group or adding a domain, Settings guard on or guarding another page, ending
  the observe week.
- **Looser** waits **24 hours** and shows under **Pending changes**, where you can cancel it:
  the reverse of all of the above, plus *any* change to the new-day time and changing the
  allowed browser.
- **Neutral** applies immediately: alternatives, confirming the suggested budget.

If a change makes anything looser, the whole change counts as looser, and anything ambiguous
counts as looser. Pending changes are stored as single steps ("daily time → 35 min"), so they
apply on top of whatever you changed in the meantime.

## 10. Settings guard (milestone 5)

Opening a page that could turn Detour off brings up a **60-second pause** and *"Why are you
changing this?"* (at least 10 characters). Then you can go ahead, and that app's settings
pages pass for 5 minutes. It's friction, not a lock. It stays quiet for the first hour after
Detour starts, so the initial setup isn't interrupted.

The guarded pages are Usage access, Display over other apps, VPN, Battery optimization, Chrome's
settings (where *Use secure DNS* lives), Detour's and Detour Filter's App info pages, and
optionally Private DNS. Pixel/AOSP names are built in. Because page names differ between phones,
**Settings guard → Open** lets you open each page once so Detour records its real name. On a
Pixel 8 with Android 17, App info is `com.android.settings.applications.InstalledAppDetailsTop`.

## 11. Backup and restore (milestone 5)

**Backup → Choose backup folder.** Make a new folder (e.g. `Documents/Detour`) rather than
choosing one with other files in it, because Detour gets access to the whole folder you pick.
Detour writes `detour-backup.json` there a few seconds after every change: categories, budget
and hours, blocklist, browser, Settings guard and its pages, pending changes (with their original
times), alternatives, and what helped. Usage history and typed intentions aren't included.

**Restoring after a reinstall:** choose the same folder. Detour finds the backup and offers
**Restore** or **Start fresh**. A restore can't loosen anything: each value that differs goes
through the same 24-hour rule, so anything looser than the fresh install waits 24 hours. Pending
changes keep their original times. Restore only adds or tightens, and never removes things that
are missing from the backup.

## 12. Setup checklist (milestone 6)

Until setup is finished, Detour opens straight into **Set up Detour**. You can always reach it
from the home screen as *Setup checklist*. Each step shows **Done / To do** live. If you finish
later, the home screen keeps listing what's left, so a half-finished setup is never invisible.

1. **Permissions:** Usage access, Display over other apps, notifications, battery
   unrestricted, each with what it can and can't see. If a switch is greyed out, the restricted
   settings steps are shown.
2. **Detour Filter:** turn it on, Always-on VPN, Chrome's *Use secure DNS: Off* (you tick these
   two), and Private DNS not in strict mode (Detour checks this one).
3. **Apps and browser:** nothing left under "Needs a decision", choose your one allowed browser,
   check your allowed hours.
4. **Alternatives:** make the list yours.
5. **Settings guard:** calibrate at least both App info pages.
6. **Backup folder.**
7. **Outside the app** (ticks only): notifications off for Distracting/Blocked apps, YouTube
   watch history off and deleted, Facebook app uninstalled or disabled, logged out of
   Facebook/Instagram in Chrome with their site data cleared, and optionally a router schedule.

**Setup window.** For the first hour after Detour first starts, every change applies
immediately, even loosening ones, and the Settings guard stays quiet. That way the initial setup
isn't held up by the 24-hour rule. Restoring a backup never uses this window: a restore still
can't loosen anything.

### Pixel 8 (Android 17) settings paths

| What | Where |
|---|---|
| Restricted settings | Settings → Apps → See all apps → Detour → ⋮ → **Allow restricted settings** |
| Battery | Settings → Apps → See all apps → Detour → App battery usage → **Unrestricted** |
| Always-on VPN | Settings → Network & internet → VPN → ⚙ Detour Filter → **Always-on VPN** |
| Private DNS | Settings → Network & internet → Private DNS → **Automatic** (or Off) |
| App notifications | Settings → Notifications → App notifications |
| Chrome secure DNS | Chrome → ⋮ → Settings → Privacy and security → **Use secure DNS: Off** |

Samsung and other phones get their own paths in the checklist (best effort, not tested).

## 13. Weekly review (milestone 6)

*Weekly review* on the home screen. Once you've used Detour for a week, a gentle card on the home
screen says it's ready, once a week. It's never a notification. It shows:
- **Time in Distracting apps:** a bar per day for the past seven days, with your budget marked,
  and opens per day.
- **What you tried instead:** alternatives you picked, per feeling, with how they went.
- **What you opened apps for:** the intentions you typed, including Settings guard reasons.
- **When protection was partly off:** lapse events, phrased neutrally, each with an optional
  *"what was going on?"* note.
- **Possible next steps:** only when the week's data supports them, and always stricter, so
  they apply right away if you tap *Apply*. They can be: lowering the daily time if you
  stayed well under it, an opens limit set at your busiest day, or allowed hours trimmed to the
  hours you actually used (with an hour of slack). Nothing is suggested during the observe week.

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
