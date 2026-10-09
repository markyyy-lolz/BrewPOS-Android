# ☕ BrewPOS v2.0 UI Foundation Preview — Coffee Shop POS for Android

**Azurate Software Solutions** · Proprietary Android Studio project · Offline-first · Customer receipt + price-free barista slip via one Bluetooth printer

**For GitHub upload:** See [HOW_TO_PUBLISH.md](HOW_TO_PUBLISH.md). For package roadmap and proposed prices, see [docs/PRICING_AND_ROADMAP.md](docs/PRICING_AND_ROADMAP.md). GitHub Actions attempts a debug APK build on every main-branch push; it is not a guarantee the app compiles without further fixes.


The v2.0 foundation adds a responsive dashboard, sidebar navigation, and a redesigned activation screen. It does **not** yet include working cloud synchronization or the owner website deployment.\n\nA local-first Android POS **source project** written in Kotlin and Jetpack Compose, optimized for coffee shops, phones, and tablets.

## Modules included

- Cashier POS with responsive product grid, search, and category chips
- Customize coffee size (Small / Regular / Large), extra shot, alternative milks, syrups and cream
- Dine-in or takeout, quantities, notes/table number, peso discount
- Cash tender/change or **manual recording** of GCash, Maya, or card payment
- Atomic SQLite checkout: persist complete sale and deduct tracked menu-item stock together; prevent overselling
- Barista queue: Queued → Preparing → Ready → Served
- Sales history, receipt viewer, reprint of old transactions
- Save PDF and Android Print dialog from **exactly the same PDF bytes**
- **One-printer, two-slip workflow**: after successful checkout, automatically print the customer sales receipt **then** the separate, price-free barista/preparation slip on the SAME paired **Bluetooth Classic SPP / ESC/POS** printer (58mm or 80mm).
- Each slip shows the same persistent receipt/order number. Barista ticket includes quantity, drink name, size, add-ons, dine-in/takeout, table/notes and timestamp, but no prices or payment data.
- ESC/POS auto-cut can separate the two slips **if the printer physically supports it**; otherwise the output includes a tear/separation guide.
- Receipt viewer has **Print BOTH (1 printer)**, **Customer only**, and **Barista only** actions for manual print or reprint; automatic print is configurable in Settings.
- Print jobs are serialized in-app for one printer, and automatic jobs queue rather than competing for the Bluetooth connection.
- Menu management: add/edit/archive products, edit prices, stock tracking and counts
- Today/all-time reports, best sellers
- Shop name / receipt footer / thermal printer preferences
- Export all sales **summaries** as CSV (amounts in centavos)
- Sample drinks and prices (change before actual use)

## Activation preview

The Android app now gates checkout behind an owner-issued signed **Lifetime / Monthly / Trial** activation code tied to its Device ID. Monthly/trial codes have a signed expiry and a 72-hour offline grace period. It does not use an online license server, and the license issuer's private signing key MUST NOT be uploaded to GitHub. See [docs/ACTIVATION_PREVIEW.md](docs/ACTIVATION_PREVIEW.md).

**Not a commercial production release; this is a DEBUG/APK preview for testing only. No Supabase Hybrid sync.**

## What this release does not do

- **Not a prebuilt APK.** Android SDK / Gradle / Google Maven were unavailable in the build environment, so a device build and UI end-to-end tests were NOT performed.
- No Supabase or cross-device sync. Each installation has its own SQLite database. **Do not use two tills expecting shared inventory.**
- No payment-gateway/API transaction verification; e-wallet/card payment labels only record the cashier's choice.
- No staff login / PIN roles, ingredient recipe inventory, refund/void workflow, or fiscal/BIR-compliant invoicing.
- No **full restorable backup** in v1.1. Sales CSV is for analysis/archive, not a recoverable complete database dump. Don't clear app data.
- Bluetooth printer compatibility depends on the model. Printer must support Classic Bluetooth SPP and ESC/POS. BLE-only printers are not covered. Actual printing has NOT been hardware-tested.
- Auto-cut works only on printers that have a cutter and support the ESC/POS command.
- Print-job success means data was sent through the Bluetooth connection, **not** a confirmed physical print. If paper jams/disconnects occur, inspect printed slips before reprinting to avoid duplicate barista preparation.
- Print queue works while the app process is running; it is not a persistent, crash-recoverable job queue. Printing may be interrupted if Android terminates the app.

**Receipt footer contains `NOT AN OFFICIAL BIR INVOICE`.** This generic record is not a legal substitute for a Philippine BIR-compliant invoice or accredited POS registration. Consult your accountant/registration provider before production use.

## Install / build in Android Studio

1. Install current stable **Android Studio**, **Android SDK Platform 36**, JDK 17, and an internet connection for Gradle dependencies.
2. Extract the ZIP and choose **File → Open** and select the folder containing `settings.gradle.kts`.
3. This source export includes `gradle/wrapper/gradle-wrapper.properties` but **does not include the Gradle wrapper binary/scripts** (build tools were not available for generating the official wrapper). Configure Android Studio to use Gradle **8.13** in **Settings → Build Tools → Gradle**, or generate official wrapper via installed Gradle CLI: `gradle wrapper --gradle-version 8.13`.
4. Sync the project and allow Android Studio to resolve the Android Gradle Plugin and Kotlin dependencies.
5. Alternatively on Windows, with Gradle 8.13 on your PATH, double-click `BUILD_APK_WINDOWS.bat`. Run on a physical Android tablet/phone (Android 8.0 or newer) using **Run**. To export a debug APK choose **Build → Build APK(s)**. For a distributable signed release, use **Build → Generate Signed Bundle / APK**.
6. At first launch, configure the shop name and products in Settings/Menu; all sample prices and stock are illustrative.
7. For printers: pair a compatible ESC/POS printer via Android Bluetooth Settings, grant Nearby Devices permission (Android 12+), and choose **one** device in BrewPOS Settings.
8. Keep **Auto-print customer receipt, then barista slip** enabled (default); choose 58mm/80mm, and enable **Auto-cut** only if the printer has a working cutter.
9. Make a test sale: the single printer should issue the customer receipt first and the price-free barista slip second. Without auto-cut, tear along the separator. For later reprints, open **Orders** → select transaction → **Print BOTH** / **Customer only** / **Barista only**.

Gradle plugin: AGP 8.11.1; Kotlin + Compose plugin: 2.2.20; Gradle: 8.13; Compose BOM: 2025.05.01; compile/target SDK 36; min SDK 26.

The 2025 Compose BOM is deliberately pinned because the newer Compose 1.12 / August 2026 BOM requires API 37 and AGP 9; this project uses a compatible AGP 8.x/Android 16 toolchain.

## Important checkout notes

- When **Cash** is chosen, enter the customer's cash tendered before completing the sale.
- GCash/Maya/Card means **manual record only**. Verify independently that the money was actually received before tapping Complete Sale.
- Item stock is a menu-item count, not ingredient-level accounting. For drinks made from shared beans/syrups, disable product stock tracking until ingredient recipes are implemented, or manage counts manually.
- Completing a sale is final in this MVP; there is no refund/void, so use test data while evaluating.
- Checkout is persisted before the printer is called. **Printer failure does not cancel/reverse the sale**; manually print from Orders after checking the paper.
- The barista slip is for preparation, not a separate transaction or a second payment.
- Do not uninstall the app or clear its storage with real sales data. Export sales CSV after shifts and keep a separate business record until a restorable backup is implemented.

## Code layout

| File | Purpose |
|---|---|
| `MainActivity.kt` | POS, cart, barista queue, menu, reports, settings |
| `StoreDb.kt` | SQLite schema, products, transactions, stock, reports, CSV |
| `Models.kt` | Domain models, prices, payment validation |
| `Receipts.kt` | Reprintable PDF / PrintManager / Bluetooth SPP output and serialized printer lock |
| `TicketFormatter.kt` | Android-free ASCII ESC/POS receipt + barista slip formatting, combined two-slip print bytes |
| `tools/validate_tickets.kts` | Pure-Kotlin checks: order details, no price leakage, two cuts, separators, wrapping |
| `app/src/test/.../TicketFormatterTest.kt` | Receipt and barista ticket unit tests |
| `app/src/test/.../CartMathTest.kt` | Pricing and payment unit tests |

Run Android/JVM unit tests in Android Studio with `gradle :app:testDebugUnitTest` (not run here due to absent Android SDK/Gradle). You can verify formatting without Android: compile `Models.kt` + `TicketFormatter.kt` with `kotlinc` into a JAR, then run `kotlinc -script -classpath <jar> tools/validate_tickets.kts`. The existing `tools/validate_models.kts` checks cart pricing.

## Roadmap (not implemented)

- Cloud sync with Supabase, durable offline outbox and duplicate-safe order IDs
- Staff accounts / roles and manager PIN for sensitive actions
- Ingredient recipes with stock consumption and low-stock alerts
- PayMongo verified payments (requires secure backend and merchant account)
- Shared barista KDS and multi-device kitchen queue
- Robust encrypted restorable backup, shift reconciliation, refunds/voids, tax-ready invoice engine

**Original StorePOS/MotoPOS databases are never touched by this standalone app.**
