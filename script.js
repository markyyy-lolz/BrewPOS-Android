/** BrewPOS by Azurate. Public client. No secrets here. */
const CONTACT_EMAIL = 'rem164791@gmail.com';
// Configure with your public Cloudflare Turnstile site key when TURNSTILE_SECRET is set on Pages.
const TURNSTILE_SITE_KEY = '';
if (TURNSTILE_SITE_KEY) {
  const container = document.createElement('div');
  container.className = 'turnstile-field';
  document.getElementById('submit-inquiry').before(container);
  const script = document.createElement('script');
  script.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
  script.async = true;
  script.onload = () => window.turnstile?.render(container, {sitekey: TURNSTILE_SITE_KEY});
  document.head.append(script);
}

const menuToggle = document.getElementById('menu-toggle');
const mobileNav = document.getElementById('mobile-nav');
const setMenuOpen = (open) => {
  mobileNav.hidden = !open;
  menuToggle.setAttribute('aria-expanded', String(open));
  menuToggle.setAttribute('aria-label', open ? 'Close navigation' : 'Open navigation');
  menuToggle.querySelector('use').setAttribute('href', open ? '#ic-x' : '#ic-menu');
};
menuToggle.addEventListener('click', () => setMenuOpen(mobileNav.hidden));
mobileNav.querySelectorAll('a').forEach(a => a.addEventListener('click', () => setMenuOpen(false)));
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape' && !mobileNav.hidden) { setMenuOpen(false); menuToggle.focus(); }
});
window.addEventListener('resize', () => { if (window.innerWidth > 980 && !mobileNav.hidden) setMenuOpen(false); });
document.getElementById('footer-year').textContent = new Date().getFullYear();
document.getElementById('footer-email').href = `mailto:${CONTACT_EMAIL}?subject=${encodeURIComponent('BrewPOS inquiry')}`;

const form = document.getElementById('contact-form');
// Plan-specific pricing actions select the corresponding inquiry category.
document.querySelectorAll('[data-inquiry-plan]').forEach(link => {
  link.addEventListener('click', () => {
    const plan = document.getElementById('contact-plan');
    const value = link.getAttribute('data-inquiry-plan');
    if (Array.from(plan.options).some(option => option.value === value || option.textContent === value)) {
      plan.value = value;
      plan.dispatchEvent(new Event('change', { bubbles: true }));
    }
  });
});

const feedback = document.getElementById('form-feedback');
const submitButton = document.getElementById('submit-inquiry');
const fields = {
  name: document.getElementById('contact-name'),
  business: document.getElementById('contact-business'),
  email: document.getElementById('contact-email'),
  plan: document.getElementById('contact-plan'),
  message: document.getElementById('contact-message'),
  website: document.getElementById('contact-hp'),
};
for (const field of Object.values(fields)) {
  field.addEventListener('input', () => { field.removeAttribute('aria-invalid'); feedback.textContent = ''; });
  field.addEventListener('change', () => field.removeAttribute('aria-invalid'));
}
form.addEventListener('submit', async (event) => {
  event.preventDefault();
  let firstError = null;
  for (const field of [fields.name, fields.business, fields.email, fields.plan]) {
    if (!field.value.trim() || !field.checkValidity()) {
      field.setAttribute('aria-invalid', 'true'); firstError ||= field;
    }
  }
  if (firstError) {
    feedback.textContent = 'Complete the required fields and provide a valid email.';
    firstError.focus(); return;
  }
  const data = Object.fromEntries(Object.entries(fields).map(([k, v]) => [k, v.value.trim()]));
  // GitHub Pages is static hosting: it does not run Cloudflare Pages Functions.
  // Open an email draft directly rather than silently dropping the inquiry.
  if (window.location.hostname.endsWith('.github.io')) {
    const body = [
      'Hello Azurate Software Solutions,', '', 'I am interested in BrewPOS.', '',
      `Name: ${data.name}`, `Café: ${data.business}`, `Email: ${data.email}`,
      `Inquiry: ${data.plan}`, '', data.message || '(No further details)',
    ].join('\n');
    feedback.textContent = 'Your email app will open with your inquiry. Please press Send in the email app to submit it.';
    window.location.href = `mailto:${CONTACT_EMAIL}?subject=${encodeURIComponent(`BrewPOS Inquiry — ${data.business}`)}&body=${encodeURIComponent(body)}`;
    return;
  }
  data.turnstileToken = document.querySelector('[name="cf-turnstile-response"]')?.value || '';
  submitButton.disabled = true;
  submitButton.textContent = 'Sending inquiry…';
  feedback.textContent = '';
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 15000);
    let response;
    try {
      response = await fetch('/api/inquiry', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data), signal: controller.signal,
      });
    } finally { clearTimeout(timeout); }
    const payload = await response.json().catch(() => ({}));
    if (!response.ok || !payload.ok) throw new Error(payload.message || 'The inquiry service is unavailable.');
    feedback.textContent = 'Thank you! Your inquiry was sent successfully. Our team will contact you by email.';
    form.reset();
    window.turnstile?.reset?.();
  } catch (error) {
    const body = [
      'Hello Azurate Software Solutions,', '', 'I am interested in BrewPOS.', '',
      `Name: ${data.name}`, `Café: ${data.business}`, `Email: ${data.email}`,
      `Inquiry: ${data.plan}`, '', data.message || '(No further details)',
    ].join('\n');
    const mailto = `mailto:${CONTACT_EMAIL}?subject=${encodeURIComponent(`BrewPOS Inquiry — ${data.business}`)}&body=${encodeURIComponent(body)}`;
    feedback.replaceChildren();
    const message = document.createTextNode('Online submission is unavailable. You can send your inquiry using your email app instead: ');
    const link = document.createElement('a'); link.href = mailto; link.textContent = 'Open email draft';
    link.className = 'email-fallback';
    feedback.append(message, link);
  } finally { submitButton.disabled = false; submitButton.textContent = 'Send inquiry ↗'; }
});

if ('IntersectionObserver' in window && !window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
  const targets = document.querySelectorAll('.feature-card, .step, .price-card, .section-head, .intro-layout, .faq-list details');
  const observer = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (entry.isIntersecting) { entry.target.classList.add('in-view'); observer.unobserve(entry.target); }
    });
  }, { threshold: .09, rootMargin: '0px 0px 40px 0px' });
  targets.forEach(el => { el.classList.add('reveal'); observer.observe(el); });
}
