package com.squaretoast.domain.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squaretoast.domain.money.CurrencyCode;
import com.squaretoast.domain.money.Money;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Slice 14 (S14-2): the same tax vectors the Android POS runs, so an offline claim total equals the server bill. */
class TaxVectorsTest {

    private static final CurrencyCode USD = CurrencyCode.of("USD");

    private static JsonNode cases() throws IOException {
        try (InputStream in = Objects.requireNonNull(TaxVectorsTest.class.getClassLoader().getResourceAsStream("tax-vectors.json"))) {
            return JsonMapper.builder().build().readTree(in).get("cases");
        }
    }

    private static @org.jspecify.annotations.Nullable FulfillmentChannel channel(JsonNode n) {
        return n.isNull() ? null : FulfillmentChannel.valueOf(n.asString());
    }

    @Test
    void everyVectorMatchesTheEngine() throws IOException {
        JsonNode cases = cases();
        assertThat(cases.size()).isGreaterThanOrEqualTo(15);
        for (JsonNode c : cases) {
            String name = c.get("name").asString();
            JsonNode x = c.get("context");
            TaxContext ctx = new TaxContext(x.get("state").asString(), LocalDate.parse(x.get("date").asString()),
                    FulfillmentChannel.valueOf(x.get("channel").asString()), x.get("ca8080").asBoolean(),
                    x.get("caSeparate").asBoolean(), x.get("mandatoryTaxable").asBoolean(), USD);
            List<TaxRule> rules = new ArrayList<>();
            for (JsonNode r : c.get("rules")) {
                rules.add(new TaxRule(r.get("state").asString(), r.get("jurisdiction").asString(), r.get("category").asString(),
                        channel(r.get("channel")), r.get("ratePpm").asInt(), LocalDate.parse(r.get("from").asString()),
                        r.get("to").isNull() ? null : LocalDate.parse(r.get("to").asString())));
            }
            List<TaxableLine> lines = new ArrayList<>();
            for (JsonNode l : c.get("lines")) {
                lines.add(new TaxableLine(l.get("category").asString(), FoodTemp.valueOf(l.get("temp").asString()),
                        Money.ofCents(l.get("cents").asLong(), USD)));
            }
            List<ServiceCharge> charges = new ArrayList<>();
            for (JsonNode s : c.get("charges")) {
                Money m = Money.ofCents(s.get("cents").asLong(), USD);
                charges.add(switch (s.get("kind").asString()) {
                    case "MANDATORY" -> ServiceCharge.mandatory(m);
                    case "AUTO_GRATUITY" -> ServiceCharge.autoGratuity(m);
                    default -> ServiceCharge.deliveryFee(m, s.get("deliveryFeeTaxable").asBoolean());
                });
            }
            JsonNode expected = c.get("expected");
            if (expected.isNull()) {
                assertThatThrownBy(() -> TaxEngine.compute(ctx, rules, lines, charges, Money.zero(USD))).as(name)
                        .isInstanceOf(MissingTaxRuleException.class);
            } else {
                TaxResult r = TaxEngine.compute(ctx, rules, lines, charges, Money.zero(USD));
                assertThat(r.taxableBase().cents()).as(name + " taxable").isEqualTo(expected.get("taxable").asLong());
                assertThat(r.exemptBase().cents()).as(name + " exempt").isEqualTo(expected.get("exempt").asLong());
                assertThat(r.tax().cents()).as(name + " tax").isEqualTo(expected.get("tax").asLong());
            }
        }
    }
}
