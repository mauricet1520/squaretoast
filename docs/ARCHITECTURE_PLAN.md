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
| D4 | The Jackson-2 fence bans `com.fasterxml.jackson..` **except `com.fasterxml.jackson.annotation..`**. It is enforced on **compile** classpaths (Gradle exclude) plus ArchUnit over our bytecode. | Jackson 3 deliberately keeps `jackson-annotations` in the old 2.x package. A naive rule would fail on Boot itself. *Slice 1 finding:* `jackson-databind` 2.20 still arrives on `:backend` **runtime** classpath transitively (third-party, e.g. Flyway), so the fence is "our code cannot compile against it", not "absent from the JVM". |
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

1. **Stripe Terminal 6.0.0 API surface (resolved in Slice 5).** Checked against the 6.0.0 AARs. Discovery is `TapToPayDiscoveryConfiguration(isSimulated)`. Connection is `TapToPayConnectionConfiguration(useCase = TapUseCase.Pay(locationId), autoReconnectOnUnexpectedDisconnect, tapToPayReaderListener)`; there is **no `onBehalfOf`** parameter. The connected account is routed by the server-side PaymentIntent (Slice 4). For destination charges, Stripe wants the Terminal Location **and** connection tokens on the **platform** account (platform key, no `Stripe-Account` header), with one Location per merchant location. `Terminal.init` now requires a `LocaleConfig`, and `compileSdk` must be ≥ 35.
2. **Tap to Pay with developer options.** Stripe blocks production Tap to Pay when debugging is enabled. QA on real devices needs release-signed builds.
3. **Tip-pool and tax legal data.** Seed profiles cover CA, ME, MA, DE and a default. Real rate tables need a provider (out of scope for v1) and counsel review.
4. **`btree_gist` on managed Postgres (resolved).** It has been a *trusted* extension since PG 13, so a non-superuser database owner can create it. `FlywayV1IT.migrationsRunAsANonSuperuserDatabaseOwner` proves the full migration set runs that way on an empty database. Reference tables (`currency`, `state_compliance_profile`) should be writable only by the migration role, not the app role. The deploy role needs `CREATE` on the database; no superuser is required. Minimum supported server: PostgreSQL 13.
5. **SSE fan-out runs on the committing thread (Slice 2).** After commit, `SseBroadcaster` writes to every subscriber of the location under one lock. A client whose TCP buffer is full can delay that location's commit responses until its write fails or the 30-minute emitter timeout. Before production: per-subscriber bounded queues drained by their own virtual thread, plus SSE heartbeats to detect dead clients sooner. Separately, the deadlock test uses two inventory rows and proves ordering through the sorted demand, not through SQL alone.
6. **Single-hub topology.** v1 assumes one hub device per location. Hub failover election is deferred.
7. **Question (counsel): card fee recovered from tips.** §4 defines `total_card_fee` as the Stripe fee *plus* the platform's `application_fee_amount`. That is the merchant's real cost, but it means part of the platform's own revenue can be recovered from employee tips. The safer reading passes only the processor fee. `TipFeeCalculator` takes whatever fee the caller passes, so this is decided where Slice 4 wires it up.
8. **Slice 4 payment follow-ups.**
   - **Amount from the caller.** `PaymentService.start` trusts the caller's amount and tip, so no REST endpoint calls it yet. The Slice 5 controller must check the amount against the check's server-computed balance and authenticate the device.
   - **Mismatched charges.** A charge whose amount or currency doesn't match the stored payment is acknowledged with 200 and logged, but not settled; retrying cannot fix it. It needs a reconciliation record or alert, not silence.
   - **Fee currency.** The processor fee currency comes from the balance transaction. If it ever differs from the payment's currency (cross-currency Connect settlement), `CardSettlement` throws and the event fails on every retry. That can't happen while only USD is allowed.
   - **Stripe call inside a transaction.** The fee lookup holds a pooled connection and the payment row lock for up to 20 s. Move it ahead of the transaction, or onto an outbox, before high volume.
   - **Body size.** The webhook body is read into memory with no cap. Add a request-size limit at the edge.
   - **Webhook URL.** Stripe must be configured to call `/api/v1/webhooks/stripe`.
9. **Slice 5 deviations and follow-ups (Slice 5b = backend + wiring).**
   - **Claim key is ECDSA P-256, not Ed25519 (amends D8).** Android Keystore supports P-256 on every API level from 23, but Ed25519 only on some API 33+ devices. The backend verifies `SHA256withECDSA`.
   - **No backend endpoints yet.** The POS calls `POST /api/v1/sync/mutations`, `POST /api/v1/terminal/connection_token` and `POST /api/v1/checks/{id}/payments`. The payment call sends **only the tip**; the server computes the amount from the check, which closes the "amount from the caller" risk in 8. `CloudApi` fails closed without a device credential, so until device registration and auth exist the outbox waits and Tap to Pay stays gated off.
   - **Not yet wired in the UI:**
     - the Terminal Location ID, so Tap to Pay can never pass the gate;
     - paired BT reader discovery, so the selector always sees no reader;
     - offline QR claim display, which needs an on-device tax port or a cached server total;
     - order entry from a menu snapshot (menu sync).
   - **Device key registration and attestation.** Register the Keystore public key with a key-attestation challenge when online. The backend must accept claims only from registered keys and reject a reused nonce.
   - **Hub gaps:**
     - The hub's `CourseBoard` is not yet joined to Room. Hub transitions don't write to the outbox, and POS transitions don't reach the board.
     - `/kds/*` assets arrive in Slice 6.
     - The POS↔POS `WS /api/v1/peer` with a pinned TLS cert is not built.
     - The pairing token crosses the LAN in plain HTTP (D6). It rotates when the hub stops. Nothing logs request URLs.
   - **Small items:**
     - `transitionCourse` is not idempotent when retried with the same mutationId; the retry fails rather than double-applying.
     - Missing Room entities: seats, guest checks and allocations, inventory snapshots, offline claims, hub peers.
10. **Question:** should guest QR ordering in v1 also work offline via the LAN hub (guest phone on restaurant Wi-Fi)? The plan assumes **cloud-only** QR ordering, while QR *pay* is offline-capable via signed claims.

---

## 10. Slice 5b — device auth, sync, connection tokens, check payments (pending approval)

Goal: the backend half of the Android POS, so its outbox drains, Tap to Pay can pass its gate, and a payment's amount is always computed by the server. Offline QR **claim redemption** (D8) moves to Slice 6 with the guest pay page that calls it.

**V5 migration**
- `device`: replace `ed25519_pubkey` with `public_key_spki bytea` (ECDSA P-256, per §9 item 9). Add `token_hash bytea unique not null` (SHA-256 of the device token), `label`, `enrolled_at`. The table is empty, so nothing needs migrating.
- New `device_enrollment(code_hash bytea pk, location_id, expires_at, used_at, device_id)`. Codes are single-use, expire after 15 minutes and are stored hashed. Merchant-admin tooling that issues them is out of scope; tests seed them.
- `location.stripe_terminal_location_id text` (`tml_…`, a platform-account Location), nullable.
- `processed_client_mutation.reason text`, so a resent conflicted mutation returns the same reason.

**Device enrollment and auth** (a plain servlet filter, no Spring Security)
- `POST /api/v1/devices/enroll {code, publicKeySpki, label}` is public. It accepts only a P-256 key and returns `{deviceId, locationId, deviceToken}`. The token is 256-bit random, shown once and stored only as a hash. A bad, expired or reused code gets the same 400.
- `DeviceAuthFilter` guards `/api/v1/devices/me`, `/api/v1/sync/**`, `/api/v1/terminal/**` and `/api/v1/checks/**`. It reads `Authorization: Bearer`, looks up the hash, and rejects revoked devices with 401. The request carries the device's `deviceId` and `locationId`.
- `GET /api/v1/devices/me` returns `{deviceId, locationId, terminalLocationId}`.
- The existing Slice 2 order-commit, menu and 86 endpoints stay unauthenticated for now (new risk). Putting them behind the same filter is a one-line change once the KDS and POS send tokens.

**`POST /api/v1/sync/mutations`** (at most 50 per batch, otherwise 400; one DB transaction per mutation)
- **Dedupe:** a `mutationId` already seen from this device returns DUPLICATE if it was applied, or CONFLICT with its stored reason. A `mutationId` owned by another device returns CONFLICT `MUTATION_ID_TAKEN`.
- **ORDER_PLACED:**
  - Its location must be the device's location; otherwise CONFLICT `FORBIDDEN_LOCATION`.
  - It creates the order (DINE_IN, origin device), seats, courses and lines. Lines are **re-priced from `menu_item`**, so the device's price is ignored. It also creates one guest check covering every line at 1/1.
  - It then runs the Slice 2 `OrderCommitService.commit`, so inventory is decremented at commit. OVERSOLD or ITEM_86ED gives CONFLICT with that reason, and nothing is written.
- **COURSE_TRANSITIONED:**
  - The course must be in the device's location.
  - The server applies the transition from its own current state. A payload `from` that doesn't match gives CONFLICT `STALE_STATE`.
  - It writes `course_transition` and updates `course`. Android `FIRED` maps to the DB's `FIRE`; `PREPPING` is reserved for the KDS.
- An unknown type or a malformed payload gives CONFLICT `INVALID_MUTATION`.
- **Domain:** Java `CourseStateMachine` and `SplitAllocator`, tested against the same `shared-test-vectors/*.json` as Android.

**`POST /api/v1/terminal/connection_token`**
- New port method `PaymentGatewayPort.createConnectionToken(String terminalLocationRef)`.
- The adapter calls Stripe `POST /v1/terminal/connection_tokens` with `location=tml_…`, using the platform key and no `Stripe-Account` header.
- If the location has no `stripe_terminal_location_id`, the endpoint returns 409.

**`POST /api/v1/checks/{checkId}/payments {idempotencyKey, method, tipCents}`** (closes §9 item 8, "amount from the caller")
- The check must belong to the device's location; otherwise 404. `tipCents` must be ≥ 0.
- **Balance**, computed by `CheckBalance`:
  - start with the lines allocated to this check, each line's amount split across its checks with `SplitAllocator`;
  - add modifiers and service charges;
  - add tax from `TaxEngine`, using the location's state rules and the order's channel;
  - subtract this check's SUCCEEDED payments, and its PENDING ones under other idempotency keys.
- If the balance is ≤ 0, it returns 409 `NOTHING_DUE`. Otherwise it charges balance + tip through the Slice 4 `PaymentService.start` and returns `{paymentId, clientSecret}`.
- There is no `amount` field in the request.

**Android wiring**
- `CloudApi.enroll(code)` sends the Keystore public key, and `CloudApi.me()` fetches the device's details.
- The device token is stored AES-GCM-encrypted under an Android Keystore key and feeds `CloudApi`'s credential.
- `terminalLocationId` from `/devices/me` replaces the empty constant, so Tap to Pay can pass the gate once the backend issues tokens.
- A one-field enrollment screen is shown while the device is unenrolled.

**RED tests first**
- **Domain:** `CourseStateMachineTest` and `SplitAllocatorTest` against the shared vectors.
- **Adapter:** a connection-token form test: `location` is sent, there is no `Stripe-Account` header, and the Bearer is the platform key.
- **`DeviceSyncIT` (enrollment and auth):**
  - enrollment: a valid code works once; a reused or expired code, or a non-P-256 key, gets 400;
  - auth: a missing, wrong or revoked token gets 401 on every guarded path;
  - `/devices/me` returns the right fields.
- **`DeviceSyncIT` (sync):**
  - ORDER_PLACED re-prices lines, creates the check and decrements inventory;
  - a resend gets DUPLICATE and changes nothing;
  - a foreign location, OVERSOLD, a mutation ID owned by another device, and a stale course state each give CONFLICT with no writes;
  - a resent conflicted mutation gets the same CONFLICT and reason;
  - a course FIRE writes the transition row;
  - a batch of 51 gets 400.
- **`CheckPaymentIT`:**
  - the Stripe amount equals subtotal + tax + tip for a CA location, and the client cannot set it;
  - a second payment on a fully pending check gets 409;
  - another location's check gets 404, and a negative tip gets 400;
  - the connection token passes `location`, and a location without a Terminal Location gets 409.
- **Android:** `CloudApiTest` with Ktor MockEngine: enroll sends the code and SPKI; every call carries the Bearer; there is no call without a credential.

**Not in 5b:**
- deferred to Slice 6: offline claim redemption and nonce replay checks;
- key-attestation verification (an Android attestation chain to Google's roots);
- Bluetooth reader discovery;
- showing the offline QR code;
- Room entities for seats and checks;
- linking the hub's course board to Room.

**Slice 5b result and follow-ups (audit, 2026-11-18).** Fixed test-first in this slice:
- Concurrent payments on one check are serialised under the check's row lock.
- A retry after a payment settled returns that payment.
- The device filter now denies by default, with an explicit public list, and matches on the container's decoded, normalised path.

Still open:
- **Enrollment rate limit (Medium).** Add a per-IP and per-location throttle at the edge. The admin tool that issues codes must make them at least 128 bits (codes are stored as unsalted SHA-256).
- **Public Slice 2 endpoints (Medium).** Menu, 86 and order commit are still on the public list until the KDS and POS send tokens.
- **Revoked devices (Low).** A 401 from `/devices/me` should clear the stored token and show enrollment again. Re-enrolling does not revoke the previous token.
- **Order caps (Low).** No cap yet on quantity or lines per order; add one at about 999. A negative modifier total that makes a line negative gives a 500 in `CheckBalance`; reject it at commit.
- **Error-code leaks (Low).** `ORDER_EXISTS` and `MUTATION_ID_TAKEN` reveal that an ID exists elsewhere. IDs are random UUIDs, but these could fold into `INVALID_MUTATION`.

---

## 11. Slice 6 — browser KDS, guest QR ordering and pay, offline claim redemption

Approval: on 2026-11-18 the user asked for all remaining slices to be built without stopping for review. This section is the written plan, treated as approved by that instruction. Slice 6 is the last slice in this plan.

Goal:
- The kitchen and the guest each get a zero-install browser app.
- A guest can pay a check from their own phone, online or with a claim signed by an offline POS.
- The Slice 2 endpoints stop being public.

### 11.1 Decisions

| # | Decision | Why |
|---|---|---|
| S6-1 | A browser KDS **enrolls as a device**, as a POS does: an enrollment code plus a WebCrypto P-256 public key. It sends its device token as a Bearer header. | One auth model; no new kind of secret. |
| S6-2 | KDS live stream: `GET /api/v1/kds/stream?ticket=…&lastEventId=…`.<br>The ticket comes from `POST /api/v1/kds/stream-tickets` (device auth). It is 32 random bytes, stored as SHA-256, **single use**, and expires in 60 s.<br>The client reconnects itself, with a fresh ticket and the last seq it saw. | `EventSource` can't send headers, and the long-lived token must never go in a URL, because URLs get logged. Native auto-reconnect would reuse a spent ticket. |
| S6-3 | Public `/api/v1/stream` now sends **only the `availability` topic** (86 state). Course and order events go only to the authenticated KDS stream. | Keeps ticket contents (items, tables) off a public stream that covers a whole location. |
| S6-4 | KDS API (device auth, only for the device's own location):<br>• `GET /api/v1/kds/board` returns fired and ready courses with table, course number, lines, modifiers and station.<br>• `POST /api/v1/courses/{id}/transitions` takes `{action, expectedFrom}` and reuses `DeviceOrderService.transitionCourse`.<br>Every transition, from REST or sync, publishes a `course` event that carries the full ticket. | Reuses the shared state machine and its stale-state check. The board never has to re-fetch. |
| S6-5 | **Guest QR ordering is cloud-only** (this answers §9 Q10).<br>• `GET /api/v1/t/{qrToken}` returns the table and its QR-visible menu.<br>• `POST /api/v1/t/{qrToken}/orders` places an order priced by the server. The order **stays OPEN with its courses on HOLD, and takes no inventory**, until staff accept it through the existing `POST /orders/{id}/commit` (now device-only).<br>• Caps: qty 1–20 per line and at most 30 lines. Modifier choices must belong to the item's groups and respect min/max. An 86'd item is refused immediately. | A public endpoint must not be able to 86 the kitchen's stock or send prank tickets to the kitchen. Inventory is still taken at commit. |
| S6-6 | Guest bill and payment:<br>• `GET /api/v1/t/{qrToken}/checks` lists the table's checks with what each owes.<br>• `GET …/checks/{checkId}` is the bill, and the receipt once it is paid.<br>• `POST …/checks/{checkId}/payments` takes `{idempotencyKey, tipCents}` (method QR_CNP). It works only for committed orders and uses **the same locked balance flow as the POS**, extracted into `CheckPaymentService`. The response includes Stripe's **publishable** key. | The server computes every amount. Guests type card details only into Stripe's Payment Element, an iframe from js.stripe.com. |
| S6-7 | **Offline claim redemption:** `POST /api/v1/claims/payments` takes `{claim, idempotencyKey, tipCents}` and is public. The server checks all of:<br>• the token's shape;<br>• the device exists, is not revoked, and is at the claim's location;<br>• the `SHA256withECDSA` signature over the exact payload bytes, using the key from enrollment;<br>• amounts are consistent;<br>• the currency is the location's;<br>• the claim has not expired, and expires within 2 h 5 min of now.<br>Storage and replay:<br>• One claim is kept per POS check ID. A newer claim for the same check replaces the old one only if nothing on the old one is paid or in flight, and it expires later.<br>• A nonce can be used only once per device.<br>Amount: the claim total, minus what is paid or in flight under other keys, plus the tip, computed under the claim's row lock. | This amends D8: no provisional `guest_check` is created. The POS's check ID never matches the ID the server assigns at sync. |
| S6-8 | V6 migration:<br>• `payment.check_id` becomes nullable; `payment.claim_check_id` is added; exactly one of the two must be set.<br>• `offline_check_claim` gains `location_id`, `nonce`, `expires_at` and `payload`.<br>• New table `kds_stream_ticket`.<br>Webhook settlement finds the location through either path and marks the claim PAID. | Settlement and the ledger work for both payment kinds. |
| S6-9 | Lock down Slice 2. Menu GET, 86 PUT and order commit need a device token **for the same location**; another location's device gets 404. Existing tests change only their setup (adding a token), never their assertions. | Closes the Slice 5b Medium finding. |
| S6-10 | Web app: Vite 6, Preact, TypeScript, tested with Vitest and jsdom. One bundle serves `/kds`, `/t/{qrToken}` and `/c#{claim}`.<br>KDS modes:<br>• **Cloud:** device token plus stream tickets.<br>• **Hub:** opened from `http://<lan-ip>:8787/kds?pair=…`. The pairing token moves to `sessionStorage` and is stripped from the URL.<br>Also:<br>• The bell uses Web Audio and plays only after a "Start shift" tap.<br>• Strict CSP: no inline scripts and no `innerHTML`.<br>• The service worker runs only on the HTTPS cloud origin. | Decisions D6 and D9. |
| S6-11 | `:web-kds-qr` Gradle project: Exec tasks for `npm ci`, `npm test` and `npm run build`. Its `check` runs the tests and `tsc`.<br>`:android-pos` copies `dist` into a **generated** assets directory through `androidComponents`. | §1. The Android build now needs Node. |
| S6-12 | The hub serves `/kds` and `/kds/*` from APK assets:<br>• safe paths only (no `..`);<br>• the content type set from the file extension;<br>• `no-store` and the CSP headers;<br>• `index.html` as the fallback. | D6. |

### 11.2 RED tests first

- **`KdsIT`:**
  - The board shows fired courses with their lines, modifiers, station and table; held courses are hidden.
  - A REST transition writes a transition row and sends a `course` event to the KDS stream.
  - A stale `expectedFrom` gets 409; another location's course gets 404.
  - A ticket works once; reused or expired tickets get 401.
  - The public stream carries no `course` event.
  - A request with no token gets 401.
- **`GuestQrIT`:**
  - The table menu contains only QR-visible items; an unknown token gets 404.
  - The server sets the price and ignores any price the client sends.
  - qty 21, 31 lines, an invalid modifier choice, or an 86'd item each get 422.
  - Resending the same orderId returns the same order.
  - The order stays OPEN with inventory unchanged until a device commits it.
  - The bill shows subtotal, tax and due.
  - Paying an uncommitted order gets 409.
  - A QR payment charges due + tip as QR_CNP and returns the publishable key.
  - Another table's check gets 404.
- **`OfflineClaimIT`:**
  - A valid claim charges total + tip.
  - Each of these gets 400 `INVALID_CLAIM` with no payment row: tampered payload, wrong key, revoked device, wrong location, expired, TTL too long, wrong currency, inconsistent amounts.
  - Retrying with the same key returns the same payment; a second key gets 409 `NOTHING_DUE`.
  - A nonce reused on another check gets 409.
  - A newer claim replaces an unpaid claim but not a paid one.
  - The webhook settles the payment at the claim's location and marks the claim PAID.
- **`EndpointLockdownIT`:** 401 with no token; 404 for a device from another location.
- **Android `HubKdsAssetsTest`:**
  - Serves index.html, with the right content types.
  - Traversal attempts are refused.
  - CSP and no-store headers are set.
- **Web (Vitest):**
  - `money`: integers only.
  - `course`: the shared transition vectors.
  - `board`: events applied in seq order and reversible.
  - `kdsStream`: reconnects with a new ticket and `lastEventId`.
  - `bell`: silent until unlocked.
  - `claim`: decodes the fragment.
  - `cart`: modifier limits, totals and caps.
  - `pairing`: the token is stripped from the URL.

### 11.3 Slice 6 result (2026-11-18)

**Delivered.** Commits b54e6fe (tests) and cd85c44 (implementation). All test suites pass:
- `:backend:check`;
- 136 backend integration tests, 30 of them new in this slice;
- 71 Android unit tests;
- Android lint, whose only warnings come from the Stripe SDK's own ProGuard file;
- 62 Vitest tests;
- `tsc`.

**Deviations from §11.1:**
- **Nonces (S6-8).** They live in their own table, `offline_claim_nonce(device_id, nonce)`, not as a column on `offline_check_claim`. A replaced claim's nonce then stays used, so it can't be replayed against another check.
- **Asset paths (S6-10).** Assets use absolute `/assets/…` paths, because relative paths break on `/t/{qr}` deep links. The hub therefore also serves `/assets/*` from the same bundle.
- **Test changes.**
  - `DeviceSyncIT.apiPathsArePrivateUnlessListedAsPublic`: one assertion was changed on purpose, in the tests commit, because S6-9 makes the menu private.
  - `MenuFixture` setup changes: in the tests commit, it sends a device token; in the implementation commit, it handles committing an unknown order.
- **Guest checks are time-limited.** A table lists, shows and pays only orders opened in the last 12 hours, so a photo of a table's QR code doesn't reveal later diners' bills.

**Not verified:**
- No real browser or phone has run the web app.
- The Stripe Payment Element has not been exercised against Stripe test mode.
- Hosting the bundle on the cloud origin isn't set up. It needs an SPA fallback for `/t/*`, `/c` and `/kds`, and HTTPS for the service worker.

**Audit (2026-11-18): one High and two Medium findings were fixed test-first.** The failing tests are in b6d7d67; the fixes are in the commit after it.

- **High: abandoned or declined payments held a check's balance forever.**
  - A payment unfinished after 15 minutes is now canceled at Stripe before a new payment is taken.
  - If Stripe refuses to cancel it, because it may just have succeeded, it keeps holding the balance. That prevents a double charge.
  - `payment_intent.payment_failed` and `payment_intent.canceled` release the payment immediately.
  - A `charge.succeeded` that arrives after a decline is still recorded, because the money was taken.
- **Medium: changing the tip got the guest stuck on `IDEMPOTENCY_KEY_REUSED`.**
  - The page now reuses a key only for the same tip.
  - When the tip changes, the page first withdraws its old unconfirmed payment through `POST /payments/cancel`. The payment's idempotency key is the proof. Only QR payments can be withdrawn, never POS card-present ones.
- **Medium: a paid offline claim was never linked to the synced check.**
  - `ORDER_PLACED` may now carry the POS's own `checkId`. The server check then uses that ID, and the claim is marked reconciled.
  - The check and its claim share one balance: payments under either count against both.
  - Both payment paths take the same per-check advisory lock, so they can't both see the check as unpaid.
  - A synced check ID must be new, and must not match another location's claim.
  - **Not done yet:** the Android POS doesn't send `checkId` until offline claims are wired into its UI.

**Results after the fixes:**
- `:backend:check` passes.
- 147 integration tests pass.
- 71 Android unit tests pass.
- Android lint passes.
- 66 Vitest tests and `tsc` pass.

**Low findings still open:**
- **Tips have no upper bound.** An overflowing tip returns a 500.
- **A claim is marked PAID after any one successful payment, even a partial one.**
- **Nothing limits how many open guest orders a table can have.** Each one sends an `order` event to the kitchen screens. The fix is rate limiting at the edge.
- **A revoked KDS keeps its open stream until the stream times out.**
- **`frame-ancestors` needs a CSP response header from the cloud host.** A meta tag doesn't enforce it.
- **The hub's pairing token is sent over LAN HTTP** (D6).

### 11.4 Not in Slice 6 (follow-ups)

- **Reconciling a paid claim with the check once the POS syncs.** The POS has to send its check ID in ORDER_PLACED first.
- **POS UI for accepting guest orders.** The server publishes the events, but nothing on Android reads them yet.
- **Ticket contents on the hub's course board.** The board has to be joined to Room first.
- **Rate limits on public endpoints.** These belong at the edge.

---

## 12. Slice 7 — hardening: close the Slice 6 gaps

Status: **proposed, awaiting approval** (2026-11-25). Branch `slice-7/hardening`, stacked on PR #1.

Goal: finish the double-charge protection end to end, and turn the remaining 500s and open-ended inputs into clear errors. No new product features.

### 12.1 Decisions

| # | Decision | Why |
|---|---|---|
| S7-1 | **The Android POS sends its own check ID.**<br>• `OrderEntity` gains `checkId`, a UUID made once when the order is placed (Room v2 migration; the column is nullable for orders placed before it, which have already synced).<br>• `ORDER_PLACED` carries `checkId`.<br>• Every claim drafted for that order uses the same ID. | Closes the §11.3 "not done yet" item. The server then links a paid claim to the synced check, so the guest can't be charged twice. |
| S7-2 | **Tips have a ceiling.** A tip must be 0 to 1,000,000 cents ($10,000), and due + tip must not overflow. Otherwise the response is 422 `TIP_OUT_OF_RANGE`. One shared check is used by the POS, guest and claim endpoints. | Replaces the 500 caused by `Math.addExact` overflow. $10,000 is far above any real tip and well inside Stripe's limits. |
| S7-3 | **A claim is PAID only when it is paid in full.** The webhook marks the claim PAID once the succeeded payments under the claim and its check, less their tips, reach the claim's total. A partial payment leaves the claim PENDING. | Fixes the Low finding: today, any one payment marks the claim PAID. |
| S7-4 | **A table can have at most 10 open guest orders waiting for staff.** The 11th gets 429 `TOO_MANY_PENDING_ORDERS` until staff accept or void one. | A server-side backstop against flooding the kitchen screens. Edge rate limiting is still a follow-up. |
| S7-5 | **A revoked KDS loses its stream.** Each KDS stream subscription remembers its device. A sweep every 15 s closes the subscriptions of revoked devices. | Revocation today is a database change only, with no endpoint to hook into, so a sweep is the simplest reliable option. Today a revoked KDS keeps its stream until the stream times out. |
| S7-6 | **Lines can't go negative.** At commit, a line whose price plus modifiers is below 0 gets 422 `INVALID_LINE` instead of a 500 from `CheckBalance`. Quantity is capped at 999 and lines at 200 per order, for POS sync and commit alike. | Slice 5b Low "order caps". |
| S7-7 | **The Android POS handles a revoked token.** A 401 from `/devices/me` or from sync clears the stored device token and shows enrollment again. Unsynced outbox rows are kept and send after re-enrollment. | Slice 5b Low "revoked devices". |

### 12.2 RED tests first

- **Android `OrderOutboxTest`:**
  - The `ORDER_PLACED` payload's `checkId` equals the order's stored `checkId`, and it's a valid UUID.
  - Two orders get different check IDs.
- **Android claim test:** a claim drafted for an order uses that order's `checkId`.
- **Android `SyncAuthTest`:**
  - A 401 clears the token and switches the state to "needs enrollment".
  - Outbox rows are still there afterwards.
- **`OfflineClaimIT`:** end to end, a claim is paid, then the order syncs with the same `checkId`. The claim is marked RECONCILED, and the check shows nothing due.
- **`PaymentLimitsIT`:**
  - On all three pay endpoints, a tip of 1,000,001 cents or `Long.MAX_VALUE` gets 422 `TIP_OUT_OF_RANGE`, never a 500.
  - A tip of 1,000,000 is accepted.
- **`OfflineClaimIT`, partial payment:** after a partial payment settles, the claim stays PENDING. After the payment that covers the rest settles, it's PAID.
- **`GuestQrIT`:** the 11th pending guest order on a table gets 429. After staff commit one, the next order is accepted.
- **`KdsIT`:** after a device is revoked, its open KDS stream closes within 20 s.
- **`AtomicEightySixIT` or `DeviceSyncIT`:**
  - A negative-total line gets 422 `INVALID_LINE`.
  - Quantity 1000 gets 422, and so does an order with 201 lines.

### 12.3 Not in Slice 7

- Hosting the web bundle on the cloud origin. That brings the `frame-ancestors` response header and the service worker over HTTPS.
- Running on a real device and browser against Stripe test mode.
- Rate limits at the edge, for enrollment and public endpoints.
- TLS for hub pairing on the LAN (D6 accepted plain HTTP on the LAN).
- POS screens for accepting guest orders, and for showing an offline claim QR code.
- Folding `ORDER_EXISTS` and `MUTATION_ID_TAKEN` into `INVALID_MUTATION`.

### 12.4 Slice 7 result (2026-11-25)

**Delivered:** tests in 913ec26, fixes in the commit after it. Every suite passes:
- `:backend:check`, with NullAway and Error Prone clean.
- 155 backend integration tests, 8 of them new.
- 77 Android unit tests, 6 of them new.
- Android lint.

The web app didn't change.

**Changes from §12.1 and §12.2:**
- **Where the tests live.** The tip-limit tests are in `OfflineClaimIT` (POS and claim) and `GuestQrIT` (guest), not a new `PaymentLimitsIT`. Those classes already have each endpoint's setup.
- **No claim-drafting test.** POS screens for drafting claims don't exist yet (§12.3). Instead, `OrderRepository.checkIdOf(orderId)` returns the ID a claim must be signed with, and the outbox test checks it.
- **Migration test.** `AppDatabaseMigrationTest` builds the v1 database straight from the exported `1.json` schema, then opens it with the v2 code and `MIGRATIONS`. To read that schema, the test sources now include `schemas/`.
- **Revoked tokens.** `DeviceSession` holds the enrolled state, and `CloudApi` calls it on any 401 to an authenticated call. The 401 stays retryable, so outbox rows stay PENDING.
- **Claims scanned after the POS synced the check.** This came up while making the partial-payment test pass: such a claim is now stored with `reconciled_at` already set. Without it, a fully paid claim on a check the POS had already synced stayed PAID and never became RECONCILED.
- **Order commit checks limits first.** Line limits and a below-zero line price are checked before menu and modifier validation, so an oversized or negative order always fails with `INVALID_LINE`.
- **How the KDS sweep works.** It runs every `squaretoast.kds.revocation-sweep` (default 15 s) on one virtual thread. It relies on Spring 7's `ResponseBodyEmitter` supporting several completion callbacks; checked in 7.0.1.
- **Already passing.** `aPaidClaimIsReconciledWhenThePosSyncsTheSameCheck` passed before any fix. It stays as a regression test.

**Not verified:** the Android migration on a real device; the revoked-token flow on a real device.

**Audit (2026-11-25): no High findings.** No test assertion changed after the tests commit. A manual security review of the payment and auth diff found no High or Medium security issues.

Fixed:
- **Medium: a 401 that arrives late could log out a freshly re-enrolled POS.** It cleared whatever token was stored. Fixed test-first: the failing test is edf0108, the fix the commit after it. `CloudApi` now passes the rejected token to `DeviceSession.rejected(sent)`, which clears it only if it's still the stored token.
- **Low: a claim stored at the same moment the check syncs could miss reconciliation.** Each transaction could miss the other's row. Placing the order now takes the same per-check advisory lock as claim payments. No test covers it, because the timing can't be reproduced reliably.

Still open (Low):
- **A guest order resent while the table is full can get 429 instead of its existing order.** This happens only when two copies race; the next resend succeeds.
- **One device's KDS stream can stay in the revocation list.** It happens if the stream ends before tracking registers, and the entry stays until that device is revoked. The sweep makes one query per open device, and a revoked stream stays open while the DB is down.
- **Someone holding a table's QR code can fill that table's 10 waiting slots.** No endpoint exists to void guest orders, so the slots free up only when staff accept the orders or after 12 h. Staff can still ring up the table on the POS.
- **A claim that reuses another location's check ID would count that check's payments.** It needs a signed claim from an enrolled device. This predates Slice 7.

**Final results:**
- `:backend:check` passes.
- 155 backend integration tests pass.
- 78 Android unit tests pass.
- Android lint passes.

---

## 13. Slice 8 — POS order entry from a menu snapshot (pending approval)

**Goal.** A server can ring up a table on the POS: pick a table, tap menu items into a cart by seat and course, and send it. The order goes through the existing outbox and shows up on the KDS. It works offline from the last menu snapshot.

**Already there:**
- `GET /api/v1/locations/{id}/menu` (device auth, with 86 state)
- `OrderRepository.placeOrder` (order + outbox in one Room transaction)
- `MutationApplier` prices lines on the server

So this slice is Android only.

**Out of scope:**
- Modifiers: the sync `Line` has none. Items with a required modifier group (`minSelect > 0`) show "Needs options" and can't be added; that's the next slice.
- Editing an order after it is sent.
- A table list from the server: the table is typed or picked from recent tables.

**Design**

1. **Menu snapshot in Room (DB v3).**
   - Add `menu_item` (id, name, priceCents, currency, available, needsOptions, position) and a `menu_meta` row (locationId, fetchedAt).
   - `MIGRATION_2_3` adds the tables only. Existing rows are untouched.
2. **`MenuSync`.**
   - Calls `CloudApi.menu(locationId)` on start, on resume, and after any 86 event.
   - Replaces the snapshot in one transaction. On any failure it keeps the old snapshot.
   - A 404 or another location's menu is never stored.
3. **`Cart` (pure Kotlin, no Android).**
   - Lines keyed by (menuItemId, seat, course): `add`, `remove`, `setQty`.
   - Seats start at 1 and courses at 1..3. Unavailable or needs-options items are refused.
   - `subtotalCents` (long) is a display estimate only; the server prices the check.
   - `toPlaceOrder(table, locationId, ids)` builds the command.
4. **Sending.** `PosViewModel.send()` uses one `mutationId` and one `orderId` per cart, made when the cart is created. A double tap or retry therefore can't make two orders. On success it clears the cart, selects the new order and kicks `OutboxDrainWorker`.
5. **UI (M3).**
   - A "New order" FAB opens a full-screen sheet with:
     - a table field with recent-table chips
     - seat and course segmented buttons
     - a menu grid (sold-out items greyed with a "86" badge)
     - a cart list with − / + controls
     - a bottom bar: "Send · $12.50 est."
   - A banner shows when the menu is older than 1 h or missing ("Menu not loaded — connect once to load it").

**First failing tests**

| Test | Checks |
|---|---|
| `AppDatabaseMigrationTest.v2ToV3KeepsOrdersAndAddsMenu` | v2 rows survive; the menu tables exist |
| `MenuSyncTest` | snapshot replaced on success; kept on network error and on 401; never stored for another location |
| `CartTest` | same item/seat/course merges qty; qty 0 removes; refuses 86'd and needs-options items; subtotal in long cents; empty cart can't build an order |
| `CartTest.toPlaceOrderKeepsSeatAndCourse` | `NewLine`s carry seat, course and qty; same ids on every call |
| `PosViewModelSendTest` | sending twice writes one order + one outbox row; cart cleared after |

**Verification:**
- `./gradlew :android-pos:testDebugUnitTest :android-pos:lintDebug`
- On the Fold5: place an order for T1 and see it on the browser KDS.
- Turn Wi-Fi off, place an order, turn it back on, and see it sync.

### 13.1 Results and changes from the plan (2026-11-26)

**Changes:**
- **Where the IDs live.** `mutationId` and `orderId` live inside `Cart` and don't change across edits. `toPlaceOrder(table, locationId)` reuses them, so there's no separate ids argument.
- **Send-once test.** It sits in `OrderOutboxTest` (`placeOrderOnce`) rather than a `PosViewModelSendTest`, because a Hilt `AndroidViewModel` isn't unit-testable as it stands. The view model also guards double taps with a `sending` flag.
- **Courses.** `Cart` only requires course ≥ 1. The 1..3 limit is in the UI (Starter / Main / Dessert).
- **Menu refresh.** It runs on enrollment, each time the POS comes back to the front (ON_RESUME), and when a new order starts. Refreshing "after any 86 event" waits until the POS has a live event stream; until then, the server still refuses oversold items at sync, and the conflict dialog shows it.
- **Location ID check.** `CloudApi.menu` checks the location ID with `[A-Za-z0-9-]{1,64}` before putting it in the URL path. The ID comes from `/devices/me`, never from the screen.

**Verified on the Fold5:**
- The menu loaded from the local backend.
- I rang up T1: 2× Burger in course 1 and 1× Fries in course 2, an estimate of $34.50.
- The server shows the order COMMITTED with 3 items and 2 courses.
- After firing course 1 on the POS, it appeared on the browser KDS as "T1 Course 1 · 2× Burger · Cooking".

**Not verified:**
- Placing an order with Wi-Fi off and seeing it sync later.
- The unfolded inner-screen grid.

**Audit:** no High findings. I fixed both Medium findings: the stale-menu banner and the refresh on resume. I also fixed the Low cancellation finding in `send`.

---

## 14. Slice 9 — item options on the POS ("no onion", "medium rare") (pending approval)

**Goal.** Staff can ring up items with options on the POS. Required choices are enforced before Send. The kitchen sees them on the ticket. The server stays the only source of option prices.

**Already there:**
- the menu API returns groups (min/max) and options (price delta, available)
- `order_line_modifier` exists
- `OrderCommitService` validates selections at commit
- `KdsTickets` already lists modifier names

The gap is that the POS sync `Line` carries no options.

**Backend (small):**
1. **`MutationApplier.Line` gets an optional `optionIds`.** If the field is missing, it means none, so older POS builds keep working. More than 20 per line → CONFLICT `INVALID_LINE`.
2. **`DeviceOrderService.PlacedLine` gets `optionIds`.** For each option it inserts an `order_line_modifier` row:
   - The name and price delta come from `modifier_option` on the server. The device's prices are never used.
   - The option must belong to a group linked to that menu item in the same location, or the result is CONFLICT `INVALID_LINE`.
   - The existing commit validation then enforces min/max and availability, which gives CONFLICT `INVALID_MODIFIERS`.

**Android:**
3. **Room v4.**
   - New tables: `modifier_group` (id, menuItemId, name, minSelect, maxSelect, position) and `modifier_option` (id, groupId, name, priceDeltaCents, available, position).
   - `MIGRATION_3_4` adds the tables only.
   - `MenuSync` stores groups and options in the same transaction as the items.
4. **`OptionRules` (pure Kotlin).** `problems(groups, chosen)` returns plain-words problems, such as "Pick a cook temp" or "Pick up to 2 sauces", plus "Rare is sold out".
5. **`Cart`.**
   - A line is keyed by (item, seat, course, sorted option IDs), so the same burger with different options gets 2 lines.
   - The display price is the base price plus the option deltas, all in long cents.
   - `add(item, seat, course, options)` refuses a selection that has problems.
   - `needsOptions` stops blocking: such items open the options sheet instead.
6. **Payload.** `LinePayload` carries `optionIds`.
7. **UI (M3).**
   - Tapping an item that has groups opens a bottom sheet listing each group:
     - "Required" or "Optional · up to N"
     - a radio button for max = 1, check boxes otherwise
     - the price delta shown as "+$1.50"
     - sold-out options greyed
   - The sheet's "Add · $16.00" button turns on only when there are no problems.
   - Cart rows show the options under the item name.

**First failing tests**

| Test | Checks |
|---|---|
| `DeviceSyncIT.optionsArePricedAndNamedByTheServer` | rows use server name/delta; device price ignored; KDS ticket lists them |
| `DeviceSyncIT.anOptionFromAnotherItemIsRejected` / `aMissingRequiredOptionIsRejected` | CONFLICT with reason, nothing committed |
| `DeviceSyncIT.anOldPosWithoutOptionIdsStillSyncs` | backward compatible |
| `AppDatabaseMigrationTest.v3ToV4` | v3 menu and orders kept; option tables exist |
| `MenuSyncTest` | groups and options stored in server order; replaced together |
| `OptionRulesTest` | required missing, over max, sold-out option, valid |
| `CartTest` | different options → separate lines; price includes deltas; invalid selection refused |
| `OrderOutboxTest` | ORDER_PLACED payload carries each line's optionIds |

**Verification:**
- `./gradlew :backend:check :backend:integrationTest` (sync and KDS ITs), Android tests and lint.
- On the Fold5: order "Burger · Medium · No onion" and see both on the kitchen ticket.
- `/security-review`, since this changes sync input on the server.

**Out of scope:**
- nested options
- per-option inventory on the POS (the server still decrements at commit)
- editing a sent line

### 14.1 Results and changes from the plan (2026-11-26)

**Changes:**
- **Adding items with options.** `Cart.addWithOptions(item, seat, course, groups, chosenIds)` is a separate call. Plain `add` still refuses needs-options items, so the Slice 8 tests stay unchanged. Items with any group open the options sheet.
- **Repeated option IDs.** A line with the same option twice → CONFLICT `INVALID_LINE`, because it would charge twice.
- **KDS check.** The KDS check is the phone test below, not an IT. `KdsTickets` already lists `order_line_modifier` names.
- **`aMissingRequiredChoiceOrTooManyChoicesConflict`.** It passed before any change, thanks to commit validation, and stays as a regression test.
- **Sheet group labels.** "Required", "Pick N", "Pick a–b" or "Optional · up to N".
- **Local dev seed.** The Burger now has Cook temp (required) and Extras (up to 2).
- **Added during the phone test: OK on a refused change.**
  - The old sync-conflict alert had no button, so one refused order froze the POS for good.
  - The alert now says in plain words what was refused and why, and has an OK button.
  - OK on a refused order removes it from the phone and stops its queued course changes.
  - Rows are kept as ACKNOWLEDGED. Tests first: `ConflictAckTest`.
  - The go-ahead came relayed from the Session Manager session ("All yes for now"), not typed here.

**Verified on the Fold5:**
- The sheet blocks Add until a cook temp is picked.
- Burger · Medium rare · Bacon · No onion showed $17.00.
- The server stored the options with its own names and prices (Bacon +250, the others +0) and committed the order.
- After firing course 1, the browser KDS showed "1× Burger — Bacon, Medium rare, No onion".
- An order refused by a stale backend showed the new alert; OK removed it and the POS kept working.

**Checks:**
- Android: 116 tests and lint pass.
- `:backend:check` passes. DeviceSyncIT (23), KdsIT (11) and GuestQrIT (18) pass.
- Not rerun: the full backend IT suite.

**Security review (manual, server diff):** no High or Medium findings.
- Option SQL is parameterised.
- The location comes from the device token.
- Each option must link to that item at that location.
- Prices and names come from the server.
- Negative totals are still refused at commit.
- A null option ID → INVALID_MUTATION.
- Low: a device can now make up to 20 option lookups per line (200 lines, 50 mutations per batch). It needs an enrolled token; batching the lookups into one query is a later clean-up.

**Audit:** no High or Medium findings. The Low findings are written down above.

---

## 15. Slice 10 — the real bill on the POS, and paying it (pending approval)

**Goal.** For the selected order, staff see the server's bill: items with options, subtotal, tax, paid and due. The even-split preview uses the real amount due, not the $100 demo. "Charge" starts a card payment for the real check, with a tip picked on screen. The server still decides the amount; the POS sends only the tip.

**Already there:**
- `CheckBills.Bill` (subtotal, service charge, tax, paid, due, lines), used by guest QR pages
- `POST /checks/{id}/payments`, which takes the tip only
- the POS's own `checkId` per order, plus `SplitAllocator`, `PaymentMethodSelector` and `TerminalSession`

**Backend:**
1. **New `GET /api/v1/checks/{checkId}/bill` for devices.**
   - Returns `CheckBills.Bill`.
   - A check at another location → 404, as if it didn't exist.
   - A check the server hasn't seen yet (order still in the outbox) → 404, which the POS shows as "Bill appears once the order syncs."

**Android:**
2. **`CloudApi.bill(checkId)`.**
3. **`BillState` (pure).**
   - Loading, NotSynced, Offline or Ready(bill).
   - Tip options 15 / 18 / 20 % of the pre-tax subtotal (never tax or service charge), plus a custom amount, all in long cents with half-up rounding.
   - The split preview runs `SplitAllocator.evenly(due, ways)`.
4. **UI.**
   - A "Check" card under the courses shows lines with options, subtotal, tax, paid and due.
   - Tip chips, then "Charge $X" through the existing payment-method selector. Tap to Pay and Bluetooth reader go through Stripe Terminal only; QR at table shows the table QR.
   - Offline, it says "Bill needs a connection — use QR pay at table" (the offline claim flow is unchanged).
   - The demo split card goes away.

**Out of scope:**
- paying one share of a split, which needs server-side split checks
- receipts
- refunds

**First failing tests**

| Test | Checks |
|---|---|
| `CheckPaymentIT.aDeviceReadsItsOwnChecksBill` | totals match `CheckBills`; options priced in |
| `CheckPaymentIT.anotherLocationsCheckIsNotFound` / `anUnknownCheckIsNotFound` | 404, no leak |
| `CloudApiTest.billReadsTheCheckWithTheDeviceToken` | GET path, bearer, parse |
| `BillStateTest` | tips 15/18/20 on subtotal only, half-up, custom tip, never negative; split of real due sums exactly |

**Verification:**
- backend check plus the payment and sync ITs, Android tests and lint
- on the Fold5: ring up T1, see the bill with tax, pick 18 %, and see Charge show the right total
- Tap to Pay itself still needs your Stripe test key
- `/security-review`, because this is a payment change

### 15.1 Results and changes from the plan (2026-11-26)

**Changes:**
- **`Tips` replaces `BillState`.** The plan's pure `BillState` became `Tips` (`percentOf`, `charge`), tested in `TipsTest`. The bill states (Loading / NotSynced / Offline / Ready) live in `PosViewModel.BillView`.
- **No custom tip amount yet.** The choices are No tip, 15, 18 and 20 %.
- **Already-passing tests.** `anotherLocationsCheckOrAnUnknownCheckIsNotFound` and the real-due split test passed before any change; they stay as regression tests.
- **Local dev seed.** It now has a 7.25 % food tax.

**Audit fixes (Medium):**
- **Stale bill.** A slow bill reply for another order could replace the current one, so Charge could hit the wrong check. Now only the latest load may set it.
- **Double tap on Charge.** It could start two payments. Charge now runs one at a time, the button shows "Charging…", and switching orders is blocked meanwhile.
- **Split reset.** The split count reset on Refresh; the view model now holds it.

**Audit fixes (Low):**
- A cancelled payment rethrows instead of showing "did not complete".
- The tip resets after a submitted payment.
- Tip math can't crash the screen.

**Security review (manual):**
- The bill uses `CheckBalance.owed`, which matches the check's location with the device's token location. Otherwise it answers 404, like an unknown check, so it doesn't reveal the check exists.
- The endpoint is read-only.
- The POS still sends only the tip, and the server refuses negative or out-of-range tips.
- No High or Medium issues.

**Verified on the Fold5:**
- T1: Burger (Bacon, Medium rare, No onion) $17.00 + tax $1.23 = due $18.23.
- An 18 % tip of $3.06 gave Charge $21.29, split 2 ways as $10.65 + $10.64.
- With no Stripe key, Charge fell back to "Ask the guest to scan the table QR", with the reason in plain words, and created no payment.
- The split count survived Refresh.

**Checks:**
- Android: 123 tests and lint pass.
- `:backend:check`, CheckPaymentIT (12) and EndpointLockdownIT (3) pass.
- Not run: the full backend IT suite, and a real Tap to Pay charge (needs your Stripe test key).

**Open:** the split shares include the POS tip, but guests paying by QR pick their own tip. Paying a single share needs server-side split checks, planned for a later slice.

---

## 16. Slice 11 — 86 from the POS (mark stock sold out and back)

Today the server can 86 an inventory item, and the POS shows "86'd" badges, but no screen lets staff do it. Square and Toast both bury this; here it is one tap from order entry.

### 16.1 Decisions
- **New read endpoint** `GET /api/v1/locations/{locationId}/inventory` (device token). Returns `items: [{id, name, tracked, onHand, eightySixed}]` sorted by name. Another location, or an unknown one, gives 404. Not part of the guest menu, so stock counts never reach guests.
- **POS "Sold out" sheet** opened from order entry: a list of stock with a switch each. A switch calls the existing PUT `.../eighty-six`, then refreshes the menu so badges update. Needs a connection; offline it says so (an 86 must be server truth, so it is not queued in the outbox).
- **Pure helper** `order/StockList` filters by search text and puts 86'd items first.
- **Local seed** adds stock: "Burger patty" (Burger) and "Bacon" (the Bacon option).

### 16.2 RED tests first
- Backend `InventoryListIT`: lists the location's stock with on-hand and 86 state; another location → 404; an 86 via PUT shows up in the list.
- Android `StockListTest`: search, ordering, empty search returns all.

### 16.3 Results (2026-11-26)
- **Already passing:** the 401/404 test passed before the change (deny-by-default device filter); kept as a regression test.
- **Audit fixes (Medium):** a counted item at 0 now shows the switch on and disabled ("restock to bring it back") instead of a switch that does nothing; a failed refresh keeps the list on screen and shows an error instead of wiping it.
- **Audit fixes (Low):** stale errors clear on reload; the location comes from the server (`me`), not the cached menu; tests added for un-86 and a missing token.
- **Security review (manual):** read-only list scoped to the device's location (404 otherwise, 401 without a token); not in the guest menu, so stock counts never reach guests; 86 writes reuse the existing scoped endpoint. No High or Medium issues.
- **Verified on the Fold5:** the Sold out sheet listed Bacon (Not counted) and Burger patty (40 left); 86ing the patty set it in the database and the Burger tile showed "86'd"; turning it back cleared it.
- **Checks:** Android 127 tests + lint pass; `:backend:check`, InventoryListIT (4), AtomicEightySixIT, EndpointLockdownIT pass.
- **Not covered:** view-model tests for double toggles and stale loads.

---

## 17. Slice 12 — guest QR orders on the POS

A guest's QR order reaches the KDS, but staff never see it on the POS, so they can't check or charge it. Square has no table ordering in the free tier; Toast charges extra for it.

### 17.1 Decisions
- **New endpoint** `GET /api/v1/guest-orders` (device token; the location comes from the token, so nothing in the path to tamper with). Lists COMMITTED guest orders (`origin_device_id is null`) from the last 12 hours at the device's location, newest first: `{orderId, table, openedAt, checks: [{checkId, label, status, dueCents, currency}]}`. Amounts come from the same `bills` code as the guest and POS bill.
- **POS:** the Orders card gains a "From guests (QR)" row of chips ("T1 · $18.23"). Tapping one opens the same Check card (bill, tip, split, Charge) for that check. A local order and a guest check are never selected at once.
- **Refresh:** on resume and with the card's Refresh button (no polling loop yet; SSE later).
- **Pure helper** `order/GuestOrders.chips()` turns the list into chips: one per open check, unpaid first, labelled with table and amount due.

### 17.2 RED tests first
- Backend `GuestOrdersIT`: a QR order shows with its table and due; POS-made orders and other locations' orders don't; orders older than 12 hours don't; 401 without a token.
- Android `GuestOrdersTest`: chip labels, paid checks hidden, order.
- **Plan change (found while exploring):** guest orders wait (`OPEN`) until staff accept them. So the list also returns waiting orders (`status: "WAITING"`, with `items: ["2× Burger (Cheddar)"]` and no checks), and the POS shows an **Accept** button that calls the existing `POST /api/v1/orders/{id}/commit`. This is the Slice 6/7 follow-up "POS screens for accepting guest orders".

### 17.3 Results (2026-11-26)
- **Already passing:** the 401 test (deny-by-default device filter); kept as a regression test.
- **Audit fixes (Medium):** order items load in one query for all orders, not one query per line; a check that fails to load (e.g. voided mid-request) is skipped and logged instead of failing the whole list.
- **Audit fixes (Low):** a 409 on Accept now says "may already be accepted, or something is sold out" (it was always "sold out"); a guest check that is paid or gone stops showing its bill after a refresh (never mid-charge); test added for a voided check.
- **Security review (manual):** the location comes only from the device token; each check's bill is computed with that location (`CheckBills` 404s otherwise); Accept reuses the existing location-checked commit endpoint; concurrent POS + guest QR payments on one check are serialised server-side (`CheckPaymentService` row lock + in-flight balance). No High or Medium issues left.
- **Verified on the Fold5:** a QR order (Burger with Cheddar, Medium; 2× Fries) showed as "T1 · waiting" with its items; Accept committed it; the chip read "T1 · $28.96"; tapping it showed the bill ($27.00 + $1.96 tax), tips, split and Charge.
- **Checks:** Android 131 tests + lint pass; `:backend:check`, GuestOrdersIT (6), GuestQrIT, EndpointLockdownIT pass.
- **Not covered / later:** live updates over SSE (now: on resume and Refresh); view-model tests for double accept and stale loads; per-check bill still costs a few queries each (fine at today's 50-order cap).

---

## 18. Slice 13 — live guest orders on the POS

Slice 12 lists guest QR orders, but only on resume or Refresh. A new QR order should show up by itself, and a check a guest pays by QR should drop off without a tap.

### 18.1 Decisions
- **Reuse the authenticated KDS stream.** Any enrolled device can already get a one-time ticket (`POST /api/v1/kds/stream-tickets`) and open `GET /api/v1/kds/stream`, scoped to its location. No new endpoint.
- **New `check` topic.** The Stripe webhook publishes `check` with only `{checkId}` the first time a payment moves to SUCCEEDED. It goes only to the authenticated stream, never the public 86 stream. No amounts or card data.
- **POS listener** `sync/LiveEvents`: get a ticket, open the stream with the Ktor SSE client, call back on `order`, `course` and `check` events, and remember the last event id. When the stream drops it waits (1 s, 2 s, 4 s … up to 30 s) and reconnects with a fresh ticket and `lastEventId`, so nothing is missed. After every (re)connect it refreshes once.
- **View model** runs the listener only while the app is on screen (started on resume, stopped on pause), and coalesces a burst of events into one `loadGuestOrders()`.

### 18.2 RED tests first
- Backend `PosLiveIT`: a POS device's stream hears `order` when a guest orders and `order` (status ACCEPTED) when staff accept it, and `check` (with only the check id) when the guest's payment succeeds; a repeated webhook does not publish again; the public stream never carries `check`.
- Android `LiveEventsTest` (Ktor MockEngine): only order/course/check reach the callback; a reconnect sends the last event id with a fresh ticket; back-off grows and caps at 30 s; a 401 on the ticket stops the listener.
