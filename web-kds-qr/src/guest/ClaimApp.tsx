import { useState } from 'preact/hooks';
import { call, store } from '../shared/api';
import { formatCents } from '../shared/money';
import { readClaim } from './claim';
import { CardForm, errorText, TipPicker, type Started } from './Pay';

const session = store('session');

/** Pay-at-table from an offline POS's QR code. The amount shown is the signed total; the server checks it. */
export function ClaimApp({ fragment }: { fragment: string }) {
  const parsed = readClaim(fragment);
  const [started, setStarted] = useState<Started | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [paid, setPaid] = useState(false);

  if (parsed === null) {
    return <main class="narrow"><p role="alert" class="error">This payment code is not readable. Please ask your server for a new one.</p></main>;
  }
  const { token, claim } = parsed;
  const expired = claim.expiresAtEpochSecond * 1000 <= Date.now();

  async function pay(tipCents: number) {
    const keyName = `claim.${claim.checkId}`;
    const key = session.getItem(keyName) ?? crypto.randomUUID();
    session.setItem(keyName, key);
    setBusy(true);
    setError(null);
    try {
      // The token goes in the request body, never in a URL.
      setStarted(await call<Started>('/claims/payments', { body: { claim: token, idempotencyKey: key, tipCents } }));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main class="narrow">
      <h1>Pay your check</h1>
      <dl class="sums">
        <dt>Subtotal</dt><dd>{formatCents(claim.subtotalCents, claim.currency)}</dd>
        {claim.serviceChargeCents > 0 && <><dt>Service charge</dt><dd>{formatCents(claim.serviceChargeCents, claim.currency)}</dd></>}
        <dt>Tax</dt><dd>{formatCents(claim.taxCents, claim.currency)}</dd>
        <dt>Total</dt><dd><strong>{formatCents(claim.totalCents, claim.currency)}</strong></dd>
      </dl>
      {error && <p role="alert" class="error">{error}</p>}
      {expired ? (
        <p role="alert" class="error">This payment code has expired. Please ask your server for a new one.</p>
      ) : paid ? (
        <p role="status" class="notice">Thank you! Your payment is processing.</p>
      ) : started ? (
        <CardForm started={started} onPaid={() => { setPaid(true); session.removeItem(`claim.${claim.checkId}`); }} />
      ) : (
        <TipPicker dueCents={claim.totalCents} currency={claim.currency} busy={busy} onPay={(t) => void pay(t)} />
      )}
    </main>
  );
}
