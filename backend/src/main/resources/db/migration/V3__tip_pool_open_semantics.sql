-- "Open pool" means closed_at is null everywhere (V1's location trigger used period_end),
-- pools with members can't change location, and membership inserts lock the location row.

create or replace function enforce_location_tip_credit() returns trigger language plpgsql as $$
begin
    if new.uses_tip_credit and not (select tip_credit_allowed from state_compliance_profile
                                    where state_code = new.state_code) then
        raise exception 'state % does not allow a tip credit', new.state_code using errcode = 'check_violation';
    end if;
    if tg_op = 'UPDATE' and new.uses_tip_credit and not old.uses_tip_credit and exists (
            select 1 from tip_pool p
            join tip_pool_member m on m.pool_id = p.id
            join employee e on e.id = m.employee_id
            where p.location_id = new.id and p.closed_at is null and (m.boh_snapshot or e.boh)) then
        raise exception 'location % has BOH staff in an open tip pool; cannot start taking a tip credit', new.id
            using errcode = 'check_violation';
    end if;
    return new;
end $$;

create function enforce_tip_pool_location_fixed() returns trigger language plpgsql as $$
begin
    if new.location_id <> old.location_id
       and exists (select 1 from tip_pool_member where pool_id = old.id) then
        raise exception 'tip pool % has members and cannot move to another location', old.id
            using errcode = 'check_violation';
    end if;
    return new;
end $$;

create trigger tip_pool_location_fixed before update on tip_pool
    for each row execute function enforce_tip_pool_location_fixed();

-- Same rule as V2. The location row is now also read FOR SHARE, so a concurrent switch to
-- tip credit waits for this insert and then sees the new member (and vice versa).
create or replace function enforce_tip_pool_eligibility() returns trigger language plpgsql as $$
declare
    e employee%rowtype;
    pool_location uuid;
    tip_credit boolean;
begin
    select * into e from employee where id = new.employee_id for share;
    select p.location_id, l.uses_tip_credit into pool_location, tip_credit
    from tip_pool p join location l on l.id = p.location_id
    where p.id = new.pool_id
    for share of l;

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
