-- Slice 5b: POS device enrollment and auth, sync results, Terminal Locations.

-- Devices sign offline QR claims with an ECDSA P-256 key in Android Keystore (plan §9 item 9 amends D8).
-- Nothing has written this table before this slice, so the column swap needs no data migration.
alter table device drop column ed25519_pubkey;
alter table device add column public_key_spki bytea not null;
-- SHA-256 of the bearer token. The token itself is shown to the device once and never stored.
alter table device add column token_hash bytea not null unique check (octet_length(token_hash) = 32);
alter table device add column label text not null default '';
alter table device add column enrolled_at timestamptz not null default now();

-- One-time enrollment codes, stored hashed. Issued by merchant admin tooling (not part of this slice).
create table device_enrollment (
    code_hash    bytea primary key check (octet_length(code_hash) = 32),
    location_id  uuid not null references location (id),
    expires_at   timestamptz not null,
    used_at      timestamptz,
    device_id    uuid references device (id),
    check ((used_at is null) = (device_id is null))
);

-- A Terminal Location on the platform account (destination charges), one per merchant location.
alter table location add column stripe_terminal_location_id text
    check (stripe_terminal_location_id ~ '^tml_[A-Za-z0-9]+$');

-- A resent mutation that conflicted must get the same reason back.
alter table processed_client_mutation add column reason text;
