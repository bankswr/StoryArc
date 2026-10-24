# Build environment

How to build StoryArc on this machine, and the toolchain constraints behind the pinned versions. Recorded 2026-10-24.

## Building

- The system Java (`/usr/lib/jvm/java-21-openjdk-amd64`) is a JRE with no `javac`, so Gradle cannot compile with it. Use Android Studio's bundled JDK:
  ```sh
  export JAVA_HOME=~/android-studio/jbr
  ./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
  ./gradlew test                 # all JVM unit tests (:board and :app/Robolectric)
  ```
- Android SDK: `~/Android/Sdk` (platform android-36 only). `local.properties` (gitignored) sets `sdk.dir`.
- Don't use `jvmToolchain(...)` in build scripts: no JDK 17 is installed and toolchain download isn't configured. Modules target JVM 17 bytecode through `compilerOptions.jvmTarget` / `compileOptions`.
- The user installs the debug APK themselves; there is no emulator workflow.

## Version constraints

- AGP 8.13.0 + Gradle 8.14.3 (both already cached locally). AGP 8.13 supports compileSdk up to 36.
- AndroidX releases from mid-2026 onward (core 1.19+, lifecycle 2.10+/2.11, navigation 2.10+) require compileSdk 37 **and** AGP 9.1+. They fail the AAR metadata check, and a newer Compose BOM can pull them in transitively. Current pins: core 1.18.0, lifecycle 2.9.4, navigation 2.9.8, activity 1.12.2, Compose BOM 2026.03.01.
- Moving to AGP 9 means installing platform 37 and migrating to AGP 9's built-in Kotlin support (the `org.jetbrains.kotlin.android` plugin is no longer applied). Treat it as a separate, deliberate upgrade.

## Visual checks without a device

- `BoardScreenshotTest` (Robolectric with native graphics) renders screens to `app/build/screenshots/*.png`; view them to check layout. Run it with `./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'`.
- `captureToImage()` times out under Robolectric (it can't force a redraw), so the test draws `window.decorView` into a `Bitmap` directly.
- Board content outside the viewport isn't composed, so wait on text that is visible in the configured screen size.

## Gesture tests (Robolectric + Compose)

- All events in one `performTouchInput {}` block are injected together, so no clock time passes inside it. To trigger a long press: `performTouchInput { down(p) }`, then `mainClock.advanceTimeBy(800)`, then a separate `performTouchInput` for the moves.
- `delay()` inside a `LaunchedEffect` doesn't fire under `mainClock.advanceTimeBy` or `ShadowLooper.idleMainLooper` here. Logic that must be testable measures elapsed time from pointer event `uptimeMillis` (`advanceEventTime` drives it); the marker dwell does both.
- Board writes go Room (background) → main looper, so assert with retries (`BoardDragTest.eventually`), not right after `waitForIdle()`.
- A `withFrameNanos` loop that keeps running prevents Compose from going idle, and test synchronisation then times out. Run frame loops only while they're doing work (e.g. auto-scroll only near an edge that can scroll).
- Dialogs use `AppAlertDialog` (`ui/common/Common.kt`), which sets `usePlatformDefaultWidth = false`. With the platform default width, a landscape dialog's window and content re-measured each other between two widths forever, and the app never went idle under Robolectric. Diagnose "Compose did not get idle" by sampling the `SDK 35 Main Thread` with `jstack`: repeated measure/text-layout frames with no recomposition point to a layout loop.
- Navigate only on the main thread. After a Room suspend call, a coroutine can resume on a Room thread in tests, so wrap navigation in `withContext(Dispatchers.Main)`.
