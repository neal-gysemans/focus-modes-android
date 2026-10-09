# Focus Modes for Android

iPhone-style Focus modes built on Android's Do Not Disturb (`AutomaticZenRule` +
`ZenPolicy`). Android 15+, Kotlin + Compose, Room. Public repo, MIT, shipped as an
APK on GitHub Releases.

## Ground truth

- **`README.md` → "Key design invariants"** — ModeEngine reconciliation, one active
  mode, Room as truth and the system rule as cache. Read before touching `engine/`,
  `zen/` or `schedule/`.
- **`README.md` → "Build-config traps"** — the compileSdk / AGP / Kotlin plugin
  combination. Read before editing any Gradle file or version catalog entry.
- **`docs/feasibility-report.html`** — the research behind the API choices, HyperOS
  quirks and Play policy.
- **`docs/widget-ios-spec.md`** — the widget design.
- `spikes/` are standalone Gradle projects with their own wrappers; the gate below
  does not build them.

## The gate

Java is not on the shell PATH on this machine. Every Gradle command needs
`JAVA_HOME` in the same shell call, or it exits with "Unable to locate a Java
Runtime" before building anything:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug testDebugUnitTest
```

There is no CI, so this local run is the only check. The JVM unit tests
(`app/src/test`) take seconds.

## Verifying a change

Before writing code, name the test that will prove it, and watch it go red without
the change before you count it green.

The unit tests cover the pure logic: schedule windows, ModeEngine reconciliation
against fakes, zen mapping, tile taps, widget grid. They cannot see what Android
does with a zen rule — DND enforcement, tile and widget rendering, alarm timing,
HyperOS battery behaviour. A change there is verified only on a device, so report
it as "unit tests green, not verified on a device" until someone has.
