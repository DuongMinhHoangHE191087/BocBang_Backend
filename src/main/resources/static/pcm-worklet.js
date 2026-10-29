// Runs on the audio thread: averages the microphone down to the target rate and hands back 16-bit PCM frames.
class PcmDownsampler extends AudioWorkletProcessor {
  constructor(options) {
    super();
    const { targetRate, frameSamples } = options.processorOptions;
    this.ratio = sampleRate / targetRate;
    this.frameSamples = frameSamples;
    this.frame = new Int16Array(frameSamples);
    this.filled = 0;
    this.sum = 0;
    this.count = 0;
    this.phase = 0;
    this.energy = 0;
  }

  process(inputs) {
    const channel = inputs[0] && inputs[0][0];
    if (!channel) {
      return true;
    }
    for (let i = 0; i < channel.length; i++) {
      this.sum += channel[i];
      this.count++;
      this.phase += 1;
      if (this.phase < this.ratio) {
        continue;
      }
      this.phase -= this.ratio;
      const value = Math.max(-1, Math.min(1, this.sum / this.count));
      this.sum = 0;
      this.count = 0;
      this.frame[this.filled++] = value < 0 ? value * 0x8000 : value * 0x7fff;
      this.energy += value * value;
      if (this.filled === this.frameSamples) {
        const rms = Math.sqrt(this.energy / this.frameSamples);
        const pcm = this.frame.buffer;
        this.port.postMessage({ pcm, rms }, [pcm]);
        this.frame = new Int16Array(this.frameSamples);
        this.filled = 0;
        this.energy = 0;
      }
    }
    return true;
  }
}

registerProcessor('pcm-downsampler', PcmDownsampler);
