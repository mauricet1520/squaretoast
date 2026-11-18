-- Slice 4: Stripe destination charges and the settlement ledger.

-- A retried start with the same idempotency key must hand the device the same intent without calling Stripe
-- again, so the intent's client secret is kept with the payment. It is not card data (PCI scope unchanged)
-- and is never logged.
alter table payment add column client_secret text;

-- Business-key idempotency for settlements: one CARD_SETTLEMENT per processor intent, whatever webhook
-- (charge.succeeded, charge.updated, replays) gets there first.
create unique index journal_txn_card_settlement_once on journal_txn (source_ref) where kind = 'CARD_SETTLEMENT';
