# Spike #1 — Do third-party `AutomaticZenRule`s actually work?

**Existential spike.** The whole app rests on one assumption: that a third-party app
can create an `AutomaticZenRule`, toggle it, and have the OS honour its `ZenPolicy`
and `ZenDeviceEffects`. AOSP says yes. Xiaomi HyperOS 3 replaces large parts of the
notification and DND stack, so this has to be proven on metal before anything else
gets built.

"Zen Spike" is a standalone, single-screen diagnostic app: one button per API call,
a live status header, and a timestamped event log of every call, result, and
broadcast. It has **no `INTERNET` permission** — same constraint as the real app.

## Build and install

No system Gradle/JDK on this machine; use the Android Studio JBR.

```sh
cd spikes/zen-hyperos
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` (gitignored) needs `sdk.dir=$HOME/Library/Android/sdk`.

Toolchain: Gradle 9.7.1 · AGP 9.4.0 (built-in Kotlin, no separate KGP) ·
Kotlin 2.4.10 · Compose BOM 2026.09.00 · minSdk 35 · targetSdk 36 · compileSdk 37.

Watch the log from the host as well — everything on screen is mirrored to logcat:

```sh
adb logcat -s ZenSpike:I
```

## What the app does

**Status header** (polled every 1.5 s, because whether HyperOS even *sends* the zen
broadcasts is one of the open questions):

- `isNotificationPolicyAccessGranted()`
- `areAutomaticZenRulesUserManaged()` — proxy for "does this OS have the Modes UI"
- whether `Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS` resolves
- whether `Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS` resolves
- our rule's id, name, enabled flag, `getAutomaticZenRuleState()`, the device
  effects the OS actually stored, `getCurrentInterruptionFilter()`, the count of
  other rules on the device, and the full `getConsolidatedNotificationPolicy()`

**Buttons**

| Button | Call under test |
|---|---|
| Grant DND access | `Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS` |
| Open Modes UI | `Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS` |
| Create / Update rule | `addAutomaticZenRule` / `updateAutomaticZenRule` |
| Activate: USER_ACTION | `setAutomaticZenRuleState(id, Condition(..., STATE_TRUE, SOURCE_USER_ACTION))` |
| Activate: SCHEDULE | same, with `SOURCE_SCHEDULE` |
| Deactivate | same, with `STATE_FALSE` + `SOURCE_USER_ACTION` |
| Read back | `getAutomaticZenRule` + `getAutomaticZenRuleState` + `getConsolidatedNotificationPolicy` |
| Delete rule | `removeAutomaticZenRule` |
| Notify in 5s (normal) | ordinary channel, posted after 5 s so the screen can be locked |
| Notify in 5s (bypass DND) | channel created with `setBypassDnd(true)` — previews the relay path |

**The rule under test** (`ZenController.buildRule`)

- owner: `RuleConfigActivity` via `setConfigurationActivity` (exported, with the
  `android.app.action.AUTOMATIC_ZEN_RULE` filter and `ruleType` meta-data —
  `addAutomaticZenRule` throws without an owner)
- `setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY)`, `setType(TYPE_OTHER)`,
  `setManualInvocationAllowed(true)`, `setTriggerDescription("Spike test")`
- `ZenPolicy`: `allowCalls(STARRED)`, `allowMessages(STARRED)`,
  `allowRepeatCallers(true)`, `allowPriorityChannels(true)`
- `ZenDeviceEffects`: grayscale + dim wallpaper + night mode

## Manual test protocol

Run the whole sequence on each device and fill the table. Every step's outcome is
also in the on-screen log — copy it out with `adb logcat -s ZenSpike:I -d` and
attach it to `docs/` if anything is surprising.

> The **"Modes UI present"** and **"DND-access screen reachable"** rows are read via
> `resolveActivity`, which only works because the manifest declares both Settings
> actions in `<queries>`. Under package-visibility filtering (Android 11+) an
> undeclared action always resolves to null — so if either row ever reads `no` on
> every device at once, check that declaration before believing the result.

0. Fresh install. Note what the status header says **before** granting anything.
1. **Grant DND access** → return to the app. Header should flip to `YES`.
2. **Create rule** → log shows `addAutomaticZenRule -> id=…`, then a read-back.
   Compare the read-back `effects:` and `policy:` lines against what was sent —
   any field that comes back `unset` or `null` is an OEM drop.
3. **Activate: USER_ACTION** → status becomes `STATE_TRUE`,
   `currentInterruptionFilter` becomes `PRIORITY`. Look for the status-bar DND icon
   and check whether the rule shows up in the system DND/Modes screen.
4. With the rule active, observe the three device effects: is the screen
   **grayscale**, is the system in **night mode**, is the **wallpaper dimmed**
   (check the home screen / lock screen, not just this app).
5. **Notify in 5s (normal)** → lock the screen immediately. Nothing should sound or
   peek. Unlock: the notification should be in the shade, silent.
6. **Notify in 5s (bypass DND)** → lock the screen. This one *should* alert. If the
   log line says `channel canBypassDnd=false`, the OS refused the flag — record it.
7. **Snooze semantics.** With the rule active, turn the mode **off from the system
   UI** (not from the app — this sets the manual-snooze override). Then press
   **Activate: SCHEDULE**: the state should stay `STATE_FALSE` and the log should
   flag the mismatch. Then press **Activate: USER_ACTION**: it should punch through
   to `STATE_TRUE`.
8. **Deactivate**, then **Read back**, then **Delete rule**. Confirm the rule
   disappears from the system UI too.
9. Watch the log for `AUTOMATIC_ZEN_RULE_STATUS_CHANGED` throughout — note whether
   the broadcast arrives at all, and with which status.
10. Reboot with the rule active, reopen the app, **Read back**: did the rule and
    its state survive? (HyperOS aggressive-kill check.)

### Results

Leave cells blank until run on-device. Use `yes` / `no` / `partial`, and put the
detail in the notes column.

| # | Check | 17T Pro (A16 / HyperOS 3) | 17T Pro (A17 / HyperOS 3.3) | Nord 3 (OxygenOS 16) | Notes |
|---|---|---|---|---|---|
| 1 | Rule creation succeeds (`addAutomaticZenRule` returns an id) | yes | | | A16: read-back matched submission field-for-field — zero drift, all 3 effects + full policy stored |
| 2 | Activation silences a test notification (normal channel) | yes | | | A16: silent into shade; bypass-DND channel sounded through (relay precondition proven); `canBypassDnd=true` accepted after DND-access grant + app restart |
| 3 | Grayscale applies | yes | | | |
| 4 | Night mode applies | yes | | | A16: verified from light theme → flipped dark on activation |
| 5 | Dim wallpaper applies | yes | | | |
| 6 | `SOURCE_SCHEDULE` ignored after manual off (snooze semantics) | yes | | | A16: two attempts both refused (`STATE_FALSE` read back), matching AOSP docs |
| 7 | `SOURCE_USER_ACTION` punches through manual off | yes | | | A16: `STATE_TRUE` + ACTIVATED broadcast in ~30 ms |
| 8 | Modes UI present (`areAutomaticZenRulesUserManaged` / settings action resolves) | partial | | | A16: `userManaged=true`, both AOSP activities exist and the per-rule editor deep-links fine (worked as system-side off surface) — but Settings navigation/search never surfaces Modes; app deep link is the only route |
| 9 | DND-access screen reachable | yes | | | A16: grant screen opened directly, app listed, granted without workarounds |

Supplementary observations worth writing down in the notes column:

- Did the read-back match what was written, field for field? Which fields drifted?
- Did `AUTOMATIC_ZEN_RULE_STATUS_CHANGED` arrive, and with which status ints?
- Did `setBypassDnd(true)` survive on the channel (`canBypassDnd` in the log)?
- Does the rule appear in the OEM's own DND/Modes screen, and can the user edit it
  there? Does editing it there break later `updateAutomaticZenRule` calls?
- Did the rule and its active state survive a reboot / an aggressive app kill?
- How long between pressing a button and the status bar reflecting it?

### Verdict

Fill in once the table is complete.

- **Go** — third-party zen rules are honoured well enough on all three targets.
- **Go with caveats** — list which effects or semantics need a fallback.
- **No-go** — say which row killed it.

## Implementation notes / deviations

- **`compileSdk = 37`, not 36.** The current androidx artifacts (core-ktx 1.19.1,
  Compose BOM 2026.09.00, lifecycle 2.11.0) refuse to be compiled against anything
  below 37. `targetSdk` and `minSdk` are as specified (36 / 35).
- **No `org.jetbrains.kotlin.android` plugin.** AGP 9 ships built-in Kotlin support
  and rejects the standalone plugin outright.
- **The `ZenPolicy` carries explicit denials** beyond the four calls in the brief
  (`allowConversations(NONE)`, `allowReminders(false)`, `allowEvents(false)`,
  `allowSystem(false)`, `allowAlarms(true)`, `allowMedia(true)`). A fresh
  `ZenPolicy.Builder` leaves every other field `unset`, which means "inherit the
  device default" — that would make step 5 ("did activation silence the
  notification?") depend on the device's existing DND config instead of on our rule.
- **Status is polled, not broadcast-driven.** Broadcasts are logged, but the header
  refreshes on a 1.5 s timer so the app still reports correctly on an OS that never
  sends them.
- **The 5 s notification delay uses a process-scoped coroutine**, not `AlarmManager`
  — no exact-alarm permission needed, and the process comfortably survives 5 s of
  being backgrounded.
- Broadcast receiver is registered `RECEIVER_EXPORTED`. These are protected system
  broadcasts, so `NOT_EXPORTED` should also work, but `EXPORTED` is the variant
  guaranteed to receive them under targetSdk 34+ rules.
