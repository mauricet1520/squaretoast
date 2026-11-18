package com.squaretoast.order;

import com.squaretoast.domain.order.CourseAction;
import com.squaretoast.domain.order.CourseState;
import com.squaretoast.domain.order.CourseStateMachine;
import com.squaretoast.domain.order.IllegalCourseTransitionException;
import com.squaretoast.realtime.RealtimePublisher;
import com.squaretoast.realtime.Topics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies POS orders and course changes. Runs inside the caller's transaction so a rejection rolls back
 * everything, including the inventory taken by {@link OrderCommitService}.
 */
@Service
public class DeviceOrderService {

    /** A line as the device sent it. The device's price is never used: lines are priced from the menu. */
    public record PlacedLine(UUID lineId, UUID courseId, int course, int seat, UUID menuItemId, int quantity) {}

    private record MenuLine(long priceCents, String taxCategory, String temp, String name) {}

    private final JdbcClient db;
    private final OrderCommitService commits;
    private final RealtimePublisher realtime;
    private final KdsTickets tickets;

    DeviceOrderService(JdbcClient db, OrderCommitService commits, RealtimePublisher realtime, KdsTickets tickets) {
        this.db = db;
        this.commits = commits;
        this.realtime = realtime;
        this.tickets = tickets;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void placeOrder(UUID device, UUID location, UUID orderId, @Nullable String tableLabel, List<PlacedLine> lines) {
        if (lines.isEmpty()) {
            throw new OrderRejected("INVALID_MUTATION");
        }
        Map<Integer, UUID> courses = new LinkedHashMap<>();
        TreeSet<Integer> seats = new TreeSet<>();
        for (PlacedLine l : lines) {
            if (l.quantity() <= 0 || l.seat() < 1 || l.course() < 1) {
                throw new OrderRejected("INVALID_MUTATION");
            }
            UUID prior = courses.putIfAbsent(l.course(), l.courseId());
            if (prior != null && !prior.equals(l.courseId())) {
                throw new OrderRejected("INVALID_MUTATION");
            }
            seats.add(l.seat());
        }
        if (db.sql("select count(*) from orders where id = ?").params(orderId).query(Long.class).single() > 0) {
            throw new OrderRejected("ORDER_EXISTS");
        }
        UUID table = tableLabel == null ? null : db.sql("select id from dining_table where location_id = ? and label = ?")
                .params(location, tableLabel).query(UUID.class).optional().orElse(null);

        db.sql("insert into orders(id, location_id, table_id, fulfillment_channel, origin_device_id) values (?, ?, ?, 'DINE_IN', ?)")
                .params(java.util.Arrays.asList(orderId, location, table, device)).update();
        Map<Integer, UUID> seatIds = new LinkedHashMap<>();
        for (int seat : seats) {
            UUID id = UUID.randomUUID();
            seatIds.put(seat, id);
            db.sql("insert into seat(id, order_id, seat_no) values (?, ?, ?)").params(id, orderId, seat).update();
        }
        for (Map.Entry<Integer, UUID> c : courses.entrySet()) {
            db.sql("insert into course(id, order_id, course_no) values (?, ?, ?)").params(c.getValue(), orderId, c.getKey()).update();
        }
        UUID check = UUID.randomUUID();
        db.sql("insert into guest_check(id, order_id, label) values (?, ?, 'Check 1')").params(check, orderId).update();
        for (PlacedLine l : lines) {
            MenuLine m = db.sql("select price_cents, tax_category, temp, name from menu_item where id = ? and location_id = ?")
                    .params(l.menuItemId(), location)
                    .query((rs, n) -> new MenuLine(rs.getLong("price_cents"), rs.getString("tax_category"),
                            rs.getString("temp"), rs.getString("name")))
                    .optional().orElseThrow(() -> new OrderRejected("ITEM_NOT_AT_LOCATION"));
            db.sql("""
                    insert into order_line(id, order_id, seat_id, course_id, menu_item_id, qty, unit_price_cents,
                                           tax_category, temp, name_snapshot)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(l.lineId(), orderId, Objects.requireNonNull(seatIds.get(l.seat())), l.courseId(), l.menuItemId(), l.quantity(),
                            m.priceCents(), m.taxCategory(), m.temp(), m.name())
                    .update();
            db.sql("insert into check_allocation(check_id, order_line_id, numerator, denominator) values (?, ?, 1, 1)")
                    .params(check, l.lineId()).update();
        }
        try {
            commits.commit(orderId); // inventory is taken here, at commit, not at kitchen print
        } catch (CommitException.Conflict e) {
            throw new OrderRejected(e.conflict);
        } catch (CommitException.Rejected e) {
            throw new OrderRejected(e.error);
        } catch (CommitException.NotFound e) {
            throw new OrderRejected("INVALID_MUTATION");
        }
    }

    /**
     * Applies {@code action} to the course's current server state. {@code expectedFrom} is the state the device
     * saw; if the server has moved on, the device's view is stale and nothing changes.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void transitionCourse(UUID device, UUID location, UUID orderId, UUID courseId, CourseAction action,
                                 CourseState expectedFrom) {
        record Row(UUID order, UUID location, String state) {}
        Row row = db.sql("""
                select c.order_id, o.location_id, c.state from course c join orders o on o.id = c.order_id
                where c.id = ? for update of c""")
                .params(courseId)
                .query((rs, n) -> new Row(rs.getObject("order_id", UUID.class), rs.getObject("location_id", UUID.class),
                        rs.getString("state")))
                .optional().orElseThrow(() -> new OrderRejected("INVALID_MUTATION"));
        if (!row.location().equals(location)) {
            throw new OrderRejected("FORBIDDEN_LOCATION");
        }
        if (!row.order().equals(orderId)) {
            throw new OrderRejected("INVALID_MUTATION");
        }
        CourseState current = fromDb(row.state());
        if (current != expectedFrom) {
            throw new OrderRejected("STALE_STATE");
        }
        CourseState next;
        try {
            next = CourseStateMachine.next(current, action);
        } catch (IllegalCourseTransitionException e) {
            throw new OrderRejected("ILLEGAL_TRANSITION");
        }
        db.sql("update course set state = ?, version = version + 1 where id = ?").params(toDb(next), courseId).update();
        db.sql("insert into course_transition(course_id, from_state, to_state, actor_id) values (?, ?, ?, ?)")
                .params(courseId, row.state(), toDb(next), device).update();
        // Every transition, from the KDS or a POS sync, reaches kitchen screens with the whole ticket.
        realtime.publish(location, Topics.COURSE, () -> tickets.ticket(courseId).orElseThrow());
    }

    /** The database keeps the KDS-only PREPPING state; to the POS it is still fired. */
    private static CourseState fromDb(String state) {
        return switch (state) {
            case "HOLD" -> CourseState.HOLD;
            case "FIRE", "PREPPING" -> CourseState.FIRED;
            case "READY" -> CourseState.READY;
            case "SERVED" -> CourseState.SERVED;
            default -> throw new IllegalStateException("unknown course state " + Objects.toString(state));
        };
    }

    private static String toDb(CourseState state) {
        return state == CourseState.FIRED ? "FIRE" : state.name();
    }
}
