# Focus Modes for Android

iPhone-style Focus modes for Android. Make modes such as Work, Sleep and Personal,
choose who can reach you in each one, and switch between them from a Quick Settings
tile, a home-screen widget, or a schedule.

Each mode is a real Android Do Not Disturb rule (`AutomaticZenRule` + `ZenPolicy` +
`ZenDeviceEffects`). That means the system enforces who gets through, and the app
never has to read your notifications.

## Features

- **Modes with their own rules.** Each mode sets whose calls and messages get
  through (starred contacts, all contacts, anyone or no one) and whether repeat
  callers are allowed.
- **Device effects per mode.** Greyscale, dimmed wallpaper and dark theme.
- **Quick Settings tile.** Tap to toggle and long-press for the mode picker. Tapping
  can turn on the last-used mode, ask which mode, or cycle through modes.
- **Home-screen widget.** Modelled on the iPhone Focus control.
- **Schedules.** A mode turns on and off by time and weekday, overnight windows
  included. If you turn a scheduled mode off by hand, it stays off until you turn
  it back on or the next scheduled period starts.
- **One mode at a time.** Turning one on turns the others off, so you always know
  which rules apply.

## Privacy

Focus Modes has **no internet permission**, so it cannot send anything anywhere.
It has no accounts, analytics or ads. Your modes and schedules stay on your phone.
It doesn't read your contacts or notifications either: "starred contacts" are
matched by Android itself.

## Install

Requires **Android 15 or newer**.

1. Download the latest `focus-modes-*.apk` from
   [Releases](https://github.com/neal-gysemans/focus-modes-android/releases/latest).
2. Open it on your phone and allow installing from your browser or file manager
   when Android asks.
3. Open Focus Modes and grant the access it asks for:
   - **Do Not Disturb access**, so it can manage its modes. Required.
   - **Alarms & reminders**, so schedules fire on the minute.
   - **Notifications**, for the "mode is on" notification with its *Turn off* button.
4. Add the **Focus** tile to Quick Settings from the app, or by editing your tiles.

**Xiaomi / HyperOS, and other phones with aggressive battery savers:** turn on
*Autostart* and set battery usage to *No restrictions* for Focus Modes, or schedules
may fire late. Signed releases update in place, so install new versions over the old
one.

## Repo layout

| Path | Purpose |
|---|---|
| `app/` (+ root Gradle) | The app: ModeEngine, zen adapter, tile, widget, schedules, data layer |
| `spikes/zen-hyperos/` | Spike #1: standalone diagnostic app. Do third-party zen rules work on HyperOS? |
| `spikes/tile-feel/` | Spike #2: standalone diagnostic app. Tile latency, dialog picker, add-tile flow |
| `docs/` | Feasibility report and widget spec |

The [feasibility study](docs/feasibility-report.html) covers the research behind the
app: API verification, HyperOS quirks, Play policy, architecture and the MVP scope.

The spikes are standalone Gradle projects with their own wrappers, so they build
independently of the main app.

## Building

Needs a JDK 17+ (Android Studio's bundled JBR works) and the Android SDK.

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"  # macOS example
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties                        # gitignored
./gradlew assembleDebug      # debug build
./gradlew testDebugUnitTest  # JVM unit tests
./gradlew assembleRelease    # minified release; unsigned unless signing is configured
```

### Release signing

Release signing is read from your **user-level** `~/.gradle/gradle.properties`.
It is never read from this repo, and no key or password is committed:

```properties
focusModes.storeFile=/absolute/path/to/release.jks
focusModes.storePassword=...
focusModes.keyAlias=...
focusModes.keyPassword=...
```

Without these properties, `assembleRelease` produces an unsigned APK. Keep the keystore
backed up. An APK signed with a different key can't update an installed copy; users
would have to uninstall it and lose their modes.

Build-config traps (learned the hard way, do not regress):

- **compileSdk is 37** (current androidx artifacts hard-fail AAR-metadata checks
  against 36); minSdk 35 / targetSdk 36 are the product decision and stay.
- **AGP 9 ships built-in Kotlin** and rejects the standalone
  `org.jetbrains.kotlin.android` plugin — apply only
  `org.jetbrains.kotlin.plugin.compose`. Working combo: AGP 9.4.0 + Gradle 9.7.1.
- Probing Settings screens with `resolveActivity` requires the `<queries>`
  declarations already in the manifests (package-visibility filtering otherwise
  returns null and probes false-negative).

## Key design invariants (from the feasibility study)

- Every state change funnels through **ModeEngine** — a reconciliation loop that
  recomputes desired mode on every wake and converges idempotently. Nothing
  toggles a zen rule directly.
- Single-active-mode invariant: activating a mode deactivates the rest.
- Every `setAutomaticZenRuleState` call declares its `Condition` source
  (`SOURCE_USER_ACTION` for user toggles, `SOURCE_SCHEDULE` for schedules).
- Room is the source of truth; the system rule is a cache (user edits can
  silently freeze app updates to a rule).
- Allowed people = starred contacts, enforced by `ZenPolicy` (system-side),
  never by the notification relay.

## License

[MIT](LICENSE)
