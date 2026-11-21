# prism web

prism runs in a browser on desktop, tablet, or phone. The four patterns use the Android app’s GLSL shaders. Live inputs and local files use the same Kotlin beat and phrase trackers and the same two BeatNet models. The static bundle includes the models, fonts, licenses, and all runtime code.

## Run the release

Download **prism-web-1.16.0.zip** from [GitHub Releases](https://github.com/xklob/prism/releases/tag/v1.16.0) and extract it. With Node.js installed, open a terminal in the extracted directory and run:

```sh
node serve.mjs --open
```

The launcher binds to the loopback interface. Open `http://127.0.0.1:4173/` if the browser does not open. You can also use `python3 -m http.server 4173 --bind 127.0.0.1` in the same folder.

To self-host, serve the extracted directory over HTTPS. Relative asset URLs support hosting at the domain root or a subpath. Serve `.mjs` as `text/javascript` and `.wasm` as `application/wasm`. The app needs WebGL 2 and JavaScript modules. **Settings → Display** reports when the service worker has cached the app for offline use. Local settings and named looks belong to the browser profile and site origin.

The **Windows x64 controller ZIP** includes the web app and Node.js. **Start-prism-web.cmd** launches the standalone app locally. **Start-Prism.cmd** starts a crowd show and serves the web app to participants using its own network interface. See the [controller guide](../controller/README.md).

## Using prism

| Task | In the web app |
| --- | --- |
| Render a pattern | Select Kaleido, Wormhole, Mandelbrot, or Kaleidoscope. Drag the visual to reframe, pause motion, hide the settings panel, or use **Immerse** for a full-window view. |
| Tune the image | **Tune** has each pattern’s geometry, motion, palette, and beat controls. **Settings → Effects** adds bloom and blur. Target FPS, battery saver, render-scale feedback, and adaptive quality are under **Display**. |
| Follow live audio | In **Audio**, choose **Microphone** or **Tab / system audio**, then **Connect audio**. For tab capture, select a surface with audio and enable audio sharing in the browser picker. The visualizer uses its audio track. |
| Play a file | **Audio → Local file → Choose file** decodes and analyzes the track on-device. Press play when analysis finishes. Use the waveform, restart, or ten-second seek controls. A changed file meter or phrase length takes effect with **Analyze again**. |
| Correct the grid | Live timing includes tap tempo, half/double time, beat/bar/phrase alignment, automatic handoff, meter, and synchronization offset. The monitor shows measured input and estimated timing. |
| Save a look | **Tune → Pattern look** has a per-pattern shortcut. **Looks** stores up to 32 named looks with favorites, rename/delete, eased transitions, and cueing at a future confident phrase boundary. |
| Inspect timing | **Settings → Diagnostics** displays beat/downbeat predictions, tracked beat crossings, confidence, and matched-beat offset. Freeze an 8- or 16-second view or export the retained 60-second history to CSV. |
| Record a session | Connect the desired input before pressing **Record**. The download contains the rendered canvas and its effects, with the connected audio or a silent video track. The browser selects a supported MP4 or WebM codec. **Settings → Display** reopens, downloads or shares the last recording where supported. |
| Use song assistance | **Settings → Song** can look up a Spotify track link or an artist/title pair with ReccoBeats. Opting into AudD identification sends short live clips with your own token. The token stays in tab memory. |
| Follow a crowd show | Join the controller’s Wi-Fi network in device settings. Scan the web invitation or paste it under **Settings → Crowd**. Audience devices follow the signed show clock. An audio-input invitation allows the selected device to supply beat timing. |

## Browser capabilities

Microphone and tab sharing require HTTPS or localhost and browser consent. Ordinary LAN HTTP supports the controller’s audience view and signature verification. Tab and system audio availability depends on the browser, OS, selected surface, and source. Sharing a music tab with audio is one option on supporting desktop browsers. Android’s Shizuku Spotify relay, OS-wide media-session access, notification listener, and automatic Wi-Fi joining remain Android features. Web media keys operate the file playing in prism. See the [screen capture specification](https://www.w3.org/TR/screen-capture/) and [Media Session API](https://www.w3.org/TR/mediasession/).

Local decoding accepts mono/stereo formats supported by the browser, up to one hour, within available memory. Reselect the file after a reload. Closing the page ends its audio session. Browsers control background playback, recording codecs, screen wake lock, sharing, and app installation. A download is also available when native file sharing is unavailable. The user controls sharing of recordings and diagnostic files. All rendering and timing work locally. Only the optional song services and a joined crowd controller require requests beyond the app’s own assets.

## Build from source

Use Node.js 24, npm, and JDK 17. The repository Gradle wrapper compiles the Kotlin/JS timing engine. The web build runs independently of the Android SDK.

```sh
cd web
npm ci
npm run build
npm start
```

`dist/` is ready for static hosting. To run the Node and Chromium regression suites:

```sh
npm test
npx playwright install chromium
npm run test:browser
```

For the Firefox and WebKit playback regression, install their Playwright browsers with `npx playwright install --with-deps firefox webkit` and run `npm run test:playback`. The local tests use original synthetic audio. Model provenance and licenses are included in the deployment under `dsp/`. The web package also includes the Space Grotesk OFL and licenses for the browser signature verifier.
