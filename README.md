# Prism

A native Android psychedelic visualizer. Kotlin, Jetpack Compose, and seven original OpenGL ES 3 shaders. Android 10 or newer.

## Scenes

| Ambient | Audio reactive |
| --- | --- |
| Aurora: interwoven contour fields | Pulse: interlocking, bass-driven mandalas |
| Kaleido: recursive mirrored geometry | Strings: harmonic lattices woven from sound |
| Wormhole: an engraved geometric tunnel | Nova: nested orbital flowers and filigree |
| Julia: an evolving Julia fractal | |

Reactive scenes also have gentle motion when no audio is connected. They do not simulate an incoming audio signal.

## Use

1. Install the APK in `dist/` on an Android device.
2. Choose Ambient or Reactive, then select a scene.
3. For reactive scenes, choose **Microphone** or **System audio**, then **Connect audio**.
4. Open **Tune** for transparent controls over the full-size visual. Nothing is dimmed or reframed. While dragging a slider, other controls fade away. **Hide** removes the controls until **Show controls** is tapped; **Done** returns to the scene browser.
5. Tap **Immerse** for fullscreen. Tap the visual or use Back to restore the controls. Drag the visual to shift its center.

System audio follows media/game playback from compatible apps, including playback through headphones. Grant Android's audio permission and device capture consent, then play music. Prism acquires audio only; it never creates a virtual display or acquires screen frames. Some apps prohibit playback capture. Switch to the microphone if their audio remains silent. Phone calls are not captured. Android documents these limitations at <https://developer.android.com/media/platform/av-capture>.

System capture continues when you switch to your music app. Allow notifications for an ongoing notification with a Stop button. Disconnect with **Stop**, the notification's **Stop**, Android's capture control, or by choosing an ambient scene. Pausing also disconnects audio; reconnect to resume audio response. Removing Prism from recents ends system capture. Microphone capture stops whenever Prism leaves the foreground and resumes the already-authorized session when you return.

## Tuning

| Tab | Controls |
| --- | --- |
| Geometry | Scene-specific detail/recursion/iteration count, symmetry or petals, distortion, zoom, line weight |
| Color | Four palettes, hue shift, saturation, brightness, contrast |
| Motion | Travel speed, signed rotation speed, shape evolution, color cycling, battery saver |
| Audio (reactive scenes) | Source and connection, sensitivity, bass/mid/treble influence, response smoothing |

Each scene remembers its own settings. **Shuffle** makes a new visual variation without changing capture or playback state. **Save** stores one favorite look per scene; **Recall** restores it; **Reset** returns that scene to its defaults. Existing v1 palette, speed, and intensity preferences migrate automatically. Saved looks do not contain capture permission tokens.

Contours use pixel-width antialiasing without glow or blur. Normal rendering uses the screen's native resolution. AudioLabs (<https://audiolabs.dev>) was inspected as a reference for visual density and detailed controls; Prism's shaders and Android UI are original implementations.

No account, network permission, analytics, ads, audio files, or cloud services. Audio is processed in memory using a 2,048-sample FFT at 48 kHz. Scene and tuning preferences are saved locally. Capture consent is never saved or reused across sessions.

## Build

Requires JDK 17 and Android SDK platform/build tools 36. Set `sdk.dir` in `local.properties`, or set `ANDROID_HOME`.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The debug APK is in `app/build/outputs/apk/debug/` and uses package `com.caleb.prism.debug`, so it can coexist with the release. For a release, configure the ignored `signing.properties` file with `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`, then:

```sh
./gradlew :app:assembleRelease :app:lintRelease
```

## Verification

`SpectrumAnalyzerTest` checks silence, bass/mid/treble isolation, gain, bounds, transient detection, decay, and incomplete input. `PrismDeviceTest` exercises the actual app, checks Android system capture against generated PCM playback, and covers saved settings, immersive controls, microphone lifecycle, ambient isolation, and landscape layout. Pixel comparisons verify that the tuning overlay preserves the underlying visual and that geometry adjustments change the actual renderer immediately. Saved looks and per-scene tuning are also checked. Device screenshots and capture measurements are written to the app's external `files/review` directory.

```sh
./gradlew :app:connectedDebugAndroidTest
```

The device tests grant audio access and approve Android's capture dialog on the test device. They generate a short test tone. Use an emulator or a device prepared for testing.

Normal rendering uses native resolution at approximately 60 fps. Battery saver lowers this to a 1,080-pixel longest edge and approximately 30 fps. The tuning overlay shows measured frame rate. Background rendering stops with the activity lifecycle. Paused rendering uses a low refresh rate while still accepting visual adjustments. Actual frame rate depends on the device and scene complexity.
