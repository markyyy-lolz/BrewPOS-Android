# BrewPOS inventory protocol v2

Target: BrewPOS `rfxzbuocxersgbshczbj`. Continue draft PR #10. Do not merge or advertise a production café release before physical QA.

## Implemented contract

- Sale payload v2 carries cloud product UUIDs. Financial rows, event receipt, inventory movement and stock delta commit in one PostgreSQL transaction.
- Identical event retries return the same durable acceptance without additional deductions; changed payloads with the same ID fail. Products lock in UUID order.
- Stock adjustments are additive signed deltas with an event UUID and reason. Only active owners/managers can upload them. No absolute stock overwrites or automatic conflict rebasing.
- Two offline devices can oversell their cached stock. Completed paid sales are retained and cloud stock can become negative; this shortage must be resolved by an audited manager adjustment after counting stock. Neither client may silently clamp it to zero.
- Cloud catalog snapshots are fetched through the authenticated, registered-device API. Local replacement is transactional and blocked until all local events are acknowledged. Both clients periodically refresh after a clean queue.
- Android installations bind permanently to one organization/branch in SQLite. Events carry that scope. Signing out cannot move receipts to another café.
- Android local schema upgrades are additive. Fresh installs no longer fabricate menu items or stock. Existing local menus/history remain until a catalog is deliberately linked and downloaded.
- Live sync v3 verified-email checks and signup request routes were ahead of GitHub; those routes are preserved in this update.

## Compatibility and limitations

Legacy v1 sale submissions still receive financial ACKs, explicitly `inventory_applied=false`. They lack reliable cross-device product identity; do not infer IDs by product name. Updated clients retain such legacy events for owner reconciliation. Existing unbound Android pending sales prevent branch linking. No historical event is rewritten or silently adopted.

Android currently represents stock in whole units and refuses an entire fractional-stock catalog rather than rounding it. Ingredient recipes, stock transfer workflows, refunds/reversal UI, catalog creation/edit API and a legacy reconciliation UI remain separate work. Windows supports decimal stock. Offline role changes cannot be learned until reconnect; the server validates current permissions on every upload.

## Rollout

1. Run `npm ci --prefix supabase/tests` and `node --test supabase/tests/*.test.mjs`.
2. Review/apply `supabase/live/brewpos_ingest_sale.sql` then `supabase/live/brewpos_inventory.sql` to the dedicated BrewPOS project. No table replacement, customer deletion or RLS relaxation is required.
3. Deploy `supabase/functions/brewpos-sync/index.ts` preserving explicit Auth verification and `verify_jwt=false` as on the existing deployment.
4. Build Android and Windows on PR CI. Install only for development acceptance testing.
5. Link an authorized owner/manager, register each device and download the actual cafe catalog. Disconnect both devices, complete sales and adjustments, reconnect in reverse order, retry uploads and compare stock, receipts and ledger records.

## Remaining real-world inputs and checks

Café Oabi branch name, approved owner/staff emails and roles, actual menu/prices/opening stock, and Android/Windows device activation IDs are needed. Do not invent staff, issue placeholder licenses or reuse another store's tenant. Owner signing, email verification, physical printers, app restart during upload, backups/restore and café acceptance require end-to-end verification. Existing automated BP1 tests establish signature format compatibility, not successful live issuance on the café's devices.

Website public plans and owner dashboard exist. Production owner/onboarding functions have additional changes outside this branch; inspect live code before deploying them. This inventory change does not replace the owner API, change plan prices, send invitations or publish a new customer release.
