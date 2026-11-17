# CLAUDE.md — SquareToast Monorepo Constitution

## Mission & Product Thesis
SquareToast is a **$0/month, hardware-agnostic, processor-portable restaurant POS** combining Square's contract-free BYOD accessibility with Toast's full-service restaurant depth.
- **Monetization:** Software (POS, Browser KDS, Guest QR Ordering, Split Checks, Coursing) is **100% free ($0/mo)** to the merchant. Platform revenue comes strictly from transparent Stripe Connect processing spreads (`application_fee_amount`), never hidden add-ons, junk fees, or early-termination penalties.
- Never introduce proprietary hardware lock-in, platform surcharges injected into diner tax lines, or single-processor dependencies.

## Repository Layout & Tech Stack
- `/backend`: Java 25 LTS, Spring Boot 4.0.x, Spring Framework 7.0, Tomcat (NO Undertow; dropped in SB4), JPA 3.2 / Hibernate 7.0, PostgreSQL, Flyway, Gradle (Kotlin DSL).
- `/android-pos`: Kotlin 2.2+, Jetpack Compose, Room (offline-first UUID outbox), Hilt, Stripe Terminal Android SDK (`stripeterminal-taptopay:6.0.0`, `stripeterminal-core:6.0.0`).
- `/web-kds-qr`: Zero-install Browser PWA (TypeScript/React or lightweight HTML/SSE) for multi-station KDS and Guest Table QR Ordering/Receipts.

## Non-Negotiable Architecture & Compliance Invariants
1. **Null Safety (Spring Boot 4):** Enforce JSpecify (`org.jspecify.annotations.NullMarked` in `package-info.java`, `@Nullable` for optional fields/returns). NEVER use deprecated `org.springframework.lang.*` null annotations. Enable Error Prone + NullAway compilation checks.
2. **Jackson 3 Isolation:** Spring Boot 4 defaults to Jackson 3 (`tools.jackson.*`). Isolate payment SDKs using Jackson 2 (`com.fasterxml.jackson.*`) inside `/backend/payment-adapters`.
3. **Money & Precision:** NEVER use `double` or `float` for currency. Represent all money as `long` integer minor units (cents) paired with ISO-4217 currency (`USD`).
4. **Tap to Pay on Android Boundaries:**
   - Declare `minSdk >= 26` (and enforce runtime Android 13 / API 33+ gating for Tap to Pay on Android).
   - Declare `android.permission.MODIFY_AUDIO_SETTINGS` in `AndroidManifest.xml`.
   - Tap to Pay on Android DOES NOT support offline mode or chip/swipe fallback. Gate Tap to Pay behind live connectivity + device attestation checks; fall back to QR Pay-at-Table (guest cellular) or paired Bluetooth M2/WisePad 3 reader when offline.
   - NEVER build custom PAN/CVV capture or DIY PIN screens. Keep all card/PIN flows inside Stripe Terminal SDK (PCI SAQ C / MPoC boundary). Never log or persist SAD/PAN data.
5. **Tip & Tax Compliance:**
   - FLSA Tip Credit: NEVER allow managers, supervisors, or 20%+ active owners in any tip pool. Block BOH staff from pools if `location.usesTipCredit == true`. Compute overtime on full minimum wage, not cash floor.
   - Card Fee Deduction from Tips: Hard-block in CA, ME, MA, DE. Where permitted, deduct ONLY the exact proportional card fee on the tip portion—never on food subtotal, never rounded up.
   - Sales Tax: Calculate dynamically by `item.taxCategory`, `order.fulfillmentChannel` (`DINE_IN`, `TAKEOUT`, `DELIVERY`), and `location.stateRules`. Keep voluntary tips non-taxable; model mandatory service charges separately.
6. **Atomic 86ing & Offline LAN Sync:**
   - Decrement inventory at order commit (not kitchen print). Broadcast updates over SSE (<100ms target).
   - Never rely on UPnP multicast routers. LAN sync between FOH Android and Browser KDS uses an embedded local WebSocket/SSE hub running inside a persistent Android `ForegroundService` (`WifiLock` + `WakeLock`) with mDNS discovery and a manual local IP/QR pairing fallback.

## Build & Verification Commands
- Backend check (compile + NullAway + JUnit): `./gradlew :backend:check`
- Android check (unit tests + lint): `./gradlew :android-pos:testDebugUnitTest :android-pos:lintDebug`
- Integration tests (Testcontainers): `./gradlew :backend:integrationTest`

## Engineering Workflow Rules
- Follow **Explore -> Plan -> TDD Implement**: Inspect files first; write NO production code until a written architecture plan is approved.
- Strict TDD: Write failing JUnit 5 / Kotlin tests first (no stubbed/fake core logic), confirm tests fail, commit tests, then write minimal code to pass without modifying test assertions.
- Run `/security-review` on every payment, webhook, or authentication change.

## Token & Cost Discipline (Mandatory)
1. **Run builds directly, delegate only verbose work:** run Gradle in the main session, always filtered to pass/fail + exact failure lines (e.g. `| grep -E "error:|warning:|FAILED|BUILD" | head -n 30`). Use a `haiku` subagent only for broad exploration or when a run's output would still be long after filtering (e.g. many failing Testcontainers tests). One `sonnet` subagent per finished slice audits against this file and returns only violations or "PASS".
2. **Surgical reads:** read target line ranges/classes only.
3. **Zero fluff:** no restated prompts or unchanged code; output diffs/new files only. Pipe builds: `./gradlew ... --console=plain -q | tail -n 30`.
4. **Slice hygiene:** stop after each slice and remind the user to `/compact` (or `/clear` when switching modules).
