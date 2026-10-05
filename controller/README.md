# Prism Crowd controller

Run a small crowd show using a Windows laptop's Wi-Fi card. The controller starts Mobile Hotspot, opens a local dashboard, and sends a shared timeline to Prism phones. Internet access is not used by the show protocol.

## Start a show

1. Install the **Prism 1.8.0 preview APK** on each Android phone.
2. Extract the complete **Windows x64 controller ZIP** into a folder you can write to. Double-click **Start-Prism.cmd**.
3. Windows requests administrator access to allow the controller's UDP port through its firewall. The launcher preserves your existing hotspot name and password and turns the hotspot on.
4. Scan the dashboard's **Audience** invitation with the phone's camera and open Prism. Tap **Join show**. Android can ask to connect to the hotspot for this app. Alternatively, join the hotspot in Android Settings, paste the invitation into **Join crowd show**, and turn off **Ask Android to join the hotspot**.
5. Wait for **Ready**, then click **Start show** on the laptop. The initial diagnostic is a 50 ms white flash on beat 1 of each bar at 120 BPM. Choose quarter notes for more frequent timing checks.
6. For patterns, select **Prism scene**, choose Aurora, Kaleido, Wormhole, or Julia, then click **Queue settings**. Changes take effect at a shared future boundary. **Blackout** takes effect as soon as each phone receives it.

Keep Prism in the foreground on the phones and keep the controller window open. Press Ctrl+C in the controller window to finish. The launcher stops a hotspot that it started; an already-running hotspot stays on. Closing the window forcibly may leave Mobile Hotspot on, so check Windows Settings in that case.

The phone's Crowd controls are transparent over the full-screen visual. Each participant can disable flashes and inversion, adjust screen brightness, blank their own screen, or leave. Local recording is unavailable while joined in this preview.

## Capacity and Windows requirements

The launcher reads Windows' actual **MaxClientCount** and displays it on the dashboard. The development PC reports **8 total clients**, including the audio-input phone. Your laptop's limit is checked when it starts. Supporting 50 simulated network clients in software does not override this hardware/driver limit. A larger access point can be used later without changing the show protocol.

Windows may require an active Wi-Fi or Ethernet connection profile before Mobile Hotspot can start. Sharing an existing Wi-Fi connection depends on the adapter and driver. The laptop can continue using Ethernet while its Wi-Fi card hosts the show. Windows' old “Hosted network supported” field does not describe modern Mobile Hotspot support.

The packaged controller includes Node.js 24 for Windows x64 and its runtime dependencies. No Node installation or development tools are needed. If Windows blocks a downloaded ZIP, open the ZIP's Properties and unblock it before extracting.

The firewall rule **Prism crowd timing** allows only the controller executable's UDP port, **48761**, from the local subnet. The dashboard listens only on the laptop's loopback address, port **48760**. Restart the launcher after moving the controller folder so the rule follows its executable.

For an existing network, run **Start-Prism.cmd -NoHotspot** and choose the correct local interface in the dashboard. **-Inspect** reports hotspot support and capacity without changing the network. **-NoFirewall** skips rule creation when a suitable rule already exists. **-Port** changes the UDP port.

## Use microphone or system audio

Manual tempo is the quickest timing check. Enter a BPM and queue it, use **Tap tempo**, or click **Bar / phrase starts here** on a known first beat. Manual phrase alignment is a chosen grid, not detected song structure.

For live music, select **Audio input phone** on the laptop and use that separate invitation on one Prism phone. On that phone, choose microphone or system audio and tap **Connect audio**, granting the usual Android capture permissions. Then select **Prism audio input** as the controller's timing source and start the show.

The input phone sends timestamped beat, tempo, bar, phrase, and confidence data. Audio samples are not sent to the controller or audience. Audience phones do not open their microphones. Existing song assistance can inform the input phone's detector, with its existing opt-in network behavior.

Keep the input phone in Prism after starting playback in Spotify, YouTube, or another app. System capture still depends on the player's Android capture policy. Going into the background suspends the crowd connection; returning reacquires the show clock. Other phones suppress accents when the input becomes stale or uncertain. Only one input phone can publish timing at a time.

Network synchronization cannot repair an incorrect musical beat or bar estimate. The dashboard separates music confidence from each phone's clock uncertainty and frame rate. Phrase boundaries remain estimates unless aligned manually.

## Timing and loss behavior

The laptop is the authoritative clock. Phones estimate its offset and drift from repeated four-timestamp UDP exchanges, preferring samples with less network queuing. They never change Android's system clock. Each phone receives its own copy of the same signed schedule; there is no dependency on multicast support.

Regular setting changes are scheduled at least one second ahead, usually on the next bar. Audio estimates also become future timeline segments. Edits are frozen in the final 250 ms before activation. Phones sample absolute flash and inversion envelopes at their predicted display presentation time, so a late frame cannot restart an expired flash.

The controller repeats state ten times per second. A short packet gap can follow the last trusted timeline. State expires after two seconds: flashes and musical accents stop, diagnostic mode becomes dark, and scene animation holds at its last scheduled time. Losing clock confidence also suppresses accents. Rejoining waits for a new boundary instead of replaying a partial flash.

**Ready** means the software clock has enough recent samples and estimated uncertainty of at most 15 ms. It does not measure actual light leaving the display. Different refresh rates, scanout, GPU load, display processing, and thermal throttling still matter. Android 13 and newer use Choreographer's expected presentation timeline; older supported devices use a refresh-period estimate.

The first physical target is a **p95 onset spread within about 33 ms** across the participating screens using 50 ms flashes. This is an acceptance target, not a measured result. Test several real phones with a high-frame-rate camera or photodiodes, reporting per-flash earliest-to-latest onset spread and missed pulses. Repeat with different refresh rates, sustained rendering, busy radio conditions, and disconnect/rejoin events before expanding the audience.

**Export measurements** saves software diagnostics from the dashboard. “Missed flash windows” counts render sampling gaps, not optical failures. A 50-client loopback test verifies fanout and consistent schedules, not hotspot capacity or room-scale synchronization.

Invitations pin the controller's per-run public signing key. Audience, audio-input, and local administrator credentials are separate and regenerated on restart. QR codes may include the hotspot password, so share only the Audience invitation with participants. The input invitation grants control of the musical timeline. The local dashboard URL grants show control. Credentials stay in memory and temporary runtime launch files, not in the source or APK.

## Development and verification

Use Node.js 24 or newer:

~~~sh
npm ci
npm run check
npm test
npm start
~~~

For another network, set **PRISM_HOST** to the controller's local IPv4 address. Optional environment variables are **PRISM_UDP_PORT**, **PRISM_HTTP_PORT**, **PRISM_HOTSPOT_LIMIT**, **PRISM_WIFI_SSID**, and **PRISM_WIFI_PASSWORD**. Avoid putting real invitation credentials or hotspot passwords in checked-in files.

Controller tests cover future activation, tempo continuity, tap/bar alignment, input expiry, malformed commands, signed packet tampering, role separation, and fifty simultaneous UDP clients. Android unit tests cover clock offset/drift, slow/asymmetric paths, several refresh rates, missed windows, backward corrections, cue boundaries, and confidence loss.

To exercise Android against a real controller process, run **node test/android-harness.ts** on the emulator's host. It writes temporary invitations to **runtime/android-test.json**; the default emulator host address is **10.0.2.2**, adjustable with **PRISM_TEST_HOST**. Pass the base64url **invite** as the **crowdInvite** instrumentation argument to **CrowdDeviceTest#signedShowJoinsRendersResumesAndLeavesWithoutMicrophoneCapture**. For the source bridge test, first choose audio timing on the dashboard and pass **sourceInvite** as **crowdSourceInvite** to **CrowdDeviceTest#sourceBridgeConvertsAndroidTimestampsIntoTheControllerClock**. The source test uses synthetic timing, not a real microphone recording.

See the main README for Android build and instrumentation setup. Physical multi-phone optical timing and the user's laptop adapter still need field testing.

Windows references: [Mobile Hotspot](https://support.microsoft.com/en-us/windows/experience/connectivity-networking/use-your-windows-device-as-a-mobile-hotspot), [reported client capacity](https://learn.microsoft.com/en-us/uwp/api/windows.networking.networkoperators.networkoperatortetheringmanager.maxclientcount).
