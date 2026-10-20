# Tech Analysis — Busy Mode & Pause Orders (Kitchen Control)

**PRD:** [Busy and pause order](https://app.notion.com/p/aio-app/Busy-and-pause-order-3a75b13baaa380448733d14f0a3f9f33)
**Figma:** [POS Header](https://www.figma.com/design/iEsAO1rJawNNrqGLElWKTN/POS-Header?node-id=3151-11118)
**Scope of this doc:** POS (Android) only. Backend and Kitchen Hub work is listed as dependencies.
**Status:** Pre-implementation analysis. No code changed.

---

## 1. Feature Overview

| Field | Value |
| --- | --- |
| **Jira Ticket** | *(pending)* |
| **Feature Name** | Busy Mode & Pause Orders (Kitchen Control) |
| **Module / Layer Affected** | **Cross-module.** UI (Orders Hub), Data (new API calls), Backend (pause enforcement, Kitchen Hub proxy), Super Admin settings. |
| **Tech Lead / Owner** | *(pending)* |
| **Estimated Complexity** | **L3** |
| **Initial Risk Level** | **Medium–High**. Pausing a source stops real orders; a wrong or stuck pause loses sales. |
| **Dependencies** | **Backend (critical):** new endpoints to read/set pause state per source, proxy to Kitchen Hub, enforce pause for AIO sources, auto-resume. **Kitchen Hub:** `PUT/GET /v2/integration_accounts/{integration_id}/status/`. **Super Admin:** online ordering flag. **Internal:** Orders Hub, permissions. |
| **Target Release Date** | *(pending)* |

### Business Context

- **Problem:** During peak load or an outage there is no single place to slow down or stop orders from specific sources, which leads to missed SLAs, cancellations and a poor guest experience.
- **User impact:** Managers pause or resume order sources from Orders Hub. Everyone sees what is paused at a glance.
- **Success criteria:** A paused source stops receiving orders immediately and resumes on time; badges always match the real state.

### Scope

| In scope (this release) | Deferred (PRD: "out of scope for now") |
| --- | --- |
| Kitchen control button + modal on Orders Hub | Auto-fire time (US-2, US-3) |
| Pause / resume per source: **Grubhub, DoorDash, Uber Eats** (via Kitchen Hub), **Online Ordering (single card for pickup + delivery), Catering, Kiosk** (inside AIO) | Additional prep time on top of MOM base time (US-4) |
| Pause until a time (15-min steps) or indefinitely (mutually exclusive); auto-resume | |
| Save / Cancel; pre-populated from current state | |
| Status badges on the Orders Hub main screen; read-only for other roles | |
| Online Ordering card hidden when disabled in Super Admin | |

---

## 2. Existing Code Review

Paths under `pos/src/main/java/aio/app/pos/` unless stated.

### 2.1 Architecture & Layering

- **Nothing like Kitchen Control exists.** No busy mode, pause, store status, or Kitchen Hub status code was found in `pos` or `commons`.
- **The POS never talks to Kitchen Hub directly.** The only Kitchen Hub references are two AIO backend endpoints (`PUT /api/kitchen-hub/respond-to-order`, `POST /api/kitchen-hub/update-delivery-status`, `ApiService.kt:863, 975`). There is one HTTP client pointing at the AIO backend; no Kitchen Hub client, URL or key on the device. **The PRD's Kitchen Hub status calls must be made by the backend**, so the POS needs new AIO endpoints.
- **`integration_id` is in an SDK object the POS never reads.** `RestaurantFullBO.kitchenHubs` (external SDK `aio.app.common:restaurant:0.0.32`) carries `partnerName`, `integrationAccountId`, `kitchenHubStoreId`. `BusinessIdUseCase` is the natural accessor. The `partnerName` strings are unknown and must be confirmed with the backend.
- **Closest precedent: the online-ordering toggle.**
  - `GET/PATCH /api/online-ordering-settings` (`ApiService.kt:635–641`), logic in `OrdersHubViewModel.kt:362–403`, UI is the "Online Orders On/Off" button in `SearchFragment` and an overlay `online_orders_new_dialog.xml` driven by `OrdersHubParentActivity.setOnlineOrderingSwitch()` (L2072–2096, duplicated in `GiftCardActivity`).
  - The response also has pickup, delivery and restaurant timezone fields, but only `id` and `enableOnlineOrdering` are mapped.
  - **It overlaps with the new Online Ordering pause card** and the Super Admin flag. Their relationship is undefined (see §7).
- **Orders Hub layout:** top strip is `SearchFragment` (`fragment_search.xml`), which already holds the Online Orders button and the order-delay selector. A "Kitchen control" button and badges fit there. The global header (`HeaderFragment`) is shared with Gift Card screens and already carries blinking Online/3PO count badges.
- **Modal style:** two precedents (in-activity overlay, or a `DialogFragment` such as `ManagerApprovalDialog`). **No hour/min/AM-PM picker exists** in the codebase (only a numpad time entry).
- **Large classes on the path:** `OrdersHubParentActivity` 2,961 lines, `ApplicationRepositoryImpl` 2,497, `OrdersHubTicketsFragment` 1,620, `HeaderFragment` 759. New code should go into new files.

### 2.2 Concurrency

- **Multi-device state:** there is **no settings-sync event**. MQTT has no `UPDATE_*_SETTINGS`; `BUSINESS_HOURS_UPDATE` exists but its handler only acknowledges. The restaurant info cache refreshes only at splash and clock-in.
- Two devices can show different states, and there is **no concurrency token**, so last write wins. "Pre-populated with the current state" (US-1) therefore needs a **fresh GET on every open**.
- **Save across 3 providers is not atomic.** Each Kitchen Hub PUT is separate, and GET can return `unknown`. Per-source results and partial failure must be handled and shown.
- **Auto-resume cannot run on the device.** There is no WorkManager. The only scheduler is `AlarmManager` for close-out. Auto-resume must be backend-side (Kitchen Hub `pause_duration`, backend timestamp for AIO sources) and the POS only reads it.

### 2.3 Data Layer

- **Absolute time, not local time.** Device time zone is used almost everywhere (clock, `toUtcDateTime`). Restaurant time zone exists (`BusinessIdUseCase.getTimeZone()`, default `America/New_York`) but is effectively unused. `toUtcDateTime` converts a time to **today's date only, with no midnight rollover**, and there is no clock-drift handling. A "paused until 12:15 AM" picked at 11:50 PM would break.
- **Kitchen Hub takes seconds** (`pause_duration`). The PRD says AIO computes "now to selected time", so the computation must use one agreed clock (server time) to avoid drift between device and server.
- **Super Admin flags exist in the SDK but the POS barely uses them:** `onlineOrder`, `isPickupEnabled`, `isDeliveryEnabled`, `isCateringEnabled` are on `RestaurantBO`, but `RestaurantFeatureFlag` defaults are `false` and are never read. **There is no kiosk-enabled or catering-enabled setting read anywhere.** The authoritative online ordering flag today is `enableOnlineOrdering` from the online-ordering-settings API.
- **No accept/reject gate on the POS for online, kiosk or catering orders.** The POS only receives them (MQTT `ONLINE_ORDER`). Pause for AIO sources must be enforced by the backend, which this analysis cannot confirm.
- **Order source strings:** `OrderHubSourceEnum` has `doordash`, `uber-eats`, `grub-hub`, `website`, `website-catering`, `kiosk`. `SearchFiltersDialog` uses the key `"grubhub"` and maps it to `"grub-hub"` (`OrdersHubFilterRepoImpl.kt:91`). New code should use the enum. `isThirdPartyOrder()` is copy-pasted in 5 places.

### 2.4 UI & State Management

- **Existing precedent has a bug.** The online-ordering toggle sets the UI state **before** the API call, and on failure only shows a snackbar, so the state is never reverted (`SearchFragment.kt:146–153`). The new modal must not copy this: Save should apply the state only after success.
- Modal needs: per-source card, toggle, "Paused until" picker (15-minute steps), "Paused indefinitely" checkbox (mutually exclusive with the time), Save and Cancel (Cancel discards all edits), loading state while fetching status, and an `unknown` state.
- **Badges:** show one badge per paused source, next to the Kitchen control button. Must update after save and after a refresh from the server (a source that auto-resumed must lose its badge without user action, which needs a refresh trigger).
- **Permissions:** features are backend-driven keys in `AllowedFeature` (`AuthEnum`, checked with `Utils().checkFeature`). **There is no Kitchen Control key**, so a new one is needed. `RolesEnum` has `MANAGER`, `OWNER` and `SHIFTORASSISTANTMANAGER`; **no role is named "Shift Lead"** (the PRD note itself asks for the role names to be confirmed). No read-only-vs-edit pattern exists; only show/hide.

### 2.5 Performance & Memory

- One GET per provider on open (up to 3 Kitchen Hub calls, via the backend). Fetch in parallel with a timeout; the HTTP client timeout is 12 s.
- No other performance concerns.

### 2.6 Reliability & Stability

- **Failure handling:** none of the existing status APIs handle offline. The HTTP client retries only on connectivity or 502/503.
- **Failure of a Kitchen Hub call must be visible.** A source shown as "Paused" when the call failed (or the reverse) is the worst outcome: lost orders or orders during an outage.
- **Secrets/logging:** the POS holds no Kitchen Hub keys. `logNewRelicEvent` is widely used with payload maps, so do not log tokens or `integrationAccountId` unnecessarily and do not dump `RestaurantBO` (it holds `metaAccessToken`).

### 2.7 Test Coverage

- No tests for Orders Hub, online ordering, permissions or `BusinessIdUseCase`. Only 4 unit test files exist (`TicketMappersTest`, `ApiQueueRetryLimitTest`, `MenuRefreshPocTest`, `ExampleUnitTest`). MockK and JUnit are available.

---

## 3. Identified Issues Log

| # | Category | Issue | Severity | Risk Impact |
| --- | --- | --- | --- | --- |
| I-1 | Backend contract | POS has no Kitchen Hub client. Backend endpoints for status read/set, pause enforcement for AIO sources, auto-resume, and per-source state do not exist on the client | **Critical** | Blocks the feature |
| I-2 | Reliability | Save is non-atomic across providers; Kitchen Hub status can be `unknown`; failure handling undefined | High | UI shows a state that is not real; orders lost or received during a pause |
| I-3 | Concurrency | No settings-sync event; cache refreshes only at splash/clock-in; no concurrency token | High | Devices disagree; last write wins |
| I-4 | Data | No server-side auto-resume contract; device has no scheduler for it | High | Source stays paused after the time passes |
| I-5 | Data | Time handling is device-local; no midnight rollover; restaurant time zone unused; no drift handling | High | Wrong "paused until" time |
| I-6 | Architecture | Existing online-ordering toggle and new Online Ordering card overlap; Super Admin flag and `enableOnlineOrdering` relationship undefined | High | Two switches controlling one thing |
| I-7 | UI | Existing toggle updates UI before the API call and never reverts on failure | Medium | Wrong state shown |
| I-8 | Permissions | No Kitchen Control permission key; role names in the PRD do not match `RolesEnum`; no read-only pattern | Medium | Wrong people can change settings, or nobody can |
| I-9 | Data | `integrationAccountId` / `partnerName` mapping unknown (SDK data is not consumed by the POS) | Medium | Wrong provider paused |
| I-10 | Data | Catering and Kiosk "enabled" settings are not read by the POS | Medium | Cards shown for features that are off |
| I-11 | UI | No time-of-day picker (hour/min/AM-PM) exists | Low | New component |
| I-12 | Architecture | Source naming inconsistent (`grubhub` vs `grub-hub`); `isThirdPartyOrder()` copied 5 times | Low | Mismatched filters/badges |
| I-13 | Test | No tests on Orders Hub or settings | Medium | Regression risk |

---

## 4. Improvement Decision Matrix

| Issue | Fix now? | Justification | Backlog Ticket | Target Sprint |
| --- | --- | --- | --- | --- |
| I-1 Backend contract | **Y (first)** | One agreed API: read all sources' state, set per source (pause until / indefinite / resume). Backend calls Kitchen Hub | *[Ref]* | *[Sprint]* |
| I-2 Partial failure | **Y** | Per-source result in the response; Save applies only after success; clear error per source; `unknown` shown as "Status unknown" | — | *[Sprint]* |
| I-3 Multi-device sync | **Y (scoped)** | Fresh GET on every modal open and on Orders Hub resume. New MQTT event is optional and needs backend | *[Ref]* (push sync) | *[Sprint]* |
| I-4 Auto-resume | **Y (backend)** | Backend owns resume; POS only displays. Refresh badges on resume/poll | *[Ref]* | *[Sprint]* |
| I-5 Time handling | **Y** | Send an absolute UTC time or a duration computed by the server; show times in the restaurant time zone; handle midnight rollover; pure-function tests | — | *[Sprint]* |
| I-6 Online ordering overlap | **Y (needs product)** | Define one source of truth. Reuse `enableOnlineOrdering` or replace it; remove the old button if the new card replaces it | *[Ref]* | *[Sprint]* |
| I-7 Optimistic toggle bug | **Y** | Do not copy it. Fix the old toggle only if it is replaced | *[Ref]* | *[Sprint]* |
| I-8 Permission | **Y** | New `AuthEnum` key; backend sends it per role; show badges read-only for others | *[Ref]* | *[Sprint]* |
| I-9 Provider mapping | **Y** | Backend returns the provider list with names and ids; POS does not hard-code | — | *[Sprint]* |
| I-10 Catering/Kiosk visibility | **Y (needs backend)** | Backend returns which sources exist for the restaurant | — | *[Sprint]* |
| I-11 Time picker | **Y** | New small component in 15-minute steps, reusable | — | *[Sprint]* |
| I-12 Naming | **Y (scoped)** | Use `OrderHubSourceEnum` in new code only | *[Ref]* | *[Sprint]* |
| I-13 Tests | **Y (scoped)** | Unit tests for time calculation, mutual exclusivity (time vs indefinite), card visibility rules, Save/Cancel state | *[Ref]* | *[Sprint]* |

**Governance:** Everything on the pause path that can silently show a wrong state is fixed in this iteration. New code goes in new files, not in the large Orders Hub classes. Nothing is ignored without a ticket.

---

## 5. Impact Assessment

### 5.1 UI Impact
- Orders Hub: "Kitchen control" button and status badges (one per paused source) in the top strip.
- New modal: source cards (Grubhub, DoorDash, Uber Eats, **single Online Ordering card**, Catering, Kiosk), toggle, pause-until picker, indefinite checkbox, Save, Cancel.
- Online Ordering card hidden when disabled in Super Admin. Catering/Kiosk shown only when enabled.
- No navigation change.

### 5.2 API Contract Impact
- **Yes.** New AIO endpoints (get state, set state per source) returning per-source status, pause end time (UTC) and an error per source. The backend calls Kitchen Hub. Older POS builds simply do not show the button.

### 5.3 Database Impact
- **None.** State is read from the server and not stored locally.

### 5.4 Performance Impact
- Low. Up to 3 provider calls on open (through the backend), run in parallel.

### 5.5 Backward Compatibility
- Recommended: feature flag per restaurant, plus the new permission key (button hidden when absent).
- Existing online-ordering toggle stays until the product decides (I-6).
- Mixed fleet: older builds do not show badges, so staff on those devices will not see a pause.

---

## 6. Risk Assessment

| Risk | Probability | Impact | Mitigation |
| --- | --- | --- | --- |
| Backend/Kitchen Hub contract not ready | High | **High** | Agree contract first (I-1) |
| Source shows "paused" but the provider is live (or reverse) | Medium | **High** | Apply state only after success; fresh GET on open; show `unknown` |
| Paused source never resumes | Medium | High | Backend-owned auto-resume; badge refresh; alert on pauses older than expected |
| Wrong "paused until" time (time zone, midnight) | Medium | High | Absolute server time; restaurant time zone; tests |
| Two devices disagree | High | Medium | Fresh read on open; optional push event |
| Wrong role changes settings | Low | Medium | New permission key; read-only for others |
| Two controls for online ordering conflict | High | Medium | One source of truth (I-6) |
| Partial save across providers | Medium | Medium | Per-source result and retry |

**Rollback strategy**
- Turn off the per-restaurant flag or remove the permission key to hide the feature with no app release.
- A pause already set at a provider is not undone by a client rollback; managers resume from the provider or the backend.

**Monitoring plan**
- New Relic events: modal opened, save started/succeeded/failed per source, pause set (source, until/indefinite), resume (manual/auto), status `unknown` (**no keys or integration ids in events**).
- Alert on sources paused indefinitely for more than a set time and on save failures.

---

## 7. Open Questions (blocking)

1. **Backend:** Which endpoints? Does the backend proxy Kitchen Hub (the POS has no client or key)? What does a per-source result look like on partial failure?
2. **AIO sources:** How does the backend enforce a pause for Online Ordering, Catering and Kiosk (reject at checkout, hide ordering)? Does the Kiosk app read this state?
3. **Online ordering overlap:** What is the relationship between the existing Online Orders On/Off button (`enableOnlineOrdering`), the Super Admin flag, and the new card? Is the old button removed?
4. **Auto-resume:** Is it fully server-side for both Kitchen Hub and AIO sources? How does the POS learn the source resumed (poll, push, refresh on open)?
5. **Time:** Which time zone for "Paused until" (restaurant or device)? How are times past midnight handled? Is the maximum pause length limited?
6. **Roles:** Which roles can edit? The PRD mentions Manager/Admin and Shift Lead, but the matrix lists Manager and Owner. Existing role keys are `manager`, `owner` and `shift-or-assistant-manager`. Is manager approval (PIN) required?
7. **Visibility:** Are Catering and Kiosk shown only when enabled for the restaurant? Where does that setting come from?
8. **Unknown status:** What should the UI show when Kitchen Hub returns `unknown`?
9. **Badges:** Where exactly do badges go (Orders Hub only, or the global header)? Do they also show when a source is disabled in Kitchen Hub directly?
10. **Deferred items:** The auto-fire and additional-prep-time stories are marked out of scope: confirm they are not part of this release and whether the modal reserves space for them.

---

## 8. Final Approval

| Role | Approval |
| --- | --- |
| Engineering Manager Approval | |
| Platform Lead Approval | |
| Squad Lead Approval | |
| Product Acknowledgment | |
| Date | |
