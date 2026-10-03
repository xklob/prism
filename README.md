# Prism

A native Android psychedelic visualizer. Kotlin, Jetpack Compose, and four original OpenGL ES 3 shaders. Android 10 or newer.

## Scenes

| Pattern | Optional audio response |
| --- | --- |
| Aurora: interwoven contour fields | Expanding currents, bending contours, shifting detail |
| Kaleido: recursive mirrored geometry | Pulsing scale, changing folds and nested geometry |
| Wormhole: an engraved geometric tunnel | Bass-driven depth, midrange twist, treble inlays |
| Julia: an evolving Julia fractal | Fractal deformation, breathing scale, shifting contours |

Every pattern works independently of audio. **React to audio** is optional and remembered separately for each pattern. The old Pulse, Strings, and Nova scenes have been removed. No simulated audio signal is used.

## Use

1. Install the APK in `dist/` on an Android device.
2. Select a pattern.
3. To follow music, enable **React to audio**, choose **Microphone** or **System audio**, then **Connect audio**. Leave the switch off for ambient animation.
4. Open **Tune** for transparent controls over the full-size visual. Nothing is dimmed or reframed. While dragging a slider, other controls fade away. **Hide** removes the controls until **Show controls** is tapped; **Done** returns to the scene browser.
5. Tap **Immerse** for fullscreen. Tap the visual or use Back to restore the controls. Drag the visual to shift its center.

System audio follows media/game playback from compatible apps, including playback through headphones. Grant Android's audio permission and device capture consent, then play music. Prism acquires audio only; it never creates a virtual display or acquires screen frames. Some apps prohibit playback capture. Switch to the microphone if their audio remains silent. Phone calls are not captured. Android documents these limitations at <https://developer.android.com/media/platform/av-capture>.

System capture continues when you switch to your music app. Allow notifications for an ongoing notification with a Stop button. Disconnect with **Stop**, the notification's **Stop**, Android's capture control, or by turning off **React to audio**. Switching to a pattern whose audio toggle is off also stops capture. Switching between enabled patterns retains the active session. Pausing disconnects audio; reconnect to resume audio response. Removing Prism from recents ends system capture. Microphone capture stops whenever Prism leaves the foreground and resumes the already-authorized session when you return.

## Tuning

| Tab | Controls |
| --- | --- |
| Geometry | Scene-specific detail/recursion/iteration count, symmetry, distortion, zoom, line weight |
| Color | Four palettes, hue shift, saturation, brightness, contrast |
| Motion | Travel speed, signed rotation speed, shape evolution, color cycling, battery saver |
| Audio (all patterns) | Per-pattern enable switch, source and connection, live band meters, reaction strength, sensitivity, bass/mid/treble influence, response smoothing |

Bass expands the geometry and adds a short beat impact; mids deform and twist it; treble changes fine detail and color. **Reaction strength** controls the overall amount, including values above 100%. Set it to zero for no modulation, or disable **React to audio** to also stop capture. Quiet playback is boosted automatically, a noise gate prevents silence from triggering motion, and fast attack/release preserves distinct beats. **Response smoothing** mostly lengthens the release; it keeps the initial response quick.

Each scene remembers its own settings. **Shuffle** makes a new visual variation without changing capture or playback state. **Save** stores one favorite look per scene, including its reaction settings; **Recall** restores it; **Reset** returns that scene to its defaults. None of these actions changes the audio enable switch or starts capture. Existing visual preferences migrate automatically. Julia retains its original stable ID; an installation last using a removed scene starts on Aurora with audio disabled. Saved looks do not contain capture permission tokens.

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

`SpectrumAnalyzerTest` checks silence, quiet playback, noise gating, bass/mid/treble isolation, gain, bounds, repeated beats, decay, and incomplete input. `AudioResponseTest` checks response latency, strength headroom, independent band controls, and clearing modulation when audio is disabled. `PrismDeviceTest` exercises Android system capture against real PCM playback, source lifecycle, per-pattern toggles, migration, saved looks, immersive controls, and landscape layout. The shipped shader is rendered at fixed animation phases to verify that every pattern visibly reacts to captured audio and each quiet frequency band; audio off must produce exactly the original ambient image. Pixel comparisons also verify the transparent overlay and live geometry adjustments. Device screenshots and capture measurements are written to the app's external `files/review` directory.

```sh
./gradlew :app:connectedDebugAndroidTest
```

The device tests grant audio access and approve Android's capture dialog on the test device. They generate a short test tone. Use an emulator or a device prepared for testing.

Normal rendering uses native resolution at approximately 60 fps. Battery saver lowers this to a 1,080-pixel longest edge and approximately 30 fps. The tuning overlay shows measured frame rate. Background rendering stops with the activity lifecycle. Paused rendering uses a low refresh rate while still accepting visual adjustments. Actual frame rate depends on the device and scene complexity.
