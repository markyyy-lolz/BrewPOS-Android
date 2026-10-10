# BrewPOS customer account onboarding (development, not production enabled)

## Self-registration: Create Account + OTP (October 2026)

- Customers can open **Create account • Email OTP** in Android's activation screen before they buy a license, in Android Cloud, or on the public **GitHub Pages `/signup.html`** screen.
- Enter business name, email and exact 16-hex Android Device ID shown in BrewPOS. A new Supabase Auth identity is created through `/auth/v1/otp` with `create_user=true`. This is only an email identity, **not** a merchant role or activated device.
- Customer enters the emailed numeric code through `/auth/v1/verify` (`type=email`). A server-side `request_account` action verifies the identity and `email_confirmed_at`, then writes a private pending application to `brew_signup_requests`. Public clients cannot read or change this table directly.
- Owner Dashboard → Client Accounts shows pending business/email/device applications. Azurate issues a *real* signed BP1 code to exactly that business and device, then selects the customer's email in the Client Accounts form to approve. The backend confirms the signup user's email through Supabase Auth Admin before creating an organization, branch, membership and license-bound customer account.
- **No OTP bypass, automatic Trial/Lifetime assignment, publicly writable license table, or service-role key in Android/Pages**. Existing offline POS SQLite data is unchanged.
- **Email/OTP prerequisite:** in BrewPOS Supabase Auth, update **both** `Confirm signup` (new accounts) and `Magic Link / OTP` (existing accounts) email templates to include `{{ .Token }}` instead of `{{ .ConfirmationURL }}`. Use the ready-to-paste branded HTML and detailed instructions in `supabase/email-templates/README.md`. Then configure Auth email delivery/rate limits and request a **fresh** email. The default confirmation-link email is **not** a numeric OTP; the linked templates are not installed in the managed Auth project automatically. Customer verification and real-device tests are still required.
- **Deployment order:** apply additive `supabase/drafts/brewpos_signup_email_otp.sql`, deploy the matching `brewpos-sync` and `brewpos-owner` functions, publish GitHub Pages `signup.html`, `signup.js`, and updated owner assets. Then test invalid OTP, duplicate request, unverified JWT, other-business license ID, inactive device, and offline checkout.

## Architecture
- **App:** BrewPOS Android; SQLite is the offline source of truth. Receipt printing is independent of cloud availability.
- **Hosted marketing / owner interface:** GitHub Pages `https://markyyy-lolz.github.io/BrewPOS-Android/`.
- **Backend:** dedicated Supabase project `rfxzbuocxersgbshczbj`, never StorePOS or MotoPOS.
- **Sign-in:** Azurate provisions a separate customer Auth user from an existing signed BP1 license. The customer enters a one-time email code inside Android → Cloud. The mobile API sets `create_user=false` so unaffiliated users cannot self-register through the app.
- **Merchant isolation:** dedicated `brew_organizations`, `brew_branches`, `brew_memberships` per account. A customer cannot edit the plan or memberships from the Android client.
- **License assignment:** `brew_customer_accounts` links a cloud account to a signed license *issuance record*, includes plan and expiration. Changing plan requires a new, unexpired, server-signed BP1 issuance for the same business. The Android device still requires the actual new BP1 activation token. Cloud account status alone cannot modify offline licenses.

## Steps to activate once deployment is approved
1. Create a verified Azurate Auth owner account and set `BREWPOS_OWNER_USER_ID` as a **server-side secret**. Owner login is impossible until this is configured. Do not give the owner login to merchants.
2. Configure **original matching** `BREWPOS_LICENSE_PRIVATE_KEY_PKCS8_B64` in Supabase Edge Function secrets (not website/Android/GitHub). Validate issuance on a physical Android tablet with the app's installed public key.
3. Configure Supabase Auth email templates **Confirm signup and Magic Link / OTP** using the `{{ .Token }}` templates under `supabase/email-templates/`. SMTP and OTP expiry/rate settings must be verified. A default signup confirmation link will not work in BrewPOS's numeric-code input.
4. Review/apply additive `supabase/drafts/brewpos_customer_account_provisioning.sql` to **BrewPOS** only. Confirm RLS and privilege advisors. Do not modify sale history or StorePOS database.
5. Deploy tested `brewpos-owner` Edge Function update and publish the matching Owner Dashboard `owner.html` and `owner.js` to GitHub Pages branch `gh-pages`.
6. In Owner Dashboard issue a real TRIAL/MONTHLY/LIFETIME license first, then use **Client accounts → Create account** using the client's email and signed license record. The server provisions tenant, branch and membership. The client requests an OTP in BrewPOS Android → Cloud and registers the tablet. A newly created account does not automatically activate the POS offline; enter the BP1 code on that tablet.
7. For upgrades or renewals, issue a new signed BP1 license and use **Client accounts → Change license** for that same business. Give the new BP1 code to its device; never silently transfer customer data across tenants.

## Safety and remaining limitations
- **Draft only:** Neither a real owner nor test merchant account is provisioned in the current BrewPOS Supabase project. Do not advertise client account creation or subscription editing as live until end-to-end testing is performed.
- This database records subscription **entitlement metadata**, not payment processing. No PayMongo payment is charged, verified or refunded here.
- SMTP delivery may require a paid/custom provider and has quotas. Verify Supabase Auth email settings before testing on real clients.
- Offline BP1 licenses cannot be remotely revoked with the current verifier. Cancelling a cloud account or changing entitlement does not make old signed offline activations invalid before their natural expiry.
- An Auth user can be created before the account row and membership finish. If a network/database failure happens during provisioning, an Azurate administrator must inspect and repair partial records safely; do not delete client sales.
- A cloud account by itself does not prove that all offline sales have synced. Always verify pending outbox counts and server acknowledgement.
- The UI feature is a draft until Android CI, SQL migration, server deployment and a physical device OTP sign-in and Bluetooth print test succeed.
