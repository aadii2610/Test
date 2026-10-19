# Tech Analysis — Send to Kitchen Post Payment (QSR)

**CR:** [CR: Send to Kitchen Post Payment](https://app.notion.com/p/aio-app/CR-Send-to-Kitchen-Post-Payment-3b25b13baaa38013a4fdecf2f73afb36)
**Status:** Pre-implementation analysis. No code changed.

---

## 1. Feature Overview

| Field | Value |
| --- | --- |
| **Jira Ticket** | *(pending)* |
| **Feature Name** | Send to Kitchen Post Payment (QSR) |
| **Module / Layer Affected** | **Cross-module.** UI + Domain (`TicketFragment`, `QsrTicketViewModel`), Data (queue handler, settings), external SDK (`aio.app.common:restaurant`), WebDash, and likely Backend. |
| **Tech Lead / Owner** | *(pending)* |
| **Estimated Complexity** | **L3** |
| **Initial Risk Level** | **High**. Changes when an order reaches the kitchen. A bug means orders never reach KDS, or are sent twice. |
| **Dependencies** | **External SDK:** `restaurant:0.0.32` must add the flag. **Backend/WebDash:** new setting, and confirmation of the `qsr-create` behavior in §2.1. **Internal:** `PaymentViewModel`, API queue. |
| **Target Release Date** | *(pending)* |

**Problem:** In QSR, tapping Card / Cash / Others sends the ticket to the kitchen before payment. Restaurants cannot choose to wait for payment.

**Expected behavior (from CR):**
- New WebDash > POS Settings toggle **"Send to Kitchen post payment"**, default **Off** (today's behavior).
- **On:**
  - Card / Cash / Others no longer send to the kitchen.
  - **Save & Send stays** (needed for phone orders).
  - When the ticket becomes **Paid** (single full payment, or partial payments through completion), the order is sent to KDS and the kitchen printer auto-prints.

**Success criteria:** With the toggle on, no kitchen send before Paid, exactly one send after Paid, and Off behaves exactly as today.

---

## 2. Existing Code Review

Paths below are under `pos/src/main/java/aio/app/pos/`.

### 2.1 How "send to kitchen" works today

- **The POS has no separate KDS call.** "Send" means posting the ticket to `POST /api/ticket/qsr-create/{id}` (`network/ApiService.kt:389`) with item status `send`. The backend then pushes to KDS and returns the kitchen print job over MQTT (`UpdateTicketRepositoryImpl.handlePrintKDSPrintReceipt`).
- **Save & Send:** `TicketFragment.kt:455` → `QsrTicketViewModel.validateButtonSendAndStayPayQsr` (~L687) → `postQsrTicketToServer(isForButtonSaveAndSend = true)` (L488) → queue task `SAVE_SEND_QSR` → `ApiQueueManagerRepoImpl.handleSaveAndSend` (L2839).
- **Payment buttons also send.** `TicketFragment.kt:3124` → `handlePaymentButtonClick` (`QsrTicketViewModel.kt:110`) → `btnPayLogicForQsr` → `validateButtonPayQsr` (L626) → `postQsrTicketToServer(isForButtonSaveAndSend = false)` → queue task `PAY_TICKET_QSR` → `handleQsrPay` (`ApiQueueManagerRepoImpl.kt:2751`).
  - `validateButtonPayQsr` reuses `isAllSendToKDS()` and `updateDbFirstQsr()`. It flips pending items to `send` in the same `qsr-create` request. **This is the implicit send-on-pay to remove.**
- **Existing similar flag:** `confirmSendToKdsOnPaymentQsr` (`BusinessIdUseCase.kt:558`) only shows a "send items to KDS and proceed?" dialog (`QsrTicketViewModel.kt:113`). It does not change what gets sent.

### 2.2 Where the ticket becomes Paid

- **The server decides Paid.** The POS only receives `paymentStatus` (`full-paid`, `partial-paid`, …; `BackendPaymentEnum`).
- **Four UI completion paths plus MQTT:**
  - Cash: `PayByCashFragment.processPaymentResponse` (L330)
  - Tender: `PayByTenderFragment` (L290)
  - Card (Stripe/Adyen): `PayByCardFragment.processPaymentResponse` (L478)
  - Gift card: `PayByGiftCardFragment`
  - Other devices: `UpdateTicketRepositoryImpl` (~L1300) emits `MqttEvent.PaymentDone`
- **Shared choke point:** the Success branch of `PaymentViewModel.authorisePayment` (`viewmodels/PaymentViewModel.kt:162–340`). It is shared with TSR, so any hook must be gated on QSR (`IS_QUICK_SERVE`).
- QSR has no split ticket (`btnSplitTicket` is hidden, `TicketFragment.kt:1240`).

### 2.3 Settings chain (template: `isStayQSREnabled`)

- Backend JSON → `RestaurantResponseDTO` → `RestaurantBO` → Room `RestaurantEntity` → `BusinessIdUseCase` (reads `aioRestaurant.appState.value.data?.restaurant?.…`).
- These classes live in the **external SDK `aio.app.common:restaurant:0.0.32`**, not in this repo. A new flag needs an SDK release (DTO, BO, Entity, mappers, DB migration) and a version bump in `pos/build.gradle`.
- Refresh is on onboarding / `onRestaurantByDeviceId`. There is **no MQTT settings push**, so a toggle change reaches the POS only on the next refresh.
- **Risk:** in 0.0.32, `confirmSendToKdsOnPaymentQsr` exists in the DTO and BO but not in `RestaurantEntity` or its mappers, so it may read `false` after loading from Room. The new flag must be checked end-to-end.

### 2.4 Concurrency, Data, Reliability (only what matters here)

- **Offline-first write:** `updateDbFirstQsr()` writes `send` to Room before the API call. The post-payment send must not leave Room saying `send` while the server never received it.
- **Payment calls are online API calls. Only the ticket create (`PAY_TICKET_QSR`) is queued.**
- **Failures:** `handleTaskFailure` (L2692) marks the task failed and resyncs. `whenQsrFailFallBackToPreviousState` (L3290) resets items to `pending`, retried up to 3 times (`MAX_RESET_TO_PENDING_ATTEMPTS`). There is no KDS-specific retry.
- **Double-send protection** is client-only: `DoubleTapCheckClickListener` and the `pending` → `send` check. There is no server dedupe.
- **Partially paid QSR tickets are locked in the UI** (`checkPartiallyPaidOrder`, `QsrMenuFragment.kt:1557`), so items can't be added after payment starts unless a refund/void unlocks the ticket (not verified).
- **Kitchen print result is not tracked by the POS**, because printing comes back over MQTT.
- **Tests:** none for send-to-kitchen or payment completion. The only unit tests are `ApiQueueRetryLimitTest`, `TicketMappersTest`, `MenuRefreshPocTest`, `ExampleUnitTest`.
- `TicketFragment` is ~4,500 lines.

---

## 3. Identified Issues Log

| # | Category | Issue | Severity | Risk Impact |
| --- | --- | --- | --- | --- |
| I-1 | Architecture | Send and Pay share one request path (`validateButtonPayQsr`, `isAllSendToKDS`, `updateDbFirstQsr`) and write `send` to Room before the call | High | Flag-on must skip the send without breaking payment or the ticket's local state |
| I-2 | Backend | Unknown whether `qsr-create` accepts a payment-initiated create with items left `pending`, and whether the backend can send to KDS after Paid, or the POS must call again | **Critical** | Blocks the design (§4) |
| I-3 | Data | New setting needs an external SDK release, DB migration and version bump; the existing similar flag may not persist in Room | High | Flag silently reads `false`; the feature never turns on |
| I-4 | Architecture | Paid is detected in 4 fragments plus MQTT. Only `PaymentViewModel` is a common point | High | A missed path means an order paid but never sent |
| I-5 | Concurrency | No server-side dedupe. Multiple devices/MQTT can both observe Paid | Medium | Duplicate kitchen tickets |
| I-6 | Reliability | No KDS-specific retry. A failed post-payment send is silent to staff | High | Paid order never reaches the kitchen |
| I-7 | Data | Settings refresh is on onboarding only (no push) | Low | Toggle change delayed on devices |
| I-8 | Test | No tests on send or payment completion | Medium | Regression risk |

---

## 4. Improvement Decision Matrix

| Issue | Fix now? | Justification | Backlog Ticket | Target Sprint |
| --- | --- | --- | --- | --- |
| I-1 Split send from pay when flag on | **Y** | Core of the CR. Skip the `pending` → `send` flip in `validateButtonPayQsr` when flag on and QSR | — | *[Sprint]* |
| I-2 Confirm backend contract | **Y (first)** | Must be answered before any POS work; may require a backend change | *[Ref]* | *[Sprint]* |
| I-3 SDK flag + migration | **Y** | Required; must be released and tested before POS work | *[Ref]* | *[Sprint]* |
| I-4 Single "Paid" hook | **Y** | Trigger the send from `PaymentViewModel` Success (QSR only); do not add per-fragment hooks | — | *[Sprint]* |
| I-5 Dedupe | **Y** | Only send when the ticket still has `pending` items; ask backend for idempotency | — | *[Sprint]* |
| I-6 Failure handling | **Y** | Reuse the queue retry; surface failure to staff and log to New Relic | — | *[Sprint]* |
| I-7 Settings push | **N** | Out of scope; document the delay | *[Ref]* | *[Sprint]* |
| I-8 Tests | **Y (scoped)** | Unit-test the decision "send on payment click?" and "send on Paid?" as pure functions | — | *[Sprint]* |

**Governance:** Flag Off must not change any code path. No issue is ignored without a ticket.

---

## 5. Impact Assessment

### 5.1 UI Impact
- No layout change. Save & Send stays (removal was dropped from scope).
- With the flag on, the "send to KDS and proceed?" dialog must not show, because nothing is sent at that point.
- Staff need a clear signal that the order goes to the kitchen after payment (optional; confirm with product).

### 5.2 API Contract Impact
- Likely **yes**: new setting field on the restaurant response. Possibly a new or changed `qsr-create` behavior (I-2).
- Versioning: older POS builds ignore the unknown field and keep today's behavior.

### 5.3 Database Impact
- New column on `RestaurantEntity` (in the SDK, DB version 32 → 33).
- Migration needed. No backfill: default is `false`.
- Rollback: harmless, since an unused column can stay.

### 5.4 Performance Impact
- Negligible. One boolean read per payment tap and one existing API call, moved to a different moment.

### 5.5 Backward Compatibility
- Default Off keeps current behavior on all devices.
- Mixed fleet: devices on older builds keep sending on tap, so an Off/On mismatch is possible until all devices update.
- Gradual rollout: toggle per restaurant from WebDash. No separate app feature flag needed.

---

## 6. Risk Assessment

| Risk | Probability | Impact | Mitigation |
| --- | --- | --- | --- |
| Paid order never reaches the kitchen (missed completion path or failed call) | Medium | **High** | One hook in `PaymentViewModel`; failure surfaced to staff; New Relic alert on "Paid, not sent" |
| Order sent twice (retry, MQTT, multi-device) | Medium | High | Send only if `pending` items remain; backend idempotency |
| Flag reads `false` because it isn't stored in Room | Medium | High | Verify Room persistence in SDK before POS work; add a test |
| Backend rejects payment-initiated create with `pending` items | Unknown | **Critical** | Confirm with backend first (I-2) |
| Partial payment already made, then ticket voided/refunded before Paid | Low | Medium | Agree behavior with product: no send until Paid |
| Toggle changed mid-shift | Medium | Low | Takes effect on next settings refresh; document it |

**Rollback strategy**
- Set the toggle Off in WebDash for the restaurant. That restores current behavior without an app release.
- If the POS change has a defect, halt the rollout or revert the PR. The SDK column can stay.

**Monitoring plan**
- New Relic events: "post-payment send triggered", "send success", "send failed", "duplicate suppressed".
- Alert on tickets Paid with items still `pending`.

---

## 7. Open Questions (blocking)

1. **Backend (I-2):** Does `qsr-create` accept a pay-initiated create with items left `pending`? After Paid, does the backend send to KDS by itself, or must the POS call again?
2. **Failure UX:** If the post-payment send fails, what should staff see?
3. **Void/refund:** If a partially paid ticket is voided before Paid, is anything sent?
4. **Timing:** For a partial payment, confirm nothing is sent until the ticket is fully Paid.
5. **Offline:** If the device is offline at the moment of Paid, is the send queued and retried later?
6. **Printers:** Is "kitchen printer auto print" fully backend-driven (MQTT), or does the POS also print locally?

---

## 8. Final Approval

| Role | Approval |
| --- | --- |
| Engineering Manager Approval | |
| Platform Lead Approval | |
| Squad Lead Approval | |
| Product Acknowledgment | |
| Date | |
