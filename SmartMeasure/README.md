# Smart Measure (non-AR)

A camera + tilt-angle measuring app that works on **any Android phone**,
including Android 9 and devices Google doesn't consider ARCore-compatible.
No "Google Play Services for AR" dependency at all.

## Why this version exists
The original AR version (using ARCore) hit "This app isn't compatible with
your device" because Google Play Services for AR refuses to install on some
phones/OS versions. This version drops ARCore entirely and uses a much older,
universally-supported technique instead.

## How it measures (the "clinometer" method)
This is the same trick surveyors and forestry workers have used for
centuries with a hand-held clinometer:

1. **Calibrate**: enter how high your phone is off the ground (measure once
   with a tape measure — e.g. holding it at chest height), hold the phone
   pointed exactly level at the horizon, and tap "Calibrate level." This
   zeroes out the accelerometer's angle reading so you get an accurate 0°.
2. **Distance-to-point mode**: aim the on-screen crosshair at a point on the
   floor and tap "Measure." Using your phone's height and the downward tilt
   angle, it calculates the horizontal distance via `height / tan(angle)`.
3. **Object-height mode**: aim at the base of something (a door, a wall, a
   tree) and tap "Set Base" to capture the horizontal distance, then aim at
   the top and tap "Set Top." It computes
   `height = phoneHeight + distance * tan(angleAboveLevel)`.

All angle data comes from the phone's built-in accelerometer + magnetometer
(present on virtually every Android device) via the standard Android sensor
APIs. The camera preview (via CameraX) is just there so you can see what
you're aiming at — it's not used for tracking or depth.

**Fully offline** — no ARCore, no Play Services for AR, no network calls at
runtime. Minimum SDK is API 21 (Android 5.0), so Android 9 and newer are
comfortably covered.

## Accuracy notes
- This is inherently less precise than true AR depth sensing — accuracy
  depends on how steady you hold the phone and how carefully you calibrate.
  Expect single-digit-percent error under normal use, more at very close or
  very far distances.
- Recalibrate (tap "Calibrate level" again) if you move to standing on
  different ground or change how you're holding the phone.
- I was not able to test this on a physical device from this environment —
  the sensor remap convention (`AXIS_X, AXIS_Z`) used to get pitch from the
  rear camera's pointing direction is the standard approach for this use
  case, but if you find the angle reads backwards on your device (e.g. tilts
  the wrong direction), the fix is a one-line sign flip in `onActionTapped()`
  and `onSensorChanged()` in `MainActivity.kt` — happy to patch that once you
  tell me what you observe.

## Build
Same as before — open in Android Studio, let Gradle sync, run on a device.
The project is flattened at the repo root (`app/`, `build.gradle.kts`, etc.
directly present, no wrapper folder).
