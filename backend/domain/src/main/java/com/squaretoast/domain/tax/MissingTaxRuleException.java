package com.squaretoast.domain.tax;

/** No effective rule covers a taxable category: fail closed rather than sell it untaxed. */
public final class MissingTaxRuleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MissingTaxRuleException(String stateCode, String taxCategory, FulfillmentChannel channel) {
        super("no tax rule for " + taxCategory + " / " + channel + " in " + stateCode);
    }
}
