package com.squaretoast.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.squaretoast.testsupport.MenuFixture;
import com.squaretoast.testsupport.SseClient;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Review fix: an idle stream gets SSE comment heartbeats, so a quiet POS keeps its connection (its client times out
 * after two silent minutes) and proxies do not drop it. Comments carry no id, so Last-Event-ID is unaffected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "squaretoast.realtime.heartbeat=200ms")
@Testcontainers
class SseHeartbeatIT {

    @Container @ServiceConnection
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired DataSource dataSource;
    @LocalServerPort int port;

    @Test
    void anIdleStreamReceivesHeartbeatCommentsAndNoEvents() throws Exception {
        MenuFixture f = new MenuFixture(dataSource, port);
        UUID loc = f.location();

        try (SseClient sse = SseClient.connect(f.baseUrl(), loc, null)) {
            assertThat(sse.status()).isEqualTo(200);
            assertThat(sse.poll(Duration.ofMillis(1500))).as("heartbeats are not events").isNull();
            assertThat(sse.comments()).isGreaterThanOrEqualTo(3);
        }
    }
}
