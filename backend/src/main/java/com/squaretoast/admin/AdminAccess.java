package com.squaretoast.admin;

import com.squaretoast.admin.PermissionResolver.Grant;
import java.sql.Array;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Slice B2: loads a user's grants and answers "may this user do X here?". Every target is first resolved inside
 * the caller's own merchant (taken from the session); a target outside it is reported as not found.
 */
@Service
class AdminAccess {

    private final JdbcClient db;

    AdminAccess(JdbcClient db) {
        this.db = db;
    }

    static Set<Permission> rolePermissions(@Nullable String systemKey, @Nullable Array custom) throws SQLException {
        if (systemKey != null) {
            return SystemRole.valueOf(systemKey).permissions();
        }
        EnumSet<Permission> out = EnumSet.noneOf(Permission.class);
        if (custom != null) {
            for (Object p : (Object[]) custom.getArray()) {
                out.add(Permission.valueOf((String) p));
            }
        }
        return out;
    }

    List<Grant> grants(UUID userId) {
        return db.sql("""
                select g.scope_kind, g.scope_id, r.system_key,
                       array_remove(array_agg(rp.permission), null) as perms
                from role_grant g join role r on r.id = g.role_id
                left join role_permission rp on rp.role_id = r.id
                where g.user_id = ?
                group by g.id, g.scope_kind, g.scope_id, r.system_key""")
                .params(userId)
                .query((rs, n) -> new Grant(ScopeKind.valueOf(rs.getString("scope_kind")), rs.getObject("scope_id", UUID.class),
                        rolePermissions(rs.getString("system_key"), rs.getArray("perms"))))
                .list();
    }

    /** The groups a location belongs to. */
    Set<UUID> groupsOf(UUID locationId) {
        return new HashSet<>(db.sql("select group_id from location_group_member where location_id = ?")
                .params(locationId).query(UUID.class).list());
    }

    /** 404 unless the scope exists inside the caller's merchant. */
    void requireOwnScope(AdminPrincipal p, ScopeKind kind, UUID scopeId) {
        boolean own = switch (kind) {
            case MERCHANT -> scopeId.equals(p.merchantId());
            case GROUP -> exists("select count(*) from location_group where id = ? and merchant_id = ?", scopeId, p.merchantId());
            case LOCATION -> exists("select count(*) from location where id = ? and merchant_id = ?", scopeId, p.merchantId());
        };
        if (!own) {
            throw AdminDenied.notFound();
        }
    }

    Set<Permission> at(AdminPrincipal p, ScopeKind kind, UUID scopeId) {
        return at(grants(p.userId()), p, kind, scopeId);
    }

    Set<Permission> at(List<Grant> grants, AdminPrincipal p, ScopeKind kind, UUID scopeId) {
        Set<UUID> groups = kind == ScopeKind.LOCATION ? groupsOf(scopeId) : Set.of();
        return PermissionResolver.atScope(grants, p.merchantId(), kind, scopeId, groups);
    }

    /** 404 for a location outside the caller's merchant, 403 if the caller lacks {@code needed} there. */
    void requireAtLocation(AdminPrincipal p, UUID locationId, @Nullable Permission needed) {
        requireOwnScope(p, ScopeKind.LOCATION, locationId);
        Set<Permission> held = at(p, ScopeKind.LOCATION, locationId);
        if (needed == null ? held.isEmpty() : !held.contains(needed)) {
            throw AdminDenied.forbidden();
        }
    }

    private boolean exists(String sql, Object... params) {
        return db.sql(sql).params(params).query(Long.class).single() > 0;
    }
}
