import { describe, expect, it } from 'vitest';
import { formatCents, sumCents } from './money';

describe('money is integer cents only', () => {
  it('formats cents without floating point', () => {
    expect(formatCents(123456, 'USD')).toBe('$1,234.56');
    expect(formatCents(5, 'USD')).toBe('$0.05');
    expect(formatCents(-5, 'USD')).toBe('-$0.05');
    expect(formatCents(0, 'USD')).toBe('$0.00');
    // 0.1 + 0.2 style drift cannot happen: 30 cents stays 30 cents.
    expect(formatCents(sumCents([10, 20]), 'USD')).toBe('$0.30');
  });

  it('refuses fractions, NaN and unsafe integers', () => {
    expect(() => formatCents(1.5, 'USD')).toThrow(TypeError);
    expect(() => formatCents(Number.NaN, 'USD')).toThrow(TypeError);
    expect(() => formatCents(2 ** 53, 'USD')).toThrow(TypeError);
    expect(() => sumCents([0.1, 0.2])).toThrow(TypeError);
    expect(() => sumCents([Number.MAX_SAFE_INTEGER, 1])).toThrow(RangeError);
  });

  it('refuses a currency that is not an ISO-4217 code', () => {
    expect(() => formatCents(100, 'usd')).toThrow(TypeError);
    expect(() => formatCents(100, 'US')).toThrow(TypeError);
  });
});
