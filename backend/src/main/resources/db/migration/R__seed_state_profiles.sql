-- State compliance seed (repeatable). Wage figures are 2026 placeholders: verify with counsel before go-live.
-- 'US' = federal FLSA default for states without a dedicated profile.
insert into state_compliance_profile (state_code, tip_credit_allowed, min_wage_cents, min_cash_wage_cents,
                                      tip_card_fee_deduction_allowed, mandatory_service_charge_taxable, effective_from)
values ('US', true,  725,  213,  true,  true, date '2026-01-01'),
       ('CA', false, 1690, 1690, false, true, date '2026-01-01'),
       ('ME', true,  1510, 755,  false, true, date '2026-01-01'),
       ('MA', true,  1500, 675,  false, true, date '2026-01-01'),
       ('DE', true,  1500, 223,  false, true, date '2026-01-01')
on conflict (state_code) do update set
    tip_credit_allowed               = excluded.tip_credit_allowed,
    min_wage_cents                   = excluded.min_wage_cents,
    min_cash_wage_cents              = excluded.min_cash_wage_cents,
    tip_card_fee_deduction_allowed   = excluded.tip_card_fee_deduction_allowed,
    mandatory_service_charge_taxable = excluded.mandatory_service_charge_taxable,
    effective_from                   = excluded.effective_from;
