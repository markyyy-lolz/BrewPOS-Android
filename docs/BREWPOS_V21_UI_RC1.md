# BrewPOS v2.1 UI Release Candidate — StorePOS-inspired Hospitality Design

Status: **Development PR, not released to live merchants**. The dedicated BrewPOS cloud remains untouched.

## Verified dependencies

- Dedicated Supabase BrewPOS project: `rfxzbuocxersgbshczbj`, ACTIVE_HEALTHY.
- 11 public tables with RLS, an idempotent server ingestion migration, and active `brewpos-sync` + `brewpos-site` Edge Functions.
- 1 organization and 1 branch, but **0 memberships, 0 registered tablets, 0 cloud products and 0 cloud sales** at inspection. Cloud device onboarding is required before commercial deployment.
- Existing BrewPOS Android Kotlin/Compose + SQLite local-first checkout, customer thermal/PDF receipt, separate price-free barista ticket, signed local activation, transaction outbox and cloud client are reused.

## v2.1 visual design

**StorePOS-inspired workflow; distinct BrewPOS identity**. No StorePOS code was copied into the BrewPOS repository. Original BrewPOS architecture and product data are preserved.

- Wider forest/emerald sidebar with clear active screen, secondary labels and local-first status.
- Responsive 11-inch Android tablet layout: left sidebar, menu grid, right checkout panel; smaller devices retain bottom navigation and collapsible cart.
- White/sage cards, warm café accent, typography hierarchy, dashboard sales/order/queue/stock KPIs.
- Active business-type profile in Settings: **Café, Restaurant, Milk Tea, Silogan**; persisted in existing `shop_prefs`, **UI-only** and does not modify historical receipts or the Supabase organization.
- Product catalog categories derive dynamically from the real local menu. Adding/editing a product offers business-specific suggestions **plus arbitrary category text**.
- POS line-item customization continues for coffee, non-coffee, tea and milk tea.
- Dashboard distinguishes *local sales* from *cloud pending sync*. It never claims consolidated cloud reporting.

## Critical pre-release gates

- Android PR CI must pass unit tests and debug APK build.
- Fresh installation + update migration tests in Android emulator and two physical tablets.
- No cloud sales until verified staff sign-in, authorized org membership and device registration.
- Device registration, outbox sync and duplicate replay tests with real authenticated accounts.
- Verify receipt + kitchen/barista slips with actual printer, PDF, auto-cut, stock and cash/change math.
- Confirm signed production APK key continuity before distribution.
- Audit performance advisor's missing indexes before introducing heavy multi-branch cloud load.
- Implement full backup/restore, ingredient recipe stock, secure role management and fiscal-compliant invoice flow for commercial use.

## Deployment

Development branch: `feature/brewpos-v2-storepos-inspired-ui`.
**Do not merge into production automatically or reinstall live BrewPOS until the above acceptance tests.**
