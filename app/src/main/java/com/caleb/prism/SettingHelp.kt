package com.caleb.prism

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Touch opens persistent help; hover/long press and accessibility work too. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SettingHelp(label: String) {
    val description = settingHelp(label) ?: return
    val state = rememberTooltipState(isPersistent = true)
    BackHandler(state.isVisible) { state.dismiss() }
    val scope = rememberCoroutineScope()
    TooltipBox(positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(), state = state,
        tooltip = {
            RichTooltip(modifier = Modifier.widthIn(max = 300.dp), title = { Text(label) },
                action = { TextButton(onClick = { state.dismiss() }) { Text("Close") } }) {
                Text(description, modifier = Modifier.semantics { contentDescription = "$label help" }, fontSize = 13.sp)
            }
        }) {
        IconButton(onClick = { scope.launch { state.show() } },
            modifier = Modifier.size(32.dp).semantics { contentDescription = "About $label" }) {
            Text("ⓘ", fontSize = 18.sp, color = Color.White.copy(alpha = .85f))
        }
    }
}

internal fun settingHelp(label: String): String? = when (label) {
    "Spotify compatibility" -> "Uses an optional Shizuku helper to reroute Spotify media into a capturable 48 kHz stereo stream, with playback on your selected audio output. Install and start Shizuku, enable this setting, connect System audio, and allow prism access. The helper stops when capture ends. Restart Shizuku after a reboot. Physical-device compatibility still needs testing; this mode uses hidden Android audio APIs."
    "Recursion depth" -> "Chooses 3–9 repeated levels of Kaleido geometry. More levels add smaller nested shapes and increase drawing work."
    "Mirror segments" -> "Divides Kaleido into 3–20 repeated wedges around its center. More segments create finer radial symmetry."
    "Fold angle" -> "Changes the rotation and spacing used inside each recursive Kaleido fold. It changes the structure, independently of the whole image's rotation."
    "Edge width" -> "Sets the thickness of Kaleido's diamond, circle, and frame outlines. Higher values make the geometry bolder."
    "Ring density" -> "Changes how tightly Wormhole's engraved rings are packed along its depth. More rings create finer detail near the tunnel center."
    "Tunnel facets" -> "Sets the number of repeated sections around the tunnel wall. Lower counts feel more angular; higher counts create finer repeating panels."
    "Tunnel twist" -> "Twists the engraved wall around the tunnel as depth changes. At zero, the wall has no base twist; bar accents can still add movement."
    "Tunnel scale" -> "Magnifies the tunnel around its center. This changes the framing and apparent opening, not the forward travel speed."
    "Engraving width" -> "Sets the width of the tunnel's rings and engraved details. Fine details near the center fade out when too small to display cleanly."
    "Fractal iterations" -> "Limits how many calculations trace the fractal boundary. More iterations reveal finer edges but cost more GPU time. Mandelbrot uses 80–256."
    "Zoom" -> "Magnifies the pattern around the touch position. It changes framing while keeping the pattern's own animation and detail settings."
    "Contour width" -> "Sets the thickness of the fractal's colored contour bands. It does not add iterations or increase numerical precision."
    "Zoom depth" -> "Sets Mandelbrot's maximum automatic dive, approximately 16×–362× before framing. The animation travels smoothly in and out of a fixed boundary location; precision is bounded for mobile GPUs."
    "Framing" -> "Applies an additional fixed magnification to the Mandelbrot journey. It changes the view without changing the automatic zoom's duration or destination."
    "Space shape" -> "Chamber leaves a rounded hollow center. Gallery opens a horizontal passage. Tunnel opens a square passage through the fractal. Open space adjusts the size of the chosen hollow."
    "Fractal detail" -> "Uses 32–72 ray steps to reveal Kaleidoscope's wire structures. Higher values show more depth and filaments but can reduce frame rate."
    "Open space" -> "Changes the empty space carved out of the Kaleidoscope fractal. Larger values open up the chamber or passage and push the nearby structures outward."
    "Fold shape" -> "Changes the offsets of Kaleidoscope's repeated 3D folds. It reshapes the filament network while keeping the selected chamber, gallery, or tunnel."
    "Camera zoom" -> "Changes the Kaleidoscope camera's field of view. Higher values magnify the central structures; camera drift still follows its own motion setting."
    "Filament thickness" -> "Sets how thick the glowing fractal wires appear. Higher values make the structure denser and brighter; lower values emphasize fine strands."
    "Ring drift" -> "Moves Kaleido's outer ring accents. Fold evolution animates the recursive interior separately."
    "Travel speed" -> "Controls forward motion through Wormhole. Rotation and twist evolution have independent speeds."
    "Zoom speed" -> "Controls the pace of Mandelbrot's out-and-back zoom journey. Higher values shorten the cycle; Zoom depth sets how far it travels."
    "Camera drift" -> "Moves the Kaleidoscope camera gently forward and backward. Fractal evolution changes the wire structure separately."
    "Rotation" -> "Rotates the whole view around its center. Negative and positive values turn in opposite directions; the middle position is still. Beat/bar rotation may still occur when enabled."
    "Fold evolution" -> "Animates Kaleido's repeated fold offsets and angles. Higher values morph the interior faster; zero holds its current shape."
    "Twist evolution" -> "Animates the variation in Wormhole's twist along its walls. It is most visible with Tunnel twist above zero."
    "Fractal evolution" -> "Continuously changes Kaleidoscope's 3D fold offsets. Zero holds the base structure; live bass, midrange, and treble can still reshape it when audio is enabled."
    "Palette" -> "Chooses the colors used throughout the current pattern. Hue shift rotates the palette and Color cycling moves through it over time. Each pattern keeps its own selection."
    "Hue shift" -> "Offsets the current palette around its color cycle. This is a fixed color choice; Color cycling adds continuous movement."
    "Saturation" -> "Controls color strength. Zero produces grayscale; 100% uses the original palette; higher values intensify color."
    "Brightness" -> "Scales the visual's brightness before contrast. High values can clip bright details to white. It changes the rendered image, not Android's screen-brightness setting."
    "Contrast" -> "Changes the balance between darker details and highlights. Above 1 darkens midtones and separates bright structures; below 1 lifts darker detail."
    "Color cycling" -> "Moves continuously through the selected palette. Zero keeps the colors still; higher values cycle faster without changing the shape's motion."
    "React to audio" -> "Enables audio effects for this pattern and is remembered per pattern. Connect audio to begin capture. Turning it off returns geometric patterns to ambient motion; an active recording retains its soundtrack."
    "Reaction strength", "Audio-driven folds" -> "Scales this pattern's audio response, including beat effects and downbeat colors. Kaleidoscope also uses bass, midrange, and treble to reshape its folds. Zero removes audio modulation without ending an active capture."
    "Beat expansion" -> "Expands the view on each detected beat, with a short eased rise and smooth release. Pulse length sets the return time. This is independent of the bar accent."
    "Beat travel" -> "Adds a short forward movement through the tunnel on each beat, together with a small view expansion. Pulse length controls how quickly it settles."
    "Beat zoom" -> "Adds a short magnification pulse on each beat, on top of Mandelbrot's continuous zoom journey. Pulse length sets how long the accent lasts."
    "Bar twist" -> "Accents the first beat of a detected bar with a change in fold angle or tunnel twist, plus a small rotation. It needs bar confidence, not just a known tempo."
    "Bar rotation" -> "Adds a small rotation and expansion on the first beat of each confidently detected bar. It does not change Mandelbrot's zoom destination."
    "Bar fold" -> "Changes Kaleidoscope's fold shape and adds a small view accent on the first beat of a confidently detected bar."
    "Pulse length" -> "Sets the release duration of beat movement in fractions of a beat. Shorter values feel sharp; longer values feel elastic. The bar accent uses a longer envelope."
    "Shape breathing" -> "Adds a smooth, continuous rise and fall across the detected bar, or across each beat until bar lock is established. It changes shapes and palette gently between the sharper beat accents."
    "Palette breathing" -> "Moves the Mandelbrot palette smoothly across the detected bar, or each beat until bar lock is established. It does not distort the mathematical fractal boundary."
    "Treble detail" -> "Adds small geometry and color variations driven by the high-frequency audio level, such as hi-hats and bright transients. It layers over beat/bar motion, does not create sound, and is off by default."
    "Treble color" -> "Adds a small palette shift driven by high-frequency energy. It does not alter the recorded sound or require a separate beat event. The effect is off by default."
    "Downbeat flash" -> "Adds a brief, approximately 35 ms white flash on beat 1 of a trusted bar. Zero disables it. It waits for a real bar crossing and stops while timing is uncertain."
    "Color inversion" -> "Inverts the image's RGB colors at the start of a trusted bar, then fades back to the normal palette. Zero disables it; Invert fade sets the return duration."
    "Invert fade" -> "Sets how long an inverted downbeat takes to return to normal colors, from 60 to 400 ms. It affects inversion only, not the white flash or geometry pulse."
    "Input source" -> "Microphone follows sound around the device. System audio follows compatible apps, including headphone playback, after Android capture consent. Some players restrict capture; prism cannot infer their exact policy from silence."
    "Input gain" -> "Adjusts analysis sensitivity for quiet or loud input. It can make weak sound easier to follow, but can also emphasize noise. It never changes the original audio saved in a session recording."
    "Sync offset" -> "Moves visual timing relative to captured sound by up to 250 ms. Positive values advance the visual; negative values delay it. Use it to compensate for playback or Bluetooth latency."
    "Global effects" -> "Adds bloom and blur after any local pattern is drawn. The same completed image goes to the screen and recordings; controls remain clear. Switch off to bypass every effect while retaining your settings. Crowd shows retain their controller's output."
    "Effect strength" -> "Mixes the entire effect stack into the original image. Zero produces the untouched pattern. Bloom and blur keep their individual settings."
    "Bloom" -> "Spreads light from bright parts of the pattern into nearby dark areas. Increase for glowing filaments and softer neon halos. Strong bloom can wash out highlights."
    "Bloom radius" -> "Sets how far the glow spreads. A small radius keeps edges crisp; a large radius creates broad halos. It does not change the underlying geometry."
    "Bloom threshold" -> "Chooses which colors produce a glow. High values keep bloom around the brightest highlights; low values let more of the image glow."
    "Blur" -> "Blends a softened version of the whole pattern over the original. Zero stays sharp; 100% shows the fully blurred image. This also applies to recordings."
    "Blur radius" -> "Controls how widely neighboring pixels are blended. Increase for softer shapes and a more abstract image; use Blur to control the mix."
    "Effect quality" -> "Sets the resolution of the glow and blur passes. Economy uses the least GPU work; High keeps finer detail. Balanced is a good starting point. This setting is separate from the pattern's adaptive rendering resolution."
    "Adaptive quality" -> "Lowers rendering resolution after sustained low frame rates or when Android reports heat, then restores it slowly after recovery. Pattern controls, animation speed, and audio timing stay unchanged. The recorded image follows the same quality. It aims for your target frame rate but cannot guarantee it."
    "Target frame rate" -> "Chooses a 30 or 60 fps target. Adaptive quality uses it to balance sharpness and smoothness. The display's refresh rate and Battery saver can cap the target; recordings remain capped at 30 fps."
    "Battery saver" -> "Targets 30 fps and a maximum 1,080-pixel render edge to reduce GPU work. Adaptive quality may reduce it further. Audio analysis continues at its normal rate; recording has its own encoder settings."
    "Tablet monitor" -> "Keeps song identity, input level, BPM, and beat/bar/phrase confidence at bottom left on tablet-sized windows, including fullscreen. It is excluded from session recordings."
    "Song assist" -> "Uses available playback metadata and optional identification to look up song duration and BPM with ReccoBeats. Titles or recording IDs are sent online. A matching BPM supports tempo confidence but cannot establish a bar or phrase origin."
    "Playback info access" -> "Allows prism's notification listener to read media-session metadata and control the active player's previous, play/pause, and next actions. prism does not read notification messages. Playback controls work without Song assist or audio capture. This permission does not grant audio capture or override a player's restrictions."
    "Identify audio with AudD" -> "Optionally sends an 8–10 second clip of the active input to AudD using your own API key. This can identify room audio or players without usable metadata. Requests are rate-limited and may incur provider charges."
    "AudD API key" -> "Your AudD credential is encrypted with Android Keystore and excluded from backup. Removing it disables audio identification. It is never included in exported videos or visual presets."
    "Phrase length" -> "Chooses an 8-, 16-, or 32-bar phrase hypothesis. Changes in whole-bar sound provide evidence for the origin, so automatic detection may arrive one bar late. This is an estimate, not a known song arrangement."
    "Tap tempo" -> "Tap at least three successive beats to establish tempo manually. Consistent intervals improve the estimate. Auto timing later uses this tempo and alignment as a high-confidence starting point."
    "Beat starts here" -> "Aligns the current beat to the instant you tap. It corrects beat phase without relabeling which beat starts the bar. Set a tempo first."
    "Bar starts here" -> "Marks this instant as beat 1 of the bar. This fixes a bar origin when the rhythm is clear but automatic downbeat detection is uncertain."
    "Tempo" -> "Half BPM and double BPM correct a half-time or double-time interpretation while retaining alignment. Auto timing uses the corrected tempo as its initial guide."
    "Beats per bar" -> "Auto compares 3- and 4-beat meter hypotheses. Choose 3 or 4 to constrain the meter manually. A BPM alone does not tell prism where the first beat falls."
    "Phrase starts here" -> "Marks the current beat as a new bar and phrase origin. Use it on a known section boundary after tempo is established. The phrase length comes from Song settings."
    "Auto phrase" -> "Returns phrase tracking to automatic mode while using your manual phrase origin as its initial guide. New bar-level sound changes can refine the estimate."
    "Auto timing" -> "Returns tempo, beat, and bar tracking to automatic mode. Your current manual grid starts with high confidence, then its influence fades over eight bars while fresh audio refines it."
    "Look library" -> "Save up to 32 named looks, including the pattern, its tuning, and global effects. Star favorites, rename, or delete them. PLAY recalls a look with the selected transition time; CUE waits for a stable phrase boundary. Audio source, capture, tempo, and display preferences remain unchanged."
    "Transition time" -> "Sets the duration of a smooth visual crossfade when applying a library look. Both patterns keep moving. Zero makes an immediate cut. Transitions also appear in recordings. Two live patterns cost more to render during the fade; adaptive quality can reduce resolution."
    "Phrase cue" -> "Queues this look for the next future boundary of the current 8, 16, or 32 bar phrase grid. Audio must be connected with a stable beat, bar, and phrase estimate. Lost confidence, seeks, or grid corrections re-arm the cue instead of triggering it. Apply now works without detection. Phrase boundaries are estimates unless you align them manually."
    "Beat grid diagnostics" -> "Plots recent beat and downbeat model predictions beside vertical lines from the tracked beat grid, plus beat, bar, and phrase confidence. Matching peaks and lines support the grid; disagreement can reveal offbeat or half-time tracking. Confidence is evidence strength, not a calibrated correctness score. The rolling history holds at most 60 seconds and contains no audio or song identity."
    "Freeze graph" -> "Freezes only this diagnostic view for inspection. Audio analysis, the visuals, recording, and queued phrase cues continue. Live view returns to the latest evidence."
    "Export timing CSV" -> "Saves the retained timing samples to a file you choose. It includes relative time, tempo, beat positions, model predictions, confidence, phase offsets, and tracker states. No audio, song title, app identity, or absolute clock time is included. This is detector telemetry, not independently measured beat accuracy."
    "Saved looks" -> "Save stores one favorite for this pattern. Recall restores its visual and reaction settings; Reset restores pattern defaults. Shuffle varies controls relevant to this pattern. These actions preserve shared capture preferences and never start audio."
    else -> null
}
