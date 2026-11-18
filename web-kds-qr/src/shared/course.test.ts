import { describe, expect, it } from 'vitest';
import vectors from '../../../shared-test-vectors/course-transitions.json';
import { actionsFrom, nextState, type CourseAction, type CourseState } from './course';

type Vector = { from: CourseState; action: CourseAction; to: CourseState | null };

describe('course state machine (shared vectors with backend and Android)', () => {
  const all = (vectors as { transitions: Vector[] }).transitions;

  it('covers every vector', () => {
    expect(all.length).toBe(20);
  });

  it.each(all)('$from + $action -> $to', ({ from, action, to }) => {
    expect(nextState(from, action)).toBe(to);
  });

  it('offers only the legal actions as KDS buttons', () => {
    for (const from of ['HOLD', 'FIRED', 'READY', 'SERVED'] as CourseState[]) {
      const legal = all.filter((v) => v.from === from && v.to !== null).map((v) => v.action);
      expect(actionsFrom(from)).toEqual(legal);
    }
  });
});
