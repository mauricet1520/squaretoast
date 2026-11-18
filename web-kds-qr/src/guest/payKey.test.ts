import { describe, expect, it } from 'vitest';
import { forgetKey, keyFor } from './payKey';

function memory() {
  const m = new Map<string, string>();
  return {
    getItem: (k: string) => m.get(k) ?? null,
    setItem: (k: string, v: string) => void m.set(k, v),
    removeItem: (k: string) => void m.delete(k),
  };
}

const ids = (...list: string[]) => {
  let i = 0;
  return () => list[i++] ?? 'exhausted';
};

describe('payment idempotency keys', () => {
  it('reuses the key for a retry of the same tip, so a dropped connection cannot double-charge', () => {
    const s = memory();
    const next = ids('k1', 'k2');
    expect(keyFor(s, 'check-1', 500, next)).toEqual({ key: 'k1', withdraw: null });
    expect(keyFor(s, 'check-1', 500, next)).toEqual({ key: 'k1', withdraw: null });
  });

  it('uses a new key when the tip changes, and names the old payment to withdraw first', () => {
    const s = memory();
    const next = ids('k1', 'k2', 'k3');
    keyFor(s, 'check-1', 500, next);
    expect(keyFor(s, 'check-1', 700, next)).toEqual({ key: 'k2', withdraw: 'k1' });
    expect(keyFor(s, 'check-1', 700, next)).toEqual({ key: 'k2', withdraw: null });
  });

  it('keeps checks apart and starts fresh once a payment is done', () => {
    const s = memory();
    const next = ids('k1', 'k2', 'k3');
    keyFor(s, 'check-1', 500, next);
    expect(keyFor(s, 'check-2', 500, next).key).toBe('k2');
    forgetKey(s, 'check-1');
    expect(keyFor(s, 'check-1', 500, next)).toEqual({ key: 'k3', withdraw: null });
  });

  it('ignores a stored value it cannot read', () => {
    const s = memory();
    s.setItem('pay.check-1', '{not json');
    expect(keyFor(s, 'check-1', 500, ids('k1'))).toEqual({ key: 'k1', withdraw: null });
  });
});
