package com.squaretoast.guest;

import com.squaretoast.domain.payment.GatewayException;
import com.squaretoast.guest.GuestOrders.LineRequest;
import com.squaretoast.guest.GuestOrders.Placed;
import com.squaretoast.guest.GuestOrders.Table;
import com.squaretoast.menu.MenuRepository;
import com.squaretoast.menu.MenuViews;
import com.squaretoast.menu.MenuViews.MenuView;
import com.squaretoast.payment.CardMethod;
import com.squaretoast.payment.CheckBills;
import com.squaretoast.payment.CheckBills.Bill;
import com.squaretoast.payment.CheckPaymentService;
import com.squaretoast.payment.PaymentException;
import com.squaretoast.payment.PublishableKey;
import com.squaretoast.payment.StartedPayment;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The guest's table pages, authorized by the table's QR token (plan S6-5, S6-6). A table sees only its own
 * checks from the last 12 hours, so a photo of the QR code does not show later diners' bills.
 */
@RestController
class GuestController {

    record TableView(String table, String currency, MenuView menu) {}

    record OrderRequest(@Nullable UUID orderId, @Nullable List<LineRequest> lines) {}

    record CheckSummary(UUID checkId, String label, String status, long dueCents, String currency) {}

    record Checks(List<CheckSummary> checks) {}

    record PayRequest(@Nullable UUID idempotencyKey, @Nullable Long tipCents) {}

    record Started(UUID paymentId, @Nullable String clientSecret, String publishableKey) {}

    record Problem(String error) {}

    private record OwnedCheck(String orderStatus) {}

    private static final class BadRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class NotFound extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private final GuestOrders orders;
    private final MenuRepository menus;
    private final CheckBills bills;
    private final CheckPaymentService checkPayments;
    private final PublishableKey publishableKey;
    private final JdbcClient db;

    GuestController(GuestOrders orders, MenuRepository menus, CheckBills bills, CheckPaymentService checkPayments,
                    PublishableKey publishableKey, JdbcClient db) {
        this.orders = orders;
        this.menus = menus;
        this.bills = bills;
        this.checkPayments = checkPayments;
        this.publishableKey = publishableKey;
        this.db = db;
    }

    @GetMapping(path = "/t/{qr}", version = "1")
    TableView table(@PathVariable String qr) {
        Table t = tableFor(qr);
        Set<UUID> visible = new HashSet<>(db.sql("select id from menu_item where location_id = ? and active and visible_qr")
                .params(t.location()).query(UUID.class).list());
        return menus.load(t.location())
                .map(m -> new TableView(t.label(), m.currency().code(), MenuViews.view(m, visible::contains)))
                .orElseThrow(NotFound::new);
    }

    @PostMapping(path = "/t/{qr}/orders", version = "1")
    Placed order(@PathVariable String qr, @RequestBody OrderRequest req) {
        UUID orderId = req.orderId();
        List<LineRequest> lines = req.lines();
        if (orderId == null || lines == null || lines.contains(null)) {
            throw new BadRequest();
        }
        return orders.place(tableFor(qr), orderId, lines);
    }

    @GetMapping(path = "/t/{qr}/checks", version = "1")
    Checks checks(@PathVariable String qr) {
        Table t = tableFor(qr);
        record Row(UUID id, String label, String status) {}
        List<Row> rows = db.sql("""
                select g.id, g.label, g.status from guest_check g join orders o on o.id = g.order_id
                where o.table_id = ? and o.status = 'COMMITTED' and o.opened_at > now() - interval '12 hours'
                order by o.opened_at, g.label, g.id""")
                .params(t.id())
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("label"), rs.getString("status")))
                .list();
        return new Checks(rows.stream().map(r -> {
            Bill b = bills.bill(r.id(), t.location());
            return new CheckSummary(r.id(), r.label(), r.status(), b.dueCents(), b.currency());
        }).toList());
    }

    @GetMapping(path = "/t/{qr}/checks/{checkId}", version = "1")
    Bill check(@PathVariable String qr, @PathVariable UUID checkId) {
        Table t = tableFor(qr);
        owned(t, checkId);
        return bills.bill(checkId, t.location());
    }

    @PostMapping(path = "/t/{qr}/checks/{checkId}/payments", version = "1")
    ResponseEntity<?> pay(@PathVariable String qr, @PathVariable UUID checkId, @RequestBody PayRequest req) {
        UUID key = req.idempotencyKey();
        Long tip = req.tipCents();
        if (key == null || tip == null || tip < 0) {
            throw new BadRequest();
        }
        Table t = tableFor(qr);
        if (!owned(t, checkId).orderStatus().equals("COMMITTED")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new Problem("ORDER_NOT_ACCEPTED"));
        }
        String pk = publishableKey.require();
        StartedPayment p = checkPayments.start(checkId, t.location(), key, CardMethod.QR_CNP, tip);
        return ResponseEntity.ok(new Started(p.paymentId(), p.clientSecret(), pk));
    }

    private Table tableFor(String qr) {
        return qr.length() > 128 ? fail() : orders.table(qr).orElseThrow(NotFound::new);
    }

    private static Table fail() {
        throw new NotFound();
    }

    private OwnedCheck owned(Table t, UUID checkId) {
        return db.sql("""
                select o.status from guest_check g join orders o on o.id = g.order_id
                where g.id = ? and o.table_id = ? and o.opened_at > now() - interval '12 hours'""")
                .params(checkId, t.id()).query((rs, n) -> new OwnedCheck(rs.getString("status")))
                .optional().orElseThrow(NotFound::new);
    }

    @ExceptionHandler
    ResponseEntity<Void> badRequest(BadRequest e) {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler({NotFound.class, PaymentException.NotFound.class})
    ResponseEntity<Void> notFound(RuntimeException e) {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler
    ResponseEntity<Problem> refused(GuestOrders.Refused e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(new Problem(e.code));
    }

    @ExceptionHandler
    ResponseEntity<Problem> tooMany(GuestOrders.TooManyWaiting e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new Problem("TOO_MANY_PENDING_ORDERS"));
    }

    @ExceptionHandler
    ResponseEntity<Problem> tip(PaymentException.TipOutOfRange e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(new Problem("TIP_OUT_OF_RANGE"));
    }

    @ExceptionHandler
    ResponseEntity<Problem> nothingDue(PaymentException.NothingDue e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Problem("NOTHING_DUE"));
    }

    @ExceptionHandler
    ResponseEntity<Problem> checkNotOpen(PaymentException.CheckNotOpen e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Problem("CHECK_NOT_OPEN"));
    }

    @ExceptionHandler
    ResponseEntity<Problem> notConfigured(PaymentException.NotConfigured e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Problem("NOT_CONFIGURED"));
    }

    @ExceptionHandler
    ResponseEntity<Problem> idempotency(PaymentException.IdempotencyConflict e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Problem("IDEMPOTENCY_KEY_REUSED"));
    }

    /** Only the processor's error code leaves the server, never its message. */
    @ExceptionHandler
    ResponseEntity<Problem> gateway(GatewayException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new Problem(e.code()));
    }
}
