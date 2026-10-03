"""Export the pinned BeatNet model with explicit streaming state; verify against PyTorch."""
import collections
import collections.abc
import hashlib
import json
from pathlib import Path
import sys

import numpy as np
import torch
import torch.nn.functional as F
import onnxruntime as ort
from model import BDA

collections.MutableSequence = collections.abc.MutableSequence
for name, value in [('float', float), ('int', int), ('complex', complex), ('bool', bool)]:
    setattr(np, name, value)
from madmom.audio.signal import SignalProcessor, FramedSignalProcessor
from madmom.audio.stft import ShortTimeFourierTransformProcessor
from madmom.audio.spectrogram import FilteredSpectrogramProcessor, LogarithmicSpectrogramProcessor, SpectrogramDifferenceProcessor
from madmom.processors import SequentialProcessor

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT/'app/src/main/assets/rhythm'
TEST = ROOT/'app/src/test/resources/rhythm'
TEST.mkdir(parents=True, exist_ok=True)
torch.set_num_threads(1)
weights = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.home()/'.local/share/prism/beatnet-models/model_3_weights.pt'
manifest = json.loads(Path(__file__).with_name('upstream.json').read_text())
assert hashlib.sha256(weights.read_bytes()).hexdigest() == manifest['files']['model_3_weights.pt']

class StreamingModel(BDA):
    def forward(self, features, hidden, cell):
        x = F.max_pool1d(F.relu(self.conv1(features.reshape(1, 1, 272))), 2)
        x = self.linear0(x.reshape(1, -1)).reshape(1, 1, 150)
        x, (hidden, cell) = self.lstm(x, (hidden, cell))
        return torch.softmax(self.linear(x[:, -1, :]), dim=-1), hidden, cell

model = StreamingModel(272, 150, 2, 'cpu')
model.load_state_dict(torch.load(weights, map_location='cpu', weights_only=True), strict=True)
model.eval()
args = (torch.zeros(1, 1, 272), torch.zeros(2, 1, 150), torch.zeros(2, 1, 150))
torch.onnx.export(model, args, str(OUT/'beatnet.onnx'), input_names=['features', 'hidden', 'cell'],
    output_names=['probabilities', 'next_hidden', 'next_cell'], opset_version=17, dynamo=False)

frontend = SequentialProcessor([
    SignalProcessor(num_channels=1, sample_rate=22050),
    FramedSignalProcessor(frame_size=1411, hop_size=441, num_frames=4),
    ShortTimeFourierTransformProcessor(),
    FilteredSpectrogramProcessor(num_bands=24, fmin=30, fmax=17000, norm_filters=True),
    LogarithmicSpectrogramProcessor(mul=1, add=1),
    SpectrogramDifferenceProcessor(diff_ratio=.5, positive_diffs=True, stack_diffs=np.hstack),
])
rng = np.random.default_rng(42)
rolling = (rng.normal(size=2293)*.08).astype(np.float32)
features = np.asarray(frontend(rolling)[-1], dtype=np.float32)
filters = np.asarray(frontend.processors[3].filterbank, dtype=np.float32)
assert filters.shape == (705, 136)
filters.astype('<f4').tofile(OUT/'filters.f32')
rolling.astype('<f4').tofile(TEST/'window.f32')
features.astype('<f4').tofile(TEST/'features.f32')

session = ort.InferenceSession(str(OUT/'beatnet.onnx'), providers=['CPUExecutionProvider'])
hidden = cell = np.zeros((2, 1, 150), dtype=np.float32)
torch_hidden, torch_cell = torch.zeros(2, 1, 150), torch.zeros(2, 1, 150)
max_error = 0.
with torch.inference_mode():
    for _ in range(100):
        frame = (rng.random((1, 1, 272))*.6).astype(np.float32)
        p, hidden, cell = session.run(None, {'features':frame, 'hidden':hidden, 'cell':cell})
        expected, torch_hidden, torch_cell = model(torch.from_numpy(frame), torch_hidden, torch_cell)
        error = float(np.max(np.abs(p-expected.numpy())))
        max_error = max(max_error, error)
        np.testing.assert_allclose(p, expected.numpy(), atol=2e-5, rtol=2e-4)
test_probabilities = session.run(None, {'features':features.reshape(1,1,272), 'hidden':np.zeros((2,1,150),np.float32), 'cell':np.zeros((2,1,150),np.float32)})[0]
test_probabilities.astype('<f4').tofile(TEST/'probabilities.f32')

# A general-genre model complements model 3 on music outside the rock training set.
general_weights=weights.with_name('model_1_weights.pt')
assert hashlib.sha256(general_weights.read_bytes()).hexdigest() == manifest['files']['model_1_weights.pt']
general=StreamingModel(272,150,2,'cpu')
general.load_state_dict(torch.load(general_weights,map_location='cpu',weights_only=True),strict=True)
general.eval()
torch.onnx.export(general,args,str(OUT/'beatnet-general.onnx'),input_names=['features','hidden','cell'],
    output_names=['probabilities','next_hidden','next_cell'],opset_version=17,dynamo=False)
general_session=ort.InferenceSession(str(OUT/'beatnet-general.onnx'),providers=['CPUExecutionProvider'])
hidden=cell=np.zeros((2,1,150),np.float32)
torch_hidden,torch_cell=torch.zeros(2,1,150),torch.zeros(2,1,150)
general_error=0.
with torch.inference_mode():
    for _ in range(100):
        frame=(rng.random((1,1,272))*.6).astype(np.float32)
        p,hidden,cell=general_session.run(None,{'features':frame,'hidden':hidden,'cell':cell})
        expected,torch_hidden,torch_cell=general(torch.from_numpy(frame),torch_hidden,torch_cell)
        general_error=max(general_error,float(np.max(np.abs(p-expected.numpy()))))
        np.testing.assert_allclose(p,expected.numpy(),atol=2e-5,rtol=2e-4)
(OUT/'general-manifest.json').write_text(json.dumps({'source':manifest['source'],'upstream_commit':manifest['commit'],
    'weights_sha256':manifest['files']['model_1_weights.pt'],'license':'CC-BY-4.0',
    'model_sha256':hashlib.sha256((OUT/'beatnet-general.onnx').read_bytes()).hexdigest(),
    'onnx_max_probability_error':general_error},indent=2)+'\n')
(OUT/'manifest.json').write_text(json.dumps({'source':manifest['source'], 'upstream_commit':manifest['commit'],
    'weights_sha256':manifest['files']['model_3_weights.pt'], 'license':'CC-BY-4.0',
    'model_sha256':hashlib.sha256((OUT/'beatnet.onnx').read_bytes()).hexdigest(),
    'sample_rate':22050,'hop':441,'window':1411,'rolling_window':2293,'feature_delay_samples':970,
    'filter_shape':[705,136],'onnx_max_probability_error':max_error}, indent=2)+'\n')
print('EXPORT VERIFIED', 'max_probability_error=',max_error,'model_bytes=',(OUT/'beatnet.onnx').stat().st_size)
