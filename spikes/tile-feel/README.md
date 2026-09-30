# Spike #2 — Tile feel

**Question:** can a Quick Settings tile feel as good as iOS Control Center on the two
devices we actually ship to — Xiaomi 17T Pro (HyperOS 3.x) and OnePlus Nord 3
(OxygenOS 16)?

This is a standalone diagnostic app (`be.nealgysemans.tilespike`, own Gradle wrapper, no
`INTERNET` permission). It does not share code with `app/`. It exists to produce the
numbers and yes/no answers in the results table below, on device, by hand.

Three things are being measured:

1. **Latency** — tap → tile visibly flips, and tap → the real zen rule is confirmed.
2. **Dialog picker** — is `TileService.showDialog()` a viable mode picker, and is the
   Compose route worth its plumbing?
3. **Add-tile flow** — does `StatusBarManager.requestAddTileService` work on HyperOS at
   all, and what does the per-mode-tile pattern do to the QS edit list?

---

## Build & install

```sh
cd spikes/tile-feel
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
echo "sdk.dir=/Users/nealgysemans/Library/Android/sdk" > local.properties   # gitignored
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s FocusTile:I SecondTile:I ZenController:W
```

Toolchain: AGP 9.4.0 + Gradle 9.7.1 + JDK 25 (Android Studio JBR) + Compose BOM
2026.09.00. `compileSdk = 37`, `minSdk = 35`, `targetSdk = 36`.

Two toolchain notes that cost time and will cost it again:

- **AGP 9 has built-in Kotlin.** Applying `org.jetbrains.kotlin.android` is now a hard
  error. Only `org.jetbrains.kotlin.plugin.compose` is applied, and its version must
  match the KGP that AGP bundles (AGP 9.4.0 → 2.2.10).
- **`compileSdk` must be 37, not 36.** Current androidx (core-ktx 1.19.1, Compose BOM
  2026.09.00, lifecycle 2.11.0) fails `checkDebugAarMetadata` against 36.

---

## What the app contains

| Piece | Class | Why it exists |
|---|---|---|
| Primary tile | `FocusTileService` | ACTIVE_TILE + TOGGLEABLE_TILE, label `Focus` (5 chars, limit is 18). Four switchable tap behaviours. |
| Second tile | `SecondTileService` | `android:enabled="false"`; toggled via `setComponentEnabledSetting` to probe per-mode tiles. |
| Long-press target | `TilePrefsActivity` | Exported, `QS_TILE_PREFERENCES`. A *fast mode picker over a dimmed background*, not a settings screen. |
| Classic dialog | `ClassicModePicker` | Implementation (a): inflated `LinearLayout`, ~30 lines. |
| Compose dialog | `ComposeModePickerDialog` | Implementation (b): `ComposeView` + hand-wired `LifecycleOwner` / `ViewModelStoreOwner` / `SavedStateRegistryOwner`, ~70 lines. |
| Activity launch | `LaunchedFromTileActivity` | Target of `startActivityAndCollapse(PendingIntent)`; reports tap → onCreate. |
| Zen rule | `ZenController` | Real `AutomaticZenRule` via `AutomaticZenRule.Builder`, toggled with `Condition(..., SOURCE_USER_ACTION)`. |
| Rule config screen | `ZenRuleConfigActivity` | Exported `AUTOMATIC_ZEN_RULE` target; the platform requires a `configurationActivity`. |
| Control panel | `MainActivity` | Behaviour switch, add-tile button, DND access, results table, event log. |

### How the latency numbers are produced

`onClick()` is kept to volatile-field reads plus the `Tile` mutation, in this order:

1. `t0` — `onClick()` entry (`SystemClock.elapsedRealtimeNanos()`).
2. Optimistic flip: `state` + `subtitle` + `stateDescription` + `icon`, then `updateTile()`.
   Icons are pre-created in `onCreate()` so there is no allocation on the hot path.
3. `t1` — immediately after `updateTile()` returns. **`t1 − t0` = tap-to-tile-flip.**
4. Everything else (DataStore write, `setAutomaticZenRuleState`, confirmation) runs on
   `Dispatchers.IO` afterwards, where it cannot inflate what the user feels.
5. `t2` — `getAutomaticZenRuleState()` first reports the requested state (polled every
   2 ms, 3 s budget). **`t2 − t0` = tap-to-rule-confirmed.**
6. In parallel, the arrival of `ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED` is timed
   (1.5 s budget) as an independent confirmation channel.

The tile never reads DataStore in `onClick()`. It reads a process-wide volatile
`StateCache`, mirrored from DataStore by `SpikeApp` and force-warmed in
`onStartListening()` (which always precedes `onClick()`).

Two honesty guards on the confirmation number:

- If the rule was **already** in the target state before the toggle, the row is tagged
  `pre-match` and excluded from the tap-to-rule-confirmed median — otherwise it would
  read as ~0 ms and flatter the result.
- With no DND access or no rule, the tile still demos; the row records
  `no-dnd-access` / `no-rule` and `n/a` instead of a fake number.

Each row is written up to 1.5 s after the tap (it waits for the broadcast), so the
on-screen list lags the tapping slightly. That is expected.

---

## Manual protocol

Run the whole thing once per device. Fill the table as you go.

### 0 · Setup

1. Install, open the app.
2. **3 · DND access** → *Grant DND access* → enable for "Tile Feel Spike" → back.
   Confirm the panel now says `DND access granted: yes`.
3. *Create rule*. Confirm `rule id` becomes non-empty and `rule state` reads `FALSE`.
   - If creation fails, note the exception in the results table and continue — the rest
     of the protocol still works, latency rows will just say `no-rule`.
4. Check the rule is visible in system Settings → Sound → Do Not Disturb, and that
   tapping it opens `ZenRuleConfigActivity`. (Row: *rule visible in DND settings*.)

### 1 · Tile appears in the QS edit list

1. Pull down the shade → edit / customise the tiles.
2. **HyperOS 3.x:** Control Center → pencil / *Edit* → scroll the *available* tiles list
   **all the way to the end**. Third-party tiles are appended after every MIUI tile, and
   the list does not scroll-hint, so it looks like they are absent. This is the
   documented manual add path and the fallback whenever `requestAddTileService` does
   nothing.
3. **OxygenOS 16:** shade → edit → third-party tiles are in the same drawer as system
   ones, usually near the bottom.
4. Record whether `Focus` is present. Place it somewhere reachable with one thumb.

### 2 · requestAddTileService

1. Remove the tile again if you placed it.
2. In the app: **2 · Add the tile** → *requestAddTileService("Focus")*.
3. Record the dialog behaviour and the result code from the event log
   (`requestAddTileService result=N (NAME)`).
4. Repeat twice with *deny* to see whether the platform starts auto-denying
   (`TILE_NOT_ADDED` with no dialog shown).
5. Then accept, and confirm the log shows `FocusTile onTileAdded`.

### 3 · Tap-to-tile-flip (median of 10)

1. **1 · Tile behaviour** → *Tap = toggle*.
2. Pull the shade down, tap the tile **10 times**, watching the tile itself. Each tap
   advances Off → Work → Sleep → Personal → Off, so icon, subtitle and state all change.
3. Note subjectively: does the tile change **within the same frame as the touch release**,
   or is there a visible beat? (That is the actual iOS-parity question; the number is the
   evidence.)
4. Back in the app, read **4 · Latency** → `tap -> tile flip` → median and max.

### 4 · Tap-to-rule-confirmed (median of 10)

Same 10 taps produce this row. Read `tap -> rule confirmed` → median/max, and note how
many rows carry `·bcast` (broadcast arrived inside 1.5 s) versus none.

> If every row says `pre-match`, the rule is not actually changing state — investigate
> before trusting anything else on the device.

### 5 · showDialog + shade collapse

1. **1 · Tile behaviour** → *Tap = dialog (classic View)*.
2. Tap the tile. Record: does a dialog appear? Does the shade collapse behind it?
3. Tap a mode. Record: does the tile behind the (now dismissed) dialog show the new
   mode, subtitle included?
4. Switch to *Tap = dialog (ComposeView)* and repeat. Record whether it renders at all,
   whether it is visibly slower to appear, and whether the theme matches the OEM look.
   Event log line `showDialog(...): toRunnable=… construct=… show=… total=…` gives the
   construction cost of each implementation.

### 6 · Dialog under the keyguard

1. Set a real PIN/pattern if there is none.
2. Lock the device. Pull the shade down **from the lock screen**. Tap the Focus tile.
3. Record what happens: unlock prompt first then dialog (correct), dialog invisible
   behind the keyguard (the failure mode `unlockAndRun` is guarding against), nothing at
   all, or something OEM-specific.
4. The event log records which branch ran:
   `tile tap while locked+secure -> unlockAndRun` or `tile tap unlocked … -> showDialog now`.

### 7 · Long-press opens the picker

1. Long-press the Focus tile in QS.
2. Record: does `TilePrefsActivity` open, how long the transition feels, and whether the
   shade collapses first (AOSP) or animates differently (HyperOS Control Center).
3. Pick a mode; confirm the tile reflects it next time the shade opens
   (`TileService.requestListeningState` path).

### 8 · Subtitle

Record whether the subtitle line (`Work` / `Sleep` / `Personal` / `Off`) is rendered
under the label on the tile. HyperOS Control Center tiles have their own layout and may
drop it.

### 9 · Second tile enable/disable

1. **5 · Second tile** → *Enable 2nd tile*.
2. Open the QS edit list **without rebooting**. Is `Focus: Sleep` there now?
3. Place it. Tap it (it only flips a local flag). Confirm it works.
4. Back in the app → *Disable 2nd tile*. Re-open QS. Did the placed tile disappear? Did
   the layout close the gap?
5. *Enable 2nd tile* again. Re-open the QS edit list. Is it back **in its old position**,
   or dumped at the end / absent until reboot?

This is the decisive experiment for "one tile per mode": if positions are lost on every
enable/disable, dynamic per-mode tiles are not shippable and the single-tile + picker
design wins by default.

### 10 · startActivityAndCollapse

**1 · Tile behaviour** → *Tap = activity* → tap the tile. Record tap → onCreate ms from
the launched screen, and whether the shade collapsed. Compare against the dialog paths.

---

## Results

Fill in on device. `—` = not applicable, `?` = could not determine.

| # | Check | 17T Pro (HyperOS 3.x) | Nord 3 (OxygenOS 16) |
|---|---|---|---|
| 1 | Tile appears in QS edit list | yes | |
| 2 | `requestAddTileService` result code | dialog shown, tile added (user accepted) | |
| 2b | Auto-deny after repeated denials? | ? (not exercised) | |
| 3 | Tap → tile flip, median of 10 (ms) | 2.0 (n=36) | |
| 3b | Tap → tile flip, max of 10 (ms) | 3.9 | |
| 3c | Flip perceptually instant? | yes | |
| 4 | Tap → rule confirmed, median of 10 (ms) | 35.8 (n=4 true transitions) | |
| 4b | Tap → rule confirmed, max of 10 (ms) | 45.2 | |
| 4c | Zen-status broadcast arrived < 1.5 s? | yes — median 50.7 ms, max 73.4 | |
| 5 | `showDialog` works + collapses shade (classic) | yes — dialog centered on screen | |
| 5b | `showDialog` works (ComposeView) | yes — renders and works; styling better than classic (classic shows unused space above title) | |
| 5c | Dialog styling matches OEM tiles? | acceptable; Compose variant preferred | |
| 6 | Dialog under keyguard behaviour | ? (not exercised) | |
| 7 | Long-press opens picker activity | yes (opens bottom-anchored) | |
| 7b | Long-press transition feels fast? | yes | |
| 8 | Subtitle shown on tile | icon-only by default; "Focus" label appears when control-center labels are enabled | |
| 9 | 2nd tile appears in edit list when enabled (no reboot) | ? (not exercised) | |
| 9b | Placed 2nd tile disappears when disabled | ? | |
| 9c | Position retained after re-enable | ? | |
| 10 | `startActivityAndCollapse` tap → onCreate (ms) | ? (not exercised) | |
| — | DND access grantable | yes (per-app grant; second app granted without issue) | |
| — | Zen rule creation succeeded | yes | |
| — | Rule visible in DND settings + config activity opens | see zen spike: Modes list hidden, per-rule editor deep-links | |

### Device notes

**17T Pro (HyperOS 3.x)**

> Session 2026-09-30, HyperOS 3.0 / Android 16 (BP2A.250605.031.A3).
> Verdict: tile feel is a solved problem on HyperOS — flip 2 ms, real rule
> confirmed ~36 ms, broadcasts < 75 ms. Compose dialog is the keeper.
> Sideload quirks (onboarding-relevant): `adb install` blocked with
> INSTALL_FAILED_USER_RESTRICTED even with "Install via USB" on — `adb push`
> + `pm install` from the device shell works; `adb shell input` requires the
> separate "USB debugging (Security settings)" toggle. Tiles render as small
> round icons (labels off by default) — per-mode glyphs must carry meaning;
> users wanting an iOS-sized Focus button need the future home-screen widget,
> not the tile. Rows marked "not exercised": auto-deny, keyguard dialog,
> second-tile toggling, startActivityAndCollapse timing — rerun if they
> become decision-relevant.

**Nord 3 (OxygenOS 16)**

>

---

## Verdict: classic View dialog vs ComposeView dialog

Filled in from code before device testing; confirm on device and amend.

**The classic View dialog is the one to ship.** `ClassicModePicker` is ~30 lines: inflate
a layout, add rows, `showDialog()`. There is nothing in it that can fail differently on a
Service context than on an Activity context.

`ComposeModePickerDialog` needs, by hand:

- `LifecycleOwner` + a `LifecycleRegistry` pumped manually across `show()`/`dismiss()` —
  Compose will not compose without a lifecycle in the view tree, and a Service-hosted
  `Dialog` has no one to drive it.
- `SavedStateRegistryOwner` + `SavedStateRegistryController.performAttach()` /
  `performRestore(null)`, which must run while the registry is still `INITIALIZED`.
- `ViewModelStoreOwner` + manual `ViewModelStore.clear()` on dismiss, or it leaks.
- Three `setViewTree*Owner` calls on the `ComposeView`.

That is real surface area for a picker with three rows and no state. The upside — sharing
`ModePickerContent` with the long-press activity — is genuine but small, and the classic
dialog can be styled to match by leaning on `Theme.DeviceDefault.Dialog.Alert`, which is
*more* likely to match an OEM skin than a Material 3 surface is.

Keep the Compose path in the spike as evidence; do not carry the plumbing into `app/`.

---

## Known caveats of this spike

- `tap -> tile flip` measures until `updateTile()` **returns**, i.e. until the binder call
  to SystemUI is issued — not until SystemUI has drawn. The subjective judgement in
  row 3c is what covers the rest; if the two disagree, trust the eyes and say so.
- The three modes are fake and all drive a single zen rule. Single-active-mode semantics,
  `ZenPolicy` per mode and `ZenDeviceEffects` are Spike #1 / `app/` territory.
- Confirmation polls at 2 ms; the reported confirm time is therefore quantised to ~2 ms
  and slightly pessimistic.
- `getAutomaticZenRuleState` returning `FALSE` before any state was ever set is why the
  `pre-match` tag exists.
- Debug build, no R8. Real-world flip latency on a release build should be equal or
  better.
