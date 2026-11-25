// Idempotency keys for guest payments. A retry of the same payment (same check, same tip) reuses its key, so a
// dropped connection cannot charge twice. A different tip is a different payment: it gets a new key, and the old
// unconfirmed payment must be withdrawn first, or it would keep holding the check's balance.

type Store = { getItem(k: string): string | null; setItem(k: string, v: string): void; removeItem(k: string): void };

const slot = (scope: string) => `pay.${scope}`;

function read(store: Store, scope: string): { key: string; tip: number } | null {
  try {
    const v = JSON.parse(store.getItem(slot(scope)) ?? 'null') as unknown;
    if (typeof v === 'object' && v !== null) {
      const r = v as Record<string, unknown>;
      if (typeof r.key === 'string' && typeof r.tip === 'number') {
        return { key: r.key, tip: r.tip };
      }
    }
  } catch {
    // unreadable: start over
  }
  return null;
}

/** The key to pay {@code tipCents} with, and the earlier payment's key to withdraw first, if the tip changed. */
export function keyFor(store: Store, scope: string, tipCents: number, newKey: () => string): { key: string; withdraw: string | null } {
  const prior = read(store, scope);
  if (prior !== null && prior.tip === tipCents) {
    return { key: prior.key, withdraw: null };
  }
  const key = newKey();
  store.setItem(slot(scope), JSON.stringify({ key, tip: tipCents }));
  return { key, withdraw: prior?.key ?? null };
}

/** Call once a payment completed, so the next one on this check starts fresh. */
export function forgetKey(store: Store, scope: string): void {
  store.removeItem(slot(scope));
}
