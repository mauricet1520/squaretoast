package com.squaretoast.domain.ledger;

/** Ledger accounts; must match the journal_line.account check constraint. */
public enum Account {
    SALES, TAX_PAYABLE, TIPS_PAYABLE, SERVICE_CHARGE, PLATFORM_FEE, PROCESSOR_FEE, MERCHANT_CASH, TIP_FEE_DEDUCTION, REFUNDS
}
