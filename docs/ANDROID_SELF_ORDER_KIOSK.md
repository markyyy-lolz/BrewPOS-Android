# BrewPOS Android POS + Android Self-Ordering Kiosk

Development branch: `feature/android-kiosk-qrph-multiprinter`.
Builds on the still-DRAFT hybrid inventory PR #10. No Windows kiosk is part of this feature.

## Two Android applications

- `:app`: staff-only BrewPOS cashier (retains existing BP1 license and offline SQLite sale queue), can assign paired Bluetooth printer addresses for **receipt**, **barista**, and optional **kitchen** tickets. Manual/automatic local paid checkout prints route independently. If barista has no separate printer, the existing single-printer combined ticket workflow remains.
- `:kiosk`: separate Android package `com.azurate.brewpos.kiosk`; customer menu, large-format customization, cart, dine-in/takeout, Pay at Cashier and QR Ph. The APK does not carry staff JWTs, owner passwords or PayMongo credentials. Managers sign in during initial pairing; kiosk-only random bearer token is stored encrypted with Android Keystore.

## Order processing contract

1. The kiosk verifies registration and downloads branch-scoped product catalog from the server.
2. The customer chooses service, customization, cart, and payment.
3. **Pay at Cashier** creates a unique, unpaid order number. Android cashier displays it in the **Kiosk** tab, collects cash and confirms the sale with manager/cashier JWT. One PostgreSQL transaction records sale, line items, inventory movement and the manual payment. Retries return duplicate acceptance; duplicate payments do not deduct stock a second time.
4. **QR Ph** is disabled by default. In an authorized test tenant only, the Edge API can create a PayMongo *dynamic* QR Ph Payment Intent using **server-only test credentials**. The kiosk displays its exact-amount QR image and polls status. The server reads the PayMongo Payment Intent and verifies `succeeded`, currency, amount and non-live mode before finalizing an order. The customer app cannot mark an order paid.
5. Only *paid* orders can print customer/barista/kitchen slips through the Android cashier. An order can remain pending until verified. Do not print `PAID` receipts on QR generation, PayMongo redirection or pending cash order creation.

### Backend (not deployed)

**Additive SQL draft:** `supabase/drafts/brewpos_android_kiosk_ordering.sql` creates `brew_kiosk_devices`, `brew_kiosk_orders`, protected RPCs, and extends payment method constraints for QRPH. The RLS-protected tables and RPCs should remain reachable only from service-role server paths.

**New API source:** `supabase/functions/brewpos-kiosk/index.ts`, separate from existing `brewpos-sync`, `brewpos-owner` and `brewpos-site`. Never deploy or overwrite the latter Edge Functions as a shortcut; live owner/site versions are ahead of the repo branch.

**Required environment:** existing Supabase Edge server settings for `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY`. QR Ph stays disabled unless **all three** separate sandbox secrets are present: `BREWPOS_QRPH_TEST_SECRET_KEY`, `BREWPOS_QRPH_TEST_PUBLIC_KEY`, and `BREWPOS_QRPH_TEST_ORGANIZATION_ID`. No live PayMongo key is accepted by this v0.1 development flow.

**Security and production gates:** merchant-scoped PayMongo routing, production webhook signature verification with replay protection, background reconciliation when a kiosk closes, cashier-device activation checks, remote device revocation, refunds, payment expiry/retry handling, printer receipt numbering/BIR compliance assessment, physical Android lock-task deployment, and LAN/offline hub order routing all require end-to-end acceptance before launch.

Cashier POS retains full offline SQLite checkout when cloud is unavailable. **Kiosk orders require cloud connectivity in this development build.** A kiosk must not claim it sent an order to the kitchen or cashier while offline. Offline LAN ordering is a planned separate protocol with a trusted local hub and guaranteed reconciliation.

## Before rollout

- Obtain actual Café Oabi owner/staff accounts and menu; the current BrewPOS cloud has no Café Oabi registered devices/products.
- Confirm real Android kiosk hardware and receipt, barista, kitchen printers using paired Bluetooth ESC/POS.
- Run automated tests and both APK builds. Test cashier order duplicate submissions, payment retries, print failures, branch isolation, stock deltas, QR Ph sandbox success/expiry.
- Backup and review production schema before applying the draft SQL. Do not deploy the API or connect live PayMongo keys until security audit and test payments.
- Use managed Android **device-owner lock task** for customer kiosks; immersive UI alone does not securely prevent customers from leaving the kiosk app.
