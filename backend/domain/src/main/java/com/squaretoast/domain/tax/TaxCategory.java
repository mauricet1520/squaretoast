package com.squaretoast.domain.tax;

/** Tax categories the engine itself gives meaning to. Other categories (ALCOHOL, SOFT_DRINK, ...) are plain rule keys. */
public final class TaxCategory {

    /** Food: the only category the CA cold-food-to-go exemption applies to. */
    public static final String FOOD = "FOOD";

    /** Rate key for taxable mandatory service charges, auto-gratuities and delivery fees. */
    public static final String SERVICE_CHARGE = "SERVICE_CHARGE";

    private TaxCategory() {}
}
