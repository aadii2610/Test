# Tech Analysis — Ticket, Menu & Payment UI/UX Clean Up

**PRD:** [PRD: Ticket, Menu & Payment UI/UX Clean Up](https://app.notion.com/p/aio-app/PRD-Ticket-Menu-Payment-UI-UX-Clean-Up-3bb5b13baaa381be9759de090c2ff949)
**Figma:** [POS Menu – Ticket](https://www.figma.com/design/kvAxOA6nKvmsLCFKsUUkGp/POS-Menu---Ticket?node-id=16108-26229&t=TSFi3iXvvx3K6wFo-1)
**Status:** Pre-implementation analysis. No code changed yet.

---

## 1. Feature Overview

| Field | Value |
| --- | --- |
| **Jira Ticket** | *(pending)* |
| **Feature Name** | Ticket, Menu & Payment UI/UX Clean Up |
| **Module / Layer Affected** | **UI only** (`:pos` layouts, adapters, fragments). One small **Data/UI-state** addition for connected-device info in the hamburger panel. |
| **Tech Lead / Owner** | *(pending)* |
| **Estimated Complexity** | **L3**. No business logic changes, but it touches 6 surfaces, ~100+ layout files, and spacing that is partly set in Kotlin. |
| **Initial Risk Level** | **Medium**. Wide visual blast radius, no UI/screenshot tests, and several God fragments. |
| **Dependencies** | **Internal:** Figma sign-off, `HeaderFragment`, `left_nav_menu.xml`, `DeviceStatusViewModel`, `DialogExtensions.kt`. **External:** none. No API or backend change. |
| **Target Release Date** | Release 8.0 |

### Business Context

- **Problem:** Header, ticket pane, Menu screen, dropdowns, hamburger panel and pop-ups use different spacing and design patterns, so the POS feels fragmented.
- **User impact:** POS staff (QSR/TSR) and managers get a consistent, easier-to-read UI. The hamburger panel also shows which devices are connected.
- **Success criteria (from PRD):** 0 open UI-consistency QA defects, and 100% of impacted dropdowns and pop-ups follow the single design standard before 8.0 sign-off.

### Scope by area

| Area | Main code | Shared or duplicated |
| --- | --- | --- |
| Header | `HeaderFragment.kt` + `fragment_header.xml` | **Shared.** One fragment used by Register, Dashboard, Settings, Orders Hub, Gift Card and Cash Drawer. |
| Ticket pane | `TicketFragment.kt` + `TicketMultiViewAdapter` + 5 `ticket_*_item_layout.xml` | **Shared** by QSR and TSR, but the 5 status row layouts are near-copies. |
| Menu screen | `QsrMenuFragment` + `qsr*` adapters / `TsrMenuFragment` + `tsr*` adapters | **Duplicated.** QSR and TSR are separate stacks. |
| Payment | `PaymentParentFragment`, `TicketPanFragment`, `BillFragment`, `PayBy*Fragment` | Own layouts and own ticket row (`row_items_bill.xml`). Shares nothing with Ticket/Menu. |
| Dropdowns | 5 different mechanisms (see §2.4) | **Ad hoc.** No shared component. |
| Hamburger panel | `left_nav_menu.xml` inside a `NavigationView` | Layout is **shared**. The drawer container and click wiring are **copied into 6 activities**. |
| Pop-ups | 63 `DialogFragment`s + 54 raw `Dialog`s, 108 dialog layouts | **Ad hoc.** No base dialog class. |

---

## 2. Existing Code Review

### 2.1 Architecture & Layering

- **Header is in good shape.** It is one `HeaderFragment` added in code to `headerFrameLayout` in each activity (e.g. `RegisterActivity.kt:600`), so a single change covers every screen.
- **The hamburger drawer is copied.** The `NavigationView` is declared in 6 activity layouts, and each activity wires drawer clicks itself (`RegisterActivity.setupDrawerClicks()` ~L1100, plus Dashboard, Settings, OrdersHub, GiftCard, CashDrawer). Changing the drawer's items or structure means touching all 6. Spacing-only changes in `left_nav_menu.xml` need just one edit.
- **Menu layout is built in Kotlin, not XML.** RecyclerViews, LayoutManagers, span counts, heights and margins are created in code:
  - QSR: `QsrHandlerImpl.kt:66, 93–102, 255–279`, `QsrMenuRepoImpl.kt:758–785`
  - TSR: `TsrMenuManagerImpl.kt` (L106–559, heights `740`/`110` px), `TsrModifierManagerImpl.kt:120`
  - Shared helpers: `ErrorStrings.kt:1334–1388`, which calls `setPadding(20, 5, 20, 5)`

  **XML-only spacing changes will not fix the Menu screen.**
- **The ticket row is not composed from views.** Modifiers, special request and discount are drawn as one `TextView` using `SpannableStringBuilder`, `"\n"` and `LeadingMarginSpan` (`Utils.kt:151, 200, 667`). Spacing here is controlled in code.
- **There is no design system.** The theme is `Theme.MaterialComponents.Light.NoActionBar` with 59 ad-hoc styles. Spacing is a mix of sdp/ssp (`_12sdp`), literal dp (~8,300 across 494 layouts) and a 20-entry `dimens.xml` with no spacing scale. There is no Compose.

### 2.2 Concurrency & Coroutines

- No coroutine work is required for a visual change.
- **Connected-device state is fragmented and partly broken** (relevant to the hamburger "connected device info" story):
  - `DeviceStatusViewModel` exposes booleans only (`isInternetConnected`, `isKDSConnected`, `isPaymentTerminalConnected`) plus `printerErrors: StateFlow` (printer names). No source gives "device name + status" for every device.
  - **Bug:** `DeviceHeatBeatHelper` sends terminal/printer status via `LocalBroadcastManager` (L190), but `BaseActivity.kt:251` registers with `Context.registerReceiver`. LocalBroadcastManager broadcasts never reach that receiver, so `isPaymentTerminalConnected` is likely never updated.
  - `Constants.terminalStatus` is a global `@Volatile var` read by Bill, Ticket and PayByCard fragments. No writer was found in `:pos` apart from the reset in `Application.kt:294` (it may be set from a library; needs checking).
  - `HeartBeatViewModel` holds a second, parallel `DeviceStatuses` LiveData. `SharedDataRepositoryImpl._terminalStatus` exists but nothing collects it.
  - Printer and terminal health is **polled every 60s** (`SharedDataRepositoryImpl.kt:360–378`), so the status shown can be up to 60s old.

### 2.3 Data Layer

- **No Room, API or DTO changes.**
- The terminal reader name/serial is only fetched ad hoc in `TerminalStatusDialogFragment.kt` (~L97, 143, 233–271). Showing a terminal name in the hamburger panel needs a small repository-level source for it.

### 2.4 UI & State Management

- **Header height conflict:** the activity container is sized by a guideline (`constraintGuide_percent="0.075"`, `activity_register.xml:41`), but the `fragment_header.xml` root is hardcoded to `100dp`. The two can disagree depending on screen size.
- **Screen sub-bars diverge:** QSR `clTop` (wrap_content, sdp margins) and TSR `clTop` (fixed 60dp, 12/16/20dp margins) are different layouts.
- **Dropdowns — 5 mechanisms, no shared component:**

  | Mechanism | Count | Key call sites |
  | --- | --- | --- |
  | `PopupWindow` | ~15 | `QsrMenuRepoImpl.kt:193` (Menu), `SearchFragment`, `CustomItemRepoImpl`, `OnlineOrdersPopUpHelper`, cash-drawer screens |
  | `ListPopupWindow` | 4 | NoSale, PayOut, CashIn, CashOut |
  | `PopupMenu` | 3 | `TicketFragment.kt:1355, 4050, 4349` (options, tax, check) |
  | `Spinner` | 2 | Table builder screens |
  | Exposed dropdown | 3 | Menu Manager item edit |

  - The **header dropdown is built two ways** from the same `dropdown_layout.xml`. RegisterActivity shows it as a full-screen raw `Dialog` (`DialogExtensions.kt:1096`). The other 5 activities use an `<include>` toggled by visibility.
  - There are 4 competing styles: `PopupMenuStyle` (M3), `Widget.App.PopupMenu` (MDC), `SpinnerStyle` (AppCompat), `ItemEditDropdownLayoutStyle`.
- **Pop-ups — no standard:**
  - 63 `DialogFragment`s, 54 raw `Dialog`s (18 in `DialogExtensions.kt`, 8 inline in `RegisterActivity`) and 3 `AlertDialog.Builder`s.
  - Window width is set in code with 10+ different ratios (0.25 → 0.85). `setLayout(` appears in 72 files.
  - 59 of 108 dialog layouts have **both** a close-X and a "Go back" button.
  - Button order is mostly Cancel-left / Positive-right. `layout_multiple_device_popup.xml` stacks them vertically instead.
  - The best existing base is `DiscardConfirmDialogFragment` + `dialog_discard_confirm.xml` (title / message / cancel | confirm) and `commons/.../DialogSizing.kt` `centerWithSize()`.

### 2.5 Performance & Memory

- **Raw-px spacing set in code:** `setMargins(7, 7, 7, 7)` / `(7, 10, 7, 10)` in nearly every TSR and tiled adapter (`TsrItemsAdapter:84`, `TiledItemsAdapter:56`, `QsrItemAdapter:59`, …). These are pixels, not dp, so gaps change with screen density. They must be converted for spacing to be consistent.
- **Ticket and modifier lists use `notifyDataSetChanged`** (`TicketMultiViewAdapter:76, 87`, all QSR/TSR modifier adapters). Not caused by this work, but full rebinds can cause flicker that QA may log as layout defects.
- **Dialog leak / crash risk:**
  - None of the 20 `Activity.*` dialog extensions in `DialogExtensions.kt` checks `isFinishing`/`isDestroyed`, and `BadTokenException` is never caught.
  - Extension dialogs are local `val`s, so nothing can dismiss them in `onDestroy`.
  - `RegisterActivity.onDestroy` dismisses only the break dialog. `voidOrderDialog`, `sendToKdsWarningDialog`, `receiptDialog` and `managerApprovalDialog` are not dismissed.
  - 171 `DialogFragment.show()` calls have only 27 `isStateSaved` checks.

### 2.6 Reliability & Stability

- **Large files on the change path:** `RegisterActivity` 4938 lines, `TsrMenuFragment` 4782, `TicketFragment` 4669, `ErrorStrings.kt` 4672, `QsrMenuFragment` 3783, `BillFragment` 2613, `TicketPanFragment` 2512, `PayByCardFragment` 2146. The risk of an accidental logic change while editing layouts is real.
- **Bug:** `left_nav_menu.xml:183` contains stray text `tvQuickServe` before the Settings `<TextView`.
- Crash/ANR data (last 30–90 days) for the dialog paths above: *to be pulled from Crashlytics/New Relic before sign-off.*

### 2.7 Test Coverage

- **No UI or screenshot tests.** The only instrumentation test is `ExampleInstrumentedTest`. There are 5 unit test files in total, none covering UI.
- Robolectric, Paparazzi and Roborazzi are not set up. Visual regressions will only be caught by manual QA.

---

## 3. Identified Issues Log

| # | Category | Issue | Severity | Risk Impact |
| --- | --- | --- | --- | --- |
| I-1 | UI / Performance | Menu and adapter spacing set in Kotlin in **raw px** (`setMargins(7,…)`, `setPadding(20,5,20,5)`, heights `740`/`110`) | High | Spacing cannot be made consistent through XML alone and looks different on each density |
| I-2 | Architecture | No spacing tokens or text styles. sdp, literal dp and a 20-entry `dimens.xml` are mixed | High | Without a single source of truth the clean-up will drift again |
| I-3 | Architecture | No standard dialog component. 54 raw `Dialog`s, 63 `DialogFragment`s, 10+ width ratios | High | Directly blocks the "pop-up consistency" story |
| I-4 | Architecture | No standard dropdown. 5 mechanisms and 4 styles; header dropdown built two ways | High | Directly blocks the "dropdown consistency" story |
| I-5 | Memory / Crash | Raw dialogs shown without `isFinishing` guards and not dismissed in `onDestroy` (`DialogExtensions.kt`, `RegisterActivity`, `PayByCardFragment.reinitializingDialog`, `AddTipUnclosedFragment.tipDialog`) | High | Window leaks and `BadTokenException` crashes |
| I-6 | Reliability | Terminal status receiver registered with `registerReceiver` but sent via `LocalBroadcastManager` (`BaseActivity.kt:251` vs `DeviceHeatBeatHelper.kt:190`) | High | The hamburger panel would show a wrong/stale terminal status |
| I-7 | Architecture | Device status split across `DeviceStatusViewModel`, `HeartBeatViewModel`, `Constants.terminalStatus` and unused `_terminalStatus`. No name + status model | Medium | Connected-device info needs a new unified source |
| I-8 | UI | Header height: guideline 7.5% vs hardcoded `100dp` root | Medium | Header clipping or gaps on some screen sizes |
| I-9 | Architecture | Ticket status row layouts are 5 near-copies, plus a separate payment row | Medium | Every spacing change must be repeated in ~6 files; easy to miss one |
| I-10 | Architecture | Drawer container and click wiring copied into 6 activities | Medium | Structural drawer changes need 6 edits |
| I-11 | UI | Modifier, special-request and discount spacing is Spannable text (`Utils.kt:151–202, 667`) | Medium | Spacing changes touch shared rendering used by several bindings |
| I-12 | Bug | Stray `tvQuickServe` text in `left_nav_menu.xml:183` | Low | Possible visible artefact / lint error |
| I-13 | Performance | `notifyDataSetChanged` on ticket and modifier lists | Low | Flicker; QA noise |
| I-14 | Test | No UI/screenshot tests for any affected screen | Medium | Visual regressions only caught manually |

---

## 4. Improvement Decision Matrix

| Issue | Fix now? | Justification | Backlog Ticket | Target Sprint |
| --- | --- | --- | --- | --- |
| I-1 Raw-px spacing in code | **Y** | Required to meet the PRD. Convert to `@dimen` tokens via `resources.getDimensionPixelSize` | — | *[Sprint]* |
| I-2 Spacing tokens + text styles | **Y** | Foundation for every story. Add `dimens_spacing.xml` (4/8/12/16/24) and `TextAppearance.Pos.*` | — | *[Sprint]* |
| I-3 Standard dialog | **Y** | Required by PRD. Build one `PosDialogFragment` + `layout_pos_dialog.xml` (title → body → Cancel \| Primary) | — | *[Sprint]* |
| I-4 Standard dropdown | **Y** | Required by PRD. One `PosDropdown` helper (PopupWindow + shared style); migrate `PopupMenu`/`ListPopupWindow` sites in scope | — | *[Sprint]* |
| I-5 Dialog leaks / crashes | **Y** | Governance: memory leak or crash → must fix. Covered for free by migrating in-scope dialogs to `PosDialogFragment`; add guards to the remaining raw dialogs we touch | — | *[Sprint]* |
| I-6 Broadcast receiver mismatch | **Y** | One-line fix. Needed for correct device status in the hamburger panel | — | *[Sprint]* |
| I-7 Unified device status model | **Y (scoped)** | Add a `ConnectedDevice(name, type, status)` list to `DeviceStatusViewModel` only. Do not remove `HeartBeatViewModel` or `Constants.terminalStatus` now | *[Ref]* (cleanup) | *[Sprint]* |
| I-8 Header height conflict | **Y** | Header is in scope; single file | — | *[Sprint]* |
| I-9 Merge ticket row layouts | **N** | Too risky for a visual-only PRD. Apply spacing tokens to each copy instead | *[Ref]* | *[Sprint]* |
| I-10 Centralise drawer wiring | **N** | Only needed if drawer items change. PRD changes spacing and adds device info, both inside the shared include and `BaseActivity` | *[Ref]* | *[Sprint]* |
| I-11 Spannable modifier text | **Y (values only)** | Replace the hardcoded `7f` dp indent and separator sizes with tokens. Do not restructure | — | *[Sprint]* |
| I-12 Stray text in drawer XML | **Y** | Trivial, same file | — | *[Sprint]* |
| I-13 `notifyDataSetChanged` | **N** | Not visual-scope. Log for performance backlog | *[Ref]* | *[Sprint]* |
| I-14 Screenshot tests | **Y (scoped)** | Add Paparazzi/Roborazzi snapshots for the new shared components (dialog, dropdown, header, drawer) only. Screens stay manual QA | *[Ref]* (full coverage) | *[Sprint]* |

---

## 5. Impact Assessment

### 5.1 UI Impact

- **Layout changes:** yes, across header, drawer, ticket rows, QSR/TSR menu grids, payment panes and in-scope dialogs/dropdowns.
- **Header and hamburger changes also apply to Dashboard, Settings, Orders Hub, Gift Card and Cash Drawer**, because they share `HeaderFragment` and `left_nav_menu.xml`. QA must cover those screens too.
- **Navigation:** no change.
- **State handling:** only the new connected-device list in `DeviceStatusViewModel`.

### 5.2 API Contract Impact

- None.

### 5.3 Database Impact

- None. No schema change, migration or backfill. Rollback is safe.

### 5.4 Performance Impact

- CPU and memory: neutral. Converting px to dimens is a lookup, and `PosDialogFragment` replaces existing dialogs one-for-one.
- Network: none. Device status reuses existing heartbeats.
- Startup: none.
- Watch the drawer: observing device status must not re-inflate the drawer on every 60s heartbeat. Update only the status rows.

### 5.5 Backward Compatibility

- **Feature flag:** not practical for layout and spacing changes. Optional flag for the new device-info section only.
- **Rollout:** ship per area as separate PRs inside 8.0 (see §6) so any one area can be reverted on its own.
- Legacy: no data or behaviour change, so nothing to support.

---

## 6. Proposed Implementation Plan

| Phase | Work | Notes |
| --- | --- | --- |
| 0 | Foundation: spacing tokens, text styles, `PosDialogFragment`, `PosDropdown`, screenshot-test setup | No visible change on its own |
| 1 | Header (I-8) + hamburger spacing + connected-device info (I-6, I-7, I-12) | Covers 6 activities |
| 2 | Ticket pane: 5 status row layouts + Spannable spacing values (I-11) | Shared by QSR and TSR |
| 3 | Menu screen: QSR, then TSR (I-1) | Largest effort; most spacing is in Kotlin |
| 4 | Payment screens + migrate in-scope pop-ups and dropdowns (I-3, I-4, I-5) | Order: Ticket → Menu → Payment dialogs |

---

## 7. Risk Assessment

| Risk | Probability | Impact | Mitigation |
| --- | --- | --- | --- |
| Accidental logic change while editing God fragments | Medium | High | Keep PRs to layout/dimen/adapter-bind changes only; reviewers reject logic diffs; regression test ticket → payment flow |
| Visual regressions missed (no UI tests) | High | Medium | Before/after screenshots per PR on all target devices; snapshot tests for shared components |
| Spacing differs by device because of px values and sdp scaling | High | Medium | Convert all in-scope px to dp tokens; QA on every supported POS resolution |
| Header/drawer changes break non-register screens | Medium | Medium | Include Dashboard, Settings, Orders Hub, Gift Card and Cash Drawer in QA |
| Device info shows wrong status (I-6, 60s polling) | High | Medium | Fix I-6; label status with clear states; accept ≤60s staleness or agree it with product |
| Dialog migration changes dismiss/cancel behaviour | Medium | Medium | `PosDialogFragment` keeps each dialog's current `cancelable` value and callbacks |
| Scope creep from "all pop-ups across POS" (108 layouts) | High | High | Agree the exact pop-up and dropdown list with product before Phase 4 (see Open Questions) |

**Rollback strategy**

- Client-only and no data change, so rollback means reverting the area's PR or halting the release rollout.
- One PR per phase means one area can be reverted without affecting the others.

**Monitoring plan**

- Watch Crashlytics/New Relic for `BadTokenException`, `WindowLeaked` and `IllegalStateException` (fragment state) after release. These should go **down** after I-5.
- Log a New Relic event when the drawer opens, with the device-status snapshot, to verify the status shown.
- Track UI-consistency QA defects against the PRD target (0 open).

---

## 8. Open Questions (blocking sign-off)

1. **Pop-up scope:** does "all pop-ups across POS" mean every dialog (108 layouts, including Cash Drawer, Settings and Menu Manager), or only those on Ticket, Menu and Payment?
2. **Dropdown scope:** same question. Cash Drawer and Table Builder dropdowns are outside Ticket, Menu and Payment.
3. **Connected device info:** which devices should be shown (printers, card terminal, KDS, network, customer display)? Show device name, status only, or both? Is ≤60s staleness acceptable?
4. **Dismiss pattern:** should the standard pop-up keep the close-X, the "Go back" button, or both? (59 dialogs have both today.)
5. **Header changes on other screens:** confirm the new header should also apply to Dashboard, Settings, Orders Hub, Gift Card and Cash Drawer.
6. **Target devices:** list the POS resolutions/densities QA must sign off on.
7. **Figma:** confirm the linked file is final for all six areas. The PRD still says "attach design screenshots".

---

## 9. Final Approval

| Role | Approval |
| --- | --- |
| Engineering Manager Approval | |
| Platform Lead Approval | |
| Squad Lead Approval | |
| Product Acknowledgment | |
| Date | |
