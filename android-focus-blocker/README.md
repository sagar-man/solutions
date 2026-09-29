# FocusBlocker — spike scaffold

A runnable Android project wiring up the three de-risking experiments from
[`PLAN.md`](PLAN.md) §8. It is deliberately not a product: there is no
onboarding polish, no strict mode, no stats. It exists to answer three
questions on real hardware before anyone commits to building the app.

| Spike | Question | Where it lives |
|---|---|---|
| **1** | Can we learn which app came to the foreground in under 150 ms? | `detect/BlockerAccessibilityService.kt`, measured in `core/ForegroundAppLog.kt` |
| **2** | Can we cover that app with a screen the user cannot dismiss? | `block/BlockOverlay.kt` |
| **4** | Does a session survive a reboot, a 12-hour idle, and a clock change? | `core/SessionMath.kt`, `core/SessionStore.kt`, `session/BootReceiver.kt` |

(Spike 3 — the UsageStats fallback engine — is not wired up. The
`PACKAGE_USAGE_STATS` permission is declared so it can be added without
touching the manifest.)

## Running it

1. Open the `android-focus-blocker` folder in Android Studio (Ladybug or newer)
   and let it sync. Nothing is pinned to a preview version; bump the versions in
   `gradle/libs.versions.toml` if the IDE suggests it.
2. Build and install on a **physical device**. The emulator will happily pass all
   three spikes and tell you nothing about spike 4.
3. Open the app → **Enable FocusBlocker** → turn the service on in Settings.
4. Tick one or two apps you can afford to have blocked, start a 5-minute session,
   and try to open them.

`./gradlew test` runs the `SessionMath` unit tests — the clock arithmetic behind
spike 4, including the clock-tampering cases.

## The spike protocol

Do these in order and write the answers down; that record is the input to the
Phase 1 decision, not a vibe.

**Spike 1 — latency.** Enable the service, then use the phone normally for two
minutes: open the launcher, a browser, a chat app, the camera. Return to
FocusBlocker and read the p50/p90. Under 150 ms p90 is a pass. Do this on stock
Android *and* on a Xiaomi or Samsung.

**Spike 2 — the overlay.** With a session running, open a blocked app. Check
that: the overlay appears before you can read anything underneath; back does
nothing; recents does not get you in; rotating the screen does not break it.
Note whether the blocked app's content is ever visible for a frame or two.

**Spike 4 — survival.** This is the one that actually decides the project.

1. Start a 60-minute session and reboot the phone.
2. After boot, before unlocking anything else, open FocusBlocker. The event log
   should show `BOOT #n completed — session …s left via WALL_CLOCK_AFTER_REBOOT`.
3. Open a blocked app. It must still be blocked.
4. Start a fresh 60-minute session. Leave the phone untouched overnight, then
   try a blocked app in the morning without opening FocusBlocker first. If the
   accessibility service was killed, this is where you find out — and the event
   log will show the gap.
5. Change the device clock forward by an hour mid-session. The session must not
   shorten, and the app should report the skew.

Repeat step 4 on every OEM you can get hold of. The answer differs per ROM and
that variance is the real risk in this whole category.

## What is verified and what is not

- `SessionMath` has real unit tests (10 cases, including both clock-tampering
  directions, a missed boot broadcast, and a reboot whose uptime overtook the
  previous one). They pass.
- Everything outside the Compose UI has been typechecked against stub platform
  APIs.
- **The Compose UI has never been compiled.** It parses, but the Android SDK was
  not available where this was written, so expect to fix an import or a
  deprecated signature on first sync. Nothing structural.

## Design notes worth keeping

- **The accessibility service is the engine, not a foreground service.** It is
  exempt from background execution limits and the system re-binds it after
  reboot. That is the whole reason to accept the Play Store risk described in
  `PLAN.md` §6.
- **The overlay uses `TYPE_ACCESSIBILITY_OVERLAY`**, which an accessibility
  service may create without `SYSTEM_ALERT_WINDOW` — one fewer frightening
  permission during onboarding.
- **The overlay is plain Views, not Compose.** Hosting Compose in a
  service-owned window means supplying your own lifecycle, saved-state and
  recomposer owners. Not worth it for two text views and two buttons.
- **`PolicyEngine.isBlocked` is a volatile read plus a hash lookup.** It runs on
  every window change on the device. Everything expensive happens in `refresh()`,
  triggered by a `SharedPreferences` listener. Keep it that way.
- **Two clocks, and a boot counter to tell them apart.** See the comment at the
  top of `SessionMath.kt`. The obvious reboot test — "did `startWall -
  startElapsed` change?" — is wrong, because moving the wall clock shifts that
  estimate exactly as a reboot does, so a clock change reads as a reboot and ends
  the session early. Hence the counter.
- **`NEVER_BLOCK` exists for a reason.** Dialer, emergency, system UI. Settings
  is also exempt *for now* so a spike cannot lock you out of your own phone;
  closing that escape hatch is Phase 3 work, not spike work.

## Known holes (on purpose)

- The overlay has a **Dismiss (spike only)** button. A shipping build replaces it
  with the friction ladder.
- A reboot *plus* a clock change ends a session early. Closing that needs
  `DevicePolicyManager`; see `PLAN.md` §2.4.
- Nothing stops the user disabling the accessibility service or force-stopping
  the app. That is Phase 3 (`PLAN.md` §5).
- `TYPE_WINDOW_STATE_CHANGED` also fires for dialogs and IMEs. The debounce and
  exempt list cover the common cases; a real build needs a tighter filter.
