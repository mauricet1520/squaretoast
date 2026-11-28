-- Slice B3: one append-only row per back-office change: who did it, who approved it, before and after.

create table audit_event (
    id                 uuid primary key,
    merchant_id        uuid not null references merchant (id),
    location_id        uuid references location (id),
    actor_user_id      uuid references app_user (id),
    actor_employee_id  uuid references employee (id),
    actor_device_id    uuid references device (id),
    approver_user_id   uuid references app_user (id),
    action             text not null check (action ~ '^[A-Z][A-Z_]{2,63}$'),
    entity_kind        text not null check (entity_kind ~ '^[a-z][a-z_]{1,63}$'),
    entity_id          uuid,
    before             jsonb,
    after              jsonb,
    at                 timestamptz not null default now(),
    request_id         uuid,
    check (num_nonnulls(actor_user_id, actor_employee_id, actor_device_id) <= 1)
);
create index audit_event_merchant_at on audit_event (merchant_id, at);
create index audit_event_entity on audit_event (entity_kind, entity_id);

create function audit_event_append_only() returns trigger language plpgsql as $fn$
begin
    raise exception 'audit_event is append-only';
end $fn$;
create trigger audit_event_no_update before update or delete on audit_event
    for each row execute function audit_event_append_only();
create trigger audit_event_no_truncate before truncate on audit_event
    for each statement execute function audit_event_append_only();

-- Actor, approver and location must all belong to the event's merchant.
create function audit_event_same_merchant() returns trigger language plpgsql as $fn$
begin
    if new.location_id is not null
            and (select merchant_id from location where id = new.location_id) is distinct from new.merchant_id then
        raise exception 'audit location is from another merchant';
    end if;
    if new.actor_user_id is not null
            and (select merchant_id from app_user where id = new.actor_user_id) is distinct from new.merchant_id then
        raise exception 'audit actor is from another merchant';
    end if;
    if new.approver_user_id is not null
            and (select merchant_id from app_user where id = new.approver_user_id) is distinct from new.merchant_id then
        raise exception 'audit approver is from another merchant';
    end if;
    return new;
end $fn$;
create trigger audit_event_same_merchant before insert on audit_event
    for each row execute function audit_event_same_merchant();
