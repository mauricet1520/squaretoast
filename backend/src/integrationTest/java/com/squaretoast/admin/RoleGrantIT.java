package com.squaretoast.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.squaretoast.admin.AdminFixture.Session;
import com.squaretoast.admin.AdminFixture.User;
import java.net.http.HttpResponse;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Slice B2: roles are granted at a scope, and nobody can hand out more than they hold there. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RoleGrantIT {

    @Container @ServiceConnection
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired DataSource dataSource;
    @LocalServerPort int port;

    AdminFixture a;
    UUID merchant;
    UUID east;
    UUID west;
    User owner;
    User gm;
    User newHire;
    Session gmSession;

    @BeforeEach
    void seed() {
        a = new AdminFixture(dataSource, port);
        merchant = a.merchant();
        east = a.location(merchant);
        west = a.location(merchant);
        owner = a.user(merchant);
        a.grant(owner, "OWNER", "MERCHANT", merchant);
        gm = a.user(merchant);
        a.grant(gm, "GM", "LOCATION", east);
        newHire = a.user(merchant);
        gmSession = a.session(gm);
    }

    private HttpResponse<String> grant(Session s, User to, String role, String kind, UUID scope) {
        return a.call("POST", "/admin/role-grants", AdminFixture.grantBody(to.id(), a.roleId(role), kind, scope), s);
    }

    @Test
    void cannotGrantPermissionYouDoNotHold() {
        assertThat(grant(gmSession, newHire, "OWNER", "MERCHANT", merchant).statusCode()).isEqualTo(403);
        assertThat(grant(gmSession, newHire, "GROUP_ADMIN", "LOCATION", east).statusCode())
                .as("GROUP_ADMIN can publish menus and edit prices; a GM cannot").isEqualTo(403);
        assertThat(grant(gmSession, newHire, "GM", "LOCATION", west).statusCode()).as("not the GM's location").isEqualTo(403);
        assertThat(grant(gmSession, gm, "OWNER", "MERCHANT", merchant).statusCode()).as("no self-promotion").isEqualTo(403);
        assertThat(a.count("select count(*) from role_grant where user_id = ?", newHire.id())).isZero();

        HttpResponse<String> ok = grant(gmSession, newHire, "SHIFT_LEAD", "LOCATION", east);
        assertThat(ok.statusCode()).isEqualTo(201);
        assertThat(a.count("select count(*) from role_grant where user_id = ? and granted_by = ?", newHire.id(), gm.id()))
                .isEqualTo(1);
    }

    @Test
    void withoutAPermissionYourOwnLocationIsForbiddenNotHidden() {
        Session lead = a.session(newHire);
        a.grant(newHire, "SHIFT_LEAD", "LOCATION", east);

        assertThat(a.call("GET", "/admin/locations/" + east, null, lead).statusCode()).isEqualTo(200);
        assertThat(a.call("PATCH", "/admin/locations/" + east, "{\"name\":\"East\"}", lead).statusCode()).isEqualTo(403);
        assertThat(a.call("GET", "/admin/locations/" + west, null, lead).statusCode()).isEqualTo(403);
        assertThat(a.call("GET", "/admin/locations", null, lead).body()).contains(east.toString()).doesNotContain(west.toString());

        HttpResponse<String> renamed = a.call("PATCH", "/admin/locations/" + east, "{\"name\":\"East Side\"}", gmSession);
        assertThat(renamed.statusCode()).isEqualTo(200);
        assertThat(renamed.body()).contains("East Side");
    }

    @Test
    void theLastOwnerCannotBeRevoked() {
        Session os = a.session(owner);
        UUID only = a.db.sql("select id from role_grant where user_id = ?").params(owner.id()).query(UUID.class).single();

        assertThat(a.call("DELETE", "/admin/role-grants/" + only, null, os).statusCode()).isEqualTo(409);

        User second = a.user(merchant);
        assertThat(grant(os, second, "OWNER", "MERCHANT", merchant).statusCode()).isEqualTo(201);
        assertThat(a.call("DELETE", "/admin/role-grants/" + only, null, os).statusCode()).isEqualTo(204);
        assertThat(a.count("select count(*) from role_grant where id = ?", only)).isZero();
    }

    @Test
    void onlyAnOwnerCanMakeOrRemoveOwners() {
        User admin = a.user(merchant);
        a.grant(admin, "GROUP_ADMIN", "MERCHANT", merchant);
        Session as = a.session(admin);
        UUID ownerGrant = a.db.sql("select id from role_grant where user_id = ?").params(owner.id()).query(UUID.class).single();

        assertThat(grant(as, admin, "OWNER", "MERCHANT", merchant).statusCode()).as("self-promotion to owner").isEqualTo(403);
        assertThat(grant(as, newHire, "OWNER", "MERCHANT", merchant).statusCode()).isEqualTo(403);
        assertThat(a.call("DELETE", "/admin/role-grants/" + ownerGrant, null, as).statusCode()).isEqualTo(403);
        assertThat(a.count("select count(*) from role_grant g join role r on r.id = g.role_id where r.system_key = 'OWNER'"
                + " and g.user_id in (?, ?)", admin.id(), newHire.id())).isZero();
        assertThat(grant(as, newHire, "GM", "LOCATION", west).statusCode()).as("other roles still fine").isEqualTo(201);
    }

    @Test
    void aLocationManagerListsOnlyStaffInTheirOwnScope() {
        User eastLead = a.user(merchant);
        a.grant(eastLead, "SHIFT_LEAD", "LOCATION", east);
        User westLead = a.user(merchant);
        a.grant(westLead, "SHIFT_LEAD", "LOCATION", west);

        String body = a.call("GET", "/admin/users", null, gmSession).body();
        assertThat(body).contains(eastLead.email(), gm.email()).doesNotContain(westLead.email(), owner.email());

        assertThat(a.call("GET", "/admin/users", null, a.session(owner)).body())
                .as("a merchant-wide manager sees everyone").contains(eastLead.email(), westLead.email(), newHire.email());
    }

    @Test
    void revokingNeedsTheSameRightsAsGranting() {
        UUID ownerGrant = a.db.sql("select id from role_grant where user_id = ?").params(owner.id()).query(UUID.class).single();
        assertThat(a.call("DELETE", "/admin/role-grants/" + ownerGrant, null, gmSession).statusCode()).isEqualTo(403);
    }
}
