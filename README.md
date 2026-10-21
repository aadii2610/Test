# Tech Analysis — Cash Discount (Dual Pricing) on POS

**PRD:** [Cash Discount - Product Requirement Document](https://app.notion.com/p/aio-app/Cash-Discount-Product-Requirement-Document-3c95b13baaa3803a8ffac0f35e134d63)
**Sub-pages read:** *Cash Discount Configuration*, *Cash Discount Configuration Consumption on POS and mPOS*. (*Impact in Reporting* is empty; the KIOSK and Merchant Report pages were not read.)
**Figma (POS):** [POS Payments](https://www.figma.com/design/UOxJw6F6BPq7rIZU13qy0Q/POS-Payments?node-id=14786-37885)
**Scope of this doc:** POS (Android) only. Super Admin, Menu Manager, Backend, Reports and Kiosk are dependencies.
**Status:** Pre-implementation analysis. No code changed.

---

## 1. Feature Overview

| Field | Value |
| --- | --- |
| **Jira Ticket** | *(pending)* |
| **Feature Name** | Cash Discount (Dual Pricing): POS and mPOS consumption |
| **Module / Layer Affected** | **Cross-module.** Domain (`payment_calculator`), UI (payment, ticket, CFD), Data (menu price fields, payment requests), Receipts (external printer SDK), Backend, Super Admin, Menu Manager, Reports. |
| **Tech Lead / Owner** | *(pending)* |
| **Estimated Complexity** | **L4**. It changes how money is calculated, split across payments, refunded and reported. |
| **Initial Risk Level** | **High**. Card-network compliance, cash drawer reconciliation and refund correctness. |
| **Dependencies** | **Backend (critical):** price configuration, display price, per-payment discount calculation, refund/report fields. **Super Admin / Menu Manager:** config + bulk price update. **Printer SDK** (`aio.app.common:printer`) for the unpaid-receipt disclaimer. **Payment SDK** (`payment-core`). **Other apps:** Kiosk, mPOS, online (out of this doc). |
| **Target Release Date** | *(pending)* |

### Business Context

- **Problem:** Card fees (2.5–4%) cut restaurant margin. Manual cash discounts are non-compliant (card price must be the displayed price, a separate receipt line is required), incorrect on split payments, and unreportable.
- **User impact:** Menu shows the card (display) price. Paying fully or partly in cash gives the guest a discount, shown on the receipt, and the cash drawer matches cash actually received.
- **Success criteria (PRD):** compliant receipts and disclaimers, correct split/refund math, reconciled cash drawer, and cash discount reported as its own type.

### What the POS must do (from the PRD)

| # | Requirement | Source |
| --- | --- | --- |
| 1 | Show **display price** = base ÷ (1 − rate) for items and modifiers | US1 |
| 2 | Subtotal, service charge, gratuity, tax and suggested tips use the rendered (display) subtotal for non-cash tenders | US2–3 |
| 3 | On **Cash**: discount = rate × subtotal; service charge, gratuity, tax and tips recalculated on the discounted subtotal | US4–5 |
| 4 | **Split/partial:** discount applies only to the cash payment's share of the subtotal (proportion p = cash ÷ total) | US6, US12 |
| 5 | Receipt: separate **negative "Cash Discount" line** right below subtotal | US7 |
| 6 | Disclaimers on unpaid / partially paid receipts and on the CFD: "Amounts might change after complete payment." and "{rate}% applicable on Cash Payments" | US9–11 |
| 7 | Refund of a cash payment reverses the discount proportionally | US8, US13 |
| 8 | Cash drawer shows cash actually received; Cash Discount is its own report discount type | PRD §1.2.6 |
| 9 | No manual override; takes effect from the next restaurant day; **gift card purchases excluded** | PRD §1.2 |
| 10 | Card commission split = the same rate, across channels | Config page US3 |

---

## 2. Existing Code Review

Paths under `pos/src/main/java/aio/app/pos/` unless stated. `PC/` = `payment_calculator/src/main/java/app/payment_calculator/`.

### 2.1 Architecture & Layering

- **Nothing exists today.** No cash discount, dual pricing, price configuration or surcharge setting in `pos`, `commons`, `payment_calculator` or the restaurant SDK (`restaurant:0.0.32` has none of these fields). `displayPrice` exists only as a local variable.
- **The calculator has no concept of payment method.** `PaymentCalculator.calculate(TicketData, CalculationConfig)` (`PC/PaymentCalculator.kt:39`) reduces payments to `hasPayments: Boolean`. The result (`PaymentCalculationResult`) has no tip, no tender list and **no per-payment proration** of service charge, tax or tip. Gratuity is just a service-charge config. `DiscountType` has 4 values and **no cash discount type**.
- **Totals are hybrid server/client.** Before payment the client calculates and posts totals. After the first payment the server returns the authoritative `paymentInfo`, and for settled/partial tickets the client **displays the cached server values instead of recalculating** (`CalculationsFromPaymentInfo.kt:23–79`: total, tax, SC and `balanceDue = remainingAmount` all come from the server snapshot).
- **Cash request sends whole-ticket values:** `convenienceFee`, `discount = paymentInfo.discount`, `tax = totalTax`, `serviceChargesAmount`, `total = totalBill`, `subTotal = actualPrice`, `remainingAmount` (client-computed), `paid` (`PayByCashFragment.kt:870–895`). It does **not** send per-payment portions. Whether the server recomputes them is unknown.
- **Split-payment handling is UI-driven.** `BillFragment` reads the remaining balance from the ticket-pane text (`getBalanceDueFromTicketPan`), uses the entered amount, computes remaining as `remaining − entered` with a deprecated Double helper, and passes `amount`/`paidAmount` to the cash fragment (`BillFragment.kt:1715–1790`).
- **Calculation is duplicated:** pre-payment calc, cached `paymentInfo` path, `PaymentViewModel.savingPaymentInfoWithCalculation`, split-ticket calculators and a legacy calculator in `commons/.../paymentcalculator/` still used by `ServiceChargeManagerImp`.

### 2.2 Concurrency

- No coroutine-specific concerns for the calculation itself.
- **Menu refresh:** MQTT `RefreshMenu` → full destructive re-download → the app forces navigation to the clock-in screen (`RegisterFragment.kt:199–215`, `MenuUpdateHelper`). A bulk price update from Super Admin would trigger this, and the PRD says it runs during non-restaurant hours.

### 2.3 Data Layer

- **Menu prices:** `GET /api/menu/pos/{posId}` → Room (`ItemEntity`: `itemPrice`, `totalPrice`, `takeOutPrice`; `ModifierOptionEntity`: `price`, `overridePrice`, `totalPrice`). **No separate display-price field.** If the backend simply sends the display price in `itemPrice`, the base price is lost on the device.
- **Open tickets keep old prices:** a ticket item embeds its own `item` snapshot (`TicketItem.kt:22`) and the calculator prices from it (`resolveBasePrice`, `CalculateItemTotal.kt:51–72`). After a menu refresh, items already on a ticket keep the old price.
- **Price is referenced widely:** `itemPrice` is used in roughly 62 files (ticket adapters, QSR/TSR modifier adapters, `MainViewModel` converts to `Float`, `calculateModifierPrice` mutates the modifier price in place).
- **Commission/processing fee today:** hardcoded on the client. `calculateApplicationOrConFee` = **3.74% (0.78 + 2.7 + 0.26) + 8 cents** (`commons/.../PaymentCalculatorExtensions.kt:283`), sent as `applicationFeeInCents` from `PayByCardFragment.kt:809` (base = amount without tip), `AddTipUnclosedFragment.kt:1250` (base = amount + tip) and `ApiQueueManagerRepoImpl.kt:946`. The gift card path hardcodes 0. **The PRD's "commission split = discount rate" means replacing this in three places with a backend-supplied rate.**
- **`convenienceFee` is dead plumbing:** `Constants.CONVENIENCE_FEE` is read from MMKV in ~10 places and sent in cash, card, tender and gift card requests, but **no code writes it** (checked in `pos` and `commons`), so it is always 0.
- **"Effective from next restaurant day":** the POS has no business-day or effective-date concept. `closeOutTime` only schedules an alarm that restarts the app through splash/onboarding, a natural refresh point. **The backend must serve the already-effective config or an effective-from field.**
- **Refunds:** work per payment from server fields (`refundableAmount`, `refundableTax`, tip, gratuity). `RefundComponents` has **no discount field** and the refund request sends the unchanged payment `discount`. `RefundCalculator` has **no tests**. Editing a refund amount scales service charge and gratuity proportionally (HALF_UP 2 dp) and re-derives tax. Whether the server's `refundableAmount` is gross or net of discount is unknown.

### 2.4 UI & State Management

- **Cash payment** (`PayByCashFragment`, 1,233 lines): step 1 cash tendered (rejected if less than `paidAmount`), step 2 change due, then complete and receipt. A discounted total must change `amount`/`paidAmount` built in `BillFragment` (:1746–1773), `paidAmount` in the request (:875) and the cash drawer log (`CashDrawerLogDetails.amount = paidAmount`, :887–894).
- **No re-tender recomputation exists.** Each method opens a new fragment with static args and reads `paymentInfo` from the DB. PRD "re-tender recomputes discount, tax, service charge, tip, total" is new behavior.
- **Tips:**
  - **Cash tips are blocked today** ("Cash tips cannot be added", `BillFragment.kt:1781–1785`), and the cash request sends `tips = 0`. The PRD says tips are recalculated on the cash-discounted subtotal (US5, US12); which tips are meant for cash is unclear.
  - Suggested tip percentages are based on `ticketAmount` (the total, **including tax and service charge**) or `paidAmount`, depending on `suggestedTipCalculationType` (`ErrorStrings.kt:3484`, `buildSuggestedTips` L4657). The PRD says non-cash suggested tips use the rendered **subtotal**, which is a **behavior change** on the CFD and on receipts.
- **CFD:** pending ticket totals are shown in `TicketPresentationScreen.kt:594–610` (`customer_facing_ticket.xml`); partial-payment amount in `cfd_partial_payment.xml`. The disclaimer goes in both.
- **Receipts** (`printersdk/mappers/TicketReceiptMapper.kt`, 1,410 lines): the financial section is built in `summary()` (L1165–1233) with a `discounts: Map<String, Double>`. The printer SDK renders Subtotal, then each discount entry as a negative line, then service charges, tax and total. **A "Cash Discount" entry in that map lands directly below Subtotal with no SDK change.** But today the single "Discount" value is `payment.discount` (unpaid) or `paymentInfo.discount` (paid), so if the backend folds cash discount into `paymentInfo.discount` it will print as "Discount" and merge with comps.
- **Unpaid-receipt disclaimer needs an SDK change.** `AIOReceiptFooter.Ticket.Unpaid` has only `loyaltyPoints` and `receiptType` and no note field; `Paid` has a `footerNote` (used for the partial note). A footer change needs a printer SDK release, or the note must be added as a line item/summary line.
- **Affected receipt types:** unpaid, paid card, paid cash/tender/gift, Orders Hub / payments-tab reprint (`orderHubReceipt`, ~14 callers), refund, and kiosk/online/QR summary (hides discounts for those sources, L1176). Not affected: KDS, sticky, cash drawer report.
- **Duplicated receipt calls:** `printPaidByCash` is called from cash, tender and gift card fragments; `printPaidByCard` twice in `PayByCardFragment`; four copies of gift card receipt printing.
- **Cash drawer:** records `paidAmount`. If the POS passes the discounted amount, the drawer is correct; if it keeps the base amount, the drawer overstates. Expected cash in reports is server-computed.
- **Reports:** client only formats server DTOs. Z-report discounts have fixed fields (comp, deal, third-party, loyalty); adding Cash Discount needs a DTO field, mapper row, UI row and label, plus the server. Server checkout shows a single `discountAndPromos` total.

### 2.5 Performance & Memory

- Calculation cost is low (per-ticket, in-memory). Adding a payment-method pass is negligible.
- A bulk menu update causes a full menu re-download on every device and forces the clock-in screen. This is an operational cost to plan (non-restaurant hours).

### 2.6 Reliability & Stability (money correctness)

- **Number types:** `PaymentUtilObject`, `PaymentInfo`, refund and `BillFragment` math use `Double` (calculator output goes through deprecated `Amount.toDouble()`); the remaining balance is parsed from UI text; `MainViewModel` uses `Float` for item price.
- **Rounding:** `Amount` keeps a BigDecimal `price` and a `cents` Long accumulated separately (they can diverge, documented in `AmountTest`). Cents use HALF_UP, discount and service charge division uses HALF_EVEN. **There is no proration across payments**, so the sum of parts can differ from the whole.
- **Tax-inclusive businesses** (`isTaxInclusive`) and tax-exempt/takeout exceptions are handled per item; a ticket-level cash discount after the fact needs explicit tax-base rules. They are not covered in the PRD.
- **Fee logging:** payment metadata is sent to New Relic from several payment fragments; this analysis did not audit whether card data is included.

### 2.7 Test Coverage

- `payment_calculator` has 7 test files (rounding, tax, service charge, deals, one recalculation test). **All fixtures use `paymentInfo = null` and `hasPayments = false`**, so the cached/partial-payment path is untested. Fixtures are extensible for cash discount scenarios, but need a model change for a cash portion.
- No tests for refunds (`RefundCalculator`), receipts, tips, or the cash flow.

---

## 3. Identified Issues Log

| # | Category | Issue | Severity | Risk Impact |
| --- | --- | --- | --- | --- |
| I-1 | Backend contract | Server and client calculation ownership is undefined. After the first payment totals come from the server; cash request sends whole-ticket tax/SC/discount; no per-payment discount field | **Critical** | Wrong totals, drawer and reports |
| I-2 | Data | No display-price field on items/modifiers. Backend sending display price in `itemPrice` loses base price | High | Wrong pricing; cannot reverse |
| I-3 | Domain | Calculator has no payment-method or per-payment proration; settled/partial tickets use cached server values | High | Split-payment math cannot be done locally |
| I-4 | Business rule | PRD contradicts itself: "100% cash only, voided on any card portion" vs split payments with proportional discount (US6, US12) | **Critical** | Wrong behavior on every split ticket |
| I-5 | Business rule | PRD examples conflict: US2 says subtotal = **base** prices while US1/§1.2 say **display** prices; scenario 3 uses 5% where the config is 4%; remaining balance after a cash payment is $38.84 in one scenario and $41.28 in another | High | Implementation cannot be verified against the spec |
| I-6 | Money | Double/Float and UI-text parsing in payment math; `cents` and `price` accumulate separately | High | Cent-level mismatches, sum of parts ≠ whole |
| I-7 | Compliance | Disclaimer on unpaid receipts needs a printer SDK change (no footer note field) | High | Non-compliant unpaid receipts |
| I-8 | Data | Cash Discount may merge into "Discount" on receipts and reports if the backend uses `paymentInfo.discount` | High | Indistinguishable from comps (PRD goal missed) |
| I-9 | Refund | No discount field in refund components; discount reversal and gross/net `refundableAmount` undefined; no `RefundCalculator` tests | High | Over- or under-refund |
| I-10 | Tips | Cash tips are blocked today; suggested tips use total (not subtotal); PRD changes the base | Medium | Tip amounts differ from spec |
| I-11 | Commission | Card fee is hardcoded (3.74% + 8¢) in three places with different bases (amount vs amount + tip); `convenienceFee` is dead (always 0) | High | Fee not tied to the configured rate |
| I-12 | Data | Open tickets keep old item prices after a menu refresh; refresh forces the clock-in screen | Medium | Mixed old/new prices on a ticket |
| I-13 | Config | No "effective next restaurant day" concept on the POS | Medium | Rate applied at the wrong time |
| I-14 | Drawer | Drawer logs `paidAmount`; correct only if the discounted amount is passed | Medium | Drawer reconciliation errors |
| I-15 | Architecture | Calculation duplicated (recalc, cached path, split-ticket calculators, legacy calculator); receipt/print calls duplicated | Medium | Easy to miss a path |
| I-16 | Reports | Z-report discount section has fixed fields; adding a type needs DTO, mapper, UI and server | Medium | Cash discount missing in reports |
| I-17 | Scope | Tax-inclusive businesses, tax-exempt tickets and gift card tickets not covered by the PRD | Medium | Wrong tax base |
| I-18 | Test | Cached/partial payment path untested; refund calculator untested | Medium | Regression risk on money code |

---

## 4. Improvement Decision Matrix

| Issue | Fix now? | Justification | Backlog Ticket | Target Sprint |
| --- | --- | --- | --- | --- |
| I-1 Calculation ownership | **Y (first)** | Decide: **backend returns per-payment amounts** (preferred) vs POS computes. One source of truth, shared test vectors | *[Ref]* | *[Sprint]* |
| I-2 Display price field | **Y** | Add `displayPrice` to the menu DTO/Room (migration) and keep base price; POS uses display price for menus and tickets | *[Ref]* | *[Sprint]* |
| I-3 Calculator support | **Y** | Add payment-method and cash-portion inputs and a discount output to `payment_calculator`; keep it pure and tested | — | *[Sprint]* |
| I-4 Split rule conflict | **Y (needs product)** | Must be resolved before coding | *[Ref]* | *[Sprint]* |
| I-5 Spec inconsistencies | **Y (needs product)** | Product to correct the PRD; use agreed numbers as test cases | *[Ref]* | *[Sprint]* |
| I-6 Number types | **Y (scoped)** | Use BigDecimal in the new calculator path and the cash payment amounts; no broad refactor | *[Ref]* (full cleanup) | *[Sprint]* |
| I-7 Unpaid disclaimer | **Y** | Request SDK release for a footer note; fallback is a summary line | *[Ref]* | *[Sprint]* |
| I-8 Separate discount line | **Y** | New `cashDiscount` field in payment info, receipt map key "Cash Discount", and reports | — | *[Sprint]* |
| I-9 Refund | **Y** | Refund must carry and reverse the discount; add `RefundCalculator` tests; confirm server fields | — | *[Sprint]* |
| I-10 Tips | **Y (needs product)** | Define the base for suggested tips and whether cash tips are allowed | *[Ref]* | *[Sprint]* |
| I-11 Commission | **Y (needs backend)** | Replace the hardcoded fee with a backend-supplied rate at all three sites; define the base once | *[Ref]* | *[Sprint]* |
| I-12 Stale prices | **Y (needs product)** | Decide: keep old item prices on open tickets, or reprice; show a notice | *[Ref]* | *[Sprint]* |
| I-13 Effective day | **Y (backend)** | Backend serves only the effective config; POS refreshes on splash/clock-in | *[Ref]* | *[Sprint]* |
| I-14 Drawer | **Y** | Log the discounted amount; add a test | — | *[Sprint]* |
| I-15 Duplication | **N** | Out of scope. Route the new discount through one function used by all paths | *[Ref]* | *[Sprint]* |
| I-16 Reports | **Y (backend + POS display)** | New field and row in Z-report and server checkout | *[Ref]* | *[Sprint]* |
| I-17 Tax rules | **Y (needs product)** | Define tax base for tax-inclusive, tax-exempt and takeout cases | *[Ref]* | *[Sprint]* |
| I-18 Tests | **Y** | Golden-number tests from the PRD scenarios (full cash, card then cash, cash then card, cash then cash, refund) plus the cached/partial path | *[Ref]* | *[Sprint]* |

**Governance:** Calculation correctness is fixed in this iteration and verified against the same numbers on POS and backend. A rollout flag per restaurant is recommended. Nothing is ignored without a ticket.

---

## 5. Impact Assessment

### 5.1 UI Impact
- Menu and ticket show display prices. Payment screen shows "Cash Discount" and the new cash total when Cash is chosen; tip prompt recomputes at tender selection. CFD pending-ticket disclaimer. Receipts get a new line and disclaimers. No navigation change.

### 5.2 API Contract Impact
- **Yes, large.** Price configuration (rate, rounding, decimals, effective date), display price on menu, per-payment discount in payment request/response and `paymentInfo`, refund fields, commission rate, report fields.
- **Versioning:** older POS builds would show base prices and no discount while the menu already carries display prices. Roll out the app update **before** enabling the price configuration for a restaurant.

### 5.3 Database Impact
- **Yes.** New display price column(s) on items and modifier options (Room migration), plus new payment/receipt fields if stored locally. Backfill comes from the next menu download. Rollback: unused columns can stay.

### 5.4 Performance Impact
- Low for calculation. A bulk menu update causes a full re-download on every device.

### 5.5 Backward Compatibility
- Feature is off by default (the price configuration is not enabled). Restaurants not using it see no change.
- **Mixed fleet:** a restaurant with old POS builds and the configuration on would show wrong prices and no discount. Gate enabling on a minimum app version.
- Open tickets across the change: see I-12.

---

## 6. Risk Assessment

| Risk | Probability | Impact | Mitigation |
| --- | --- | --- | --- |
| POS and backend compute different discount, tax or rounding | High | **High** | One source of truth; shared golden test vectors; backend returns amounts |
| Rounding drift across split payments (sum ≠ total) | High | High | Last payment takes the remainder; BigDecimal; tests |
| Over/under refund of a discounted cash payment | Medium | **High** | Carry discount in refund components; test every PRD refund example |
| Cash drawer does not match cash received | Medium | High | Log discounted amount; test; report reconciliation |
| Non-compliant receipts/CFD (missing line or disclaimer) | Medium | High | Receipt snapshot tests; SDK change for unpaid footer |
| Display price breaks open tickets or old POS builds | Medium | High | Version gate; define open-ticket behavior |
| Spec ambiguity leads to wrong implementation | High | High | Resolve §7 questions before coding |
| Tip rules misinterpreted | Medium | Medium | Product decision on base and cash tips |
| Fee/commission mismatch with configured rate | Medium | Medium | Backend-supplied rate replaces hardcoded values |

**Rollback strategy**
- Turn the price configuration off in Super Admin (display price returns to base price from the next restaurant day) with no app release.
- App rollback is safe after the flag is off; new columns can stay. Payments already taken with a discount cannot be undone by a rollback.

**Monitoring plan**
- New Relic events: cash discount applied (ticket id, rate, amount; **no card data**), split proportion used, refund with discount, mismatch between POS-calculated and server-returned totals.
- Alert on any mismatch above one cent and on cash drawer variance for enabled locations.

---

## 7. Open Questions (blocking)

1. **Split rule (I-4):** Is the discount only for 100% cash settlement (PRD §1.2.3) or proportional on each cash portion (US6, US12)?
2. **Spec numbers (I-5):** US2 says subtotal = base prices, US1 says display prices: which? Scenario 3 uses 5% (config is 4%); scenario 1 shows a $41.28 cash total against a $43 remaining balance, scenario 2 shows $38.84 remaining. Please confirm the intended numbers and rounding (per line or at the end).
3. **Who calculates?** Does the backend return per-payment tax, service charge, gratuity and discount, or must the POS calculate and send them? What does the server do with the whole-ticket values the POS sends today?
4. **Display price:** Which field carries it (new column vs `itemPrice`)? Rounding up/down and decimal places come from Super Admin: does the POS only display, or also recalculate?
5. **Open tickets and a menu change:** Do items already on a ticket keep the old price? Does the bulk job run at restaurant close, and what if a device is offline?
6. **Tips:** Can cash payments carry a tip? What is the base for suggested tips (rendered subtotal vs total)? Does it change the existing "total" setting (`suggestedTipCalculationType`)?
7. **Tax:** How do tax-inclusive restaurants, tax-exempt tickets and takeout tax exceptions interact with the discount? Is gratuity taxed?
8. **Refund:** Is `refundableAmount` gross or net of the discount? Does the discount refund scale with the refunded subtotal for every case, including refunds that include tip?
9. **Card commission:** Is the configured rate the only fee sent as `applicationFee` (percentage only, no fixed 8¢)? Which amount is the base (with or without tip)? Is the existing `convenienceFee` retired?
10. **Receipts:** Exact wording and placement of the disclaimers; does the unpaid footer change come from a new SDK release? Do Orders Hub reprints of paid tickets keep the Cash Discount line?
11. **mPOS and Kiosk:** mPOS and Kiosk are not in this repo. Who owns their changes and the shared calculation?
12. **Gift cards:** Gift card purchase is excluded. How is a ticket containing both items and gift cards handled? (Gift card tickets are separate objects today.)
13. **Effective date:** Does the backend or the POS apply "next restaurant day"? What does an open shift see at close-out?
14. **Permissions:** PRD says no override. Confirm that no role can remove the discount at the payment screen.

---

## 8. Final Approval

| Role | Approval |
| --- | --- |
| Engineering Manager Approval | |
| Platform Lead Approval | |
| Squad Lead Approval | |
| Product Acknowledgment | |
| Date | |
