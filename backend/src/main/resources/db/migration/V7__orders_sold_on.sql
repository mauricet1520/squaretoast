-- Plan 19.6: the local date a check was sold, for tax. Set for POS orders from the device's sale time (clamped to the
-- last 72 h); null for guest orders and older rows, which are taxed by the local date of opened_at.
alter table orders add column sold_on date;
