# BrewPOS: draft commercial packages (not product entitlements)

**Azurate Software Solutions — internal pricing proposal, 2026.** Prices are suggestions, not a price guarantee or representation of features already built.

| Proposed package | Monthly software-only | One-time lifetime software-only | Current implementation |
| --- | ---: | ---: | --- |
| Offline Basic | ₱399/month | ₱4,999 | Core v1.1 is a prototype; needs hardware/production validation |
| Offline Premium | ₱699/month | ₱8,999 | Not yet implemented (staff accounts, local restore, advanced inventory) |
| Cloud Standard | ₱699/month + ₱7,999 setup | Not offered | Not yet implemented (Supabase cloud sync, dashboard, cloud backups) |
| Cloud Premium | ₱1,499/month + ₱14,999 setup | Not offered | Not yet implemented (multi-device/branch, advanced roles, analytics) |

**Software only:** hardware, printers, POS terminals, setup beyond contract scope, major upgrades, and BIR registration/compliance are separate. A *lifetime license* means indefinite use of the purchased supported version, not free future major versions or unlimited support. Device-bound signed activation is implemented in the offline Android preview; there is no automatic subscription billing, hosted activation service, or payment confirmation. The licensed device requires owner-issued signed renewal codes.

## Proposed development milestones

1. Validate v1.1 on a physical Android device + one Bluetooth Classic ESC/POS thermal printer (both slips) and add crash reporting.
2. Implement safe, restorable offline database backups, staff PIN and void/refund approval before retail deployment.
3. Implement license activation/renewal policy with offline grace period, device replacement rules and secure server-side validation.
4. Create multi-device conflict-safe cloud synchronization: durable offline outbox, stable transaction IDs, idempotency, inventory reconciliation, queue semantics.
5. Add business dashboard, reporting, role permissions, and cloud backup/restore.
6. Complete merchant invoices, legal/tax review, and any Philippine BIR requirements before representing receipts as official invoices.

Existing StorePOS and MotoPOS databases are intentionally outside of this standalone project.
