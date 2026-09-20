# DropShare — Air Grab & Drop

A native Android proof-of-concept for gesture-driven, phone-to-phone photo/video transfer.

## Intended experience

- DropShare opens directly to the device's photos and videos using MediaStore.
- The most recent media is automatically held as the initial selection; tapping another tile changes the held item.
- While DropShare is in the foreground, CameraX runs an **invisible** front-camera analysis stream behind the normal media UI. There is no camera preview screen.
- On-device MediaPipe Gesture Recognizer detects a temporally smoothed closed-fist **grab** and open-palm **drop**.
- A sender closes the hand to grab, moves toward the nearby DropShare phone, then opens the hand to drop.
- The receiver does not get an Accept/Decline dialog. An incoming connection is accepted only after the receiver performs fist → open palm.
- Nearby Connections carries the actual media bytes. The receiver writes the result to Android MediaStore/Gallery.

## Important Android platform boundary

Android does not allow an ordinary app to secretly use the camera while the app is truly backgrounded. This project therefore keeps the camera analysis active only while DropShare is visible/in the foreground, but the preview itself is not displayed. The app requests camera permission at startup so the gesture engine can already be ready when the user interacts with media.

The interaction is inspired by Huawei-style air handoff. A third-party APK cannot reproduce Huawei's private system-level spatial/hardware integration on every Android phone. This implementation uses general Android camera/AI gesture recognition plus Nearby Connections and the receiver's physical gesture.

## Build

GitHub Actions installs JDK 17, Android SDK 36 and Gradle 8.13, downloads the MediaPipe gesture model, and builds debug and CI-signed release APK artifacts.

## Repository root

The repository root intentionally contains `.github/workflows/android.yml` directly; there is no outer project folder required.

## Quick Settings

DropShare now includes a native Android Quick Settings tile named **DropShare**. This lets the user place DropShare alongside system tiles such as Wi-Fi, Bluetooth, and Vibrate.

- Android 13+ can be prompted from the app with **Add DropShare to Quick Settings**.
- Android 12 and earlier: swipe down twice, tap **Edit**, find **DropShare**, and drag it into the active tiles.
- Android controls the tile's exact position; an app cannot force itself into the user's active Quick Settings row.

The tile opens DropShare. It does not bypass Android permissions or secretly start camera access.


### Animated gesture feedback
- A full-screen animated overlay appears when the fist gesture grabs media, showing the actual media thumbnail and “IMAGE CARRIED”.
- The open-palm gesture triggers an animated “IMAGE DROPPED” overlay.
- On the receiving phone, the acceptance gesture shows “DROP RECEIVED” before the media is saved to Gallery.
- The carrying overlay remains visible while the media is in the grabbed state, then transitions to the drop animation.

## Build requirements and validation

- Android Gradle Plugin 8.13.2 + Kotlin 2.3.10 + Gradle 8.13 + JDK 17.
- `compileSdk` / `targetSdk` 36 and `minSdk` 26.
- The project requests the runtime permissions required by Nearby Connections for the relevant Android version before advertising/discovery starts.
- Android 13/14 media permissions are handled with the modern MediaStore permissions, while Android 12 and below use the legacy storage permission.
- The MediaPipe gesture model is downloaded during CI/build and placed in `app/src/main/assets/gesture_recognizer.task` before compilation.
- CI runs Gradle project validation, unit tests, Android lint, a debug build, and an installable release build before publishing the APK artifacts.
- The current environment used to prepare this ZIP does not have Android SDK/Gradle dependencies cached and cannot perform the final remote dependency download itself. GitHub Actions is therefore the authoritative clean build in this package.

## Runtime transfer reliability

The sender keeps each outgoing stream and temporary packet alive until Nearby Connections reports the payload's terminal transfer status. Temporary files are then closed and deleted. This avoids deleting the source stream immediately after `sendPayload()` merely reports that the transfer was queued.
