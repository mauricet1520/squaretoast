// A server-sent-event stream that reconnects itself. Native EventSource reconnects reuse the same URL, but a
// KDS stream ticket works only once, so on any error this closes the source and opens a new one with a fresh
// ticket and the last event id it saw.

export type StreamEvent = { data: string; lastEventId: string };

export interface EventSourceLike {
  onerror: ((ev: Event) => void) | null;
  onopen: ((ev: Event) => void) | null;
  addEventListener(name: string, fn: (e: StreamEvent) => void): void;
  close(): void;
}

export type StreamOptions = {
  /** A one-time stream ticket (cloud) or the pairing token (LAN hub). */
  ticket: () => Promise<string>;
  url: (ticket: string, lastEventId: number | null) => string;
  open: (url: string) => EventSourceLike;
  topics: readonly string[];
  onEvent: (topic: string, seq: number, data: unknown) => void;
  schedule?: (fn: () => void, ms: number) => void;
  onStatus?: (connected: boolean) => void;
  /** Resume after this seq, e.g. the seq the board snapshot was taken at. */
  from?: number | null;
  /** Called when fetching a ticket fails; return true to stop for good (e.g. 401: the device was revoked). */
  giveUp?: (err: unknown) => boolean;
};

const FIRST_DELAY = 1000;
const MAX_DELAY = 30_000;

export class ReconnectingStream {
  private source: EventSourceLike | null = null;
  private lastEventId: number | null = null;
  private delay = FIRST_DELAY;
  private stopped = false;

  constructor(private readonly opts: StreamOptions) {
    this.lastEventId = opts.from ?? null;
  }

  async start(): Promise<void> {
    this.stopped = false;
    await this.connect();
  }

  stop(): void {
    this.stopped = true;
    this.source?.close();
    this.source = null;
  }

  private async connect(): Promise<void> {
    if (this.stopped) {
      return;
    }
    let ticket: string;
    try {
      ticket = await this.opts.ticket();
    } catch (err) {
      if (this.opts.giveUp?.(err)) {
        this.stop();
        this.opts.onStatus?.(false);
      } else {
        this.retry();
      }
      return;
    }
    if (this.stopped) {
      return;
    }
    const source = this.opts.open(this.opts.url(ticket, this.lastEventId));
    this.source = source;
    source.onopen = () => {
      this.delay = FIRST_DELAY;
      this.opts.onStatus?.(true);
    };
    source.onerror = () => {
      source.close();
      if (this.source === source) {
        this.source = null;
        this.opts.onStatus?.(false);
        this.retry();
      }
    };
    for (const topic of this.opts.topics) {
      source.addEventListener(topic, (e) => this.deliver(topic, e));
    }
  }

  private deliver(topic: string, e: StreamEvent): void {
    const seq = Number(e.lastEventId);
    if (!Number.isSafeInteger(seq) || e.lastEventId.trim() === '') {
      return;
    }
    let data: unknown;
    try {
      data = JSON.parse(e.data);
    } catch {
      return;
    }
    this.lastEventId = seq;
    this.opts.onEvent(topic, seq, data);
  }

  private retry(): void {
    if (this.stopped) {
      return;
    }
    const wait = this.delay;
    this.delay = Math.min(this.delay * 2, MAX_DELAY);
    (this.opts.schedule ?? ((fn, ms) => void setTimeout(fn, ms)))(() => void this.connect(), wait);
  }
}
