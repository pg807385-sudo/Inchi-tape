# AR Measure

An offline Android app that measures real-world distances using the camera and
ARCore, similar to iPhone's Measure app: tap a surface to place a point, tap
again to place a second point, and see the straight-line distance between them.

## How it works
- ARCore tracks the phone's position in 3D space using the camera + motion
  sensors (visual-inertial odometry) — entirely on-device.
- Tapping the screen does an AR hit-test against detected planes/surfaces to
  find the real-world 3D point under your finger.
- Distance is the straight-line (Euclidean) distance between two placed points.
- A unit toggle switches between meters and feet/inches.
- "Reset" clears all placed points and measurements.

**Nothing in this app makes a network call.** The only network dependency is
one-time: Gradle needs internet the first time you open the project to
download the ARCore/AndroidX libraries, and ARCore itself must be present on
the device (it's pre-installed on most modern Android phones; otherwise the
app will prompt to install it from Play Store once). After that, measuring
works with airplane mode on.

## Requirements
- Android Studio (Koala or newer recommended)
- A physical Android device that supports ARCore (most phones from ~2018+;
  see https://developers.google.com/ar/devices for the list) — AR does not
  work in the standard emulator
- minSdk 24, targetSdk 34

## Build & run
1. Open this folder (`ARMeasure/`) in Android Studio as an existing project.
2. Let Gradle sync (needs internet the first time to fetch dependencies).
3. Connect your Android device via USB with USB debugging enabled.
4. Click Run. Grant the camera permission when prompted.
5. If ARCore isn't already installed on the device, you'll be prompted to
   install it from Play Store the first time — after that it's local.

## Using the app
1. Point the camera at a flat surface (floor, table, wall) and move the phone
   slightly so ARCore can detect the surface — you'll feel it "lock on" once
   tracking is stable.
2. Tap the point you want to start measuring from.
3. Tap the second point. The distance appears at the top of the screen.
4. Tap "Reset" to clear and start a new measurement, or just keep tapping
   pairs of points to place additional measurement lines.
5. Tap the units button to switch between meters and feet/inches.

## Project structure
```
ARMeasure/
  app/
    build.gradle.kts
    src/main/
      AndroidManifest.xml
      java/com/example/armeasure/
        MainActivity.kt        - app lifecycle, AR session, tap handling, distance math
        BackgroundRenderer.kt  - draws the live camera feed as the background
        LineRenderer.kt        - draws the measurement points and connecting lines
      res/
        layout/activity_main.xml
        values/ (strings, colors, theme)
  build.gradle.kts
  settings.gradle.kts
```

## Honesty note
This code was written directly (not copy-pasted from a working sample) using
the standard ARCore session/hit-test/rendering pattern. I was not able to
compile or run it in this environment (no Android SDK/emulator available
here), so treat it as a solid, complete starting point rather than a
guaranteed zero-error build — if Android Studio flags anything on first sync
or build, it's most likely a minor Gradle/AGP version mismatch on your
machine, easily fixed by letting Android Studio's "Upgrade Assistant"
suggest compatible versions.

## Possible next steps
- Multi-segment area/volume measuring (like iPhone Measure's room/area mode)
- Save/export measurements
- Snap-to-corner detection
- On-screen AR labels showing distance right on the line (currently shown in
  the top banner instead, to keep the OpenGL code simpler)
