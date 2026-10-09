import { signBP1 } from "./license-signing.mjs";
// BrewPOS Azurate owner-only API: no secret ever reaches website JavaScript.
// SUPABASE_SERVICE_ROLE_KEY is provided by Supabase runtime; owner signing key
// must be securely configured as BREWPOS_LICENSE_PRIVATE_KEY_PKCS8_B64.
// Explicit authenticated owner allowlist is required; fail closed otherwise.
const base = Deno.env.get("SUPABASE_URL") || "";
const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
const ownerId = Deno.env.get("BREWPOS_OWNER_USER_ID") || "";
const signingKeyB64 = Deno.env.get("BREWPOS_LICENSE_PRIVATE_KEY_PKCS8_B64") || "";
// Must exactly match PUBLIC_KEY_DER_B64 in Android LicenseManager.kt.
const expectedSpki = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEubpoEi/rbJAmA6XF4jgNecQqNq0AD3hfbKaEIpk2XuCW6l2UkboGuqUq25E470PETTrX/N8/5NyPKskSjmEgMw==";
const allowed = new Set([
  "https://markyyy-lolz.github.io",
  "https://rfxzbuocxersgbshczbj.supabase.co",
  ...(Deno.env.get("BREWPOS_OWNER_ALLOWED_ORIGINS") || "")
    .split(",").map(x => x.trim()).filter(Boolean)
]);
function cors(req: Request): Headers {
  const h = new Headers({
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store, max-age=0",
    "X-Content-Type-Options": "nosniff",
    "Vary": "Origin",
    "Access-Control-Allow-Headers": "authorization, apikey, content-type",
    "Access-Control-Allow-Methods": "POST, OPTIONS"
  });
  const origin = req.headers.get("Origin") || "";
  if (allowed.has(origin)) h.set("Access-Control-Allow-Origin", origin);
  return h;
}
const answer = (req: Request, status: number, payload: unknown) =>
  new Response(JSON.stringify(payload), { status, headers: cors(req) });
const uuid = (x: unknown): x is string =>
  typeof x === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(x);
function hex(bytes: Uint8Array): string {
  return Array.from(bytes, b => b.toString(16).padStart(2, "0")).join("");
}
async function api(path: string, init: RequestInit = {}) {
  const headers = new Headers(init.headers);
  headers.set("apikey", serviceKey);
  headers.set("authorization", "Bearer " + serviceKey);
  if (init.body !== undefined) headers.set("content-type", "application/json");
  return fetch(base + "/rest/v1/" + path, { ...init, headers, cache: "no-store" });
}
async function rows(path: string): Promise<Record<string, unknown>[]> {
  const r = await api(path);
  if (!r.ok) throw Error("Cloud records unavailable");
  const data = await r.json();
  if (!Array.isArray(data)) throw Error("Unexpected database response");
  return data;
}
async function checkedOwner(req: Request): Promise<string | null> {
  if (!uuid(ownerId)) return null;
  const token = req.headers.get("Authorization")?.match(/^Bearer ([A-Za-z0-9._-]+)$/)?.[1];
  if (!token) return null;
  const r = await fetch(base + "/auth/v1/user", {
    headers: { "apikey": serviceKey, "authorization": "Bearer " + token },
    cache: "no-store"
  });
  if (!r.ok) return null;
  const user = await r.json();
  // Do not use editable user_metadata to grant privileges.
  return user.id === ownerId && user.email_confirmed_at ? ownerId : null;
}
function integerCentavos(v: unknown): number {
  const n = Number(v);
  return Number.isSafeInteger(n) ? n : 0;
}
function normalizeCustomer(value: unknown): string {
  const s = String(value ?? "").trim();
  if (s.length < 2 || s.length > 80 || /[|\u0000-\u001F\u007F]/.test(s)) {
    throw Error("Customer name must be 2–80 characters without pipes or control characters.");
  }
  return s;
}
async function issueLicense(input: Record<string, unknown>, userId: string) {
  const device = String(input.android_device_id || "").trim().toLowerCase();
  if (!/^[0-9a-f]{16}$/.test(device)) throw Error("Android Device ID must contain 16 hexadecimal characters");
  const plan = String(input.plan || "").toUpperCase();
  if (!["TRIAL","MONTHLY","LIFETIME"].includes(plan)) throw Error("Invalid plan");
  const customer = normalizeCustomer(input.customer);
  const days = plan === "LIFETIME" ? 0 : plan === "TRIAL" ? 7 : 30;
  const now = Math.floor(Date.now()/1000);
  const expires = days ? now + days*86400 : 0;
  const id = crypto.randomUUID();
  // The ECDSA signature must be valid in Android's SHA256withECDSA DER verifier.
  const { code } = await signBP1(signingKeyB64, expectedSpki,
    ["1",device,plan,String(expires),id,customer]);
  const sha = hex(new Uint8Array(await crypto.subtle.digest("SHA-256",new TextEncoder().encode(code))));
  const payload = {
    id,
    android_device_id:device,customer_name:customer,plan,
    expires_at:expires?new Date(expires*1000).toISOString():null,
    issued_by:userId,token_sha256:sha
  };
  const response = await api("brew_activation_issues?select=id", {
    method:"POST",headers:{"prefer":"return=representation"},body:JSON.stringify(payload)
  });
  if (!response.ok) throw Error("Could not securely record issued license. No code was released.");
  return {
    code, id, customer, plan, android_device_id:device,
    expires_at:payload.expires_at, notice:"Save this code securely; it is shown once only."
  };
}
async function route(action: string, input: Record<string,unknown>, userId: string) {
  if (action === "overview") {
    const [sales,orgs,devices,licenses] = await Promise.all([
      rows("brew_sales?select=id,total_centavos,received_at&order=received_at.desc&limit=100"),
      rows("brew_organizations?select=id,name,slug&limit=100"),
      rows("brew_devices?select=id,is_active,last_seen_at&limit=200"),
      rows("brew_activation_issues?select=id,plan,expires_at,issued_at&order=issued_at.desc&limit=200")
    ]);
    return {
      organization_count:orgs.length, organizations:orgs,
      recent_sales_count:sales.length,
      recent_sales_centavos:sales.reduce((n,s)=>n+integerCentavos(s.total_centavos),0),
      registered_devices:devices.length,
      active_devices:devices.filter(d=>d.is_active===true).length,
      issued_licenses:licenses.length,
      pending_cloud_sync_note:"Offline tablet sales are excluded until synchronized."
    };
  }
  if (action === "sales") {
    return { sales: await rows("brew_sales?select=id,organization_id,local_receipt_no,total_centavos,payment_method,status,received_at&order=received_at.desc&limit=100") };
  }
  if (action === "devices") {
    return { devices: await rows("brew_devices?select=id,organization_id,branch_id,display_name,installation_id,is_active,last_seen_at,created_at&order=created_at.desc&limit=100") };
  }
  if (action === "licenses") {
    // Hash only, never expose original secret activation tokens.
    return { licenses: await rows("brew_activation_issues?select=id,customer_name,android_device_id,plan,expires_at,issued_at&order=issued_at.desc&limit=100") };
  }
  if (action === "issue") {
    const today = await rows("brew_activation_issues?select=id&issued_by=eq."+
       userId+"&issued_at=gte."+encodeURIComponent(new Date(Date.now()-86400000).toISOString())+"&limit=51");
    if (today.length >= 50) throw Error("Daily owner issuance limit reached (50)");
    return await issueLicense(input,userId);
  }
  throw Error("Unknown owner action");
}
Deno.serve(async req => {
  const origin = req.headers.get("Origin");
  if (origin && !allowed.has(origin)) return answer(req,403,{error:"Origin not allowed"});
  if (req.method === "OPTIONS") return new Response(null,{status:204,headers:cors(req)});
  if (req.method !== "POST") return answer(req,405,{error:"POST required"});
  if (!base || !serviceKey || !uuid(ownerId)) return answer(req,503,{error:"Owner console is not configured"});
  const userId = await checkedOwner(req).catch(()=>null);
  if (!userId) return answer(req,403,{error:"Azurate owner access required"});
  const raw = await req.text();
  if (raw.length > 4096) return answer(req,413,{error:"Request too large"});
  let input: Record<string,unknown>;
  try {input=JSON.parse(raw)} catch{return answer(req,400,{error:"Invalid request JSON"})}
  if (!input || Array.isArray(input) || typeof input!=="object") return answer(req,400,{error:"Invalid request"});
  try {
    const result = await route(String(input.action||""),input,userId);
    return answer(req,200,result);
  } catch (error) {
    const msg = error instanceof Error ? error.message : "Service unavailable";
    const misconfigured = msg.includes("signing key") || msg.includes("shipped Android") || msg.includes("securely record");
    return answer(req,misconfigured?503:400,{error:msg});
  }
});
