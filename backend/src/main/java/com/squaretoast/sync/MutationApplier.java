package com.squaretoast.sync;

import com.squaretoast.device.DevicePrincipal;
import com.squaretoast.domain.order.CourseAction;
import com.squaretoast.domain.order.CourseState;
import com.squaretoast.order.DeviceOrderService;
import com.squaretoast.order.DeviceOrderService.PlacedLine;
import com.squaretoast.order.OrderRejected;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies one POS mutation exactly once. The APPLIED marker is written in the same transaction as the change,
 * so a crash can never leave one without the other. A rejected mutation is rolled back and then recorded as
 * CONFLICT in its own transaction, so a resend gets the same answer.
 */
@Service
class MutationApplier {

    record Mutation(@Nullable String mutationId, @Nullable String aggregateId, @Nullable String type,
                    long deviceSeq, @Nullable String payloadJson) {}

    enum Status { APPLIED, DUPLICATE, CONFLICT }

    record Result(String mutationId, Status status, @Nullable String reason) {}

    /** {@code checkId} is the POS's own check id, sent when it may have issued an offline claim for the check. */
    private record OrderPlaced(String orderId, String locationId, @Nullable String tableLabel, List<Line> lines,
                               @Nullable String checkId) {}

    /** {@code optionIds} is absent from POS builds before Slice 9; that means no options. */
    private record Line(String lineId, String courseId, int course, int seat, String menuItemId, int quantity,
                        @Nullable List<String> optionIds) {}

    private record CourseTransitioned(String courseId, String action, String from) {}

    private record Prior(UUID device, String result, @Nullable String reason) {}

    /** Thrown when another request recorded the same mutation id first; the caller re-reads its result. */
    private static final class LostRace extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String INVALID = "INVALID_MUTATION";

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final DeviceOrderService orders;

    MutationApplier(JdbcClient db, PlatformTransactionManager txm, DeviceOrderService orders) {
        this.db = db;
        this.tx = new TransactionTemplate(txm);
        this.orders = orders;
    }

    Result apply(DevicePrincipal device, Mutation m) {
        String rawId = m.mutationId() == null ? "" : m.mutationId();
        UUID id;
        try {
            id = UUID.fromString(rawId);
        } catch (IllegalArgumentException e) {
            return new Result(rawId, Status.CONFLICT, INVALID); // cannot be recorded without a valid id
        }
        Optional<Result> seen = prior(device, id);
        if (seen.isPresent()) {
            return seen.get();
        }
        try {
            tx.executeWithoutResult(s -> {
                int claimed = db.sql("""
                        insert into processed_client_mutation(mutation_id, device_id, result) values (?, ?, 'APPLIED')
                        on conflict (mutation_id) do nothing""").params(id, device.deviceId()).update();
                if (claimed == 0) {
                    throw new LostRace();
                }
                dispatch(device, m);
            });
            return new Result(rawId, Status.APPLIED, null);
        } catch (LostRace e) {
            return prior(device, id).orElseThrow();
        } catch (OrderRejected e) {
            return conflict(device, id, e.reason());
        } catch (DataIntegrityViolationException e) {
            return conflict(device, id, INVALID); // e.g. a client id that collides with an existing row
        }
    }

    private void dispatch(DevicePrincipal device, Mutation m) {
        String type = m.type() == null ? "" : m.type();
        String payload = m.payloadJson() == null ? "" : m.payloadJson();
        try {
            switch (type) {
                case "ORDER_PLACED" -> {
                    OrderPlaced p = JSON.readValue(payload, OrderPlaced.class);
                    UUID orderId = UUID.fromString(p.orderId());
                    if (!orderId.toString().equals(m.aggregateId())) {
                        throw new OrderRejected(INVALID);
                    }
                    if (!UUID.fromString(p.locationId()).equals(device.locationId())) {
                        throw new OrderRejected("FORBIDDEN_LOCATION");
                    }
                    List<PlacedLine> lines = p.lines().stream().map(l -> new PlacedLine(UUID.fromString(l.lineId()),
                            UUID.fromString(l.courseId()), l.course(), l.seat(), UUID.fromString(l.menuItemId()),
                            l.quantity(), l.optionIds() == null ? List.of()
                                    : l.optionIds().stream().map(UUID::fromString).toList())).toList();
                    String check = p.checkId();
                    orders.placeOrder(device.deviceId(), device.locationId(), orderId, p.tableLabel(), lines,
                            check == null ? null : UUID.fromString(check));
                }
                case "COURSE_TRANSITIONED" -> {
                    CourseTransitioned p = JSON.readValue(payload, CourseTransitioned.class);
                    orders.transitionCourse(device.deviceId(), device.locationId(),
                            UUID.fromString(String.valueOf(m.aggregateId())), UUID.fromString(p.courseId()),
                            CourseAction.valueOf(p.action()), CourseState.valueOf(p.from()));
                }
                default -> throw new OrderRejected(INVALID);
            }
        } catch (JacksonException | IllegalArgumentException | NullPointerException e) {
            throw new OrderRejected(INVALID);
        }
    }

    private Result conflict(DevicePrincipal device, UUID id, String reason) {
        tx.executeWithoutResult(s -> db.sql("""
                insert into processed_client_mutation(mutation_id, device_id, result, reason) values (?, ?, 'CONFLICT', ?)
                on conflict (mutation_id) do nothing""").params(id, device.deviceId(), reason).update());
        return prior(device, id).orElseThrow();
    }

    private Optional<Result> prior(DevicePrincipal device, UUID id) {
        return db.sql("select device_id, result, reason from processed_client_mutation where mutation_id = ?")
                .params(id)
                .query((rs, n) -> new Prior(rs.getObject("device_id", UUID.class), rs.getString("result"), rs.getString("reason")))
                .optional()
                .map(p -> {
                    if (!p.device().equals(device.deviceId())) {
                        return new Result(id.toString(), Status.CONFLICT, "MUTATION_ID_TAKEN");
                    }
                    return p.result().equals("APPLIED")
                            ? new Result(id.toString(), Status.DUPLICATE, null)
                            : new Result(id.toString(), Status.CONFLICT, p.reason());
                });
    }
}
