# BrewPOS Live Cloud

- Project `rfxzbuocxersgbshczbj`, dedicated BrewPOS database.
- Schema migration: `brewpos_v2_initial_secure_schema` (11 tenant-scoped tables, RLS).
- Ingestion migration: `brewpos_secure_idempotent_sale_ingestion` (atomic sale/line/payment and idempotent ACK).
- Public marketing website: https://rfxzbuocxersgbshczbj.supabase.co/functions/v1/brewpos-site/
- Authenticated API: https://rfxzbuocxersgbshczbj.supabase.co/functions/v1/brewpos-sync

The website currently uses **Supabase Edge Function hosting**, NOT Cloudflare Pages. The owner portal is demo-only.

**Security:** The sync API verifies Supabase Auth tokens via /auth/v1/user, enforces organization membership and registered device, and uses the server-side service_role key. Sale ingestion is SECURITY INVOKER with execute allowed to service_role only; direct anonymous/authenticated writes are denied. No service secrets are in Android or the static website.

**Onboarding prerequisite:** There were 0 registered BrewPOS Auth users at inspection. First create and confirm a staff account, then provision its organization, branch and owner membership under controlled administration before using Cloud → Register device. Do not publicly enable anonymous owner onboarding.

**Not yet done:** production signing, actual printer tests, backup/restore, cloud product pull, simultaneous offline inventory reconciliation, tax/BIR invoicing, production owner dashboard. The database smoke-test sale/duplicate was rolled back; no fake transactions were kept.
