# BrewPOS Azurate Owner Console — website and activation generator

## Deployment status

- Website source: `website/public/owner.html`, `owner.css`, `owner.js` (no embedded private keys).
- **Live static owner route (Supabase `brewpos-site` v4):** `https://rfxzbuocxersgbshczbj.supabase.co/functions/v1/brewpos-site/owner.html`. The server now serves owner HTML/CSS/JS with no-store headers and restricted CSP. The website is **published**, but sign-in and license issuance are **not yet verified end-to-end**.
- Cloud API: `supabase/functions/brewpos-owner/index.ts` deployed as `brewpos-owner` to dedicated BrewPOS project `rfxzbuocxersgbshczbj`.
- Audit table: `public.brew_activation_issues` (RLS on; anon/authenticated cannot read; service role can INSERT/SELECT but not UPDATE/DELETE). Owner migrations `brewpos_owner_issuance_audit` and `brewpos_owner_issuance_ledger_permissions` are already applied to BrewPOS only.
- Website CI: `npm run check` includes real P-256 ECDSA/DER interoperability tests.
- **IMPORTANT: owner authentication and live activation issuance are disabled until secrets and a confirmed Supabase Auth owner account are provisioned. No default owner account exists.** The API fails closed (HTTP 503/403). Never describe the website as fully activated before owner provisioning.

## Secure owner account setup

1. From **BrewPOS Supabase → Authentication → Users**, invite/create an account using an email you control, verify the email, and use a strong unique password (prefer MFA where available). Do not put passwords in repository files.
2. Get that Auth user's UUID from Supabase Dashboard. Under **Edge Functions → Secrets**, set `BREWPOS_OWNER_USER_ID` to that exact UUID. This is an owner allowlist checked after `/auth/v1/user` validates the JWT. No `user_metadata` or arbitrary role from the browser confers owner rights.
3. Locate the **original ECDSA P-256 owner PKCS#8 private key** from the existing Azurate Owner Activation Kit. Convert its PKCS#8 DER to a single base64 string (NO header/footer) and set the secret `BREWPOS_LICENSE_PRIVATE_KEY_PKCS8_B64` through the Supabase Dashboard. **Never paste this key into ChatGPT, GitHub, website JS or the Android app.**
4. The signer checks the private key against the P-256 SPKI public key bundled with `LicenseManager.kt`. If it doesn't match, activation is refused; don't generate or replace keys casually because it breaks previously activated devices.
5. For an additional Cloudflare custom domain set `BREWPOS_OWNER_ALLOWED_ORIGINS` to a comma-separated allowlist of exact HTTPS origins (e.g. `https://brew.example.com`). GitHub Pages origin `https://markyyy-lolz.github.io` is already allowed.
6. Authenticate from `/owner.html` using your confirmed owner account; then create a **TRIAL (7 days)**, **MONTHLY (30 days)** or **LIFETIME (device-bound)** license from the 16-hex-character Android ID on the BrewPOS activation screen. Check the app accepts the resulting `BP1` code on a TEST device.
7. The API records code UUID, customer, device Android ID, plan, expiry, owner ID, SHA-256 fingerprint and issue timestamp; full code is shown once only. The API is limited to 50 codes per owner in a sliding 24-hour window. The backend does **not** support immediate offline revocation: already delivered local signatures remain valid until their signed expiry, subject to the Android license verifier.

## Owner dashboard

- Overview metrics represent the **most recent 100 synced cloud sales**, not all-time revenue; they exclude un-synced local cash sales.
- Devices list shows registered cloud *installation UUID*, which is **not** the same as Android Secure Device ID used for offline activation.
- License list shows last 100 owner-issued codes' metadata **but never full tokens**.
- Device registration for cloud sync is still a separate flow via the existing `brewpos-sync` function; 0 BrewPOS Auth users/staff memberships/devices were present during initial review.
- This is not a billing or PayMongo subscription-management service. Generating a MONTHLY code does not collect or verify payment.

## Product and testing safeguards

- Never put a service-role credential, raw private key or bearer token in public HTML/JS.
- Verify 403 for unknown authenticated users, 503 when owner setup is absent, 400 for invalid device IDs, and no issuance audit record on any failure.
- Verify a test Android license is accepted by `LicenseManager.evaluate`, including expiry/clock skew and wrong-device checks.
- Test with physical tablets, stable release signing certificate, secure backups and recovery before distributing to paying café clients.
- Do not uninstall/reset Android app data: local offline sales can be lost.
- The public website can be deployed to GitHub Pages or Cloudflare Pages (output directory `website/public`); keep the privileged owner API on Supabase Edge Functions, not Pages client code.

## Important links

- Owner route on GitHub Pages: `https://markyyy-lolz.github.io/BrewPOS-Android/owner.html` (once site assets are published to `gh-pages`).
- Hosted owner website: `https://rfxzbuocxersgbshczbj.supabase.co/functions/v1/brewpos-site/owner.html` (GET; public static assets, server login required).
- Supabase function endpoint: `https://rfxzbuocxersgbshczbj.supabase.co/functions/v1/brewpos-owner` (POST; verified owner JWT required).
- GitHub source: `markyyy-lolz/BrewPOS-Android`.

## Operational security

Store owner credentials and signing key in Supabase Secrets. Rotate access tokens and disable compromised Auth users; change `BREWPOS_OWNER_USER_ID` when transferring ownership. Existing signed offline licenses cannot be revoked by deleting an audit row. Implement license revocation with app online check-in only as a separately designed feature.
