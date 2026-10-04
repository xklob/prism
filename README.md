# Prism

A native Android psychedelic visualizer. Kotlin, Jetpack Compose, and four original OpenGL ES 3 shaders. Android 10 or newer.

## Scenes

| Pattern | Optional audio response |
| --- | --- |
| Aurora: interwoven contour fields | Expanding currents, bending contours, shifting detail |
| Kaleido: recursive mirrored geometry | Pulsing scale, changing folds and nested geometry |
| Wormhole: an engraved geometric tunnel | Beat-driven depth, bar accents, rhythmic twist |
| Julia: an evolving Julia fractal | Fractal deformation, breathing scale, shifting contours |

Every pattern works independently of audio. **React to audio** is optional and remembered separately for each pattern. The old Pulse, Strings, and Nova scenes have been removed. No simulated audio signal is used.

## Use

1. [Download the APK from GitHub Releases](https://github.com/xklob/prism/releases/latest) and install it on an Android device.
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
| Audio (all patterns) | Per-pattern enable, input source, BPM and beat indicators, beat/bar lock status, timing corrections, reaction strength, beat pulse, bar accent, musical motion, pulse length, sync offset, input gain, optional audio texture |

Audio response follows musical timing. Each detected beat produces a crisp pulse; the first beat of a detected bar adds a separate accent; **Musical motion** moves the geometry smoothly across the beat or bar. The detector uses two complementary local beat/downbeat models, then tracks tempo, phase, and competing 3-beat and 4-beat bar hypotheses. Each model has its own tracker; switching requires sustained stronger evidence, and manual alignment stays in control. Beat and bar lock are separate: repeated beats alone do not establish a measure boundary. Allow a few seconds for beat lock and several measures for bar evidence. Silence stops modulation.

Automatic tracking requires spectral changes in the input. A steady tone does not become a repeating visual beat; short gaps between musical events still allow the beat clock to coast.

**Reaction strength** scales all modulation, including values above 100%. Set it to zero for the original ambient image, or disable **React to audio** to also stop capture. **Beat pulse**, **Bar accent**, and **Musical motion** are independent; **Pulse length** is measured in beats so it follows tempo. The optional **Extra audio texture** adds a little treble-dependent detail and defaults to zero. Raw volume does not drive the default movement.

Automatic timing can be ambiguous, especially with syncopation, sparse drums, tempo changes, or unusual meters. The automatic tempo search covers 60–200 BPM and 3/4 or 4/4 meter. Tap **Tap tempo** at least three times to set tempo manually, select **3** or **4** if needed, and tap **Bar starts here** on the first beat. **½ BPM** and **2× BPM** correct half/double-time interpretations. **Auto timing** returns tempo and bar alignment to detection; the selected meter remains in effect. Manual timing applies only to the active capture session. **Sync offset** advances the visual for positive values and delays it for negative values, up to 250 ms, to compensate for playback routing such as Bluetooth. Input gain boosts quiet recordings automatically and can be adjusted further.

Each scene remembers its own settings. **Shuffle** makes a new visual variation without changing capture or playback state. **Save** stores one favorite look per scene, including its reaction settings; **Recall** restores it; **Reset** returns that scene to its defaults. None of these actions changes the audio enable switch or starts capture. Existing visual preferences migrate automatically. Julia retains its original stable ID; an installation last using a removed scene starts on Aurora with audio disabled. Saved looks do not contain capture permission tokens.

Contours use pixel-width antialiasing without glow or blur. Normal rendering uses the screen's native resolution. AudioLabs (<https://audiolabs.dev>) was inspected as a reference for visual density and detailed controls; Prism's shaders and Android UI are original implementations.

No account, network permission, analytics, ads, audio recording, or cloud services. Audio stays in memory. Analysis runs every 20 ms on the capture worker; rendering predicts beat phase at display refresh rate. Android capture timestamps and frontend-delay compensation keep analysis on the same clock as the visuals. Scene and tuning preferences are saved locally. Capture consent is never saved or reused across sessions.

Beat/downbeat inference uses [BeatNet](https://github.com/mjhydri/BeatNet) models 1 and 3 by Mojtaba Heydari and contributors, licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), converted to ONNX with explicit recurrent state. Prism supplies its own Kotlin frontend and musical tracker. [ONNX Runtime](https://github.com/microsoft/onnxruntime) is MIT-licensed. Attribution, licenses, and model provenance ship in `app/src/main/assets/rhythm/`; reproducible conversion instructions are in [tools/beatnet](tools/beatnet/README.md).

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

Unit tests check the exact audio frontend, resampling, tempo/phase/meter tracking, missing beats, ambiguous bar evidence, tempo changes, silence, manual corrections, and beat/bar envelopes. Learned activations from original musical fixtures cover 96, 128, and 174 BPM. One syncopated 3/4 fixture intentionally checks manual correction of an ambiguous first beat; automatic bar alignment is not assumed to be infallible.

`PrismDeviceTest` checks Android ONNX against reference predictions, processes a full musical PCM fixture, measures analysis speed and phase error, and exercises actual Android system capture. It also checks capture lifecycle, per-pattern toggles, migration, saved looks, immersive controls, and landscape layout. The shipped shader is rendered at fixed phases to verify distinct beat, bar, and musical-motion effects in all four patterns; audio off must produce exactly the original ambient image. Pixel comparisons verify the transparent overlay and live adjustments. Evidence is written to the app's external `files/review` directory.

```sh
./gradlew :app:connectedDebugAndroidTest
```

The device tests grant audio access, approve Android's capture dialog, and play an original musical fixture on the test device. Use an emulator or a device prepared for testing.

After installing the signed release alongside the debug and test APKs, the optional release smoke test verifies actual inference after R8 shrinking, system capture, tempo corrections, manual bar alignment, automatic recovery, and restart behavior:

```sh
adb shell am instrument -w -e releaseSmoke true \
  -e class com.caleb.prism.ReleaseRhythmSmokeTest \
  com.caleb.prism.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Normal rendering uses native resolution at approximately 60 fps. Battery saver lowers this to a 1,080-pixel longest edge and approximately 30 fps. The tuning overlay shows measured frame rate. Background rendering stops with the activity lifecycle. Paused rendering uses a low refresh rate while still accepting visual adjustments. Actual frame rate depends on the device and scene complexity.
