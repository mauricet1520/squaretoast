// Money is integer minor units (cents) paired with an ISO-4217 code. No floating-point arithmetic touches it.

const CURRENCY = /^[A-Z]{3}$/;

function requireCents(cents: number): number {
  if (!Number.isSafeInteger(cents)) {
    throw new TypeError(`not integer cents: ${cents}`);
  }
  return cents;
}

/** Formats cents by building the decimal string from integers, then letting Intl add symbol and grouping. */
export function formatCents(cents: number, currency: string, locale = 'en-US'): string {
  requireCents(cents);
  if (!CURRENCY.test(currency)) {
    throw new TypeError(`not an ISO-4217 code: ${currency}`);
  }
  const abs = Math.abs(cents);
  const decimal = `${cents < 0 ? '-' : ''}${Math.trunc(abs / 100)}.${String(abs % 100).padStart(2, '0')}`;
  // Intl.NumberFormat accepts a decimal string exactly (ES2023), so no binary rounding happens.
  return new Intl.NumberFormat(locale, { style: 'currency', currency }).format(decimal as Intl.StringNumericLiteral);
}

export function sumCents(values: readonly number[]): number {
  let total = 0;
  for (const v of values) {
    total += requireCents(v);
    if (!Number.isSafeInteger(total)) {
      throw new RangeError('cents overflow');
    }
  }
  return total;
}

/** "12", "12.5" or "12.50" dollars to cents, parsed as text so no binary fraction is involved. Null if invalid. */
export function parseCents(text: string): number | null {
  const m = /^(\d{1,6})(?:\.(\d{1,2}))?$/.exec(text.trim());
  if (m === null) {
    return null;
  }
  return Number(m[1]) * 100 + Number((m[2] ?? '').padEnd(2, '0'));
}

/** A percentage tip on cents, rounded half up, in integers only. */
export function percentOf(cents: number, percent: number): number {
  requireCents(cents);
  requireCents(percent);
  return Math.floor((cents * percent + 50) / 100);
}

export function mulCents(cents: number, qty: number): number {
  const r = requireCents(cents) * requireCents(qty);
  if (!Number.isSafeInteger(r)) {
    throw new RangeError('cents overflow');
  }
  return r;
}
