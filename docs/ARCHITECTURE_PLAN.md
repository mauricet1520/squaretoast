# SquareToast — Architecture Plan (Phase 1, pending approval)

Status: **DRAFT, needs approval.** No production code exists yet. Greenfield repo.
Local toolchain found: JDK 21 on PATH (Java 25 will be provisioned by the Gradle toolchain via the foojay resolver), Docker 25 (Testcontainers OK), Node 20, Android SDK platforms 34–36.

---

## 0. Decisions to approve (TL;DR)

| # | Decision | Why |
|---|---|---|
| D1 | Split the backend into `:backend` (Boot app), `:backend:domain` (pure Java, ports, no Spring) and `:backend:payment-adapters`. `:backend:check` depends on both subprojects' `check`. | Avoids a cycle: the port lives in domain, the adapter implements it, the app wires both. `:backend:check` stays the single command from CLAUDE.md. |
| D2 | **API versioning by path segment** (`/api/v1/...`) using SF7 `ApiVersionConfigurer`. Header versioning is not used. | Browser `EventSource` (SSE) cannot send custom headers. |
| D3 | Stripe is called over **declarative `@HttpExchange` + `RestClient`**, with no `stripe-java`. Webhook HMAC is verified by hand. | No vendor SDK in the backend means Jackson 2 is probably **not needed at all**. The ArchUnit/Gradle guard still ships and fences any future SDK. |
| D4 | The Jackson-2 fence bans `com.fasterxml.jackson..` **except `com.fasterxml.jackson.annotation..`**. | Jackson 3 deliberately keeps `jackson-annotations` in the old 2.x package. A naive rule would fail on Boot itself. |
| D5 | Tax rates are stored as **ppm** (`rate_ppm`, 8.875% = 88 750). Money is `bigint` cents with `char(3)` currency. | bps can't express 8.875%. No floating point anywhere. |
| D6 | The LAN hub serves the KDS bundle **itself** over `http://<lan-ip>:8787`. | An HTTPS-origin PWA can't call `http://192.168.x.x` (mixed content + Private Network Access). In LAN mode there is no service worker, because there is no secure context. The cloud origin is the installable PWA. |
| D7 | The hub runs as a ForegroundService of type **`connectedDevice`**, not `dataSync`. | Android 15 caps `dataSync` at 6 h/day, and a hub must last a full shift. `CHANGE_WIFI_STATE` satisfies the type's runtime prerequisite. |
| D8 | Offline QR Pay carries a **device-signed (Ed25519) check claim**. | The cloud may never have seen an offline check. The signed claim lets the backend create a provisional check and charge it, then reconcile when the outbox syncs. |
| D9 | The web app uses **Vite + TypeScript + Preact** (~4 KB runtime). | Zero-install, small enough to embed in APK assets for LAN mode. |

---

## 1. Gradle multi-module layout

- Root: `settings.gradle.kts` with `gradle/libs.versions.toml` and the foojay toolchain resolver.
- Backend toolchain is Java 25. Android uses JVM target 17 and compileSdk 36.
- Plugins:
  - `org.springframework.boot` 4.0.x with `io.spring.dependency-management`
  - `net.ltgt.errorprone`
  - `com.android.application`
  - `org.jetbrains.kotlin.android` + `plugin.compose` + `plugin.serialization` (2.2.x)
  - `com.google.devtools.ksp`
  - `com.google.dagger.hilt.android`
  - `com.github.node-gradle.node`

**Backend starters (SB4 modular):**
- `spring-boot-starter-webmvc` (Tomcat is the default; there is **no** Undertow starter in SB4)
- `-data-jpa`, `-flyway` (+ `flyway-database-postgresql`), `-validation`, `-actuator`, `-restclient`
- Tests: `-webmvc-test`, `-data-jpa-test`, `spring-boot-testcontainers`, Testcontainers PostgreSQL, ArchUnit-JUnit5
- `spring.threads.virtual.enabled=true`, so SSE connections don't pin platform threads.

**Null safety:**
- Every package has a `package-info.java` with `@NullMarked`.
- Error Prone + NullAway flags: `-XepOpt:NullAway:OnlyNullMarked=true`, `-XepOpt:NullAway:JSpecifyMode=true`, severity ERROR.
- Error Prone `ForbiddenImports`/ArchUnit rule: `org.springframework.lang..` is forbidden.

**Jackson-2 fence (two layers):**
1. Gradle: `subprojects { if (path != ":backend:payment-adapters") configurations.configureEach { exclude("com.fasterxml.jackson.core","jackson-databind"); exclude("com.fasterxml.jackson.core","jackson-core"); exclude("org.springframework.boot","spring-boot-jackson2") } }`. Plus a `verifyNoJackson2` task over the resolved runtime classpaths, wired into `check`.
2. ArchUnit (`:backend` test):
   - no class outside `..payment.adapter..` depends on `com.fasterxml.jackson..`, excluding `com.fasterxml.jackson.annotation..`
   - adapter types never leak into `domain`
   - `domain` depends on no `org.springframework..`
   - no `double`/`float` field or method parameter whose name or type matches `*Cents|*Amount|Money`

**Web module:** `:web-kds-qr` runs `npm ci && vite build`. `:android-pos:preBuild` copies `web-kds-qr/dist` → `android-pos/src/main/assets/kds/`.

---

## 2. Backend structure (hexagonal per bounded context)

Bounded contexts: `location`, `menu` (+inventory/86), `order` (seats, courses, splits), `tax`, `labor` (tip pools/FLSA), `payment`, `ledger`, `sync`, `realtime`.

- `:backend:domain` holds pure records, policies and engines (`TaxEngine`, `TipPoolPolicy`, `TipFeeCalculator`, `SplitAllocator`, `CourseStateMachine`, `ApplicationFeeCalculator`) and ports (`PaymentGatewayPort`, `InventoryRepository`, `EventPublisher`…). This code is unit-tested without mocks.
- `:backend` holds REST/SSE controllers, JPA adapters, transactions and webhook endpoints.
- `:backend:payment-adapters` holds the `StripeConnectAdapter` (`@HttpExchange` interface `StripeApi`), the HMAC webhook verifier and failover routing.

**PaymentGatewayPort (domain):**
```
createCardPresentIntent(ChargeRequest) / createCardNotPresentIntent(ChargeRequest) -> GatewayIntent
capture(intentRef), cancel(intentRef), refund(RefundRequest), createConnectionToken(locationRef)
ChargeRequest{ idempotencyKey, Money amount, Money tip, Money applicationFee, connectedAccountRef, checkId }
```

**Stripe mapping (destination charge):** `POST /v1/payment_intents`, form-encoded:
- `amount`, `currency`, `application_fee_amount`
- `transfer_data[destination]=acct_x`, `on_behalf_of=acct_x` (the merchant is the settlement merchant and descriptor)
- `payment_method_types[]=card_present` (Terminal) or automatic methods (QR)
- `Idempotency-Key` header, which is the check-payment UUID

On Android, Tap to Pay connects with `onBehalfOf=acct_x` in its connection config.

**Fee spread:**
- `application_fee = floor(amount × spread_bps / 10 000) + fixed_cents`. Floor always favours the merchant.
- The spread is on **amount incl. tip**.
- Margin is `application_fee − stripe_fee`. `stripe_fee` comes from `balance_transaction` in the `payment_intent.succeeded`/`charge.updated` webhooks and is written to the ledger.

**Failover:**
- Applies to card-not-present only (QR/online).
- A `RoutingGatewayPort` tries adapters in priority order from `processor_account`, with a circuit breaker.
- Card-present is bound to the reader SDK and is not failed over.
- Secondary processors are an interface only in v1. The merchant must already be onboarded with that processor.

**Webhooks:**
- Verify `Stripe-Signature` (`t`, `v1`, HMAC-SHA256, 300 s tolerance, constant-time compare).
- Then `INSERT ... ON CONFLICT (event_id) DO NOTHING` into `webhook_event`. Rows already seen return 200 and are skipped.
- Handlers are idempotent on business keys too.

**Realtime:**
- Inventory and course changes publish domain events. `@TransactionalEventListener(AFTER_COMMIT)` sends them to `SseBroadcaster` (an `SseEmitter` registry per location+topic).
- Every event is persisted with a `bigserial` seq. The client reconnects with `Last-Event-ID` and replays from `realtime_event`.
- Single node in v1. Postgres `LISTEN/NOTIFY` fan-out is noted for multi-node.
- Latency test asserts commit → client < 100 ms.

**Client sync:** `POST /api/v1/sync/mutations` takes a batch of `{mutationId(UUIDv7), aggregateId, type, payload, deviceSeq}`. Each mutation is applied in its own transaction and recorded in `processed_client_mutation`, so a replay is a no-op. The response is per-mutation `APPLIED | DUPLICATE | CONFLICT(reason)`, for example `OVERSOLD` or `ITEM_86ED`.

---

## 3. Flyway `V1__baseline.sql` (design)

Conventions:
- PKs are `uuid` (UUIDv7, client-generatable), except log tables which use `bigserial`.
- Money is `bigint *_cents` + `currency char(3) default 'USD'` with `CHECK (*_cents >= 0)` where applicable.
- Enums are `text` + `CHECK`, which survives migrations better than PG enums.
- `version bigint` is used for optimistic locking.

**Merchant / pricing / compliance**
```
merchant(id, legal_name, status)
processor_account(id, merchant_id→, processor CHECK IN('STRIPE',…), external_account_id, priority, UNIQUE(merchant_id, processor))
pricing_plan(id, code UNIQUE, monthly_fee_cents CHECK(monthly_fee_cents = 0),            -- $0/mo is a schema invariant
             cp_spread_bps, cp_fixed_cents, cnp_spread_bps, cnp_fixed_cents, active)
merchant_pricing(merchant_id→, plan_id→, effective_from, effective_to NULL, EXCLUDE overlapping ranges)
state_compliance_profile(state_code char(2) PK, tip_credit_allowed, min_wage_cents, min_cash_wage_cents,
             tip_card_fee_deduction_allowed,          -- seeded FALSE for CA, ME, MA, DE
             mandatory_service_charge_taxable, effective_from)
location(id, merchant_id→, state_code→, timezone, currency, uses_tip_credit,
         ca_80_80_applies, ca_separate_accounting_elected,     -- CA cold-to-go rule inputs
         CHECK(NOT uses_tip_credit OR <state allows> enforced in domain + trigger))
tax_rule(id, state_code, jurisdiction_code, tax_category, fulfillment_channel NULL(=any),
         rate_ppm int, effective_from, effective_to)
```
**Menu / inventory (unified, atomic 86)**
```
inventory_item(id, location_id→, name, tracked bool, on_hand int NULL, manually_86ed bool, version)
menu_item(id, location_id→, name, price_cents, tax_category, default_course int, temp CHECK IN('HOT','COLD','NA'),
          visible_pos, visible_qr, kds_station_id NULL, active)
modifier_group(id, location_id→, name, min_select, max_select)
menu_item_modifier_group(menu_item_id→, modifier_group_id→, sort)          -- groups shared = "linked modifiers"
modifier_option(id, modifier_group_id→, name, price_delta_cents, active)
inventory_consumption(id, menu_item_id NULL→, modifier_option_id NULL→, inventory_item_id→, qty int>0,
                      CHECK(num_nonnulls(menu_item_id, modifier_option_id) = 1))
```
- Availability is derived, never stored on the menu row. An item or option is 86'd when any consumed inventory item is `manually_86ed` or has `on_hand < qty`.
- Commit-time decrement is one transaction. It updates `inventory_item` rows **ordered by id** with `SET on_hand = on_hand - :q WHERE id=:id AND (NOT tracked OR on_hand >= :q)`. If the rowcount is 0 the whole order rolls back with `OVERSOLD`.

**Orders / seats / coursing / splits**
```
dining_table(id, location_id→, label, qr_token UNIQUE)
orders(id, location_id→, table_id NULL→, fulfillment_channel CHECK IN('DINE_IN','TAKEOUT','DELIVERY'),
       status CHECK IN('OPEN','COMMITTED','CLOSED','VOID'), origin_device_id, opened_at, committed_at, version)
seat(id, order_id→, seat_no, UNIQUE(order_id, seat_no))
course(id, order_id→, course_no, state CHECK IN('HOLD','FIRE','PREPPING','READY','SERVED'), version, UNIQUE(order_id, course_no))
course_transition(seq bigserial, course_id→, from_state, to_state, actor_id, reason NULL, at)   -- append-only; reversals = new rows
order_line(id, order_id→, seat_id NULL→, course_id→, menu_item_id→, qty,
           unit_price_cents, tax_category, temp, name_snapshot)              -- price/tax snapshot at commit
order_line_modifier(id, order_line_id→, modifier_option_id→, price_delta_cents, name_snapshot)
service_charge(id, order_id→, kind CHECK IN('MANDATORY','AUTO_GRATUITY','DELIVERY_FEE'), amount_cents|rate_bps, taxable)
guest_check(id, order_id→, label, status CHECK IN('OPEN','PAID','VOID'))
check_allocation(check_id→, order_line_id→, numerator int>0, denominator int>0, PK(check_id, order_line_id))
```
- Fractional splits: for each line, Σ numerator/denominator across checks must equal 1. A deferred constraint trigger enforces this, and the domain mirrors it.
- Penny rounding uses largest remainder, deterministic by check id.
- Tax is computed **per check**, because each check is its own receipt.

**Payments / ledger / idempotency**
```
payment(id, check_id→, processor, processor_intent_ref UNIQUE NULL, method CHECK IN('CARD_PRESENT','QR_CNP','CASH','BT_READER_OFFLINE'),
        amount_cents, tip_cents, application_fee_cents, status, idempotency_key UNIQUE, created_at)
journal_txn(id, location_id→, kind, source_ref, occurred_at)
journal_line(id bigserial, txn_id→, account CHECK IN('SALES','TAX_PAYABLE','TIPS_PAYABLE','SERVICE_CHARGE',
             'PLATFORM_FEE','PROCESSOR_FEE','MERCHANT_CASH','TIP_FEE_DEDUCTION','REFUNDS'), amount_cents bigint /*signed*/, currency)
  -- deferred trigger: Σ amount_cents per txn = 0;  BEFORE UPDATE/DELETE trigger RAISEs → append-only
tip_pool(id, location_id→, period_start, period_end, distribution_basis CHECK IN('HOURS','POINTS'))
tip_pool_member(pool_id→, employee_id→, role_snapshot, is_manager_snapshot, is_supervisor_snapshot,
                owner_equity_bps_snapshot, owner_active_mgmt_snapshot, boh_snapshot, hours_x100, points,
                CHECK(NOT is_manager_snapshot AND NOT is_supervisor_snapshot
                      AND NOT (owner_equity_bps_snapshot >= 2000 AND owner_active_mgmt_snapshot)))
employee(id, location_id→, display_name, role, is_manager, is_supervisor, owner_equity_bps, owner_active_mgmt, boh)
webhook_event(event_id text PK, processor, type, received_at, processed_at NULL, payload_sha256)
processed_client_mutation(mutation_id uuid PK, device_id, result, applied_at)
offline_check_claim(check_id uuid PK, device_id, claim_sig, amount_cents, status, reconciled_at NULL)
device(id, location_id→, ed25519_pubkey, revoked_at NULL)
realtime_event(seq bigserial PK, location_id, topic, payload jsonb, at)    -- SSE replay
```
- The BOH/tip-credit block crosses tables (location × member). It is enforced in `TipPoolPolicy` plus a trigger, because a CHECK constraint can't span tables.
- The schema never stores PAN, SAD or CVV, only processor refs.

---

## 4. Domain engines (Slice 3 core rules)

- **Tax.** `rate = Σ tax_rule(state, jurisdiction, category, channel|any)`, rounded half-up per check. Inputs are `item.taxCategory`, `order.fulfillmentChannel` and `location`.
  - CA cold food to go is exempt **unless** `ca_80_80_applies && !ca_separate_accounting_elected`.
  - Hot prepared food is always taxable.
  - Dine-in is taxable.
  - Voluntary tips are never taxable. Mandatory/auto-gratuity charges are taxed per `state_compliance_profile.mandatory_service_charge_taxable`.
  - Platform fees never appear on diner receipts or tax lines.
- **Tip pool.**
  - Managers, supervisors and active owners with ≥20% equity are rejected always.
  - BOH members are rejected when `location.uses_tip_credit`.
  - Distribution uses integer cents with a largest-remainder residual.
- **Overtime.**
  - Tipped OT cash = `1.5 × full_min_wage − tip_credit`, where `tip_credit = min_wage − cash_wage`.
  - The engine returns regular-rate math only. Payroll is out of scope.
- **Tip card-fee deduction.**
  - Throws `ProhibitedDeduction` when the state is in {CA, ME, MA, DE} (data-driven plus a hard-coded guard set).
  - Otherwise `floor(total_card_fee_cents × tip_cents / charged_total_cents)`. `total_card_fee` is the merchant's actual cost, meaning the Stripe fee plus the application fee.

---

## 5. Android POS (`/android-pos`)

**Stack:** Compose, Hilt, Room (KSP), kotlinx.serialization, WorkManager, Ktor server (CIO) for the hub and Ktor client (OkHttp) for the cloud. `minSdk 26`, `targetSdk 36`.

**Room (local-first):**
```
OrderEntity, SeatEntity, CourseEntity, OrderLineEntity, LineModifierEntity, GuestCheckEntity, CheckAllocationEntity   (UUIDv7 ids, cents as Long)
MenuSnapshotEntity / InventorySnapshotEntity (server seq watermark)
OutboxEntity(mutationId PK UUID, aggregateId, type, payloadJson, deviceSeq, createdAt, attempts, state[PENDING|INFLIGHT|DONE|CONFLICT], lastError)
HubPeerEntity(deviceId, host, port, certSha256, pairedAt)
OfflineClaimEntity(checkId, claimJson, signature, status)
```
- Every user command writes the domain rows plus the outbox row in **one `@Transaction`**.
- Inventory is decremented optimistically on the device. A server `CONFLICT(OVERSOLD)` surfaces as a blocking alert. It is never silently dropped.
- `OutboxDrainWorker` is unique work with `NetworkType.CONNECTED` and exponential backoff. It sends batches of 50 in order per aggregate, and marks rows DONE on `APPLIED|DUPLICATE`.
- The device keypair is created in Android Keystore. The public key is registered when online, and it signs offline QR claims.

**LAN Sync Hub (`HubForegroundService`, type `connectedDevice`):**
- Holds `WifiManager.WIFI_MODE_FULL_LOW_LATENCY` (API 29+, `FULL_HIGH_PERF` below) plus a `PARTIAL_WAKE_LOCK`. Both are released in `onDestroy`.
- Shows an ongoing notification with a stop action, and handles `START_STICKY` restarts.
- Ktor endpoints:
  - `GET /kds/*` serves the static bundle from assets
  - `GET /api/v1/stream?token=…` is SSE for the KDS (bearer goes in the query because of the EventSource limitation)
  - `WS /api/v1/peer` is for other POS devices
  - `POST /api/v1/course/{id}/transition`
- Discovery: `NsdManager` registers `_squaretoast._tcp`.
- Fallback: the screen shows `http://<ip>:8787/kds?pair=<token>` as text plus a QR code. No UPnP or router config is involved.
- Trust:
  - KDS browsers use a pairing token that rotates per shift.
  - POS↔POS uses TLS with a self-signed hub cert whose SHA-256 is carried in the pairing QR and pinned.
- Authority:
  - One designated hub per location. While offline, the hub's course-state stream is canonical for the floor.
  - On reconnect, the server orders events by `(hubSeq, deviceSeq)`.

**Tap to Pay gating (`PaymentMethodSelector`):** Tap to Pay is allowed only if all of these hold:
1. `Build.VERSION.SDK_INT >= 33`
2. NFC present and enabled
3. The network is validated (`NET_CAPABILITY_VALIDATED`)
4. A backend `/connection_token` round-trip succeeds
5. `Terminal.supportsReadersOfType(TAP_TO_PAY_DEVICE, config)` is supported
6. The SDK attestation/connect step succeeds (Play Integrity is handled inside the SDK)

Otherwise, in order:
- Use the paired BT M2/WisePad 3 reader. These support Stripe Terminal offline mode within Stripe's limits, so the flag is shown.
- Otherwise use **QR Pay-at-Table**.

SDK details:
- `Application.onCreate` returns early when `TapToPay.isInTapToPayProcess()`, because the SDK runs in its own process.
- Debug builds use simulated discovery (`isSimulated = true`) plus simulated test cards. Release builds reject simulated mode.
- The tip is chosen in the Compose UI before `createPaymentIntent`. No card data ever touches app code, and logs are scrubbed by a Timber tree that drops SDK payloads.
- Exact 6.0.0 class names (`TapToPayDiscoveryConfiguration`, `TapToPayConnectionConfiguration(onBehalfOf=…)`) will be **verified against the 6.0.0 changelog at Slice 5 start**.

**Offline QR fallback flow:**
1. The POS builds `CheckClaim{checkId, locationId, deviceId, lines digest, subtotal, tax, serviceCharges, totalCents, currency, exp(+2h), nonce}` and signs it with the device key. The QR contains `https://pay.squaretoast.app/c#<base64url claim+sig>`. The fragment never reaches server logs or Referer headers.
2. The guest phone, on cellular, opens the page. The backend verifies the signature against `device`, upserts `offline_check_claim`, and creates a CNP destination charge with the cnp spread.
3. The guest pays with the Payment Element, which is hosted by Stripe, so the PCI scope stays SAQ-A.
4. The POS shows "pending, guest paying". When it is back online, outbox sync matches `checkId`, and the paid claim closes the check. A claim/actual total mismatch raises a reconciliation alert.

**Manifest:** `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `NFC`, `WAKE_LOCK`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`, **`MODIFY_AUDIO_SETTINGS`**, `ACCESS_FINE_LOCATION` (Terminal requirement), `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`.

---

## 6. Web (`/web-kds-qr`)

- **KDS** (`/kds`):
  - `EventSource` on the cloud stream when online, or on the hub when on LAN, resuming via `Last-Event-ID`.
  - Station filter. Ticket cards per course with reversible state buttons (e.g. READY→PREPPING), which POST transitions.
  - Web Audio bell, unlocked by a "Start shift" tap because of the autoplay policy.
  - Render budget: under 16 ms per event, using keyed Preact signals.
- **Guest QR** (`/t/{qrToken}` order, `/c#claim` pay, `/r/{checkId}` receipt): menu shows live 86 state over SSE. Pay uses the Stripe Payment Element.
- **PWA:** manifest plus service worker on the HTTPS cloud origin only.

---

## 7. Exact file tree (end of Slice 6)

```
.
├── CLAUDE.md
├── settings.gradle.kts            build.gradle.kts            gradle.properties
├── gradle/libs.versions.toml      gradle/wrapper/…            gradlew(.bat)
├── docs/ARCHITECTURE_PLAN.md
├── backend/
│   ├── build.gradle.kts           (boot app; integrationTest suite; check → subprojects)
│   ├── domain/
│   │   ├── build.gradle.kts       (java-library, errorprone, no Spring)
│   │   └── src/{main,test}/java/com/squaretoast/domain/
│   │       ├── money/{Money,Currency}.java  + package-info.java (@NullMarked, in every package)
│   │       ├── menu/{InventoryItem,MenuItem,ModifierOption,Availability,InventoryRepository}.java
│   │       ├── order/{Order,Seat,Course,CourseState,CourseStateMachine,GuestCheck,SplitAllocator}.java
│   │       ├── tax/{TaxEngine,TaxRule,TaxCategory,FulfillmentChannel,StateProfile}.java
│   │       ├── labor/{TipPoolPolicy,TipDistributor,OvertimeCalculator,TipFeeCalculator,ProhibitedDeduction}.java
│   │       ├── payment/{PaymentGatewayPort,ChargeRequest,GatewayIntent,ApplicationFeeCalculator}.java
│   │       └── ledger/{JournalTxn,JournalLine,Account}.java
│   ├── payment-adapters/
│   │   ├── build.gradle.kts       (only module allowed Jackson 2 if ever needed)
│   │   └── src/{main,test}/java/com/squaretoast/payment/adapter/
│   │       ├── stripe/{StripeApi(@HttpExchange),StripeConnectAdapter,StripeWebhookVerifier,StripeFormEncoder}.java
│   │       └── routing/{RoutingGatewayPort,ProcessorCircuitBreaker}.java
│   └── src/
│       ├── main/java/com/squaretoast/
│       │   ├── SquareToastApplication.java
│       │   ├── config/{ApiVersioningConfig,PaymentConfig,VirtualThreadsConfig}.java
│       │   ├── menu/{MenuController,InventoryCommitService,JpaInventoryRepository}.java
│       │   ├── order/{OrderController,CourseController,SplitController}.java
│       │   ├── realtime/{SseBroadcaster,SseController,RealtimeEventStore}.java
│       │   ├── sync/{MutationController,MutationApplier}.java
│       │   ├── payment/{PaymentController,WebhookController,OfflineClaimService}.java
│       │   └── ledger/{LedgerService,MarginReconciler}.java
│       ├── main/resources/{application.yaml, db/migration/V1__baseline.sql, db/migration/R__seed_state_profiles.sql}
│       ├── test/java/…            (unit + ArchUnit: ArchitectureRulesTest, Jackson2FenceTest, NullMarkedPackagesTest)
│       └── integrationTest/java/… (Testcontainers PG: FlywayV1IT, AtomicEightySixIT, SseLatencyIT, WebhookIdempotencyIT, MutationSyncIT)
├── android-pos/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/AndroidManifest.xml
│       ├── main/assets/kds/       (copied from web-kds-qr/dist at build)
│       ├── main/java/com/squaretoast/pos/
│       │   ├── PosApplication.kt  (TapToPay.isInTapToPayProcess guard, Terminal init)
│       │   ├── data/{AppDatabase,dao/*,entity/*,OutboxRepository}.kt
│       │   ├── sync/{OutboxDrainWorker,SyncApi}.kt
│       │   ├── hub/{HubForegroundService,HubServer,NsdAdvertiser,PairingQr,LocksHolder}.kt
│       │   ├── payment/{PaymentMethodSelector,TapToPayGate,TerminalTokenProvider,OfflineClaimSigner}.kt
│       │   ├── domain/{CourseStateMachine,SplitAllocator}.kt   (Kotlin ports of shared rules; same test vectors)
│       │   └── ui/{order/,coursing/,split/,pay/,hub/}*.kt
│       ├── test/java/…            (JUnit5 + MockK + Robolectric for Room/outbox/gates)
│       └── debug/…                (simulated reader wiring)
└── web-kds-qr/
    ├── package.json  vite.config.ts  tsconfig.json  build.gradle.kts
    ├── public/{manifest.webmanifest,bell.ogg}
    └── src/{kds/,guest/,shared/{sse.ts,money.ts,audio.ts}}  + tests (vitest)
```
- The domain rules shared by the backend and Android (course transitions, split allocation) have one JSON **test-vector file** `shared-test-vectors/*.json`, which both suites run against. This catches drift without a KMP module.
- Promoting them to a Kotlin Multiplatform module is a later option.

---

## 8. Slice → first failing tests

| Slice | RED tests written first |
|---|---|
| 1 | `NullMarkedPackagesTest` (every package has @NullMarked), `Jackson2FenceTest` (ArchUnit), `verifyNoJackson2` task, `FlywayV1IT` (migrates; `pricing_plan.monthly_fee_cents` rejects 1; journal UPDATE raises), `ApiVersioningIT` (`/api/v1/health` 200, `/api/v2/health` 400), NullAway compile fixture. |
| 2 | Linked modifier group shared across 2 items; 86 inventory → both items + option unavailable on POS/QR/KDS; concurrent commit of last unit → exactly one succeeds; SSE event < 100 ms; `Last-Event-ID` replay. |
| 3 | Table-driven tax vectors (CA hot/cold × DINE_IN/TAKEOUT × 80-80 on/off; mandatory charge taxable vs tip not); pool rejects manager/supervisor/20% owner; BOH rejected iff tip credit; CA/ME/MA/DE deduction throws; proportional fee floors; OT on full min wage. |
| 4 | `application_fee_amount` math; form body contains `transfer_data[destination]`, `on_behalf_of`, Idempotency-Key (MockWebServer); bad signature → 400; replayed event processed once; journal txn balances; margin = fee − stripe fee. |
| 5 | Outbox written atomically with order; drain is idempotent across retries; TapToPayGate denies on API 32 / offline / unsupported; selector falls back BT → QR; claim signature verifies; hub acquires/releases locks; manifest contains MODIFY_AUDIO_SETTINGS. |
| 6 | SSE client resumes with Last-Event-ID; reversible state transitions; money formatter integer-only; bell plays only after unlock. |

---

## 9. Risks and open questions

1. **Stripe Terminal 6.0.0 API surface.** I'll verify the class names and the `onBehalfOf` connection param at the start of Slice 5, before writing tests.
2. **Tap to Pay with developer options.** Stripe blocks production Tap to Pay when debugging is enabled. QA on real devices needs release-signed builds.
3. **Tip-pool and tax legal data.** Seed profiles cover CA, ME, MA, DE and a default. Real rate tables need a provider (out of scope for v1) and counsel review.
4. **Single-hub topology.** v1 assumes one hub device per location. Hub failover election is deferred.
5. **Question:** should guest QR ordering in v1 also work offline via the LAN hub (guest phone on restaurant Wi-Fi)? The plan assumes **cloud-only** QR ordering, while QR *pay* is offline-capable via signed claims.
