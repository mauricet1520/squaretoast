import { describe, expect, it } from 'vitest';
import { formatCents, parseCents, percentOf, sumCents } from './money';

describe('tip entry', () => {
  it('parses dollars typed by a guest into cents without floating point', () => {
    expect(parseCents('12')).toBe(1200);
    expect(parseCents('12.5')).toBe(1250);
    expect(parseCents(' 0.07 ')).toBe(7);
    expect(parseCents('1.005')).toBeNull();
    expect(parseCents('-1')).toBeNull();
    expect(parseCents('1e3')).toBeNull();
    expect(parseCents('')).toBeNull();
  });

  it('computes percentage tips in integers, half up', () => {
    expect(percentOf(3432, 18)).toBe(618); // 617.76
    expect(percentOf(1250, 20)).toBe(250);
    expect(percentOf(5, 10)).toBe(1); // 0.5 rounds up
    expect(() => percentOf(10.5, 10)).toThrow(TypeError);
  });
});

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
