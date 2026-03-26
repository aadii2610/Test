# Implementation Plan — Delivery Orders in Orders Hub (DoorDash Drive)

High-level solution guide for **Delivery order management** in POS Orders Hub. This fits alongside the existing **3PO (third-party)** implementation; the same Order Hub structure (filters, tabs, table, order details) is extended for delivery lifecycle and driver status.

---

## Technical Debt Coverage

From the Technical Assessment doc, the following should be addressed **with** this feature where applicable:

- **Delivery lifecycle and tab mapping in domain:** Implement in UseCase/Repository (or dedicated mapper), not in Fragment — *must fix in this iteration.*
- **Webhook scope and threading:** Define and implement webhook handling on IO dispatcher with lifecycle-safe scope (Repository or Application) — *must fix.*
- **Duplicate webhook handling:** Ignore duplicate events using `delivery_id` — *must fix.*
- **List updates:** Use incremental updates (e.g. DiffUtil / ListAdapter or equivalent) when applying webhook-driven changes to avoid scroll loss and frame drops — *recommended in this iteration.*

---

## Architecture Point of View

Current Order Hub follows:

**UI → ViewModel → UseCase → Repository → LocalDataSource / RemoteDataSource → API / Room / MQTT**

- **OrdersHubTicketsFragment** / **OrdersHubParentActivity** → **OrdersHubViewModel** → **GetAllOrdersUseCase** / **OrderHubFilterUseCase** / **GeSingleTicketUseCase** → **OrdersHubRepository** / **OrderHubFilterRepository** → **OrderHubLocalDataSource**, **OrderHubRemoteDataSource**.
- Keep this hierarchy. Do **not** introduce extra layers (e.g. a separate “DeliveryManager”) unless explicitly agreed; extend existing Repository and UseCase for delivery.
- **OrderHubDataState** already has `Delivery`; sub-filters (Scheduled, Active/In Kitchen, Ready, Complete, Cancelled) already exist. Delivery-specific **tab semantics** (Scheduled / In Kitchen / Ready / Completed / Cancelled) map to these; driver status is an **additional dimension** shown in the table and details panel.

---

## Deep Dive in Code

### 1. Orders Hub — Delivery Section and Tabs

- **Fulfillment filter:** User selects **Delivery** (existing `OrderHubDataState.Delivery` / `OrderHubTypesEnum.DELIVERY`) to show only orders created via online ordering and fulfilled through DoorDash Drive.
- **Lifecycle tabs:** Use existing sub-filter semantics; map PRD tabs to current status/subFilter:
  - **Scheduled** → `OrderHubDataState.Filter.Scheduled` (future delivery, prep not started).
  - **In Kitchen** → `OrderHubDataState.Filter.Active` (for delivery: preparation window started / kitchen preparing).
  - **Ready** → `OrderHubDataState.Filter.Ready` (kitchen marked ready, waiting for driver).
  - **Completed** → `OrderHubDataState.Filter.Complete` (delivered).
  - **Cancelled** → `OrderHubDataState.Filter.Cancelled`.
- **Tab transition logic** (domain/repository or use case):
  - Scheduled → In Kitchen: when `current_time >= scheduled_delivery_time - prep_time` (backend or local rule).
  - In Kitchen → Ready: when kitchen/KDS marks order ready (existing flow).
  - Ready → Completed: when provider status = `delivered`.
  - Any → Cancelled: when provider status = `cancelled`.
- **Single source of truth:** Tab placement is derived from **order status + provider status**; compute in UseCase or Repository when returning orders for a given (mainFilter=delivery, subFilter=X). Fragment only displays the list and selected tab.

### 2. Delivery Provider Status Model

- **Provider statuses** (from DoorDash Drive webhooks): `new`, `placed`, `enroute`, `arrived`, `delivered`, `cancelled`.
- **Driver status (POS):** Map 1:1 for display:
  - `new` → **Searching for Driver**
  - `placed` → **Driver Assigned**
  - `enroute` → **Driver En Route**
  - `arrived` → **Driver Arrived** (or “Driver Waiting” if kitchen not ready)
  - `delivered` → **Delivered**
  - `cancelled` → **Cancelled**
- **Provider status → POS tab:** As per PRD table (e.g. new/placed → Scheduled or In Kitchen; arrived → Ready; delivered → Completed; cancelled → Cancelled). Implement this mapping in **domain** (e.g. in Repository when persisting webhook payload, or in a mapper that computes `orderStatus` + tab eligibility).

### 3. Data Model Changes

- **Commons / API model:** Extend `DeliveryEntity` (or equivalent) to include:
  - `providerStatus` (String or enum: new, placed, enroute, arrived, delivered, cancelled)
  - `deliveryId` (String, for dedupe)
  - `vehicleType` (String, e.g. "Car")
  - Optional: `driverArrivedAt`, `deliveredAt` for timestamps.
- **OrderHubDto / OrderHubBO / OrderHubUI:** Add fields required for list and details:
  - Driver: `driverStatus`, `driverName`, `driverPhone`, `vehicleType`
  - Delivery: `deliveryId`, `providerStatus`, `scheduledDeliveryTime`, `deliveryInstructions`
- **Room:** New columns (or JSON blob) for the above; **migration** and version bump. Ensure queries for `mainFilter = delivery` and subFilter use these fields where needed.

### 4. Delivery Orders Table (OrdersHubTicketsFragment)

- **Columns** (align with PRD): Ticket No., Order ID, Order Time, Customer Name, Order Total, Order Status, **Driver Status**.
- **Adapter:** Reuse **OrdersHubTicketAdapter** (or extend); add binding for **Driver Status** from `OrderHubUI.driverStatus`. Use **DiffUtil** or **ListAdapter** so webhook-driven updates do not recreate the whole list (avoids scroll loss and jank).
- **Data source:** Same `GetAllOrdersUseCase` with `mainFilter = delivery` and `subFilter = Scheduled | Active | Ready | Complete | Cancelled`; Repository returns orders with driver/delivery fields populated from local DB (which is updated by API and webhooks).

### 5. Ticket Number Logic

- Keep existing rules: ticket numbers increment sequentially; assigned when order enters POS; displayed in list and details.
- **Fallback:** If system Order ID is long, ticket number remains the primary visible identifier (no change).

### 6. Order Details Panel (OrderInfoDialog)

- **Existing:** Ticket number, Order ID, order time, items, total, customer name, phone, address; rider block (name, phone) from `ticketData.order?.delivery`.
- **Add / extend:**
  - **Delivery information:** Delivery instructions (from order); vehicle type (e.g. Car).
  - **Driver status:** Show current driver status (Searching / Assigned / En Route / Arrived / Delivered).
  - **Driver name and phone:** Shown when driver status is Assigned or later (per PRD); reuse/extend existing rider name and phone binding.
- **OrderInfoDialog** should receive updated `TicketData` (or OrderHubUI) that includes delivery and driver fields; no business logic in Dialog, only display.

### 7. Webhook Handling

- **Entry point:** Backend receives DoorDash Drive webhooks and forwards to POS (e.g. MQTT, SSE, or poll). Use existing pattern (e.g. MQTT/SSE handler) and add a **delivery-specific handler** that:
  - Parses provider status and delivery_id.
  - **Deduplication:** If event with same `delivery_id` already processed (e.g. in-memory set or DB), ignore.
  - Runs on **IO dispatcher**; updates local DB (Repository or LocalDataSource) with provider_status, driver_status, driver name/phone if present.
  - Notifies UI layer via existing Flow/StateFlow (e.g. refresh order list or emit update for single order) on **Main**.
- **Scope:** Do not use unscoped coroutines or GlobalScope; tie to Repository or Application lifecycle so handlers do not hold Fragment/Activity references.

### 8. Tab Logic (Domain / Repository)

- **Scheduled tab:** Orders where delivery is scheduled for future and prep window has not started (e.g. `scheduled_delivery_time - prep_time > now`). Show scheduled delivery time, customer, address, driver status (Searching or Assigned).
- **In Kitchen:** Prep started or order on KDS; driver status can be Assigned or En Route.
- **Ready:** Kitchen marked ready; driver status En Route or Arrived; show driver name and phone.
- **Completed:** Provider status = delivered; show delivery completion timestamp, driver name, ticket number, total.
- **Cancelled:** Provider status = cancelled; show cancellation timestamp and reason if provided; order must not appear in any other tab.

Implement these rules when **querying** or **mapping** orders for each tab (in Repository or UseCase), so Fragment only binds to precomputed list.

### 9. Edge Cases (Implementation Notes)

- **Driver not assigned (new):** Show “Searching for Driver” in Driver Status column and in Order Info.
- **Driver assigned after kitchen ready:** Order stays in Ready tab; driver info appears when webhook received.
- **Driver arrives before order ready:** Show “Driver Waiting” (or “Driver Arrived”) in driver status; order remains in Ready when kitchen marks ready.
- **Driver reassignment:** On webhook with same delivery_id and updated driver, update driver name, phone, status.
- **Webhook delay:** Rely on “POS retries status sync” (periodic or on tab focus); show last known state until update received.
- **Duplicate webhooks:** Ignore by `delivery_id` (and optionally event id if provided).

### 10. Operational Constraints (Out of Scope V1)

- Staff **cannot cancel** delivery orders from POS.
- **No** driver ETA countdown.
- Driver phone number **visible only after** driver is assigned (provider status = placed or later).

---

## Data Layer (Repository / LocalDataSource)

- **OrdersHubRepository** / **OrderHubLocalDataSource:**  
  - Support `mainFilter = delivery` and subFilters (Scheduled, Active, Ready, Complete, Cancelled) with same pattern as 3PO.  
  - Persist and query new delivery/driver columns; apply tab rules when building list (or expose raw list and map in UseCase).
- **Webhook path:**  
  - Parse DoorDash payload → map to provider_status and driver fields → update local entity by `delivery_id` (and ticket/order id).  
  - Use transaction if updating multiple tables.  
  - After update, trigger order list refresh or emit update (e.g. via Flow) so UI updates.
- **API:**  
  - Order list endpoint must return delivery orders with driver/delivery fields when requested (e.g. filter by fulfillment type = delivery).  
  - Backend is responsible for receiving DoorDash webhooks and persisting; POS may poll or receive push (MQTT/SSE) for status sync — align with backend contract.

### Enums and Types

- **Provider status:** Use enum or sealed class (new, placed, enroute, arrived, delivered, cancelled) in domain and data layers; map to string for API/Room if needed.
- **Driver status (display):** Use enum (Searching, Assigned, EnRoute, Arrived, Delivered, Cancelled) for UI consistency.

---

## Testing and QA

- **Unit:** Repository/UseCase logic for tab mapping and provider_status → driver_status; dedupe by delivery_id.
- **Integration:** Local DB update from webhook payload; order list for delivery tab returns correct subset.
- **Manual QA:** Full lifecycle (scheduled → in kitchen → ready → delivered); driver assignment and phone visibility; Order Info popup; duplicate webhook does not change state; webhook delay then sync.

---

## Summary

| Area | Action |
|------|--------|
| **UI** | Delivery tab (existing), add Driver Status column; extend OrderInfoDialog with delivery info and driver status. |
| **State** | Keep in ViewModel (OrderHubUiState); driver/delivery fields on OrderHubUI. |
| **Domain** | Tab and driver-status rules in Repository or UseCase; no logic in Fragment/Dialog. |
| **Data** | Extend DeliveryEntity, DTO/BO/UI; Room migration; webhook handler with dedupe and IO scope. |
| **Concurrency** | Webhook on IO; UI update on Main; lifecycle-safe scope. |

This keeps the existing 3PO implementation intact and adds Delivery as a first-class fulfillment type with clear lifecycle and driver visibility, in line with the PRD and the Technical Assessment.
