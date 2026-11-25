// The new-ticket bell. Browsers block audio until a user gesture, so nothing is created until "Start shift".

export interface AudioContextLike {
  readonly state: string;
  readonly currentTime: number;
  readonly destination: unknown;
  resume(): Promise<void>;
  createOscillator(): {
    frequency: { value: number };
    connect(n: never): unknown;
    start(when?: number): void;
    stop(when?: number): void;
  };
  createGain(): {
    gain: { value: number; setValueAtTime(v: number, t: number): unknown; exponentialRampToValueAtTime(v: number, t: number): unknown };
    connect(n: never): unknown;
  };
}

export class Bell {
  private ctx: AudioContextLike | null = null;

  constructor(private readonly create: () => AudioContextLike) {}

  get unlocked(): boolean {
    return this.ctx?.state === 'running';
  }

  /** Call from a click handler. */
  async unlock(): Promise<void> {
    this.ctx ??= this.create();
    await this.ctx.resume();
  }

  /** Plays a short two-tone chime; returns false (silently) while audio is locked. */
  ring(): boolean {
    const ctx = this.ctx;
    if (ctx === null || ctx.state !== 'running') {
      return false;
    }
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    const t = ctx.currentTime;
    osc.frequency.value = 880;
    gain.gain.setValueAtTime(0.4, t);
    gain.gain.exponentialRampToValueAtTime(0.001, t + 0.6);
    osc.connect(gain as never);
    gain.connect(ctx.destination as never);
    osc.start(t);
    osc.stop(t + 0.6);
    return true;
  }
}
