-- Slice B2: roles granted at a scope (merchant, location group or location). B4 adds the group API and archiving.

create table location_group (
    id           uuid primary key,
    merchant_id  uuid not null references merchant (id),
    name         text not null,
    archived_at  timestamptz,
    created_at   timestamptz not null default now()
);

create table location_group_member (
    group_id     uuid not null references location_group (id),
    location_id  uuid not null references location (id),
    primary key (group_id, location_id)
);

create function location_group_member_same_merchant() returns trigger language plpgsql as $$
begin
    if (select merchant_id from location_group where id = new.group_id)
        is distinct from (select merchant_id from location where id = new.location_id) then
        raise exception 'group and location belong to different merchants';
    end if;
    return new;
end $$;
create trigger location_group_member_same_merchant before insert or update on location_group_member
    for each row execute function location_group_member_same_merchant();

-- System roles have no merchant and a system_key; their permissions live in code (SystemRole), so a new
-- permission reaches OWNER without a migration. Custom roles list theirs in role_permission.
create table role (
    id           uuid primary key,
    merchant_id  uuid references merchant (id),
    name         text not null,
    system_key   text unique check (system_key in ('OWNER', 'GROUP_ADMIN', 'GM', 'SHIFT_LEAD', 'STAFF')),
    check ((merchant_id is null) = (system_key is not null))
);

create table role_permission (
    role_id     uuid not null references role (id),
    permission  text not null check (permission in ('MENU_EDIT', 'MENU_PUBLISH', 'PRICE_EDIT', 'DISCOUNT_APPLY_MANAGER',
        'VOID', 'REPORTS_SALES', 'REPORTS_LABOR', 'TIPS_MANAGE', 'TIME_EDIT', 'STAFF_MANAGE', 'LOCATION_EDIT', 'AUDIT_READ')),
    primary key (role_id, permission)
);

insert into role (id, merchant_id, name, system_key) values
    ('00000000-0000-0000-0000-00000000a001', null, 'Owner', 'OWNER'),
    ('00000000-0000-0000-0000-00000000a002', null, 'Group admin', 'GROUP_ADMIN'),
    ('00000000-0000-0000-0000-00000000a003', null, 'General manager', 'GM'),
    ('00000000-0000-0000-0000-00000000a004', null, 'Shift lead', 'SHIFT_LEAD'),
    ('00000000-0000-0000-0000-00000000a005', null, 'Staff', 'STAFF');

create table role_grant (
    id          uuid primary key,
    user_id     uuid not null references app_user (id),
    role_id     uuid not null references role (id),
    scope_kind  text not null check (scope_kind in ('MERCHANT', 'GROUP', 'LOCATION')),
    scope_id    uuid not null,
    granted_by  uuid references app_user (id),
    created_at  timestamptz not null default now(),
    unique (user_id, role_id, scope_kind, scope_id)
);
create index role_grant_user on role_grant (user_id);

-- A second wall behind the API checks: a grant's scope and custom role belong to the user's own merchant.
create function role_grant_same_merchant() returns trigger language plpgsql as $fn$
begin
    if new.scope_kind = 'MERCHANT'
            and new.scope_id is distinct from (select merchant_id from app_user where id = new.user_id) then
        raise exception 'role grant crosses merchants';
    end if;
    if new.scope_kind = 'GROUP' and not exists (select 1 from location_group g join app_user u on u.merchant_id = g.merchant_id
            where g.id = new.scope_id and u.id = new.user_id) then
        raise exception 'role grant crosses merchants';
    end if;
    if new.scope_kind = 'LOCATION' and not exists (select 1 from location l join app_user u on u.merchant_id = l.merchant_id
            where l.id = new.scope_id and u.id = new.user_id) then
        raise exception 'role grant crosses merchants';
    end if;
    if exists (select 1 from role r join app_user u on u.id = new.user_id
            where r.id = new.role_id and r.merchant_id is not null and r.merchant_id <> u.merchant_id) then
        raise exception 'role grant crosses merchants';
    end if;
    return new;
end $fn$;
create trigger role_grant_same_merchant before insert or update on role_grant
    for each row execute function role_grant_same_merchant();
