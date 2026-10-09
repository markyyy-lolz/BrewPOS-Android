# BrewPOS Website — Production Source

**BrewPOS by Azurate Software Solutions**  
Static responsive marketing site with optional Cloudflare Pages Functions inquiries. The source is deployable; it is **not currently verified live** at a public address.

## Included

- Fully designed branded home page with responsive desktop/tablet/mobile navigation.
- Real navigation and functional links, Trial/Monthly/Lifetime license inquiry options, detailed FAQ.
- Accessible inquiry form with browser field validation and a server-side Cloudflare Pages Function.
- Transactional emails through Resend when configured (`RESEND_API_KEY`, `BREWPOS_TO_EMAIL`, `BREWPOS_FROM_EMAIL`). A direct email draft fallback is shown if the service is unavailable.
- Honeypot, input limits, origin check, optional Cloudflare Turnstile bot check.
- Privacy Policy, Terms, 404 page, restrictive response headers, optimized vector images, social preview.
- No invented prices, statistics, customer endorsements, or unverified product functionality.
- Node.js automated API tests.

## Build/test

Node 22 recommended. No frontend bundler or production runtime dependencies required.

```sh
npm run check
python -m http.server -d public 8080
```

The local Python webserver previews **static pages only**, so inquiry `/api/inquiry` sends no email. Use Cloudflare Pages to execute the Function.

## Deploy to Cloudflare Pages

1. Push the source files (including the top-level `functions/` and `public/` folders) to a GitHub repository. The project may be placed under `website/` inside BrewPOS Android's existing repository; select **Root directory: `website`** in Pages.
2. Cloudflare Dashboard → Workers & Pages → Create → Pages → Connect Git. Select repository.
3. **Framework preset:** None; **Build command:** leave empty; **Build output directory:** `public` (project root `website` if applicable).
4. Set the following Cloudflare Pages project environment variables:

| Variable | Type | Value |
| --- | --- | --- |
| `RESEND_API_KEY` | Secret | Secret API key from Resend, for server-side email delivery |
| `BREWPOS_FROM_EMAIL` | Secret or text | Sender verified with Resend e.g. `BrewPOS <hello@your-verified-domain.ph>` |
| `BREWPOS_TO_EMAIL` | Secret or text | `rem164791@gmail.com` (confirm it as business mailbox before launch) |
| `TURNSTILE_SECRET` | Secret (optional, recommended) | Cloudflare Turnstile secret key |

5. If Turnstile is enabled, set `TURNSTILE_SITE_KEY` in `public/script.js` to the corresponding public site key. Both keys must match the Cloudflare hostname configuration.
6. Add abuse controls (Cloudflare WAF/rate limiting rules) for `/api/inquiry`, and validate that the email arrives before accepting real leads.
7. Publish. Cloudflare assigns a `*.pages.dev` domain; attach your own domain through Pages → Custom domains.
8. Update the public site URL for search engine metadata after the domain is known.

**NEVER paste `RESEND_API_KEY` or `TURNSTILE_SECRET` into `script.js`, HTML, GitHub, screenshots or public issues.** Cloudflare dashboard's secure secrets are the intended storage location.

## Product status accuracy

The site describes the shipped Android v1.2 **activation preview** as offline-first with manual payment labels and local data. The website uses **illustrative artwork** rather than claiming the shipped Android UI looks identical. Supabase hybrid synchronization, owner dashboard, safe multi-tablet inventory reconciliation, and commercial production invoicing are still in development. No BIR accreditation is claimed.

Do not distribute debug APKs as commercial software. A full release requires production signing, real thermal printer tests, sync tests, backup/restore tests, and relevant legal compliance checks.

## Folder structure

- `public/` — deployed static HTML, styles, JS, SVG, privacy/terms, security headers
- `functions/api/inquiry.js` — Pages Functions POST email endpoint
- `tests/` — automated API endpoint tests
- `wrangler.toml` — Cloudflare Pages project config

© Azurate Software Solutions. All rights reserved.
