# BrewPOS Website — static launch-ready preview

This is the BrewPOS v2.0 **marketing website plus a non-authenticated dashboard demonstration**. The portal sample sales are not real, and no Supabase keys, customer records, or credentials are exposed.

## Preview locally
Use a local static file server from the `website` directory. Example: `python -m http.server 8080` then open http://localhost:8080 .

## Cloudflare Pages deployment (manual)
1. In Cloudflare → Workers & Pages → Create → Pages → Connect to Git.
2. Select the private `BrewPOS-Android` repository and approve access.
3. Use **no framework**, **no build command**, and set **output directory to `website`**.
4. Set production branch to `main`, then deploy.
5. Review all copy, pricing and merchant contact address prior to publishing.

No DNS or public website has been deployed automatically. `portal.html` is marked PREVIEW ONLY, contains **synthetic** data and cannot serve as a secure production admin dashboard.

## Roadmap
Connect production owner portal only **after** Supabase Auth, strict per-tenant RLS, audited sync endpoints, real backups, and proper usage limits are implemented. Never publish Supabase secret/service-role credentials in static website files.
