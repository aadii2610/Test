# Tech Analysis — Kiosk Pay at Counter Orders (POS Consumption)

**PRD:** [Kiosk Pay at Counter Orders. POS Consumption](https://app.notion.com/p/aio-app/Kiosk-Pay-at-Counter-Orders-POS-Consumption-3b35b13baaa3807597e3c1d7b69cb2b6)
**Related:** [CR: Send to Kitchen Post Payment](https://app.notion.com/p/aio-app/CR-Send-to-Kitchen-Post-Payment-3b25b13baaa38013a4fdecf2f73afb36) (KDS toggle), `SEND_TO_KITCHEN_POST_PAYMENT_TECH_ANALYSIS.md`
**Scope of this doc:** POS (Android) only. Kiosk app, WebDash and Backend work is listed as dependencies.
**Status:** Pre-implementation analysis. No code changed.

---

## 1. Feature Overview

| Field | Value |
| --- | --- |
| **Jira Ticket** | *(pending)* |
| **Feature Name** | Kiosk Pay at Counter Orders: POS Consumption |
| **Module / Layer Affected** | **Cross-module.** UI (register, Orders Hub, smart panel), Domain/Data (MQTT ingestion, ticket rules, queue), external SDK (`restaurant`), Kiosk app, WebDash, Backend. |
| **Tech Lead / Owner** | *(pending)* |
| **Estimated Complexity** | **L4**. 12 objectives across 5 POS surfaces, with no backend contract or kiosk event confirmed. |
| **Initial Risk Level** | **High**. Unpaid kiosk orders can be lost (auto-discard), sent twice, or voided incorrectly. |
| **Dependencies** | **Backend:** kiosk MQTT event, unpaid kiosk status, item status, auto-void, "Kiosk" attribution, no-trace delete, email field. **WebDash:** "Pay at counter" setting. **SDK:** `restaurant` library (new flag). **Kiosk app:** Cash → Pay at Counter flow. **Internal:** KDS post-payment CR. |
| **Target Release Date** | *(pending)* |

### Business Context

- **Problem:** An unpaid kiosk order has no landing surface, no alert and no consistent kitchen-firing rule on the POS.
- **User impact:** The cashier sees the order the moment it lands, with full item detail, edits it, takes payment (full or partial) and the kitchen fires per a setting.
- **Success criteria:** Every Pay at Counter order appears on the POS with an alert, is never lost or duplicated, and fires to KDS exactly once per the toggle.

### PRD scope → what exists today (summary)

| PRD item | Today in POS | Gap |
| --- | --- | --- |
| Ticket lands on New Ticket screen + Orders Hub | A kiosk ticket is `orderSource == "kiosk"` (`OrderHubSourceEnum.KIOSK`). Kiosk is treated as **already paid** everywhere. | "Pay at counter" / unpaid kiosk does not exist anywhere in code |
| Dedicated Kiosk queue / "Pending" tab | A Pending status tab exists but is **3rd Party only** (`OrdersHubTicketsFragment.setVisibilityHeader` ~L1574) | New tab rule, query and gating |
| Smart panel alert + sound, 15-min expiry, oldest first | `OrderOut` list is 3PO-only (Accept/Reject UI), newest first, in-memory, no fixed timeout | New block type, sort, expiry, persistence |
| Edit rules (items not editable, deletable, add new) | No item-level lock; only status-based locks | New kiosk-origin rule at 4 sites |
| Order type change + table tent | Both exist. Table tent icon is **not tied to dine-in**. | Prompt/gate on Takeaway → Dine In |
| Change guest (not with loyalty/deal) | No "remove loyalty first" check. Changing the guest **silently deletes loyalty items**. | New pre-check |
| Tax exemption + audit | Kebab checkbox only. No reason, no employee captured. | Reason, employee, backend field |
| Void / Delete pending ticket | No void or delete in Orders Hub. Delete allowed only for `pos`/`mPos`. | New action, source allow-list, no-trace delete |
| Auto-void at clock-out | Clock-out cleans only the employee's empty/pending tickets for `pos`/`mPos` | Location-wide unpaid kiosk void |
| No server, attribute to "Kiosk" | Null employee renders `--`. "Kiosk" source for sales/tips is not implemented. | Attribution (mostly backend) |
| Remove tip prompt on kiosk; email to POS | No ticket-level email field | Backend + model field |
| KDS toggle | Not implemented (see KDS CR) | Dependency |

---

## 2. Existing Code Review

Paths under `pos/src/main/java/aio/app/pos/` unless stated.

### 2.1 Architecture & Layering

- **How tickets from other devices arrive:** `ApplicationRepositoryImpl.observeMQTT` (L683) → sequence validation → `dispatchMessage` (L958; `CREATE_TICKET` L1187) or `posOtherThanTicketFlowDispatchMessage` (L1474; 3PO, QR, online) → `UpdateTicketRepositoryImpl` writes to Room.
  - **Which MQTT event a kiosk ticket would use is not visible in code.** Comments mention "Online Website And Kiosk No Payment Event". The kiosk-settings MQTT event is commented out.
  - **A new event type must be added in three places:** `dispatchMessage`, `posOtherThanTicketFlowDispatchMessage`, and `TicketSequenceRepositoryImpl.handleSequenceMismatch` (reconcile).
  - MQTT messages older than **10 minutes are dropped** (`isOlderThanTenMinutes`). A POS offline longer than that relies on reconcile/fallback sync.
- **No auto-open of incoming tickets.** `MqttEvent` has no "ticket received" variant. The register builds its current ticket by selection or creation only.
- **Smart panel:** the alert block is the `OrderOut` list in `SmartPanelRepositoryImpl` (L55–110), fed only by `addOrderOutItem` from `handleThirdPartyOrderReceived`. Its UI (`order_out_item.xml`) is 3PO-specific (Reject / Accept & Fire). The panel is hosted in `RegisterActivity` (`SmartPanelFragment`) and in Orders Hub through `AioIntelligenceOrderHubSettings`.
- **Orders Hub reads Room with one-shot queries**, refreshed on `onResume`, after fallback sync and after fire/cancel. **It is not reactive**, so a kiosk ticket arriving while Orders Hub is open will not appear until one of those triggers fires.
- **The PRD's "All orders dropdown" does not exist in code.** Orders Hub has two tab rows (type tabs, status tabs). The "Pending" tab maps to the status row.
- **Settings:** `aioRestaurant.appState` (external SDK `restaurant:0.0.32`) via `BusinessIdUseCase`. No "pay at counter" flag exists, so a new boolean needs an SDK release and a version bump in `pos/build.gradle`. There is no MQTT settings push, so a changed toggle reaches the POS on the next refresh.
- **Large classes on the change path:** `UpdateTicketRepositoryImpl` 3,628 lines, `ApplicationRepositoryImpl` 2,497, `OrdersHubParentActivity` 2,961, `OrdersHubTicketsFragment` 1,620, `TicketFragment` 4,669 (39 `IS_QUICK_SERVE` branches), `RegisterActivity` 4,938.

### 2.2 Concurrency

- MQTT collection runs on IO. `OrderOut` uses a synchronized list plus StateFlow and a **1-second global polling loop** (`orderOutUpdateJob`, L303) that removes items only when `autoFire && remaining == 0`. There is no 15-minute expiry.
- **Sound:** `RingtoneRepositoryImp` wraps a single `MediaPlayer`; each `play()` releases the previous one, so **back-to-back alerts cut each other off**. Not a leak.
- **Unknown:** most QSR click handlers mix `lifecycleScope` Main/IO and use unbounded `firstOrNull` waits on `ticketApiState`. Queued tasks need a remote ticket id (`QSR_REMOTE_CREATE_REQUIRED_TASK_TYPES`).

### 2.3 Data Layer

- **No guest-email field on the ticket.** Only `customerName`, `phoneNumber`, and `customer.emailAddress` on the customer object.
- **Kiosk ticket payment state:** the calculator treats KIOSK as non-online. Unpaid recalculates locally; Partial on kiosk uses the **cached server value** (`CalculationsFromPaymentInfo.kt:35`), which may be wrong after a partial payment on an editable ticket.
- **Orders Hub kiosk pane is forced to "goToPayment-print"** (`TicketUtils.kt:298`), so an unpaid kiosk ticket loses "Go to Order" (`GoToOrderScreen.kt:109`).
- **Void is queued offline-first** (`VOID_ORDER`, payload `reason` + `status` only, no employee id). A partially paid ticket **cannot be voided** (toast "Cannot void partially paid or unclosed tickets") and refunds are a separate API. The PRD (US11) expects void with automatic refund/reconcile.
- **"No-trace delete"** exists only as discard of empty/all-pending tickets, allowed only for `pos` and `mPos` (`TicketViewModel.kt:648`). Whether the backend leaves a report trace is not visible from the client. Note: `OrderSourceEnum.MPOS = "mPos"` vs `OrderHubSourceEnum.MPOS = "mpos"` (casing mismatch).
- **Fallback sync wipes and reinserts Room**, and does not notify Orders Hub (comment at `OrdersHubParentActivity.kt:611`).

### 2.4 UI & State Management

- **Item locking:** there is no `isEditable`/`isLocked` flag on `TicketItem` and no source on the item. The only lock is item status (`TicketMultiViewAdapter.kt:90–110`). Edit entry points: `QsrMenuFragment.whenTicketItemClickToEdit` (L2307) and `whenTicketItemMultiSelectToEdit` (L2123), header from `QsrMenuRepoImpl.kt:330–346`. **TSR duplicates this** (`PagerTicketFragment`, `TsrMenuFragment`), so the rule is needed at 4 sites.
- **Order type:** toggle at `TicketFragment.kt:3141/3154` (blocked when partially paid) → `UPDATE_ORDER_TYPE` → local update, `OrderTypeSwitch`, `updateServiceCharge`. Order-type charges recalculate already (US8 is largely covered).
- **Table tent:** icon shows whenever `isTableTentEnabled` is on; dialog `TicketFragment.kt:2237`; disabled once a number exists. There is **no dine-in check** and no auto-prompt after switching to Dine In.
- **Customer:** QSR freezes the name once set (X to clear). `canRemoveTicketCustomer` blocks only when items were sent to KDS. **Clearing or changing the customer deletes all loyalty items without warning** (`TicketViewModel.kt:313`). Deals are not handled.
- **Tax exemption:** kebab checkbox `menuItemTax` → `UPDATE_TAX_EXEMPTION` (boolean only). Manager approval callback carries no approver id, so audit is New Relic logs only (US10 requires reason + employee).
- **Payments:** Card/Cash/Other buttons, partial payment supported. After a partial payment, item taps, kebab, order type, customer and tax are all locked. **There is no "payment mode popup" and no zero-total checkout path** (PRD mentions "O dollar checkout will not have payment mode pop up").
- **Tips:** QSR card tip is chosen on the customer display with a Skip button; QSR cash has no tip in the payment flow. PRD's "capture tipping on POS" needs a product decision on where.

### 2.5 Performance & Memory

- `OrderOut` is **in-memory only**, so alerts are lost on process death.
- The 1-second polling loop runs globally for all items. Acceptable for a few items; must be removed or reworked for a time-based expiry.
- No new network payloads are expected beyond the new kiosk event.

### 2.6 Reliability & Stability

- **Auto-discard can delete a kiosk ticket.** If kiosk items arrive with status `pending` and the cashier opens the ticket then navigates away, `RegisterActivity.onEmptyTicket` (L1243–1290) and `onDestroy` (L4262–4282) call the discard paths. They are guarded by the `pos`/`mPos` allow-list on the synced-order path (`TicketViewModel.kt:648`), but other paths (MQTT `removeEmptyTicket`, `EmployeeEmptyTicketCleanupUseCase`) need to be checked against the new ticket state. **The kiosk item status is not verifiable from code.**
- **Clock-out:** `ClockInViewModel.clockOut` (L200) runs `cleanupEmptyTicketsForEmployee` (L202), employee-scoped and local-DB driven, then drains the queue (12 s cap) and makes an **online-only** server call. There is no unpaid-ticket check at clock-out. A manager clocking out another employee goes through a different path (`EmployeeScheduleFragment.kt:253` → `clockOutEmployeeFromDashboard`) and **bypasses the hook**. Multiple devices/employees would queue duplicate voids (the queue is per device).
- **Void/Delete need approval rules:** PRD US11 asks for a reason + timestamp + original kiosk order number log; today void sends `reason` + `status` only.
- **Duplicated click handler:** the `rlPending` click listener is registered twice (`OrdersHubTicketsFragment.kt:1012`, `:1038`).

### 2.7 Test Coverage

- No tests for MQTT sequencing, `UpdateTicketRepositoryImpl`, `SmartPanelRepositoryImpl`, Orders Hub, QSR void/tax/customer/order-type rules, clock-out cleanup, or kiosk handling. Existing tests: `TicketMappersTest`, `ApiQueueRetryLimitTest`, `MenuRefreshPocTest`, `payment_calculator` tests.

---

## 3. Identified Issues Log

| # | Category | Issue | Severity | Risk Impact |
| --- | --- | --- | --- | --- |
| I-1 | Backend contract | Kiosk MQTT event, unpaid status, item status and payload (email, "Kiosk" source) are undefined | **Critical** | Blocks the design |
| I-2 | Data loss | Discard paths may delete a kiosk ticket opened then left on the register (depends on item status) | **Critical** | Unpaid customer order lost |
| I-3 | Architecture | "Pay at counter" flag needs an SDK release, version bump and settings refresh (no push) | High | Tab/flow gating wrong or delayed |
| I-4 | Architecture | `OrderOut` and smart panel UI are 3PO-specific; newest-first sort; no 15-min expiry; in-memory only | High | Alert block cannot be reused as-is |
| I-5 | Reliability | Orders Hub is not reactive; incoming kiosk tickets do not appear until refresh | High | Cashier misses orders (PRD US4, US15) |
| I-6 | Data | Partial payment on kiosk uses cached server paymentInfo (calculator) | High | Wrong totals after cashier edits |
| I-7 | Reliability | Auto-void at clock-out: employee-scoped, local-only, online-only, bypassed by manager clock-out, per-device queue | High | Unpaid tickets stay open or are voided twice |
| I-8 | Business rule | Void blocked for partially paid tickets; no refund in the void flow. PRD US11 expects refund/reconcile | High | Cannot meet US11 |
| I-9 | Business rule | Changing the guest silently deletes loyalty items; deals unhandled (PRD: must remove first) | High | Silent data loss; PRD rule unmet |
| I-10 | Business rule | Delete allowed only for `pos`/`mPos`; Orders Hub has no void/delete; "no report trace" not verifiable | High | PRD's delete cannot work |
| I-11 | Architecture | Item-edit lock rule needed at 4 sites (QSR + TSR duplicates) | Medium | Inconsistent locking |
| I-12 | Business rule | Tax exemption has no reason/employee; approver id not captured | Medium | Audit requirement unmet |
| I-13 | UX | Table tent not tied to dine-in; no prompt on Takeaway → Dine In | Medium | PRD flow missing |
| I-14 | Data | No ticket-level guest-email field | Medium | Email cannot reach POS ticket |
| I-15 | Reliability | MQTT messages older than 10 min dropped; relies on reconcile | Medium | Late-arriving kiosk order missed |
| I-16 | Concurrency | Single `MediaPlayer`; back-to-back alerts cut each other off | Low | Missed sound |
| I-17 | Bug | Duplicate `rlPending` click listener (`OrdersHubTicketsFragment.kt:1012`, `:1038`) | Low | Double handling on tap |
| I-18 | Bug | `mPos` vs `mpos` casing mismatch in delete allow-list | Low | Silent misses |
| I-19 | Test | No tests on any affected area | Medium | Regression risk |

---

## 4. Improvement Decision Matrix

| Issue | Fix now? | Justification | Backlog Ticket | Target Sprint |
| --- | --- | --- | --- | --- |
| I-1 Backend contract | **Y (first)** | Everything depends on it. Agree event, statuses and payload before POS work | *[Ref]* | *[Sprint]* |
| I-2 Auto-discard | **Y** | Governance: data loss → must fix. Exclude unpaid kiosk tickets from all discard/cleanup paths and add a test | — | *[Sprint]* |
| I-3 SDK flag | **Y** | Required. Release and verify the flag persists in Room first | *[Ref]* | *[Sprint]* |
| I-4 Alert block | **Y** | New kiosk card type in the smart panel: oldest first, 15-min expiry from placed time, sound. Reuse the repository, add a kiosk item type | — | *[Sprint]* |
| I-5 Orders Hub refresh | **Y** | Trigger a refresh when a kiosk ticket is inserted (event or Room Flow for this tab only). Do not rewrite Orders Hub | *[Ref]* (full reactive Orders Hub) | *[Sprint]* |
| I-6 Cached paymentInfo | **Y (verify)** | Confirm with calculator owner; recalc locally for unpaid/partial pay-at-counter | — | *[Sprint]* |
| I-7 Clock-out auto-void | **Y (backend-led)** | Recommend server-side auto-void in the clock-out endpoint (reaches all devices, covers manager clock-out). POS only reacts to the MQTT void | *[Ref]* | *[Sprint]* |
| I-8 Void with partial payment | **Y (needs decision)** | Product/backend must define refund behavior | *[Ref]* | *[Sprint]* |
| I-9 Guest change rule | **Y** | Add a pre-check; block and tell the user to remove loyalty/deal first | — | *[Sprint]* |
| I-10 Void/Delete action | **Y** | Add Void and Delete (with confirm popup) to the ticket kebab and the Orders Hub pane for unpaid kiosk tickets. Add `kiosk` to the delete allow-list for this case only | — | *[Sprint]* |
| I-11 Item lock rule | **Y** | One predicate (`isKioskOrigin`), used at all 4 sites. No QSR/TSR merge | — | *[Sprint]* |
| I-12 Tax exemption audit | **Y (needs backend)** | Reason dialog + employee fields on the request | *[Ref]* | *[Sprint]* |
| I-13 Table tent | **Y** | Auto-prompt after Takeaway → Dine In for kiosk tickets, reusing the existing dialog | — | *[Sprint]* |
| I-14 Email field | **Y (needs backend)** | Add the field to the ticket model/DTO if the backend sends it | — | *[Sprint]* |
| I-15 10-min drop | **N** | Rely on reconcile. Verify kiosk events carry sequence data | *[Ref]* | *[Sprint]* |
| I-16 MediaPlayer | **N** | Low impact; log for backlog | *[Ref]* | *[Sprint]* |
| I-17 Duplicate listener | **Y** | Same file we edit; remove one | — | *[Sprint]* |
| I-18 `mPos` casing | **Y** | One-line fix, same code we touch | — | *[Sprint]* |
| I-19 Tests | **Y (scoped)** | Unit tests for the new pure rules: discard exclusion, item lock, guest change, Pending tab query | *[Ref]* (broader coverage) | *[Sprint]* |

**Governance:** Data loss and crash risks are fixed in this iteration. Architectural exceptions need explicit approval. Nothing is ignored without a ticket.

---

## 5. Impact Assessment

### 5.1 UI Impact
- **New Ticket screen:** ticket persists after navigating away; locked kiosk items (delete only, add allowed); kebab with Void and Delete; table tent prompt; red-flag abandonment cue (needs design for the threshold).
- **Orders Hub:** "Pending" tab visible for kiosk orders only when "Pay at counter" is ON; Go to Payment / Go to Order for unpaid kiosk; Void/Delete in the ticket pane.
- **Smart panel:** new kiosk alert card on register and Orders Hub, with sound; oldest on top; removed after 15 min.
- **Payment:** no changes to the selector. KDS firing follows the toggle (Save & Send disabled when ON).
- **Navigation:** no new graph destinations.

### 5.2 API Contract Impact
- **Yes.** New kiosk event / ticket payload (unpaid status, item status, email, "Kiosk" source), void and delete behavior for kiosk tickets, tax exemption reason/employee, auto-void at clock-out, and the new setting. Older POS builds must ignore unknown fields.

### 5.3 Database Impact
- New setting column in the SDK `RestaurantEntity` (migration). Possible new ticket fields (email, kiosk flags). No backfill, rollback is safe (columns unused).

### 5.4 Performance Impact
- Low. One more MQTT event type, one extra smart-panel item type, and a refresh trigger on Orders Hub. Remove the 1-second polling loop for kiosk items.

### 5.5 Backward Compatibility
- Setting defaults to OFF, so behavior is unchanged until enabled in WebDash.
- Mixed fleet: older POS builds will show kiosk tickets as already paid (current behavior) and may not show the alert.
- Gradual rollout via the WebDash toggle per location. No separate app flag.

---

## 6. Risk Assessment

| Risk | Probability | Impact | Mitigation |
| --- | --- | --- | --- |
| Unpaid kiosk ticket auto-discarded or cleaned up | Medium | **High** | Exclude from all discard paths; add tests; verify item status with backend |
| Backend contract not ready or different | High | **High** | Agree the contract first (I-1); POS work starts after |
| Cashier misses an order (Orders Hub not live, sound cut off, panel lost on restart) | Medium | High | Refresh trigger on insert, persist alerts, queue sound |
| Wrong totals after partial payment (cached paymentInfo) | Medium | High | Verify and recalc locally |
| Duplicate or missed auto-void at clock-out | Medium | Medium | Server-side void; POS reacts to MQTT |
| Void with partial payment fails | High | High | Product/backend decision on refund (I-8) |
| Silent loyalty deletion on guest change | High | Medium | Pre-check with message (I-9) |
| Kitchen double fire (post-payment + manual) | Medium | High | Reuse the KDS CR's single-send rule |
| Scope growth (tips on POS, red-flag, order-ready email) | Medium | Medium | Confirm scope in Open Questions |

**Rollback strategy**
- Turn "Pay at counter" OFF in WebDash to stop kiosk pay-at-counter orders and hide the Pending tab, with no app release.
- If the POS change has a defect, halt rollout or revert the PR. New columns can stay.

**Monitoring plan**
- New Relic events: kiosk ticket received, alert shown/expired, ticket discarded (should be zero for unpaid kiosk), void/delete (with reason), auto-void, post-payment send.
- Alert on unpaid kiosk tickets older than the abandonment threshold and on kiosk tickets that disappear without a void/delete/pay event.

---

## 7. Open Questions (blocking)

1. **Backend:** Which MQTT event carries a kiosk pay-at-counter ticket? Is it sequenced, and are `deviceIds` targeted or broadcast? What are the ticket status, item status (`pending` or `send`) and payment status on arrival?
2. **Item delete:** Is deleting a kiosk item a "Remove" (no approval) or a "Void" (permission, manager approval)?
3. **Void with partial payment:** What is the refund/reconcile behavior (US11)? Today a partially paid ticket cannot be voided.
4. **Delete (no trace):** Does the backend hard-delete with no report trace? Which sources and roles may delete?
5. **Auto-void at clock-out:** Is it server-side? Which clock-out counts (employee, manager, location-wide)? What happens offline?
6. **Tip capture:** Where is tipping captured on POS after the kiosk tip prompt is removed (payment screen, add tip later)? PRD only says "capture tipping on the POS".
7. **Attribution:** How is "Kiosk" set as sales/tip source with no server? Backend, POS, or both?
8. **Red-flag abandonment cue:** What threshold and where does it show? The PRD mentions it but defines no rule.
9. **15-minute side panel:** Is it counted from the kiosk order time or the POS arrival time? Does the alert reappear after dismissal or restart?
10. **Guest email:** Which field on the ticket, and does the POS show it or only keep it?
11. **KDS toggle:** Per-location setting shared with the KDS CR. Is it the same flag as "Send to Kitchen post payment"?
12. **Pending tab wording:** The PRD says an "All orders" dropdown. Orders Hub has tab rows. Confirm the design maps to the existing Pending status tab.

---

## 8. Final Approval

| Role | Approval |
| --- | --- |
| Engineering Manager Approval | |
| Platform Lead Approval | |
| Squad Lead Approval | |
| Product Acknowledgment | |
| Date | |
