-- Slice 6: KDS stream tickets, guest QR pay, offline claim redemption (plan §11, S6-2/S6-7/S6-8).

-- One-time tickets that open the authenticated KDS stream. EventSource cannot send headers, and the device
-- token must never appear in a URL, so the KDS trades its token for a ticket valid once, for 60 seconds.
create table kds_stream_ticket (
    ticket_hash  bytea primary key check (octet_length(ticket_hash) = 32),
    device_id    uuid not null references device (id),
    location_id  uuid not null references location (id),
    expires_at   timestamptz not null,
    used_at      timestamptz
);
create index kds_stream_ticket_expiry on kds_stream_ticket (expires_at);

-- A redeemed offline claim keeps the exact signed bytes. Nothing has written this table before this slice.
alter table offline_check_claim add column location_id uuid not null references location (id);
alter table offline_check_claim add column payload bytea not null;
alter table offline_check_claim add column expires_at timestamptz not null;

-- Every nonce a device has signed, so a claim's nonce can never be reused for another check.
create table offline_claim_nonce (
    device_id  uuid not null references device (id),
    nonce      text not null check (length(nonce) between 1 and 128),
    check_id   uuid not null,
    used_at    timestamptz not null default now(),
    primary key (device_id, nonce)
);

-- A payment is for a server check or for an offline claim (the POS's own check id), never both.
alter table payment alter column check_id drop not null;
alter table payment add column claim_check_id uuid references offline_check_claim (check_id);
alter table payment add constraint payment_one_target check (num_nonnulls(check_id, claim_check_id) = 1);
create index payment_claim on payment (claim_check_id) where claim_check_id is not null;
