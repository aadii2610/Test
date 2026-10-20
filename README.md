# Tech Analysis — Gift Card CRs on POS: Digital Purchase, Merge, Migration to Digital, Reload

**PRDs (4 CRs, one analysis):**
1. [Change Request: Digital Gift Card Purchase on POS](https://app.notion.com/p/aio-app/Change-Request-Digital-Gift-Card-Purchase-on-POS-3a85b13baaa380fbba46c2e96aad530d)
2. [CR: Gift Card Merge on POS](https://app.notion.com/p/aio-app/CR-Gift-Card-Merge-on-POS-25-07-2026-3a85b13baaa38058a0d0ed5f4170b48d)
3. [CR: Gift Card Migration to Digital on POS](https://app.notion.com/p/aio-app/CR-Gift-Card-Migration-to-Digital-on-POS-25-07-2026-3a85b13baaa38092879ced0cddc946d5)
4. [CR: Gift Card Reload on POS](https://app.notion.com/p/aio-app/CR-Gift-Card-Reload-on-POS-25-07-2026-3a85b13baaa380a2ac51ef15af1b8f12)

**Related:** `GIFT_CARD_VISIBILITY_AND_REFUND_TECH_ANALYSIS.md` (refund, same gift card code)
**Scope of this doc:** POS (Android) only. Backend, E-card provider, Marketing ledger and Reports are dependencies.
**Status:** Pre-implementation analysis. No code changed.

---

## 1. Feature Overview

| Field | Value |
| --- | --- |
| **Jira Ticket** | *(pending: one epic, four stories recommended)* |
| **Feature Name** | Gift Card: Digital Purchase, Merge, Migration to Digital, Reload |
| **Module / Layer Affected** | **Cross-module.** UI (gift card screens, customer-facing display "CFD"), Domain/Data (new gift card APIs and models), Payment (gift card ticket), Receipts, external services (E-card API, Marketing ledger, Backend). |
| **Tech Lead / Owner** | *(pending)* |
| **Estimated Complexity** | **Epic L4.** Per CR: Digital Purchase **L4**, Reload **L3**, Migration **L3**, Merge **L3**. |
| **Initial Risk Level** | **High**. Money, card balances and PINs; none of the four server APIs exists on the client. |
| **Dependencies** | **E-card API** (card details + delivery), **Backend** (digital purchase, reload, merge, migration endpoints, templates, delivery), **Marketing module** (merge ledger event), **Reports** (reload impact), **Internal:** `GiftCardActivity`, `PaymentParentFragment`, CFD framework. |
| **Target Release Date** | *(pending)* |

### The four CRs at a glance

| CR | What it adds | Entry point | Needs |
| --- | --- | --- | --- |
| **Digital purchase** | Buy a digital card (design, amount, recipient, Email/Text delivery, message), full payment only, delivered by the E-card flow | New "Purchase Digital Card" button | 9 screens, design gallery, E-card API, CFD forms, validation |
| **Merge** | Merge several cards (physical + digital) into one parent card | New "Merge Cards" button | Pop-up, parent/child rules, new merge API, ledger event |
| **Migration to Digital** | Transfer a Toast/Cake card to a **digital** AIO card (today: physical only) | Existing transfer icon on the card row | Type chooser, name/phone/email form, E-card API, email with number + PIN |
| **Reload** | Add value to an existing card from the POS and pay for it | New reload icon on the card row | Reload dialog, payment, receipt, multi-card flows |

### Shared building blocks (why one doc)

- **Same screens and files:** `GiftCardsMainFragment`, `GiftCardScannedFragment`, `GiftCardScannedAdapter`, `GiftCardBillFragment`, `LoadGiftCardDialog`, `TransferGiftCardDialog`, `PayBy*Fragment`.
- **Same new dependencies:** the E-card API (purchase + migration), the CFD customer form (purchase + migration), email/phone validation (purchase + migration), and the bill/payment path (purchase + reload).
- **Same blocker:** the scanned-card list and bill are hard-wired to `INVENTORY` ("New") cards. Reload, merge and migration all need other card sources to behave differently.

---

## 2. Existing Code Review

Paths under `pos/src/main/java/aio/app/pos/` unless stated. `G/` = `ui/giftcard/`, `P/` = `ui/giftcard/presentation/`.

### 2.1 Architecture & Layering

**What exists today**

- **Landing screen** (`P/GiftCardsMainFragment.kt`, 330 lines): scan area and "Enter card number manually" only. There are **no Purchase Digital or Merge buttons.** A hidden `btnSwitchToGuest` exists.
- **Scanned list** (`G/adapters/GiftCardScannedAdapter.kt`): `INVENTORY` cards show an editable amount row ("New"). **Every other source gets a Transfer-only row** (transfer, print, remove). There is **no reload icon**.
- **Transfer** (`G/dialogs/TransferGiftCardDialog.kt`): `POST /api/gift-card/transfer-balance` with `{fromCardNumber, toCardNumber}`. The **target must be an `INVENTORY` card**. There is **no physical/digital chooser**. Transfer is disabled for `DIGITAL` cards, for already-transferred cards and for zero balance.
- **Reload is blocked in six places** by `INVENTORY`-only filters: `GiftCardScannedFragment.updateBillFragment` (L872–890), the adapter view type (L30), `updateAllNewCards`, remove handling (L125), `addCardItem` (L467–480), and the receipt filters in `PayByCard/Cash/Tender/GiftCardFragment`.
- **Merge:** the words merge/parent/child **do not exist** in the gift card code.
- **Gift card bill:** `GiftCardBillFragment` sums the bill items, applies an optional dollar/percent discount to the whole subtotal, then `PATCH /api/gift-card/ticket`, then hands over to the payment fragments through bundle args (`isFromGiftCard`, `giftCardTicketId`). **The gift card ticket is not in Room.**
- **Payments for a gift card ticket:** full payment is already enforced (`BillFragment.setupGiftCardFlow` ~L685–740: split/partial widgets hidden, status = paid). Methods: Cash, Tender, Card and Manual Card (terminal ready and amount ≥ 0.50; manual needs `ADYEN_AMS`), Gift card (as tender). **This part of the Digital Purchase CR already exists.**
- **E-card:** `isECardEnabled` is only a restaurant flag that gates the gift card menu. **No E-card endpoint, DTO, design/template/category list or image loading exists.** The word "ecardsystems.net" appears only in a barcode URL parser.
- **Data model for delivery** (`UserGiftCardPayment`: purchased_by_*, recipient_*, message, security_code) is **read-side only**. `GiftCardTicketRequestDTO` carries only `cardNumber`, `topupAmountInCents`, `giftCardId`, so **recipient, delivery method and message cannot be sent today**.
- **Reload fields already exist in DTOs** (`topupAmountInCents`, `existingBalanceInCents`), but the UI only echoes them when restoring a ticket.

### 2.2 CFD (customer-facing display)

- **Same device, same process.** The CFD is an Android `Presentation` on a second display (`SecondaryScreenPresentation`). Handoff is `SecondaryScreenHandler` with a UI-state flow (replay 1) and a user-action flow (**no replay, no buffer**).
- **Adding a form needs:** a new `SecondaryScreenUiState`, a new `UserAction`, a branch in the Presentation, and a collector in the host fragment.
- **Precedents for customer-entered values:** `IdleMarketingSecondaryScreen` (name + phone + consent), `LoyaltyLoginSignupFormSecondaryScreen`, `ReceiptSecondaryScreen` (email), and gift card number/PIN entry in `PayByGiftCardFragment`. The gift card CFD flow is **quick-serve only** (TSR uses the POS screen).
- **Keyboard:** the CFD uses an external custom keyboard AAR (`aio.app.keyboard:keyboard:0.0.16`) through `KeyboardConfig.setUpKeyBoard`, not the system keyboard.
- **Waiting state:** `CardPaymentProcessing` shows "Guest is entering…" with a Skip button and a switch-back button. Tip and gift card entry have **no timeout**; the cashier cancels.
- **CFD is optional:** `isSecondaryScreenEnabled` is a restaurant setting; fragments never check whether a display is attached. With the setting on and no display, the state is emitted and **nobody renders it** (no automatic fallback).
- **Label mismatch:** the PRD says "Switch to CFD"; the existing button is "Switch to guest".
- **Multi-step form on the CFD (name, recipient, delivery, message) does not exist** and must be built (the PRD calls for 3 CFD screens in the purchase flow, 2 in migration).

### 2.3 Concurrency

- `giftCardValidateResponse` in `TsrTicketViewModel` is a `SharedFlow(replay=0)` with **four collectors**, and a scan in any dialog also feeds the scanned list. **A merge dialog that scans cards must not share this flow**, or the scanned fragment will also add the card.
- A late collector misses events: `GiftCardsMainFragment.observeCurrentTicket` (L255) can miss the `getCurrentGiftCardTicket` response fired in `GiftCardActivity.onCreate` (L270), leaving `ticketId` null and **Pay failing with "Ticket ID not found"**.
- CFD user actions are emitted on a global scope into a **no-replay** flow, so an action is **dropped if no collector is active**. Several screens collect the same flow (`RegisterActivity`, payment fragments, `LoyaltyMenuDialogFragment`).
- Gift card settings (min, max, presets) are fetched in `RegisterActivity` (L615–617), but `GiftCardActivity` is reachable from other drawers, so `LoadGiftCardDialog` **may get empty settings** and then skips range validation.

### 2.4 Data Layer

- **Not found:** E-card, digital purchase, reload, merge and migration endpoints. Existing gift card APIs: validate (+PIN), ticket create/update/delete/current, transfer-balance, settings, process-payment, receipt (`ApiService.kt:903–972`).
- `createGiftCardTicket` is declared but **never called from the UI**.
- `GiftCardSettingsDTO.presetAmounts` is `List<Int>` and `LoadGiftCardDialog` works on **whole numbers only** (max 6 digits). PRD presets differ: purchase $25/50/100/150, reload $25/50/100/200 (settings supply a list; the dialog keeps at most 5).
- **Discount** math is duplicated in three places (`GiftCardBillFragment.kt:213, 626`, `GiftCardActivity.kt:452`). The PRD says a gift card purchase discount also applies to reload amounts, which is already true because the discount applies to the whole bill subtotal once reload lines can enter the bill.
- `replaceCard` (after transfer) builds a new item **with no source, giftCardId or amount**.
- **Commission split:** no "commission" code exists. The gift card card-payment path hard-codes `applicationFeeInCents = 0` and `discount = "0"` (`PayByCardFragment.kt:1811, 1822, 1842`). The Reload CR says "the configured POS commission split will be applied".

### 2.5 UI & State Management

- Scanned list and bill state are **synced manually** between adapter and `GiftCardViewModel` (`syncRepositoryWithAdapter`, `updateBillFragment`).
- **Global state:** `Constants.QsrGiftCard/QsrGiftPin/TsrGiftCard/TsrGiftPin/giftCardBalance`, top-level `var enteredPassword`, and `SharedDataRepository.giftCardAllCardsList/giftCardList/giftCardSettings` (cleared in several places).
- **Receipts:** four near-identical `printGiftCardReceipt` copies (`PayByCard/Cash/Tender/GiftCardFragment`). All filter to `INVENTORY` cards, so **reloaded cards would be missing from receipts**. The receipt prints the full card number (card footer masked) and no PIN. **No reload, merge or transfer receipt exists.** The Cash variant lacks the empty-printer check.
- **Delivery is backend-side:** the POS calls a receipt endpoint (`/api/payment/gift-card/receipt/{paymentId}`) with an email or mobile number. Whether it can deliver a card to a **recipient** (not the purchaser) with a personal message is not visible from the client.
- **Validators:** email regex is copied in three places; phone validators are US-only (two different ones). **No message length-limit utility exists**; error patterns differ between CFD (toast or inline) and POS (snackbar).
- **Permissions:** `AuthEnum.GIFT_CARDS_VISIBLE` is defined but **unused**; `navGiftCard()` has no permission check.

### 2.6 Performance & Memory

- The CFD UI state carries ViewModels, `LifecycleOwner` and `Context` for gift card approval screens (leak risk), a pattern the new forms should avoid by passing plain data.
- Design gallery needs image loading and caching (Glide 4.16 is available). No gallery exists. Design count, image size and caching are unknown.
- No other notable performance concerns. All operations are online API calls.

### 2.7 Reliability & Stability

- **All gift card operations are online-only** (no offline checks); failures show snackbars.
- **PCI/PII in logs.** `logNewRelicEvent` has no masking. Confirmed in the code:
  - The **gift card PIN** is logged (`TsrTicketViewModel.kt:2051` `GIFT_CARD_PIN`; `RegisterActivity.kt:1591, 1607, 1649`).
  - Full **gift card numbers** are logged (`TransferGiftCardDialog`, `GiftCardScannedFragment`, `GiftCardBillFragment`).
  - Customer **email, phone and name** are logged (`PayByGiftCardFragment.kt:3405–3478`), plus a receipt body with email/phone (`MainViewModel`) and `card: $card` (`AioPrinterViewModel.kt:1033`).
  - The new flows add recipient name, email, phone and personal message, and migration adds a **new PIN**. They must not be logged.
- **Cancel flow:** the Digital Purchase CR says Cancel at any step creates no card and no charge. No gift card ticket may be created before payment succeeds (or it must be deleted on cancel; `DELETE /api/gift-card/ticket/{id}` exists).
- **Fulfilment failure:** payment succeeds but the E-card call fails. There is no existing pattern for "paid but card not loaded".

### 2.8 Test Coverage

- **No gift card tests** anywhere (no discount math, payment flows, ViewModels, CFD, validators or receipts). The repo has about 12 test files in total.

---

## 3. Identified Issues Log

| # | Category | Issue | Severity | Risk Impact |
| --- | --- | --- | --- | --- |
| I-1 | Backend contract | No E-card, digital purchase, reload, merge or migration API on the client. Delivery of a card to a recipient (with message) is undefined | **Critical** | Blocks all four CRs |
| I-2 | Security / PCI | PIN, full card numbers, email, phone and name are logged to New Relic; new flows add more PII and a new PIN | **Critical** | Sensitive data exposure; must not grow |
| I-3 | Architecture | List, bill and receipts are hard-wired to `INVENTORY` cards (six places) | High | Reload and merge cannot work; reloaded cards missing from receipts |
| I-4 | Fulfilment | Payment can succeed while E-card load/delivery fails; no recovery pattern | High | Customer pays, gets no card |
| I-5 | Money | Merge and migration move balance between cards; no idempotency key, no in-flight guard, no rollback | High | Duplicate or partial transfer |
| I-6 | Architecture | No CFD multi-step form for gift cards; CFD user actions are dropped without a collector; CFD is optional with no fallback | High | Purchase/migration flow can stall or lose input |
| I-7 | Concurrency | `giftCardValidateResponse` has four collectors and replay 0; a merge scanner would double-add cards; a late collector misses the current-ticket response | High | Wrong cards added; "Ticket ID not found" |
| I-8 | Business rule | Commission split is not found in code; gift card card payment hard-codes fee 0 | High | Reload commission rule unmet |
| I-9 | Data | Request DTO has no recipient/delivery/message/sender fields; `replaceCard` drops source and card id after transfer | High | Data lost or cannot be sent |
| I-10 | Business rule | Transfer is blocked for `DIGITAL` and requires an `INVENTORY` target; merge rules (physical + digital, parent choice) are undefined | High | Migration and merge rules conflict with today's rules |
| I-11 | UI | No design/template gallery, category list or image loading | Medium | Whole screen is new work |
| I-12 | UI | No validation framework: email regex copied 3 times, US-only phone checks, no length-limit utility | Medium | Inconsistent validation |
| I-13 | Architecture | Settings (min/max/presets) fetched only in `RegisterActivity`; dialog skips validation if missing; whole-number-only amounts | Medium | Invalid amounts or wrong presets |
| I-14 | Architecture | God classes, global state (`Constants.*GiftCard/Pin`) and duplicated code: 4 copies of receipt printing, discount math in 3 places, `isFromGiftCard` in 106 places | Medium | Every change touches many files |
| I-15 | Permissions | `GIFT_CARDS_VISIBLE` unused; no role gating for new actions | Medium | No way to restrict merge/migration/reload by role |
| I-16 | Reporting | Reports are server-side; POS only sends `orderType="gift-card"` for card payments (cash/tender/gift card send none) | Medium | Reload may be missing from Sales Summary, Gift Card, Checkout and Z reports |
| I-17 | UX | PRD says "Switch to CFD"; code says "Switch to guest"; SMS button commented out on the CFD receipt | Low | Naming and capability mismatch |
| I-18 | Test | No gift card tests | Medium | Regression risk on money code |

---

## 4. Improvement Decision Matrix

| Issue | Fix now? | Justification | Backlog Ticket | Target Sprint |
| --- | --- | --- | --- | --- |
| I-1 Backend contract | **Y (first)** | Everything depends on it. One contract review for all four CRs | *[Ref]* | *[Sprint]* |
| I-2 Sensitive logging | **Y (first)** | Governance: security risk → must fix before new PII flows. Remove PIN, card number, email, phone and name from logs; mask card numbers | — | *[Sprint]* |
| I-3 `INVENTORY` hard-wiring | **Y** | Required by Reload and Merge. Introduce one rule per card source (what a source may do: reload, transfer, merge) and use it everywhere | — | *[Sprint]* |
| I-4 Paid but not loaded | **Y** | Show a clear failure state, allow retry of the E-card call without recharging, and log the event. Needs backend retry behavior | *[Ref]* | *[Sprint]* |
| I-5 Idempotency for merge/migration | **Y** | Idempotency key (backend), in-flight guard, disable buttons while running, refresh card state after timeout | — | *[Sprint]* |
| I-6 CFD forms | **Y** | Build one reusable CFD gift card form (name, delivery, message) for purchase and migration; add a "no CFD / skip" path to the POS screen | — | *[Sprint]* |
| I-7 Shared flow | **Y** | Give merge and the new dialogs their own ViewModel/flow; fix the current-ticket race by replaying or reading state on collect | — | *[Sprint]* |
| I-8 Commission | **Y (needs product + backend)** | Define where the split applies; the POS must pass it in the payment request | *[Ref]* | *[Sprint]* |
| I-9 DTO fields | **Y** | Extend the ticket request with recipient, delivery and message; keep source and card id in `replaceCard` | — | *[Sprint]* |
| I-10 Transfer/merge rules | **Y** | Replace the "INVENTORY target only" rule with the new rules once the product answers (see §7) | — | *[Sprint]* |
| I-11 Design gallery | **Y** | New screen with category filter, Glide cache; reuse nothing | — | *[Sprint]* |
| I-12 Validation | **Y (scoped)** | One shared email, phone and length validator used by the new forms; do not rewrite existing screens | *[Ref]* | *[Sprint]* |
| I-13 Settings fetch | **Y** | Fetch settings when the gift card screen opens; block amount entry until loaded; allow decimals if the PRD requires custom amounts with cents | — | *[Sprint]* |
| I-14 Refactor | **N** | Too large for this iteration. Extract only a shared receipt builder for gift cards (one function instead of four) | *[Ref]* | *[Sprint]* |
| I-15 Permissions | **Y** | New `AuthEnum` entries for purchase digital, merge, migrate, reload; apply to buttons | *[Ref]* | *[Sprint]* |
| I-16 Reports | **Y (needs backend)** | Send an explicit gift card type and reload flag for every tender, not only card | *[Ref]* | *[Sprint]* |
| I-17 Naming / SMS | **Y** | Align labels with design; confirm whether SMS must work on the CFD | — | *[Sprint]* |
| I-18 Tests | **Y (scoped)** | Unit tests for source rules, amount validation, discount math, merge total, and form validation | *[Ref]* | *[Sprint]* |

**Governance:** Security and money issues are fixed in this iteration and before the new flows ship. Architectural exceptions need explicit approval. Nothing is ignored without a ticket.

---

## 5. Impact Assessment

### 5.1 UI Impact
- **Landing screen:** two new buttons (Purchase Digital Card, Merge Cards).
- **Digital purchase:** 7 POS screens (amount/design, custom amount, recipient ×2, delivery, review) + 3 CFD screens (name/recipient, delivery, message) + payment + confirmation.
- **Merge:** pop-up with parent radio, remove button, "Total merge balance", Merge CTA, success toast.
- **Migration:** transfer pop-up gets a Digital/Physical chooser and a name/phone/email form (POS and CFD).
- **Reload:** reload icon on each non-inventory card row, "Set a reload value" pop-up, editable amount shown on the row, receipt.
- **Navigation:** new fragments inside the existing gift card nav graph; no change elsewhere.

### 5.2 API Contract Impact
- **Yes, large.** New or extended endpoints for digital purchase (design, recipient, delivery, message), reload (existing card top-up and payment type), merge (parent + children, total), migration to digital (name, phone, email), and E-card load. Responses must return the card number and, for migration, the PIN. Idempotency keys needed. Older POS builds ignore the new buttons.

### 5.3 Database Impact
- **None expected.** Gift card tickets are not stored in Room today and the new flows should stay API-driven. No migration.

### 5.4 Performance Impact
- Design gallery images (cache and size). Otherwise low. All calls are online and short.

### 5.5 Backward Compatibility
- **Feature flags recommended** per CR (purchase digital, merge, migration, reload) so each can ship or roll back separately; all behind the existing `isGiftCardEnabled`.
- Legacy and physical flows must keep working; the transfer-to-physical flow is unchanged.
- Mixed fleet: older builds show none of the new buttons.

---

## 6. Risk Assessment

| Risk | Probability | Impact | Mitigation |
| --- | --- | --- | --- |
| Backend/E-card contract not ready or differs | High | **High** | Agree contract first (I-1); POS starts after |
| Customer pays but card is not loaded or delivered | Medium | **High** | Retry without recharge; clear failure message; alert on paid-but-unloaded |
| PIN or PII leaked through logs | High | **High** | Fix before new flows (I-2); review every new log call |
| Merge or migration applied twice or partially | Medium | High | Idempotency key, in-flight guard, refresh card state after timeout |
| Wrong cards added through the shared scan flow | Medium | Medium | Dedicated flow for merge (I-7) |
| CFD form lost or stuck (no collector, no display, dropped action) | Medium | Medium | Replay-safe state; POS fallback and cancel path; timeout decision |
| Reload missing from receipts or reports | High | Medium | Remove `INVENTORY`-only receipt filter (I-3); send explicit type to Reports (I-16) |
| Commission split applied wrong | Medium | High | Define the rule, pass it explicitly, test it |
| Scope growth (4 CRs, 3 CFD flows, gallery) | High | Medium | Ship per CR behind flags; recommended order below |

**Suggested order:** (0) security + shared building blocks (log cleanup, source rules, CFD form, validators), then (1) **Reload** (smallest, existing DTO fields), (2) **Migration to Digital**, (3) **Merge**, (4) **Digital Purchase** (largest, needs gallery + E-card).

**Rollback strategy**
- Turn the CR's feature flag off to hide its buttons without an app release.
- If the POS change has a defect, halt rollout or revert that CR's PR. Balance moves made by the backend (merge, migration, reload) cannot be undone by a client rollback.

**Monitoring plan**
- New Relic events per flow: started, payment done, E-card/merge/migration API success or failure, delivery failure, duplicate suppressed (**no card number, PIN or contact details in any event**).
- Alert on paid gift card tickets without a loaded card, and on merge/migration requests without a completion.

---

## 7. Open Questions (blocking)

**Shared**
1. **Backend/E-card:** which endpoints and payloads for digital purchase, reload, merge and migration? Is delivery (email/SMS, message) done by the backend after payment? What if the E-card call fails after payment?
2. **Permissions:** which roles may purchase digital, merge, migrate, reload? Is manager approval needed for merge and migration?
3. **CFD:** is the CFD required or optional for the digital flows? What happens on a single-screen device or when the customer does not respond (timeout, cancel)? The PRD says "Switch to CFD"; existing UI says "Switch to guest".
4. **Offline:** all gift card operations are online-only today. Confirm that stays true.

**Digital purchase**
5. Where do designs and categories come from (API, count, images)? What are the custom amount min and max and decimal rules? What is the personal message limit?
6. Is text (SMS) delivery supported by the backend? The CFD SMS button is currently commented out.
7. Is a gift card allowed as the tender for a digital purchase? (Allowed today for physical.)
8. Can a digital purchase be added to an open ticket ("the selected amount is reflected in the ticket total")? Gift card tickets are separate from normal tickets today.

**Reload**
9. The card must be **active**? Which card sources can be reloaded (physical, digital, migrated)? Digital cards are entered manually only: confirm.
10. Commission split: where is it configured, and does it apply to every card payment or only to reload?
11. Does the gift card purchase discount apply to reload amounts (PRD says yes)? Is approval needed for the discount?
12. Receipt: one receipt for the whole bill, with reload lines marked "Reload"?

**Migration**
13. Is the new digital card created from inventory, or generated by E-card? Is the old card deactivated? Is the PIN shown on the POS or only emailed?
14. Phone is optional or required? Is the transferred amount always the full balance?

**Merge**
15. Which sources can merge into which? Can a digital card be a child? What happens to the child cards (zeroed, deactivated)? Is there a max card count?
16. What does "the merge event is consumed by the marketing module" mean for the POS: does the backend emit it, or must the POS send an event?
17. The PRD uses both "Merge" and "Transfer" for the CTA ("Transfer button is enabled"): confirm the label.

---

## 8. Final Approval

| Role | Approval |
| --- | --- |
| Engineering Manager Approval | |
| Platform Lead Approval | |
| Squad Lead Approval | |
| Product Acknowledgment | |
| Date | |
