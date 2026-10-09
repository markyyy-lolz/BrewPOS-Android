# BrewPOS Website — GitHub Pages

BrewPOS website files live in `website/` and are deployed by GitHub Actions through `.github/workflows/deploy-website-pages.yml`. **GitHub Pages is the website host. Supabase is backend only** (authentication, database, authenticated sync API).

## Publish once (repo owner action)

1. Open **https://github.com/markyyy-lolz/BrewPOS-Android/settings/pages**
2. Under **Build and deployment → Source**, choose **GitHub Actions**.
3. Check **Actions** and wait for the **Deploy BrewPOS Website to GitHub Pages** job to pass. You can also run it with **Run workflow** from the Actions tab.
4. The expected default project site is `https://markyyy-lolz.github.io/BrewPOS-Android/` once GitHub marks it published. Do **not** assume it is live before the Pages deployment succeeds.

**Important:** The Android repository is PRIVATE. GitHub Free supports GitHub Pages for **public** repos only. For private repositories, GitHub Pro/Team/Enterprise is required. Do not make this Android app source public merely to host the marketing website. On GitHub Free, create a separate PUBLIC `BrewPOS-Website` repository containing ONLY the website files, and deploy Pages from that repository. No Android source or owner signing material should be copied there.

## About the pages
- `index.html`: BrewPOS marketing landing page (public).
- `dashboard.html` + `dashboard.js`: merchant sign-in + real read-only Supabase sales scoped through RLS, no persisted browser session.
- `portal.html`: clearly labeled sample/demo dashboard.
- `styles.css`, `site.js`: static assets.

Supabase's PUBLIC publishable API key is intentionally embedded in dashboard.js; never add a secret/service-role key. Cloud sales only appear when there are authenticated, confirmed staff accounts with authorized organizations. No public anonymous owner provisioning.

## Updating
Push website changes to `main`; GitHub Actions publishes `website/` as a Pages artifact. The build never uploads the private Android project. Relative asset URLs support the `/BrewPOS-Android/` Pages project subpath. GitHub Pages ignores Cloudflare's `_headers` file; HTML carries a limited CSP meta tag where supported.

## Developer preview
`cd website && python -m http.server 8080`, then open http://localhost:8080.

## Status
A GitHub Actions workflow does not itself guarantee Pages has been enabled or that the account's plan supports private-repo Pages. Check the deployment job and Settings → Pages before sharing the URL with customers.
