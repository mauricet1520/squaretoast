import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { ApiError, call, store } from '../shared/api';
import { type CourseAction, type CourseState } from '../shared/course';
import { Bell } from './bell';
import { applyEvent, emptyBoard, fromSnapshot, ticketsFor, type Board, type Ticket } from './board';
import { takePairingToken } from './pairing';
import { ReconnectingStream } from './stream';

const TOKEN_KEY = 'kds.deviceToken';
const local = store('local');

/** Actions the kitchen takes. Firing and un-firing belong to the POS. */
const KITCHEN: Record<CourseState, CourseAction[]> = { HOLD: [], FIRED: ['BUMP'], READY: ['RECALL', 'SERVE'], SERVED: [] };
const LABEL: Record<CourseAction, string> = { FIRE: 'Fire', UNFIRE: 'Hold', BUMP: 'Bump', RECALL: 'Recall', SERVE: 'Served' };

type Mode = { kind: 'cloud'; token: string } | { kind: 'hub'; token: string };

function detectMode(): Mode | null {
  const pair = takePairingToken(window.location.href, store('session'), (u) => window.history.replaceState(null, '', u));
  if (pair !== null) {
    return { kind: 'hub', token: pair };
  }
  const token = local.getItem(TOKEN_KEY);
  return token === null ? null : { kind: 'cloud', token };
}

export function KdsApp() {
  const [mode, setMode] = useState<Mode | null>(() => detectMode());
  if (mode === null) {
    return <Enroll onEnrolled={(token) => setMode({ kind: 'cloud', token })} />;
  }
  return <Kitchen mode={mode} onSignedOut={() => { local.removeItem(TOKEN_KEY); setMode(null); }} />;
}

function bytesToBase64(buf: ArrayBuffer): string {
  let s = '';
  for (const b of new Uint8Array(buf)) {
    s += String.fromCharCode(b);
  }
  return btoa(s);
}

function Enroll({ onEnrolled }: { onEnrolled: (token: string) => void }) {
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: Event) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      // A device key, as a POS has. The private half is non-extractable and never leaves this browser.
      const keys = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign']);
      const spki = bytesToBase64(await crypto.subtle.exportKey('spki', keys.publicKey));
      const r = await call<{ deviceToken: string }>('/devices/enroll', {
        body: { code: code.trim(), publicKeySpki: spki, label: 'Kitchen display' },
      });
      local.setItem(TOKEN_KEY, r.deviceToken);
      onEnrolled(r.deviceToken);
    } catch (err) {
      setError(err instanceof ApiError && err.status === 400 ? 'That code is not valid or has expired.' : 'Could not reach the server.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <main class="narrow">
      <h1>Kitchen display</h1>
      <p>Enter the enrollment code from the manager's screen to connect this display.</p>
      <form onSubmit={submit}>
        <label>
          Enrollment code
          <input value={code} onInput={(e) => setCode(e.currentTarget.value)} autocomplete="off" required />
        </label>
        <button disabled={busy || code.trim() === ''}>Connect</button>
      </form>
      {error && <p role="alert" class="error">{error}</p>}
    </main>
  );
}

type HubCourse = { hubSeq: number; courseId: string; state: CourseState };

function Kitchen({ mode, onSignedOut }: { mode: Mode; onSignedOut: () => void }) {
  const [board, setBoard] = useState<Board>(emptyBoard);
  const [station, setStation] = useState<string | null>(null);
  const [connected, setConnected] = useState(false);
  const [awaiting, setAwaiting] = useState<string[]>([]);
  const bell = useMemo(() => new Bell(() => new AudioContext()), []);
  const [shiftStarted, setShiftStarted] = useState(false);
  const boardRef = useRef(board);
  boardRef.current = board;

  useEffect(() => {
    let stream: ReconnectingStream | null = null;
    let cancelled = false;
    let hubSeq = 0;

    const onCourse = (seq: number, t: Ticket) => {
      const before = boardRef.current;
      const next = applyEvent(before, seq, t);
      if (t.state === 'FIRED' && !before.tickets.has(t.courseId)) {
        bell.ring();
      }
      setBoard(next);
    };

    async function begin() {
      if (mode.kind === 'cloud') {
        let snapshot: { lastSeq: number; tickets: Ticket[] };
        try {
          snapshot = await call('/kds/board', { token: mode.token });
        } catch (err) {
          if (err instanceof ApiError && err.status === 401) onSignedOut();
          return;
        }
        if (cancelled) return;
        setBoard(fromSnapshot(snapshot.tickets, snapshot.lastSeq));
        stream = new ReconnectingStream({
          from: snapshot.lastSeq,
          ticket: async () => (await call<{ ticket: string }>('/kds/stream-tickets', { method: 'POST', body: {}, token: mode.token })).ticket,
          url: (ticket, last) => `/api/v1/kds/stream?ticket=${encodeURIComponent(ticket)}${last === null ? '' : `&lastEventId=${last}`}`,
          open: (url) => new EventSource(url),
          topics: ['course', 'order'],
          onEvent: (topic, seq, data) => {
            if (topic === 'course') onCourse(seq, data as Ticket);
            if (topic === 'order') {
              const o = data as { orderId: string; table: string | null };
              setAwaiting((a) => [...a.filter((x) => x !== o.orderId), o.orderId]);
              bell.ring();
            }
          },
          onStatus: setConnected,
        });
      } else {
        // The hub sends a snapshot on every connect, all at its current seq, then live events. Number them locally.
        stream = new ReconnectingStream({
          ticket: async () => mode.token,
          url: (token) => `/api/v1/stream?token=${encodeURIComponent(token)}`,
          open: (url) => new EventSource(url),
          topics: ['course'],
          onEvent: (_topic, _seq, data) => {
            const c = data as HubCourse;
            onCourse(++hubSeq, { courseId: c.courseId, orderId: '', table: null, courseNo: 0, state: c.state, lines: [] });
          },
          onStatus: (up) => {
            if (up) {
              hubSeq = 0;
              setBoard(emptyBoard());
            }
            setConnected(up);
          },
        });
      }
      await stream.start();
    }

    void begin();
    return () => {
      cancelled = true;
      stream?.stop();
    };
  }, [mode]);

  async function act(t: Ticket, action: CourseAction) {
    try {
      if (mode.kind === 'cloud') {
        await call(`/courses/${encodeURIComponent(t.courseId)}/transitions`, { body: { action, expectedFrom: t.state }, token: mode.token });
      } else {
        await call(`/course/${encodeURIComponent(t.courseId)}/transition`, { body: { action }, token: mode.token });
      }
    } catch (err) {
      if (err instanceof ApiError && err.status === 409 && mode.kind === 'cloud') {
        // Someone else moved it first; the stream brings the current state, a fresh board makes sure.
        const snap = await call<{ lastSeq: number; tickets: Ticket[] }>('/kds/board', { token: mode.token });
        setBoard(fromSnapshot(snap.tickets, Math.max(snap.lastSeq, boardRef.current.lastSeq)));
      }
    }
  }

  const stations = [...new Set([...board.tickets.values()].flatMap((t) => t.lines.map((l) => l.station)).filter((s): s is string => s !== null))].sort();
  const tickets = ticketsFor(board, station);

  return (
    <main>
      <header class="bar">
        <h1>Kitchen</h1>
        <span class={connected ? 'ok' : 'warn'}>{connected ? 'Live' : 'Reconnecting…'}</span>
        {mode.kind === 'hub' && <span class="muted">Local hub</span>}
        <label>
          Station{' '}
          <select value={station ?? ''} onChange={(e) => setStation(e.currentTarget.value || null)}>
            <option value="">All</option>
            {stations.map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
        </label>
        {!shiftStarted && (
          <button onClick={async () => { await bell.unlock(); setShiftStarted(bell.unlocked); }}>Start shift (enable sound)</button>
        )}
      </header>
      {awaiting.length > 0 && (
        <p class="notice" role="status">
          {awaiting.length} guest order{awaiting.length === 1 ? '' : 's'} waiting for staff to accept on the POS.
          <button class="link" onClick={() => setAwaiting([])}>Dismiss</button>
        </p>
      )}
      {tickets.length === 0 ? (
        <p class="empty">No tickets.</p>
      ) : (
        <ul class="tickets">
          {tickets.map((t) => (
            <li key={t.courseId} class={`ticket ${t.state.toLowerCase()}`}>
              <div class="ticket-head">
                <strong>{t.table ?? 'No table'}</strong>
                {t.courseNo > 0 && <span>Course {t.courseNo}</span>}
                <span class="state">{t.state === 'READY' ? 'Ready' : 'Cooking'}</span>
              </div>
              <ul class="lines">
                {t.lines.map((l) => (
                  <li key={l.lineId}>
                    <span class="qty">{l.qty}×</span> {l.name}
                    {l.modifiers.length > 0 && <div class="mods">{l.modifiers.join(', ')}</div>}
                  </li>
                ))}
              </ul>
              <div class="actions">
                {KITCHEN[t.state].map((a) => (
                  <button key={a} onClick={() => void act(t, a)}>{LABEL[a]}</button>
                ))}
              </div>
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
