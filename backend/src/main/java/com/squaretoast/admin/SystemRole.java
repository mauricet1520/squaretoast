package com.squaretoast.admin;

import static com.squaretoast.admin.Permission.AUDIT_READ;
import static com.squaretoast.admin.Permission.DISCOUNT_APPLY_MANAGER;
import static com.squaretoast.admin.Permission.LOCATION_EDIT;
import static com.squaretoast.admin.Permission.MENU_EDIT;
import static com.squaretoast.admin.Permission.MENU_PUBLISH;
import static com.squaretoast.admin.Permission.PRICE_EDIT;
import static com.squaretoast.admin.Permission.REPORTS_LABOR;
import static com.squaretoast.admin.Permission.REPORTS_SALES;
import static com.squaretoast.admin.Permission.STAFF_MANAGE;
import static com.squaretoast.admin.Permission.TIME_EDIT;
import static com.squaretoast.admin.Permission.TIPS_MANAGE;
import static com.squaretoast.admin.Permission.VOID;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** The built-in roles (seeded in V9 by {@code system_key}). Their permissions live here, not in the database. */
public enum SystemRole {
    OWNER(EnumSet.allOf(Permission.class)),
    GROUP_ADMIN(EnumSet.of(MENU_EDIT, MENU_PUBLISH, PRICE_EDIT, DISCOUNT_APPLY_MANAGER, VOID, REPORTS_SALES, REPORTS_LABOR,
            TIPS_MANAGE, TIME_EDIT, STAFF_MANAGE, LOCATION_EDIT, AUDIT_READ)),
    GM(EnumSet.of(MENU_EDIT, DISCOUNT_APPLY_MANAGER, VOID, REPORTS_SALES, REPORTS_LABOR, TIPS_MANAGE, TIME_EDIT, STAFF_MANAGE,
            LOCATION_EDIT)),
    SHIFT_LEAD(EnumSet.of(DISCOUNT_APPLY_MANAGER, VOID, REPORTS_SALES, TIME_EDIT)),
    STAFF(EnumSet.noneOf(Permission.class));

    @SuppressWarnings("ImmutableEnumChecker") // an unmodifiable view of a set nothing else holds
    private final Set<Permission> permissions;

    SystemRole(EnumSet<Permission> permissions) {
        this.permissions = Collections.unmodifiableSet(permissions);
    }

    public Set<Permission> permissions() {
        return permissions;
    }
}
