package com.squaretoast.realtime;

import java.util.UUID;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Subscriptions for other modules, e.g. the authenticated KDS stream. */
@Component
public class RealtimeStreams {

    private final SseBroadcaster broadcaster;
    private final RealtimeEventStore store;

    RealtimeStreams(SseBroadcaster broadcaster, RealtimeEventStore store) {
        this.broadcaster = broadcaster;
        this.store = store;
    }

    public SseEmitter subscribe(UUID location, @Nullable Long lastEventId, Predicate<String> topics) {
        return broadcaster.subscribe(location, lastEventId, topics);
    }

    /** The newest committed seq for a location; a snapshot read after this is at least this new. */
    public long head(UUID location) {
        return store.head(location);
    }
}
