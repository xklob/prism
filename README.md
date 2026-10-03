# Prism

A native Android psychedelic visualizer. Kotlin, Jetpack Compose, and six original OpenGL ES 3 shaders. Android 10 or newer.

## Scenes

| Ambient | Audio reactive |
| --- | --- |
| Aurora: flowing ribbons of liquid color | Pulse: bass-driven concentric mandalas |
| Kaleido: rotating, mirrored geometry | Strings: luminous frequency ribbons |
| Wormhole: an endless neon tunnel | Nova: a spectral flower with treble sparks |

Reactive scenes also have gentle motion when no audio is connected. They do not simulate an incoming audio signal.

## Use

1. Install the APK in `dist/` on an Android device.
2. Choose Ambient or Reactive, then select a scene.
3. For reactive scenes, choose **Microphone** or **System audio**, then **Connect audio**.
4. Open **Tune** for four palettes, motion speed, intensity, audio sensitivity, and battery saver.
5. Tap **Immerse** for fullscreen. Tap the visual or use Back to restore the controls. Drag the visual to shift its center.

System audio follows media/game playback from compatible apps, including playback through headphones. Grant Android's audio permission and device capture consent, then play music. Prism acquires audio only; it never creates a virtual display or acquires screen frames. Some apps prohibit playback capture. Switch to the microphone if their audio remains silent. Phone calls are not captured. Android documents these limitations at <https://developer.android.com/media/platform/av-capture>.

System capture continues when you switch to your music app. Allow notifications for an ongoing notification with a Stop button. Disconnect with **Stop**, the notification's **Stop**, Android's capture control, or by choosing an ambient scene. Pausing also disconnects audio; reconnect to resume audio response. Removing Prism from recents ends system capture. Microphone capture stops whenever Prism leaves the foreground and resumes the already-authorized session when you return.

No account, network permission, analytics, ads, audio files, or cloud services. Audio is processed in memory using a 2,048-sample FFT at 48 kHz. Scene and tuning preferences are saved locally. Capture consent is never saved or reused across sessions.

## Build

Requires JDK 17 and Android SDK platform/build tools 36. Set `sdk.dir` in `local.properties`, or set `ANDROID_HOME`.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The debug APK is in `app/build/outputs/apk/debug/`. For a release, configure the ignored `signing.properties` file with `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`, then:

```sh
./gradlew :app:assembleRelease :app:lintRelease
```

## Verification

`SpectrumAnalyzerTest` checks silence, bass/mid/treble isolation, gain, bounds, transient detection, decay, and incomplete input. `PrismDeviceTest` exercises the actual app, checks Android system capture against generated PCM playback, and covers saved settings, immersive controls, microphone lifecycle, ambient isolation, and landscape layout. Device screenshots and capture measurements are written to the app's external `files/review` directory.

```sh
./gradlew :app:connectedDebugAndroidTest
```

The device tests grant audio access and approve Android's capture dialog on the test device. They generate a short test tone. Use an emulator or a device prepared for testing.

Rendering is capped at a 1,920-pixel longest edge and approximately 60 fps. Battery saver lowers this to 1,080 pixels and approximately 30 fps. Background rendering stops with the activity lifecycle. Paused rendering uses a low refresh rate. Actual frame rate depends on the device.
