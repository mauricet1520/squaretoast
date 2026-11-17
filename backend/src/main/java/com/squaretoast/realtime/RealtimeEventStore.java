package com.squaretoast.realtime;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code realtime_event}: the replay log behind Last-Event-ID. */
@Repository
class RealtimeEventStore {

    /** Upper bound on one replay, so a stale Last-Event-ID cannot pull the whole history in one go. */
    static final int MAX_REPLAY = 1000;

    private final JdbcClient jdbc;

    RealtimeEventStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Appends inside the caller's transaction. The per-location advisory lock is held until commit, so events of
     * one location commit in seq order and a reader never sees seq N+1 before N.
     */
    long append(UUID location, String topic, String json) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(cast(? as text), 0))").param(location.toString())
                .query((rs, n) -> n).list();
        return jdbc.sql("insert into realtime_event(location_id, topic, payload) values (?, ?, cast(? as jsonb)) returning seq")
                .params(location, topic, json).query(Long.class).single();
    }

    List<StoredEvent> after(UUID location, long seq) {
        return jdbc.sql("""
                select seq, topic, payload::text as payload from realtime_event
                where location_id = ? and seq > ? order by seq limit ?""")
                .params(location, seq, MAX_REPLAY)
                .query((rs, n) -> new StoredEvent(rs.getLong("seq"), rs.getString("topic"), rs.getString("payload")))
                .list();
    }

    long head(UUID location) {
        return jdbc.sql("select coalesce(max(seq), 0) from realtime_event where location_id = ?").param(location)
                .query(Long.class).single();
    }

    record StoredEvent(long seq, String topic, String payload) {}
}
