# BrewPOS v1.2 Preview — Device Activation

**Owner: Azurate Software Solutions**

BrewPOS checks signed, offline-verifiable ECDSA license codes on startup and before each sale. A device ID is displayed on the first-run activation screen. The customer sends that ID to the owner and receives an activation code. In **Settings / license label** the customer can renew an expiring key.

| Plan | Offline behavior |
|---|---|
| Lifetime | Valid indefinitely on that Android device ID |
| Monthly | Valid until the date signed by the owner, plus 72 hours grace |
| Trial | Same expiry/grace rules, for demonstrations |

The customer can sell, print, and use the local SQLite database without internet **while the signed license is valid**. No subscription payment gateway, automatic renewal, or cloud license-server check is implemented. For a monthly renewal, issue a **new signed code** and replace it on the device. Clock rollback checks reduce accidental bypass but **do not provide tamper-proof enforcement**. A modified APK or rooted device can bypass local licensing.

**KEEP YOUR OWNER SIGNING KEY OUT OF THIS REPOSITORY.** The issuing script/private signing key are deliberately not committed. Do not send them to customers. Back up the key privately. Use a stable, production APK signing certificate: Android ID may change if the signing certificate or Android user changes, and a factory reset may require reactivation.

## Release status

This is a **preview** of the offline app + activation. The APK published by GitHub Actions is a **debug build intended for test devices only**. No guarantee of device/printer compatibility or production readiness. Hybrid Supabase sync, production licensing backend, server verified payments, cloud backup, compliance/BIR invoicing, and multi-till stock reconciliation are NOT implemented.

Sales are persisted locally. Never clear app data or uninstall a device that holds real sales without a verified backup and restore procedure. The sales CSV export is not a restorable backup.
