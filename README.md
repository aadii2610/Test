# RefundMainFragment: Manual View-Switching to Nested NavGraph Migration

**Area:** `pos/src/main/java/aio/app/pos/ui/main/fragments/refund/`
**Status:** Proposed (not started)
**Scope:** Presentation/navigation layer only -- no business logic, calculations, API contracts, or analytics/logging calls change.

---

## Context

`RefundMainFragment` is hosted by `PaymentParentFragment` via a plain `childFragmentManager` transaction (`setRefundMainFragment()` / `clearRefundMainFragment()` in `PaymentParentFragment.kt:685-720`), replacing `payByCashFragmentContainer`. It is **not** currently part of any Navigation Component graph -- it's a manually managed child fragment, and this migration does not change that boundary.

Inside `RefundMainFragment`, six "steps" are implemented as custom `View` subclasses (not Fragments), all inflated up front in `onCreateView()` and added to a single container (`binding.addView`, see `fragment_refund_main.xml:28-36`):

| Step (current class) | Extends | Binding | Lines |
|---|---|---|---|
| `PaymentSelectionView` | `RelativeLayout` | `PaymentSelectionViewBinding` | 88 |
| `RefundAmountSelectionView` | (ViewGroup) | `RefundAmountSelectionBinding` | 871 |
| `RefundReasonView` | `RelativeLayout` | `RefundReasonViewBinding` | 29 |
| `RefundConfirmationView` | `RelativeLayout` | `RefundConfirmationViewBinding` | 21 |
| `RefundCompleteView` | `RelativeLayout` | `RefundCompleteViewBinding` | 20 |
| `CancelOrderView` | `RelativeLayout` | `CancelOrderViewBinding` | ~30 |

`RefundMainFragment` (2362 lines) owns:
- All six view instances and toggles `visibility = VISIBLE/GONE` to move between them (`hideAllViews()`, and the show-calls scattered through `clickListeners()`, `handleBackButtonClick()`, `cardRefund()`, `cashRefund()`).
- All cross-step mutable state as plain fields: `reason`, `refundedAmount`, `refundedTax`, `refundedTip`, `refundedRiderTip`, `refundedServiceCharges`, `refundedGratuity`, `refundedDeliveryFee`, `refundDateTime`, `refundableAmount`.
- Four booleans standing in for "current step": `paymentSelection`, `refundReason`, `refundConfirmation`, `refundAmountSelection`, plus two init-guard booleans (`isPaymentSelectionViewInitialized`, `isRefundAmountSelectionViewInitialized`).
- All business logic: `cardRefund()`, `cashRefund()`, `getPaymentRefundId()`, `movingBack()`, proportional recalculation helpers, New Relic logging, Analytics calls, SSE broadcast receiver (`onResume`/`onPause`), `OnReceiptCallback` (for SMS/Email screens launched via `RegisterActivity`).
- Direct reach-into-child-view-internals coupling, e.g. `refundReasonView!!.binding.rBtnFive.isChecked`, `refundAmountSelectionView?.binding?.rAmountET?.text` -- the parent fragment freely pokes at every child's binding.

This is documented in the companion discussion; this document is the concrete migration plan.

---

## Goals

1. Replace the six manually-toggled `View`s with six Fragments hosted in a nested `NavHostFragment`, using a real back stack instead of the boolean-flag chain.
2. Preserve **every** existing behavior exactly: all calculations, API calls, analytics events, New Relic logs, receipt/print/SMS/email flows, the online-order-cancellation branch, and every back-button edge case.
3. Do it incrementally, screen by screen, so each step is independently verifiable against the live (money-handling) flow.
4. Leave `PaymentParentFragment`'s hosting of `RefundMainFragment` untouched -- this migration is entirely internal to `RefundMainFragment`.

## Non-Goals

- No change to `cardRefund()` / `cashRefund()` business logic, request payloads, or backend contracts.
- No change to `RefundAmountSelectionView`'s calculation logic (proportional reductions, tax recalculation) -- only how it's hosted.
- No change to how `RefundMainFragment` itself is created/destroyed by `PaymentParentFragment`.
- Not a rewrite of `RefundAmountSelectionView`'s internals (871 lines) -- it moves into a Fragment wrapper as-is.

---

## Target Architecture

```
RefundMainFragment (unchanged role: host + orchestrator)
├── refundHeaderView (unchanged, include layout)
├── btnBackRefund (unchanged, single back button -- now delegates to child NavController)
└── refundNavHostContainer (NEW -- replaces "addView" ConstraintLayout)
        └── NavHostFragment (app:navGraph="@navigation/refund_nav_graph")
                ├── PaymentSelectionFragment
                ├── RefundAmountSelectionFragment
                ├── RefundReasonFragment
                ├── RefundConfirmationFragment
                ├── RefundCompleteFragment
                └── CancelOrderFragment
```

`RefundMainFragment` **keeps** its current responsibilities: it still owns `cardRefund()`, `cashRefund()`, `movingBack()`, the SSE broadcast receiver, `OnReceiptCallback`, and all logging/analytics. It becomes the **flow orchestrator holding the child `NavController`**, reacting to shared state changes and driving navigation -- it just stops manually toggling `View.GONE/VISIBLE` and instead calls `navController.navigate(...)`.

Each new Fragment takes over exactly what its View counterpart did: inflate its own binding, wire its own click listeners for **purely local** UI concerns (radio button mutual exclusion, checkbox toggles, recycler view adapters), and read/write shared flow state through a new `RefundFlowViewModel` instead of through `RefundMainFragment` fields.

### New class: `RefundFlowViewModel`

A `ViewModel` scoped to `RefundMainFragment` (`by viewModels()` in `RefundMainFragment`, `by viewModels({ requireParentFragment() })` in each child Fragment -- **not** Activity-scoped, so it's created/cleared with the refund flow exactly like the current fields are).

Holds what are today loose fields on `RefundMainFragment`:

```kotlin
class RefundFlowViewModel : ViewModel() {
    var reason: String? = null
    var refundedAmount = 0.00
    var refundedTax = 0.00
    var refundedTip = 0.00
    var refundedRiderTip = 0.00
    var refundedServiceCharges = 0.00
    var refundedGratuity = 0.00
    var refundedDeliveryFee = 0.00
    var refundDateTime = ""
    var refundableAmount: Double = 0.0
    var isPaymentSelectionViewInitialized = false
    var isRefundAmountSelectionViewInitialized = false
}
```

This is the mechanism that lets child Fragments read/write "flow state" without holding a reference back to `RefundMainFragment` (today's `refundReasonView!!.binding...` pattern is replaced by each Fragment reading its own `binding` and writing into `RefundFlowViewModel`). All existing Activity-scoped ViewModels (`MainViewModel`, `PaymentViewModel`, `TicketViewModel`, `BusinessIdViewModel`) are obtained by each child Fragment the same way `RefundMainFragment` obtains them today (`activityViewModels()` / `viewModels()`) -- no change there.

---

## File-by-File Mapping

| Old (View, self-managed visibility) | New (Fragment, nav destination) | Layout reuse |
|---|---|---|
| `PaymentSelectionView.kt` | `PaymentSelectionFragment.kt` | Reuse `PaymentSelectionViewBinding`'s layout XML as the fragment's layout |
| `RefundAmountSelectionView.kt` | `RefundAmountSelectionFragment.kt` | Reuse `RefundAmountSelectionBinding`'s layout XML |
| `RefundReasonView.kt` | `RefundReasonFragment.kt` | Reuse `RefundReasonViewBinding`'s layout XML |
| `RefundConfirmationView.kt` | `RefundConfirmationFragment.kt` | Reuse `RefundConfirmationViewBinding`'s layout XML |
| `RefundCompleteView.kt` | `RefundCompleteFragment.kt` | Reuse `RefundCompleteViewBinding`'s layout XML |
| `CancelOrderView.kt` | `CancelOrderFragment.kt` | Reuse `CancelOrderViewBinding`'s layout XML |

Each new Fragment's `onCreateView` uses the *same* generated `ViewBinding` class the old View used (`XxxBinding.inflate(inflater, container, false)`) -- the XML layouts themselves do not need to change, only the Kotlin class wrapping them (`RelativeLayout` subclass → `Fragment`).

`fragment_refund_main.xml` changes from:

```xml
<androidx.constraintlayout.widget.ConstraintLayout
    android:id="@+id/addView"
    ... />
```

to:

```xml
<fragment
    android:id="@+id/refundNavHostFragment"
    android:name="androidx.navigation.fragment.NavHostFragment"
    app:navGraph="@navigation/refund_nav_graph"
    app:defaultNavHost="false"
    ... />
```

`app:defaultNavHost="false"` is deliberate: `RefundMainFragment`'s existing `OnBackPressedCallback` (registered in `onViewCreated`, `RefundMainFragment.kt:322-329`) stays the single source of truth for back-press handling (see "Back Navigation" below) rather than letting the child `NavHostFragment` intercept system back on its own.

---

## Handling the Two Dynamic/Conditional Flows

This is the part of the current code most likely to regress if migrated carelessly -- both flows pick their *first* screen based on data that isn't known until an `IO` dispatch resolves, which is why the current implementation waits until `onCreateView` runs a suspend function before deciding what to show.

### 1. Cancel-order vs. normal refund (`RefundMainFragment.kt:239`)

```kotlin
if (cancelOnlineDeliveryOrder) inflateCancelOrderView()
else lifecycleScope.launch { inflateRefundViews() }
```

This is a constructor-time argument (`ARG_SHOW_CANCEL_VIEW`), known synchronously. In the nav graph, this is **not** encoded as `app:startDestination` (a fixed XML attribute) -- instead, `RefundMainFragment` sets the graph's start destination in code, before the `NavHostFragment` is shown, mirroring how `newInstance(showCancelView)` already decides this today:

```kotlin
val navHostFragment = childFragmentManager.findFragmentById(R.id.refundNavHostFragment) as NavHostFragment
val navController = navHostFragment.navController
val graph = navController.navInflater.inflate(R.navigation.refund_nav_graph)
graph.setStartDestination(
    if (cancelOnlineDeliveryOrder) R.id.cancelOrderFragment else R.id.refundLoadingPlaceholder
)
navController.graph = graph
```

### 2. Single-payment vs. multi-payment start screen (`inflateRefundViews()`, `RefundMainFragment.kt:595-646`)

Today, after an `IO` fetch of ticket data, the code decides between showing `PaymentSelectionView` (multiple payments / split items) or jumping straight to `RefundAmountSelectionView` (single payment) -- this can't be known synchronously, so it can't be the graph's static start destination either.

**Solution:** add a lightweight, invisible `RefundLoadingFragment` as the graph's actual `app:startDestination` for the normal-refund branch. It does no rendering -- `onViewCreated` runs the same suspend check `inflateRefundViews()` does today, then calls:

```kotlin
navController.navigate(
    if (multiplePaymentsOrSplit) R.id.action_loading_to_paymentSelection
    else R.id.action_loading_to_amountSelection,
    args,
    navOptions { popUpTo(R.id.refundLoadingPlaceholder) { inclusive = true } }
)
```

`popUpTo(...) { inclusive = true }` removes the placeholder from the back stack, so once the real first screen is showing, pressing back from it pops to *nothing* (matching today's behavior where back from the true first screen exits the whole flow, not "back to a loading screen").

This keeps 100% of the existing decision logic (`selectedPayment.size > 1 || isItemSplited`) -- it just moves the "then what" from `paymentSelectionView?.visibility = View.VISIBLE` to `navController.navigate(...)`.

---

## Back Navigation Mapping

`RefundMainFragment`'s current `handleBackButtonClick()` (lines 255-304) is a boolean-flag priority chain. Every branch maps directly onto `NavController` back-stack behavior, which is the strongest evidence this migration is low-risk for behavior parity:

| Current boolean-flag branch | New behavior |
|---|---|
| `refundConfirmation == true` → show `refundReasonView` | Plain `navController.popBackStack()` (Confirmation was pushed on top of Reason) |
| `refundReason == true` → show `refundAmountSelectionView`, call `setupCheckboxListeners()` + `refreshViewState()` | Plain `navController.popBackStack()`. `setupCheckboxListeners()`/`refreshViewState()` move into `RefundAmountSelectionFragment.onViewCreated()`/`onStart()`, which re-runs naturally every time Fragment's view is recreated after being popped back to -- no special-casing needed |
| `paymentSelection == true` **and** currently showing `PaymentSelectionView` → exit flow | `navController.popBackStack()` returns `false` (Payment Selection is the effective start destination after the loading placeholder is popped) → fall through to `exitRefundFlowToPaymentParent()` |
| `paymentSelection == true` **and NOT** currently on `PaymentSelectionView` (shouldn't happen given current flag semantics, but defensively handled) | Same `popBackStack()` call handles it uniformly -- no separate branch needed |
| `else` (single-payment flow, Amount Selection is first screen) → exit flow | `navController.popBackStack()` returns `false` (Amount Selection is the effective start destination) → `exitRefundFlowToPaymentParent()` |

Net result, `RefundMainFragment`'s back handling collapses from a 50-line if/else chain to:

```kotlin
override fun handleOnBackPressed() {
    val popped = navHostFragment.navController.popBackStack()
    if (!popped) exitRefundFlowToPaymentParent()
}
```

**Exception -- `RefundConfirmationFragment`'s "processing" sub-state:** when the user taps Confirm, the current code doesn't navigate anywhere -- it hides `cancelBtn`/`confirmBtn` and shows `processingRefund` *within the same view* (`RefundMainFragment.kt:956-960`), and on failure reverses that (`cardRefund()` failure path, lines 1805-1816). This is **not** a navigation event and must **not** become one -- it stays exactly as internal view-state toggling inside `RefundConfirmationFragment`, driven by a result callback (LiveData/callback from `RefundMainFragment` after `cardRefund()`/`cashRefund()` resolves) rather than a nav destination change. Getting this wrong (e.g. treating "processing" as its own destination) would change back-button behavior while a refund is in flight -- explicitly called out here as a trap to avoid.

**`RefundCompleteFragment` is a dead end, not a back-stack destination.** Every action on it (`noReceiptBtn`, `printBtn`, `smsBtn`, `emailBtn`) calls `movingBack()`, which exits directly to `PaymentParentFragment` (`clearRefundMainFragment()` + `setBillFragment()` + `setTicketPanFragment()`) -- it never relies on `popBackStack()`. No change needed here beyond confirming `movingBack()` is called from the new Fragment instead of `RefundMainFragment` directly (via a shared callback/ViewModel event, since `movingBack()` itself stays owned by `RefundMainFragment` -- it needs `parentFragment as? PaymentParentFragment`, which only `RefundMainFragment` has access to).

---

## What Stays Exactly Where It Is

To keep the blast radius to "navigation + view hosting only":

- `cardRefund()`, `cashRefund()`, `getPaymentRefundId()`, `movingBack()`, `calculateProportionalValue()`, `calculateAllProportionalReductions()`, all New Relic logging, all `Analytics()` calls -- **stay in `RefundMainFragment`**, unchanged. Child fragments call into `RefundMainFragment` (or a shared ViewModel event) to trigger these exactly as they do today via direct method calls -- e.g. `RefundConfirmationFragment`'s confirm button still ultimately triggers `RefundMainFragment.cardRefund()`/`cashRefund()`, just reached via `(parentFragment as RefundMainFragment)` or a shared `RefundFlowViewModel` event instead of a raw click listener registered directly on `refundConfirmationView!!.binding.confirmBtn` from within `RefundMainFragment`.
- The SSE `BroadcastReceiver` (`onResume`/`onPause`, lines 2248-2292) and `OnReceiptCallback` (`onItemClick`, line 2234) -- stay in `RefundMainFragment`, since both are tied to the fragment's own lifecycle and its relationship with `RegisterActivity`, not to any individual step.
- `Utils().resetBearerToken(businessIdViewModel)` calls in `onStop()`/`exitRefundFlowToPaymentParent()` -- unchanged, stay in `RefundMainFragment`.

---

## Migration Phases

Given this is a live, money-handling flow with no existing automated UI test coverage, migrate **incrementally, one destination at a time**, verifying manually against the real app after each phase (per this repo's `verify` skill) before moving to the next. Suggested order, easiest/lowest-risk first:

1. **Prep (no visible behavior change):** Introduce `RefundFlowViewModel`, move the loose fields into it, update `RefundMainFragment` to read/write through it. Verify the whole flow still works identically -- this alone touches nothing about views/navigation and is the safest place to catch mistakes early.
2. **`CancelOrderFragment`** -- simplest, most isolated (own start-destination branch, no shared state).
3. **`RefundCompleteFragment`** -- terminal screen, no back-stack interaction to get wrong.
4. **`RefundConfirmationFragment`** -- moderate; must carefully preserve the "processing" internal sub-state (see callout above).
5. **`RefundReasonFragment`** -- simple, but exercises the `viewModel.reasonDialog`/`viewModel.nextBtn` observer wiring.
6. **`PaymentSelectionFragment`** and **`RefundAmountSelectionFragment`** -- last, since they hold the most state and the dynamic-start-destination logic (`RefundLoadingFragment`) depends on both existing first.

After each phase, keep the old `View` class in place but unused (don't delete) until the *whole* migration is verified end-to-end -- cheap insurance, delete them all together at the end once QA signs off.

---

## Verification Checklist (manual, per phase and again end-to-end)

- Single payment, cash refund, full amount -- reaches `RefundCompleteFragment`, print/SMS/email/no-receipt all work.
- Single payment, card refund, partial amount -- confirmation shows "(Partial refund)", proportional SC/tax/gratuity recalculation on amount edit still matches pre-migration values.
- Multiple payments / split ticket -- `PaymentSelectionFragment` shown first, selecting a payment enables Next, navigates to Amount Selection with that payment's values.
- Back button from every screen: Confirmation → Reason (reason radio state preserved), Reason → Amount Selection (checkbox/amount state preserved via `refreshViewState()`), Amount Selection (as first screen) → exits to `PaymentParentFragment`, Payment Selection (as first screen) → exits to `PaymentParentFragment`.
- "Other" reason flow -- empty reason blocks Next with the enter-reason dialog; non-empty reason flows to Confirmation correctly.
- Scheduled order + card refund -- cancellation-before-refund branch (`cardRefund()`'s `isScheduledOrder` path) still fires correctly from the new Confirmation fragment's confirm button.
- Refund API failure -- returns to Confirmation with buttons restored, error message shown (not stuck in "processing").
- Online-order cancellation branch (`ARG_SHOW_CANCEL_VIEW = true`) -- `CancelOrderFragment` shown directly, retry button re-triggers the cancel flow correctly, success transitions into the normal refund flow.
- SMS/Email receipt screens still return control correctly via `OnReceiptCallback.onItemClick`.

---

## Rollback Plan

Since old `View` classes remain in the codebase (undeleted) until the full migration is verified, rolling back any single phase is a revert of that phase's commit -- `RefundMainFragment` falls back to instantiating the old `View` for that step exactly as before. No data migration, no API changes, and no persisted state format changes are involved anywhere in this plan, so rollback carries no cleanup cost.
