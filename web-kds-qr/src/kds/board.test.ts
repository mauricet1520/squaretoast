import { describe, expect, it } from 'vitest';
import { applyEvent, emptyBoard, fromSnapshot, ticketsFor, type Ticket } from './board';

const ticket = (courseId: string, state: Ticket['state'], stations: (string | null)[] = ['Grill']): Ticket => ({
  courseId,
  orderId: 'o-' + courseId,
  table: 'T4',
  courseNo: 1,
  state,
  lines: stations.map((station, i) => ({ lineId: `${courseId}-${i}`, name: 'Burger', qty: 1, station, modifiers: [] })),
});

describe('KDS board', () => {
  it('applies events in seq order and ignores anything older than what it has', () => {
    let b = applyEvent(emptyBoard(), 5, ticket('c1', 'FIRED'));
    b = applyEvent(b, 6, ticket('c1', 'READY'));
    b = applyEvent(b, 4, ticket('c1', 'FIRED')); // late duplicate from a replay
    b = applyEvent(b, 6, ticket('c1', 'FIRED')); // same seq again
    expect(b.lastSeq).toBe(6);
    expect(ticketsFor(b, null).map((t) => t.state)).toEqual(['READY']);
  });

  it('reverses a bump with recall', () => {
    let b = applyEvent(emptyBoard(), 1, ticket('c1', 'FIRED'));
    b = applyEvent(b, 2, ticket('c1', 'READY'));
    b = applyEvent(b, 3, ticket('c1', 'FIRED'));
    expect(ticketsFor(b, null)[0]?.state).toBe('FIRED');
  });

  it('drops courses the kitchen is done with or has not been sent', () => {
    let b = applyEvent(emptyBoard(), 1, ticket('c1', 'FIRED'));
    b = applyEvent(b, 2, ticket('c2', 'FIRED'));
    b = applyEvent(b, 3, ticket('c1', 'SERVED'));
    b = applyEvent(b, 4, ticket('c2', 'HOLD')); // unfired
    expect(ticketsFor(b, null)).toEqual([]);
  });

  it('keeps tickets in the order they first reached the kitchen', () => {
    let b = applyEvent(emptyBoard(), 1, ticket('c1', 'FIRED'));
    b = applyEvent(b, 2, ticket('c2', 'FIRED'));
    b = applyEvent(b, 3, ticket('c1', 'READY'));
    expect(ticketsFor(b, null).map((t) => t.courseId)).toEqual(['c1', 'c2']);
  });

  it('filters by station, showing only that station\'s lines', () => {
    const b = fromSnapshot([ticket('c1', 'FIRED', ['Grill', 'Fry']), ticket('c2', 'FIRED', ['Fry']), ticket('c3', 'FIRED', [null])], 9);
    const grill = ticketsFor(b, 'Grill');
    expect(grill.map((t) => t.courseId)).toEqual(['c1']);
    expect(grill[0]?.lines.map((l) => l.station)).toEqual(['Grill']);
    expect(ticketsFor(b, null)).toHaveLength(3);
    expect(b.lastSeq).toBe(9);
  });
});
