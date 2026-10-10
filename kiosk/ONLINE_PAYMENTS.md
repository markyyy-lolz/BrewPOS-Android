# Secure Online Payments — integration gate (DISABLED)

The kiosk **must not** show GCash/Maya/Card as usable until a verified provider is configured. The current shared UI intentionally offers **Pay at Counter only**. Do NOT collect card details directly in a kiosk WebView.

Recommended server-only integration with a merchant-activated provider such as PayMongo:
1. From the existing authenticated, tenant-verified hub, create a checkout session/payment link using the **server-side private API key**, never device code or public JS. Server must use its stored authoritative order amount and currency PHP; do not trust kiosk request totals.
2. Store a unique order UUID, provider reference, amount, currency, tenant/branch/merchant ID, status **pending** and expiry in a private durable ledger before presenting a hosted payment page or a QR code. Require user consent and clearly identify whether the device is online.
3. Provider webhook endpoint must authenticate each callback using the provider's documented signature/secret, replay window and event ID; if applicable re-query the provider's trusted API. Reject amount, currency, customer/merchant account or reference mismatches.
4. Atomically apply **Paid** once only after trusted confirmation; the kitchen must never accept a browser redirect, screenshot, kiosk UI action or offline claim as proof of payment.
5. Refunds, duplicate charge prevention, order cancellation, retry, subscription/merchant-account setup, and provider transaction reconciliation must be tested end-to-end in sandbox. Production requires separate owner authorization and verified merchant setup.
6. Keep the Pay at Counter fallback functional even when internet/merchant provider is offline.

Provider docs (for developer reference): https://developers.paymongo.com/

No secret keys are currently stored in this project or shared with client software. No payment capture is claimed or performed by this development build.
