package com.squaretoast.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Flyway V1 baseline: structure + DB-level enforcement of CLAUDE.md money/compliance invariants. */
@Testcontainers
class FlywayV1IT {

    private static final String CHECK_VIOLATION = "23514";
    private static final String FK_VIOLATION = "23503";

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration").load().migrate();
    }

    // ---------- structure ----------

    @Test
    void createsEveryBaselineTable() throws SQLException {
        List<String> tables = column("""
                select table_name from information_schema.tables
                where table_schema = 'public' and table_type = 'BASE TABLE'""");
        assertThat(tables).contains(
                "merchant", "processor_account", "pricing_plan", "merchant_pricing", "state_compliance_profile",
                "location", "tax_rule", "kds_station", "inventory_item", "menu_item", "modifier_group",
                "menu_item_modifier_group", "modifier_option", "inventory_consumption", "dining_table", "device",
                "orders", "seat", "course", "course_transition", "order_line", "order_line_modifier",
                "service_charge", "guest_check", "check_allocation", "payment", "journal_txn", "journal_line",
                "employee", "tip_pool", "tip_pool_member", "webhook_event", "processed_client_mutation",
                "offline_check_claim", "realtime_event");
    }

    @Test
    void noFloatingPointOrMoneyTypedColumnsExist() throws SQLException {
        assertThat(column("""
                select table_name || '.' || column_name from information_schema.columns
                where table_schema = 'public' and data_type in ('double precision', 'real', 'money', 'numeric')"""))
                .isEmpty();
    }

    @Test
    void everyCentsColumnIsBigint() throws SQLException {
        assertThat(column("""
                select table_name || '.' || column_name || ':' || data_type from information_schema.columns
                where table_schema = 'public' and column_name like '%\\_cents' and data_type <> 'bigint'"""))
                .isEmpty();
        assertThat(column("""
                select column_name from information_schema.columns
                where table_schema = 'public' and column_name like '%\\_cents'""")).isNotEmpty();
    }

    // ---------- $0/mo pricing ----------

    @Test
    void pricingPlanMonthlyFeeIsAlwaysZero() {
        assertThatCode(() -> exec("""
                insert into pricing_plan(id, code, monthly_fee_cents, cp_spread_bps, cp_fixed_cents, cnp_spread_bps, cnp_fixed_cents)
                values ('%s', 'FREE_%s', 0, 50, 0, 80, 10)""".formatted(UUID.randomUUID(), shortId())))
                .doesNotThrowAnyException();
        assertRejected(CHECK_VIOLATION, """
                insert into pricing_plan(id, code, monthly_fee_cents, cp_spread_bps, cp_fixed_cents, cnp_spread_bps, cnp_fixed_cents)
                values ('%s', 'PAID_%s', 1, 50, 0, 80, 10)""".formatted(UUID.randomUUID(), shortId()));
    }

    // ---------- state compliance ----------

    @Test
    void cardFeeOnTipDeductionIsBlockedInCaMeMaDe() throws SQLException {
        assertThat(column("select state_code from state_compliance_profile where not tip_card_fee_deduction_allowed"))
                .contains("CA", "ME", "MA", "DE");
        for (String st : List.of("CA", "ME", "MA", "DE")) {
            assertRejected(CHECK_VIOLATION,
                    "update state_compliance_profile set tip_card_fee_deduction_allowed = true where state_code = '" + st + "'");
        }
    }

    @Test
    void tipCreditLocationRejectedWhereStateForbidsTipCredit() {
        UUID m = merchant();
        assertRejected(CHECK_VIOLATION, locationSql(UUID.randomUUID(), m, "CA", true));
        assertThatCode(() -> exec(locationSql(UUID.randomUUID(), m, "MA", true))).doesNotThrowAnyException();
    }

    // ---------- append-only, balanced ledger ----------

    @Test
    void balancedJournalTxnCommitsAndIsAppendOnly() throws SQLException {
        UUID loc = location("MA", false);
        UUID txn = UUID.randomUUID();
        tx(journalTxnSql(txn, loc), lineSql(txn, "MERCHANT_CASH", 1000), lineSql(txn, "SALES", -1000));
        assertRejected(CHECK_VIOLATION, "update journal_line set amount_cents = 1 where txn_id = '" + txn + "'");
        assertRejected(CHECK_VIOLATION, "delete from journal_line where txn_id = '" + txn + "'");
        assertRejected(CHECK_VIOLATION, "update journal_txn set kind = 'X' where id = '" + txn + "'");
    }

    @Test
    void unbalancedJournalTxnRejectedAtCommit() {
        UUID loc = location("MA", false);
        UUID txn = UUID.randomUUID();
        assertRejected(CHECK_VIOLATION, journalTxnSql(txn, loc), lineSql(txn, "MERCHANT_CASH", 100), lineSql(txn, "SALES", -99));
    }

    // ---------- unified inventory -> menu/modifier ----------

    @Test
    void inventoryConsumptionHasExactlyOneOwner() throws SQLException {
        UUID loc = location("MA", false);
        UUID inv = inventory(loc);
        UUID item = menuItem(loc);
        assertRejected(CHECK_VIOLATION,
                "insert into inventory_consumption(id, inventory_item_id, qty) values ('%s','%s',1)".formatted(UUID.randomUUID(), inv));
        UUID group = UUID.randomUUID();
        UUID opt = UUID.randomUUID();
        exec("insert into modifier_group(id, location_id, name) values ('%s','%s','Cheese')".formatted(group, loc));
        exec("insert into modifier_option(id, modifier_group_id, name, price_delta_cents) values ('%s','%s','Cheddar',75)".formatted(opt, group));
        assertRejected(CHECK_VIOLATION, """
                insert into inventory_consumption(id, menu_item_id, modifier_option_id, inventory_item_id, qty)
                values ('%s','%s','%s','%s',1)""".formatted(UUID.randomUUID(), item, opt, inv));
        assertThatCode(() -> exec("""
                insert into inventory_consumption(id, modifier_option_id, inventory_item_id, qty)
                values ('%s','%s','%s',1)""".formatted(UUID.randomUUID(), opt, inv))).doesNotThrowAnyException();
    }

    @Test
    void menuPricesCannotBeNegativeAndTrackedStockCannotGoBelowZero() {
        UUID loc = location("MA", false);
        assertRejected(CHECK_VIOLATION, """
                insert into menu_item(id, location_id, name, price_cents, tax_category)
                values ('%s','%s','Bad',-1,'PREPARED_FOOD')""".formatted(UUID.randomUUID(), loc));
        assertRejected(CHECK_VIOLATION, """
                insert into inventory_item(id, location_id, name, tracked, on_hand)
                values ('%s','%s','Ribeye',true,-1)""".formatted(UUID.randomUUID(), loc));
    }

    // ---------- orders, coursing, splits ----------

    @Test
    void enumsAreConstrained() {
        UUID loc = location("MA", false);
        assertRejected(CHECK_VIOLATION, "insert into orders(id, location_id, fulfillment_channel) values ('%s','%s','DRIVE_THRU')"
                .formatted(UUID.randomUUID(), loc));
        UUID order = order(loc);
        assertRejected(CHECK_VIOLATION, "insert into course(id, order_id, course_no, state) values ('%s','%s',1,'BURNT')"
                .formatted(UUID.randomUUID(), order));
    }

    @Test
    void courseTransitionsAreAppendOnly() throws SQLException {
        UUID course = course(order(location("MA", false)));
        exec("insert into course_transition(course_id, from_state, to_state) values ('%s','HOLD','FIRE')".formatted(course));
        exec("insert into course_transition(course_id, from_state, to_state) values ('%s','FIRE','HOLD')".formatted(course));
        assertRejected(CHECK_VIOLATION, "delete from course_transition where course_id = '" + course + "'");
    }

    @Test
    void fractionalSplitsMustSumToExactlyOneWholeLine() throws SQLException {
        UUID loc = location("MA", false);
        UUID order = order(loc);
        UUID line = orderLine(order, course(order), menuItem(loc));
        UUID a = guestCheck(order), b = guestCheck(order), c = guestCheck(order);
        tx(alloc(a, line, 1, 3), alloc(b, line, 1, 3), alloc(c, line, 1, 3));

        UUID line2 = orderLine(order, course(order, 2), menuItem(loc));
        assertRejected(CHECK_VIOLATION, alloc(a, line2, 1, 2), alloc(b, line2, 1, 3));
        assertRejected(CHECK_VIOLATION, alloc(a, line2, 3, 2));
    }

    @Test
    void movingAnAllocationCannotLeaveTheSourceLineUnderAllocated() throws SQLException {
        UUID loc = location("MA", false);
        UUID order = order(loc);
        UUID line1 = orderLine(order, course(order), menuItem(loc));
        UUID line2 = orderLine(order, course(order, 2), menuItem(loc));
        UUID a = guestCheck(order), b = guestCheck(order);
        tx(alloc(a, line1, 1, 2), alloc(b, line1, 1, 2));
        // line2 ends at exactly 1/2 + 1/2 = 1, so only the source line (left at 1/2) can trip the check.
        assertRejected(CHECK_VIOLATION, alloc(a, line2, 1, 2),
                "update check_allocation set order_line_id = '%s' where check_id = '%s' and order_line_id = '%s'"
                        .formatted(line2, b, line1));
    }

    @Test
    void allocationCheckAndLineMustBelongToTheSameOrder() {
        UUID loc = location("MA", false);
        UUID orderA = order(loc);
        UUID orderB = order(loc);
        UUID lineOfB = orderLine(orderB, course(orderB), menuItem(loc));
        assertRejected(CHECK_VIOLATION, alloc(guestCheck(orderA), lineOfB, 1, 1));
    }

    // ---------- FLSA tip pools ----------

    @Test
    void managersSupervisorsAndActiveTwentyPercentOwnersNeverJoinTipPools() throws SQLException {
        UUID loc = location("MA", false);
        UUID pool = tipPool(loc);
        assertRejected(CHECK_VIOLATION, member(pool, employee(loc), true, false, 0, false, false));
        assertRejected(CHECK_VIOLATION, member(pool, employee(loc), false, true, 0, false, false));
        assertRejected(CHECK_VIOLATION, member(pool, employee(loc), false, false, 2000, true, false));
        exec(member(pool, employee(loc), false, false, 1999, true, false));
        exec(member(pool, employee(loc), false, false, 5000, false, false));
        exec(member(pool, employee(loc), false, false, 0, false, false));
    }

    @Test
    void bohBlockedFromPoolsOnlyWhenLocationTakesTipCredit() throws SQLException {
        UUID tipCreditPool = tipPool(location("MA", true));
        UUID noCreditLoc = location("MA", false);
        UUID noCreditPool = tipPool(noCreditLoc);
        assertRejected(CHECK_VIOLATION, member(tipCreditPool, employee(noCreditLoc), false, false, 0, false, true));
        exec(member(noCreditPool, employee(noCreditLoc), false, false, 0, false, true));
    }

    @Test
    void poolEligibilityIsJudgedOnTheEmployeeRecordNotCallerSuppliedSnapshots() {
        UUID loc = location("MA", true);
        UUID pool = tipPool(loc);
        assertRejected(CHECK_VIOLATION, member(pool, employee(loc, true, false, 0, false, false), false, false, 0, false, false));
        assertRejected(CHECK_VIOLATION, member(pool, employee(loc, false, true, 0, false, false), false, false, 0, false, false));
        assertRejected(CHECK_VIOLATION, member(pool, employee(loc, false, false, 2500, true, false), false, false, 0, false, false));
        assertRejected(CHECK_VIOLATION, member(pool, employee(loc, false, false, 0, false, true), false, false, 0, false, false));
    }

    @Test
    void cannotStartTakingTipCreditWhileBohStaffAreInAPool() throws SQLException {
        UUID loc = location("MA", false);
        exec(member(tipPool(loc), employee(loc, false, false, 0, false, true), false, false, 0, false, true));
        assertRejected(CHECK_VIOLATION, "update location set uses_tip_credit = true where id = '" + loc + "'");
    }

    @Test
    void stateCannotRevokeTipCreditWhileALocationUsesIt() {
        location("ME", true);
        assertRejected(CHECK_VIOLATION, """
                update state_compliance_profile set tip_credit_allowed = false, min_cash_wage_cents = min_wage_cents
                where state_code = 'ME'""");
    }

    @Test
    void journalTxnWithoutLinesIsRejectedAtCommit() {
        UUID txn = UUID.randomUUID();
        assertRejected(CHECK_VIOLATION, journalTxnSql(txn, location("MA", false)));
    }

    @Test
    void appendOnlyTablesCannotBeTruncated() {
        assertRejected(CHECK_VIOLATION, "truncate course_transition");
        assertRejected(CHECK_VIOLATION, "truncate journal_line");
    }

    // ---------- slice-1 follow-ups: currency allowlist ----------

    @Test
    void everyCurrencyColumnReferencesTheCurrencyAllowlist() throws SQLException {
        List<String> currencyColumns = column("""
                select table_name || '.' || column_name from information_schema.columns
                where table_schema = 'public' and column_name = 'currency'""");
        List<String> referencing = column("""
                select c.conrelid::regclass::text || '.' || a.attname
                from pg_constraint c join pg_attribute a on a.attrelid = c.conrelid and a.attnum = any (c.conkey)
                where c.contype = 'f' and c.confrelid = 'public.currency'::regclass""");
        assertThat(currencyColumns).isNotEmpty();
        assertThat(referencing).containsExactlyInAnyOrderElementsOf(currencyColumns);
    }

    @Test
    void currenciesOutsideTheAllowlistAreRejected() throws SQLException {
        UUID loc = location("MA", false); // default USD is allowed
        assertRejected(FK_VIOLATION, "update location set currency = 'ZZZ' where id = '" + loc + "'");
        assertRejected(FK_VIOLATION, "update location set currency = 'JPY' where id = '" + loc + "'");
        UUID txn = UUID.randomUUID();
        assertRejected(FK_VIOLATION, journalTxnSql(txn, loc),
                lineSql(txn, "SALES", -100).replace("'USD'", "'ZZZ'"),
                lineSql(txn, "MERCHANT_CASH", 100).replace("'USD'", "'ZZZ'"));
        // the allowlist itself only admits currencies whose minor unit is cents
        assertRejected(CHECK_VIOLATION, "insert into currency(code, minor_digits) values ('JPY', 0)");
    }

    // ---------- slice-1 follow-ups: closed pools are frozen ----------

    @Test
    void closedTipPoolMembershipIsFrozen() throws SQLException {
        UUID loc = location("MA", false);
        UUID pool = tipPool(loc);
        exec(member(pool, employee(loc), false, false, 0, false, false));
        exec("update tip_pool_member set hours_x100 = 800 where pool_id = '" + pool + "'"); // open: editable
        exec("update tip_pool set closed_at = now() where id = '" + pool + "'");

        assertRejected(CHECK_VIOLATION, "update tip_pool_member set hours_x100 = 999 where pool_id = '" + pool + "'");
        assertRejected(CHECK_VIOLATION, "delete from tip_pool_member where pool_id = '" + pool + "'");
        assertRejected(CHECK_VIOLATION, member(pool, employee(loc), false, false, 0, false, false));
        assertRejected(CHECK_VIOLATION, "update tip_pool set closed_at = null where id = '" + pool + "'");
        assertRejected(CHECK_VIOLATION,
                "update tip_pool set period_end = period_end + interval '1 day' where id = '" + pool + "'");
        assertRejected(CHECK_VIOLATION, "truncate tip_pool_member");
    }

    // ---------- slice-1 follow-ups: role changes re-check pool eligibility ----------

    @Test
    void employeeInAnOpenPoolCannotBecomeIneligible() throws SQLException {
        UUID loc = location("MA", true);
        UUID pool = tipPool(loc);
        UUID emp = employee(loc);
        exec(member(pool, emp, false, false, 0, false, false));
        String where = " where id = '" + emp + "'";

        assertRejected(CHECK_VIOLATION, "update employee set is_manager = true" + where);
        assertRejected(CHECK_VIOLATION, "update employee set is_supervisor = true" + where);
        assertRejected(CHECK_VIOLATION, "update employee set owner_equity_bps = 2500, owner_active_mgmt = true" + where);
        assertRejected(CHECK_VIOLATION, "update employee set boh = true" + where); // tip-credit location
        assertRejected(CHECK_VIOLATION, "update employee set location_id = '" + location("MA", true) + "'" + where);

        exec("update employee set display_name = 'Pat R.'" + where); // harmless edits still allowed
        exec("update tip_pool set closed_at = now() where id = '" + pool + "'");
        exec("update employee set is_manager = true" + where); // promotion after the pool closes is fine
    }

    @Test
    void tipCreditCannotStartWhileAPastPeriodPoolIsStillOpenWithBohStaff() throws SQLException {
        UUID loc = location("MA", false);
        UUID pool = UUID.randomUUID();
        exec("""
                insert into tip_pool(id, location_id, period_start, period_end, distribution_basis)
                values ('%s','%s', now() - interval '8 days', now() - interval '1 day', 'HOURS')""".formatted(pool, loc));
        exec(member(pool, employee(loc, false, false, 0, false, true), false, false, 0, false, true));
        String flip = "update location set uses_tip_credit = true where id = '" + loc + "'";

        assertRejected(CHECK_VIOLATION, flip); // period over, but payroll not closed: still open
        exec("update tip_pool set closed_at = now() where id = '" + pool + "'");
        exec(flip); // closed pools no longer hold the location back
    }

    @Test
    void openPoolWithMembersCannotMoveToAnotherLocation() throws SQLException {
        UUID loc = location("MA", false);
        UUID pool = tipPool(loc);
        exec(member(pool, employee(loc), false, false, 0, false, false));
        assertRejected(CHECK_VIOLATION,
                "update tip_pool set location_id = '" + location("MA", false) + "' where id = '" + pool + "'");
    }

    @Test
    void concurrentPromotionCannotLeaveAManagerInAnOpenPool() throws Exception {
        UUID loc = location("MA", false);
        UUID pool = tipPool(loc);
        UUID emp = employee(loc);
        race(member(pool, emp, false, false, 0, false, false),
                "update employee set is_manager = true where id = '" + emp + "'");
        assertThat(column("""
                select m.employee_id from tip_pool_member m join employee e on e.id = m.employee_id
                where e.id = '%s' and e.is_manager""".formatted(emp))).isEmpty();
    }

    @Test
    void concurrentTipCreditSwitchCannotLeaveBohStaffInAnOpenPool() throws Exception {
        UUID loc = location("MA", false);
        UUID pool = tipPool(loc);
        UUID cook = employee(loc, false, false, 0, false, true);
        race(member(pool, cook, false, false, 0, false, true),
                "update location set uses_tip_credit = true where id = '" + loc + "'");
        assertThat(column("""
                select m.employee_id from tip_pool_member m join location l on l.id = '%s'
                where m.pool_id = '%s' and l.uses_tip_credit""".formatted(loc, pool))).isEmpty();
    }

    // ---------- slice-1 follow-ups: managed Postgres ----------

    @Test
    void migrationsRunAsANonSuperuserDatabaseOwner() throws SQLException {
        exec("create role st_app login password 'st_app' nosuperuser nocreatedb nocreaterole");
        exec("create database st_app_db owner st_app");
        String url = "jdbc:postgresql://%s:%d/st_app_db".formatted(PG.getHost(), PG.getMappedPort(5432));
        assertThatCode(() -> Flyway.configure().dataSource(url, "st_app", "st_app")
                .locations("classpath:db/migration").load().migrate())
                .doesNotThrowAnyException();
    }

    // ---------- idempotency ----------

    @Test
    void webhookEventsAndClientMutationsDeduplicate() throws SQLException {
        String evt = "evt_" + shortId();
        String insert = """
                insert into webhook_event(event_id, processor, type, payload_sha256)
                values ('%s','STRIPE','payment_intent.succeeded','%s') on conflict (event_id) do nothing"""
                .formatted(evt, "a".repeat(64));
        assertThat(update(insert)).isEqualTo(1);
        assertThat(update(insert)).isZero();

        UUID mutation = UUID.randomUUID();
        String m = "insert into processed_client_mutation(mutation_id, device_id, result) values ('%s','%s','APPLIED') on conflict do nothing"
                .formatted(mutation, UUID.randomUUID());
        assertThat(update(m)).isEqualTo(1);
        assertThat(update(m)).isZero();
    }

    // ================= fixtures =================

    private static UUID merchant() {
        UUID id = UUID.randomUUID();
        execUnchecked("insert into merchant(id, legal_name) values ('%s','Test Bistro')".formatted(id));
        return id;
    }

    private static String locationSql(UUID id, UUID merchant, String state, boolean tipCredit) {
        return "insert into location(id, merchant_id, name, state_code, timezone, uses_tip_credit) values ('%s','%s','Main','%s','America/New_York',%s)"
                .formatted(id, merchant, state, tipCredit);
    }

    private static UUID location(String state, boolean tipCredit) {
        UUID id = UUID.randomUUID();
        execUnchecked(locationSql(id, merchant(), state, tipCredit));
        return id;
    }

    private static UUID inventory(UUID loc) {
        UUID id = UUID.randomUUID();
        execUnchecked("insert into inventory_item(id, location_id, name, tracked, on_hand) values ('%s','%s','Ribeye',true,10)".formatted(id, loc));
        return id;
    }

    private static UUID menuItem(UUID loc) {
        UUID id = UUID.randomUUID();
        execUnchecked("insert into menu_item(id, location_id, name, price_cents, tax_category) values ('%s','%s','Steak',3200,'PREPARED_FOOD')"
                .formatted(id, loc));
        return id;
    }

    private static UUID order(UUID loc) {
        UUID id = UUID.randomUUID();
        execUnchecked("insert into orders(id, location_id, fulfillment_channel) values ('%s','%s','DINE_IN')".formatted(id, loc));
        return id;
    }

    private static UUID course(UUID order) { return course(order, 1); }

    private static UUID course(UUID order, int no) {
        UUID id = UUID.randomUUID();
        execUnchecked("insert into course(id, order_id, course_no) values ('%s','%s',%d)".formatted(id, order, no));
        return id;
    }

    private static UUID orderLine(UUID order, UUID course, UUID item) {
        UUID id = UUID.randomUUID();
        execUnchecked("""
                insert into order_line(id, order_id, course_id, menu_item_id, qty, unit_price_cents, tax_category, temp, name_snapshot)
                values ('%s','%s','%s','%s',1,3200,'PREPARED_FOOD','HOT','Steak')""".formatted(id, order, course, item));
        return id;
    }

    private static UUID guestCheck(UUID order) {
        UUID id = UUID.randomUUID();
        execUnchecked("insert into guest_check(id, order_id, label) values ('%s','%s','Check')".formatted(id, order));
        return id;
    }

    private static String alloc(UUID check, UUID line, int num, int den) {
        return "insert into check_allocation(check_id, order_line_id, numerator, denominator) values ('%s','%s',%d,%d)"
                .formatted(check, line, num, den);
    }

    private static String journalTxnSql(UUID txn, UUID loc) {
        return "insert into journal_txn(id, location_id, kind, source_ref) values ('%s','%s','SALE','test')".formatted(txn, loc);
    }

    private static String lineSql(UUID txn, String account, long cents) {
        return "insert into journal_line(txn_id, account, amount_cents, currency) values ('%s','%s',%d,'USD')"
                .formatted(txn, account, cents);
    }

    private static UUID employee(UUID loc) {
        return employee(loc, false, false, 0, false, false);
    }

    private static UUID employee(UUID loc, boolean manager, boolean supervisor, int ownerBps, boolean ownerActive, boolean boh) {
        UUID id = UUID.randomUUID();
        execUnchecked("""
                insert into employee(id, location_id, display_name, role, is_manager, is_supervisor, owner_equity_bps, owner_active_mgmt, boh)
                values ('%s','%s','Pat','SERVER',%s,%s,%d,%s,%s)""".formatted(id, loc, manager, supervisor, ownerBps, ownerActive, boh));
        return id;
    }

    private static UUID tipPool(UUID loc) {
        UUID id = UUID.randomUUID();
        execUnchecked("""
                insert into tip_pool(id, location_id, period_start, period_end, distribution_basis)
                values ('%s','%s', now(), now() + interval '1 day', 'HOURS')""".formatted(id, loc));
        return id;
    }

    private static String member(UUID pool, UUID emp, boolean manager, boolean supervisor, int ownerBps,
                                 boolean ownerActive, boolean boh) {
        return """
                insert into tip_pool_member(pool_id, employee_id, role_snapshot, is_manager_snapshot, is_supervisor_snapshot,
                  owner_equity_bps_snapshot, owner_active_mgmt_snapshot, boh_snapshot)
                values ('%s','%s','STAFF',%s,%s,%d,%s,%s)""".formatted(pool, emp, manager, supervisor, ownerBps, ownerActive, boh);
    }

    private static String shortId() { return UUID.randomUUID().toString().substring(0, 8); }

    // ================= JDBC helpers =================

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) { s.execute(sql); }
    }

    private static void execUnchecked(String sql) {
        try { exec(sql); } catch (SQLException e) { throw new IllegalStateException(sql, e); }
    }

    private static int update(String sql) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) { return s.executeUpdate(sql); }
    }

    /** Runs all statements in one transaction; deferred constraint triggers fire at commit. */
    private static void tx(String... sqls) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                for (String sql : sqls) s.execute(sql);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /**
     * T1 runs {@code first} and keeps its transaction open while T2 runs {@code second}; then T1 commits.
     * Either side may be rejected; callers assert the invariant on the final state.
     */
    private static void race(String first, String second) throws Exception {
        try (Connection t1 = connect()) {
            t1.setAutoCommit(false);
            try (Statement s = t1.createStatement()) {
                s.execute(first);
            }
            CompletableFuture<Void> t2 = CompletableFuture.runAsync(() -> {
                try {
                    tx(second);
                } catch (SQLException rejected) {
                    // a rejection is an acceptable outcome; the final state is what is asserted
                }
            });
            Thread.sleep(500);
            try {
                t1.commit();
            } catch (SQLException rejected) {
                t1.rollback();
            }
            t2.get(15, TimeUnit.SECONDS);
        }
    }

    private static void assertRejected(String sqlState, String... sqls) {
        assertThatThrownBy(() -> tx(sqls))
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(sqlState);
    }

    private static List<String> column(String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }
}
