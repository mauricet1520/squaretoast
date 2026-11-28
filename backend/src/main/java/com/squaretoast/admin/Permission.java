package com.squaretoast.admin;

/** Closed list of back-office permissions; mirrored by the {@code role_permission} check constraint. */
public enum Permission {
    MENU_EDIT,
    MENU_PUBLISH,
    PRICE_EDIT,
    DISCOUNT_APPLY_MANAGER,
    VOID,
    REPORTS_SALES,
    REPORTS_LABOR,
    TIPS_MANAGE,
    TIME_EDIT,
    STAFF_MANAGE,
    LOCATION_EDIT,
    AUDIT_READ
}
