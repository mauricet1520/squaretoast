package com.squaretoast.order;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Kitchen tickets: one per fired course, with table, lines, modifiers and each line's station. */
@Component
public class KdsTickets {

    /** Courses the kitchen is working on or has ready; an upper bound keeps one board response small. */
    private static final int MAX_BOARD = 200;

    public record Line(UUID lineId, String name, int qty, @Nullable String station, List<String> modifiers) {}

    /** {@code state} uses the POS names (HOLD, FIRED, READY, SERVED); the database's PREPPING shows as FIRED. */
    public record Ticket(UUID courseId, UUID orderId, @Nullable String table, int courseNo, String state, List<Line> lines) {}

    private record Head(UUID courseId, UUID orderId, @Nullable String table, int courseNo, String state) {}

    private final JdbcClient db;

    KdsTickets(JdbcClient db) {
        this.db = db;
    }

    public Optional<Ticket> ticket(UUID courseId) {
        return db.sql("""
                select c.id, c.order_id, c.course_no, c.state, t.label
                from course c join orders o on o.id = c.order_id left join dining_table t on t.id = o.table_id
                where c.id = ?""")
                .params(courseId)
                .query((rs, n) -> new Head(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                        rs.getString("label"), rs.getInt("course_no"), rs.getString("state")))
                .optional()
                .map(this::withLines);
    }

    /** Fired and ready courses at {@code location}, in the order they were first fired. */
    public List<Ticket> board(UUID location) {
        return db.sql("""
                select c.id, c.order_id, c.course_no, c.state, t.label
                from course c join orders o on o.id = c.order_id left join dining_table t on t.id = o.table_id
                where o.location_id = ? and o.status <> 'VOID' and c.state in ('FIRE', 'PREPPING', 'READY')
                order by (select min(x.seq) from course_transition x where x.course_id = c.id and x.to_state = 'FIRE')
                         nulls last, c.id
                limit ?""")
                .params(location, MAX_BOARD)
                .query((rs, n) -> new Head(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                        rs.getString("label"), rs.getInt("course_no"), rs.getString("state")))
                .list().stream().map(this::withLines).toList();
    }

    private Ticket withLines(Head h) {
        Map<UUID, List<String>> modifiers = new LinkedHashMap<>();
        db.sql("""
                select m.order_line_id, m.name_snapshot from order_line_modifier m join order_line l on l.id = m.order_line_id
                where l.course_id = ? order by m.name_snapshot, m.id""")
                .params(h.courseId())
                .query((rs, n) -> modifiers.computeIfAbsent(rs.getObject("order_line_id", UUID.class), k -> new ArrayList<>())
                        .add(rs.getString("name_snapshot")))
                .list();
        List<Line> lines = db.sql("""
                select l.id, l.name_snapshot, l.qty, s.name as station
                from order_line l join menu_item m on m.id = l.menu_item_id left join kds_station s on s.id = m.kds_station_id
                where l.course_id = ? order by l.name_snapshot, l.id""")
                .params(h.courseId())
                .query((rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new Line(id, rs.getString("name_snapshot"), rs.getInt("qty"), rs.getString("station"),
                            List.copyOf(modifiers.getOrDefault(id, List.of())));
                })
                .list();
        return new Ticket(h.courseId(), h.orderId(), h.table(), h.courseNo(), posState(h.state()), lines);
    }

    static String posState(String dbState) {
        return switch (dbState) {
            case "FIRE", "PREPPING" -> "FIRED";
            default -> dbState;
        };
    }
}
