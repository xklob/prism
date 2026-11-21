const types = (audio) => [
  `video/mp4;codecs=avc1.42E01E${audio ? ',mp4a.40.2' : ''}`,
  `video/webm;codecs=vp9${audio ? ',opus' : ''}`,
  `video/webm;codecs=vp8${audio ? ',opus' : ''}`,
  'video/webm',
  'video/mp4',
];

export class Recorder extends EventTarget {
  constructor() {
    super();
    this.active = false;
    this.url = null;
  }
  static get supported() {
    return (
      typeof MediaRecorder !== 'undefined' && typeof HTMLCanvasElement.prototype.captureStream === 'function'
    );
  }
  start(canvas, audioStream, fps) {
    if (!Recorder.supported) throw new Error('This browser cannot record a canvas.');
    if (this.active) return;
    const stream = canvas.captureStream(fps);
    const audio = audioStream?.getAudioTracks().filter((t) => t.readyState === 'live') || [];
    for (const track of audio) stream.addTrack(track.clone());
    const chunks = [];
    const cleanup = () => stream.getTracks().forEach((t) => t.stop());
    let rec, finished;
    this.finished = new Promise((resolve) => {
      finished = resolve;
    });
    try {
      const mimeType = types(audio.length).find((type) => MediaRecorder.isTypeSupported(type));
      rec = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
      rec.ondataavailable = ({ data }) => {
        if (data.size) chunks.push(data);
      };
      let failure = '';
      rec.onerror = (event) => {
        failure = event.error?.message || 'Video encoding failed.';
        this.stop();
      };
      rec.onstop = () => {
        cleanup();
        if (!failure && chunks.length) {
          if (this.url) URL.revokeObjectURL(this.url);
          const type = rec.mimeType || chunks[0].type;
          this.file = new File(
            chunks,
            'prism-' +
              new Date().toISOString().replace(/[:.]/g, '-') +
              (type.includes('mp4') ? '.mp4' : '.webm'),
            { type }
          );
          this.url = URL.createObjectURL(this.file);
        }
        this.active = false;
        this.rec = null;
        finished();
        this.dispatchEvent(
          new CustomEvent('finish', {
            detail: failure || (chunks.length ? '' : 'The browser returned an empty recording.'),
          })
        );
      };
      rec.start(1000);
    } catch (error) {
      cleanup();
      finished();
      throw error;
    }
    this.rec = rec;
    this.active = true;
    this.size = `${canvas.width} × ${canvas.height} · ${fps} fps target`;
  }
  stop() {
    if (this.rec && this.rec.state !== 'inactive') this.rec.stop();
    return this.finished;
  }
}
