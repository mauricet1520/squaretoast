# Guest Online — online ordering, order & pay, loyalty, gift cards, receipts (plan, pending approval)

Status: plan only. No production code until the owner approves (CLAUDE.md, Explore -> Plan -> TDD).
Scope: everything a guest touches off the POS: `/web-kds-qr` guest pages and the public `/api/v1` guest endpoints.

## 1. What exists now

Web (`/web-kds-qr`, Preact + Vite, one bundle, router in `src/main.tsx`):
- `/t/{qrToken}` -> `guest/TableApp.tsx`: QR-visible menu, cart (`guest/cart.ts`), place order, list the table's checks, bill, receipt view after pay.
- `/c#{claim}` -> `guest/ClaimApp.tsx` + `guest/claim.ts`: pay a check signed by an offline POS.
- `guest/Pay.tsx`: `TipPicker` (percent chips or custom, integer cents), `CardForm` mounts Stripe's Payment Element (iframe from js.stripe.com); `payKey.ts` keeps one idempotency key per (scope, tip).
- Service worker only on the HTTPS cloud origin; strict CSP, no `innerHTML`.

Backend (`com.squaretoast.guest`, `com.squaretoast.payment`):
- `GuestController`: `GET /t/{qr}`, `POST /t/{qr}/orders`, `GET /t/{qr}/checks`, `GET /t/{qr}/checks/{id}`, `POST /t/{qr}/checks/{id}/payments` (method `QR_CNP`, `{idempotencyKey, tipCents}`; tip 0..$10,000).
- `GuestOrders.place`: server-priced lines, caps (qty 1–20, 30 lines, max waiting orders per table), order stays `OPEN` with courses on `HOLD` and **no inventory taken** until staff commit. **Channel is hard-coded `'DINE_IN'`**, one seat, one check ("Check 1"), allocations 1/1.
- `GuestOrdersController`: `GET /guest-orders` for the POS accept queue (device auth).
- `OfflineClaimController`: `POST /claims/payments` (public, ECDSA-signed claim, nonce, 2 h expiry); `ClaimReconciler` flags `MISMATCH`.
- `CheckBalance`/`CheckBills`: tax by `orders.fulfillment_channel` + `sold_on` (V7) + state rules; `service_charge.kind` already has `MANDATORY`, `AUTO_GRATUITY`, `DELIVERY_FEE`.
- `StripeConnectAdapter`: destination charges (`on_behalf_of`, `transfer_data[destination]`, `application_fee_amount`), `automatic_payment_methods[enabled]=true` for card-not-present.

Not present: any takeout/delivery entry point, pickup time, kitchen throttling, courier dispatch, guest identity, loyalty, gift cards, emailed/SMS receipts, open tabs (pre-auth), split-by-item on the guest side, wallet domain registration, rate limits at the edge (Slice 6 §11.4).

## 2. Gaps vs Toast and Square, ranked by impact

| # | Gap | Toast / Square today | Impact |
|---|---|---|---|
| 1 | **Online ordering for pickup/delivery** (web, no app, no commission) with scheduled orders | Toast OO: future orders, fire at time minus prep [T1][T2]; Square Online free plan, pickup + curbside, schedules and cut-offs [S1][S2] | Highest: table stakes; it is the channel that replaces 15–30% marketplace commissions [T5] |
| 2 | **Throttling / quote time** (kitchen capacity, prep-time delay, pause) | Toast quote-time strategies: kitchen capacity, order price, delay [T3] | Without it a rush of online orders sinks the dine-in line |
| 3 | **Delivery handoff** (DoorDash Drive, Uber Direct) | Toast Delivery Services, flat fee by distance via Uber Direct / DoorDash Drive [T4]; Square On-Demand Delivery [S2] | Delivery without a fleet; flat fee, no commission |
| 4 | **Wallets + split + open tab at the table** | Toast MOP: Apple/Google Pay, pay for my items or split evenly, open a tab with a card pre-auth [T6][T7] | Speed of table turns; the core "Toast depth" claim |
| 5 | **Digital receipts + marketing opt-in** | Toast: email receipt with separate opt-in checkbox; loyalty sign-up link on the receipt [T9] | Starts the guest list for 6–8; legally clean consent |
| 6 | **Guest profiles** (phone/email, order history, reorder) | Toast email audience built from OO, loyalty, receipts [T9] | Repeat orders, multi-location recognition |
| 7 | **Loyalty** | Square Loyalty $45–$105/mo per location [S4]; Toast Loyalty paid add-on [T8] | Free loyalty is a sharp price wedge |
| 8 | **Gift cards / eGift** | Square eGift: 2.5% load fee + processing [S3] | Revenue at holidays; free = wedge, but heavy compliance |
| 9 | **Accessibility proof** (WCAG 2.2 AA, ACR) | Food service is a top ADA web-suit target [A1] | Risk to merchants; a selling point if we ship an ACR |
| 10 | **Multi-location brand page** (pick a location, shared menu) | Toast and Square both support location pickers [T1][S1] | Needed for multi-location groups |

## 3. UX flows

F1 Online order (pickup), `/o/{locationSlug}`:
menu (only items `visible_online`, 86 state live from public `availability` SSE) -> cart -> "ASAP (ready ~25 min)" or "Schedule" (slots from the server) -> name + phone (+ email optional, receipt) -> opt-in checkbox, unticked -> pay (Payment Element + Express Checkout wallets, tip optional) -> status page (`Received / Preparing / Ready`), SMS/email when ready.
F2 Delivery: as F1 + address -> server quote (courier fee shown as its own line **before** pay, never inside tax) -> pay -> live courier status/tracking link from the courier webhook.
F3 Brand page `/o/{brandSlug}`: pick location (open now, distance), then F1.
F4 Table order & pay (existing `/t/{qr}`, extended):
- "Start a tab": guest card pre-auth (manual capture) so they can order rounds without paying each time; close = capture actual total + tip.
- "Pay": whole check, **my items** (pick lines -> fractional allocations), or **split evenly N ways**; wallet button first, card second.
F5 Receipt: after any guest pay, "Email / text me the receipt" + separate opt-in; receipt link `/r/{token}` works without login.
F6 Loyalty (later): phone number at pay -> points accrue on the pre-tip, pre-tax subtotal; reward redemption as a discount line before tax.
F7 Gift card (later): buy eGift at `/g/{brand}`; redeem at pay as a tender.
Speed budgets: menu first paint < 1.5 s on 4G mid-range Android, JS < 100 KB gz before Stripe loads; Stripe loads only on the pay step.

## 4. API and data changes

### 4.1 Tax by channel (required for F1/F2)
- New orders carry the guest's chosen channel: `TAKEOUT` for pickup, `DELIVERY` for courier, `DINE_IN` only from `/t/{qr}`. Never taken from the client for `/t` routes.
- `CheckBalance` already taxes by `fulfillment_channel` and `sold_on`; add tests that a takeout order uses the channel rule (and CA cold-food-to-go) and that **scheduled orders tax by the day the order is placed** (sale date), not the pickup day. Open question Q4 confirms.
- `DELIVERY_FEE` is a `service_charge` row; its taxability uses the existing `ServiceCharge.deliveryFeeTaxable` flag in `TaxEngine`; source it per state (new column on `state_compliance_profile` if not already loaded). Tips stay outside the taxable base. Courier tip passes through, separate from staff tip (Q3).

### 4.2 Migration V8 (sketch)
- `location`: `slug`, `online_enabled`, `pickup_enabled`, `delivery_enabled`, `prep_minutes`, `max_schedule_days`, `slot_minutes`, `slot_capacity_cents` and/or `slot_capacity_items`, `online_paused_until`.
- `menu_item.visible_online boolean`.
- `orders`: `source` (`POS`, `QR`, `ONLINE`), `promised_at timestamptz`, `fire_at timestamptz`, `guest_contact_id`, nullable `table_id` already allowed.
- `online_slot(location_id, slot_start, booked_cents, booked_items)` with a row lock per slot (same pattern as the per-table lock in `GuestOrders.place`).
- `delivery(id, order_id, provider DOORDASH|UBER, quote_id, quote_fee_cents, quote_expires_at, external_id, status, tracking_url)`.
- `guest_contact(id, merchant_id, phone_e164, email, email_opt_in_at, sms_opt_in_at, opt_out_at)` — consent timestamps, not booleans.
- `receipt_link(token_hash, check_id, expires_at)`.
- Later: `loyalty_account`, `loyalty_ledger` (integer points), `gift_card(id, code_hash, balance_cents, currency)`, `gift_card_ledger`.

### 4.3 Endpoints (`/api/v1`, public unless noted)
- `GET /o/{slug}` -> location, hours, channels, menu (online-visible).
- `GET /o/{slug}/slots?channel=&date=` -> slots with remaining capacity.
- `POST /o/{slug}/delivery-quotes {address}` -> `{quoteId, feeCents, etaMinutes, expiresAt}` (DoorDash quote lives 5 min, Uber 15 min [D1][U1]).
- `POST /o/{slug}/orders {orderId, channel, slot|asap, lines, contact, quoteId?}` -> priced order + check; slot booked under lock; order `COMMITTED` at pay success (webhook), which is when inventory is taken (CLAUDE.md §6: order commit). Unpaid holds expire after 15 min.
- `POST /o/{slug}/orders/{id}/payments {idempotencyKey, tipCents}` -> reuses `CheckPaymentService` with method `ONLINE_CNP`.
- `GET /o/{slug}/orders/{id}?t=` -> status (token scoped to the order, hashed at rest).
- `POST /t/{qr}/checks/{id}/split {mode: ITEMS|EVEN, lineIds|parts}` -> new child checks via `check_allocation` fractions (sum of numerators/denominators must equal 1 per line; remainder cents to the last part).
- `POST /t/{qr}/tabs` -> PaymentIntent `capture_method=manual` [P2]; `POST /t/{qr}/tabs/{id}/close {tipCents}` captures.
- `POST /checks/{id}/receipt {email|phone, optIn}` -> sends receipt; writes consent row.
- Device auth: `PUT /locations/{id}/online-settings`, `POST /locations/{id}/online-pause {minutes}`.
- Webhooks: `/webhooks/doordash`, `/webhooks/uber` (signature verified, stored in `webhook_event`, idempotent).
- Courier adapters live in `/backend/delivery-adapters` (port `DeliveryPort`), Jackson isolation as in `payment-adapters`.

### 4.4 Kitchen side
- Scheduled orders fire courses at `fire_at = promised_at - prep_minutes` (a scheduler moves HOLD -> FIRE through `DeviceOrderService.transitionCourse`), as Toast does [T2].
- KDS ticket shows `PICKUP 6:30 · Ana` or `DELIVERY · DoorDash`, plus a POS "Online" queue with accept/pause.

## 5. Thin TDD slices (each: RED tests first, commit, then minimal code)

- **G1 Takeout channel + tax.** `GuestOnlineTaxIT`: `takeoutOrderUsesTakeoutRule`, `caColdFoodToGoUntaxed`, `tipNeverTaxedOnOnlineOrder`, `qrTableOrderStaysDineIn`. `TaxEngineTest` vectors added to `testdata/tax-vectors.json` (Kotlin runs them too).
- **G2 Online menu.** `OnlineMenuIT`: `onlyVisibleOnlineItems`, `unknownSlug404`, `disabledLocation404`, `eightySixedItemShownSoldOut`. Web `onlineMenu.test.ts`: `rendersSoldOut`, `noPriceMathOnClient`.
- **G3 Place pickup order ASAP.** `OnlineOrderIT`: `serverPricesLines`, `idempotentOnOrderId`, `capsQtyAndLines`, `noInventoryUntilPaid`, `unpaidHoldExpiresAfter15Min`.
- **G4 Pay online + commit on webhook.** `OnlinePaymentIT`: `paysThroughCheckPaymentService`, `webhookCommitsAndDecrementsInventory`, `applicationFeeIsOnlySpread`, `noPlatformFeeLineOnDinerBill`.
- **G5 Scheduled orders + slots.** `OnlineSlotIT`: `slotBeyondMaxDaysRefused`, `fullSlotRefused`, `concurrentBookingsRespectCapacity`, `scheduledOrderTaxedBySaleDate`. `FireSchedulerTest`: `firesAtPromisedMinusPrep`, `pausedLocationRefusesAsap`.
- **G6 Throttle + pause from POS.** `OnlineThrottleIT`: `pauseBlocksNewOrders`, `delayAddsToQuote`, `otherLocationDeviceGets404`. Android `OnlineQueueViewModelTest`: `showsPromisedTime`, `pauseSendsMinutes`.
- **G7 Status page + ready notice.** `OrderStatusIT`: `tokenScopedToOrder`, `wrongToken404`, `readyEventPublished`. Web `status.test.ts`: `reconnectsWithLastSeq`.
- **G8 Wallets.** `PaymentMethodDomainTest` (adapter): `registersDomainOnPlatformForDestinationCharges` [P1]. Web `pay.test.ts`: `expressCheckoutShownFirst`, `cardFallbackStillWorks`.
- **G9 Split at the table.** `GuestSplitIT`: `payMyItemsCreatesChildCheck`, `evenSplitRemainderCentsToLast`, `allocationsSumToOne`, `cannotSplitPaidCheck`, `concurrentSplitAndPaySerialised`.
- **G10 Open tab (pre-auth).** `GuestTabIT`: `tabUsesManualCapture`, `closeCapturesTotalPlusTip`, `captureNeverExceedsAuthWithoutIncrement`, `expiringAuthAlertsStaff`.
- **G11 Digital receipts + consent.** `ReceiptIT`: `receiptLinkTokenHashed`, `optInUncheckedByDefault`, `consentStoredWithTimestamp`, `optOutHonoured`, `noCardDataInReceipt` (last4 + brand only).
- **G12 Delivery quote (DoorDash Drive).** `DoorDashAdapterTest`: `quoteMapsFeeToCents`, `expiredQuoteRefused`; `DeliveryQuoteIT`: `feeShownAsDeliveryFeeLine`, `deliveryFeeTaxPerState`.
- **G13 Dispatch + courier webhooks.** `CourierWebhookIT`: `badSignatureRejected`, `replayIgnored`, `statusAdvancesOrder`, `dispatchAfterPayOnly`. Then the Uber Direct adapter behind the same port: `UberAdapterTest` same names.
- **G14 Brand page (multi-location).** `BrandPageIT`: `listsOnlyOnlineLocations`, `closedLocationShownNotOrderable`.
- **G15 Guest profile + reorder.** `GuestContactIT`: `phoneNormalisedE164`, `contactScopedToMerchant`, `reorderRepricesAtToday`. Login by one-time SMS/email code (no passwords).
- **G16 Accessibility gate.** Web `a11y.test.ts` with axe-core on menu, cart, pay, receipt: `noSeriousViolations`; `keyboardOnlyCheckout`; `focusReturnsAfterDialog`; `tipChipsAreRadioGroup`. Publish an ACR (VPAT 2.5) [A1].
- **G17 Loyalty (points).** `LoyaltyIT`: `pointsOnPreTaxPreTipSubtotal`, `rewardIsDiscountBeforeTax`, `voidReversesPoints`, `noPointsOnGiftCardPurchase`.
- **G18 Gift cards.** Owner decision first (Q6). `GiftCardIT`: `codeStoredHashed`, `redeemLocksBalance`, `neverNegative`, `breakageNotRevenue`.

Each slice ends with the sonnet audit against CLAUDE.md and `/security-review` for G4, G8, G10, G11, G13, G15, G18.

## 6. Security and PCI

- Card data only in Stripe Payment Element / Express Checkout Element iframes; our pages never see PAN/CVV, keeping SAQ A-style scope for the web pages (POS stays SAQ C / MPoC). Log only `pi_…` ids and last4/brand from Stripe.
- Wallet domains: register each serving domain via the Payment Method Domains API on the **platform** account (destination charges, no `Stripe-Account` header) [P1].
- All amounts computed on the server; the client sends line ids, qty, option ids, tip cents only.
- Public endpoints: rate limits at the edge (per IP + per slug), plus server caps (open unpaid orders per contact/IP, quote calls per minute). Bot spam of scheduled slots is the main abuse; unpaid holds expire in 15 min.
- Status and receipt tokens: 32 random bytes, stored as SHA-256, in the fragment or a POST body where possible; never in logs.
- Courier and Stripe webhooks: verify signatures, store in `webhook_event`, process idempotently.
- PII: phone/email per merchant, encrypted at rest, deletable on request; marketing consent is opt-in, timestamped, separate from receipts and loyalty [T9]; SMS follows TCPA consent and STOP handling.
- Tab pre-auths: online card holds last 7 days on most networks, 5 days for some Visa cases [P2]; tabs must close same day or alert staff.
- Gift cards: codes hashed; card balances are a liability, not revenue; state escheat rules apply (Q6).
- CSP stays strict; allow only `js.stripe.com`, `*.stripe.com` frames, and our API origin.

## 7. Open questions for the owner

1. Online ordering domain: one shared host (`order.squaretoast.app/{slug}`) or merchant custom domains (each needs wallet domain registration)?
2. Who pays the courier fee: diner (shown as its own `DELIVERY_FEE` line before pay, pass-through at cost) or merchant? Any markup would be a platform fee to diners, which CLAUDE.md forbids — confirm zero markup.
3. Courier tip: pass 100% to the driver, separate from the staff tip pool? Show both?
4. Scheduled orders: tax by the order date (our default, matches `sold_on`) or by the pickup date? Ask a tax adviser for CA/NY.
5. Throttle unit: items per slot, dollars per slot, or both (Toast offers both [T3])?
6. Gift cards: build our own (stored-value ledger, escheat, breakage) or defer? Square charges 2.5% load [S3]; free is a wedge but heavy compliance.
7. Loyalty: free for all merchants? Points per dollar or per visit? Cross-location within a merchant?
8. Open tabs: offer at all, given the 7-day auth window and walkouts? Default pre-auth amount?
9. SMS: which provider, and who pays per-message cost if software is $0?
10. Accept guest orders automatically for online pickup (paid) while QR table orders still need staff accept?

## 8. Sources

- [T1] Toast, scheduled-order settings: https://doc.toasttab.com/doc/platformguide/adminSettingsForScheduledOrders.html
- [T2] Toast, firing online orders: https://doc.toasttab.com/doc/platformguide/adminFiringToastOnlineOrders.html and https://support.toasttab.com/article/Scheduling-Future-Orders
- [T3] Toast, quote time strategy (kitchen capacity, order price, delay): https://support.toasttab.com/article/Managing-Your-Quote-Time-Strategy
- [T4] Toast Delivery Services (Uber Direct / DoorDash Drive, flat fee): https://support.toasttab.com/en/article/Toast-Delivery-Services and https://pos.toasttab.com/integrations/uber-direct
- [T5] Toast, marketplace commissions: https://pos.toasttab.com/blog/food-delivery-fees-and-commissions
- [T6] Toast Mobile Order & Pay overview: https://support.toasttab.com/en/article/Mobile-Order-and-Pay-Overview
- [T7] Toast MOP split payments: https://support.toasttab.com/article/Split-Payments-with-Toast-Mobile-Order-Pay
- [T8] Toast Loyalty: https://pos.toasttab.com/products/loyalty and https://support.toasttab.com/en/article/How-Guests-Enroll-in-Toast-Loyalty
- [T9] Toast email marketing / receipt opt-in: https://support.toasttab.com/en/article/Toast-Marketing-FAQ
- [S1] Square Online ordering: https://squareup.com/us/en/online-ordering
- [S2] Square delivery (DoorDash / Uber couriers): https://square.com/us/en/online-ordering/delivery and https://squareup.com/help/us/en/article/8445
- [S3] Square gift card pricing: https://square.com/us/en/gift-cards/pricing
- [S4] Square Loyalty pricing: https://square.com/us/en/software/loyalty
- [D1] DoorDash Drive quotes and webhooks: https://developer.doordash.com/en-US/docs/drive/how_to/quote_deliveries and https://developer.doordash.com/docs/drive/reference/webhooks
- [U1] Uber Direct quotes and deliveries: https://developer.uber.com/docs/deliveries/direct/api/v1/post-eats-deliveries-estimates and https://developer.uber.com/docs/deliveries/direct/api/v1/post-eats-deliveries-orders
- [P1] Stripe payment method domains: https://docs.stripe.com/payments/payment-methods/pmd-registration and https://docs.stripe.com/elements/express-checkout-element
- [P2] Stripe place a hold (manual capture): https://docs.stripe.com/payments/place-a-hold-on-a-payment-method
- [A1] ADA Title III digital accessibility (ABA): https://www.americanbar.org/groups/business_law/resources/business-law-today/2025-august/digital-accessibility-under-title-iii-ada/
