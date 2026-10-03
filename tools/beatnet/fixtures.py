"""Original musical fixtures with known timing, plus causal activations from the reference model."""
import collections, collections.abc
from pathlib import Path
import numpy as np
import torch
import scipy.signal
from model import BDA

collections.MutableSequence=collections.abc.MutableSequence
for name,value in [('float',float),('int',int),('complex',complex),('bool',bool)]: setattr(np,name,value)
from madmom.audio.signal import SignalProcessor,FramedSignalProcessor
from madmom.audio.stft import ShortTimeFourierTransformProcessor
from madmom.audio.spectrogram import FilteredSpectrogramProcessor,LogarithmicSpectrogramProcessor,SpectrogramDifferenceProcessor
from madmom.processors import SequentialProcessor

ROOT=Path(__file__).resolve().parents[2]
RES=ROOT/'app/src/test/resources/rhythm'
ASSETS=ROOT/'app/src/androidTest/assets/rhythm'
torch.set_num_threads(1)
model=BDA(272,150,2,'cpu')
model.load_state_dict(torch.load(Path.home()/'.local/share/prism/beatnet-models/model_3_weights.pt',map_location='cpu',weights_only=True))
model.eval()

def music(bpm,meter,seconds=32):
    rate=48000
    t=np.arange(int(seconds*rate))/rate
    p=(t-.3)*bpm/60
    index=np.floor(p).astype(int)
    age=(p-index)*60/bpm
    bar_age=np.mod(p,meter)*60/bpm
    noise=np.random.default_rng(19).normal(size=len(t))
    kick=.64*np.sin(2*np.pi*(52*age+85*.018*(1-np.exp(-age/.018))))*np.exp(-age*22)
    backbeat=np.mod(index,meter)==(1 if meter==3 else 1)
    if meter==4: backbeat |= np.mod(index,meter)==3
    snare=.16*(noise+.5*np.sin(2*np.pi*180*age))*np.exp(-age*32)*backbeat
    hat_age=np.mod(p*2,1)*30/bpm
    hats=.045*(noise-np.roll(noise,1))*np.exp(-hat_age*100)
    crash=.08*(noise-np.roll(noise,1))*np.exp(-bar_age*5)
    root=np.take([55,65.406,73.416,48.999],np.mod(np.floor(p/meter).astype(int),4))
    bass=.10*np.sin(2*np.pi*root*t)*np.exp(-age*5)
    chord=sum(.025*np.sin(2*np.pi*root*ratio*t) for ratio in [4,5,6])*np.exp(-bar_age*1.8)
    wave=(kick+snare+hats+crash+bass+chord)*(t>=.3)
    return np.clip(wave,-.95,.95).astype(np.float32)

def activations(pcm,rate):
    wave=scipy.signal.resample_poly(pcm,22050,rate).astype(np.float32)
    frontend=SequentialProcessor([SignalProcessor(num_channels=1,sample_rate=22050),
        FramedSignalProcessor(frame_size=1411,hop_size=441,num_frames=4),ShortTimeFourierTransformProcessor(),
        FilteredSpectrogramProcessor(num_bands=24,fmin=30,fmax=17000,norm_filters=True),
        LogarithmicSpectrogramProcessor(mul=1,add=1),
        SpectrogramDifferenceProcessor(diff_ratio=.5,positive_diffs=True,stack_diffs=np.hstack)])
    model.hidden=torch.zeros(2,1,150); model.cell=torch.zeros(2,1,150)
    rolling=np.zeros(2293,np.float32); output=[]
    with torch.inference_mode():
        for hop,end in enumerate(range(441,len(wave)+1,441),start=1):
            rolling[:-441]=rolling[441:]; rolling[-441:]=wave[end-441:end]
            if hop < 6: continue
            feature=np.asarray(frontend(rolling)[-1],np.float32)
            probability=model.final_pred(model(torch.from_numpy(feature)[None,None,:])[0])[:,0].numpy()
            output.append([(end-970)/22050,*probability[:2]])
    return np.asarray(output)

def main():
    ASSETS.mkdir(parents=True,exist_ok=True)
    RES.mkdir(parents=True,exist_ok=True)
    for name,bpm,meter in [('drums128',128,4),('drums174',174,4),('waltz96',96,3)]:
        pcm=music(bpm,meter)
        if name=='drums128': (pcm*32767).astype('<i2').tofile(ASSETS/(name+'.pcm'))
        values=activations(pcm,48000)
        np.savetxt(RES/(name+'.csv'),values,delimiter=',',fmt='%.7f')
        peaks=[v for i,v in enumerate(values[1:-1],1) if v[1:].sum()>.18 and v[1:].sum()>values[i-1,1:].sum() and v[1:].sum()>=values[i+1,1:].sum()]
        print(name,'peaks',len(peaks),'strong_downbeats',sum(v[2]>.5 for v in peaks),flush=True)
    for name in ['features','probabilities']:
        (ASSETS/(name+'.f32')).write_bytes((RES/(name+'.f32')).read_bytes())
    model.load_state_dict(torch.load(Path.home()/'.local/share/prism/beatnet-models/model_1_weights.pt',map_location='cpu',weights_only=True))
    for name,bpm,meter in [('drums128',128,4),('drums174',174,4),('waltz96',96,3)]:
        np.savetxt(RES/(name+'-general.csv'),activations(music(bpm,meter),48000),delimiter=',',fmt='%.7f')

if __name__ == '__main__':
    main()
