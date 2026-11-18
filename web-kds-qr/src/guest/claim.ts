// An offline POS shows a QR code whose URL fragment holds base64url(payload).base64url(signature). The fragment
// never reaches a server in the URL or a Referer header. This page decodes the payload only to show the amount;
// the server verifies the signature and charges exactly the signed total.

export type Claim = {
  checkId: string;
  locationId: string;
  subtotalCents: number;
  taxCents: number;
  serviceChargeCents: number;
  totalCents: number;
  currency: string;
  expiresAtEpochSecond: number;
};

const PART = /^[A-Za-z0-9_-]+$/;

function decodeBase64Url(s: string): string | null {
  if (!PART.test(s)) {
    return null;
  }
  try {
    const bin = atob(s.replace(/-/g, '+').replace(/_/g, '/'));
    return new TextDecoder('utf-8', { fatal: true }).decode(Uint8Array.from(bin, (c) => c.charCodeAt(0)));
  } catch {
    return null;
  }
}

const isCents = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0;
const isText = (v: unknown): v is string => typeof v === 'string' && v.length > 0 && v.length <= 64;

export function readClaim(fragment: string): { token: string; claim: Claim } | null {
  const token = fragment.startsWith('#') ? fragment.slice(1) : fragment;
  const parts = token.split('.');
  if (parts.length !== 2 || !PART.test(parts[1] ?? '')) {
    return null;
  }
  const json = decodeBase64Url(parts[0] ?? '');
  if (json === null) {
    return null;
  }
  let raw: unknown;
  try {
    raw = JSON.parse(json);
  } catch {
    return null;
  }
  if (typeof raw !== 'object' || raw === null) {
    return null;
  }
  const c = raw as Record<string, unknown>;
  if (!isText(c.checkId) || !isText(c.locationId) || !isCents(c.subtotalCents) || !isCents(c.taxCents)
      || !isCents(c.serviceChargeCents) || !isCents(c.totalCents) || !isCents(c.expiresAtEpochSecond)
      || typeof c.currency !== 'string' || !/^[A-Z]{3}$/.test(c.currency)) {
    return null;
  }
  return {
    token,
    claim: {
      checkId: c.checkId,
      locationId: c.locationId,
      subtotalCents: c.subtotalCents,
      taxCents: c.taxCents,
      serviceChargeCents: c.serviceChargeCents,
      totalCents: c.totalCents,
      currency: c.currency,
      expiresAtEpochSecond: c.expiresAtEpochSecond,
    },
  };
}
