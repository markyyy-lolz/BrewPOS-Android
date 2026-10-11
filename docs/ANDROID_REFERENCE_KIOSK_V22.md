# BrewPOS Android Self-Order Kiosk — Reference UI v2.2

The existing `kiosk/` native Kotlin/Jetpack Compose Android app now implements the approved BrewPOS visual mockup's screen composition. No demo app, separate database, or simulated menu is used.

## Customer browsing (portrait/tablet)

- Full-width coffee-photo hero with BrewPOS Self Order brand and "Great Coffee, Brighter Days".
- Deep green selected Dine-in vs ivory Takeout tiles.
- Horizontal dynamic category pills sourced from the selected café's **live Supabase branch catalog**.
- Adaptively sized 2-column (phone), 3-column (mid tablet), or 4-column (large kiosk) photo-based product grid showing **real names, prices and stock availability**.
- Photo fallback illustrations and generic illustrative image assets from Unsplash; no mock products or prices are inserted.
- Permanently visible green order summary/footer and Pay at Cashier / QR Ph choice. QR Ph is intentionally unavailable unless the dedicated **TEST** merchant is enabled by the verified backend.
- Existing customer customization modal, review/edit cart, authenticated order submission, generated order number and payment-status confirmation remain.
- Welcome/browse automatically fetches the latest menu periodically; empty catalog presents staff instructions. Android system status bar insets respected.

## Cloud/API

Existing `brewpos-kiosk` Edge Function returns `qrph_test_enabled` only when the **server-side** test merchant keys and the specific approved test organization match. The Android app does not store payment/owner/service-role secrets and cannot enable a live payment on its own. Orders remain unpaid until backend verifies cashier or authorized payment settlement.

Owner can populate product names/categories/prices/stock under either Azurate Menu Catalog or the separate Café Owner Portal. Images for now are **generic illustrative category/product CDN photos**, not owner-uploaded product photos. Exact café product photography can be added as menu metadata in a later step, with owner authorization and approved storage.

## Release and testing

Development preview APKs are available from the `v2.2.0-kiosk-reference-design-preview.1` GitHub prerelease. They are debug-signed, not Play Store or production certification. Confirm phone/tablet dimensions, actual branch pairing, live catalog and sale/stock checks, order numbers, network interruption recovery, cashier queue, and physical Bluetooth receipt/kitchen printing before live unattended use. Configure Android lock-task/device-owner mode for customer kiosk lockdown.

Do not clear existing app data or remove an installed Android POS without a verified local offline-sales backup. Offline kiosk payment/local LAN hub is not yet implemented.
