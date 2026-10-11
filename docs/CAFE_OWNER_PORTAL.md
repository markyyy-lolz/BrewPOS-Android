# BrewPOS Café Owner Portal — First Release

**Live path:** https://markyyy-lolz.github.io/BrewPOS-Android/cafe.html
**Supabase fallback:** https://rfxzbuocxersgbshczbj.supabase.co/functions/v1/brewpos-site/cafe.html
**API:** `brewpos-cafe` (separate from Azurate's `brewpos-owner`)

## Access
An existing approved café owner or manager signs in with a Supabase email OTP. The API validates the bearer token via /auth/v1/user, checks email verification, active brew_memberships role owner/manager, active non-expired brew_customer_accounts subscription, and branch belonging to that organization. Only tenant-scoped records are returned. Client-supplied org/branch IDs never establish authorization on their own. JWT is kept in memory only; page refresh requires signing in again. No owner API, license generator, or private signing key exposed.

## Features
- Responsive multi-branch dashboard with last 250 synced sales and last 7 days chart, menu-item count and kiosk-order status.
- Create/edit/temporarily hide café menu products. New item can include opening stock. Prices are specified in pesos in the UI and sent as integer centavos.
- Stock quantity and last 75 audited inventory movements visible. Subsequent stock corrections must use the established Android POS audited manager flow; website deliberately does not allow untracked stock overwrites.
- Last 150 kiosk orders with payment status (read only), recent sales and CSV export, registered devices (no tokens), subscription/expiry.
- Product queries and mutations are bound to an approved active organization/branch. Optimistic version matching prevents blind concurrent edits.

## Important restrictions
The portal displays only **cloud-synced** sales; it is not a complete local/offline receipt backup. No live QR Ph payment settlement, remote user grants, license generation, global owner access, or kiosk payment confirmation actions are provided. New café registrations still require Azurate approval. No demo products or historical sales were created, deleted or reset.

**Deployment:** GitHub Pages `gh-pages/cafe.html` and static files, mirrored into the working BrewPOS branch and Supabase `brewpos-site` for hosting alternatives. Account and menu use the existing BrewPOS project ID `rfxzbuocxersgbshczbj`, never StorePOS.
