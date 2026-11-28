-- Slice B1: back-office users and cookie sessions. Device tokens stay separate (device table).

create table app_user (
    id             uuid primary key,
    merchant_id    uuid not null references merchant (id),
    email          text not null check (length(email) <= 254 and email ~ '^[^@[:space:]]+@[^@[:space:]]+$'),
    display_name   text not null,
    password_hash  text not null check (password_hash like '$argon2id$%'),
    disabled_at    timestamptz,
    created_at     timestamptz not null default now()
);
-- One account per email across all merchants, so login needs no merchant picker.
create unique index app_user_email_lower on app_user (lower(email));
create index app_user_merchant on app_user (merchant_id);

create table admin_session (
    token_hash    bytea primary key check (length(token_hash) = 32),
    user_id       uuid not null references app_user (id),
    csrf_hash     bytea not null check (length(csrf_hash) = 32),
    created_at    timestamptz not null default now(),
    last_seen_at  timestamptz not null default now(),
    revoked_at    timestamptz
);
create index admin_session_user on admin_session (user_id);

create table admin_login_attempt (
    id           bigint generated always as identity primary key,
    email_lower  text not null,
    ip           text not null,
    at           timestamptz not null default now(),
    ok           boolean not null
);
create index admin_login_attempt_email on admin_login_attempt (email_lower, at);
create index admin_login_attempt_ip on admin_login_attempt (ip, at);
