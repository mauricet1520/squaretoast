#!/usr/bin/env bash
# Seeds a demo location (once) in the local-dev database and prints a fresh 15-minute enrollment code for it.
# Local development only: it talks to the squaretoast-pg container that backend.sh starts.
set -euo pipefail
psql() { docker exec -i squaretoast-pg psql -U squaretoast -d squaretoast -v ON_ERROR_STOP=1 -qtA "$@"; }

psql <<'SQL'
insert into merchant(id, legal_name) values ('00000000-0000-0000-0000-00000000a001', 'Local Dev Bistro')
  on conflict do nothing;
insert into location(id, merchant_id, name, state_code, timezone)
  values ('00000000-0000-0000-0000-00000000b001', '00000000-0000-0000-0000-00000000a001', 'Main', 'US', 'America/New_York')
  on conflict do nothing;
insert into menu_item(id, location_id, name, price_cents, tax_category) values
  ('00000000-0000-0000-0000-00000000c001', '00000000-0000-0000-0000-00000000b001', 'Burger', 1450, 'FOOD'),
  ('00000000-0000-0000-0000-00000000c002', '00000000-0000-0000-0000-00000000b001', 'Fries', 550, 'FOOD')
  on conflict do nothing;
insert into dining_table(id, location_id, label, qr_token)
  values ('00000000-0000-0000-0000-00000000d001', '00000000-0000-0000-0000-00000000b001', 'T1', 'local-dev-table-1')
  on conflict do nothing;
SQL

code=$(head -c 12 /dev/urandom | base64 | tr -dc 'A-Z0-9' | head -c 8)
[ ${#code} -ge 6 ] || code=$(date +%s%N | tail -c 9)
psql <<SQL
insert into device_enrollment(code_hash, location_id, expires_at)
  values (sha256(convert_to('$code', 'UTF8')), '00000000-0000-0000-0000-00000000b001', now() + interval '15 minutes');
SQL
echo "$code"
