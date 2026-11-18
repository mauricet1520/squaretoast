package com.squaretoast.kds;

import com.squaretoast.device.DevicePrincipal;
import com.squaretoast.domain.order.CourseAction;
import com.squaretoast.domain.order.CourseState;
import com.squaretoast.order.DeviceOrderService;
import com.squaretoast.order.KdsTickets;
import com.squaretoast.order.KdsTickets.Ticket;
import com.squaretoast.order.OrderRejected;
import com.squaretoast.realtime.RealtimeStreams;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The browser KDS. Board and transitions need a device token; the stream is opened with a one-time ticket from
 * {@code /kds/stream-tickets}, because EventSource cannot send an Authorization header.
 */
@RestController
class KdsController {

    record TransitionRequest(@Nullable String action, @Nullable String expectedFrom) {}

    record BoardView(long lastSeq, List<Ticket> tickets) {}

    record StreamTicket(String ticket, int expiresInSeconds) {}

    record Problem(String error) {}

    private static final class BadRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final Set<String> CONFLICTS = Set.of("STALE_STATE", "ILLEGAL_TRANSITION");

    private final KdsTickets tickets;
    private final DeviceOrderService orders;
    private final RealtimeStreams streams;
    private final StreamTickets streamTickets;
    private final JdbcClient db;
    private final TransactionTemplate tx;

    KdsController(KdsTickets tickets, DeviceOrderService orders, RealtimeStreams streams, StreamTickets streamTickets,
                  JdbcClient db, PlatformTransactionManager txm) {
        this.tickets = tickets;
        this.orders = orders;
        this.streams = streams;
        this.streamTickets = streamTickets;
        this.db = db;
        this.tx = new TransactionTemplate(txm);
    }

    /** {@code lastSeq} is read before the tickets, so resuming the stream after it can only repeat, never skip. */
    @GetMapping(path = "/kds/board", version = "1")
    BoardView board(@RequestAttribute(DevicePrincipal.ATTRIBUTE) DevicePrincipal device) {
        long head = streams.head(device.locationId());
        return new BoardView(head, tickets.board(device.locationId()));
    }

    @PostMapping(path = "/courses/{courseId}/transitions", version = "1")
    ResponseEntity<?> transition(@RequestAttribute(DevicePrincipal.ATTRIBUTE) DevicePrincipal device,
                                 @PathVariable UUID courseId, @RequestBody TransitionRequest req) {
        CourseAction action = parse(CourseAction.class, req.action());
        CourseState from = parse(CourseState.class, req.expectedFrom());
        Optional<UUID> order = db.sql("""
                select c.order_id from course c join orders o on o.id = c.order_id where c.id = ? and o.location_id = ?""")
                .params(courseId, device.locationId()).query(UUID.class).optional();
        if (order.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            tx.executeWithoutResult(s ->
                    orders.transitionCourse(device.deviceId(), device.locationId(), order.get(), courseId, action, from));
        } catch (OrderRejected e) {
            return CONFLICTS.contains(e.reason())
                    ? ResponseEntity.status(HttpStatus.CONFLICT).body(new Problem(e.reason()))
                    : ResponseEntity.notFound().build();
        }
        return ResponseEntity.of(tickets.ticket(courseId));
    }

    @PostMapping(path = "/kds/stream-tickets", version = "1")
    StreamTicket streamTicket(@RequestAttribute(DevicePrincipal.ATTRIBUTE) DevicePrincipal device) {
        return new StreamTicket(streamTickets.issue(device.deviceId(), device.locationId()), 60);
    }

    /** Every topic for the ticket's location. {@code lastEventId} (query) wins over the Last-Event-ID header. */
    @GetMapping(path = "/kds/stream", version = "1", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> stream(@RequestParam(required = false) @Nullable String ticket,
                                      @RequestParam(required = false) @Nullable Long lastEventId,
                                      @RequestHeader(name = "Last-Event-ID", required = false) @Nullable Long header) {
        Optional<UUID> location = ticket == null ? Optional.empty() : streamTickets.redeem(ticket);
        if (location.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Long from = lastEventId != null ? lastEventId : header;
        return ResponseEntity.ok(streams.subscribe(location.get(), from, topic -> true));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, @Nullable String value) {
        if (value == null) {
            throw new BadRequest();
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new BadRequest();
        }
    }

    @ExceptionHandler
    ResponseEntity<Void> badRequest(BadRequest e) {
        return ResponseEntity.badRequest().build();
    }
}
