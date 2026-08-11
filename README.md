# Tech Analysis — Gift Card Features (4 CRs Bundle)
**Scope:** Implementation of 4 interconnected Gift Card CRs: (1) Gift Card Reload on POS, (2) Gift Card Merge on POS, (3) Gift Card Migration to Digital, and (4) Digital Gift Card Purchase on POS.

**Status:** Pre-implementation technical analysis. No code has been changed for these CRs yet.

---

## 1. Feature Overview

| Field | Value |
| --- | --- |
| **Jira Tickets** | POS-XXXX (Reload), POS-XXXX (Merge), POS-XXXX (Migration), POS-XXXX (Digital Purchase) |
| **Feature Names** | (1) Gift Card Reload on POS, (2) Gift Card Merge on POS, (3) Gift Card Migration to Digital, (4) Digital Gift Card Purchase on POS |
| **Module / Layer Affected** | **UI:** (`GiftCardsMainFragment`, `GiftCardScannedFragment`, `GiftCardScannedContainerFragment`, new dialogs for reload/merge/migration/purchase). **Domain/Data:** (`TsrTicketViewModel`, `TsrRepository`, new API endpoints, gift card data models, e-card API integration). **Database:** New Room entities for gift card transactions, settings, and merge tracking. **Navigation:** Extended Navigation Component graph for gift card sub-flows. |
| **Tech Lead / Owner** | Adeel Nawaz |
| **Estimated Complexity** | **L3–L4** (Multiple new API contracts, new UI flows with nested dialogs, stateful operations, 3+ new domain entities, cross-feature discount integration, e-card API dependency, Report impacts, payment integration with commission splits) |
| **Initial Risk Level** | **High** (High-value feature affecting financial transactions; gift card balance mutations impact reports; e-card API dependency with no local fallback; payment commission split logic interacts with existing discount/taxation system; multiple sequential API calls in reload/merge/purchase with no rollback mechanism) |
| **Dependencies** | **Internal:** `GiftCardViewModel` (existing lightweight state holder), `TsrTicketViewModel` (API orchestration), `PaymentViewModel` (payment method selection & processing), gift card settings (cached in `SharedDataRepository`), discount system (`DiscountType`, `TicketDiscount`), report entities (Sales Summary, Gift Card, Checkout, Z-report). **External:** e-card API (fetch, reload, merge, digital-issue endpoints), payment processor (for card commission splits), customer detail validation (US phone/email). |
| **Target Release Date** | *(pending)* |

### Summary

These 4 CRs form a cohesive **gift card management suite** for POS:
- **Reload** (CR-1): Top-up existing physical gift cards with payment.
- **Merge** (CR-2): Combine balances from multiple cards (physical/digital) into one.
- **Migration to Digital** (CR-3): Transfer physical gift card balance to an AIO digital gift card sent via email/SMS.
- **Digital Purchase** (CR-4): Sell a new AIO digital gift card (self-purchase or as a gift, design selection, custom amounts).

**Architectural Shape:**
- All four flows are **sub-screens within the GiftCardActivity** (already an isolated, feature-gated activity per the code review above).
- They share a common **card-fetch pattern** (barcode scan or manual entry via `GiftCardNumberDialog`).
- They use a **shared set of new API endpoints** via `TsrRepository` (reload, merge, transfer-to-digital, digital-purchase).
- They interact with an **external e-card API** (Toast/Cake → AIO digital card bridge).
- They integrate with the **existing payment flow** (`PaymentViewModel`) for settlement, with special handling for **gift card purchase commission splits** and **disabled-partial-payment enforcement**.
- All four flows impact **financial reports** (Sales Summary, Gift Card report, Checkout report, Z-report) — report generation logic must be updated to classify and filter these transactions separately.
- There is **no dedicated data model today** for these operations; they're new concepts. Reload, Merge, Migration, and Digital Purchase each need their own **TTransaction record** (or a unified `GiftCardTransaction` enum) and **audit trail**.

---

## 2. Business Context

### 2.1 Current State vs. Target State

| Operation | Current (Today) | Target (After CR) | Impact |
| --- | --- | --- | --- |
| **Reload** | Online ordering only (customer-driven) | Online + **POS (operator-driven)** | Offline access, faster checkout, restaurant control |
| **Merge** | Manual customer inquiry, offline process | **POS UI-driven**, real-time balance consolidation | Self-service, instant result, reduced support tickets |
| **Transfer to Digital** | Manual customer request via online | **POS UI-driven**, operator-facilitated** | Reduces friction, enables in-location conversion |
| **Digital Purchase** | Online ordering only | Online + **POS**, design gallery, custom amounts | Improves impulse purchase, cross-sell opportunity |

### 2.2 Success Metrics (Product Intent)
- **Reload:** Enable operators to handle gift card top-ups at the register, reducing customer friction and enabling offline scenarios.
- **Merge:** Reduce customer support burden by allowing self-service consolidation of multi-card loyalty scenarios.
- **Migration:** Bridge Toast/Cake physical-only customers to AIO's digital ecosystem.
- **Digital Purchase:** Enable in-location impulse gift card sales (e.g., "give a gift on the spot").

---

## 3. Existing Code Review (Mandatory Pre-Implementation Step)

### 3.1 Current Gift Card Architecture Review

#### 3.1.1 UI Layer Structure
- **Entry Point:** `GiftCardActivity` (isolated feature gate, `@AndroidEntryPoint` Hilt, full lifecycle isolation from main ticket flow).
- **Navigation:** Uses **Navigation Component** with a `NavHostFragment` (`giftCardNavHostFragment`, `res/navigation/gift_card_navigation.xml`).
  - Current destinations: `giftCardsMainFragment` (entry/main menu), `giftCardScannedContainer` (scanned card list, item-detail + payment).
  - No deep-link or external-source routing to gift cards today.
- **Fragments (existing):**
  - `GiftCardsMainFragment` (barcode scan entry, manual-entry dialog trigger, main actions).
  - `GiftCardScannedContainerFragment` (card list, item-level actions: reload/transfer/delete).
  - `GiftCardScannedFragment` (card detail display, read-only today).
  - `GiftCardBillFragment` (bill/summary for payment).
  - `PayByGiftCardFragment` (gift card as payment method in main ticket flow — out of scope for these CRs).
- **State Management:**
  - `GiftCardViewModel` (lightweight, holds: `giftCards: List<GiftCardBillItem>`, `discountType`, `discountAmount`).
  - `TsrTicketViewModel` (heavyweight orchestrator: holds all SharedFlow response channels for gift card operations — `giftCardValidateResponse`, `giftCardTicketResponse`, `giftCardTicketUpdateResponse`, `giftCardTransferBalanceResponse`, etc.).
  - **No SavedStateHandle or process-death resilience** — state is lost on process kill mid-flow.
  - **No ViewModel-level encapsulation of gift card sub-operations** (reload/merge/migration/digital-purchase) — each will be a bare coroutine launch from the Fragment with a callback.

#### 3.1.2 Data Layer Review
- **API Contracts (Existing):**
  - `validateGiftCard(cardNumber: String)` → `GiftCardValidateResponse` (fetch card info, balance, status).
  - `createGiftCardTicket(request: GiftCardTicketRequest)` → `GiftCardTicketResponse` (start a gift card "basket").
  - `updateGiftCardTicket(request: GiftCardTicketUpdateRequest)` → `GiftCardTicketUpdateResponse` (add items to basket, apply discount).
  - `deleteGiftCardTicket(ticketId: Int)` → `GiftCardTicketDeleteResponse` (discard basket, no payment).
  - `getCurrentGiftCardTicket()` → `GiftCardCurrentTicketResponse` (resume interrupted flow).
  - `transferGiftCardBalance(request: GiftCardTransferBalanceRequest)` → `GiftCardTransferBalanceResponse` (Toast/Cake to physical AIO).
  - `validateGiftCardPin(cardNumber: String, pin: String)` → `GiftCardPinValidateResponse` (PIN verification for redemption).
- **New API Contracts (Required by CRs):**
  - `reloadGiftCard(request: GiftCardReloadRequest)` → response with new balance, transaction ID (CR-1).
  - `mergeGiftCards(request: GiftCardMergeRequest)` → response with parent card's new balance, merge transaction ID, marketing event (CR-2).
  - `transferToDigitalGiftCard(request: TransferToDigitalRequest)` → e-card API call, response with digital card number/PIN (CR-3).
  - `purchaseDigitalGiftCard(request: DigitalPurchaseRequest)` → e-card API call, response with card details, initiate delivery (CR-4).
  - `processGiftCardPayment(request: PaymentRequest)` → integrate with existing payment system (all CRs).
- **Room Entities (Current):**
  - No local gift card table — gift cards are entirely server-sourced, validated on first touch, and discarded after payment. No offline-cached gift card state.
- **New Room Entities (Required):**
  - `GiftCardTransactionEntity` (for audit/reporting): `id`, `cardNumber`, `transactionType` (RELOAD/MERGE/TRANSFER_DIGITAL/PURCHASE), `amount`, `timestamp`, `status`, `ticketId`.
  - Possibly: `GiftCardMergeLogEntity` (track which cards merged into which, for undo/support scenarios).
  - Z-report integration (sales-summary report rows for gift card transactions, separate from item sales).

#### 3.1.3 Payment Integration
- **Existing Payment Layer (`PaymentViewModel`):**
  - Handles full ticket payment via multiple methods (Card, Cash, Tenders, Gift Card, Manual Card Entry).
  - Supports **split payments** (multi-method on one ticket).
  - Applies **discount logic** (percentage or dollar, already computed in `MainViewModel`).
  - Applies **commission splits** for card payments (POS-configured per business).
  - **Special requirement for gift card CRs:** Payment for gift card operations is **full-payment-only** (no splits), and card commission splits are **applied to gift card reload/purchase** but **not to merge or migration** (no new money changes hands).
- **Discount Interaction:**
  - Gift card purchase discount *should apply* to reload and purchase (per PR user story).
  - Discount *should not apply* to merge (no payment) or migration (balance transfer, no new money).
- **Commission Split Interaction:**
  - When a card payment is used for gift card reload/purchase, the configured commission split (e.g., 2.5%) applies to the reload/purchase amount, **not to the base item in the ticket**.
  - This is a **new kind of transaction** for the payment processor — flagged as `transactionType = "GIFT_CARD_RELOAD"` or similar.

#### 3.1.4 Report Impacts
The CRs mention: "The gift card reload impacts the following reports: Sales Summary report, Gift Card report, Checkout report, and Z-report."
- **Current Report Layer:** (High-level understanding from code review)
  - Z-report, Sales Summary, and Checkout reports are generated from `TicketItem` entries and their status.
  - There is a "Gift Card report" module (location not traced in this pass, likely separate from the main `TicketItem` model).
- **New Impacts:**
  - **Reload:** A reload is payment for an amount added to a card; it should show as a separate line in Sales Summary (distinct from item sales), and also in the Gift Card report as a reload transaction.
  - **Merge:** No revenue impact (balance consolidation), but should log in Gift Card report for audit.
  - **Migration:** No revenue impact, but audit trail in Gift Card report.
  - **Digital Purchase:** Same as reload — a sale of a digital gift card design, distinct from item sales, both in Sales Summary and Gift Card report.
- **Outstanding Question:** Are these transactions added as pseudo-`TicketItem` entries with a special `itemType = "GIFT_CARD"`, or as separate `GiftCardTransaction` records outside the `TicketItem` hierarchy? Answer determines report query shape and will impact the scope of report-generation code changes.

#### 3.1.5 E-Card API Integration
- **New Dependency:** `e-card API` (Toast/Cake bridge for digital card issuance).
- **Current Code:** No e-card API client in the codebase today — this is a **net-new integration**.
- **Requirements:**
  - Fetch digital card number / PIN from e-card system.
  - Trigger email/SMS delivery of card details.
  - No local fallback if e-card API is unavailable — the entire transfer-to-digital and digital-purchase flows are blocked.
  - Idempotency: re-running the same purchase/transfer request should not double-issue a card — need a unique `idempotencyKey` or deduplication logic.
- **Risk Flag:** High dependency on external API with unknown SLA/uptime — reload and merge work offline (no e-card dependency), but migration and digital-purchase are fully coupled to e-card availability.

#### 3.1.6 Customer Detail Capture
- **Required for Migration & Digital Purchase (CRs 3 & 4):**
  - Name, Phone, Email (for digital card delivery).
  - Email and phone have standard validations (regex for US format).
  - **CFD (Customer-Facing Device) Integration:** User stories mention "Switch to CFD" — allow customer to enter details on a secondary screen (e.g., kiosk, tablet). This is a **multi-screen, coordinated-input flow** not present in the current gift card module.
  - **Current precedent:** TSR/QSR register has basic CFD support for other flows (not reviewed in detail); migration/digital-purchase need to **reuse that pattern**, not invent a new one.

### 3.2 Concurrency & Coroutine Audit

#### 3.2.1 Current Patterns in GiftCardActivity & Fragments
- **GiftCardActivity.onCreate():** Already manages multiple `lifecycleScope.launch { ... }` blocks for observing `currentGiftCardTicket`, `giftCardValidateResponse`, etc. Uses standard `flowWithLifecycle` pattern.
- **Sequential vs. Concurrent Operations:**
  - **Existing:** `getCurrentGiftCardTicket()` → observe response → if has items, navigate to scanned container. All sequential, no race.
  - **New (Reload/Merge/Migration/Purchase):** Each operation involves **multiple sequential API calls** (e.g., reload: validate card → fetch current ticket → call reload API → process payment → update ticket). **No atomic transaction** at the database level — if the app crashes mid-sequence, the ticket state and payment state can diverge.
- **Current Debounce/Guards:** No visible debounce on any gift card operations today. Double-tapping a "Reload" button could fire two concurrent reload API calls.

#### 3.2.2 New Concurrency Concerns
- **Reload:** Validate → Fetch Ticket → Call Reload API → Process Payment → Update Ticket. If crash at step 3 (after payment, before ticket update), the backend has charged the customer but the local ticket is stale. **Recovery path unclear.**
- **Merge:** Fetch Card 1 → Fetch Card 2 → ... → Fetch Card N → Call Merge API → Update Parent Card → Delete Child Cards (locally). **Delete-after-update race:** if an update succeeds but delete fails, the UI shows merged but the backend still has the child card as separate. **Rollback mechanism needed.**
- **Migration & Digital Purchase:** Call e-card API (external) → Process Payment (internal) → Update Ticket (local). **e-card API failure after payment:** customer charged, card not issued. **Idempotency & retry logic mandatory.**

#### 3.2.3 Dispatcher Affinity
- **Existing Code:** `TsrTicketViewModel` uses `launch(IO)` for all repository calls (e.g., `GiftCardActivity.kt:549` in the read-above shows `lifecycleScope.launch { ... tsrTicketViewModel.giftCardTicketDeleteResponse...}`). This is correct.
- **New Code:** Reload/Merge/Migration/Purchase will similarly call `tsrRepository.reloadGiftCard(...)`, etc. via `launch(IO)` from the ViewModel.
- **Potential Issue:** The **payment processing** (inside the reload/purchase flow) may have its own dispatcher affinity (e.g., `launch(IO)` inside PaymentViewModel). **Double-wrapping IO dispatchers is benign** but adds latency; verify no cross-dispatcher deadlocks in a chain like `launch(IO) { viewModel.reload { payment.process { ... } } }`.

### 3.3 Data Layer & API Design Review

#### 3.3.1 Reload Request/Response Models
**Required Models (new):**
```kotlin
data class GiftCardReloadRequest(
    val cardNumber: String,
    val reloadAmountInCents: Int,
    val ticketId: Int?,
    val discountPercentage: Float? = null,
    val discountAmountInCents: Int? = null
)

data class GiftCardReloadResponse(
    val success: Boolean,
    val message: String?,
    val newBalance: Long?, // in cents
    val transactionId: String?,
    val timestamp: Long?
)
```
**Integration Points:**
- Discount is already computed in `GiftCardViewModel` (from user input in reload modal).
- Amount is in cents to match existing gift card API pattern (see `GiftCardCurrentTicketItem.topupAmountInCents`).
- `transactionId` is for idempotency & audit (will be stored in `GiftCardTransactionEntity`).

#### 3.3.2 Merge Request/Response Models
**Required Models (new):**
```kotlin
data class GiftCardMergeRequest(
    val parentCardNumber: String,
    val childCardNumbers: List<String>,
    val ticketId: Int?
)

data class GiftCardMergeResponse(
    val success: Boolean,
    val message: String?,
    val parentNewBalance: Long?, // in cents
    val mergeTransactionId: String?,
    val marketingEvent: Map<String, Any>? // for marketing module consumption
)
```
**Integration Points:**
- Marketing module consumes the `marketingEvent` to update ledger (per PR user story 7).
- Parent card is the one remaining active; child cards are marked as merged/inactive on the backend.
- No local database delete (rely on backend to deactivate); only UI refresh needed.

#### 3.3.3 Transfer to Digital Request/Response Models
**Required Models (new):**
```kotlin
data class TransferToDigitalRequest(
    val sourceCardNumber: String,
    val customerName: String,
    val phoneNumber: String,
    val emailAddress: String,
    val deliveryMethod: String, // "EMAIL" | "TEXT"
    val ticketId: Int?
)

data class TransferToDigitalResponse(
    val success: Boolean,
    val message: String?,
    val digitalCardNumber: String?, // the new AIO digital card number
    val digitalCardPin: String?,
    val transactionId: String?
)
```
**Integration Points:**
- Calls e-card API (Toast/Cake bridge) internally.
- Email/SMS delivery triggered by the backend after successful transfer.
- Response includes digital card details to show in POS UI (confirmation screen).

#### 3.3.4 Digital Purchase Request/Response Models
**Required Models (new):**
```kotlin
data class DigitalPurchaseRequest(
    val designId: String, // gift card template selected
    val purchaseAmountInCents: Int,
    val recipientType: String, // "SOMEONE_ELSE" | "CUSTOMER"
    val recipientName: String?,
    val senderName: String?,
    val deliveryMethod: String, // "EMAIL" | "TEXT"
    val deliveryContact: String, // email or phone
    val personalMessage: String?,
    val ticketId: Int?
)

data class DigitalPurchaseResponse(
    val success: Boolean,
    val message: String?,
    val cardNumber: String?,
    val cardPin: String?,
    val transactionId: String?
)
```
**Integration Points:**
- Design gallery (requires new endpoint: `getGiftCardDesigns(category: String?)` → `List<GiftCardDesign>`).
- Full payment enforced (no splits in PaymentViewModel for this transaction type).
- No discount applied to digital purchase in this iteration (or does it apply? PRD says "If gift card purchase discount is applied..." — needs clarification).
- e-card API call issued after payment (not before).

#### 3.3.5 New API Endpoints Summary
| Endpoint | Method | Request Type | Response Type | Dependency | Error Handling |
| --- | --- | --- | --- | --- | --- |
| `/giftCard/reload` | POST | `GiftCardReloadRequest` | `GiftCardReloadResponse` | Internal | Timeout: retry with exponential backoff; validation failure: snackbar + return to reload modal |
| `/giftCard/merge` | POST | `GiftCardMergeRequest` | `GiftCardMergeResponse` | Internal | As above; also handle child-card-not-found separately |
| `/giftCard/transferToDigital` | POST | `TransferToDigitalRequest` | `TransferToDigitalResponse` | e-card API | Timeout: retry once; e-card failure: show "unable to issue digital card"; payment already processed — show "contact support" flow |
| `/giftCard/purchaseDigital` | POST | `DigitalPurchaseRequest` | `DigitalPurchaseResponse` | e-card API | As transferToDigital |
| `/giftCard/designs` | GET | (query param: `category`) | `List<GiftCardDesign>` | None | Cache locally in SharedDataRepository (already fetched at app startup per GiftCardActivity.kt comment) |

### 3.4 UI & Navigation Review

#### 3.4.1 Navigation Graph
- **Current structure:** Linear: `giftCardsMainFragment` → `giftCardScannedContainer` → payment.
- **New sub-flows:**
  - From `giftCardsMainFragment`: "Reload", "Merge", "Transfer", "Purchase Digital" entry points.
  - Each entry point opens a distinct flow:
    - **Reload:** Card fetch modal → reload-amount modal → payment → success.
    - **Merge:** Card-list modal (add N cards, select parent) → payment (none, just confirm) → success.
    - **Transfer:** Card fetch modal → customer-details modal (or CFD detour) → payment (none) → success.
    - **Purchase Digital:** Design gallery → amount selector → customer-details → payment → success.
  - All flows return to `giftCardsMainFragment` on success or cancel.
- **Navigation Component Changes:**
  - Add 4 new sub-graph `<navigation>` elements in `gift_card_navigation.xml`, one per flow.
  - Or: flatten into a single `<navigation>` with 10+ `<fragment>` entries and 15+ `<action>` entries (both are valid; flattened is simpler for a feature this size).
  - Add a `<fragment>` for CFD detour (shared by transfer & digital-purchase) to allow seamless handoff to secondary screen.

#### 3.4.2 Dialog & Modal Consolidation
- **Existing:** `GiftCardNumberDialog` (barcode + manual entry, reusable).
- **New Modals Required:**
  - `ReloadAmountModal`: NumPad or text entry for reload amount.
  - `MergeCardsModal`: Card list, add/remove, parent-radio, balance preview, merge CTA.
  - `CustomerDetailsModal`: Name, Phone, Email inputs; "Switch to CFD" button.
  - `DesignGalleryModal`: Category filter, design grid, amount selector (pre-set + custom).
  - `ReviewModal`: Summary of all selections (design, amount, recipient, delivery method, message); Continue/Edit CTAs.
  - `CFDCustomerDetailsForm`: Separate form for secondary-screen input (name, delivery method, contact details, message).
- **Reuse Opportunity:** If the reload-amount UI is a simple **NumPad or decimal entry**, reuse or share with the existing time-pad pattern in `GiftCardActivity.kt:240` (currently unused in the read fragments, but infrastructure exists).

#### 3.4.3 Visual States & Error Handling
- **Loading State:** Show progress overlay during API calls (reload, merge, transfer, purchase).
- **Error States:** Network error, validation error (invalid phone/email), e-card API timeout → show snackbar + return to the modal (allow retry).
- **Fulfilled/Locked Item Guards:** Gift card transfers on a fulfilled/sent item should be **blocked at the button level** (consistent with special-request locked behavior).
- **Undo/Rollback UI:** Merge and transfer show "This cannot be undone" warnings (or do they? PRD is silent — product decision needed).

### 3.5 Payment Integration Review

#### 3.5.1 Commission Split Logic for Gift Card Reload/Purchase
- **Existing Payment Flow:** Payment processor (Card, Cash, Tenders, etc.) is selected in `PaymentViewModel`, and **commission splits are applied per the POS business configuration**.
- **New Requirement:** For gift card reload and digital purchase, if a **card payment method** is chosen, the commission applies to the **reload/purchase amount**, not to any base item in the ticket.
- **Implementation:** Mark the reload/purchase transaction with `transactionType = "GIFT_CARD_RELOAD"` or `"DIGITAL_PURCHASE"` in the payment request, so the payment processor knows to apply commission to the specified amount (not the item total).
- **Design Question:** Does reload/purchase count as a **new, separate line in the ticket** (like a custom item), or is it **outside the ticket model** (separate transaction)? Answer determines whether the payment processor sees it as a ticket item or a free-standing amount.

#### 3.5.2 Disabled Partial Payment Enforcement
- **Current:** PaymentViewModel allows split payments (multiple methods on one ticket).
- **New Requirement:** For gift card reload/purchase, **partial payments are disabled** — only full payment is allowed.
- **Implementation:** Add a flag `allowPartialPayment: Boolean` to the payment context passed to `PaymentViewModel`, default `true`, set to `false` for reload/purchase flows.
- **UX:** Hide or disable the "Add Another Payment Method" button when the reload/purchase payment is in progress.

### 3.6 Reliability & Error Recovery

#### 3.6.1 Mid-Flow Crashes
- **Reload Crash Scenario:** Customer pays $25 → payment processes successfully → app crashes before ticket update → next app open, user sees no reload. **Recovery:** Check e-card backend for the transaction; if it exists, auto-apply the reload on next card fetch.
- **Merge Crash Scenario:** Merge API returns success → app crashes before UI refresh → user opens app, unsure if merge happened. **Recovery:** Query parent-card balance; if it matches the merged total, merge happened; show success message.
- **Purchase Crash Scenario:** e-card API returns card number → app crashes before updating ticket → user doesn't see confirmation. **Recovery:** Query e-card API with the transactionId; if card exists, issue succeeded; show receipt.
- **General Strategy:** Store the `transactionId` in a **local `GiftCardTransactionEntity`** immediately after API success, *before* any UI updates. On app re-open, check for incomplete transactions and resume/retry from the last known point.

#### 3.6.2 Idempotency & Deduplication
- **Problem:** If the user double-taps "Pay" on the final confirmation screen, two reload/merge/purchase requests could fire.
- **Solution:** Implement a **debounce guard** on the "Confirm & Pay" button (disable for 2 seconds after tap), or assign a **requestId** (UUID) to the payment request and deduplicate server-side (e.g., Stripe-style idempotency key).
- **Priority:** High — gift card transactions are financial, double-charge is a high-severity incident.

#### 3.6.3 E-Card API Failure Handling
- **Scenario 1:** e-card API timeout during migration/digital-purchase. Payment already processed. **Recovery:** Retry e-card call (with exponential backoff, up to 3 times). If still fails after 3 retries, log the failed transaction, show "Contact Support" screen with transaction ID, and allow operator to note the issue. The transaction will be resolved manually by support (refund or manual card issuance).
- **Scenario 2:** e-card API returns success but email/SMS delivery fails. Card was issued but customer didn't receive details. **Recovery:** Store the card details in a `GiftCardIssuanceEntity` (local fallback) and allow the operator to resend via email/SMS from a support screen.

---

## 4. Identified Issues Log

| Category | Issue Description | Severity | Risk Impact | CR Affected |
| --- | --- | --- | --- | --- |
| Architecture | (I-1) No SavedStateHandle or process-death resilience in GiftCardViewModel / GiftCardActivity state — mid-flow crashes lose all in-progress data (reload amount, merge card list, customer details) | High | Data loss + repeated UX friction on process death (customer re-enters details) | All (1–4) |
| Concurrency | (I-2) No debounce guard on "Confirm & Pay" button across reload/merge/transfer/purchase flows; double-tap can fire duplicate payment requests | High | Double-charge, regulatory/financial incident | All (1–4) |
| API Design | (I-3) No idempotency key / requestId in reload/merge/transfer/purchase API contracts; server-side deduplication not specified | High | Duplicate transactions on network retry, customer support escalation | All (1–4) |
| API Design | (I-4) e-card API integration is net-new; no client code exists yet. Dependency on external API with unknown SLA, no local fallback for migration/digital-purchase | High | Feature-blocking if e-card API is unavailable; no graceful degradation for reload/merge (which don't use e-card) | CR-3, CR-4 |
| Data Model | (I-5) No GiftCardTransactionEntity or audit trail for reload/merge/transfer/purchase operations. Report integration undefined (how do these transactions appear in Sales Summary / Gift Card report?) | High | Missing audit trail, incomplete financial reports, no reconciliation | All (1–4) |
| Data Model | (I-6) Discount logic for gift card reload/purchase: PRD says "If gift card purchase discount is applied..." but doesn't specify the default behavior. Should reload/purchase inherit the current ticket's discount, or is there a separate gift-card-specific discount? | Medium | Incorrect revenue impact, customer confusion, tax/compliance issues | CR-1, CR-4 |
| Payment | (I-7) Commission split logic for card payments on reload/purchase is not yet integrated into PaymentViewModel. Card-payment commission (e.g., 2.5%) should apply to reload/purchase amount, not to ticket items. | High | Incorrect commission calculation, revenue recognition error | CR-1, CR-4 |
| UI/UX | (I-8) Merge card list modal: PRD doesn't specify a limit on the number of cards that can be merged, or max balance after merge. Should there be a cap (e.g., "max 5 cards", "max balance $5000")? | Medium | Potential for UI stress (long card list) or backend transaction failure on merge of many cards | CR-2 |
| UI/UX | (I-9) CFD (Customer-Facing Device) integration for customer-details entry: current POS has basic CFD support elsewhere, but gift card module has no CFD-aware components yet. Requires design/pattern review before implementation. | Medium | Re-implementation of CFD coordination logic; inconsistent UX across features | CR-3, CR-4 |
| Data Model | (I-10) Merge operation: If a child card is already part of a prior merge, should the system allow double-merge? No "anti-fraud" or "card ownership" validation mentioned. | Medium | Potential for dispute (customer claims card was already merged) | CR-2 |
| API Design | (I-11) Transfer to digital & digital purchase: e-card API returns card number and PIN in plaintext in the response. Should these be encrypted at rest in `GiftCardTransactionEntity`, or logged at all? No data-privacy/PCI-DSS guidance given. | Medium | PCI-DSS compliance risk, regulatory audit failure | CR-3, CR-4 |
| Reliability | (I-12) Merge child-card deletion: if merge API succeeds but local card-list refresh fails, user sees out-of-sync state (card still in list but marked inactive on backend). | Medium | UX confusion, support escalation ("my card is still there") | CR-2 |
| Test | (I-13) Zero existing test coverage for gift card flows (no unit tests, no instrumentation tests). New code will ship untested on an already-zero-coverage surface. | Medium | Regression risk, post-release bug discovery | All (1–4) |

---

## 5. Improvement Decision Matrix (Mandatory)

| Issue | Fix in Current Iteration? (Y/N) | Justification | Backlog Ticket | Target Sprint |
| --- | --- | --- | --- | --- |
| (I-1) Process-death resilience | Y | SavedStateHandle + local entity store is low-effort and high-value for mid-flow crash recovery; non-negotiable for financial transactions | *[Ref]* | *[Sprint]* |
| (I-2) Debounce guard on "Confirm & Pay" | Y | One-line button disable in each flow; critical to prevent double-charge | *[Ref]* | *[Sprint]* |
| (I-3) Idempotency key in API contracts | Y | Add requestId field to all 4 request models; verify server-side deduplication logic; low effort, high safety | *[Ref]* | *[Sprint]* |
| (I-4) e-card API client library | Y | Must be built before CR-3 and CR-4 can proceed; plan as a separate, blocking sub-task | *[Ref]* | *[Sprint]* |
| (I-5) GiftCardTransactionEntity + audit trail | Y | Required for financial reporting and compliance; design the schema and report-integration points before implementation | *[Ref]* | *[Sprint]* |
| (I-6) Discount logic clarification | Y (pending product) | Product decision: does reload/purchase inherit ticket discount? Confirm in writing before implementation. | *[Ref]* | *[Sprint]* |
| (I-7) Commission split integration | Y | Design: does reload/purchase create a pseudo-TicketItem or a free-standing transaction? Answer determines scope of PaymentViewModel changes. | *[Ref]* | *[Sprint]* |
| (I-8) Merge card list size/balance cap | Y (pending product) | Product decision: any limits on card count or total balance? Confirm before UI design. | *[Ref]* | *[Sprint]* |
| (I-9) CFD integration pattern review | Y | Review existing CFD patterns in the codebase (e.g., TSR customer-details flow); extract a reusable component before using in gift card flows. | *[Ref]* | *[Sprint]* |
| (I-10) Anti-fraud checks on merge | N | Out of scope for MVP; log as a future enhancement for post-launch iteration (e.g., "detect double-merge"). | *[Ref]* | *[Backlog]* |
| (I-11) PCI-DSS / data-privacy guidance | Y (pending legal/security) | Confirm with security/legal: should card numbers/PINs be encrypted at rest? Should they be logged at all? Answer before implementation. | *[Ref]* | *[Sprint]* |
| (I-12) Child-card deletion race | Y | Reload the card list from the server after merge succeeds (simple fix); or, mark deleted cards as "merged" in the UI rather than removing them. | *[Ref]* | *[Sprint]* |
| (I-13) Test coverage backfill | Y (scoped) | Write integration tests for the four happy-path flows (reload, merge, transfer, purchase) using a mock e-card API; don't backfill existing code. | *[Ref]* | *[Sprint]* |

### Governance Rules (Mirror of Special Request)
1. **Any improvement bundled into these CRs must be directly traceable to a line the CR is touching or a defect the PR's new behavior introduces.** I-1 (process death), I-2 (double-tap), I-3 (idempotency) qualify. I-10 (anti-fraud) is deferred.
2. **No Medium+ "risk if bundled" improvement proceeds without product/security sign-off**, even if engineering effort is low (see I-6, I-8, I-11).
3. **Blocking dependencies (I-4: e-card client) must be built and verified before the dependent CRs (CR-3, CR-4) enter implementation.**
4. **All new, payment-touching logic must be unit-tested**, not exempted by surrounding lack of coverage (see I-13 governance note below).
5. **Deferred improvements (I-10, others) should be logged as separate follow-up tickets**, not silently dropped.

---

## 6. Impact Assessment

### 6.1 UI Impact
- **GiftCardsMainFragment:** Add 4 new action buttons (Reload, Merge, Transfer, Purchase Digital) to the landing screen. Minimal layout change.
- **New Dialogs/Modals:** ~5–6 new modal/dialog layouts (reload-amount, merge-card-list, customer-details, design-gallery, review, CFD form).
- **New Fragments (possibly):** Design gallery might warrant a dedicated `DesignGalleryFragment` if the gallery is complex (image caching, pagination). Otherwise, a dialog-based approach is simpler.
- **Navigation Graph:** Extend `gift_card_navigation.xml` with ~10–15 new `<fragment>` and `<action>` entries (manageable, not a refactor).
- **Existing Fragment Minimal Changes:** `GiftCardsMainFragment`, `GiftCardScannedFragment`, `GiftCardBillFragment` are read-only views; no significant changes to their existing logic.

### 6.2 API Contract Impact
- **New Endpoints:** 4 new REST endpoints (reload, merge, transfer, purchase) + 1 design-gallery endpoint.
- **Backwards Compatibility:** None of the new endpoints conflict with existing gift card endpoints. Existing flows (validate, transfer-to-physical, pin-check) are unchanged.
- **Load Impact:** Each operation triggers 2–3 API calls (validate → fetch ticket → operation → payment). Assume typical response time ~500ms per call; total flow time ~2–3 seconds (acceptable for POS UX).

### 6.3 Database Impact
- **New Room Entities:** `GiftCardTransactionEntity` (1 table, ~1000 rows per year per store, minimal).
- **New Columns in Existing Entities:** Possibly `TicketItem.giftCardTransactionId` (foreign key link for reporting), or a separate `GiftCardTicketItemJoin` table if the report needs to link a transaction to a ticket.
- **Migration:** Simple ADD COLUMN + CREATE TABLE statements; no complex data backfill required (new feature, no legacy data).
- **Schema Review:** Coordinate with data/analytics team to ensure `GiftCardTransactionEntity` is queryable for the new report views (Sales Summary, Gift Card report, Checkout, Z-report).

### 6.4 Performance Impact
- **API Latency:** No change (new endpoints are separate from existing ticket flow).
- **Local Computation:** GiftCardViewModel's existing `updateGiftCards()` and discount logic are O(n) where n = number of cards in the current session (typically 1–5). Negligible impact.
- **Network Bandwidth:** Reload/merge/transfer/purchase each send ~200–500 bytes request, receive ~200 bytes response. Negligible.
- **Database I/O:** Insert into `GiftCardTransactionEntity` is a single write per operation. Negligible.
- **UI Rendering:** 4–5 new modals / dialogs, no RecyclerView-heavy views. Negligible rendering overhead.
- **Critical watch item:** If the design gallery has 100+ designs with high-res images, **lazy-load images via Glide** (already used in the codebase per line 134 of GiftCardActivity.kt) and **cache locally in SharedDataRepository** (already done per app-startup comment).

### 6.5 Backwards Compatibility
- **Existing Tickets:** A ticket created before these CRs will see the new "Reload", "Merge", "Transfer", "Purchase Digital" buttons on the gift card landing screen, but those buttons only affect gift cards, not the base ticket. No impact.
- **Existing Gift Card Data:** No migration needed; the new `GiftCardTransactionEntity` only logs *future* operations.
- **Payment System:** New payment context for reload/purchase (flag `allowPartialPayment = false`, `transactionType = "GIFT_CARD_RELOAD"`) should be backward-compatible with the existing PaymentViewModel (add new fields with defaults, don't rename/delete existing fields).

### 6.6 Offline Support
- **Reload/Merge/Transfer:** Require online access (API calls to backend gift card service). No offline mode.
- **Purchase Digital:** Requires online access + e-card API availability. If e-card API is down, the purchase is blocked (no local fallback).
- **Current Offline Strategy (if any):** GiftCardActivity does not seem to have special offline handling today. Assume all gift card operations are online-only (acceptable, since gift cards are typically used online anyway).

---

## 7. Risk Assessment

| Risk | Probability | Impact | Mitigation Strategy |
| --- | --- | --- | --- |
| **Double-Tap Double-Charge (I-2)** | High | Critical — customer charged twice, regulatory/audit issue | Implement debounce guard on "Confirm & Pay" button per Improvement Decision Matrix |
| **Process Death Loss of Mid-Flow State (I-1)** | Medium | High — customer frustration, re-entry friction, potential data loss if crash occurs before local persistence | Implement SavedStateHandle + GiftCardTransactionEntity per Decision Matrix |
| **e-card API Unavailability (I-4)** | Medium | High (for CR-3, CR-4 only) — feature blocking, no graceful degradation | Implement retry logic (3 attempts, exponential backoff) + fallback "Contact Support" flow; reload/merge unaffected |
| **Incorrect Discount Application (I-6)** | Medium | Medium — revenue impact, customer dispute, tax/audit issue | Obtain product confirmation in writing *before* implementation |
| **Commission Split Calculation Error (I-7)** | Medium | High — revenue recognition error, POS reconciliation failure | Design the payment-context structure and PaymentViewModel integration carefully; unit-test with realistic commission rates |
| **Merge Child-Card Deletion Race (I-12)** | Low | Low — UX confusion, but not a financial error | Simple fix: reload card list after merge succeeds |
| **Idempotency Gap (I-3)** | Medium | High — duplicate transactions on network retry, customer support burden | Add requestId to all request models; verify server-side deduplication logic *before* release |
| **Report Integration Undefined (I-5)** | High | High — incomplete financial reports, compliance risk, auditor questions | Define GiftCardTransactionEntity schema and report-integration points in design phase, not post-launch |
| **CFD Integration Misstep (I-9)** | Medium | Low–Medium — re-implementation or UX inconsistency | Review existing CFD patterns early; extract a reusable component |
| **PCI-DSS Compliance (I-11)** | Medium | High — regulatory audit failure, data-breach liability | Get security/legal sign-off on card-data handling before implementation |

### Rollback Strategy
- **Scope:** All 4 CRs are feature-gated (new buttons on the gift card landing screen, not in the main ticket flow). Rollback is a **app-version rollback**, not a database migration or API deprecation.
- **Data Persistence:** New `GiftCardTransactionEntity` rows can be safely left in the database after rollback — they're read-only logs, not used by any rollback-era code.
- **API Contracts:** New endpoints (reload, merge, transfer, purchase) can remain on the backend indefinitely if not called; they do not conflict with existing gift card endpoints.
- **Recommendation:** If a critical bug is discovered post-launch (e.g., double-charge, e-card issuance failure), rollback to the prior app version and issue a hotfix rather than attempting mid-flight surgery on the 4-endpoint ecosystem.

### Monitoring Plan
- **New Relic Instrumentation:** Extend existing logging (already in place per GiftCardActivity.kt) to tag all reload/merge/transfer/purchase operations with:
  - Operation type (reload, merge, transfer, purchase).
  - Request ID / transaction ID (for idempotency tracking).
  - API latency (success and failure paths).
  - Payment method (card, cash, tender, etc.).
  - Final status (success, timeout, validation-error, e-card-failure, etc.).
- **Dashboard Targets:**
  - **Funnel:** Initiated → API Success → Payment Success → Reported. Track drop-off at each stage.
  - **Latency:** Percentile distribution of total operation time; alert if p99 > 10s (suggests e-card API latency or network issue).
  - **Error Rate:** Track by operation type; alert if any operation has >5% error rate.
  - **Double-Tap Detection:** Flag instances where the same requestId is submitted twice within 5 seconds (indicates debounce failure or network retry).
- **Support Escalation Triggers:**
  - "e-card API unavailable" errors (wake on-call engineer).
  - "Commission split mismatch" (amount charged ≠ amount reported).
  - Any "double transaction" detected within 1 minute of each other (manual review).

---

## 8. Open Questions for Product/Design/Security (blocking full implementation sign-off)

1. **Discount Logic (I-6):** Should reload and digital purchase inherit the *current ticket's discount* (if any), or is there a separate "gift card purchase discount" setting? If the latter, is it a business-level config or a transaction-level input?

2. **Merge Card Limits (I-8):** Is there a maximum number of cards that can be merged in a single operation, or a maximum balance cap? (e.g., "up to 5 cards", "max combined balance $5000"). If so, what happens when the user tries to exceed the limit?

3. **Confirmation on Merge/Transfer (UX):** Should merge and transfer operations show a "This cannot be undone" warning or confirmation dialog? Or is the action assumed to be intentional if the user enters multiple cards / selects a parent?

4. **Fulfilled/Locked Items (Reliability):** Can a fulfilled gift card be reloaded, merged, transferred, or purchased? (Presumably no — the locked-status guard from special-request should apply here too, but it's not stated in the PRs.)

5. **PCI-DSS Guidance (I-11):** Should gift card numbers and PINs be:
   - Encrypted at rest in `GiftCardTransactionEntity`?
   - Logged to New Relic at all (even in success events)?
   - Displayed in plaintext in the success confirmation screen, or masked?
   - Stored in `SharedDataRepository` at all, or retrieved fresh from the server on demand?

6. **e-card API Dependency (I-4):** What is the SLA / uptime target for the e-card API? If it's offline, should the POS:
   - Block the entire gift card activity, or just migration/purchase?
   - Queue the transaction locally and retry on reconnect, or fail immediately?
   - Show a user-facing error ("e-card service unavailable, try later") or a silent retry loop?

7. **CFD Coordination (I-9):** Can you share an existing example of a POS feature that uses the secondary/CFD for multi-step customer input (e.g., name → phone → email)? Needed to ensure gift card CFD integration reuses the same pattern rather than inventing a new one.

8. **Commission Split Behavior (I-7):** For a reload/purchase with a card payment method, the commission (e.g., 2.5%) is applied to the reload/purchase amount. Does this also apply if the *underlying ticket* has a discount? (e.g., reload $100 with 10% ticket discount → reload amount is $90 → commission is 2.5% of $90 or 2.5% of $100?)

9. **Report Structure (I-5):** How should reload/merge/transfer/purchase transactions appear in the existing reports?
   - **Sales Summary:** As separate line items (like item sales), or grouped under a "Gift Card" category?
   - **Gift Card Report:** As transaction logs (one row per operation), or aggregated by card?
   - **Checkout Report:** Total gift card revenue (reloads + purchases) as a separate row, or mixed with item revenue?
   - **Z-report:** Same structure as Checkout?

10. **Rollback Scenario:** If a customer initiates a reload/purchase, the payment succeeds, but the app crashes before the ticket is updated, how should recovery work? Should the POS automatically retry the operation on next app open, or should it wait for customer/operator to manually initiate a "resume" flow?

---

## 9. Recommended Implementation Sequence

### Phase 1: Foundation (Blocking Dependency)
1. **Build e-card API client library** (I-4): Isolated module, mock-friendly for testing, no business logic yet. Unblocks CR-3 and CR-4.
2. **Define GiftCardTransactionEntity schema and report integration** (I-5): Coordinate with data/analytics; confirm query patterns for Sales Summary, Gift Card report, Checkout, Z-report.
3. **Obtain product clarifications** (I-6, I-8, I-9): Discount logic, merge limits, CFD pattern, confirmation dialogs.

### Phase 2: CR-1 (Reload)
- **Lowest risk, no e-card dependency.**
- Prerequisite: Phase 1 foundation.
- New API endpoint: `/giftCard/reload`.
- New modals: reload-amount, payment (existing), success confirmation.
- Integration: PaymentViewModel (commission split, partial-payment disable).
- Test: Integration test for reload happy path + error cases (network timeout, validation failure).

### Phase 3: CR-2 (Merge)
- **No e-card dependency, no payment, similar UI shape to reload.**
- Prerequisite: Phase 1 foundation + CR-1 (code patterns, error handling).
- New API endpoint: `/giftCard/merge`.
- New modal: merge-card-list.
- Integration: Marketing module event consumption.
- Test: Integration test for 2-card, 3-card, and N-card merge scenarios.

### Phase 4: CR-3 & CR-4 (Transfer to Digital & Digital Purchase) — In Parallel
- **Both depend on e-card API (Phase 1), CFD integration (Phase 1).**
- Prerequisite: Phase 1 foundation, Phase 2–3 code patterns.
- New API endpoints: `/giftCard/transferToDigital`, `/giftCard/purchaseDigital`, `/giftCard/designs`.
- New modals: customer-details, design-gallery, review, CFD form.
- Integration: e-card API, payment, report, customer-details validation.
- Test: Integration test for CR-3 (transfer success + e-card timeout recovery) and CR-4 (purchase success + design gallery filtering).

### Phase 5: Report Integration & Post-Launch Monitoring
- Extend report generation logic (Sales Summary, Gift Card, Checkout, Z-report) to include transaction rows for all 4 operations.
- Deploy New Relic instrumentation (dashboards, alerts, error tracking).
- Monitor for 2 weeks post-launch; address any critical issues (double-charge, e-card failures, discount miscalculation).

---

## 10. Summary Table: CRs at a Glance

| CR | Feature | API Endpoints | New UI | E-Card Dep. | Payment | Risk Level | Est. Complexity |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **CR-1** | Reload | `/reload` | Reload-amount modal, success screen | No | Yes (full-payment-only, commission split) | Medium | L2 |
| **CR-2** | Merge | `/merge` | Merge-card-list modal, success | No | No (balance transfer, no payment) | Medium | L2 |
| **CR-3** | Transfer to Digital | `/transferToDigital` | Customer-details modal, CFD form, success | Yes | No (balance transfer, no payment) | High | L3 |
| **CR-4** | Digital Purchase | `/purchaseDigital`, `/designs` | Design gallery, amount modal, customer-details modal, review, CFD form, payment, success | Yes | Yes (full-payment-only, commission split) | High | L3 |

---

## 11. Key Design Decisions

1. **Atomic Transaction Handling:** All 4 operations are **not true database transactions** (no rollback on app crash mid-sequence). Mitigation: store `transactionId` locally in `GiftCardTransactionEntity` immediately after API success, before UI updates. On app reopen, check for incomplete transactions and resume/retry.

2. **Merge vs. Transfer Terminology:** Merge = combine balances of 2+ cards into one. Transfer = move balance from one card to another (child card is marked inactive, parent remains active). Both are one-way, non-reversible operations (no undo).

3. **Commission Split Scope:** Applies only to **card payments** (not cash, not tenders) and only to **reload and digital purchase** (not merge, not migration, which involve no new money).

4. **e-card API Fallback:** No fallback for transfer/purchase if e-card is unavailable. Retry 3 times with exponential backoff, then show "Contact Support" flow with transaction ID. Defer as a future enhancement: "queue transfer/purchase for later e-card retry" (not in MVP).

5. **CFD vs. Server-Facing Device:** Reload and merge happen entirely on the server-facing POS device. Transfer and digital purchase allow customer to enter details on a secondary/customer-facing device (CFD). This is a UX/accessibility feature, not a requirement for these CRs to function (i.e., if CFD integration is delayed, the operations can still work server-side-only, but it's a worse UX).

---

## 12. Appendix: Code Locations & Dependencies

### 12.1 Entry Points
- `GiftCardActivity.kt` (main orchestrator, already reviewed above).
- `GiftCardsMainFragment.kt` (landing screen, needs 4 new buttons for Reload/Merge/Transfer/Purchase).

### 12.2 ViewModel & State
- `TsrTicketViewModel.kt` (orchestrates all API calls, needs 4 new SharedFlow response channels for reload/merge/transfer/purchase).
- `GiftCardViewModel.kt` (lightweight holder for `giftCards`, `discountType`, `discountAmount` — may need extension for reload/purchase amount state).
- `PaymentViewModel.kt` (payment method selection — needs `allowPartialPayment` flag and commission split logic for gift card transactions).

### 12.3 Repository & API
- `TsrRepository.kt` (gift card API calls — needs 4 new methods: `reloadGiftCard()`, `mergeGiftCards()`, `transferToDigitalGiftCard()`, `purchaseDigitalGiftCard()`).
- New e-card API client (to be created, integrates with external e-card service).

### 12.4 Data Models
- `GiftCardSettings.kt` (gift card configuration, already exists).
- New: `GiftCardReloadRequest`, `GiftCardReloadResponse`.
- New: `GiftCardMergeRequest`, `GiftCardMergeResponse`.
- New: `TransferToDigitalRequest`, `TransferToDigitalResponse`.
- New: `DigitalPurchaseRequest`, `DigitalPurchaseResponse`.
- New: `GiftCardDesign` (for design gallery).
- New: `GiftCardTransactionEntity` (Room entity for audit trail).

### 12.5 Navigation
- `res/navigation/gift_card_navigation.xml` (extend with reload/merge/transfer/purchase sub-graphs or fragments).

### 12.6 Dialogs & Modals
- Existing: `GiftCardNumberDialog` (reuse for card fetch in reload/merge).
- New: `ReloadAmountDialog`, `MergeCardsDialog`, `CustomerDetailsDialog`, `DesignGalleryDialog`, `ReviewDialog`, `CFDCustomerDetailsForm`.

### 12.7 Reports & Auditing
- Report generation logic (location TBD in this review, likely in a separate report module) needs extension for gift card transaction rows.

---

## 13. Conclusion

These 4 CRs represent a **significant feature addition** (L3–L4 complexity) that introduces **new financial transaction types, new API contracts, and external API dependencies**. The implementation plan must prioritize:

1. **Financial robustness** (debounce, idempotency, process-death recovery, audit trail).
2. **Clear API contracts** (request/response models, error codes, retry semantics).
3. **Coordinated product sign-offs** (discount logic, merge limits, confirmation dialogs, CFD integration).
4. **External dependency management** (e-card API SLA, timeout/retry strategy, fallback behavior).
5. **Report integration** (GiftCardTransactionEntity, query patterns, reconciliation).

**Estimated effort:** 
- Phase 1 (foundation): 2–3 sprints.
- Phase 2 (Reload): 1–2 sprints.
- Phase 3 (Merge): 1 sprint.
- Phase 4 (Transfer + Digital Purchase, parallel): 2–3 sprints.
- **Total: 6–9 sprints** for a high-confidence, tested, monitored release.

**Critical blockers:**
- e-card API client (Phase 1).
- Product clarifications on discount, merge limits, CFD, confirmation dialogs (Phase 1).
- Security/legal guidance on card-data handling (Phase 1).
- Report schema design and integration points (Phase 1).

**Next step:** Obtain answers to the 10 open questions (§8) and product approval of the design decisions (§11) before commencing detailed design/implementation.
