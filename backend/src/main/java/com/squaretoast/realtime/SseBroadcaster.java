package com.squaretoast.realtime;

import com.squaretoast.realtime.RealtimeEventStore.StoredEvent;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Per-location SSE fan-out. After each commit the broadcaster reads the committed log past its cursor and sends
 * it in seq order, so subscribers never see gaps or reordering. New subscribers replay from Last-Event-ID up to
 * the cursor under the same lock, then receive everything after it live.
 */
@Component
class SseBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(SseBroadcaster.class);
    private static final Duration TIMEOUT = Duration.ofMinutes(30);
    private static final long RECONNECT_MILLIS = 1000;

    private final RealtimeEventStore store;
    private final ConcurrentHashMap<UUID, Channel> channels = new ConcurrentHashMap<>();
    private final ScheduledFuture<?> beating;
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("sse-heartbeat").daemon().factory());

    /**
     * Every {@code heartbeat}, each open stream gets an SSE comment. A quiet POS then keeps its stream (its client
     * gives up after two silent minutes) and idle proxies do not cut it. Comments have no id, so replay is unchanged.
     */
    SseBroadcaster(RealtimeEventStore store, @Value("${squaretoast.realtime.heartbeat:25s}") Duration heartbeat) {
        this.store = store;
        this.beating = heartbeats.scheduleAtFixedRate(this::beat, heartbeat.toMillis(), heartbeat.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        beating.cancel(false);
        heartbeats.shutdownNow();
    }

    private void beat() {
        for (Channel ch : channels.values()) {
            if (ch.subscribers.isEmpty() || !ch.lock.tryLock()) {
                continue; // a pump holding the lock is sending real events right now
            }
            try {
                List<Subscriber> gone = new ArrayList<>();
                for (Subscriber s : ch.subscribers) {
                    try {
                        s.emitter().send(SseEmitter.event().comment("hb"));
                    } catch (IOException | IllegalStateException closed) {
                        gone.add(s);
                    }
                }
                ch.subscribers.removeAll(gone);
            } catch (RuntimeException e) {
                log.debug("heartbeat failed", e); // the next beat tries again
            } finally {
                ch.lock.unlock();
            }
        }
    }

    /** {@code topics} decides which topics this subscriber may see; replay and live delivery both apply it. */
    SseEmitter subscribe(UUID location, @Nullable Long lastEventId, Predicate<String> topics) {
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        Subscriber sub = new Subscriber(emitter, topics);
        Channel ch = channels.computeIfAbsent(location, l -> new Channel(store.head(l)));
        ch.lock.lock();
        try {
            emitter.send(SseEmitter.event().reconnectTime(RECONNECT_MILLIS));
            long from = lastEventId == null ? ch.cursor : lastEventId;
            while (from < ch.cursor) { // events past the cursor are delivered by the next pump
                List<StoredEvent> page = store.after(location, from);
                if (page.isEmpty()) {
                    break;
                }
                for (StoredEvent e : page) {
                    if (e.seq() > ch.cursor) {
                        break;
                    }
                    if (topics.test(e.topic())) {
                        send(emitter, e);
                    }
                    from = e.seq();
                }
                if (page.getLast().seq() > ch.cursor) {
                    break;
                }
            }
            ch.subscribers.add(sub);
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
            return emitter;
        } finally {
            ch.lock.unlock();
        }
        Runnable remove = () -> ch.subscribers.remove(sub);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(t -> remove.run());
        return emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onAppended(RealtimePublisher.Appended event) {
        try {
            pump(event.locationId());
        } catch (RuntimeException e) {
            // The event is committed; subscribers catch up on the next pump or via Last-Event-ID.
            log.warn("realtime pump failed for location {}", event.locationId(), e);
        }
    }

    private void pump(UUID location) {
        Channel ch = channels.get(location);
        if (ch == null) {
            return;
        }
        ch.lock.lock();
        try {
            List<StoredEvent> page;
            do {
                page = store.after(location, ch.cursor);
                List<Subscriber> gone = new ArrayList<>();
                for (StoredEvent e : page) {
                    for (Subscriber s : ch.subscribers) {
                        if (!s.topics().test(e.topic())) {
                            continue;
                        }
                        try {
                            send(s.emitter(), e);
                        } catch (IOException | IllegalStateException closed) {
                            gone.add(s);
                        }
                    }
                    ch.cursor = e.seq();
                }
                ch.subscribers.removeAll(gone);
            } while (page.size() == RealtimeEventStore.MAX_REPLAY);
        } finally {
            ch.lock.unlock();
        }
    }

    private static void send(SseEmitter emitter, StoredEvent e) throws IOException {
        emitter.send(SseEmitter.event().id(Long.toString(e.seq())).name(e.topic()).data(e.payload(), MediaType.TEXT_PLAIN));
    }

    private record Subscriber(SseEmitter emitter, Predicate<String> topics) {}

    private static final class Channel {
        final ReentrantLock lock = new ReentrantLock();
        final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
        long cursor;

        Channel(long cursor) {
            this.cursor = cursor;
        }
    }
}
