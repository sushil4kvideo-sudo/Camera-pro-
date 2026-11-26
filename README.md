# Camera Pro

**Camera Pro** is an open-source Android camera application focused on hands-on capture controls, advanced color processing, and flexible photo and video workflows.

## Features

- **Advanced LUT support:** import and apply `.cube`, `.png`, and `.xmp` profiles, with real-time preview.
- **Color controls:** fine-tune exposure, contrast, highlights, shadows, saturation, temperature, tint, vignette, grain, fade, and other creative effects.
- **Motion photos:** support for motion-photo workflows across supported Android devices and camera vendors.
- **High-speed burst:** capture rapid image sequences with optional real-time LUT processing.
- **Multi-frame processing:** combine frames for improved detail and noise reduction.
- **Depth effects:** local depth estimation for large-aperture-style background blur.
- **Phantom Mode:** use the system camera capture path while applying Camera Pro's color-processing pipeline.
- **AI-assisted color tools:** create color looks from visual references.

## Technology

- **UI:** Jetpack Compose
- **Camera:** Android Camera2 API
- **Minimum Android version:** Android 11 (API 30)
- **Build toolchain:** JDK 17, Android SDK 36, NDK 29

## Build

From the repository root:

```bash
./gradlew --no-daemon :app:assembleDebug
```

The first build needs network access to download the pinned face-detection model used by the eye-focus feature.

## APK builds

GitHub Actions runs unit tests and builds the minified debug APK on pushes to `main`. Successful push builds publish an APK to the repository's [Releases](https://github.com/sushil4kvideo-sudo/Photon.camera/releases) page.

## Bugs and feature requests

Please open an issue in the [Camera Pro issue tracker](https://github.com/sushil4kvideo-sudo/Photon.camera/issues).

## License

The project includes an Apache License 2.0 license file. Third-party libraries, models, fonts, LUTs, and other assets may have separate license terms; retain and follow their original notices when using or redistributing them.
