package com.squaretoast.admin;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pure: a user's permissions at a scope are the union of the grants whose scope covers it. A merchant grant
 * covers everything of that merchant; a group grant covers the group and its member locations; a location
 * grant covers only that location. Nothing covers upward (a location grant gives nothing at group scope).
 */
final class PermissionResolver {

    record Grant(ScopeKind kind, UUID scopeId, Set<Permission> permissions) {}

    private PermissionResolver() {}

    /**
     * @param merchantId the merchant that owns the target scope
     * @param groupsOfTarget for a LOCATION target, the groups it belongs to; ignored otherwise
     */
    static Set<Permission> atScope(List<Grant> grants, UUID merchantId, ScopeKind kind, UUID scopeId, Set<UUID> groupsOfTarget) {
        EnumSet<Permission> out = EnumSet.noneOf(Permission.class);
        for (Grant g : grants) {
            boolean covers = switch (g.kind()) {
                case MERCHANT -> g.scopeId().equals(merchantId);
                case GROUP -> (kind == ScopeKind.GROUP && g.scopeId().equals(scopeId))
                        || (kind == ScopeKind.LOCATION && groupsOfTarget.contains(g.scopeId()));
                case LOCATION -> kind == ScopeKind.LOCATION && g.scopeId().equals(scopeId);
            };
            if (covers) {
                out.addAll(g.permissions());
            }
        }
        return out;
    }
}
