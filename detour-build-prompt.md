# Build "Detour": a privacy-first Android app to interrupt mindless phone use

You are building a personal Android tool in this repository. It is for one user (me), installed by USB/APK, never published to the Play Store. Read this entire spec before writing any code. Then:

1. Ask me any clarifying questions. **Ask my phone model and Android version first**: setup steps and settings-page names differ by manufacturer (Samsung especially).
2. Propose the module layout and a milestone plan (suggested milestones at the end).
3. Build one milestone at a time, with tests, committing after each.

"Detour" is a working name; feel free to use it for package names (e.g. `dev.detour.guard`, `dev.detour.filter`).

---

## 1. Why this exists (use this to make judgment calls)

Commercial blockers that actually work rely on Android's Accessibility service or a full-traffic VPN, which let a company see everything on my screen or my network traffic. I want something effective **without** handing that access to anyone. Design principles, in priority order:

1. **Privacy by construction.** The main app holds all sensitive data (usage history, feelings check-ins, alternatives) and has **no internet permission**, so it is verifiably incapable of sending data off the phone. No Accessibility service. No Device Admin. No analytics, crash reporting, ads, Firebase, or any third-party SDK beyond AndroidX/Jetpack, Kotlin libraries, Room, and test libraries.
2. **Decisions are made by "reflective me," not "craving me."** Making rules stricter is instant; making them looser is delayed 24 hours. Blocked apps have **no "open anyway" button**.
3. **Replace the behavior, don't just block it.** Every interruption offers alternatives from my own list, matched to what I'm feeling. The app learns which alternatives actually help.
4. **No shame, and lapses are recoverable.** Neutral language everywhere. No streaks, badges, points, or guilt. A lapse (disabling protection, uninstalling) is logged neutrally, and reinstalling restores everything in about a minute.
5. **The phone shouldn't pull me back in.** No engagement notifications. Check-ins happen when I next unlock the phone, not via notifications.

---

## 2. Architecture: two apps, one repo

A Gradle multi-module project (Kotlin, Jetpack Compose, Room), producing **two separate APKs** signed with the same key:

### `guard` app (the main app)
- Holds all logic, UI, and personal data.
- **Must not have `android.permission.INTERNET`.** Add `<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />` to its manifest so no library can sneak it in through manifest merging, **and** add a Gradle check or test that fails the build if the merged release manifest of `guard` contains INTERNET or ACCESS_NETWORK_STATE.
- Permissions: Usage Access (`PACKAGE_USAGE_STATS`), Display over other apps (`SYSTEM_ALERT_WINDOW`), foreground service (with the correct foreground-service type for the current target SDK), post notifications, receive boot completed, request ignore battery optimizations. Use a `<queries>` element for launcher apps rather than `QUERY_ALL_PACKAGES`.

### `filter` app (the browser DNS filter)
- A tiny local DNS-filtering VPN that applies **only to the browser**. It needs INTERNET to forward allowed DNS lookups, which is exactly why it is a separate app: **it never receives usage data, check-ins, or anything personal.** It only knows the domain blocklist.
- No persistent query logging, ever. Domain names pass through memory and are discarded. At most it may keep a running count of blocked lookups for today.
- It has a minimal status UI only. All configuration happens in `guard`.

### Communication between the two
- Define a custom permission with `protectionLevel="signature"` so only my two apps can talk to each other.
- **guard → filter:** the current blocklist groups (domains plus whether each is currently blocked). Expose it through a signature-protected ContentProvider in `guard` that `filter` reads, and/or explicit broadcasts when the config changes. `filter` caches the latest config locally so it keeps working if `guard` is killed.
- **filter → guard:** status only (running yes/no, last heartbeat, whether Private DNS is in strict mode, blocked-lookup count today). Nothing else.
- Document the debug/release signing setup clearly, since the signature permission requires both APKs to share a signing certificate.

---

## 3. `guard`: app classification and detection

### Three app categories
- **Allowed:** never interrupted. Seed with the dialer and emergency features (always allowed, not editable), SMS/messages, Messenger (`com.facebook.orca`), maps, camera, clock, banking, YouTube Music (`com.google.android.apps.youtube.music`), the launcher, system UI, keyboards/IMEs, and `guard`/`filter` themselves. I pick the rest during onboarding.
- **Distracting:** counts against a **shared daily budget** (see §5). Suggested default: YouTube (`com.google.android.youtube`).
- **Blocked:** always blocked, no way to open. Suggested defaults: Facebook (`com.facebook.katana`), Facebook Lite (`com.facebook.lite`). Ask me during onboarding about Instagram (`com.instagram.android`), TikTok, Reddit, X, etc.
- **Browsers:** exactly one browser is allowed (default Chrome, `com.android.chrome`), and it goes through the `filter` VPN. **Every other browser is Blocked by default**, including Samsung Internet (`com.sec.android.app.sbrowser`) and any browser installed later (detect apps that handle `ACTION_VIEW` for http/https).
- **Newly installed or unclassified apps** default to Distracting, and `guard` asks me to classify them. Classifying into a stricter category applies immediately. A looser category goes through the 24-hour queue (§6).

### Foreground detection
- A foreground service polls `UsageStatsManager.queryEvents` (about every 500–1000 ms while the screen is on; pause polling when the screen is off, using screen on/off broadcasts) and tracks `ACTIVITY_RESUMED` / `ACTIVITY_PAUSED` events, including `getClassName()`, which §7 needs.
- Request a battery optimization exemption during onboarding, and include OEM-specific instructions (e.g. Samsung "Never sleeping apps"). Detect when the service was killed (gaps in heartbeat) and log it.

### Intervention screen
- When a Blocked app, or a Distracting app that is out of budget or outside allowed hours, comes to the foreground, immediately show a full-screen intervention (an activity launched over it, which the overlay permission allows, or an overlay; pick what is most reliable on current Android and explain why). Leaving the intervention always goes to the **home screen**, never back into the app.
- For Blocked apps and an exhausted budget, the only choices are my alternatives (§4) or going home.
- Handle the back button, recents, and quick re-launch loops gracefully: re-launching should re-trigger the intervention without flicker or crash loops.

---

## 4. Alternatives: replacing the behavior

### My alternatives list
Each alternative has a title, optional note, **feeling tags** (anxious, bored, lonely, avoiding something, tired, other), **duration** (about 2 min, about 15 min, about 1 hr), and **context tags** (home, out, work, in bed). It's fully editable in-app. Adding alternatives is always immediate. Seed it with placeholders I'll edit:
- Take a walk (anxious, avoiding; ~15 min; home/out)
- 10 slow breaths (anxious, tired; ~2 min; anywhere)
- Read a few pages (bored, tired; ~15 min; home/in bed)
- Text or call a friend (lonely; ~2–15 min; anywhere)
- Work on a craft project (bored, anxious; ~1 hr; home)
- Stand up and stretch (tired, avoiding; ~2 min; anywhere)
- Do the first 2 minutes of the thing I'm avoiding (avoiding; ~2 min; anywhere)
- **Ride it out**: a built-in 60-second urge-surfing timer with a calm visual and one line of text ("Notice the urge. You don't have to act on it. It will pass.")

### The interruption flow
1. Show one line of feedback: e.g. "Opened 14 times today · 47 min of 90 used."
2. Ask "What's going on?" with one-tap chips: anxious · bored · lonely · avoiding something · tired · other.
3. Show 3–5 alternatives matching that feeling (and the current context if inferable, e.g. late night means "in bed"), ranked by what has worked for me before (below).
4. Tapping an alternative logs the choice and goes home (or starts the Ride-it-out timer).

### Learning what works
- The next time I unlock the phone (`ACTION_USER_PRESENT`) at least ~10 minutes after choosing an alternative, show a one-tap check-in: "How was [walk]?" with **better · same · worse · didn't do it**. Skippable, never a notification.
- Rank alternatives per feeling by their recent outcome history (e.g. a smoothed success rate over the last N attempts). Show plain evidence when there's enough data: "When anxious, walks helped 9 of the last 11 times."
- No streaks, scores, badges, or celebratory animations.

---

## 5. Distracting apps: the shared budget

- **One daily budget for the whole Distracting category** (minutes), shared across apps, so hitting the limit on one app doesn't push me to another.
- **The day resets at 4:00 AM** (configurable; moving it is a "loosen" change), so late-night use doesn't get a fresh budget at midnight. Handle DST and timezone changes correctly.
- **Optional cap on opens per day** (a new session = the app resumed after more than 30 s away).
- **Optional allowed hours** (e.g. the budget can only be spent 9 AM–10 PM). Default on, with times set at onboarding.
- **Every open of a Distracting app (while budget remains) goes through a pause first:** the feeling check and alternatives as in §4, plus a **non-skippable wait that escalates with opens today** (default 5 s → 15 s → 30 s → 60 s, configurable), plus typing a short intention ("Why are you opening this?", minimum ~10 characters). Then a "Continue to [app]" button appears. The intention is saved locally for the weekly review.
- **Session check-in:** after N minutes in a Distracting app (default 10), show a small overlay: "Still doing what you came for?" with *yes, keep going* / *no, take me home*.
- When the budget runs out mid-session, intervene immediately. Show a gentle warning at 5 minutes remaining.
- **Observe-only mode for the first 7 days**, applying only to the Distracting budget (Blocked apps are blocked from day one): it measures my real baseline (daily minutes and opens), then suggests a starting budget slightly below it. I confirm or adjust (tightening is immediate).

---

## 6. Asymmetric settings: easy to tighten, slow to loosen

Every settings change is classified as **tighten** (applies immediately) or **loosen** (queued for 24 hours, cancellable, shown in a "Pending changes" list with a countdown).

- **Tighten:** moving an app to a stricter category; lowering the budget or opens cap; shrinking allowed hours; lengthening waits; adding domains to the browser blocklist; turning protections on.
- **Loosen:** the reverse of each of those; changing the reset time; pausing protection; removing domains; disabling the Settings guard (§7).
- **Neutral (immediate):** adding, editing, or removing alternatives; cosmetic settings; exporting data.

Implement this as a single well-tested function, `classify(old, new) -> Tighten | Loosen | Neutral`, so no setting can accidentally bypass the queue. When in doubt, classify as Loosen.

---

## 7. The Settings guard: friction where I'd go to cheat

- Using Usage Access class names, detect when these screens come to the foreground: `guard`'s or `filter`'s app-info page; the Usage Access settings page; the Display-over-other-apps page; VPN settings; Private DNS settings; Battery optimization settings for my apps; and Chrome's settings activity (where "Use secure DNS" lives).
- Class names vary by OEM, so include a **calibration step in onboarding**: guide me to open each of those pages once, and record the resumed `(package, className)` pairs. Also ship known AOSP/Pixel and Samsung defaults.
- On detection, show a timed pause (default 60 s, non-skippable) plus "Why are you changing this?" (typed). Then allow through. This is friction, not a lock, because I sometimes have legitimate reasons.

---

## 8. Health, lapses, and recovery

- **Continuously check:** Usage Access granted, overlay granted, battery exemption, foreground service alive, `filter` running and heartbeating, Private DNS not in strict mode (strict mode can bypass the filter).
- If anything is off, show a **persistent, non-dismissable notification** ("Detour protection is partly off: [what]") and a banner in the app with a one-tap fix. Log it as a neutral **lapse event** (timestamp + what changed).
- **Config backup:** during onboarding, have me pick a folder using the Storage Access Framework (no storage permission needed). Auto-export config + alternatives + outcome history to a JSON file there on every change. On first launch, if a backup exists, offer to restore it. Note: pending "loosen" changes in a backup should restore as still pending with their original timestamps, and a restore must not let me sneak in a looser config: a restore that is looser than the current config goes through the 24-hour queue.

---

## 9. Weekly review

A screen (and a gentle in-app prompt, not a notification, once a week when I open `guard`) showing:
- Daily Distracting minutes and opens over the past week (simple bar chart).
- Alternatives chosen, with outcomes, per feeling.
- Intentions I typed (scrollable), so I can see what I was actually opening apps for.
- Lapse events, phrased neutrally, with an optional free-text "what was going on?" note.
- Suggested tightening steps, if the data supports them (applied immediately if I accept).

---

## 10. `filter`: the browser-only DNS VPN

- Use `VpnService` with `addAllowedApplication()` for the single allowed browser only (default Chrome). Other apps, including Messenger and the YouTube app, are **not** routed through it.
- **DNS-only routing:** give the VPN a fake DNS server address in an unused private/test range, `addDnsServer()` that address, and `addRoute()` **only** that address (IPv4 and IPv6). All page traffic goes out normally and never passes through the app; only DNS queries to the fake server enter the tunnel.
- Parse the UDP DNS queries in the tunnel:
  - **Blocked domain** (exact or any subdomain, case-insensitive, trailing dot handled): answer **NXDOMAIN** locally.
  - **Allowed:** forward to the upstream resolver over a socket protected with `VpnService.protect()`, and relay the response back. Upstream default = the DNS servers of the underlying non-VPN network (from `ConnectivityManager` link properties), with a fallback I can configure. Handle timeouts, truncated responses, and network changes.
  - Answers for domains in blocklist groups that are *currently allowed* get their TTL clamped to about 30 s, so a block takes effect quickly when it switches on.
- **Default blocklist groups** (editable from `guard`, always blocked in the browser on the phone; educational YouTube happens on my computer):
  - YouTube: `youtube.com`, `youtu.be`, `youtube-nocookie.com`
  - Facebook: `facebook.com`, `fb.com`, `fb.watch`, `fb.me` (**not** `messenger.com`)
  - Instagram: `instagram.com`, `instagr.am`, `cdninstagram.com`
  - Research and propose any other domains needed so these sites don't load, but avoid shared infrastructure domains that would break unrelated sites (e.g. don't block generic Google domains).
- Run as a foreground service; restart on boot and on network changes; recommend enabling Android's **Always-on VPN** for it during onboarding.
- Detect Private DNS strict mode and report it to `guard` as a bypass condition.
- Unit-test the DNS packet parser and response builder thoroughly (malformed packets, multiple questions, EDNS, IPv6 AAAA queries, compression pointers).

---

## 11. Onboarding

A step-by-step flow that I can't half-finish without seeing what's missing:
1. Explain in two sentences what each permission sees and does not see.
2. **Restricted settings:** because I'm sideloading, Android 13+ blocks Usage Access and Display-over-other-apps for my apps until I tap "Allow restricted settings" in the app-info ⋮ menu. Walk me through it (and note that installing via `adb install` from Android Studio may avoid this).
3. Grant Usage Access, overlay, notifications, battery exemption (with OEM-specific steps for my phone).
4. Install and enable `filter`: VPN consent, Always-on VPN, then Chrome → Settings → Privacy → **Use secure DNS: Off**, and Android **Private DNS: Off or Automatic**.
5. Classify apps (with the suggested defaults above), choose the allowed browser, set allowed hours.
6. Edit the alternatives list.
7. Settings-guard calibration (§7).
8. Pick the backup folder.
9. A checklist of things **I** do outside the app (just shown, with checkboxes): turn off notifications for Distracting/Blocked apps; turn off and delete YouTube watch history; uninstall or disable the Facebook app; log out of Facebook/Instagram in Chrome and clear their site data; optionally set a router schedule blocking these sites on my phone at night.

---

## 12. Quality bar

- Target the current stable Android SDK; minSdk 29 unless you have a reason. Use the correct foreground-service types and permissions for that target.
- Unit tests for: DNS parsing/response building; domain matching; `classify()` tighten/loosen; budget accounting across the 4 AM boundary and DST; session/open counting; alternative ranking; backup/restore (including the "restore can't loosen" rule).
- A manifest test proving `guard` has no network permissions.
- Calm, simple Compose UI: large text on intervention screens, no bright reward colors, dark-mode friendly.
- A `README.md` for building and installing **from Windows with Android Studio**: install Android Studio, open the project, enable USB debugging on the phone, build and install both apps, the signing setup, and the restricted-settings and onboarding steps. Assume I'm not an Android developer.

## Suggested milestones

1. Project skeleton, both modules, signing, the manifest network-permission test, README build steps.
2. `guard`: categories, foreground detection, intervention screen for Blocked apps. (First usable version.)
3. Distracting budget, pause flow, alternatives, check-ins, observe-only mode.
4. `filter` DNS VPN + guard↔filter communication + health checks.
5. Asymmetric settings queue, Settings guard with calibration, backup/restore.
6. Weekly review, onboarding polish, OEM-specific notes for my phone.

Before starting, ask me your clarifying questions (phone model first).
