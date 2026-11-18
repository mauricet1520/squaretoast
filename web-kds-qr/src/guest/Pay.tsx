import { loadStripe, type Stripe, type StripeElements } from '@stripe/stripe-js';
import { useEffect, useRef, useState } from 'preact/hooks';
import { ApiError } from '../shared/api';
import { formatCents, parseCents, percentOf, sumCents } from '../shared/money';

export type Started = { paymentId: string; clientSecret: string; publishableKey: string };

const PRESETS = [15, 18, 20];

/** Tip choice in integer cents. The amount owed comes from the server; only the tip is the guest's. */
export function TipPicker({ dueCents, currency, onPay, busy }: {
  dueCents: number; currency: string; busy: boolean; onPay: (tipCents: number) => void;
}) {
  const [choice, setChoice] = useState<number | 'custom' | 0>(18);
  const [custom, setCustom] = useState('');
  const tip = choice === 'custom' ? parseCents(custom) : percentOf(dueCents, choice);
  return (
    <section>
      <h2>Add a tip</h2>
      <div class="chips" role="radiogroup" aria-label="Tip">
        {PRESETS.map((p) => (
          <button key={p} role="radio" aria-checked={choice === p} onClick={() => setChoice(p)}>
            {p}% <small>{formatCents(percentOf(dueCents, p), currency)}</small>
          </button>
        ))}
        <button role="radio" aria-checked={choice === 0} onClick={() => setChoice(0)}>No tip</button>
        <button role="radio" aria-checked={choice === 'custom'} onClick={() => setChoice('custom')}>Other</button>
      </div>
      {choice === 'custom' && (
        <label>
          Tip amount
          <input inputMode="decimal" value={custom} onInput={(e) => setCustom(e.currentTarget.value)} placeholder="0.00" />
        </label>
      )}
      <p class="total">Total {tip === null ? '—' : formatCents(sumCents([dueCents, tip]), currency)}</p>
      <button class="primary" disabled={busy || tip === null} onClick={() => tip !== null && onPay(tip)}>Continue to payment</button>
    </section>
  );
}

/**
 * Card entry happens only inside Stripe's Payment Element, an iframe served by js.stripe.com. This page never
 * sees a card number. The PaymentIntent lives on the platform account (destination charge), so no
 * stripeAccount option is passed.
 */
export function CardForm({ started, onPaid }: { started: Started; onPaid: () => void }) {
  const mount = useRef<HTMLDivElement>(null);
  const [stripe, setStripe] = useState<Stripe | null>(null);
  const [elements, setElements] = useState<StripeElements | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let alive = true;
    void loadStripe(started.publishableKey).then((s) => {
      if (!alive || s === null || mount.current === null) return;
      const els = s.elements({ clientSecret: started.clientSecret });
      els.create('payment').mount(mount.current);
      setStripe(s);
      setElements(els);
    });
    return () => {
      alive = false;
    };
  }, [started.clientSecret]);

  async function confirm() {
    if (stripe === null || elements === null) return;
    setBusy(true);
    setError(null);
    const { error: e } = await stripe.confirmPayment({
      elements,
      redirect: 'if_required',
      confirmParams: { return_url: window.location.href },
    });
    setBusy(false);
    if (e) {
      setError(e.type === 'card_error' || e.type === 'validation_error' ? e.message ?? 'Payment failed.' : 'Payment failed. Please try again.');
    } else {
      onPaid();
    }
  }

  return (
    <section>
      <div ref={mount} />
      {error && <p role="alert" class="error">{error}</p>}
      <button class="primary" disabled={busy || stripe === null} onClick={() => void confirm()}>Pay</button>
    </section>
  );
}

export function errorText(err: unknown): string {
  if (err instanceof ApiError) {
    switch (err.code) {
      case 'NOTHING_DUE': return 'This check is already paid or being paid.';
      case 'ORDER_NOT_ACCEPTED': return 'Your order has not been confirmed by staff yet.';
      case 'INVALID_CLAIM': return 'This payment code is no longer valid. Please ask your server for a new one.';
      case 'CLAIM_SUPERSEDED': return 'Your server issued a newer payment code. Please scan the latest one.';
      case 'CLAIM_REPLAYED': return 'This payment code was already used.';
      default: return err.status === 404 ? 'We could not find that.' : 'Something went wrong. Please try again.';
    }
  }
  return 'Could not reach the restaurant. Check your connection and try again.';
}
