# Focus / App Blocker for Android — Build Plan

A practical, end-to-end plan for shipping an Android app that blocks distracting
apps during focus sessions. Written for a solo developer or a small team.

---

## 1. What you are actually building

Android has **no public "block this app" API** for third-party apps. Everything
that works is an assembly of side-doors, each with different power, reliability
and Play Store risk. The product is therefore three things stacked:

1. **A detection engine** — know, within ~100 ms, which app just came to the
   foreground.
2. **An intervention layer** — put something between the user and that app
   (full-screen overlay, home-button press, network cut, or true OS-level
   suspension).
3. **A behavioural layer** — sessions, schedules, friction, stats, streaks.
   This is what makes people keep it; the blocking is table stakes.

Ignoring #3 is the most common way these projects die. A pure blocker gets
uninstalled in three days.

---

## 2. The blocking mechanisms (ranked)

### 2.1 AccessibilityService + overlay — **the primary engine**

The workhorse for nearly every real blocker on Play.

- Subscribe to `TYPE_WINDOW_STATE_CHANGED`. Each event carries
  `event.packageName` — that's the app entering the foreground.
- If the package is blocked right now, immediately either:
  - draw a blocking screen, or
  - `performGlobalAction(GLOBAL_ACTION_HOME)` to bounce them out, or both.
- An `AccessibilityService` can create windows of type
  `TYPE_ACCESSIBILITY_OVERLAY` **without** the `SYSTEM_ALERT_WINDOW`
  permission, and on API 34+ there is
  `attachAccessibilityOverlayToDisplay()` which is cleaner still.
- Accessibility services are **exempt from background execution limits** and
  are **automatically restarted by the system after reboot** (as long as the
  user hasn't revoked the toggle). This solves the single hardest problem in
  this category: staying alive.

**Pros:** instant, event-driven, near-zero battery, survives reboot, no
foreground-service-type headaches.
**Cons:** the user must grant it manually through Settings, it looks scary, and
Google Play scrutinises it (see §6).

### 2.2 UsageStatsManager polling — **the fallback engine**

`UsageStatsManager.queryEvents()` filtered to `ACTIVITY_RESUMED` gives you the
foreground package without accessibility. Requires the `PACKAGE_USAGE_STATS`
special permission (user grants it in Settings → Special app access → Usage
access).

- Poll every 300–800 ms **only while a session is active**; idle otherwise.
- Latency is 0.5–1 s, so the user sees a flash of the blocked app. Acceptable,
  not great.
- Needed anyway for **usage statistics and daily time limits**, so you build it
  regardless.

Ship this as the "lite mode" for users who refuse accessibility, and as the
path of least resistance if Play ever pushes back on your accessibility
declaration.

### 2.3 Local VpnService — **network-level blocking**

Start a `VpnService`, and use `addAllowedApplication(pkg)` for each blocked app
so **only** those apps are routed into your tunnel — then simply drop every
packet. The app opens, but it is offline and useless. No traffic leaves the
device; you never proxy anything.

- Also lets you block **websites** (the browser workaround: someone blocked
  from the Instagram app just opens instagram.com). Filter by DNS name.
- Only one VPN can be active at a time on Android — a real conflict for users
  running a privacy VPN. Detect and warn.
- Works without accessibility. Great combined mechanism: overlay for the app,
  VPN for its web version.

### 2.4 DevicePolicyManager — **the nuclear option**

`DevicePolicyManager.setPackagesSuspended()` is the only *true* OS-level block:
the app icon greys out and tapping it shows a system dialog. Unbypassable.

Two routes for a consumer app:

- **Work profile (Profile Owner).** Your app provisions a managed profile via
  `ACTION_PROVISION_MANAGED_PROFILE` and becomes its owner (the Shelter/Island
  approach). You can then suspend or hide any app *inside that profile*. The
  user must install distracting apps in the work profile. Powerful, but a big
  onboarding ask.
- **Device Owner.** Requires a factory-reset device and a one-time
  `adb shell dpm set-device-owner ...`. Only viable as a documented
  "hardcore mode" for power users, not for the mainstream install.

Treat this as a **v2+ "Strict Edition"**, likely distributed outside Play.

### 2.5 Supporting pieces

| Goal | API |
|---|---|
| Kill notifications from blocked apps | `NotificationListenerService` → `cancelNotification()` |
| Silence everything during a session | `NotificationManager.setInterruptionFilter()` + `ACCESS_NOTIFICATION_POLICY` |
| Sessions surviving reboot | `BOOT_COMPLETED` receiver + persisted session state |
| Scheduled sessions | `AlarmManager.setExactAndAllowWhileIdle` + `SCHEDULE_EXACT_ALARM` (user-granted on Android 14+; **do not** use `USE_EXACT_ALARM`, Play restricts it to clock/calendar apps) |
| Listing installed apps | `queryIntentActivities` for MAIN/LAUNCHER via a `<queries>` manifest element — **avoid `QUERY_ALL_PACKAGES`**, it triggers a Play declaration review |

---

## 3. Recommended architecture

```
app/
 ├─ core-detect/     AccessibilityService, UsageStats poller, foreground-app Flow
 ├─ core-block/      Policy engine, overlay renderer, VPN blackhole, DND control
 ├─ core-data/       Room (rules, sessions, attempt log), DataStore (prefs)
 ├─ core-schedule/   AlarmManager + WorkManager session scheduling
 ├─ feature-onboard/ Permission walkthrough (the highest-churn screen you own)
 ├─ feature-session/ Start/stop, strict mode, timers
 ├─ feature-rules/   App picker, schedules, limits, allowlist
 └─ feature-stats/   Charts, streaks, "blocked N times today"
```

**Stack:** Kotlin, Jetpack Compose + Material 3, Coroutines/Flow, Room, DataStore,
Hilt, WorkManager. `minSdk 26` (Android 8) or `29` if you want a simpler life;
`targetSdk` = latest.

**Hot-path rule:** the policy set lives in memory as an immutable snapshot
(`Map<String, Rule>`), rebuilt on a background dispatcher whenever rules change.
The accessibility callback does a hash lookup and nothing else — **no database,
no disk, no JSON on the detection path.** This is what keeps the phone from
feeling laggy.

**The block loop:**

```
WINDOW_STATE_CHANGED(pkg)
  → pkg == self or launcher or systemui? → ignore
  → policySnapshot[pkg] == null? → ignore
  → rule active right now? (session / schedule / quota exhausted)
  → log attempt (async)
  → decide intervention: overlay | delay-then-allow | home | network-cut
  → render
```

---

## 4. Product design — the part that decides whether it works

### Blocking modes
- **Focus session** — "block these for 45 minutes", the core loop.
- **Schedule** — Mon–Fri 09:00–17:00, or "every night after 23:00".
- **Always blocked** — the permanent quit list.
- **Time quota** — "Instagram: 20 min/day", then it locks.
- **Launch quota** — "you may open it 5 times today". Often more effective than
  time, because the compulsion is the *opening*.
- **Allowlist mode** — only these apps work; everything else is blocked. The
  deep-work nuclear mode.

### Friction ladder (better than a hard wall)
A hard block makes people uninstall; friction makes them reflect. Offer levels:

1. **Breathe** — 10-second countdown before the app opens.
2. **Intention** — "What do you want to do in here?" free-text, then 60 s of access.
3. **Effort** — type a long sentence, solve arithmetic, shake the phone 30×.
4. **Hard block** — no entry until the session ends.
5. **Strict mode** — you cannot end the session early. Variants: a cooldown of
   N minutes before an unlock takes effect, a partner-held PIN, or an NFC tag
   you leave in another room.

### Retention
- Daily "attempts blocked" counter — surprisingly motivating.
- Streaks, weekly recap, before/after screen-time chart.
- Widget + quick-settings tile to start a session in one tap.

### Non-negotiable safety rules
- **Never** block the dialer, emergency calls, Messages, Clock, or Settings.
- Always ship a visible emergency exit (even if it costs the streak). An app
  that traps a user is a liability and a one-star generator.
- Ask for one permission at a time, at the moment it is needed, with a plain
  explanation of what you do with it.

---

## 5. Anti-bypass hardening (v2)

Users *will* try to get around it — that's the point of the product. Ordered by
value/effort:

1. **Block the escape routes.** When `com.android.settings` comes foreground and
   the screen is your app's App Info page (force-stop / uninstall) or the
   Accessibility settings, bounce the user back. Do not match on English UI
   strings — match your own package name in the node tree, which is
   locale-independent.
2. **Detect service death.** If the accessibility service is disabled mid-session,
   notify loudly and mark the session broken in stats.
3. **Clock tampering.** Track session duration with
   `SystemClock.elapsedRealtime()`, never wall-clock; flag large wall-clock jumps.
4. **Uninstall resistance.** A `DeviceAdminReceiver` cannot be uninstalled while
   active — but Play now heavily restricts device-admin use, so keep this for a
   sideloaded strict build.
5. **Safe mode / reboot / factory reset** are unwinnable. Say so honestly in your
   marketing rather than over-promising.

Frame all of this as *commitment device*, not *surveillance*. Never build
anything that reports a user's activity to a third party without explicit,
ongoing consent — that crosses into stalkerware and gets you removed.

---

## 6. Google Play risk — read before writing code

This is the biggest non-technical risk in the project.

- **Accessibility API.** Play's policy says it must be used for accessibility
  purposes; digital-wellbeing and parental-control blockers are a tolerated but
  scrutinised category. You must complete the **Permissions Declaration Form**,
  add a **prominent in-app disclosure** before the user enables the service, and
  set `android:isAccessibilityTool="false"` with an honest
  `accessibilityServiceInfo` description. Expect at least one review round-trip.
  Apps in this space *have* been pulled — mitigate by keeping UsageStats mode
  fully functional so the app still works if you must drop accessibility.
- **VpnService** must be core functionality and disclosed; you may not use it to
  collect data or inject ads.
- **`QUERY_ALL_PACKAGES`** — avoid it entirely, use the `<queries>` element.
- **Device admin** — restricted; assume it means a sideloaded build.
- **Data safety form** — declare that usage data stays on-device (make that true;
  it is also your best marketing line).

**Distribution strategy:** mainstream build on Play (accessibility + overlay +
UsageStats), and an unrestricted "Strict Edition" on GitHub Releases / F-Droid /
Obtainium (device admin, uninstall protection, work-profile suspension).

---

## 7. OEM reality check

Stock Android behaves. Xiaomi/HyperOS, Samsung One UI, Oppo/vivo/realme ColorOS
and Transsion ROMs aggressively kill background apps and silently revoke
accessibility toggles. Plan for it:

- Onboarding step that detects the manufacturer and deep-links to the
  autostart / battery settings screen with instructions (see dontkillmyapp.com
  for the per-OEM intents).
- Request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- A health-check screen: "Protection active / Protection degraded" with a
  one-tap fix for each broken permission. Run this check on every app open.
- **Buy or borrow a cheap Xiaomi and a Samsung.** Emulator testing will not
  surface these bugs, and they are the #1 source of one-star reviews in this
  category.

---

## 8. De-risking spikes — do these first (about a week)

Before committing to the full product, prove the hard parts. Each is 0.5–2 days
and throwaway.

| # | Spike | Success criterion |
|---|---|---|
| 1 | AccessibilityService logging every foreground package | Correct package name in < 150 ms, on stock + one OEM device |
| 2 | Full-screen `TYPE_ACCESSIBILITY_OVERLAY` blocking screen | Cannot be dismissed by back/recents; disappears cleanly |
| 3 | UsageStats poller as an alternative source | Comparable accuracy, measured battery cost |
| 4 | Survive reboot + 12 h idle + Doze | Still blocking the next morning with no user action |
| 5 | VpnService blackholing one chosen package | That app is offline, everything else is unaffected |
| 6 | Work-profile Profile Owner + `setPackagesSuspended` | App truly suspended — decides whether the Strict Edition is feasible |
| 7 | Settings-screen escape blocking | Cannot reach your app's force-stop page during a session |

If spikes 1, 2 and 4 pass on a Xiaomi device, the product is real.

---

## 9. Roadmap

**Phase 0 — Spikes (week 1).** As above. Decide: accessibility-first (yes,
almost certainly).

**Phase 1 — MVP (weeks 2–5).** Ship-ability over features.
- Onboarding + permission flow with health check
- App picker (launcher apps only)
- One blocklist, one manual focus session with a duration picker
- Accessibility detection + overlay block screen
- Persistent notification with remaining time and a Stop button
- Session survives reboot
- "Blocked N attempts" counter
- Local-only data, no account, no analytics on app names

**Phase 2 — Real product (weeks 6–10).**
- Schedules and multiple named profiles ("Study", "Sleep", "Work")
- Daily time and launch quotas (needs the UsageStats path)
- Friction ladder levels 1–3
- Strict mode with cooldown unlock
- Stats screen, streaks, weekly recap
- Home-screen widget + quick-settings tile
- UsageStats fallback mode for users who decline accessibility

**Phase 3 — Differentiation (weeks 11–16).**
- Website blocking via VpnService
- Notification suppression + DND integration
- Anti-bypass hardening (§5)
- Pomodoro/break cycles, ambient sounds, optional session journal
- Export/import of rules, cloud backup (opt-in)

**Phase 4 — Expansion.**
- Strict Edition with work-profile suspension, sideloaded
- Wear OS companion, accountability partner, shared sessions
- iOS via the **Screen Time / FamilyControls + ManagedSettings +
  DeviceActivity** frameworks — far more capable and Apple-sanctioned than
  anything Android offers, but requires a special entitlement from Apple. Apply
  early; approval takes weeks.

A realistic solo-dev target: **4–6 weeks to a usable MVP, 3–4 months to
something you'd charge for.**

---

## 10. Market and monetisation

The space is crowded: AppBlock, StayFocused, Forest, Freedom, ScreenZen, one sec,
Jomo, LockMeOut, Digital Detox. Do **not** ship a generic blocker — pick a wedge:

- **Students** — blocking tied to a study timer, subject tracking, class schedule import.
- **Anti-doomscroll specialisation** — block *feeds* (Reels, Shorts, Explore) rather
  than whole apps. Detect the specific screen via the accessibility node tree.
  Technically the most interesting and the most defensible.
- **Accountability** — a partner holds the unlock; sessions are visible to them.
- **Commitment with stakes** — money, or a charity donation, on failure.

**Pricing:** freemium — one profile and basic sessions free; schedules, strict
mode, website blocking, stats history paid. Roughly $3–5/month, $20–30 lifetime.
Lifetime converts well in this category because the audience distrusts
subscriptions. Use Google Play Billing; RevenueCat if you plan on iOS later.

---

## 11. Immediate next steps

1. Install Android Studio; create a Kotlin/Compose project, `minSdk 26`.
2. Get a physical device (plus a Xiaomi or Samsung for testing).
3. Run spikes 1, 2 and 4 from §8 — target: end of week one.
4. In parallel, draft the Play accessibility declaration text. If you cannot
   write a convincing one, restructure around UsageStats now rather than later.
5. Install the top three competitors and log, precisely, what annoys you in the
   first 20 minutes of each. That list is your feature spec.

---

## Appendix A — Manifest skeleton

> The sketches below are the shape of the thing. The working versions live in
> this folder's Android project — see [`README.md`](README.md). Where the two
> differ, the code is right.


```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- See installed launcher apps without QUERY_ALL_PACKAGES -->
    <queries>
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent>
    </queries>

    <uses-permission android:name="android.permission.PACKAGE_USAGE_STATS"
                     tools:ignore="ProtectedPermissions" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

    <application android:name=".FocusApp">
        <service
            android:name=".detect.BlockerAccessibilityService"
            android:exported="false"
            android:label="@string/accessibility_service_label"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>
    </application>
</manifest>
```

`res/xml/accessibility_service_config.xml`:

```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagRetrieveInteractiveWindows|flagReportViewIds"
    android:canRetrieveWindowContent="true"
    android:notificationTimeout="100"
    android:isAccessibilityTool="false"
    android:description="@string/accessibility_service_description" />
```

## Appendix B — Detection service sketch

```kotlin
class BlockerAccessibilityService : AccessibilityService() {

    @Inject lateinit var policy: PolicyEngine      // holds an in-memory snapshot
    @Inject lateinit var blocker: BlockPresenter   // owns the overlay window

    private var lastPackage: String? = null
    private var lastAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in SYSTEM_EXEMPT) return

        // debounce: the same app fires several window events per launch
        val now = SystemClock.elapsedRealtime()
        if (pkg == lastPackage && now - lastAt < 400) return
        lastPackage = pkg; lastAt = now

        when (val decision = policy.decide(pkg, now)) {   // pure, in-memory, no IO
            Decision.Allow  -> blocker.dismiss()
            is Decision.Block -> {
                blocker.show(pkg, decision)               // TYPE_ACCESSIBILITY_OVERLAY
                if (decision.alsoGoHome) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }
            is Decision.Delay -> blocker.showCountdown(pkg, decision.seconds)
        }
    }

    override fun onInterrupt() = Unit

    private companion object {
        val SYSTEM_EXEMPT = setOf("com.android.systemui", "com.android.dialer")
    }
}
```

Keep `PolicyEngine.decide()` allocation-free and synchronous — it runs on every
single window change on the device.
