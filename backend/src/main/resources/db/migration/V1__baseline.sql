-- SquareToast V1 baseline.
-- Conventions: uuid PKs (UUIDv7, client-generatable); bigserial for append-only logs;
-- money = bigint *_cents + char(3) currency; enums = text + CHECK; tax rates in ppm.
-- Invariant violations raised by triggers use SQLSTATE 23514 (check_violation).

create extension if not exists btree_gist;

-- =====================================================================
-- Merchant, pricing ($0/mo + processing spread), state compliance
-- =====================================================================

create table merchant (
    id          uuid primary key,
    legal_name  text not null,
    status      text not null default 'ACTIVE' check (status in ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    created_at  timestamptz not null default now()
);

create table processor_account (
    id                   uuid primary key,
    merchant_id          uuid not null references merchant (id),
    processor            text not null check (processor in ('STRIPE', 'ADYEN', 'OTHER')),
    external_account_id  text not null,
    priority             int  not null default 0 check (priority >= 0),
    unique (merchant_id, processor)
);

create table pricing_plan (
    id                 uuid primary key,
    code               text not null unique,
    monthly_fee_cents  bigint not null default 0 check (monthly_fee_cents = 0),  -- software is always $0/mo
    cp_spread_bps      int    not null check (cp_spread_bps between 0 and 10000),
    cp_fixed_cents     bigint not null default 0 check (cp_fixed_cents >= 0),
    cnp_spread_bps     int    not null check (cnp_spread_bps between 0 and 10000),
    cnp_fixed_cents    bigint not null default 0 check (cnp_fixed_cents >= 0),
    active             boolean not null default true
);

create table merchant_pricing (
    merchant_id     uuid not null references merchant (id),
    plan_id         uuid not null references pricing_plan (id),
    effective_from  timestamptz not null,
    effective_to    timestamptz,
    primary key (merchant_id, effective_from),
    check (effective_to is null or effective_to > effective_from),
    exclude using gist (
        merchant_id with =,
        tstzrange(effective_from, coalesce(effective_to, 'infinity'::timestamptz)) with &&)
);

create table state_compliance_profile (
    state_code                        char(2) primary key check (state_code ~ '^[A-Z]{2}$'),
    tip_credit_allowed                boolean not null,
    min_wage_cents                    bigint  not null check (min_wage_cents > 0),
    min_cash_wage_cents               bigint  not null check (min_cash_wage_cents > 0),
    tip_card_fee_deduction_allowed    boolean not null,
    mandatory_service_charge_taxable  boolean not null,
    effective_from                    date    not null,
    check (min_cash_wage_cents <= min_wage_cents),
    check (tip_credit_allowed or min_cash_wage_cents = min_wage_cents),
    -- CLAUDE.md 5: card-fee deduction from tips is hard-blocked in CA, ME, MA, DE.
    check (not (state_code in ('CA', 'ME', 'MA', 'DE') and tip_card_fee_deduction_allowed))
);

create table location (
    id                              uuid primary key,
    merchant_id                     uuid not null references merchant (id),
    name                            text not null,
    state_code                      char(2) not null references state_compliance_profile (state_code),
    timezone                        text not null,
    currency                        char(3) not null default 'USD' check (currency ~ '^[A-Z]{3}$'),
    uses_tip_credit                 boolean not null default false,
    ca_80_80_applies                boolean not null default false,
    ca_separate_accounting_elected  boolean not null default false,
    created_at                      timestamptz not null default now()
);

create function enforce_state_tip_credit_revocation() returns trigger language plpgsql as $$
begin
    if not new.tip_credit_allowed and exists (select 1 from location
                                              where state_code = new.state_code and uses_tip_credit) then
        raise exception 'state % still has locations taking a tip credit', new.state_code
            using errcode = 'check_violation';
    end if;
    return new;
end $$;

create trigger state_tip_credit_revocation before update on state_compliance_profile
    for each row execute function enforce_state_tip_credit_revocation();

-- BOH-in-pool check references tip_pool/tip_pool_member/employee, created later in this migration;
-- plpgsql resolves names at execution time.
create function enforce_location_tip_credit() returns trigger language plpgsql as $$
begin
    if new.uses_tip_credit and not (select tip_credit_allowed from state_compliance_profile
                                    where state_code = new.state_code) then
        raise exception 'state % does not allow a tip credit', new.state_code using errcode = 'check_violation';
    end if;
    if tg_op = 'UPDATE' and new.uses_tip_credit and not old.uses_tip_credit and exists (
            select 1 from tip_pool p
            join tip_pool_member m on m.pool_id = p.id
            join employee e on e.id = m.employee_id
            where p.location_id = new.id and p.period_end > now() and (m.boh_snapshot or e.boh)) then
        raise exception 'location % has BOH staff in an open tip pool; cannot start taking a tip credit', new.id
            using errcode = 'check_violation';
    end if;
    return new;
end $$;

create trigger location_tip_credit before insert or update on location
    for each row execute function enforce_location_tip_credit();

create table tax_rule (
    id                   uuid primary key,
    state_code           char(2) not null references state_compliance_profile (state_code),
    jurisdiction_code    text not null,
    tax_category         text not null,
    fulfillment_channel  text check (fulfillment_channel in ('DINE_IN', 'TAKEOUT', 'DELIVERY')),  -- null = any
    rate_ppm             int  not null check (rate_ppm between 0 and 1000000),
    effective_from       date not null,
    effective_to         date,
    check (effective_to is null or effective_to > effective_from)
);
create index tax_rule_lookup on tax_rule (state_code, tax_category, fulfillment_channel);

-- =====================================================================
-- Unified inventory -> menu item / modifier option (atomic 86ing)
-- =====================================================================

create table kds_station (
    id           uuid primary key,
    location_id  uuid not null references location (id),
    name         text not null
);

create table inventory_item (
    id              uuid primary key,
    location_id     uuid not null references location (id),
    name            text not null,
    tracked         boolean not null default false,
    on_hand         int,
    manually_86ed   boolean not null default false,
    version         bigint not null default 0,
    check (not tracked or on_hand is not null),
    check (on_hand is null or on_hand >= 0)
);

create table menu_item (
    id              uuid primary key,
    location_id     uuid not null references location (id),
    name            text not null,
    price_cents     bigint not null check (price_cents >= 0),
    tax_category    text not null,
    default_course  int  not null default 1 check (default_course >= 1),
    temp            text not null default 'NA' check (temp in ('HOT', 'COLD', 'NA')),
    visible_pos     boolean not null default true,
    visible_qr      boolean not null default true,
    kds_station_id  uuid references kds_station (id),
    active          boolean not null default true,
    version         bigint not null default 0
);

create table modifier_group (
    id           uuid primary key,
    location_id  uuid not null references location (id),
    name         text not null,
    min_select   int not null default 0,
    max_select   int not null default 1,
    check (min_select >= 0 and max_select >= min_select)
);

-- Groups are shared across items ("linked modifiers").
create table menu_item_modifier_group (
    menu_item_id       uuid not null references menu_item (id),
    modifier_group_id  uuid not null references modifier_group (id),
    sort               int  not null default 0,
    primary key (menu_item_id, modifier_group_id)
);

create table modifier_option (
    id                 uuid primary key,
    modifier_group_id  uuid not null references modifier_group (id),
    name               text not null,
    price_delta_cents  bigint not null default 0,  -- may be negative ("no cheese")
    active             boolean not null default true
);

create table inventory_consumption (
    id                  uuid primary key,
    menu_item_id        uuid references menu_item (id),
    modifier_option_id  uuid references modifier_option (id),
    inventory_item_id   uuid not null references inventory_item (id),
    qty                 int  not null check (qty > 0),
    check (num_nonnulls(menu_item_id, modifier_option_id) = 1)
);
create index inventory_consumption_inv on inventory_consumption (inventory_item_id);

-- =====================================================================
-- Tables, devices, orders, seats, coursing, splits
-- =====================================================================

create table dining_table (
    id           uuid primary key,
    location_id  uuid not null references location (id),
    label        text not null,
    qr_token     text not null unique
);

create table device (
    id              uuid primary key,
    location_id     uuid not null references location (id),
    ed25519_pubkey  bytea not null,
    revoked_at      timestamptz
);

create table orders (
    id                   uuid primary key,
    location_id          uuid not null references location (id),
    table_id             uuid references dining_table (id),
    fulfillment_channel  text not null check (fulfillment_channel in ('DINE_IN', 'TAKEOUT', 'DELIVERY')),
    status               text not null default 'OPEN' check (status in ('OPEN', 'COMMITTED', 'CLOSED', 'VOID')),
    origin_device_id     uuid references device (id),
    opened_at            timestamptz not null default now(),
    committed_at         timestamptz,
    version              bigint not null default 0
);

create table seat (
    id        uuid primary key,
    order_id  uuid not null references orders (id),
    seat_no   int  not null check (seat_no >= 1),
    unique (order_id, seat_no)
);

create table course (
    id         uuid primary key,
    order_id   uuid not null references orders (id),
    course_no  int  not null check (course_no >= 1),
    state      text not null default 'HOLD' check (state in ('HOLD', 'FIRE', 'PREPPING', 'READY', 'SERVED')),
    version    bigint not null default 0,
    unique (order_id, course_no)
);

-- Append-only; reversals (e.g. READY -> PREPPING) are new rows.
create table course_transition (
    seq         bigserial primary key,
    course_id   uuid not null references course (id),
    from_state  text not null check (from_state in ('HOLD', 'FIRE', 'PREPPING', 'READY', 'SERVED')),
    to_state    text not null check (to_state in ('HOLD', 'FIRE', 'PREPPING', 'READY', 'SERVED')),
    actor_id    uuid,
    reason      text,
    at          timestamptz not null default now(),
    check (from_state <> to_state)
);

create table order_line (
    id                uuid primary key,
    order_id          uuid not null references orders (id),
    seat_id           uuid references seat (id),
    course_id         uuid not null references course (id),
    menu_item_id      uuid not null references menu_item (id),
    qty               int  not null check (qty > 0),
    unit_price_cents  bigint not null check (unit_price_cents >= 0),  -- snapshot at commit
    tax_category      text not null,
    temp              text not null check (temp in ('HOT', 'COLD', 'NA')),
    name_snapshot     text not null
);

create table order_line_modifier (
    id                  uuid primary key,
    order_line_id       uuid not null references order_line (id),
    modifier_option_id  uuid not null references modifier_option (id),
    price_delta_cents   bigint not null,
    name_snapshot       text not null
);

-- Mandatory charges are modelled separately from voluntary tips (never mixed).
create table service_charge (
    id            uuid primary key,
    order_id      uuid not null references orders (id),
    kind          text not null check (kind in ('MANDATORY', 'AUTO_GRATUITY', 'DELIVERY_FEE')),
    amount_cents  bigint check (amount_cents >= 0),
    rate_bps      int    check (rate_bps between 0 and 10000),
    taxable       boolean not null,
    check (num_nonnulls(amount_cents, rate_bps) = 1)
);

create table guest_check (
    id        uuid primary key,
    order_id  uuid not null references orders (id),
    label     text not null,
    status    text not null default 'OPEN' check (status in ('OPEN', 'PAID', 'VOID'))
);

create table check_allocation (
    check_id       uuid not null references guest_check (id),
    order_line_id  uuid not null references order_line (id),
    numerator      int  not null check (numerator > 0),
    denominator    int  not null check (denominator > 0),
    primary key (check_id, order_line_id),
    check (numerator <= denominator)
);

create function enforce_allocation_same_order() returns trigger language plpgsql as $$
begin
    if (select order_id from guest_check where id = new.check_id)
       is distinct from (select order_id from order_line where id = new.order_line_id) then
        raise exception 'check % and order_line % belong to different orders', new.check_id, new.order_line_id
            using errcode = 'check_violation';
    end if;
    return new;
end $$;

create trigger check_allocation_same_order before insert or update on check_allocation
    for each row execute function enforce_allocation_same_order();

-- Exact rational sum per order line must be 1 (if the line is allocated at all).
-- An allocated line may return to zero rows (fully un-split back to the order).
create function assert_whole_line(line uuid) returns void language plpgsql as $$
declare
    n bigint := 0;
    d bigint := 1;
    g bigint;
    r record;
    cnt int := 0;
begin
    for r in select numerator, denominator from check_allocation where order_line_id = line loop
        n := n * r.denominator + r.numerator * d;
        d := d * r.denominator;
        g := gcd(n, d);
        n := n / g;
        d := d / g;
        cnt := cnt + 1;
    end loop;
    if cnt > 0 and n <> d then
        raise exception 'allocations for order_line % sum to %/% (must be exactly 1)', line, n, d
            using errcode = 'check_violation';
    end if;
end $$;

create function enforce_whole_line_allocation() returns trigger language plpgsql as $$
begin
    if tg_op in ('INSERT', 'UPDATE') then
        perform assert_whole_line(new.order_line_id);
    end if;
    if tg_op = 'DELETE' or (tg_op = 'UPDATE' and old.order_line_id <> new.order_line_id) then
        perform assert_whole_line(old.order_line_id);
    end if;
    return null;
end $$;

create constraint trigger check_allocation_whole
    after insert or update or delete on check_allocation
    deferrable initially deferred
    for each row execute function enforce_whole_line_allocation();

-- =====================================================================
-- Payments, append-only double-entry ledger
-- =====================================================================

create table payment (
    id                     uuid primary key,
    check_id               uuid not null references guest_check (id),
    processor              text not null check (processor in ('STRIPE', 'ADYEN', 'OTHER', 'CASH')),
    processor_intent_ref   text unique,
    method                 text not null check (method in ('CARD_PRESENT', 'QR_CNP', 'CASH', 'BT_READER_OFFLINE')),
    amount_cents           bigint not null check (amount_cents >= 0),
    tip_cents              bigint not null default 0 check (tip_cents >= 0),
    application_fee_cents  bigint not null default 0 check (application_fee_cents >= 0),
    currency               char(3) not null default 'USD' check (currency ~ '^[A-Z]{3}$'),
    status                 text not null check (status in
                               ('PENDING', 'REQUIRES_ACTION', 'SUCCEEDED', 'FAILED', 'CANCELED', 'REFUNDED')),
    idempotency_key        uuid not null unique,
    created_at             timestamptz not null default now(),
    check (application_fee_cents <= amount_cents)
);

create table journal_txn (
    id           uuid primary key,
    location_id  uuid not null references location (id),
    kind         text not null,
    source_ref   text not null,
    occurred_at  timestamptz not null default now()
);

create table journal_line (
    id            bigserial primary key,
    txn_id        uuid not null references journal_txn (id),
    account       text not null check (account in ('SALES', 'TAX_PAYABLE', 'TIPS_PAYABLE', 'SERVICE_CHARGE',
                      'PLATFORM_FEE', 'PROCESSOR_FEE', 'MERCHANT_CASH', 'TIP_FEE_DEDUCTION', 'REFUNDS')),
    amount_cents  bigint not null,  -- signed; sum per txn must be 0
    currency      char(3) not null check (currency ~ '^[A-Z]{3}$')
);
create index journal_line_txn on journal_line (txn_id);

create function reject_append_only_mutation() returns trigger language plpgsql as $$
begin
    raise exception '% is append-only', tg_table_name using errcode = 'check_violation';
end $$;

create trigger journal_txn_append_only before update or delete on journal_txn
    for each row execute function reject_append_only_mutation();
create trigger journal_txn_no_truncate before truncate on journal_txn
    for each statement execute function reject_append_only_mutation();
create trigger journal_line_append_only before update or delete on journal_line
    for each row execute function reject_append_only_mutation();
create trigger journal_line_no_truncate before truncate on journal_line
    for each statement execute function reject_append_only_mutation();
create trigger course_transition_append_only before update or delete on course_transition
    for each row execute function reject_append_only_mutation();
create trigger course_transition_no_truncate before truncate on course_transition
    for each statement execute function reject_append_only_mutation();

create function enforce_journal_txn_has_lines() returns trigger language plpgsql as $$
begin
    if (select count(*) from journal_line where txn_id = new.id) < 2 then
        raise exception 'journal txn % needs at least two lines', new.id using errcode = 'check_violation';
    end if;
    return null;
end $$;

create constraint trigger journal_txn_has_lines
    after insert on journal_txn
    deferrable initially deferred
    for each row execute function enforce_journal_txn_has_lines();

create function enforce_balanced_journal() returns trigger language plpgsql as $$
declare
    total bigint;
    currencies int;
begin
    select coalesce(sum(amount_cents), 0), count(distinct currency) into total, currencies
    from journal_line where txn_id = new.txn_id;
    if total <> 0 then
        raise exception 'journal txn % unbalanced by % cents', new.txn_id, total using errcode = 'check_violation';
    end if;
    if currencies > 1 then
        raise exception 'journal txn % mixes currencies', new.txn_id using errcode = 'check_violation';
    end if;
    return null;
end $$;

create constraint trigger journal_line_balanced
    after insert on journal_line
    deferrable initially deferred
    for each row execute function enforce_balanced_journal();

-- =====================================================================
-- Labor / FLSA tip pools
-- =====================================================================

create table employee (
    id                 uuid primary key,
    location_id        uuid not null references location (id),
    display_name       text not null,
    role               text not null,
    is_manager         boolean not null default false,
    is_supervisor      boolean not null default false,
    owner_equity_bps   int     not null default 0 check (owner_equity_bps between 0 and 10000),
    owner_active_mgmt  boolean not null default false,
    boh                boolean not null default false
);

create table tip_pool (
    id                  uuid primary key,
    location_id         uuid not null references location (id),
    period_start        timestamptz not null,
    period_end          timestamptz not null,
    distribution_basis  text not null check (distribution_basis in ('HOURS', 'POINTS')),
    check (period_end > period_start)
);

create table tip_pool_member (
    pool_id                     uuid not null references tip_pool (id),
    employee_id                 uuid not null references employee (id),
    role_snapshot               text not null,
    is_manager_snapshot         boolean not null,
    is_supervisor_snapshot      boolean not null,
    owner_equity_bps_snapshot   int     not null check (owner_equity_bps_snapshot between 0 and 10000),
    owner_active_mgmt_snapshot  boolean not null,
    boh_snapshot                boolean not null,
    hours_x100                  int not null default 0 check (hours_x100 >= 0),
    points                      int not null default 0 check (points >= 0),
    primary key (pool_id, employee_id),
    -- FLSA: managers, supervisors and 20%+ owners actively managing are never pool members.
    check (not is_manager_snapshot),
    check (not is_supervisor_snapshot),
    check (not (owner_equity_bps_snapshot >= 2000 and owner_active_mgmt_snapshot))
);

-- Eligibility is judged on the authoritative employee row as well as the snapshot:
-- a caller cannot launder a manager/supervisor/owner/BOH into a pool via false snapshot values.
create function enforce_tip_pool_eligibility() returns trigger language plpgsql as $$
declare
    e employee%rowtype;
    pool_location uuid;
    tip_credit boolean;
begin
    select * into e from employee where id = new.employee_id;
    select p.location_id, l.uses_tip_credit into pool_location, tip_credit
    from tip_pool p join location l on l.id = p.location_id where p.id = new.pool_id;

    if e.location_id <> pool_location then
        raise exception 'employee % does not work at the pool''s location', e.id using errcode = 'check_violation';
    end if;
    if e.is_manager or e.is_supervisor or (e.owner_equity_bps >= 2000 and e.owner_active_mgmt) then
        raise exception 'employee % is a manager, supervisor or 20%%+ active owner; FLSA bars tip pool membership', e.id
            using errcode = 'check_violation';
    end if;
    if (new.boh_snapshot or e.boh) and tip_credit then
        raise exception 'BOH staff cannot join a tip pool at a tip-credit location' using errcode = 'check_violation';
    end if;
    return new;
end $$;

create trigger tip_pool_member_eligibility before insert or update on tip_pool_member
    for each row execute function enforce_tip_pool_eligibility();

-- =====================================================================
-- Idempotency, offline claims, realtime replay
-- =====================================================================

create table webhook_event (
    event_id        text primary key,
    processor       text not null,
    type            text not null,
    received_at     timestamptz not null default now(),
    processed_at    timestamptz,
    payload_sha256  char(64) not null
);

create table processed_client_mutation (
    mutation_id  uuid primary key,
    device_id    uuid not null,
    result       text not null check (result in ('APPLIED', 'CONFLICT')),
    applied_at   timestamptz not null default now()
);

-- check_id has no FK: an offline check may be paid before the POS has synced it.
create table offline_check_claim (
    check_id       uuid primary key,
    device_id      uuid not null references device (id),
    claim_sig      bytea not null,
    amount_cents   bigint not null check (amount_cents >= 0),
    currency       char(3) not null default 'USD' check (currency ~ '^[A-Z]{3}$'),
    status         text not null check (status in ('PENDING', 'PAID', 'RECONCILED', 'MISMATCH')),
    reconciled_at  timestamptz
);

create table realtime_event (
    seq          bigserial primary key,
    location_id  uuid not null references location (id),
    topic        text not null,
    payload      jsonb not null,
    at           timestamptz not null default now()
);
create index realtime_event_replay on realtime_event (location_id, seq);
