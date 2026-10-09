/** Public health endpoint used to confirm that Cloudflare Pages Functions deployed. */
export function onRequestGet() {
  return new Response(JSON.stringify({ ok: true, service: 'BrewPOS public website', version: '1.0' }), {
    status: 200,
    headers: { 'Content-Type':'application/json; charset=utf-8', 'Cache-Control':'no-store' },
  });
}
