// The analysis clock uses 20 ms blocks. Recording keeps the original channels.
class PrismPcm extends AudioWorkletProcessor {
  constructor() {
    super();
    this.channels = [];
    this.offset = 0;
    this.firstFrame = 0;
  }
  process(inputs) {
    const input = inputs[0];
    if (!input?.[0]) return true;
    const count = Math.min(2, input.length);
    if (count !== this.channels.length) {
      this.channels = Array.from({ length: count }, () => new Float32Array(960));
      this.offset = 0;
    }
    let index = 0;
    while (index < input[0].length) {
      if (!this.offset) this.firstFrame = currentFrame + index;
      const n = Math.min(960 - this.offset, input[0].length - index);
      for (let c = 0; c < count; c++) this.channels[c].set(input[c].subarray(index, index + n), this.offset);
      this.offset += n;
      index += n;
      if (this.offset === 960) {
        this.port.postMessage(
          { start: this.firstFrame / sampleRate, channels: this.channels },
          this.channels.map((c) => c.buffer)
        );
        this.channels = Array.from({ length: count }, () => new Float32Array(960));
        this.offset = 0;
      }
    }
    return true;
  }
}
registerProcessor('prism-pcm', PrismPcm);
