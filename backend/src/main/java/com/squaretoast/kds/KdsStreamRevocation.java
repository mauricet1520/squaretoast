package com.squaretoast.kds;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Ends the open KDS streams of devices that have been revoked (plan S7-5). Revocation is a database change with no
 * hook to call, so the open streams are checked on a short interval instead.
 */
@Component
class KdsStreamRevocation {

    private static final Logger log = LoggerFactory.getLogger(KdsStreamRevocation.class);

    private final JdbcClient db;
    private final ConcurrentHashMap<UUID, Set<SseEmitter>> open = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("kds-revocation-sweep").factory());

    KdsStreamRevocation(JdbcClient db, @Value("${squaretoast.kds.revocation-sweep:15s}") Duration every) {
        this.db = db;
        // sweep() catches its own failures, so the schedule never stops and the Future has nothing to report.
        @SuppressWarnings("FutureReturnValueIgnored")
        var unused = sweeper.scheduleWithFixedDelay(this::sweep, every.toMillis(), every.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Remembers {@code emitter} as {@code device}'s stream until it ends. */
    void track(UUID device, SseEmitter emitter) {
        open.computeIfAbsent(device, d -> ConcurrentHashMap.newKeySet()).add(emitter);
        Runnable forget = () -> open.computeIfPresent(device, (d, set) -> {
            set.remove(emitter);
            return set.isEmpty() ? null : set;
        });
        emitter.onCompletion(forget);
        emitter.onTimeout(forget);
        emitter.onError(t -> forget.run());
    }

    void sweep() {
        try {
            for (UUID device : List.copyOf(open.keySet())) {
                boolean revoked = db.sql("select revoked_at is not null from device where id = ?").params(device)
                        .query(Boolean.class).optional().orElse(true);
                if (revoked) {
                    Set<SseEmitter> streams = open.remove(device);
                    if (streams != null) {
                        streams.forEach(SseEmitter::complete);
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("KDS revocation sweep failed; retrying on the next run", e);
        }
    }

    @PreDestroy
    void stop() {
        sweeper.shutdownNow();
    }
}
