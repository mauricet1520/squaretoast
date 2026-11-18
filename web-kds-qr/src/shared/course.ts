// The course state machine, identical to the backend's and Android's (checked against shared-test-vectors).

export type CourseState = 'HOLD' | 'FIRED' | 'READY' | 'SERVED';
export type CourseAction = 'FIRE' | 'UNFIRE' | 'BUMP' | 'RECALL' | 'SERVE';

export const ACTIONS: readonly CourseAction[] = ['FIRE', 'UNFIRE', 'BUMP', 'RECALL', 'SERVE'];

const NEXT: Record<CourseState, Partial<Record<CourseAction, CourseState>>> = {
  HOLD: { FIRE: 'FIRED' },
  FIRED: { UNFIRE: 'HOLD', BUMP: 'READY' },
  READY: { RECALL: 'FIRED', SERVE: 'SERVED' },
  SERVED: { RECALL: 'READY' },
};

export function nextState(from: CourseState, action: CourseAction): CourseState | null {
  return NEXT[from][action] ?? null;
}

export function actionsFrom(from: CourseState): CourseAction[] {
  return ACTIONS.filter((a) => nextState(from, a) !== null);
}
