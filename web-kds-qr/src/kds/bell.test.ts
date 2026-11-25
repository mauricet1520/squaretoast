import { describe, expect, it } from 'vitest';
import { Bell, type AudioContextLike } from './bell';

class FakeAudio implements AudioContextLike {
  state: 'suspended' | 'running' | 'closed' = 'suspended';
  tones = 0;
  currentTime = 0;
  readonly destination = {};
  async resume() {
    this.state = 'running';
  }
  createOscillator() {
    const self = this;
    return {
      frequency: { value: 0 },
      connect: (n: unknown) => n,
      start() {
        self.tones++;
      },
      stop() {},
    };
  }
  createGain() {
    return { gain: { value: 0, setValueAtTime() {}, exponentialRampToValueAtTime() {} }, connect: (n: unknown) => n };
  }
}

describe('KDS bell', () => {
  it('stays silent until a person unlocks audio', async () => {
    let made = 0;
    const audio = new FakeAudio();
    const bell = new Bell(() => {
      made++;
      return audio;
    });

    expect(bell.ring()).toBe(false);
    expect(made).toBe(0); // no AudioContext before a user gesture (autoplay policy)

    await bell.unlock();
    expect(bell.ring()).toBe(true);
    expect(audio.tones).toBe(1);
  });

  it('does not ring while the context is still suspended', async () => {
    const audio = new FakeAudio();
    audio.resume = async () => {}; // the browser refused to start audio
    const bell = new Bell(() => audio);
    await bell.unlock();
    expect(bell.unlocked).toBe(false);
    expect(bell.ring()).toBe(false);
    expect(audio.tones).toBe(0);
  });
});
