package com.squaretoast.admin;

import java.net.URI;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Slice B2: users, roles and role grants. To grant or revoke a role at a scope you need STAFF_MANAGE there and
 * every permission the role carries there, so nobody hands out more than they hold. The last owner stays.
 */
@RestController
class AdminStaffController {

    record UserView(UUID id, String email, String displayName, boolean disabled) {}

    record RoleView(UUID id, String name, @Nullable String systemKey, Set<Permission> permissions) {}

    record GrantRequest(@Nullable UUID userId, @Nullable UUID roleId, @Nullable String scopeKind, @Nullable UUID scopeId) {}

    record GrantCreated(UUID id) {}

    private record RoleRow(UUID id, String name, @Nullable String systemKey, Set<Permission> permissions) {}

    private record GrantRow(UUID userId, UUID roleId, ScopeKind kind, UUID scopeId) {}

    private final JdbcClient db;
    private final AdminAccess access;
    private final AuditLog audit;

    AdminStaffController(JdbcClient db, AdminAccess access, AuditLog audit) {
        this.db = db;
        this.access = access;
        this.audit = audit;
    }

    @GetMapping(path = "/admin/users", version = "1")
    List<UserView> users(@RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        if (!access.holdsAnywhere(admin, Permission.STAFF_MANAGE)) {
            throw AdminDenied.forbidden();
        }
        return db.sql("select id, email, display_name, disabled_at is not null as disabled from app_user where merchant_id = ? order by email")
                .params(admin.merchantId())
                .query((rs, n) -> new UserView(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("display_name"), rs.getBoolean("disabled")))
                .list();
    }

    @GetMapping(path = "/admin/roles", version = "1")
    List<RoleView> roles(@RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return roleRows("r.merchant_id is null or r.merchant_id = ?", admin.merchantId()).stream()
                .map(r -> new RoleView(r.id(), r.name(), r.systemKey(), r.permissions())).toList();
    }

    @PostMapping(path = "/admin/role-grants", version = "1")
    @Transactional
    ResponseEntity<GrantCreated> grant(@RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin,
                                       @RequestBody GrantRequest body) {
        UUID userId = body.userId();
        UUID roleId = body.roleId();
        UUID scopeId = body.scopeId();
        ScopeKind kind = scopeKind(body.scopeKind());
        if (userId == null || roleId == null || scopeId == null) {
            throw AdminDenied.badRequest();
        }
        if (db.sql("select count(*) from app_user where id = ? and merchant_id = ?").params(userId, admin.merchantId())
                .query(Long.class).single() == 0) {
            throw AdminDenied.notFound();
        }
        RoleRow role = ownRole(admin, roleId).orElseThrow(AdminDenied::notFound);
        access.requireOwnScope(admin, kind, scopeId);
        requireMayHandOut(admin, role, kind, scopeId);

        UUID id = UUID.randomUUID();
        int inserted = db.sql("""
                insert into role_grant(id, user_id, role_id, scope_kind, scope_id, granted_by) values (?, ?, ?, ?, ?, ?)
                on conflict (user_id, role_id, scope_kind, scope_id) do nothing""")
                .params(id, userId, roleId, kind.name(), scopeId, admin.userId()).update();
        if (inserted == 0) {
            throw AdminDenied.conflict(); // already granted
        }
        audit.record(new AuditLog.Entry(admin.merchantId(), kind == ScopeKind.LOCATION ? scopeId : null,
                AuditLog.Actor.user(admin.userId()), null, "ROLE_GRANTED", "role_grant", id, null,
                grantView(userId, role, kind, scopeId)));
        return ResponseEntity.created(URI.create("/api/v1/admin/role-grants/" + id)).body(new GrantCreated(id));
    }

    @DeleteMapping(path = "/admin/role-grants/{id}", version = "1")
    @Transactional
    ResponseEntity<Void> revoke(@RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin, @PathVariable UUID id) {
        // Serialise grant changes per merchant, so two owners cannot remove each other at the same moment.
        db.sql("select id from merchant where id = ? for update").params(admin.merchantId()).query(UUID.class).single();
        GrantRow g = db.sql("""
                select g.user_id, g.role_id, g.scope_kind, g.scope_id from role_grant g join app_user u on u.id = g.user_id
                where g.id = ? and u.merchant_id = ?""")
                .params(id, admin.merchantId())
                .query((rs, n) -> new GrantRow(rs.getObject("user_id", UUID.class), rs.getObject("role_id", UUID.class),
                        ScopeKind.valueOf(rs.getString("scope_kind")),
                        rs.getObject("scope_id", UUID.class)))
                .optional().orElseThrow(AdminDenied::notFound);
        RoleRow role = ownRole(admin, g.roleId()).orElseThrow(AdminDenied::notFound);
        requireMayHandOut(admin, role, g.kind(), g.scopeId());
        if ("OWNER".equals(role.systemKey()) && g.kind() == ScopeKind.MERCHANT && otherOwners(admin.merchantId(), id) == 0) {
            throw AdminDenied.conflict();
        }
        db.sql("delete from role_grant where id = ?").params(id).update();
        audit.record(new AuditLog.Entry(admin.merchantId(), g.kind() == ScopeKind.LOCATION ? g.scopeId() : null,
                AuditLog.Actor.user(admin.userId()), null, "ROLE_REVOKED", "role_grant", id,
                grantView(g.userId(), role, g.kind(), g.scopeId()), null));
        return ResponseEntity.noContent().build();
    }

    private static Map<String, Object> grantView(UUID userId, RoleRow role, ScopeKind kind, UUID scopeId) {
        return Map.of("userId", userId, "roleId", role.id(), "role", role.name(), "scopeKind", kind, "scopeId", scopeId);
    }

    private void requireMayHandOut(AdminPrincipal admin, RoleRow role, ScopeKind kind, UUID scopeId) {
        Set<Permission> held = access.at(admin, kind, scopeId);
        if (!held.contains(Permission.STAFF_MANAGE) || !held.containsAll(role.permissions())) {
            throw AdminDenied.forbidden();
        }
    }

    private long otherOwners(UUID merchantId, UUID exceptGrant) {
        return db.sql("""
                select count(*) from role_grant g join role r on r.id = g.role_id join app_user u on u.id = g.user_id
                where r.system_key = 'OWNER' and g.scope_kind = 'MERCHANT' and g.scope_id = ? and g.id <> ?
                  and u.disabled_at is null""")
                .params(merchantId, exceptGrant).query(Long.class).single();
    }

    private static ScopeKind scopeKind(@Nullable String s) {
        try {
            return ScopeKind.valueOf(s == null ? "" : s);
        } catch (IllegalArgumentException e) {
            throw AdminDenied.badRequest();
        }
    }

    private Optional<RoleRow> ownRole(AdminPrincipal admin, UUID roleId) {
        return roleRows("r.id = ? and (r.merchant_id is null or r.merchant_id = ?)", roleId, admin.merchantId()).stream().findFirst();
    }

    private List<RoleRow> roleRows(String where, Object... params) {
        return db.sql("""
                select r.id, r.name, r.system_key, array_remove(array_agg(rp.permission), null) as perms
                from role r left join role_permission rp on rp.role_id = r.id
                where %s group by r.id, r.name, r.system_key order by r.merchant_id nulls first, r.name""".formatted(where))
                .params(params)
                .query((rs, n) -> {
                    try {
                        return new RoleRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("system_key"),
                                AdminAccess.rolePermissions(rs.getString("system_key"), rs.getArray("perms")));
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .list();
    }
}
