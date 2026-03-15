# Android Technical Assessment & Engineering Governance Document

**Delivery Orders in Orders Hub (DoorDash Drive)**

Before starting development, consider going over: **Android Best Practices.docx**.

---

## Purpose

This document ensures that every Android feature implementation:

- Evaluates existing module quality before modification
- Preserves architectural integrity
- Prevents uncontrolled technical debt
- Assesses performance and memory impact
- Documents trade-offs explicitly
- Maintains long-term maintainability and scalability

This assessment is **mandatory** for all L3 and L4 complexity tickets and **recommended** for L2.

---

## 1. Feature Overview

| Field | Value |
|-------|--------|
| **Jira Ticket** | *[To be filled]* |
| **Feature Name** | Delivery order management in Orders Hub (DoorDash Drive) |
| **Module / Layer Affected** | **UI** (Orders Hub: `OrdersHubParentActivity`, `OrdersHubParentFragment`, `OrdersHubTicketsFragment`, `OrderInfoDialog`); **Domain** (orderhub use cases, mappers); **Data** (OrderHub DAO/DTO, remote API, webhooks); **Cross-module** (MQTT/SSE for webhook updates, KDS, ticket/register) |
| **Tech Lead / Owner** | *[To be filled]* |
| **Estimated Complexity** | **L3** (new lifecycle tabs, driver status model, webhook integration, order details panel changes) |
| **Initial Risk Level** | **Medium** (external provider dependency, real-time updates, lifecycle rules) |
| **Dependencies** | **Internal:** OrderHub (ViewModel, Repository, Local/Remote DataSource, Filters), TicketData/OrderEntity/DeliveryEntity, OrderHubTypesEnum (DELIVERY exists), OrderHubStatusEnum, OrderHubDataState (Delivery tab exists). **External:** DoorDash Drive API, webhooks (provider status events), backend for order/status sync. |
| **Target Release Date** | *[To be filled]* |

### Business Context

- **Problem being solved:** Restaurants need to view and manage delivery orders (AIO Online Ordering + DoorDash Drive) inside POS Orders Hub, with both kitchen preparation state and delivery-provider (driver) state, without relying on external delivery tablets.
- **Expected user impact:** Staff see delivery orders in a dedicated Delivery section with lifecycle tabs (Scheduled, In Kitchen, Ready, Completed, Cancelled), driver assignment status, driver phone number, ticket number, and order details popup; orders move between tabs automatically via webhook updates.
- **Success criteria:** Delivery orders appear within 2 seconds; ticket number assigned; customer name and driver status visible; driver name/phone visible after assignment; correct tab transitions (e.g. prep window → In Kitchen, kitchen ready → Ready, delivered → Completed); Order Info popup shows delivery and driver details.

---

## 2. Existing Code Review (Mandatory Pre-Implementation Step)

The feature owner must review the current state of the affected module before implementation.

### 2.1 Architecture & Layering Review

- **ViewModel responsibility boundaries:** `OrdersHubViewModel` holds UI state (`OrderHubUiState`: filter, subFilter, searchQuery, searchFilter, orders), uses `GetAllOrdersUseCase`, `OrderHubFilterUseCase`, `GeSingleTicketUseCase`; emits `UiEvent`. Filter/tab state is driven by `OrderHubDataState` (AllTickets, ThirdParty, **Delivery**, TakeOut, DineIn + sub-filters: Active, Ready, Complete, Scheduled, Cancelled, etc.). Ensure delivery-specific state (driver status, provider status) stays in ViewModel or domain; avoid Fragment-owned business logic.
- **Repository abstraction:** `OrdersHubRepository` / `OrdersHubRepositoryImpl`, `OrderHubRemoteDataSource`, `OrderHubLocalDataSource`; filters via `OrderHubFilterRepository`. Delivery orders will require repository/API support for mainFilter=delivery and provider-status mapping; ensure new fields (driver status, delivery_id) flow through existing layers.
- **UseCase / Domain isolation:** `GetAllOrdersUseCase`, `OrderHubFilterUseCase`, `GeSingleTicketUseCase`; domain models `OrderHubBO`, `OrderHubFilter`, `OrderHubFilterBO`. Delivery lifecycle and provider-status → POS tab mapping are domain concerns; implement in use case or repository, not in Fragment.
- **Separation of concerns:** `OrdersHubTicketsFragment` handles tab clicks, sort, search, adapter; `OrderInfoDialog` shows ticket/order/customer/delivery (rider) info. Keep tab transition logic (e.g. prep window, kitchen ready, delivered) and driver-status derivation in domain/data; Fragment only reflects UI state.
- **Cross-module dependency violations:** Order Hub uses `SharedDataRepository` (ordersHubFilterStatus), `MainViewModel` (order hub item click). Delivery webhooks may come via MQTT/SSE or backend sync; document integration point and avoid circular dependencies.
- **DI graph integrity:** Hilt used (`@AndroidEntryPoint`, `@HiltViewModel`); ViewModels injected in Activity/Fragment. New delivery data sources or use cases must be provided via modules.

### 2.2 Concurrency & Coroutine Audit

- **Dispatcher correctness:** ViewModel uses `viewModelScope`, `Dispatchers.IO` for use case calls; Fragment uses `lifecycleScope` and `withContext(Main)` for UI. Webhook handling (when implemented) must use IO for parsing and Main for UI updates; avoid blocking main thread.
- **Structured concurrency:** `flatMapLatest` + `flow` in ViewModel for order list; job cancelled on filter change. Delivery webhook processing should be lifecycle-scoped (e.g. Application or Repository scope) and not leak Fragment reference.
- **SupervisorJob misuse:** Not observed in current Order Hub; if delivery introduces parallel flows (e.g. poll + webhook), use supervisor where appropriate.
- **Unscoped coroutines:** Avoid `GlobalScope` or `CoroutineScope(IO).launch` without lifecycle; webhook handlers should be tied to Repository or Application.
- **Flow cold/hot misuse:** Order list is cold Flow from use case; ensure delivery status updates (e.g. from webhook) emit via StateFlow/SharedFlow so UI collects once.
- **Backpressure / Cancellation:** High-frequency webhook events should be throttled or conflated before updating UI state.
- **Cancellation awareness:** Use case and repository suspend functions should respect cancellation; webhook processing should not hold references to destroyed UI.

### 2.3 Data Layer Review

- **Room queries performance:** `OrderHubNewDao` / `OrderHubDao`; ensure delivery filter (mainFilter=delivery) and new columns (e.g. driver_status, delivery_id, provider_status) are indexed or covered by existing queries; avoid N+1 when loading order list with delivery details.
- **Index usage validation:** Confirm indexes on (orderType/serveType, orderStatus, ticketPaymentStatus) or equivalent for delivery tab queries.
- **N+1 query risks:** If delivery metadata lives in a separate table, prefer single query with relation or embedded DTO to avoid per-order lookups.
- **Transaction correctness:** Webhook-driven updates (e.g. provider status, driver assigned) must update local DB in a transaction where needed; avoid partial state.
- **Migration readiness:** New columns (driver_status, provider_status, delivery_id, driver_phone, driver_name, etc.) require Room migration and version bump; document rollback.
- **API error mapping:** Backend/DoorDash API errors must map to user-visible messages and retry strategy; duplicate events ignored by delivery_id.

### 2.4 UI & State Management Review

- **Single source of truth:** Order list and filters already in `OrderHubUiState`; add delivery-specific state (e.g. driver status per order, selected order for details) in same state or dedicated StateFlow; avoid duplicate state in Fragment.
- **Immutable UI state:** Use data classes and `copy()` for state updates; expose `StateFlow`/`LiveData` read-only; avoid mutable public properties.
- **StateFlow/LiveData misuse:** Order Hub uses StateFlow for uiState and SharedFlow for events; continue pattern for delivery events (e.g. driver arrived).
- **One-time event handling:** Use SharedFlow or Event wrapper for one-time events (e.g. “driver arrived” toast); avoid re-emitting on config change.
- **Configuration change safety:** ViewModel survives config change; ensure delivery state (selected tab, order list, driver status) is in ViewModel, not Fragment vars.
- **Compose recomposition risks:** N/A (XML/View-based); if any Compose is introduced for delivery, avoid heavy logic in composables.

### 2.5 Performance & Memory Review

- **Large ViewModels:** OrdersHubViewModel already carries filter + orders; adding delivery fields should not duplicate full order list; consider paging if delivery order count is large.
- **Memory leaks:** OrderInfoDialog and list item click callbacks must use viewLifecycleOwner or clear references on destroy; webhook listeners must unregister.
- **Heavy object allocation:** Avoid creating large objects per webhook; prefer incremental state updates.
- **Bitmap handling:** N/A for delivery feature.
- **RecyclerView inefficiencies:** Reuse existing `OrdersHubTicketAdapter` pattern; use DiffUtil or ListAdapter for delivery list updates to avoid full refresh and scroll loss.
- **Cold start impact:** Delivery tab data can load on demand when user selects Delivery filter; avoid loading all delivery orders at app start.
- **Frame drops risk:** Webhook-driven list updates should post to Main and batch if needed; avoid heavy work on main thread.

### 2.6 Reliability & Stability

- **Crash reports (last 30–90 days):** *[Check Firebase/Crashlytics for Orders Hub / orderhub / delivery]*  
- **ANR reports:** *[Check for main-thread work in Order Hub]*  
- **Unhandled exceptions:** Webhook payload parsing (e.g. DoorDash provider status) must be in try/catch; invalid or unknown status should not crash.  
- **Error propagation strategy:** Define how webhook failures and API errors surface (snackbar, silent retry, status sync retry).  
- **Retry logic correctness:** PRD specifies “POS retries status sync”; implement bounded retry with backoff for webhook delay scenarios.

### 2.7 Test Coverage Assessment

- **Unit test coverage %:** *[Current Order Hub ViewModel/UseCase coverage]*  
- **Integration test presence:** *[Order Hub repository/local DB tests]*  
- **Flaky tests:** *[None identified]*  
- **Missing edge-case tests:** Delivery-specific: driver not assigned, driver assigned after kitchen ready, driver arrives before order ready, duplicate webhooks, webhook delay.  
- **Mocking anti-patterns:** Ensure webhook and API layers are mockable for unit tests.

---

## 3. Identified Issues Log

Document all findings clearly.

| Category | Issue Description | Severity | Risk Impact |
|----------|-------------------|----------|--------------|
| Architecture | Delivery lifecycle and provider-status → tab mapping logic could be implemented in Fragment | High | Hard to test, maintain, reuse |
| Data | DeliveryEntity (commons) has name, phoneNumber but no provider_status, delivery_id, vehicle_type | Medium | Cannot support full PRD without model extension |
| Data | No Room migration plan yet for delivery/driver columns | Medium | Blocking for persistence |
| Concurrency | Webhook handling scope and threading not defined | Medium | ANR or leaks if done on main or unscoped |
| UI | OrderInfoDialog shows rider info but not driver status (Searching/Assigned/EnRoute/Arrived/Delivered) or vehicle type | Medium | Incomplete UX per PRD |
| Reliability | Duplicate webhook handling (by delivery_id) not implemented | Medium | Duplicate events could corrupt state |
| Performance | Full list refresh on every webhook could cause scroll loss and jank | Medium | Bad UX |
| Test | No automated tests for Delivery tab or driver status | Low | Regressions |

*Additional issues may be added after deeper code review of OrderHubNewDao, webhook entry points, and OrderHubDataState usage.*

---

## 4. Improvement Decision Matrix (Mandatory)

For each identified issue:

| Issue | Fix in Current Iteration? (Y/N) | Justification | Backlog Ticket | Target Sprint |
|-------|----------------------------------|---------------|----------------|---------------|
| Lifecycle/tab mapping in domain | Y | Architectural; must be in UseCase/Repository for testability and single source of truth | *[Ref]* | *[Sprint]* |
| Extend DeliveryEntity / DTO for provider_status, delivery_id, vehicle_type | Y | Required for PRD; backend/API contract must align | *[Ref]* | *[Sprint]* |
| Room migration for delivery columns | Y | Required for persistence and rollback safety | *[Ref]* | *[Sprint]* |
| Webhook scope and threading | Y | Concurrency governance; must fix | *[Ref]* | *[Sprint]* |
| OrderInfoDialog driver status + vehicle | Y | PRD acceptance criteria | *[Ref]* | *[Sprint]* |
| Dedupe by delivery_id | Y | PRD edge case | *[Ref]* | *[Sprint]* |
| Incremental list updates (DiffUtil/ListAdapter) | Y (recommended) | Performance; avoid scroll loss | *[Ref]* | *[Sprint]* |
| Delivery tab / driver status tests | N (recommended) | Add when scope allows | *[Ref]* | *[Sprint]* |

**Governance Rules**

- Crash, ANR, or memory leak → Must fix immediately  
- Concurrency violation → Must fix  
- Architectural violation → Explicit approval required  
- Performance regression risk → Must be benchmarked  
- No issue may be ignored without documentation  

---

## 5. Impact Assessment

### 5.1 UI Impact

- **Layout changes:** New or repurposed Delivery section in Orders Hub; Delivery tab already present in `OrderHubDataState.Delivery`; sub-tabs (Scheduled, In Kitchen, Ready, Completed, Cancelled) may reuse or extend existing sub-filter UI. Table columns: add **Driver Status** (and ensure Ticket No., Order ID, Order Time, Customer Name, Order Total, Order Status exist). Order Info popup: add/expand **Delivery Information** (instructions, driver name, phone, vehicle type) and **Driver Status**.
- **Navigation changes:** No new activity; Delivery is a filter/tab within existing Orders Hub flow. Order details remain in OrderInfoDialog or equivalent.
- **State handling modifications:** New state for driver status (and possibly provider_status) per order; tab state derived from lifecycle rules; use ViewModel and existing StateFlow pattern.

### 5.2 API Contract Impact

- **Request/response changes:** Backend must support delivery orders and DoorDash Drive provider status (new/placed/enroute/arrived/delivered/cancelled); may require new endpoints or webhook payload schema. Order list API may need to return driver_status, delivery_id, driver_phone, driver_name, vehicle_type for delivery orders.
- **Error model changes:** Define error codes for delivery provider errors and map to user messages.
- **Versioning required:** Confirm backend/API version and webhook version compatibility.

### 5.3 Database Impact

- **Schema changes:** New or extended columns for delivery: e.g. provider_status, delivery_id, driver_status, driver_name, driver_phone, vehicle_type, delivery_instructions; possibly scheduled_delivery_time, prep_time for Scheduled tab logic.
- **Migration needed:** Yes; add migration and bump DB version; test upgrade path.
- **Data backfill required:** Not for existing non-delivery orders; new delivery orders populated via API/webhook.
- **Rollback feasibility:** Migration must be reversible or forward-compatible so rollback does not crash on new columns.

### 5.4 Performance Impact

- **CPU impact:** Webhook parsing and state updates should be on background thread; minimal main-thread work.
- **Memory footprint:** Avoid holding full order payloads in memory; use paging if list is large.
- **Network payload size:** Delivery fields will increase payload size; monitor and optimize if needed.
- **Startup time impact:** No change if delivery data is loaded only when Delivery tab is selected.
- **Expected load increase:** Webhook volume depends on DoorDash Drive activity; throttle/conflate updates.

### 5.5 Backward Compatibility

- **Feature flags required:** Recommended to gate Delivery section and webhook handling for gradual rollout.
- **Gradual rollout:** Per PRD; consider by restaurant or region.
- **Legacy support maintained:** Existing 3PO (third-party) and other Order Hub tabs must continue to work; Delivery is additive.

---

## 6. Risk Assessment

| Risk | Probability | Impact | Mitigation Strategy |
|------|-------------|--------|----------------------|
| Webhook delay or failure | Medium | High | Retry status sync; show “Searching for driver” until placed; document timeout behavior |
| Duplicate webhooks corrupt state | Medium | High | Dedupe by delivery_id; idempotent updates |
| Tab transition logic wrong (e.g. Ready vs In Kitchen) | Medium | High | Implement and test per PRD rules; code review and QA |
| Driver PII (phone) exposure | Low | High | Show only after assignment; comply with privacy policy |
| Main-thread webhook handling | Low | High | Enforce IO dispatcher and lifecycle scope in code review |
| Regression in existing 3PO/Order Hub | Medium | High | Feature flag; QA of All/ThirdParty/TakeOut/DineIn tabs |

**Rollback strategy:** Feature flag to hide Delivery section and disable webhook handling; DB migration must not break existing app (nullable columns or compatible migration). Document steps to disable and re-enable.

**Monitoring plan:** Log delivery webhook receipt and failures; monitor crash/ANR for Order Hub; alert on delivery sync failure rate or latency.

---

## 11. Final Approval

| Role | Approval |
|------|----------|
| Engineering Manager Approval | *[Name / Date / Signature]* |
| Platform Lead Approval | *[Name / Date / Signature]* |
| Squad Lead Approval | *[Name / Date / Signature]* |
| Product Acknowledgment | *[Name / Date / Signature]* |
| Date | *[Date]* |
