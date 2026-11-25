import { describe, expect, it } from 'vitest';
import { readClaim } from './claim';

const b64url = (s: string) => btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

const payload = {
  checkId: '6f1c2c1e-0000-4000-8000-000000000001',
  locationId: '6f1c2c1e-0000-4000-8000-000000000002',
  deviceId: '6f1c2c1e-0000-4000-8000-000000000003',
  linesDigest: 'abc',
  subtotalCents: 4000,
  taxCents: 290,
  serviceChargeCents: 0,
  totalCents: 4290,
  currency: 'USD',
  expiresAtEpochSecond: 1_900_000_000,
  nonce: 'n1',
};

describe('offline claim from the QR fragment', () => {
  it('reads the claim for display and keeps the exact token for the server', () => {
    const token = `${b64url(JSON.stringify(payload))}.${b64url('sig')}`;
    const c = readClaim('#' + token);
    expect(c?.token).toBe(token);
    expect(c?.claim.totalCents).toBe(4290);
    expect(c?.claim.currency).toBe('USD');
  });

  it.each([
    ['empty', ''],
    ['no signature', '#' + b64url(JSON.stringify(payload))],
    ['three parts', `#${b64url(JSON.stringify(payload))}.a.b`],
    ['not base64url', '#!!!.abc'],
    ['not JSON', `#${b64url('nope')}.${b64url('sig')}`],
    ['fractional cents', `#${b64url(JSON.stringify({ ...payload, totalCents: 42.9 }))}.${b64url('sig')}`],
    ['missing total', `#${b64url(JSON.stringify({ ...payload, totalCents: undefined }))}.${b64url('sig')}`],
    ['bad currency', `#${b64url(JSON.stringify({ ...payload, currency: 'usd' }))}.${b64url('sig')}`],
  ])('rejects %s', (_name, fragment) => {
    expect(readClaim(fragment)).toBeNull();
  });
});
