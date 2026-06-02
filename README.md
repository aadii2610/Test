# SharedRepository Migration Plan

## Problem

`Constants.kt` (mpos module) mixes two very different things:

- **True constants** — `const val` compile-time values that never change (URLs, key strings, port numbers). These are fine where they are.
- **Mutable runtime state** — `var` fields and `MutableLiveData` instances that represent live app state (tokens, payment amounts, ticket data, etc.). These are currently global mutable singletons with no encapsulation, no clear ownership, and no testability.

The mutable state is the problem. It is written and read from anywhere in the codebase with no injection boundary, making it hard to test, hard to reason about, and fragile across process recreation.

---

## Goal

Move all **mutable runtime state** out of `Constants` into a `SharedRepository` singleton that is:
- Injected via Hilt wherever needed
- Mockable in tests
- Logically grouped by domain

True `const val` entries stay in `Constants.kt` — they are zero-cost compile-time values and need no change.

---

## What Moves vs What Stays

### Stays in `Constants.kt` (compile-time constants)

```
const val BASE_URL, SSE_URL, REFRESH_TOKEN_URL, STRIPE_BASE
const val KEY_USERID, KEY_USERNAME, KEY_ROLEID, KEY_ROLENAME, KEY_CLOCKED_IN, ...  (SharedPrefs keys)
const val RESTAURANT_ID, TIME_ZONE, STORE_ID, IMAGE_URL, ... (other string keys)
const val PORT, MAX_TICKETS_PER_ROW, MQTT_PORT, MAX_LOCATIONS
const val MQTT_END_POINT, MQTT_END_POINT_INTERNAL, MQTT_END_POINT_POS, MQTT_END_POINT_PROD
const val MAX_ATTEMPTS_PAYMENT_AUTH, DELAY_DURATION_AUTH
const val CURRENCY, APK_FILE_NAME, MPOS, DEVICE_REVOKED, RE_ONBOARD, DEFAULT, INSTALLATION_ID
const val HeaderItemType, LoyaltyItemType, HeaderItemTypeColumns, LoyaltyItemTypeColumns
```

### Moves to `SharedRepository`

| Domain group | Fields |
|---|---|
| **Auth / Session** | `SESSION_ID`, `BEARER_TOKEN`, `TENANT_ID`, `REFRESH_TOKEN`, `USER_ACCESS_TOKEN`, `USER_ID_TOKEN`, `RESET_USER_ID_TOKEN`, `M_POS_ID`, `USER_ID_STR`, `employeeName`, `USERID` |
| **Payment state** | `discount`, `tax`, `subtotal`, `allDiscounts`, `total`, `tempSubtotal`, `paymentAmount`, `tenderName`, `tenderId`, `tenderIsTipAllowed`, `balanceDue`, `remainingBalance`, `cashTendered`, `changeDue`, `serviceCharge`, `taxRate`, `paymentId`, `rating`, `receiptName`, `emailAddress`, `phoneNumber`, `paymentStatus`, `paymentSummaryStatus`, `ticketId`, `paymentIntentId`, `pspReferenceIdAdyen`, `paymentMethod`, `paymentProvider`, `tipAmount`, `customTipAmount`, `partialPaid`, `paymentUtilObject`, `serviceChargeList` |
| **Register / Table** | `SELECTEDTABLEID`, `TABLE_NUM`, `billType`, `guestChipClicked`, `selectedTicketId`, `ticketsByTableResponse`, `currentlySelectedTicketPos`, `currentTicket`, `TICKETSTATUS`, `CHANGEDTABLENUMBER`, `createTicketResponse`, `printersListResponse`, `terminalStatus`, `connectLocationId`, `AUTOGRATITUITYBOOL`, `LARGEPARTYSIZE` |
| **Guest selection** | `currentlySelectedGuest`, `currentSelectedName`, `currentGuest` |
| **Discount** | `discountType`, `ticketItemId`, `discountPercent`, `employeeDiscountPercent`, `discountPrice` |
| **UI flags** | `TICKETS_REMAINING`, `IS_CARD_FRAG_VISIBLE`, `PROCESSPAYMENTDONE`, `isTipReceiptNavigationPending`, `isConnected`, `restaurantInfoFailed`, `loyaltyPoints` |
| **LiveData events** | `tableChangeLiveData`, `setNewGuestLiveData`, `createNewGuestLiveData`, `isTableChanged`, `_menuItemLiveData`, `splitTicketRefreshLiveData`, `isMenuSheetVisibleEvent`, `discountPercentPaymentBool`, `employeeDiscountPercentPaymentBool`, `discountPricePaymentBool`, `callItemFragment` |
| **Card / Adyen** | `cardDetailsPaymentIntent` |

---

## Interface Design

Create the interface at:
`mpos/src/main/java/aio/app/mpos/repositories/shared/SharedRepository.kt`

```kotlin
package aio.app.mpos.repositories.shared

import aio.app.commons.datamodels.adyen.CardDetails
import aio.app.commons.datamodels.posTicketItems.TicketData
import aio.app.commons.datamodels.posTicketItems.servicecharges.ServiceChargeModel
import aio.app.commons.utils.PaymentUtilObject
import aio.app.mpos.datamodels.deviceresponse.GetPosDevicesResponse
import aio.app.mpos.datamodels.ticket.CreateTicketResponse
import aio.app.mpos.datamodels.ticket.TicketResponse
import androidx.lifecycle.MutableLiveData

interface SharedRepository {

    // --- Auth / Session ---
    var sessionId: String
    var bearerToken: String
    var tenantId: Int
    var refreshToken: String
    var userAccessToken: String
    var userIdToken: String
    var resetUserIdToken: String
    var mPosId: Int
    var userIdStr: String
    var employeeName: String
    var userId: String

    // --- Payment ---
    var discount: Double
    var tax: Double
    var subtotal: Double
    var allDiscounts: Double
    var total: Double
    var tempSubtotal: Double
    var paymentAmount: Double
    var tenderName: String
    var tenderId: Int
    var tenderIsTipAllowed: Boolean
    var balanceDue: Double
    var remainingBalance: Double
    var cashTendered: Double
    var changeDue: Double
    var serviceCharge: Double
    var taxRate: Double
    var paymentId: Int
    var rating: Int
    var receiptName: String
    var emailAddress: String
    var phoneNumber: String
    var paymentStatus: String
    var paymentSummaryStatus: String
    var ticketId: String
    var paymentIntentId: String?
    var pspReferenceIdAdyen: String
    var paymentMethod: String
    var paymentProvider: String?
    var tipAmount: Double
    var customTipAmount: Double
    var partialPaid: Boolean
    var paymentUtilObject: PaymentUtilObject
    var serviceChargeList: ArrayList<ServiceChargeModel>
    var cardDetailsPaymentIntent: CardDetails

    // --- Register / Table ---
    var selectedTableId: Int
    var tableNum: Int
    var billType: String
    var guestChipClicked: Int
    var selectedTicketId: Int
    var ticketsByTableResponse: TicketResponse
    var currentlySelectedTicketPos: Int
    var currentTicket: TicketData
    var ticketStatus: String
    var changedTableNumber: String
    var createTicketResponse: CreateTicketResponse
    var printersListResponse: GetPosDevicesResponse?
    var terminalStatus: String
    var connectLocationId: String?
    var autoGratuityBool: Boolean
    var largePartySize: Int

    // --- Guest ---
    var currentlySelectedGuest: ArrayList<String>
    var currentSelectedName: String
    var currentGuest: String

    // --- Discount ---
    var discountType: String
    var ticketItemId: Int
    var discountPercent: Double
    var employeeDiscountPercent: Double
    var discountPrice: Double

    // --- UI flags ---
    var ticketsRemaining: Boolean
    var isCardFragVisible: Boolean
    var processPaymentDone: Boolean
    var isTipReceiptNavigationPending: Boolean
    var isConnected: Boolean
    var restaurantInfoFailed: Boolean
    var loyaltyPoints: Int

    // --- LiveData events ---
    val tableChangeLiveData: MutableLiveData<Boolean>
    val setNewGuestLiveData: MutableLiveData<Boolean>
    val createNewGuestLiveData: MutableLiveData<Boolean>
    val isTableChanged: MutableLiveData<Boolean>
    val menuItemLiveData: MutableLiveData<Boolean>
    val splitTicketRefreshLiveData: MutableLiveData<Boolean>
    val isMenuSheetVisibleEvent: MutableLiveData<Boolean>
    val discountPercentPaymentBool: MutableLiveData<Boolean>
    val employeeDiscountPercentPaymentBool: MutableLiveData<Boolean>
    val discountPricePaymentBool: MutableLiveData<Boolean>
    val callItemFragment: MutableLiveData<Boolean>

    // --- Utility ---
    fun resetPaymentState()
    fun resetSessionState()
}
```

---

## Implementation

Create at:
`mpos/src/main/java/aio/app/mpos/repositories/shared/SharedRepositoryImpl.kt`

```kotlin
package aio.app.mpos.repositories.shared

import aio.app.commons.datamodels.adyen.CardDetails
import aio.app.commons.datamodels.posTicketItems.TicketData
import aio.app.commons.datamodels.posTicketItems.servicecharges.ServiceChargeModel
import aio.app.commons.utils.PaymentUtilObject
import aio.app.mpos.datamodels.deviceresponse.GetPosDevicesResponse
import aio.app.mpos.datamodels.ticket.CreateTicketResponse
import aio.app.mpos.datamodels.ticket.TicketResponse
import androidx.lifecycle.MutableLiveData
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SharedRepositoryImpl @Inject constructor() : SharedRepository {

    // Auth
    override var sessionId = ""
    override var bearerToken = ""
    override var tenantId = -1
    override var refreshToken = ""
    override var userAccessToken = ""
    override var userIdToken = ""
    override var resetUserIdToken = ""
    override var mPosId = -1
    override var userIdStr = ""
    override var employeeName = ""
    override var userId = ""

    // Payment
    override var discount = 0.0
    override var tax = 0.0
    override var subtotal = 0.0
    override var allDiscounts = 0.0
    override var total = 0.0
    override var tempSubtotal = 0.0
    override var paymentAmount = 0.0
    override var tenderName = ""
    override var tenderId = -1
    override var tenderIsTipAllowed = false
    override var balanceDue = 0.0
    override var remainingBalance = 0.0
    override var cashTendered = 0.0
    override var changeDue = 0.0
    override var serviceCharge = 0.0
    override var taxRate = 0.0
    override var paymentId = 0
    override var rating = 0
    override var receiptName = ""
    override var emailAddress = ""
    override var phoneNumber = ""
    override var paymentStatus = ""
    override var paymentSummaryStatus = ""
    override var ticketId = ""
    override var paymentIntentId: String? = null
    override var pspReferenceIdAdyen = ""
    override var paymentMethod = ""
    override var paymentProvider: String? = null
    override var tipAmount = 0.0
    override var customTipAmount = 0.0
    override var partialPaid = false
    override var paymentUtilObject = PaymentUtilObject()
    override var serviceChargeList = ArrayList<ServiceChargeModel>()
    override var cardDetailsPaymentIntent = CardDetails()

    // Register / Table
    override var selectedTableId = -1
    override var tableNum = -1
    override var billType = "Shared"
    override var guestChipClicked = 0
    override var selectedTicketId = -1
    override var ticketsByTableResponse = TicketResponse()
    override var currentlySelectedTicketPos = 0
    override var currentTicket = TicketData()
    override var ticketStatus = ""
    override var changedTableNumber = ""
    override var createTicketResponse = CreateTicketResponse()
    override var printersListResponse: GetPosDevicesResponse? = null
    override var terminalStatus = ""
    override var connectLocationId: String? = null
    override var autoGratuityBool = false
    override var largePartySize = -1

    // Guest
    override var currentlySelectedGuest = ArrayList<String>()
    override var currentSelectedName = ""
    override var currentGuest = ""

    // Discount
    override var discountType = ""
    override var ticketItemId = -1
    override var discountPercent = 0.0
    override var employeeDiscountPercent = 0.0
    override var discountPrice = 0.0

    // UI flags
    override var ticketsRemaining = false
    override var isCardFragVisible = false
    override var processPaymentDone = false
    override var isTipReceiptNavigationPending = false
    override var isConnected = false
    override var restaurantInfoFailed = false
    override var loyaltyPoints = 0

    // LiveData
    override val tableChangeLiveData = MutableLiveData<Boolean>()
    override val setNewGuestLiveData = MutableLiveData<Boolean>()
    override val createNewGuestLiveData = MutableLiveData<Boolean>()
    override val isTableChanged = MutableLiveData<Boolean>()
    override val menuItemLiveData = MutableLiveData<Boolean>()
    override val splitTicketRefreshLiveData = MutableLiveData<Boolean>()
    override val isMenuSheetVisibleEvent = MutableLiveData<Boolean>()
    override val discountPercentPaymentBool = MutableLiveData<Boolean>()
    override val employeeDiscountPercentPaymentBool = MutableLiveData<Boolean>()
    override val discountPricePaymentBool = MutableLiveData<Boolean>()
    override val callItemFragment = MutableLiveData<Boolean>()

    override fun resetPaymentState() {
        discount = 0.0; tax = 0.0; subtotal = 0.0; allDiscounts = 0.0; total = 0.0
        tempSubtotal = 0.0; paymentAmount = 0.0; tenderName = ""; tenderId = -1
        tenderIsTipAllowed = false; balanceDue = 0.0; remainingBalance = 0.0
        cashTendered = 0.0; changeDue = 0.0; serviceCharge = 0.0; taxRate = 0.0
        paymentId = 0; rating = 0; receiptName = ""; emailAddress = ""; phoneNumber = ""
        paymentStatus = ""; paymentSummaryStatus = ""; ticketId = ""
        paymentIntentId = null; pspReferenceIdAdyen = ""; paymentMethod = ""
        paymentProvider = null; tipAmount = 0.0; customTipAmount = 0.0
        partialPaid = false; paymentUtilObject = PaymentUtilObject()
        serviceChargeList = ArrayList(); cardDetailsPaymentIntent = CardDetails()
        processPaymentDone = false; isTipReceiptNavigationPending = false
    }

    override fun resetSessionState() {
        sessionId = ""; bearerToken = ""; tenantId = -1; refreshToken = ""
        userAccessToken = ""; userIdToken = ""; resetUserIdToken = ""; mPosId = -1
        userIdStr = ""; employeeName = ""; userId = ""
    }
}
```

---

## DI Registration

Add a binding in `RepositoryModule.kt`:

```kotlin
// In the existing RepositoryModule (SingletonComponent)
@Binds
@Singleton
abstract fun bindSharedRepository(
    sharedRepositoryImpl: SharedRepositoryImpl
): SharedRepository
```

No changes needed to `CoreModule` or `NetworkModule`.

---

## Usage — Before vs After

### Before
```kotlin
// In a ViewModel or Fragment — direct global mutation
Constants.BEARER_TOKEN = token
Constants.discount = 12.5
Constants.tableChangeLiveData.postValue(true)
```

### After
```kotlin
@HiltViewModel
class CheckoutViewModel @Inject constructor(
    private val repo: Repository,
    private val sharedRepo: SharedRepository   // <-- inject here
) : ViewModel() {

    fun applyDiscount(amount: Double) {
        sharedRepo.discount = amount
        sharedRepo.discountPercentPaymentBool.postValue(true)
    }

    fun onTableChanged() {
        sharedRepo.tableChangeLiveData.postValue(true)
    }
}
```

For Fragments that observe LiveData but don't need to write state, only inject `SharedRepository` via the ViewModel — the Fragment observes through the ViewModel, not directly.

---

## Migration Strategy

Because `Constants` is referenced in many places, a full cut-over in one PR would be too risky. The recommended approach is:

### Phase 1 — Add the repo, keep Constants working (no breakage)
1. Create `SharedRepository` interface + `SharedRepositoryImpl`.
2. Register in `RepositoryModule`.
3. Do **not** delete anything from `Constants.kt` yet.
4. In new code, write to `SharedRepository` only.

### Phase 2 — Migrate one domain group at a time
Migrate in this order (lowest blast radius first):
1. Auth/Session fields — written in login flow only
2. Discount fields — isolated to discount fragments
3. Guest/Table fields — used by register screens
4. Payment fields — broadest usage, do last

For each group:
- Find all reads/writes via `grep -r "Constants\.<fieldName>"`.
- Replace writes with `sharedRepo.<field> = ...` in the owning ViewModel.
- Replace reads with `sharedRepo.<field>` wherever the class already has `SharedRepository` injected.
- Delete the field from `Constants.kt` once all call sites are gone.

### Phase 3 — Remove Constants mutable state entirely
After all `var` fields are migrated, `Constants.kt` becomes a pure `const val` object. At this point:
- Rename it to something like `AppKeys.kt` or `AppConfig.kt` if desired.
- Remove the `object` wrapper if you prefer top-level `const val` declarations.

---

## Commons Module Constants

`commons/src/main/java/aio/app/commons/utils/Constants.kt` contains:
- All view-height `const val` — leave them, they are true constants.
- `var DATEFORMAT` and `var taxListCustom` — these are mutable and could move to a commons-level `SharedRepository`, but since the commons module has no DI of its own it is simpler to keep them as-is or pass them via function parameters when needed.

---

## Testing Benefit

With `SharedRepository` as an interface, tests can provide a fake:

```kotlin
class FakeSharedRepository : SharedRepository {
    override var bearerToken = "test-token"
    override var discount = 0.0
    // ... set only what the test needs
}

@Test
fun `checkout applies discount correctly`() {
    val fake = FakeSharedRepository()
    val vm = CheckoutViewModel(fakeRepo, fake)
    vm.applyDiscount(10.0)
    assertEquals(10.0, fake.discount)
}
```

This is impossible with the current static `Constants` object.
