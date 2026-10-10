# BrewPOS Self-Ordering Kiosk — Android + Windows

Development branch: \`feature/brewpos-self-order-kiosk-android-windows\`. Shared original UI in \`kiosk/ui/\`; native Android full-screen shell in \`kiosk/android/\`; Windows kiosk/cashier/barista WPF WebView2 shell in \`kiosk/windows/\`; HTTPS LAN hub and SQLite state machine in \`kiosk/hub/\`.

**This is a development build, not an already-installed or activated commercial kiosk.** Existing BrewPOS Android application, BrewPOS database, StorePOS, sales history and receipt records were not reset or overwritten.

## Order lifecycle

1. Owner/manager provisions an authorized BrewPOS organization/branch, registers the LAN hub device through \`brewpos-sync\`, and uses the existing Supabase authenticated membership to publish the approved menu. Café Oabi is NOT automatically created; it must be onboarded with authorized staff first.
2. Manager configures the LAN hub on a trusted café Windows PC with a branch UUID, HTTPS certificate and **FOUR unique random 32+ character role-specific pairing keys**.
3. Pair the Android tablet or Windows kiosk using the **kiosk** key. Separate staff devices use **cashier** or **kitchen** keys.
4. Customer selects products, modifiers, dine-in/takeout, then Pay at Counter. Each client recalculates totals using a locally cached, previously authorized menu and saves an immutable UUID order in SQLite before contacting the hub. If LAN is unavailable, the device shows **NOT DELIVERED**. A local order is **not paid** and should not appear at the barista station.
5. The HTTPS hub validates branch-authorized catalogue prices and option IDs, deduplicates the UUID, and records an **AwaitingPayment** order. Kiosk retries the identical UUID after disconnection.
6. Authorized CASHIER verifies funds were actually received, then marks the order **Paid**. Only then can KITCHEN mark **Preparing → Ready → Completed**.
7. An authorized member can register the hub with \`/v1/cloud/register\` (owner/manager JWT) and reconcile paid orders through \`/v1/cloud/sync-paid\` (staff JWT). The hub uses the existing BrewPOS \`brewpos-sync\` route with a fixed UUID for exactly-once cash-sale insertion. It never writes unpaid orders as sales.
8. During internet outages, the LAN hub continues to accept/pay/prepare locally over the existing café network. If the LAN itself fails, each kiosk retains orders locally but **cannot guarantee** other devices have received them.

## Hub setup (trusted Windows host)

Build \`kiosk/hub/BrewPOS.Kiosk.Hub.csproj\` with .NET 10 or use the GitHub Actions **BrewPOS-Kiosk-LAN-Hub-Windows-v0.1.0** artifact. Install a valid TLS certificate trusted by all Android/Windows devices, with the host/IP in certificate SAN; LAN HTTP and ignored TLS certificate errors are NOT supported.

Configure environment variables on the hub PC (values are private, do not commit them):

- \`BREW_HUB_URL\` = \`https://0.0.0.0:8443\`
- \`BREW_HUB_ORGANIZATION_ID\` = approved BrewPOS organization UUID
- \`BREW_HUB_BRANCH_ID\` = approved BrewPOS branch UUID
- \`BREW_HUB_ADMIN_KEY\`, \`BREW_HUB_KIOSK_KEY\`, \`BREW_HUB_CASHIER_KEY\`, \`BREW_HUB_KITCHEN_KEY\`: four **distinct** cryptographically random long secrets
- \`ASPNETCORE_Kestrel__Certificates__Default__Path\` = absolute PFX path
- \`ASPNETCORE_Kestrel__Certificates__Default__Password\` = PFX password (set through secure OS secret management)

The hub stores orders at \`%LOCALAPPDATA%\Azurate\BrewPOS.Kiosk.Hub\hub.sqlite3\`. Back up this SQLite file, its WAL state, and device pairing configuration. Do not delete it during upgrades.

### Role-restricted API

| Route | Role | Purpose |
| --- | --- | --- |
| GET /healthz | none | non-sensitive health |
| GET /v1/catalog | all paired roles | branch-published kiosk menu |
| PUT /v1/catalog | admin | owner-curated menu with approved modifiers and prices |
| POST /v1/catalog/refresh | admin + current BrewPOS owner/manager JWT | pull approved products from existing BrewPOS Supabase (preserves locally curated modifier groups; does not invent charges) |
| POST /v1/orders | kiosk | verify prices/options; create unpaid order |
| GET /v1/orders/{id} | paired | order status for assigned UUID; avoid exposing arbitrary PII |
| GET /v1/orders | cashier/kitchen/admin | role-limited queues (kitchen excludes unpaid) |
| PATCH /v1/orders/{id}/status | cashier/kitchen/admin | approved finite-state changes and audit trail |
| POST /v1/cloud/register | admin + active BrewPOS staff JWT | register hub as an approved device |
| POST /v1/cloud/sync-paid | cashier/admin + active BrewPOS staff JWT | upload PAID cash orders only; preserve matching cloud ACK and UUID |

Use TLS even on a private LAN. A simple display-name or PIN is not sufficient authentication; every device requires its matching secret role key. Do not enter service_role, PayMongo secret key, or server admin credentials in a kiosk app.

### Menu format

The hub expects product objects:
\`{id:UUID,name:"Iced Latte",category:"Coffee",description:"...",priceCentavos:12000,version:1,active:true,modifiers:[{id:"size",name:"Size",required:true,multiple:false,maxSelections:1,options:[{id:"regular",name:"Regular",deltaCentavos:0},{id:"large",name:"Large",deltaCentavos:2000}]}]}\`

**Those numbers are an API-format illustration, not Café Oabi prices or inserted records.** Kiosk menu remains EMPTY until an approved manager imports/publishes the actual café catalogue. Product IDs/price/version are verified against the trusted server on every submitted order; no demo menu is automatically seeded.

## Windows / Android

GitHub Actions \`.github/workflows/brewpos-kiosk-build.yml\` builds:
- Android kiosk **debug APK** (separate \`com.brewpos.kiosk\` package, does not overwrite BrewPOS Android)
- Windows x64 **Inno Setup EXE** with role selection at first installation (kiosk, cashier, barista)
- Standalone Windows x64 **HTTPS LAN hub** executable (run as a dedicated Windows user or service with private certificates)
- Tests for order idempotency, totals/price tampering and customer-to-kitchen paid-state gates.

Android HTTPS pairing tokens use Android Keystore AES-GCM, Android customer orders persist in SQLite. Windows pairing tokens are protected using Windows DPAPI; SQLite persists under \`%LOCALAPPDATA%\Azurate\BrewPOS.Kiosk.Windows\`. Windows WebView2 uses a bundled trusted virtual host and blocks external navigation.

**Dedicated-device lockdown** still requires Android Enterprise device-owner/Lock Task configuration and Windows Assigned Access/Kiosk policy. Immersive/full-screen flags alone do not secure a public kiosk.

## Important remaining integration gates

- The existing BrewPOS database currently has no Café Oabi tenant/staff membership; onboarding requires owner authorization.
- PayMongo/GCash/Maya online payments are **NOT ENABLED**. A real merchant account, provider credentials, secure checkout creation, verified webhooks/server-side payment lookup, amount/merchant/branch/reference matching and refunds policy are required before any online option is shown.
- **BrewPOS's existing \`brew_ingest_sale\` does not decrement shared product inventory.** Complete Android/Windows stock reconciliation needs a separate backed-up reviewed migration. Do not count existing cloud inventory as accurate after concurrent kiosk/Android sales until this is fixed.
- Staff authentication for the hub's cloud registration and paid-order sync currently uses a fresh staff JWT supplied to the restricted LAN endpoint. A production manager admin UI and rotation workflow should replace this operator-facing API step.
- Successful GitHub compilation is not actual hardware QA. Test Android touchscreen scaling, Windows touchscreen, real Bluetooth/USB printer, cashier-barista order transitions, HTTPS certificate trust, 2+ offline clients, restart recovery, price changes, idempotent cloud sale, online status, and physical stocktake conflicts before deploying publicly.
