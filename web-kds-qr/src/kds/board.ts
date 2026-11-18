import type { CourseState } from '../shared/course';

export type TicketLine = { lineId: string; name: string; qty: number; station: string | null; modifiers: string[] };

/** One fired course as the kitchen sees it; every `course` event carries the whole ticket. */
export type Ticket = {
  courseId: string;
  orderId: string;
  table: string | null;
  courseNo: number;
  state: CourseState;
  lines: TicketLine[];
};

export type Board = { lastSeq: number; tickets: ReadonlyMap<string, Ticket> };

const ON_BOARD: ReadonlySet<CourseState> = new Set(['FIRED', 'READY']);

export function emptyBoard(): Board {
  return { lastSeq: 0, tickets: new Map() };
}

export function fromSnapshot(tickets: readonly Ticket[], seq: number): Board {
  return { lastSeq: seq, tickets: new Map(tickets.filter((t) => ON_BOARD.has(t.state)).map((t) => [t.courseId, t])) };
}

/** Applies a stream event. Replays and duplicates (seq at or below what the board has) change nothing. */
export function applyEvent(board: Board, seq: number, ticket: Ticket): Board {
  if (seq <= board.lastSeq) {
    return board;
  }
  const tickets = new Map(board.tickets); // a Map keeps first-insertion order when a key is updated
  if (ON_BOARD.has(ticket.state)) {
    tickets.set(ticket.courseId, ticket);
  } else {
    tickets.delete(ticket.courseId);
  }
  return { lastSeq: seq, tickets };
}

/** Tickets for one station (only that station's lines), or every ticket when station is null. */
export function ticketsFor(board: Board, station: string | null): Ticket[] {
  const all = [...board.tickets.values()];
  if (station === null) {
    return all;
  }
  return all
    .map((t) => ({ ...t, lines: t.lines.filter((l) => l.station === station) }))
    .filter((t) => t.lines.length > 0);
}
