package com.squaretoast.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The same JSON vectors the Android POS runs (shared-test-vectors/), so both ports of each rule agree. */
class SharedVectorsTest {

    private static JsonNode vectors(String name) throws IOException {
        try (InputStream in = Objects.requireNonNull(SharedVectorsTest.class.getClassLoader().getResourceAsStream(name))) {
            return JsonMapper.builder().build().readTree(in);
        }
    }

    @Test
    void courseTransitionsCoverEveryPair() throws IOException {
        JsonNode transitions = vectors("course-transitions.json").get("transitions");
        assertThat(transitions.size()).isEqualTo(CourseState.values().length * CourseAction.values().length);
        for (JsonNode v : transitions) {
            CourseState from = CourseState.valueOf(v.get("from").asString());
            CourseAction action = CourseAction.valueOf(v.get("action").asString());
            if (v.get("to").isNull()) {
                assertThatThrownBy(() -> CourseStateMachine.next(from, action))
                        .as("%s + %s", from, action)
                        .isInstanceOfSatisfying(IllegalCourseTransitionException.class, e -> {
                            assertThat(e.from()).isEqualTo(from);
                            assertThat(e.action()).isEqualTo(action);
                        });
            } else {
                assertThat(CourseStateMachine.next(from, action)).as("%s + %s", from, action)
                        .isEqualTo(CourseState.valueOf(v.get("to").asString()));
            }
        }
    }

    @Test
    void splitAllocationFollowsVectors() throws IOException {
        for (JsonNode c : vectors("split-allocation.json").get("cases")) {
            long amount = c.get("amount").asLong();
            List<Long> weights = new ArrayList<>();
            c.get("weights").forEach(w -> weights.add(w.asLong()));
            if (c.get("expected").isNull()) {
                assertThatThrownBy(() -> SplitAllocator.allocate(amount, weights)).as("%d by %s", amount, weights)
                        .isInstanceOf(IllegalArgumentException.class);
            } else {
                List<Long> expected = new ArrayList<>();
                c.get("expected").forEach(e -> expected.add(e.asLong()));
                assertThat(SplitAllocator.allocate(amount, weights)).as("%d by %s", amount, weights).isEqualTo(expected);
            }
        }
    }

    @Test
    void largeWeightsDoNotOverflow() {
        assertThat(SplitAllocator.allocate(9_999_999_999L, List.of(Long.MAX_VALUE / 4, Long.MAX_VALUE / 4, 7L)))
                .containsExactly(5_000_000_000L, 4_999_999_999L, 0L);
    }
}
