# Android Premium UI Refresh — Kiosk + Cashier

This implementation lives on PR #13, **feature/android-premium-ui-refresh**, based on the Android kiosk PR #12 (itself based on the hybrid-inventory PR #10). It is an actual Jetpack Compose UI update to both existing Android apps; no new demo app and no Windows UI change.

## Self-ordering kiosk (Android only)
- Premium green/cream two-pane manager pairing on landscape devices; branded hero + vertical setup on portrait. Existing verified-manager login, branch selection, encrypted device pairing and identity logic are unchanged.
- Customer welcome screen now has an espresso/green café hero, dine-in/takeout choice, and touch-friendly Start Ordering control.
- Live menu screen has dynamic category pills, responsive card grid, coffee/pastry illustrative icon panels (not invented products), and sticky cart/total action.
- Original backend catalog, product availability checks, customization, cart, pay-at-cashier and disabled-by-default QR Ph paths remain.
- **The screenshot's black 'Overlay #1: 1280×720, 240 dpi' is an Android Developer Options simulated secondary display overlay, not a kiosk app view.** Disable it using Settings → Developer options → Simulate secondary displays → None.

## Cashier POS (Android only)
- New light café sidebar with Dashboard, Orders (POS), Menu, Kiosk Orders, Order History, Reports, Cloud and Settings; responsive 135dp / 178dp width.
- POS opens by default instead of dashboard. Landscape tablets at >=700dp show the sidebar, and cashier content at >=650dp presents a two-column product / cart layout.
- New café white/green header with branch-binding state and licensing control, category chips, rich icon/gradient product cards and real prices from local SQLite (and the approved cloud catalog when linked).
- Right order panel now has individual item quantity controls and remove actions, dine-in/takeout, note, cash/GCash/Maya/card recording, discount, cash tender, totals, and prominent checkout action. Existing local checkout, inventory outbox, PDF receipt and multi-printer routing retained.
- Other workflow screens still use the existing functional Compose views, to be progressively restyled.

## Scope and honest acceptance
- Visual image assets are illustrative category icons; real product photos are not populated because the existing brew_products schema lacks product image URLs. Do not insert mocked prices into the active café inventory.
- The kiosk's dedicated Supabase ordering tables/Edge Function remain **unapplied and undeployed**. QR Ph is **NOT live**; pre-existing fail-closed test logic is preserved. A kiosk cannot be used at a live customer counter until branch/merchant onboarding, secure server pairing/ordering, and physical checks are complete.
- Build artifacts are debug APKs for technical preview; no overwrite of deployed website/owner API, no customer data reset or live SQL migration.
- Device rotation and scaling must be tested on the actual Android tablet, plus Bluetooth printer settings and payment retries before production release.
