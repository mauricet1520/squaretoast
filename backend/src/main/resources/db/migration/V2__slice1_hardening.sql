-- Slice 1 audit follow-ups: currency allowlist, frozen closed tip pools,
-- employee role changes re-checked against open pools.

-- =====================================================================
-- Currency allowlist (mirrors domain CurrencyCode: ISO-4217 with cents)
-- =====================================================================

create table currency (
    code          char(3) primary key check (code ~ '^[A-Z]{3}$'),
    minor_digits  smallint not null check (minor_digits = 2)
);

-- Add a row when a market launches; state tax/tip rules are US-only today.
insert into currency (code, minor_digits) values ('USD', 2);

alter table location            add constraint location_currency_fk            foreign key (currency) references currency (code);
alter table payment             add constraint payment_currency_fk             foreign key (currency) references currency (code);
alter table journal_line        add constraint journal_line_currency_fk        foreign key (currency) references currency (code);
alter table offline_check_claim add constraint offline_check_claim_currency_fk foreign key (currency) references currency (code);

-- =====================================================================
-- Closed tip pools are payroll history: frozen once closed_at is set
-- =====================================================================

alter table tip_pool add column closed_at timestamptz;

create function enforce_tip_pool_closed_is_final() returns trigger language plpgsql as $$
begin
    if old.closed_at is not null then
        raise exception 'tip pool % is closed and cannot be changed', old.id using errcode = 'check_violation';
    end if;
    if tg_op = 'DELETE' then
        return old;
    end if;
    return new;
end $$;

create trigger tip_pool_closed_is_final before update or delete on tip_pool
    for each row execute function enforce_tip_pool_closed_is_final();

create function assert_tip_pool_open(pool uuid) returns void language plpgsql as $$
declare
    closed timestamptz;
begin
    -- FOR SHARE serialises against a concurrent close of the same pool.
    select closed_at into closed from tip_pool where id = pool for share;
    if closed is not null then
        raise exception 'tip pool % is closed; its membership is frozen', pool using errcode = 'check_violation';
    end if;
end $$;

create function enforce_tip_pool_member_frozen() returns trigger language plpgsql as $$
begin
    if tg_op = 'TRUNCATE' then
        raise exception 'tip pool membership is payroll history and cannot be truncated' using errcode = 'check_violation';
    end if;
    if tg_op in ('UPDATE', 'DELETE') then
        perform assert_tip_pool_open(old.pool_id);
    end if;
    if tg_op in ('INSERT', 'UPDATE') then
        perform assert_tip_pool_open(new.pool_id);
    end if;
    if tg_op = 'DELETE' then
        return old;
    end if;
    return new;
end $$;

create trigger tip_pool_member_frozen before insert or update or delete on tip_pool_member
    for each row execute function enforce_tip_pool_member_frozen();

create trigger tip_pool_member_no_truncate before truncate on tip_pool_member
    for each statement execute function enforce_tip_pool_member_frozen();

-- =====================================================================
-- Employee changes are re-checked against the open pools they belong to
-- =====================================================================

create function enforce_employee_pool_eligibility() returns trigger language plpgsql as $$
begin
    if (new.is_manager or new.is_supervisor
            or (new.owner_equity_bps >= 2000 and new.owner_active_mgmt)
            or new.location_id <> old.location_id
            or (new.boh and exists (select 1 from location l where l.id = new.location_id and l.uses_tip_credit)))
       and exists (select 1 from tip_pool_member m join tip_pool p on p.id = m.pool_id
                   where m.employee_id = new.id and p.closed_at is null) then
        raise exception 'employee % is in an open tip pool; this change would make them ineligible under FLSA', new.id
            using errcode = 'check_violation';
    end if;
    return new;
end $$;

create trigger employee_pool_eligibility before update on employee
    for each row execute function enforce_employee_pool_eligibility();

-- Same rule as V1, but the employee row is read FOR SHARE so a concurrent promotion
-- cannot slip past a membership insert (the FK's KEY SHARE lock does not block it).
create or replace function enforce_tip_pool_eligibility() returns trigger language plpgsql as $$
declare
    e employee%rowtype;
    pool_location uuid;
    tip_credit boolean;
begin
    select * into e from employee where id = new.employee_id for share;
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
