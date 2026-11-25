import { useEffect, useState } from 'preact/hooks';
import { call, store } from '../shared/api';
import { formatCents } from '../shared/money';
import { addLine, cartSubtotal, emptyCart, lineCents, orderRequest, removeLine, type Cart, type CartError, type MenuItem } from './cart';
import { CardForm, errorText, paymentDone, startPayment, TipPicker, type Started } from './Pay';

type TableView = { table: string; currency: string; menu: { items: MenuItem[] } };
type CheckSummary = { checkId: string; label: string; dueCents: number; status: string; currency: string };
type Bill = {
  checkId: string; status: string; currency: string;
  subtotalCents: number; serviceChargeCents: number; taxCents: number; paidCents: number; dueCents: number;
  lines: { name: string; qty: number; modifiers: string[]; amountCents: number }[];
};

const session = store('session');
const CART_ERRORS: Record<CartError, string> = {
  QTY: 'Choose between 1 and 20.',
  MODIFIERS: 'Check the required choices for this item.',
  UNAVAILABLE: 'Sorry, that is sold out.',
  FULL: 'Your order is full. Send it, then start another.',
};

export function TableApp({ qr }: { qr: string }) {
  const [table, setTable] = useState<TableView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState<'menu' | 'bill'>('menu');

  useEffect(() => {
    call<TableView>(`/t/${encodeURIComponent(qr)}`).then(setTable, (e) => setError(errorText(e)));
  }, [qr]);

  if (error) return <main class="narrow"><p role="alert" class="error">{error}</p></main>;
  if (table === null) return <main class="narrow"><p>Loading…</p></main>;
  return (
    <main class="narrow">
      <header class="bar">
        <h1>Table {table.table}</h1>
        <nav>
          <button aria-pressed={tab === 'menu'} onClick={() => setTab('menu')}>Order</button>
          <button aria-pressed={tab === 'bill'} onClick={() => setTab('bill')}>Bill &amp; pay</button>
        </nav>
      </header>
      {tab === 'menu' ? <Order qr={qr} table={table} onSent={() => setTab('bill')} /> : <Checks qr={qr} />}
    </main>
  );
}

function Order({ qr, table, onSent }: { qr: string; table: TableView; onSent: () => void }) {
  const [cart, setCart] = useState<Cart>(emptyCart);
  const [picking, setPicking] = useState<MenuItem | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function send() {
    // The order id doubles as the idempotency key, so a retry after a dropped connection cannot double-order.
    const pendingKey = `order.${qr}`;
    const orderId = session.getItem(pendingKey) ?? crypto.randomUUID();
    session.setItem(pendingKey, orderId);
    setBusy(true);
    try {
      await call(`/t/${encodeURIComponent(qr)}/orders`, { body: orderRequest(cart, orderId) });
      session.removeItem(pendingKey);
      setCart(emptyCart());
      setMessage('Sent! Staff will confirm your order shortly.');
      onSent();
    } catch (e) {
      setMessage(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  const items = table.menu.items;
  return (
    <>
      {message && <p role="status" class="notice">{message}</p>}
      <ul class="menu">
        {items.map((i) => (
          <li key={i.id}>
            <button disabled={!i.available} onClick={() => setPicking(i)}>
              <span>{i.name}</span>
              <span>{i.available ? formatCents(i.priceCents, i.currency) : 'Sold out'}</span>
            </button>
          </li>
        ))}
      </ul>
      {picking && (
        <Picker item={picking} onCancel={() => setPicking(null)} onAdd={(qty, ids) => {
          const r = addLine(cart, picking, qty, ids);
          if (r.ok) {
            setCart(r.cart);
            setPicking(null);
            return null;
          }
          return CART_ERRORS[r.error];
        }} />
      )}
      {cart.lines.length > 0 && (
        <section class="cart">
          <h2>Your order</h2>
          <ul>
            {cart.lines.map((l, i) => (
              <li key={i}>
                {l.qty}× {l.item.name}{l.options.length > 0 && ` (${l.options.map((o) => o.name).join(', ')})`}
                <span>{formatCents(lineCents(l), table.currency)}</span>
                <button class="link" onClick={() => setCart(removeLine(cart, i))}>Remove</button>
              </li>
            ))}
          </ul>
          <p class="total">Subtotal {formatCents(cartSubtotal(cart), table.currency)} <small>before tax</small></p>
          <button class="primary" disabled={busy} onClick={() => void send()}>Send order</button>
        </section>
      )}
    </>
  );
}

function Picker({ item, onAdd, onCancel }: { item: MenuItem; onAdd: (qty: number, ids: string[]) => string | null; onCancel: () => void }) {
  const [qty, setQty] = useState(1);
  const [ids, setIds] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const toggle = (groupMax: number, groupIds: string[], id: string) => {
    setIds((cur) => {
      if (cur.includes(id)) return cur.filter((x) => x !== id);
      // A pick-one group swaps the choice; a pick-several group stops at its maximum.
      if (groupMax === 1) return [...cur.filter((x) => !groupIds.includes(x)), id];
      return cur.filter((x) => groupIds.includes(x)).length >= groupMax ? cur : [...cur, id];
    });
  };
  return (
    <section class="picker" role="dialog" aria-label={item.name}>
      <h2>{item.name}</h2>
      {item.modifierGroups.map((g) => (
        <fieldset key={g.id}>
          <legend>{g.name} {g.minSelect > 0 ? '(required)' : ''}</legend>
          {g.options.map((o) => (
            <label key={o.id}>
              <input type={g.maxSelect === 1 ? 'radio' : 'checkbox'} name={g.id} checked={ids.includes(o.id)}
                disabled={!o.available} onChange={() => toggle(g.maxSelect, g.options.map((x) => x.id), o.id)} />
              {o.name}{o.priceDeltaCents !== 0 && ` ${o.priceDeltaCents > 0 ? '+' : ''}${formatCents(o.priceDeltaCents, item.currency)}`}
            </label>
          ))}
        </fieldset>
      ))}
      <label>
        Quantity
        <input type="number" min={1} max={20} value={qty} onInput={(e) => setQty(Number.parseInt(e.currentTarget.value, 10) || 0)} />
      </label>
      {error && <p role="alert" class="error">{error}</p>}
      <button class="primary" onClick={() => setError(onAdd(qty, ids))}>Add</button>
      <button class="link" onClick={onCancel}>Cancel</button>
    </section>
  );
}

function Checks({ qr }: { qr: string }) {
  const [checks, setChecks] = useState<CheckSummary[] | null>(null);
  const [open, setOpen] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    const load = () => call<{ checks: CheckSummary[] }>(`/t/${encodeURIComponent(qr)}/checks`).then((r) => alive && setChecks(r.checks), () => {});
    void load();
    const timer = window.setInterval(() => document.visibilityState === 'visible' && void load(), 10_000);
    return () => {
      alive = false;
      window.clearInterval(timer);
    };
  }, [qr]);

  if (open !== null) return <CheckPage qr={qr} checkId={open} onBack={() => setOpen(null)} />;
  if (checks === null) return <p>Loading…</p>;
  if (checks.length === 0) return <p>No open checks yet. Once staff confirm your order, your bill appears here.</p>;
  return (
    <ul class="menu">
      {checks.map((c) => (
        <li key={c.checkId}>
          <button onClick={() => setOpen(c.checkId)}>
            <span>{c.label}</span>
            <span>{c.status === 'OPEN' ? `${formatCents(c.dueCents, c.currency)} due` : 'Paid'}</span>
          </button>
        </li>
      ))}
    </ul>
  );
}

function CheckPage({ qr, checkId, onBack }: { qr: string; checkId: string; onBack: () => void }) {
  const [bill, setBill] = useState<Bill | null>(null);
  const [started, setStarted] = useState<Started | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [paid, setPaid] = useState(false);
  const path = `/t/${encodeURIComponent(qr)}/checks/${encodeURIComponent(checkId)}`;

  const reload = () => call<Bill>(path).then(setBill, (e) => setError(errorText(e)));
  useEffect(() => void reload(), [path]);

  async function pay(tipCents: number) {
    setBusy(true);
    setError(null);
    try {
      setStarted(await startPayment(`check.${checkId}`, tipCents,
        (key) => call<Started>(`${path}/payments`, { body: { idempotencyKey: key, tipCents } })));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  if (bill === null) return error ? <p role="alert" class="error">{error}</p> : <p>Loading…</p>;
  const c = bill.currency;
  return (
    <section>
      <button class="link" onClick={onBack}>← All checks</button>
      <h2>{paid || bill.status === 'PAID' ? 'Receipt' : 'Your bill'}</h2>
      <ul class="bill">
        {bill.lines.map((l, i) => (
          <li key={i}>
            <span>{l.qty}× {l.name}{l.modifiers.length > 0 && <small> ({l.modifiers.join(', ')})</small>}</span>
            <span>{formatCents(l.amountCents, c)}</span>
          </li>
        ))}
      </ul>
      <dl class="sums">
        <dt>Subtotal</dt><dd>{formatCents(bill.subtotalCents, c)}</dd>
        {bill.serviceChargeCents > 0 && <><dt>Service charge</dt><dd>{formatCents(bill.serviceChargeCents, c)}</dd></>}
        <dt>Tax</dt><dd>{formatCents(bill.taxCents, c)}</dd>
        {bill.paidCents > 0 && <><dt>Paid</dt><dd>−{formatCents(bill.paidCents, c)}</dd></>}
        <dt>Due</dt><dd><strong>{formatCents(bill.dueCents, c)}</strong></dd>
      </dl>
      {error && <p role="alert" class="error">{error}</p>}
      {paid ? (
        <p role="status" class="notice">Thank you! Your payment is processing.</p>
      ) : started ? (
        <>
          <CardForm started={started} onPaid={() => { setPaid(true); paymentDone(`check.${checkId}`); void reload(); }} />
          <button class="link" onClick={() => setStarted(null)}>Change tip</button>
        </>
      ) : bill.dueCents > 0 ? (
        <TipPicker dueCents={bill.dueCents} currency={c} busy={busy} onPay={(t) => void pay(t)} />
      ) : null}
    </section>
  );
}
