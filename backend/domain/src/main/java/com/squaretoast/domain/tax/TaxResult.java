package com.squaretoast.domain.tax;

import com.squaretoast.domain.money.Money;

/** Tax on one check. Bases exclude voluntary tips, which are never taxable. */
public record TaxResult(Money taxableBase, Money exemptBase, Money tax) {}
