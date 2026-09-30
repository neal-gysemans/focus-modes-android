# Focus Modes for Android

Recreates iPhone Focus modes as a third-party Android app: user-defined modes
(Work, Sleep, Personal) backed by `AutomaticZenRule` + `ZenPolicy` +
`ZenDeviceEffects`, with a Quick Settings tile as the primary surface.

- **Stack:** Kotlin, Jetpack Compose, minSdk 35, targetSdk 36, no INTERNET permission
- **Test devices:** Xiaomi 17T Pro (HyperOS 3, Android 16/17), OnePlus Nord 3 (OxygenOS 16)
- **Feasibility study:** [docs/feasibility-report.html](docs/feasibility-report.html) —
  API verification, HyperOS quirks, Play policy, architecture, MVP, spike plan.

## Repo layout

| Path | Purpose |
|---|---|
| `app/` (+ root Gradle) | The app skeleton — ModeEngine, zen adapter, tile, data layer |
| `spikes/zen-hyperos/` | Spike #1: standalone diagnostic app — do third-party zen rules work on HyperOS? |
| `spikes/tile-feel/` | Spike #2: standalone diagnostic app — tile latency, dialog picker, add-tile flow |
| `docs/` | Feasibility report and spike findings |

Spikes are standalone Gradle projects (own wrapper) so they build independently
of the main app.

## Building

No system Gradle/JDK on this machine; use the Android Studio JBR:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

`local.properties` (gitignored) needs `sdk.dir=/Users/nealgysemans/Library/Android/sdk`.

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
