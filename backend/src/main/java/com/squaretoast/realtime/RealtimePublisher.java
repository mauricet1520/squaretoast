package com.squaretoast.realtime;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Records a realtime event in the caller's transaction; it is broadcast only after that transaction commits. */
@Component
public class RealtimePublisher {

    private final RealtimeEventStore store;
    private final JsonMapper json;
    private final ApplicationEventPublisher events;

    RealtimePublisher(RealtimeEventStore store, JsonMapper json, ApplicationEventPublisher events) {
        this.store = store;
        this.json = json;
        this.events = events;
    }

    /**
     * The payload is built after the location lock is taken, so it reflects every change published before it.
     * Building it from a snapshot read earlier could broadcast a stale state over a newer one.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(UUID location, String topic, Supplier<?> payload) {
        store.lockLocation(location);
        store.append(location, topic, json.writeValueAsString(payload.get()));
        events.publishEvent(new Appended(location));
    }

    /** Signals the broadcaster after commit; carries no payload, the broadcaster reads the committed log. */
    record Appended(UUID locationId) {}
}
