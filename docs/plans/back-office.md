# Back Office (Web) — Plan (pending approval)

Status: **DRAFT, needs approval.** Plan only. No production code until the owner approves.
Goal: a web back office that beats Toast Web and Square Dashboard for full-service and
multi-location groups, at $0/month. All rules in `CLAUDE.md` apply (long cents + ISO-4217,
FLSA tip pools, card-fee-on-tips blocks, JSpecify, no `double`).

---

## 1. What exists now

| Area | Today | Where |
|---|---|---|
| Tenancy | `merchant` -> `location` (state, timezone, currency, `uses_tip_credit`, CA 80/80 flags). No group/region level. | `V1__baseline.sql` |
| Menu | `menu_item`, `modifier_group`, `modifier_option`, `menu_item_modifier_group`, `inventory_item` / `inventory_consumption`. **Every row is owned by one `location_id`.** No categories, dayparts, price levels, publish/versions. Read-only API. | `menu/MenuController` `GET /locations/{id}/menu`, `GET .../inventory`, `PUT .../eighty-six` |
| Tax | `tax_rule` by state/category/channel, `rate_ppm`, effective dates; profile keeps recent rules for offline orders; `orders.sold_on`. Read-only API. | `menu/TaxProfileController`, `V7__orders_sold_on.sql` |
| Tips & labor | `employee` (one location, `role` text, manager/supervisor/owner/BOH flags), `tip_pool`, `tip_pool_member` with DB checks that forbid managers, supervisors and 20%+ active owners; pools freeze on close. Domain: `TipPoolPolicy`, `TipDistributor`, `TipFeeCalculator`, `OvertimeCalculator`, `TippedWage`. **No API, no time clock, no PINs.** | `V1`–`V3`, `backend/domain/.../labor` |
| Devices & auth | Device enrollment + bearer token (SHA-256 at rest), deny-by-default `DeviceAuthFilter`; KDS one-time tickets; QR tokens. **No human user login, no roles.** | `device/*` |
| Orders/payments | Commit, coursing, split checks, Stripe Connect payments, webhook, ledger (`journal_txn/line`, `CARD_SETTLEMENT`). | `order/*`, `payment/*`, `ledger/*` |
| Web | Preact KDS and guest QR pages only. **No admin pages.** | `web-kds-qr/src/{kds,guest}` |
| Audit | `course_transition`, `realtime_event`, `processed_client_mutation` are partial trails. No actor-level audit log. | — |

---

## 2. Gaps vs Toast and Square (ranked by impact for groups)

| # | Gap | Toast / Square today | Impact |
|---|---|---|---|
| G1 | **Human auth + roles + location scoping** (owner, group admin, GM, staff). Nothing in the back office can ship without it. | Toast jobs + access permissions, per-location jobs [T7][T8]; Square permission sets, location-limited access [S1][S2] | Blocker |
| G2 | **Group-level catalog with publish to many locations** (one item, many locations; targets; versioned publish). Today each location copies its own rows. | Toast menu entity "target" + "owner", Publish Config to many locations [T1][T2][T3]; Square item library with price overrides by location [S5] | Very high |
| G3 | **Menu editing API**: categories/menus, modifier CRUD, per-location price overrides, dayparts (menu availability), channel visibility. | Toast availability vs visibility, price levels, menu-specific pricing [T4][T5][T6]; Square price overrides, bulk CSV [S5] | Very high |
| G4 | **Staff, time clock, tip pools as a product** (PIN clock-in, shifts, jobs with wage per location, pool rules UI, preview). Domain rules exist, no API. | Toast Tips Manager (pooling vs sharing, intervals, preview) [T9][T10]; Square personal passcodes + time tracking [S1][S3] | High |
| G5 | **Reports + exports** (sales summary, net sales, PMIX, labor, tips, payouts & fees, voids/comps/discounts) with multi-location rollup and CSV. | Toast PMIX, labor summary, location overview, CSV/Excel/PDF export, nightly export [T11][T12][T13]; Square net sales = sales − returns − discounts/comps [S4] | High |
| G6 | **Discounts and promos** (open/fixed/%, item/check scope, schedule = happy hour, location scope, manager approval). No table exists. | Square automatic discount rules by time/item/location [S6]; Toast permission 3.1 manager discounts [T8] | Medium-high |
| G7 | **Actor-level audit log** (who did what, who approved, before/after) for menu, price, discount, void, tip, time-entry edits. | Toast says it **cannot** show which access code approved an action [T14]; time-entry audit only [T15] | Medium (and a differentiator) |
| G8 | Restaurant groups/regions (rollups by brand/region, group-scoped admins). | Toast restaurant groups (targets, archived not deleted) [T3] | Medium |

Differentiators to keep: $0 software (Square gates permission sets and restaurant reports behind Plus [S1][S4]); full audit with approver (G7); FLSA checks enforced in the database, not just UI.

---

## 3. Data model changes (Flyway `V8`..`V13`, one per slice group)

All new tables carry `merchant_id` (tenant key) and money as `bigint *_cents` + `char(3) currency`.

- **Groups** `location_group(id, merchant_id, name, archived_at)`, `location_group_member(group_id, location_id)`. Groups archive, never delete (as Toast).
- **Users & roles**
  - `app_user(id, merchant_id, email citext unique per merchant, display_name, password_hash, mfa_secret_enc, disabled_at)`; external IdP later.
  - `role(id, merchant_id, name, system_key)` with seeded `OWNER`, `GROUP_ADMIN`, `GM`, `SHIFT_LEAD`, `STAFF`; `role_permission(role_id, permission)` where `permission` is a closed enum (`MENU_EDIT`, `MENU_PUBLISH`, `PRICE_EDIT`, `DISCOUNT_APPLY_MANAGER`, `VOID`, `REPORTS_SALES`, `REPORTS_LABOR`, `TIPS_MANAGE`, `TIME_EDIT`, `STAFF_MANAGE`, `AUDIT_READ`, ...).
  - `role_grant(user_id, role_id, scope_kind ('MERCHANT','GROUP','LOCATION'), scope_id)`. Effective permission = union of grants whose scope covers the location.
  - `employee` gains `merchant_id`, `app_user_id null`, `pos_pin_hash`; new `employee_job(employee_id, location_id, job_code, wage_cents, tipped bool, is_manager, is_supervisor)` so one person works many locations. FLSA flags move to job level; the pool snapshot keeps existing checks.
- **Catalog (group level)**
  - `catalog_item(id, merchant_id, name, tax_category, temp, default_course, base_price_cents, currency, version)`; `menu_item` becomes the **published per-location projection** (keeps POS/KDS read path unchanged).
  - `catalog_target(entity_kind, entity_id, scope_kind, scope_id)` (Toast "target"); `catalog_price_override(item_id, location_id | price_level_id, price_cents)`; `price_level(id, merchant_id, name)`.
  - `menu(id, merchant_id, name)`, `menu_section(menu_id, name, sort)`, `menu_section_item`; `menu_availability(menu_id, dow_mask, start_local time, end_local time)` evaluated in `location.timezone`; `menu_visibility(menu_id, channel in POS/QR/ONLINE)`.
  - Modifier groups/options get the same `merchant_id` + target + override pattern.
  - `menu_publish(id, merchant_id, requested_by, created_at, status)`, `menu_publish_location(publish_id, location_id, snapshot_hash, applied_at, error)`.
- **Time clock** `time_entry(id, employee_id, location_id, job_code, clock_in timestamptz, clock_out, break_minutes, source ('POS','WEB_EDIT'), edited_by)`; overtime by workweek via `OvertimeCalculator`.
- **Tip rules** `tip_policy(id, location_id, interval ('DAY','SERVICE_PERIOD'), basis ('HOURS','POINTS'), job_points jsonb)`; `tip_policy_contributor(policy_id, job_code, pct_ppm)` lets managers **contribute** but never receive [L1][L2].
- **Discounts** `discount(id, merchant_id, name, kind ('PCT','FIXED','OPEN'), pct_ppm, amount_cents, scope ('CHECK','ITEM'), requires_permission, schedule jsonb, active)`, targets via `catalog_target`; `order_discount(order_id, line_id null, discount_id, amount_cents, applied_by, approved_by)`.
- **Void/comp** `order_line_void(line_id, reason_code, voided_by, approved_by, at)`.
- **Audit** `audit_event(id, merchant_id, location_id null, actor_user_id | actor_employee_id | actor_device_id, approver_id null, action, entity_kind, entity_id, before jsonb, after jsonb, at, request_id)`, append-only (revoke UPDATE/DELETE from app role; trigger rejects both).
- **Reporting** read models refreshed at order close: `report_daily_location(location_id, business_date, gross_cents, discounts_cents, comps_cents, voids_cents, net_cents, tax_cents, tips_cents, svc_charge_cents, card_fees_cents, app_fee_cents)`, `report_item_daily(...)` for PMIX, `report_labor_daily(...)`. `business_date` = existing `orders.sold_on`.

## 4. API changes (`/api/v1/admin/**`, user-session auth only)

| Method + path | Permission |
|---|---|
| `POST /admin/session`, `DELETE /admin/session`, `POST /admin/session/mfa` | public / self |
| `GET/POST/PATCH /admin/users`, `/admin/roles`, `/admin/role-grants` | `STAFF_MANAGE` at merchant or group scope |
| `GET/POST/PATCH /admin/location-groups` | `OWNER`, `GROUP_ADMIN` |
| `GET/POST/PATCH /admin/catalog/items`, `/modifier-groups`, `/menus`, `/price-levels`, `/items/{id}/price-overrides` (`If-Match: version`) | `MENU_EDIT`, `PRICE_EDIT` |
| `POST /admin/publishes` `{scope}` -> 202; `GET /admin/publishes/{id}` per-location status | `MENU_PUBLISH` on every target location |
| `GET/POST/PATCH /admin/employees`, `/employees/{id}/jobs`, `/time-entries` | `STAFF_MANAGE`, `TIME_EDIT` |
| `POST /time-clock/punch` (device route, PIN in body) | device token + PIN |
| `GET/PUT /admin/locations/{id}/tip-policy`; `POST .../tip-pools/{id}/preview`; `POST .../close` | `TIPS_MANAGE` |
| `GET/POST/PATCH /admin/discounts` | `MENU_EDIT` |
| `GET /admin/reports/{sales,pmix,labor,tips,payouts,voids-discounts}?locations=..&from=..&to=..` (`Accept: text/csv` for export) | `REPORTS_SALES` / `REPORTS_LABOR` on every requested location |
| `GET /admin/audit?entity=..&actor=..` | `AUDIT_READ` |

Web: new `web-kds-qr/src/admin/` Preact app (catalog, publish, staff, tips, reports, audit). Same bundle, separate route, no LAN mode.

---

## 5. TDD slices (each: RED tests committed first, then minimal code)

- **B1 — Admin users and sessions.** `AdminSessionIT.loginSetsHttpOnlySecureSameSiteStrictCookie`, `.wrongPasswordIsGeneric401AndRateLimited`, `.disabledUserCannotLogin`, `PasswordHashTest.argon2idParamsMeetFloor`, `AdminAuthFilterTest.adminPathsRejectDeviceTokens`, `.devicePathsRejectAdminCookies`.
- **B2 — Roles, grants, scope.** `PermissionResolverTest.locationGrantDoesNotLeakToSiblingLocation`, `.groupGrantCoversMemberLocationsOnly`, `.merchantOwnerCoversAll`, `TenantIsolationIT.userOfMerchantACannotReadMerchantBById` (every admin GET/PATCH, table-driven), `RoleGrantIT.cannotGrantPermissionYouDoNotHold`.
- **B3 — Audit log.** `AuditEventIT.everyAdminMutationWritesOneEvent`, `.updateAndDeleteOnAuditEventAreRejected`, `AuditRedactionTest.neverStoresPinPasswordOrPan`, `AuditIT.approverRecordedSeparatelyFromActor`.
- **B4 — Location groups.** `LocationGroupIT.archiveHidesFromTargetsButKeepsHistory`, `.locationInOtherMerchantRejected`.
- **B5 — Group catalog items + per-location price override.** `CatalogPriceTest.overrideBeatsPriceLevelBeatsBase`, `.priceIsLongCentsWithCurrencyMatchingLocation`, `CatalogItemIT.staleIfMatchReturns412`, `.targetOutsideUserScopeIs403`.
- **B6 — Modifier groups/options at group level.** `ModifierCatalogIT.minMaxSelectValidated`, `.negativeDeltaAllowedButFinalLineNeverBelowZero`, `.overrideByLocation`.
- **B7 — Publish to many locations.** `MenuPublishIT.publishProjectsIntoMenuItemPerTargetLocation`, `.unchangedLocationGetsNoNewVersion`, `.onePartialFailureDoesNotRollBackOthers`, `.publishEmitsMenuChangedSseUnder100ms`, `.posSnapshotStaysReadableDuringPublish`, `.needsMenuPublishOnEveryTarget`.
- **B8 — Menus, dayparts, channel visibility.** `MenuAvailabilityTest.evaluatesInLocationTimezoneAcrossDst`, `.overnightWindowCrossesMidnight`, `.overlappingMenusRejectedForSameChannel`, `GuestMenuIT.qrHidesPosOnlyMenus`.
- **B9 — Employees with jobs across locations + POS PIN.** `EmployeeJobIT.onePersonTwoLocationsTwoWages`, `PinTest.pinHashedAndUniquePerLocation`, `.pinAttemptsLockAfterFive`.
- **B10 — Time clock + edits.** `TimeClockIT.punchInTwiceIsIdempotentByClientId`, `.editRequiresTimeEditAndIsAudited`, `OvertimeIT.workweekOvertimeOnFullMinimumWageNotCashWage`.
- **B11 — Tip policy and pool preview/close.** `TipPolicyIT.managerJobCanContributeButNeverReceives`, `.supervisorAndTwentyPctActiveOwnerExcluded`, `.bohExcludedWhenLocationUsesTipCredit`, `.previewMatchesCloseToTheCent`, `.closedPoolIsFrozen`, `TipFeeIT.cardFeeDeductionBlockedInCaMeMaDe`, `.feeDeductedOnlyOnTipPortionNeverRoundedUp`.
- **B12 — Discounts and comps.** `DiscountTest.pctUsesPpmAndRoundsHalfUpToCent`, `.discountNeverExceedsLineOrCheck`, `.scheduledDiscountUsesLocationTimezone`, `DiscountIT.managerDiscountNeedsApproverWithPermission`, `TaxIT.discountReducesTaxableBaseTipsStayNonTaxable`.
- **B13 — Voids with reason + approver.** `VoidIT.voidAfterKitchenSendNeedsApprover`, `.voidRestoresInventoryAtomically`, `.voidWritesAuditWithReason`.
- **B14 — Sales summary + rollup.** `SalesReportIT.netEqualsGrossMinusDiscountsCompsVoids`, `.businessDateIsSoldOnNotUtcDate`, `.rollupAcrossLocationsSameCurrencyOnly`, `.locationOutsideScopeIs403NotFiltered`.
- **B15 — PMIX + voids/discounts report.** `PmixReportIT.modifiersCountedSeparately`, `.voidQuantityShownNotInNet`.
- **B16 — Labor + tips report.** `LaborReportIT.hoursOvertimeAndLaborPctOfNet`, `TipsReportIT.tipsByEmployeeMatchClosedPools`.
- **B17 — Payouts and fees.** `PayoutReportIT.feesFromLedgerMatchStripeBalanceTxn`, `.applicationFeeShownSeparatelyFromProcessorFee`.
- **B18 — CSV export.** `CsvExportIT.centsRenderedAsDecimalStringNotFloat`, `.formulaInjectionCellsPrefixed`, `.streamsLargeRangeWithoutLoadingAll`.
- **B19 — Admin web shell.** Vitest: `AdminRoutes.test.hidesNavForMissingPermission`, `CatalogEditor.test.showsConflictOn412`, `PublishStatus.test.pollsPerLocationStatus`.

Order: B1–B3 first (no admin route ships without auth, scope, audit). Then catalog B4–B8, labor B9–B11, money edits B12–B13, reports B14–B18, UI B19 in parallel from B5.

---

## 6. Security notes

- **Two principal types, never mixed.** Device bearer tokens stay on device routes; admin routes take only a session cookie (`HttpOnly; Secure; SameSite=Strict`, short idle timeout, rotation on login). `AdminAuthFilter` is deny-by-default like `DeviceAuthFilter`.
- **CSRF**: SameSite=Strict plus a double-submit token on all non-GET admin calls.
- **Passwords**: Argon2id; generic error; per-IP and per-account rate limits; MFA required for `OWNER` and anyone with `PRICE_EDIT`, `TIPS_MANAGE` or `STAFF_MANAGE`.
- **PINs** are hashed (Argon2id, per-location salt), lock after 5 bad tries, are never logged, and are only valid on an enrolled device at the same location.
- **Tenant isolation**: every query filters on `merchant_id` taken from the session, never from the request. Resolve the target location first, then check the permission for that location's scope. Unknown or foreign ids return 404, not 403, so ids do not leak. Add Postgres row-level security on `merchant_id` as a second wall (open question Q4).
- **Least privilege**: a user cannot grant a permission or scope they do not hold. Owner removal needs a second owner.
- **FLSA in the database**: keep the `tip_pool_member` checks; add a check that a contributor-only manager row can never get a payout share. The UI never offers managers, supervisors or 20%+ active owners as recipients [L1][L2].
- **Card fee on tips**: `TipFeeCalculator` stays the only code path; CA/ME/MA/DE hard-blocked server-side, whatever the UI sends.
- **PCI**: the back office never shows PAN. Reports show only brand + last4 from Stripe.
- **Audit**: append-only, redacts secrets, records actor + approver + request id; reading it needs `AUDIT_READ`. Retain at least 3 years (FLSA payroll records).
- **CSV exports**: neutralize `= + - @` cells; exports are audited.
- `/security-review` on B1, B2, B3, B9, B11, B12, B13, B17.

---

## 7. Open questions for the owner

1. **Identity**: own email+password+TOTP first, or SSO (Google/Microsoft/Okta) from day one for groups?
2. **Catalog migration**: move existing per-location `menu_item` rows into `catalog_item` automatically (dedupe by name+price), or start groups fresh?
3. **Publish model**: explicit "Publish" button (Toast style [T2]) or live save with optional scheduled publish?
4. **Row-level security** in Postgres now (more safety, harder tests) or app-level filtering only for v1?
5. **Tip interval**: support "per service period" and "per order" like Toast [T10], or only per business day for v1?
6. **Managers contributing to pools**: allow it (legal under FLSA [L1]) or keep the stricter "managers never in pools" rule?
7. **Multi-currency groups** (e.g. US + Canada): roll up per currency only, or add FX later?
8. **Payroll export** target: generic CSV only, or adapters (Gusto, ADP) later?
9. **Retention** for audit and time entries: 3 years minimum OK, or longer?

---

## Sources

- [T1] https://doc.toasttab.com/doc/platformguide/platformMenuManagerMenuAndMultiLocationRestaurants.html
- [T2] https://doc.toasttab.com/doc/platformguide/platformPublishingMenuManagerChanges.html
- [T3] https://doc.toasttab.com/doc/platformguide/creatingEditingAndHidingRestaurantGroups.html
- [T4] https://support.toasttab.com/en/article/Settings-Menu-Availability
- [T5] https://doc.toasttab.com/doc/platformguide/adminUsingPriceLevels.html
- [T6] https://support.toasttab.com/article/Building-Happy-Hour-Menus-w-Menu-Specific-Pricing-1493004445781
- [T7] https://doc.toasttab.com/doc/platformguide/adminUnderstandingTheJobsAndPermissionsTabInEnterpriseEnvironments.html
- [T8] https://support.toasttab.com/article/Access-Permissions-Reference
- [T9] https://support.toasttab.com/en/article/Pooling-Tips-and-Tipping-Out
- [T10] https://support.toasttab.com/en/article/Common-Tip-Policies
- [T11] https://support.toasttab.com/en/article/Product-Mix-PMIX-Report-Overview
- [T12] https://support.toasttab.com/en/article/Labor-Reports-Overview
- [T13] https://support.toasttab.com/en/article/Locations-Reports-Overview
- [T14] https://support.toasttab.com/en/article/Can-I-see-which-POS-access-code-was-used-to-allow-an-employee-to-clock-in-early
- [T15] https://support.toasttab.com/article/Run-A-Time-Entries-Audit-Report
- [S1] https://square.com/help/us/en/article/6430-setting-up-your-employees-with-square-for-restaurants
- [S2] https://square.com/help/us/en/article/5822
- [S3] https://square.com/help/us/en/article/5591
- [S4] https://squareup.com/help/us/en/article/8579-review-daily-sales-for-your-restaurant
- [S5] https://squareup.com/help/us/en/article/8242-create-item-price-overrides-for-multiple-locations-in-square-dashboard
- [S6] https://squareup.com/help/gb/article/6606-happy-hour-and-discounts-with-square-for-restaurants
- [L1] https://www.dol.gov/agencies/whd/fact-sheets/15b-managers-supervisors-tips-flsa
- [L2] https://www.dol.gov/agencies/whd/fact-sheets/15-tipped-employees-flsa
