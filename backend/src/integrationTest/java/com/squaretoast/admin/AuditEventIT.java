package com.squaretoast.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squaretoast.admin.AdminFixture.Session;
import com.squaretoast.admin.AdminFixture.User;
import java.net.http.HttpResponse;
import java.util.Map;
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
import tools.jackson.databind.json.JsonMapper;

/** Slice B3: every back-office change leaves exactly one append-only audit row naming who did it and who approved. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuditEventIT {

    @Container @ServiceConnection
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired DataSource dataSource;
    @Autowired AuditLog audit;
    @LocalServerPort int port;

    AdminFixture a;
    UUID merchant;
    UUID loc;
    User owner;
    User staff;

    @BeforeEach
    void seed() {
        a = new AdminFixture(dataSource, port);
        merchant = a.merchant();
        loc = a.location(merchant);
        owner = a.user(merchant);
        a.grant(owner, "OWNER", "MERCHANT", merchant);
        staff = a.user(merchant);
    }

    private long events(String action) {
        return a.count("select count(*) from audit_event where merchant_id = ? and action = ?", merchant, action);
    }

    private long all() {
        return a.count("select count(*) from audit_event where merchant_id = ?", merchant);
    }

    @Test
    void everyAdminMutationWritesOneEvent() {
        Session s = a.session(owner);
        assertThat(events("ADMIN_LOGIN")).isEqualTo(1);
        assertThat(a.count("select count(*) from audit_event where action = 'ADMIN_LOGIN' and actor_user_id = ?", owner.id()))
                .isEqualTo(1);

        assertThat(a.login(owner.email(), "wrong").statusCode()).isEqualTo(401);
        assertThat(events("ADMIN_LOGIN_FAILED")).isEqualTo(1);

        assertThat(a.call("PATCH", "/admin/locations/" + loc, "{\"name\":\"Uptown\"}", s).statusCode()).isEqualTo(200);
        assertThat(events("LOCATION_UPDATED")).isEqualTo(1);
        Map<String, Object> row = a.db.sql("""
                select location_id, entity_kind, entity_id, before ->> 'name' as before_name, after ->> 'name' as after_name,
                       request_id from audit_event where action = 'LOCATION_UPDATED' and merchant_id = ?""")
                .params(merchant).query().singleRow();
        assertThat(row).containsEntry("location_id", loc).containsEntry("entity_kind", "location").containsEntry("entity_id", loc)
                .containsEntry("before_name", "Main").containsEntry("after_name", "Uptown");
        assertThat(row.get("request_id")).isNotNull();

        HttpResponse<String> granted = a.call("POST", "/admin/role-grants",
                AdminFixture.grantBody(staff.id(), a.roleId("SHIFT_LEAD"), "LOCATION", loc), s);
        assertThat(granted.statusCode()).isEqualTo(201);
        assertThat(events("ROLE_GRANTED")).isEqualTo(1);
        String grantId = JsonMapper.builder().build().readTree(granted.body()).get("id").asString();

        assertThat(a.call("DELETE", "/admin/role-grants/" + grantId, null, s).statusCode()).isEqualTo(204);
        assertThat(events("ROLE_REVOKED")).isEqualTo(1);

        assertThat(a.call("DELETE", "/admin/session", null, s).statusCode()).isEqualTo(204);
        assertThat(events("ADMIN_LOGOUT")).isEqualTo(1);

        assertThat(all()).isEqualTo(6);
    }

    @Test
    void refusedCallsChangeNothingAndLogNothing() {
        Session s = a.session(staff);
        long before = all();

        assertThat(a.call("PATCH", "/admin/locations/" + loc, "{\"name\":\"Mine\"}", s).statusCode()).isEqualTo(403);
        assertThat(a.call("POST", "/admin/role-grants",
                AdminFixture.grantBody(staff.id(), a.roleId("OWNER"), "MERCHANT", merchant), s).statusCode()).isEqualTo(403);

        assertThat(all()).isEqualTo(before);
    }

    @Test
    void updateAndDeleteOnAuditEventAreRejected() {
        a.session(owner);

        assertThatThrownBy(() -> a.db.sql("update audit_event set action = 'NOTHING' where merchant_id = ?").params(merchant).update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> a.db.sql("delete from audit_event where merchant_id = ?").params(merchant).update())
                .hasMessageContaining("append-only");
        assertThat(events("ADMIN_LOGIN")).isEqualTo(1);
    }

    @Test
    void approverRecordedSeparatelyFromActor() {
        UUID line = UUID.randomUUID();
        audit.record(new AuditLog.Entry(merchant, loc, AuditLog.Actor.user(staff.id()), owner.id(), "LINE_VOIDED", "order_line",
                line, null, Map.of("reason", "guest changed mind")));

        Map<String, Object> row = a.db.sql("select actor_user_id, approver_user_id, after ->> 'reason' as reason from audit_event where entity_id = ?")
                .params(line).query().singleRow();
        assertThat(row).containsEntry("actor_user_id", staff.id()).containsEntry("approver_user_id", owner.id())
                .containsEntry("reason", "guest changed mind");

        User outsider = a.user(a.merchant());
        assertThatThrownBy(() -> audit.record(new AuditLog.Entry(merchant, loc, AuditLog.Actor.user(staff.id()), outsider.id(),
                "LINE_VOIDED", "order_line", UUID.randomUUID(), null, null)))
                .as("an approver from another merchant").hasMessageContaining("merchant");
    }
}
