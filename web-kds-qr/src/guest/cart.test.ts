import { describe, expect, it } from 'vitest';
import { addLine, cartSubtotal, emptyCart, orderRequest, removeLine, type MenuItem } from './cart';

const burger: MenuItem = {
  id: 'burger',
  name: 'Burger',
  priceCents: 1500,
  currency: 'USD',
  available: true,
  modifierGroups: [
    {
      id: 'cheese',
      name: 'Cheese',
      minSelect: 0,
      maxSelect: 1,
      options: [
        { id: 'cheddar', name: 'Cheddar', priceDeltaCents: 100, available: true },
        { id: 'swiss', name: 'Swiss', priceDeltaCents: 150, available: false },
        { id: 'brie', name: 'Brie', priceDeltaCents: 200, available: true },
      ],
    },
    { id: 'bun', name: 'Bun', minSelect: 1, maxSelect: 1, options: [{ id: 'brioche', name: 'Brioche', priceDeltaCents: 0, available: true }] },
  ],
};
const soldOut: MenuItem = { ...burger, id: 'gone', available: false, modifierGroups: [] };

describe('guest cart', () => {
  it('adds lines and totals them in integer cents', () => {
    const r = addLine(emptyCart(), burger, 2, ['cheddar', 'brioche']);
    expect(r.ok).toBe(true);
    if (!r.ok) return;
    expect(cartSubtotal(r.cart)).toBe(3200);
    const r2 = addLine(r.cart, burger, 1, ['brioche']);
    expect(r2.ok && cartSubtotal(r2.cart)).toBe(4700);
  });

  it.each([
    ['qty 0', burger, 0, ['brioche'], 'QTY'],
    ['qty 21', burger, 21, ['brioche'], 'QTY'],
    ['fractional qty', burger, 1.5, ['brioche'], 'QTY'],
    ['missing required bun', burger, 1, [], 'MODIFIERS'],
    ['two cheeses', burger, 1, ['cheddar', 'brie', 'brioche'], 'MODIFIERS'],
    ['unknown option', burger, 1, ['ranch', 'brioche'], 'MODIFIERS'],
    ['86d option', burger, 1, ['swiss', 'brioche'], 'UNAVAILABLE'],
    ['86d item', soldOut, 1, [], 'UNAVAILABLE'],
  ] as const)('refuses %s', (_n, item, qty, options, error) => {
    const r = addLine(emptyCart(), item, qty, [...options]);
    expect(r).toEqual({ ok: false, error });
  });

  it('caps a cart at 30 lines', () => {
    let cart = emptyCart();
    for (let i = 0; i < 30; i++) {
      const r = addLine(cart, burger, 1, ['brioche']);
      if (!r.ok) throw new Error(r.error);
      cart = r.cart;
    }
    expect(addLine(cart, burger, 1, ['brioche'])).toEqual({ ok: false, error: 'FULL' });
    expect(removeLine(cart, 0).lines).toHaveLength(29);
  });

  it('sends only ids and quantities, never prices', () => {
    const r = addLine(emptyCart(), burger, 2, ['cheddar', 'brioche']);
    if (!r.ok) throw new Error(r.error);
    expect(orderRequest(r.cart, 'order-1')).toEqual({
      orderId: 'order-1',
      lines: [{ menuItemId: 'burger', qty: 2, optionIds: ['cheddar', 'brioche'] }],
    });
  });
});
