package com.squaretoast.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Slice B3: secrets never reach the audit log, however deep or however the key is spelled. */
class AuditRedactionTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void neverStoresPinPasswordOrPan() {
        JsonNode in = JSON.readTree("""
                {"name":"East","password":"hunter2","posPin":"1234","Card_Number":"4242424242424242",
                 "nested":{"cvv":"123","sessionToken":"abc","apiSecret":"s","pan":"4111","note":"keep"},
                 "list":[{"PIN":"9999","qty":2}]}""");

        String out = AuditLog.redact(in).toString();

        assertThat(out).doesNotContain("hunter2", "1234", "4242424242424242", "\"123\"", "abc", "\"s\"", "4111", "9999");
        assertThat(out).contains("\"name\":\"East\"", "\"note\":\"keep\"", "\"qty\":2", "[redacted]");
        assertThat(in.get("password").asString()).as("the input is not changed").isEqualTo("hunter2");
    }

    @Test
    void plainValuesPassThrough() {
        assertThat(AuditLog.redact(JSON.readTree("{\"name\":\"Main\",\"spanish\":true}")).toString())
                .isEqualTo("{\"name\":\"Main\",\"spanish\":true}"); // "pan" inside a longer word is not a card number
    }
}
