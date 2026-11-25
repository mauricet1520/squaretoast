package com.squaretoast.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Slice 1 bootstrap: SB4 on Tomcat, Jackson 3 only, Flyway V1 applied, path-segment API versioning ("1"). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class BootstrapIT {

    @Container @ServiceConnection
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired Environment env;
    @Autowired ApplicationContext ctx;
    @Autowired DataSource dataSource;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void v1HealthReturnsVersionedJson() throws Exception {
        HttpResponse<String> r = get("/api/v1/health");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).startsWith("application/json"));
        JsonNode body = JsonMapper.builder().build().readTree(r.body());
        assertThat(body.get("status").asString()).isEqualTo("UP");
        assertThat(body.get("apiVersion").asString()).isEqualTo("1");
    }

    @Test
    void unsupportedApiVersionIsRejectedWith400() throws Exception {
        assertThat(get("/api/v2/health").statusCode()).isEqualTo(400);
    }

    @Test
    void unversionedApiPathIsNotServed() throws Exception {
        assertThat(get("/api/health").statusCode()).isBetween(400, 499);
    }

    @Test
    void runsOnTomcatWithoutUndertow() {
        assertThat(ClassUtils.isPresent("org.apache.catalina.startup.Tomcat", null)).isTrue();
        assertThat(ClassUtils.isPresent("io.undertow.Undertow", null)).isFalse();
    }

    @Test
    void jackson3IsTheOnlyJsonMapperBean() {
        assertThat(ctx.getBeansOfType(JsonMapper.class)).isNotEmpty();
        List<String> jackson2Beans = Arrays.stream(ctx.getBeanDefinitionNames())
                .map(ctx::getType)
                .filter(t -> t != null && t.getName().startsWith("com.fasterxml.jackson.databind"))
                .map(Class::getName).toList();
        assertThat(jackson2Beans).isEmpty();
    }

    @Test
    void flywayAppliedV1Baseline() throws Exception {
        List<String> versions = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select version from flyway_schema_history where success and version is not null")) {
            while (rs.next()) versions.add(rs.getString(1));
        }
        assertThat(versions).contains("1");
    }

    @Test
    void virtualThreadsAreEnabled() {
        assertThat(env.getProperty("spring.threads.virtual.enabled", Boolean.class)).isTrue();
    }

    private HttpResponse<String> get(String path) throws Exception {
        String port = env.getRequiredProperty("local.server.port");
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
