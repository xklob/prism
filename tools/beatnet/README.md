# BeatNet assets

The app bundles BeatNet models 1 and 3 by Mojtaba Heydari and contributors under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). `upstream.json` pins
the original source revision and SHA-256 hashes. `model.py` is unchanged upstream
source. `export.py` converts the network to ONNX with explicit recurrent state;
the app uses its own Kotlin tracker rather than BeatNet's particle filter.

Normal Android builds use the committed assets and need no Python environment,
model downloads, or network access at runtime.

To regenerate, use an isolated Python 3.11 environment with PyTorch 2.8.0 (CPU),
NumPy 1.26.4, SciPy 1.15.3, madmom 0.16.1, ONNX 1.17.0 and ONNX Runtime 1.23.2.
madmom is a development reference only, not an Android runtime dependency.
Its older NumPy aliases are patched locally inside these scripts.

Download `src/BeatNet/models/model_1_weights.pt` and `src/BeatNet/models/model_3_weights.pt` from the upstream revision in
`upstream.json` into `~/.local/share/prism/beatnet-models/`, then run:

```sh
python tools/beatnet/export.py
python tools/beatnet/fixtures.py
```

The exporter verifies the input hash, compares 100 consecutive ONNX predictions from each model
with PyTorch, and writes model/frontend assets plus reference test vectors.
`fixtures.py` generates original 128 BPM and 174 BPM 4/4 tracks and a 96 BPM 3/4
track. No third-party recordings are included. Test PCM lives only in the test
APK, not the shipped app.

The fixture generator also writes a short multitone input and reference-resampled
output for JVM tests, covering bass, the upper trained bands, and out-of-band
treble. The streaming resampler uses a Kaiser-windowed sinc with the reference
22.05 kHz output cutoff; preserving only lower frequencies changes model inputs.

The frontend uses 22,050 Hz audio, 1,411-sample Hann windows, 441-sample hops,
136 log-frequency bands and 136 positive-difference bands. Kotlin uses a
band-limited 48,000-to-22,050 Hz resampler and Bluestein FFT to match the reference
transform length exactly. The 970-sample feature delay is removed from analysis
timestamps. Tests compare the frontend, Android ONNX output, musical phase,
meter, and complete captured PCM path.
