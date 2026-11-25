import { mulCents, sumCents } from '../shared/money';

// The guest's cart. Prices here are for display only: the order request carries ids and quantities, and the
// server prices every line itself. The same limits as the server keep a guest from building an order it refuses.

export type Option = { id: string; name: string; priceDeltaCents: number; available: boolean };
export type Group = { id: string; name: string; minSelect: number; maxSelect: number; options: Option[] };
export type MenuItem = {
  id: string;
  name: string;
  priceCents: number;
  currency: string;
  available: boolean;
  modifierGroups: Group[];
};

export type CartLine = { item: MenuItem; qty: number; options: Option[] };
export type Cart = { lines: CartLine[] };
export type CartError = 'QTY' | 'MODIFIERS' | 'UNAVAILABLE' | 'FULL';

export const MAX_QTY = 20;
export const MAX_LINES = 30;

export function emptyCart(): Cart {
  return { lines: [] };
}

export function addLine(cart: Cart, item: MenuItem, qty: number, optionIds: string[]):
    { ok: true; cart: Cart } | { ok: false; error: CartError } {
  if (!Number.isInteger(qty) || qty < 1 || qty > MAX_QTY) {
    return { ok: false, error: 'QTY' };
  }
  if (cart.lines.length >= MAX_LINES) {
    return { ok: false, error: 'FULL' };
  }
  if (!item.available) {
    return { ok: false, error: 'UNAVAILABLE' };
  }
  if (new Set(optionIds).size !== optionIds.length) {
    return { ok: false, error: 'MODIFIERS' };
  }
  const chosen: Option[] = [];
  for (const id of optionIds) {
    const opt = item.modifierGroups.flatMap((g) => g.options).find((o) => o.id === id);
    if (opt === undefined) {
      return { ok: false, error: 'MODIFIERS' };
    }
    chosen.push(opt);
  }
  for (const g of item.modifierGroups) {
    const n = chosen.filter((o) => g.options.includes(o)).length;
    if (n < g.minSelect || n > g.maxSelect) {
      return { ok: false, error: 'MODIFIERS' };
    }
  }
  if (chosen.some((o) => !o.available)) {
    return { ok: false, error: 'UNAVAILABLE' };
  }
  return { ok: true, cart: { lines: [...cart.lines, { item, qty, options: chosen }] } };
}

export function removeLine(cart: Cart, index: number): Cart {
  return { lines: cart.lines.filter((_, i) => i !== index) };
}

export function lineCents(line: CartLine): number {
  return mulCents(sumCents([line.item.priceCents, ...line.options.map((o) => o.priceDeltaCents)]), line.qty);
}

export function cartSubtotal(cart: Cart): number {
  return sumCents(cart.lines.map(lineCents));
}

export function orderRequest(cart: Cart, orderId: string) {
  return {
    orderId,
    lines: cart.lines.map((l) => ({ menuItemId: l.item.id, qty: l.qty, optionIds: l.options.map((o) => o.id) })),
  };
}
