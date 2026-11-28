package com.squaretoast.admin;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Slice B2: the locations a user may see, and renaming one (LOCATION_EDIT). */
@RestController
class AdminLocationsController {

    record LocationView(UUID id, String name, String stateCode, String timezone, String currency) {}

    record Rename(@Nullable String name) {}

    private final JdbcClient db;
    private final AdminAccess access;

    AdminLocationsController(JdbcClient db, AdminAccess access) {
        this.db = db;
        this.access = access;
    }

    @GetMapping(path = "/admin/locations", version = "1")
    List<LocationView> list(@RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        List<PermissionResolver.Grant> grants = access.grants(admin.userId());
        return views("where merchant_id = ? order by name, id", admin.merchantId()).stream()
                .filter(l -> !access.at(grants, admin, ScopeKind.LOCATION, l.id()).isEmpty())
                .toList();
    }

    @GetMapping(path = "/admin/locations/{id}", version = "1")
    LocationView get(@RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin, @PathVariable UUID id) {
        access.requireAtLocation(admin, id, null);
        return views("where id = ?", id).getFirst();
    }

    @PatchMapping(path = "/admin/locations/{id}", version = "1")
    @Transactional
    LocationView rename(@RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin, @PathVariable UUID id,
                        @RequestBody Rename body) {
        access.requireAtLocation(admin, id, Permission.LOCATION_EDIT);
        String raw = body.name();
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > 100) {
            throw AdminDenied.badRequest();
        }
        db.sql("update location set name = ? where id = ? and merchant_id = ?").params(name, id, admin.merchantId()).update();
        return views("where id = ?", id).getFirst();
    }

    private List<LocationView> views(String where, Object... params) {
        return db.sql("select id, name, state_code, timezone, currency from location " + where)
                .params(params)
                .query((rs, n) -> new LocationView(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("state_code"), rs.getString("timezone"), rs.getString("currency").trim()))
                .list();
    }
}
