import { describe, expect, it } from 'vitest';
import { ReconnectingStream, type EventSourceLike } from './stream';

class FakeSource implements EventSourceLike {
  closed = false;
  onerror: (() => void) | null = null;
  onopen: (() => void) | null = null;
  readonly listeners = new Map<string, (e: { data: string; lastEventId: string }) => void>();
  constructor(readonly url: string) {}
  addEventListener(name: string, fn: (e: { data: string; lastEventId: string }) => void) {
    this.listeners.set(name, fn);
  }
  close() {
    this.closed = true;
  }
  emit(name: string, id: number, data: unknown) {
    this.listeners.get(name)?.({ data: JSON.stringify(data), lastEventId: String(id) });
  }
}

function harness() {
  const sources: FakeSource[] = [];
  const timers: { fn: () => void; ms: number }[] = [];
  const got: [string, number, unknown][] = [];
  let ticketNo = 0;
  const stream = new ReconnectingStream({
    ticket: async () => `t/${++ticketNo}`,
    url: (ticket, lastId) => `/api/v1/kds/stream?ticket=${encodeURIComponent(ticket)}` + (lastId === null ? '' : `&lastEventId=${lastId}`),
    open: (url) => {
      const s = new FakeSource(url);
      sources.push(s);
      return s;
    },
    topics: ['course', 'order', 'availability'],
    onEvent: (name, seq, data) => got.push([name, seq, data]),
    schedule: (fn, ms) => timers.push({ fn, ms }),
  });
  const flush = () => new Promise((r) => setTimeout(r, 0));
  return { stream, sources, timers, got, flush };
}

describe('KDS stream', () => {
  it('connects with a fresh ticket and delivers events with their seq', async () => {
    const h = harness();
    await h.stream.start();
    expect(h.sources[0]?.url).toBe('/api/v1/kds/stream?ticket=t%2F1');
    h.sources[0]?.emit('course', 7, { courseId: 'c1' });
    expect(h.got).toEqual([['course', 7, { courseId: 'c1' }]]);
  });

  it('on error closes, waits, then resumes after the last event with a new ticket', async () => {
    const h = harness();
    await h.stream.start();
    h.sources[0]?.emit('course', 7, {});
    h.sources[0]?.onerror?.();
    expect(h.sources[0]?.closed).toBe(true);
    expect(h.timers[0]?.ms).toBe(1000);

    h.timers[0]?.fn();
    await h.flush();
    expect(h.sources[1]?.url).toBe('/api/v1/kds/stream?ticket=t%2F2&lastEventId=7');
  });

  it('backs off up to 30 s and resets once a connection opens', async () => {
    const h = harness();
    await h.stream.start();
    for (let i = 0; i < 7; i++) {
      h.sources[i]?.onerror?.();
      h.timers[i]?.fn();
      await h.flush();
    }
    expect(h.timers.map((t) => t.ms)).toEqual([1000, 2000, 4000, 8000, 16000, 30000, 30000]);
    h.sources[7]?.onopen?.();
    h.sources[7]?.onerror?.();
    expect(h.timers[7]?.ms).toBe(1000);
  });

  it('retries when fetching a ticket fails, and stops for good when closed', async () => {
    const h = harness();
    let fail = true;
    const s = new ReconnectingStream({
      ticket: async () => {
        if (fail) throw new Error('offline');
        return 'ok';
      },
      url: (t) => `/s?ticket=${t}`,
      open: (url) => {
        const f = new FakeSource(url);
        h.sources.push(f);
        return f;
      },
      topics: ['course'],
      onEvent: () => {},
      schedule: (fn, ms) => h.timers.push({ fn, ms }),
    });
    await s.start();
    expect(h.sources).toHaveLength(0);
    expect(h.timers[0]?.ms).toBe(1000);
    fail = false;
    s.stop();
    h.timers[0]?.fn();
    await h.flush();
    expect(h.sources).toHaveLength(0);
  });

  it('ignores an event whose id is not a number', async () => {
    const h = harness();
    await h.stream.start();
    h.sources[0]?.listeners.get('course')?.({ data: '{}', lastEventId: 'x' });
    expect(h.got).toEqual([]);
  });

  it('gives up for good when the ticket is refused, e.g. the device was revoked', async () => {
    const timers: { fn: () => void; ms: number }[] = [];
    const opened: string[] = [];
    const refused: unknown[] = [];
    const revoked = Object.assign(new Error('unauthorized'), { status: 401 });
    const stream = new ReconnectingStream({
      ticket: async () => { throw revoked; },
      url: (t) => t,
      open: (url) => { opened.push(url); return new FakeSource(url); },
      topics: ['course'],
      onEvent: () => {},
      schedule: (fn, ms) => timers.push({ fn, ms }),
      giveUp: (err) => { refused.push(err); return (err as { status?: number }).status === 401; },
    });

    await stream.start();

    expect(refused).toEqual([revoked]);
    expect(timers).toEqual([]);
    expect(opened).toEqual([]);
  });

  it('still retries a ticket failure it is not told to give up on', async () => {
    const timers: { fn: () => void; ms: number }[] = [];
    const stream = new ReconnectingStream({
      ticket: async () => { throw new Error('offline'); },
      url: (t) => t,
      open: (url) => new FakeSource(url),
      topics: ['course'],
      onEvent: () => {},
      schedule: (fn, ms) => timers.push({ fn, ms }),
      giveUp: () => false,
    });

    await stream.start();

    expect(timers.map((t) => t.ms)).toEqual([1000]);
  });
});
