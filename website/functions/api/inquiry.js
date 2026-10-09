/* Cloudflare Pages Function: server-side BrewPOS inquiries. No API keys are shipped to browsers. */
const LIMIT_BYTES = 12_000;
const CATEGORIES = new Set([
  'Free trial inquiry', 'Monthly plan', 'Lifetime license',
  'Hybrid Cloud availability', 'Hardware / printer compatibility', 'Other inquiry',
]);
const trimField = (data, field, max = 250) => {
  if (typeof data[field] !== 'string') throw new Error(`Invalid ${field}`);
  const value = data[field].trim().replace(/[\u0000-\u001F\u007F]/g, ' ');
  if (value.length === 0 || value.length > max) throw new Error(`Invalid ${field}`);
  return value;
};
const json = (body, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
});

export async function onRequestPost({ request, env }) {
  const origin = request.headers.get('Origin');
  const sameOrigin = new URL(request.url).origin;
  if (origin && origin !== sameOrigin) return json({ ok: false, message: 'Unrecognized request origin.' }, 403);
  if (!request.headers.get('Content-Type')?.toLowerCase().startsWith('application/json'))
    return json({ ok: false, message: 'JSON required.' }, 415);
  if (Number(request.headers.get('Content-Length') || 0) > LIMIT_BYTES)
    return json({ ok: false, message: 'Message too large.' }, 413);
  let raw = '';
  try { raw = await request.text(); } catch { return json({ok: false, message: 'Invalid request.'},400); }
  if (new TextEncoder().encode(raw).length > LIMIT_BYTES)
    return json({ ok: false, message: 'Message too large.' }, 413);
  let input;
  try { input = JSON.parse(raw); } catch { return json({ ok: false, message: 'Invalid JSON.' }, 400); }
  if (!input || Array.isArray(input) || typeof input !== 'object')
    return json({ ok: false, message: 'Invalid request.' }, 400);
  if (input.website) return json({ ok: true }); // honeypot: silently ignore bot submissions

  let customer;
  try {
    customer = {
      name: trimField(input, 'name', 120),
      business: trimField(input, 'business', 160),
      email: trimField(input, 'email', 200),
      plan: trimField(input, 'plan', 90),
      message: typeof input.message === 'string' ? input.message.trim().slice(0, 4000).replace(/[\u0000-\u001F\u007F]/g,' ') : '',
    };
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(customer.email)) throw new Error('Invalid email');
    if (!CATEGORIES.has(customer.plan)) throw new Error('Invalid category');
  } catch {
    return json({ ok: false, message: 'Check your name, email and inquiry fields.' }, 400);
  }

  if (!env?.RESEND_API_KEY || !env?.BREWPOS_TO_EMAIL || !env?.BREWPOS_FROM_EMAIL)
    return json({ ok: false, message: 'Online inquiries are not configured. Please contact us by email.' }, 503);

  // Turnstile can be enabled as an additional spam defense in production.
  if (env.TURNSTILE_SECRET) {
    const token = typeof input.turnstileToken === 'string' ? input.turnstileToken : '';
    if (!token) return json({ ok: false, message: 'Complete the security verification and retry.' }, 400);
    try {
      const verification = await fetch('https://challenges.cloudflare.com/turnstile/v0/siteverify', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ secret: env.TURNSTILE_SECRET, response: token, remoteip: request.headers.get('CF-Connecting-IP') || undefined }),
      });
      const result = await verification.json();
      if (!result.success) return json({ ok: false, message: 'Security verification failed. Please retry.' }, 403);
    } catch { return json({ ok: false, message: 'Verification unavailable. Please retry.' }, 502); }
  }

  const plainText = [
    'New BrewPOS inquiry', '', `Name: ${customer.name}`, `Business: ${customer.business}`,
    `Email: ${customer.email}`, `Interest: ${customer.plan}`, '',
    'Message:', customer.message || '(No additional message)',
  ].join('\n');
  try {
    const response = await fetch('https://api.resend.com/emails', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${env.RESEND_API_KEY}` },
      body: JSON.stringify({
        from: env.BREWPOS_FROM_EMAIL,
        to: [env.BREWPOS_TO_EMAIL],
        reply_to: customer.email,
        subject: `BrewPOS Inquiry — ${customer.business.slice(0, 80)}`,
        text: plainText,
      }),
    });
    if (!response.ok) return json({ ok: false, message: 'Email delivery failed. Please contact us directly.' }, 502);
    return json({ ok: true, message: 'Inquiry delivered.' });
  } catch {
    return json({ ok: false, message: 'Email delivery unavailable. Please contact us directly.' }, 502);
  }
}

export function onRequestGet() {
  return json({ ok: false, message: 'Use the inquiry form to contact us.' }, 405);
}
