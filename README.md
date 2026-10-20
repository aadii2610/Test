# Tech Analysis — Gift Card Visibility and Refund (POS)

**PRD:** [Gift Card Visibility and Refund](https://app.notion.com/p/aio-app/Gift-Card-Visibility-and-Refund-3b55b13baaa38021810cec88bc720544)
**Figma:** [POS Payments](https://www.figma.com/design/UOxJw6F6BPq7rIZU13qy0Q/POS-Payments?node-id=15385-80275)
**Scope of this doc:** POS (Android) only. MOM app, Backend, Reports, Online Ordering and Gift Cards ledger work is listed as dependencies.
**Status:** Pre-implementation analysis. No code changed.

---

## 1. Feature Overview

| Field | Value |
| --- | --- |
| **Jira Ticket** | *(pending)* |
| **Feature Name** | Gift Card Visibility and Refund |
| **Module / Layer Affected** | **Cross-module.** UI (Orders Hub, ticket pane, refund flow), Domain/Data (new gift card ticket model, refund API), Backend, MOM app, Reports, Receipts. |
| **Tech Lead / Owner** | *(pending)* |
| **Estimated Complexity** | **L4**. The client has no gift card ticket in Orders Hub, no gift card refund API, and most required fields are missing from the DTOs. |
| **Initial Risk Level** | **High**. Money movement (refund + balance zeroing) across two apps with no idempotency key today. |
| **Dependencies** | **Backend (critical):** gift card ticket in the orders list/search, card state fields, refund endpoint, atomic refund + deactivate, refund lock. **MOM app**, **Reports**, **Receipts**, **Online Ordering**. **Internal:** refund flow, `AllowedFeature` permissions. |
| **Target Release Date** | *(pending)* |

### Business Context

- **Problem:** A gift card sale has no guaranteed searchable ticket, and nothing stops a used card from being refunded. Risks are double liability (guest refunded, card still spendable) and unreconciled reports.
- **User impact:** Staff find a gift card purchase in Orders Hub, and refund it with guardrails, from POS or MOM.
- **Success criteria:** No refund of a used card, no double refund across POS/MOM, a refunded card is zeroed and deactivated in the same action, and Reports show the refund in the same period.

### PRD scope → what exists today (summary)

| PRD item | Today in POS | Gap |
| --- | --- | --- |
| Search gift card tickets in Orders Hub (order no., guest, card no.) | **Gift card tickets are not in Orders Hub.** They are backend objects (`POST /api/gift-card/ticket`), not in Room. Search is local and does not search card numbers. | New data source, new search field |
| Order source (POS/Online) and order type (Digital Gift Card) | Not found. Only `orderType = "gift-card"` is sent for card-paid purchases. | New fields and enums |
| "Refund Gift Card" action on ticket detail | **No gift card refund code.** `GiftCardActivity.onRefundClicked` has no callers. Ticket detail buttons fall back to "no buttons" for unknown types. | New action, new flow |
| Eligibility (unused card / same day / not legacy) | Not found: original balance, issue date, legacy flag, deactivated state | Backend fields needed |
| Per-card and per-reload refund; Partially Refunded / Refunded | Refund is amount-component based (subtotal, tax, tip…). Per-line refund is not supported. No distinct "Partially Refunded" badge. | New per-card flow |
| Mandatory reason | A reason exists but a default is pre-selected ("Bad food quality") and the list is food-oriented | Change reason UX for gift cards |
| Reuse original tender | Implicit: card goes to one API, everything else to another. No auto-match. | Needs a rule + server confirmation |
| Digital refund receipt to purchaser | Email receipt exists, address typed by hand. Purchaser email is in the model but unused. | Prefill + gift card receipt |
| Zero + deactivate; reloadable / blocked | Not found | Backend |
| Lock across POS/MOM | Not found. Client uses a cached ticket snapshot and no idempotency key. | Server lock + refresh |
| New permission "Refund Gift Card" | `AllowedFeature` model exists; no gift card refund entry | New `AuthEnum` + server feature |
| Reports | Server-side only | Backend |

---

## 2. Existing Code Review

Paths under `pos/src/main/java/aio/app/pos/` unless stated.

### 2.1 Architecture & Layering

- **Gift card sale is a separate flow, not a normal ticket.** `GiftCardActivity` (2,749 lines) → `GiftCardBillFragment` → payment through `PaymentParentFragment.handleGiftCardTicket` (L347) with `isFromGiftCard` / `giftCardTicketId` bundle args. The ticket is created via `POST /api/gift-card/ticket` and paid via `POST /api/payment/process-payment` (`type = "giftCardTicket"`). `PaymentParentFragment` notes it is "not stored in DB".
- **Orders Hub reads only Room `ticket_request_table`**, which has no gift card fields. `OrderHubTypesEnum` and `OrderHubSourceEnum` have no gift card or digital values. There is no "gift" string under `orderhub`.
- **Ticket detail actions** come from `TicketUtils.getStatusTypes` (~L140–245). Any type that is not third-party/delivery/takeout/dine-in falls to `else -> VOIDED` (L238, L242) and **shows no buttons**, so a gift card type needs an explicit branch.
- **Refund flow already exists** as six fragments under `ui/main/fragments/refund/`: `PaymentSelectionFragment` → `RefundAmountSelectionFragment` → `RefundReasonFragment` → `RefundConfirmationFragment` → `RefundCompleteFragment`, driven by `PaymentParentFragment.startRefundFlow` (L680–717). The repo document `REFUND_MAIN_FRAGMENT_NAVGRAPH_MIGRATION_SOLUTION.md` is **stale** (`RefundMainFragment` no longer exists).
- **Refund state** is plain `var`s in `RefundRepository` (Koin `single`), with no thread safety. The ticket is cached there when the flow starts.
- **Permissions:** `AllowedFeature` (Room) read via `loadAllowedFeatureByRoleAndFeatureName(roleId, AuthEnum.X.feature)`. `AuthEnum.REFUND_VISIBLE` exists; `GIFT_CARDS_VISIBLE` is defined but **never used**. Gift cards are gated only by `isGiftCardEnabled`. Manager PIN (`approve-with-pin`) sends only `restaurantId` and `pin`, with no feature name.
- **Duplication / size:** `PayByGiftCardFragment` 3,538 lines (builds the same purchase request in two places), `TsrTicketViewModel` 2,244 lines with ~10 gift card `SharedFlow`s, `RegisterActivity` 4,938, `OrdersHubParentActivity` 2,961. `BackendPaymentEnum` and `OrderHubPaymentEnum` are near-duplicates with different strings (`"refund"` vs `"full-refund"`).

### 2.2 Concurrency

- Refund is **online-only**, a direct API call with a 150 s timeout, no queue, no retry (`ApiService.kt:201–207`).
- **No in-flight guard** in the ViewModel. Protection is only hiding the confirm button and `setSafeOnClickListener`.
- **No idempotency key** in `RefundRequest` or `CashRefundPost`. On timeout the UI reverts after 3 s and the user can retry.
- **If the app is backgrounded**, `RefundSuccess` (SharedFlow, no replay, `repeatOnLifecycle(STARTED)`) can be lost and the UI stays on "processing".
- Orders Hub search loads and maps **all tickets on every keystroke** with no debounce (`OrdersHubSearchRepoImpl.kt:50–84`).

### 2.3 Data Layer

- **Present on the client:** `GiftCardValidateDataDTO` (id, cardNumber, amount, status, source, balance), `GiftCardSource` (inventory, migrated, physical, digital; `migrated` is the only plausible legacy marker), `GiftCardTicketItemRequestDTO(cardNumber, topupAmountInCents, giftCardId)`, `UserGiftCardPayment` (initial_amount, balance, status, recipient_*, purchased_by_*; attached only when a gift card is used as **tender**).
- **Not found:** issue date, legacy/cutover flag, delivery status, digital boolean, reloadable flag, deactivated state, original issued balance on the card DTO, order source and order type on gift tickets, any refund state or origin, any Room entity for gift cards.
- **No gift card refund, deactivate, reload-reversal or credit-back endpoint exists.** Gift card endpoints are `ApiService.kt:903–972`. The payment SDK (`aio.app.common:payment-core`, external) was not inspected for refund support.
- **No standalone reload-sale flow** found on the client (only `inventory` cards enter the bill; existing cards show Transfer only), although DTOs carry `topupAmountInCents` and `existingBalanceInCents`. PRD story "reloads refunded as separate lines" has no matching client model.
- **Digital sale and recipient email capture** was not found on the POS (`GiftCardSource.DIGITAL` only disables Transfer).
- **Refund endpoints today:** `POST api/payment/refund` (card only), `POST api/payment/refund/{ticketId}` (everything else, no card number in the payload), `GET api/payment/refund/{ticketId}`. When the original tender is a gift card, the server must credit the card.
- **Money types:** `Double` in DTOs, `String` in `RefundMetaData`; amounts are `roundToInt` of grand total × 100 (`RefundConfirmationViewModel.kt:221`). Calculator uses BigDecimal HALF_UP.
- `MqttTicketMapper.kt:293–294`: `refundedserviceChargeAmount` and `refundedGratuityAmount` are commented out, so those fields are dropped for payments arriving via MQTT.

### 2.4 UI & State Management

- **Refund eligibility today:** `onConfirmClicked` blocks a payment already `full-refund`, `partial-refund` or `refund-initiated`. **A partially refunded payment cannot be refunded again.** Full vs partial uses a 0.01 tolerance.
- **Reason:** 7 fixed radio options plus "Other", and `rBtnOne` ("Bad food quality") is `checked="true"` by default (`fragment_refund_reason.xml:92`), so the reason is **effectively pre-filled, not mandatory**. An empty custom reason is ignored. The PRD requires a mandatory reason.
- **Status badge:** `OrdersHubTicketAdapter.kt:297–306` shows one "Refund" badge for full and partial. The filter dialog distinguishes them.
- **Reason and refunding staff** are stored per `Refund` (`reason`, `refundedBy`, `approvedBy`) but shown only in payment-log history and on the printed receipt, **not on the ticket detail** (PRD requires them visible).
- **`approvedBy` is never populated:** `Constants.APPROVAL_USER_ID` is read in `RefundConfirmationViewModel.kt:214,386` and **no code writes it** (verified across `pos` and `commons`). The approver always equals the refunder.
- **Gift card lines** render as pseudo `TicketItem`s built from bundle args (card number in `itemDescription`), not from the DB (`TicketItemBillAdapter.kt:115`).
- **Side effect:** `cashRefund` always logs `CASH_REFUND` and emits `OpenCashDrawer` for **every non-card tender** (gift card, tender, mke, moto), so the drawer opens and a cash drawer entry is sent for a gift-card-tender refund (`RefundConfirmationViewModel.kt:389–396, 471`).
- **Already-refunded lock:** the refund flow caches the ticket in `RefundRepository.ticketData` when it starts and refreshes only on `DeliveryStatusUpdated`. A refund made on MOM mid-flow is not seen, and the "already refunded" check uses the stale snapshot (`RefundConfirmationViewModel.kt:113`). There is no `REFUND_*` MQTT event; payment changes arrive as `TICKET_PAYMENT_UPDATE` → `handlePaymentEvent`, and gift card tickets are not delivered that way.
- **Receipts:** printed refund receipt (`TicketReceiptMapper.refundReceipt`) has no gift card, purchaser or card info. Email receipt: `POST /api/payment/receipt/{paymentId}` (`receiptType = "refund"`, `refundId` required; address typed by hand), and a gift card variant `POST /api/payment/gift-card/receipt/{paymentId}`. `Payment.userGiftCardPayment.purchasedByEmail` exists but no refund code uses it.

### 2.5 Performance & Memory

- Search-on-keystroke over all tickets is the only notable cost, and it grows if gift card tickets are added to the same list. Add a debounce or move the search to a query.
- No new heavy payloads expected.

### 2.6 Reliability & Stability

- **Privacy / security (found while tracing):**
  - `TsrTicketViewModel.getGiftCardBalance` (~L2050) **logs the gift card number and PIN to New Relic.**
  - `ClockInViewModel.kt:122` puts the **raw manager PIN** into a New Relic log map.
- **Idempotency gap** (§2.2) and **stale snapshot** (§2.4) are the two main double-refund risks. Double-refund prevention today is server-side only (the `refund-initiated` state).
- Gift card tickets are not in Room and not updated by MQTT, so a refund done elsewhere is never reflected locally.

### 2.7 Test Coverage

- No tests for gift cards, Orders Hub, or refunds. Existing tests: `TicketMappersTest`, `ApiQueueRetryLimitTest`, `MenuRefreshPocTest`, example tests. `payment_calculator` has no refund tests.

---

## 3. Identified Issues Log

| # | Category | Issue | Severity | Risk Impact |
| --- | --- | --- | --- | --- |
| I-1 | Backend contract | No gift card ticket list/search, refund endpoint, card state fields (original balance, issue date, legacy, deactivated, reloadable), atomic refund + deactivate, or refund lock exists in the client contract | **Critical** | Blocks the design |
| I-2 | Money | No idempotency key and no in-flight guard on refund; stale cached ticket; refund can be retried after timeout | **Critical** | Double refund |
| I-3 | Architecture | Gift card tickets are not in Orders Hub/Room; search ignores card numbers; no gift card type/source enums | High | Core PRD story cannot work as-is |
| I-4 | Architecture | Per-card / per-reload refund does not exist; current refund is amount-component based and one refund per payment | High | PRD multi-card and reload scenarios unsupported |
| I-5 | Business rule | Reason is pre-selected and not mandatory; the options are food-specific | High | Audit requirement unmet |
| I-6 | Business rule | `approvedBy` never populated; reason/refunding staff not shown on ticket detail | Medium | Audit fields missing (PRD asks for both) |
| I-7 | Business rule | Gift-card-tender refunds open the cash drawer and write a cash drawer log | High | Wrong cash records |
| I-8 | Security | Gift card number + PIN and raw manager PIN logged to New Relic | High | Sensitive data exposure |
| I-9 | Data | Original tender "reuse" is implicit and server-decided; no auto-match | Medium | Wrong tender possible |
| I-10 | Permissions | No "Refund Gift Card" permission; `GIFT_CARDS_VISIBLE` unused; manager PIN is not feature-scoped | Medium | Cannot restrict by role |
| I-11 | Reliability | `RefundSuccess` lost on background; UI stuck on "processing" | Medium | Staff retry and double refund |
| I-12 | UI | `getStatusTypes` falls to no buttons for unknown types; "Refund" badge for full and partial | Medium | No action on gift card tickets; unclear status |
| I-13 | Data | Purchaser email unused; email typed by hand; MQTT drops `refundedserviceChargeAmount` / `refundedGratuityAmount` | Low | Receipt friction; missing refund fields |
| I-14 | Architecture | Duplicate payment enums; gift card purchase request built in two places; stale refund doc | Low | Maintenance |
| I-15 | Performance | Search over all tickets per keystroke, no debounce | Low | Lag as the list grows |
| I-16 | Test | No tests on gift cards or refunds | Medium | Regression risk on money code |

---

## 4. Improvement Decision Matrix

| Issue | Fix now? | Justification | Backlog Ticket | Target Sprint |
| --- | --- | --- | --- | --- |
| I-1 Backend contract | **Y (first)** | Everything depends on it. Agree endpoint and fields with Backend/MOM first | *[Ref]* | *[Sprint]* |
| I-2 Idempotency / double refund | **Y** | Governance: money and data risk → must fix. Add an idempotency key (needs backend), an in-flight guard, and refresh the ticket before submit | — | *[Sprint]* |
| I-3 Gift card tickets in Orders Hub | **Y** | Required. Either the backend returns them in the orders feed (preferred) or a new gift card orders API; add card-number search | — | *[Sprint]* |
| I-4 Per-card refund flow | **Y** | New, small flow for gift cards: select card or reload line → reason → confirm. Do not extend the amount-component flow | — | *[Sprint]* |
| I-5 Mandatory reason | **Y** | Gift card flow: no default selection; a gift-card-appropriate reason list | — | *[Sprint]* |
| I-6 Audit fields | **Y** | Show reason and refunding staff on ticket detail; populate `approvedBy` for gift card refunds | *[Ref]* (general refund flow) | *[Sprint]* |
| I-7 Cash drawer on non-cash refund | **Y (gift card path only)** | Do not open the drawer or log cash for gift card refunds. General refund fix goes to backlog | *[Ref]* | *[Sprint]* |
| I-8 Sensitive logging | **Y** | Remove card number, PIN and manager PIN from logs. Quick, high value | — | *[Sprint]* |
| I-9 Tender reuse | **Y** | Auto-select the original tender and disallow change. Confirm the server rule | — | *[Sprint]* |
| I-10 Permission | **Y** | New `AuthEnum` entry; backend sends it in `userFeatureDtos` | *[Ref]* | *[Sprint]* |
| I-11 Lost `RefundSuccess` | **Y** | Use replay-safe state for the gift card flow | — | *[Sprint]* |
| I-12 Buttons / badge | **Y** | Explicit gift card branch in `getStatusTypes`; "Partially Refunded" badge | — | *[Sprint]* |
| I-13 Receipt / MQTT fields | **Y (receipt only)** | Prefill purchaser email; use the gift card receipt endpoint. MQTT field fix to backlog | *[Ref]* | *[Sprint]* |
| I-14 Duplicates / stale doc | **N** | Out of scope. Mark the stale doc as outdated | *[Ref]* | *[Sprint]* |
| I-15 Search debounce | **Y** | Same code we touch; small change | — | *[Sprint]* |
| I-16 Tests | **Y (scoped)** | Unit tests for eligibility rules (unused / same-day / legacy / already refunded) and ticket-state calculation (partially refunded vs refunded) | *[Ref]* | *[Sprint]* |

**Governance:** Money and sensitive-data issues are fixed in this iteration. Architectural exceptions need explicit approval. Nothing is ignored without a ticket.

---

## 5. Impact Assessment

### 5.1 UI Impact
- **Orders Hub:** gift card tickets appear in the list; search by card number; gift card badges (source, type, Refunded / Partially Refunded); refund reason and refunding staff on the detail pane.
- **Ticket detail:** "Refund Gift Card" action with a visible disabled reason ("card already used", "already refunded"); legacy cards show no action.
- **New flow:** pick card/reload → reason (mandatory) → confirm → complete (with receipt).
- **Navigation:** reuse the existing refund child-fragment host; no new graph destination.

### 5.2 API Contract Impact
- **Yes, large.** Gift card ticket list/detail with card state (original balance, current balance, issue date, legacy, type, deactivated, reloadable, delivery status, order source, order type), a gift card refund endpoint (atomic zero + deactivate + tender reversal, idempotency key, per-card/reload lines), a refund-lock signal, and receipt support. Older POS builds ignore new fields.

### 5.3 Database Impact
- Depends on option chosen in I-3. If gift card tickets are returned in the orders feed they need new `ticket_request_table` columns (migration). If a separate API is used, **no DB change** (in-memory/API only). Rollback: unused columns can stay.

### 5.4 Performance Impact
- Low. Add debounce/query for search. Gift card tickets add rows to Orders Hub; keep pagination.

### 5.5 Backward Compatibility
- **Feature flag:** recommended. Gate by the new "Refund Gift Card" permission and a backend flag per restaurant.
- **Mixed fleet:** older POS builds show no gift card tickets and no refund action (current behavior).
- **Legacy cards:** no action (PRD out of scope); the legacy marker must come from the backend.

---

## 6. Risk Assessment

| Risk | Probability | Impact | Mitigation |
| --- | --- | --- | --- |
| Double refund across POS and MOM or by retry | Medium | **High** | Server-side lock + idempotency key; refresh ticket before confirm; in-flight guard |
| Used card refunded (balance/same-day rule wrong) | Medium | **High** | Server enforces the rule; client only displays and disables; pure-function tests |
| Refund to the wrong tender | Low | High | Auto-select original tender; no override |
| Backend contract not ready | High | **High** | Agree contract first (I-1); POS starts after |
| Refund succeeded but POS shows failure (timeout/background) | Medium | High | Refresh by ticket id after a timeout; replay-safe success state |
| Gift-card-tender refund opens drawer / wrong cash log | High | Medium | Gift card path skips cash drawer (I-7) |
| Sensitive data in logs | High | Medium | Remove now (I-8) |
| Ticket refunded on MOM not visible on POS until reopened | High | Medium | Fetch ticket state on open and before submit |
| Scope growth (reload refund, multi-card UX) | Medium | Medium | Confirm in Open Questions |

**Rollback strategy**
- Disable the feature flag / "Refund Gift Card" permission to hide the action with no app release.
- If the POS change has a defect, halt rollout or revert the PR. Gift card state changes made by the backend cannot be undone by a client rollback.

**Monitoring plan**
- New Relic events: gift card ticket opened, refund started / success / failed (with ticket id and card id, never card number or PIN), refund blocked (reason), duplicate suppressed.
- Alert on a refund request without a matching completion, and on any `refund-initiated` ticket older than the timeout.

---

## 7. Open Questions (blocking)

1. **Backend:** Which endpoint returns gift card purchase tickets for Orders Hub and MOM? Is the ticket the same object as a normal ticket? Which fields are provided (original balance, issue date, legacy, type, delivery status, order source/type)?
2. **Eligibility rules conflict in the PRD.** Story 1 says the action is enabled when balance < original ("user will select the card to refund") and also when the refund is the same day; Story 2 (MOM) says it is hidden with "card already used" after the purchase date. **What is the final rule?** Where is it enforced (server preferred)?
3. **"Same calendar day":** In which time zone, and from which timestamp?
4. **Reload refunds:** The PRD says only the reloaded amount is refunded. How is a reload represented as a separate refundable line? The POS has no reload-sale flow today.
5. **Partial refund and partial usage:** Can a card be refunded for the remaining balance only? Today one refund per payment is allowed.
6. **Original tender = gift card:** To which card is value returned (the same card)? Which endpoint? Is the refund of a gift-card-tender purchase also a "refund of a gift card purchase"?
7. **Cash refunds on POS:** Does the cash drawer open for the cash-purchase refund? How is it logged?
8. **Physical reload after refund:** Is a "reloadable" flag displayed on the POS, or is it backend-only? Does the existing reload flow need to accept refunded cards?
9. **Digital card + recipient email:** Where is recipient email captured on POS today? (No digital sale or email capture was found in the POS code.)
10. **Lock:** How does POS learn that MOM refunded? Is there an MQTT event, or only a fetch on open?
11. **Permission:** Name and default roles for "Refund Gift Card"; does the manager approval PIN apply?
12. **Receipts:** Printed refund receipt content for a gift card; email goes to the purchaser's address from the original payment?
13. **"Order Type = Digital Gift Card" and "Order Source = POS/Online"** values: confirm the exact backend strings.

---

## 8. Final Approval

| Role | Approval |
| --- | --- |
| Engineering Manager Approval | |
| Platform Lead Approval | |
| Squad Lead Approval | |
| Product Acknowledgment | |
| Date | |
