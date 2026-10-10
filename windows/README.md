# Café Oabi Windows × BrewPOS Hybrid (v1.0.0 development)

Dedicated **Café Oabi** Windows 10/11 x64 application based on C#/.NET 10/WPF with a light cream/forest-green cashier UI. It uses the **existing BrewPOS** Supabase project \`rfxzbuocxersgbshczbj\` and the **same** BrewPOS Android \`brewpos-sync\` Edge Function for completed cash sales. NOT StorePOS/MotoPOS.

## Hybrid behavior
- Locally cached approved Café Oabi menu, options (size, sugar, ice, extra espresso), dine-in/takeout, table note, cash checkout and barista ticket
- SQLite WAL in \`%LOCALAPPDATA%\Azurate\CafeOabi.BrewPOS\cafe-oabi.sqlite3\`, saved receipts, immutable sale UUIDs and retry queue
- Offline sales require first online BrewPOS member login, authorized device registration, locally cached menu and signed BP1 license; the owner console accepts the 16-hex Windows Device ID
- Customer receipt and kitchen/barista slip print to a directly connected, Windows-installed USB/Bluetooth ESC/POS RAW printer without internet; retrying print does not repeat the sale
- \`brewpos-sync\` via authenticated JWT, one original event UUID, and exact matching cloud ACK. Internet interruption preserves receipts and retries
- Device and catalog are scoped to Café Oabi organization/branch; other tenants cannot be loaded into this café profile

## IMPORTANT — Current deployment blockers, not demo data
1. The live BrewPOS DB currently contains only **Azurate Internal Test Café**, no Café Oabi tenant, and no staff memberships. Onboard Café Oabi through a verified owner-approved workflow; don't auto-assign the internal test organization or fabricate a staff account.
2. \`brewpos-sync\` currently handles only \`sale.completed\`. **Stock adjustments are saved locally and shown as pending, but must not be declared cloud-synced**. Inventory RPC and Cloud inventory reconciliation are separate work, subject to migration/review and end-to-end cross-tablet tests.
3. The live \`brew_ingest_sale\` records sales/items/payments but does not subtract stock from \`brew_products\`. Combined Android + Windows cloud inventory should NOT be called fully consistent until that backend migration exists.
4. BP1 owner signing key needs end-to-end signing-verification testing; an unverified or unsigned code never grants access. The existing owner site accepts a 16-character hexadecimal device ID. Onboarding includes a signed Café Oabi Windows device license.
5. Supabase Auth may require CAPTCHA; this build has email/password login only. Integrate a trusted hosted challenge if the project requires it.
6. Hardware tests needed for receipt width/cut, network restoration, multi-device stock conflicts and signed Windows installer. **Not production-ready.**

## Build
\`dotnet build windows/CafeOabi.BrewPOS/CafeOabi.BrewPOS.csproj -c Release\`
\`dotnet test windows/CafeOabi.Tests/CafeOabi.Tests.csproj -c Release\`

GitHub Actions \`.github/workflows/cafe-oabi-windows-build.yml\` produces a **per-user Inno Setup EXE** plus a portable self-contained win-x64 output. It never wipes the local SQLite database.

Developed by Azurate Software Solutions.
