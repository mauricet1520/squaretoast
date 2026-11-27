package com.squaretoast.menu;

import com.squaretoast.device.DevicePrincipal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/**
 * Slice 14 (S14-3): the location's tax setup for its own devices, so an offline POS can total a check exactly as
 * {@code CheckBalance} will. Rules are those in force at some point in the next seven local days; guests never see this.
 */
@RestController
class TaxProfileController {

    static final int WINDOW_DAYS = 7;
    /** Plan 19.6: offline orders are taxed by their own day, and sync accepts sale times up to 72 h old. */
    static final int LOOKBACK_DAYS = 4; // 72 h can span 4 local dates across a DST change

    record RuleView(String jurisdictionCode, String taxCategory, @Nullable String channel, int ratePpm,
                    String effectiveFrom, @Nullable String effectiveTo) {}

    record ItemTax(UUID menuItemId, String taxCategory, String temp) {}

    record TaxProfile(UUID locationId, String stateCode, String currency, String timezone, boolean ca8080Applies,
                      boolean caSeparateAccountingElected, boolean mandatoryServiceChargeTaxable, String validFrom,
                      String validUntil, List<RuleView> rules, List<ItemTax> items) {}

    private record Header(String state, String currency, String timezone, boolean ca8080, boolean caSeparate,
                          boolean mandatoryTaxable) {}

    private final JdbcClient db;

    TaxProfileController(JdbcClient db) {
        this.db = db;
    }

    @GetMapping(path = "/locations/{locationId}/tax-profile", version = "1")
    ResponseEntity<TaxProfile> profile(@RequestAttribute(DevicePrincipal.ATTRIBUTE) DevicePrincipal device,
                                       @PathVariable UUID locationId) {
        if (!device.locationId().equals(locationId)) {
            return ResponseEntity.notFound().build(); // as if another location did not exist
        }
        Optional<Header> found = db.sql("""
                select l.state_code, l.currency, l.timezone, l.ca_80_80_applies, l.ca_separate_accounting_elected,
                       p.mandatory_service_charge_taxable
                from location l join state_compliance_profile p on p.state_code = l.state_code
                where l.id = ?""")
                .params(locationId)
                .query((rs, n) -> new Header(rs.getString("state_code"), rs.getString("currency"), rs.getString("timezone"),
                        rs.getBoolean("ca_80_80_applies"), rs.getBoolean("ca_separate_accounting_elected"),
                        rs.getBoolean("mandatory_service_charge_taxable")))
                .optional();
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Header h = found.get();
        LocalDate from = LocalDate.now(ZoneId.of(h.timezone()));
        LocalDate until = from.plusDays(WINDOW_DAYS);
        List<RuleView> rules = db.sql("""
                select jurisdiction_code, tax_category, fulfillment_channel, rate_ppm, effective_from, effective_to
                from tax_rule
                where state_code = ? and effective_from < ? and (effective_to is null or effective_to > ?)
                order by jurisdiction_code, tax_category, fulfillment_channel nulls first, effective_from""")
                .params(h.state(), until, from.minusDays(LOOKBACK_DAYS))
                .query((rs, n) -> {
                    java.sql.Date to = rs.getDate("effective_to");
                    return new RuleView(rs.getString("jurisdiction_code"), rs.getString("tax_category"),
                            rs.getString("fulfillment_channel"), rs.getInt("rate_ppm"),
                            rs.getDate("effective_from").toLocalDate().toString(), to == null ? null : to.toLocalDate().toString());
                })
                .list();
        List<ItemTax> items = db.sql("select id, tax_category, temp from menu_item where location_id = ? order by id")
                .params(locationId)
                .query((rs, n) -> new ItemTax(rs.getObject("id", UUID.class), rs.getString("tax_category"), rs.getString("temp")))
                .list();
        return ResponseEntity.ok(new TaxProfile(locationId, h.state(), h.currency().trim(), h.timezone(), h.ca8080(),
                h.caSeparate(), h.mandatoryTaxable(), from.toString(), until.toString(), rules, items));
    }
}
