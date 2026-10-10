# BrewPOS email OTP templates — required Supabase Dashboard configuration

The default "Confirm your email address" email containing a **link** is NOT the six-digit OTP required by BrewPOS. Calling `/auth/v1/otp` does not make Supabase automatically send digits; it uses the content of the Auth email templates. Supabase officially supports `{{ .Token }}` for a six-digit email OTP.

## Save these TWO templates in the BrewPOS Supabase project

Project: `rfxzbuocxersgbshczbj` (BrewPOS only)

Open: https://supabase.com/dashboard/project/rfxzbuocxersgbshczbj/auth/templates

1. Under **Authentication → Email Templates → Confirm signup**, change the subject to **BrewPOS — Your 6-digit verification code**, replace the full HTML body with `supabase/email-templates/confirm-signup-otp.html`, and **Save**. This is critical for NEW customer registrations, which currently receive the old default "Confirm your email address" link.
2. Under **Magic Link / OTP**, change the subject to **BrewPOS — Your sign-in code**, replace the full HTML body with `supabase/email-templates/magic-link-otp.html`, and **Save**. This is needed for EXISTING customer login using OTP.
3. Both templates must contain `{{ .Token }}`. Do **not** use `{{ .ConfirmationURL }}` for an OTP-only workflow. Templates only customize email content; they do not create a license or authorize a customer.
4. In Supabase Auth Email provider settings, ensure email signups and confirmations are enabled. For production volume, configure SMTP delivery, email rate limits and a suitable OTP expiration duration in Auth settings. Do not place SMTP credentials or Supabase service-role secrets in GitHub.
5. **Request a NEW code** from https://markyyy-lolz.github.io/BrewPOS-Android/signup.html after saving the templates. Old link-only emails cannot display the newly updated OTP; do not click the old confirmation URL and expect a code.
6. Enter the six digits in the website or Android. Only then does BrewPOS submit the request as **Pending Approval**; Azurate Owner authorizes the matching business and Android ID with a valid signed BP1 issuance.

**Security checks:** never send a real OTP, password, Supabase secret, private BP1 signing key or customer JWT through chat. A customer signup alone does not unlock sales, change a paid plan, or grant organization membership. All sales tables and StorePOS/MotoPOS data remain separate.

**Status:** these branded templates are committed to GitHub for administrator installation, **not yet saved inside Supabase Auth**, because the available Supabase integration cannot change the managed Auth email-template configuration. Do not advertise OTP email delivery as fixed until these dashboard actions and a real test email succeed.

Reference: https://supabase.com/docs/guides/auth/auth-email-templates
