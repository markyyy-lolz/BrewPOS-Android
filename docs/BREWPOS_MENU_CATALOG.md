# Live café menu catalog — Android self-order kiosk

## Café menu onboarding

BrewPOS Android kiosk gets actual products from the tenant's branch, via the authenticated brewpos-kiosk Edge Function and the **brew_catalog** RPC. The kiosk keeps **Start ordering** disabled while no active available products exist; it cannot invent products, prices or orders.

1. Log in as an Azurate owner at https://markyyy-lolz.github.io/BrewPOS-Android/owner.html
2. Open **Menu catalog** and select the client's approved active café / branch.
3. Enter a real item name, category and price in Philippine pesos. Decide whether the café tracks quantities per product.
4. For made-to-order drinks with no per-cup stock tracking, leave **Track individual stock quantity** unchecked. For packaged stock-tracked items, enter the verified opening quantity.
5. Save the product. Repeat for menu items. Products are scoped to the selected organization and branch.
6. On the self-order kiosk tablet tap **Refresh menu**, or leave the welcome screen open for the automatic 20-second refresh.
7. After at least one active available product has loaded, **Start ordering** becomes available.

**Editing:** Owner can update name, price, category, whether stock is tracked and public visibility. Hiding a product sets `active=false` and preserves historic receipts. For tracked products, subsequent quantity changes must use an audited inventory adjustment; this owner editor does not silently overwrite stock.

**Android screen insets:** Removed obsolete full-screen layout flags that caused the header to render under the system status bar. Device should use Android lock-task mode to secure a customer unattended kiosk (separate from layout).

**QR Ph:** Test-only / disabled for live production. Cloud-generated kiosk payments are NOT confirmed until the server verifies them. Pay at Cashier requires the Android cashier queue and an authorized staff user.

**Deployed to live:** Owner menu API on brewpos-owner and page UI on both gh-pages and brewpos-site. The live database has not been seeded or wiped; real client products must be entered by authorized owners.
