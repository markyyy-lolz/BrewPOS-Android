# BrewPOS v2.0 — Hybrid Technical Foundation

**Status: architecture and schema draft only. No cloud sync is running yet.**

## Existing state
- Android app stores transactions in local SQLite.
- BrewPOS Supabase project ref `rfxzbuocxersgbshczbj` was last inspected as ACTIVE_HEALTHY with **zero** public tables.
- This draft is intentionally separate from MotorPOS, StorePOS, and other user databases.

## Migration draft
`supabase/drafts/brewpos_v2_dev_schema.sql` is an isolated **non-applied** SQL draft.
Use a *separate Supabase development project* and a reviewed official migration before applying it.
This SQL contains no broad authenticated writes; it allows strictly tenant-scoped SELECT after Supabase Auth and owner-created memberships, and reserves mutation for a secure backend.

## Offline-first sync contract (future)
1. Android commits a sale and stock deduction in one SQLite transaction.
2. In the same transaction, create one durable outbox event with a globally unique `event_id`, `client_sale_id`, local installation ID, version and immutable sale snapshot.
3. Android WorkManager retries uploads with backoff when network is available.
4. Authenticated Edge Function verifies Supabase session, membership, device registration, branch assignment, subscription status and event schema.
5. Server applies the sale, items, payment, inventory ledger and event acknowledgement atomically.
6. Database `unique (organization_id,client_sale_id)` and event deduplication prevent duplicate sales. Only a confirmed receipt marks the local event synced.
7. Product catalog changes use versioned rows; offline competing stock decrements go to a reconciliation queue, not last-write-wins.
8. Device uses cursor-based pull for authorized updates. Reject cross-tenant requests.

## Important unresolved design choices
- Offline two-device inventory reconciliation: accept oversells with alerts or require hard stock reservations while online.
- Printer routing when the second tablet submits a sale: only one tablet currently owns the Bluetooth printer.
- Stable installation ID and device replacement / license migration rules.
- Conflict-safe barista statuses and retryable/persisted print jobs.
- Backups, restores, refund/void audit, signed production APK and BIR invoicing.

## Security validation before real rollout
- Test RLS by logging in as two different test organizations, verifying users cannot read each other's sales.
- Test anonymous Data API requests are denied.
- Test an authenticated cashier cannot create or modify sales directly.
- Test simultaneous duplicate event submissions and deliberately delayed ACKs.
- Never publish server secret/service-role keys in the Android binary or static site.
