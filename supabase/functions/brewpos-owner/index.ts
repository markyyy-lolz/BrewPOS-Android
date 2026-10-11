import { signBP1 } from "./license-signing.mjs";
// BrewPOS Azurate owner-only API: no secret ever reaches website JavaScript.
// SUPABASE_SERVICE_ROLE_KEY is provided by Supabase runtime; owner signing key
// must be securely configured as BREWPOS_LICENSE_PRIVATE_KEY_PKCS8_B64.
// Explicit authenticated owner allowlist is required; fail closed otherwise.
const base = Deno.env.get("SUPABASE_URL") || "";
const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
const ownerId = Deno.env.get("BREWPOS_OWNER_USER_ID") || "";
let signingKeyB64 = Deno.env.get("BREWPOS_LICENSE_PRIVATE_KEY_PKCS8_B64") || "";
// Must exactly match PUBLIC_KEY_DER_B64 in Android LicenseManager.kt.
const expectedSpki = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAES/6XwBwEcfBCyJ9riMKmCMxY7BohpCKbL8cbMWcHPr4jfUWJXIQmDinKCSKpoJ60ty3rNb9QyIU6XGdjb/NLWw==";
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
  const token = req.headers.get("Authorization")?.match(/^Bearer ([A-Za-z0-9._-]+)$/)?.[1];
  if (!token) return null;
  // Verify token with Supabase Auth; never trust email, role or UID supplied in JSON.
  const r = await fetch(base + "/auth/v1/user", {
    headers: { "apikey": serviceKey, "authorization": "Bearer " + token },
    cache: "no-store"
  });
  if (!r.ok) return null;
  const user = await r.json();
  if (!uuid(user?.id) || !user?.email_confirmed_at) return null;
  // Backward-compatible legacy explicit owner UUID; never elevate based on email text.
  if (uuid(ownerId) && user.id === ownerId) return user.id;
  // Existing Auth UUID authorization; never automatically re-enable a disabled owner.
  const matches = await rows("brew_owner_accounts?select=user_id,is_active&user_id=eq."+
    user.id+"&limit=1");
  if (matches.length) return matches.length === 1 && matches[0].is_active === true ? user.id : null;

  // First-time invited owner: atomically redeem ONLY an existing private invitation.
  // The SQL function independently cross-checks auth.users.id, verified email
  // and expiry. No email/role submitted by a public client is trusted.
  const email = typeof user.email === "string" ? user.email.trim().toLowerCase() : "";
  if (!email || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return null;
  const claim = await api("rpc/brew_redeem_owner_invitation",{
    method:"POST",
    body:JSON.stringify({verified_user_id:user.id,verified_email:email})
  });
  if (!claim.ok) return null;
  const accepted = await claim.json();
  return accepted === true ? user.id : null;
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
  // Vault-backed signing key, available only after authenticated owner authorization.
  // Service-role-only RPC never exposes a private key to the client or public website.
  if (!signingKeyB64) {
    const response = await api("rpc/brew_owner_signing_key_v1", {method:"POST",body:"{}"});
    if (!response.ok) throw Error("Owner signing key is not configured");
    const stored = await response.json();
    if (typeof stored !== "string" || stored.length < 100) throw Error("Owner signing key is not configured");
    signingKeyB64 = stored;
  }
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
// Account creation is an Azurate-owner-only operation. A customer gets a role ONLY
// after being invited to Auth and associated with a real, server-recorded BP1 issue.
const validEmail = (s: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(s) && s.length <= 254;
async function insertOne(table: string, data: Record<string,unknown>) {
  const r=await api(table+"?select=*", {
    method:"POST", headers:{"prefer":"return=representation"}, body:JSON.stringify(data)
  });
  if(!r.ok)throw Error("Could not save "+table+"; no access was granted");
  const list=await r.json();
  if(!Array.isArray(list)||list.length!==1)throw Error("Unexpected response while creating "+table);
  return list[0] as Record<string,unknown>;
}
async function getIssue(value: unknown) {
  if(!uuid(value))throw Error("Choose a valid signed license issuance");
  const issues=await rows("brew_activation_issues?select=id,customer_name,android_device_id,plan,expires_at&id=eq."+value);
  if(issues.length!==1)throw Error("This activation has not been issued by Azurate");
  const issue=issues[0];
  if(issue.expires_at && new Date(String(issue.expires_at)).getTime()<=Date.now())
    throw Error("Expired licenses cannot be assigned to an account");
  return issue;
}
async function provisionCustomer(input: Record<string,unknown>, userId: string) {
  const email=String(input.email||"").trim().toLowerCase();
  if(!validEmail(email))throw Error("Enter a valid customer email address");
  const issue=await getIssue(input.license_issue_id);
  if((await rows("brew_customer_accounts?select=id&license_issue_id=eq."+issue.id)).length)
    throw Error("This activation is already assigned to an account");
  if((await rows("brew_customer_accounts?select=id&email=eq."+encodeURIComponent(email))).length)
    throw Error("Email already has a BrewPOS customer account; use Change License");
  // A customer-created Auth account is strictly pending: it cannot choose
  // plan, organization or role. Approval binds it to this real, issued BP1.
  const requests=await rows("brew_signup_requests?select=user_id,email,business_name,android_device_id,status&email=eq."+
      encodeURIComponent(email));
  if(requests.length>1)throw Error("Conflicting signup requests");
  const signup=requests[0];
  if(signup){
    if(signup.status!=="pending")throw Error("Customer signup is not pending");
    if(String(signup.business_name).trim().toLowerCase()!==
       String(issue.customer_name).trim().toLowerCase())
      throw Error("Signed license customer name differs from registered business");
    // A cloud-only signup has no device ID. For optional later offline
    // licensing, validate the issued device only if registration supplied one.
    if(signup.android_device_id && String(signup.android_device_id)!==String(issue.android_device_id))
      throw Error("Signed license is for another Android device");
    if(!uuid(signup.user_id))throw Error("Invalid customer account identity");
  }
  // Dedicated merchant tenant, NEVER use the shared demo organization.
  // Stable slug lets an administrator safely find partial onboarding work.
  const slug="brew-license-"+String(issue.id);
  let organizations=await rows("brew_organizations?select=id,name&slug=eq."+slug);
  if(!organizations.length)organizations=[await insertOne("brew_organizations",{
    name:String(issue.customer_name),slug
  })];
  const orgId=String(organizations[0].id);
  let branches=await rows("brew_branches?select=id&organization_id=eq."+orgId+"&name=eq.Main%20Branch");
  if(!branches.length)branches=[await insertOne("brew_branches",{
    organization_id:orgId,name:"Main Branch",timezone:"Asia/Manila"
  })];
  const branchId=String(branches[0].id);
  let invitedUser: string;
  if(signup){
    // Confirm existing customer-created identity at Auth Admin, never accept
    // an email or user UUID merely asserted by the public Android device.
    const admin=await fetch(base+"/auth/v1/admin/users/"+String(signup.user_id),{
      headers:{"apikey":serviceKey,"authorization":"Bearer "+serviceKey},
      cache:"no-store"
    });
    if(!admin.ok)throw Error("Could not verify registered Auth identity");
    const verified=await admin.json();
    const authUser=verified?.user ?? verified;
    if(authUser?.id!==signup.user_id || !authUser.email_confirmed_at ||
       String(authUser.email||"").trim().toLowerCase()!==email)
      throw Error("Customer must verify signup email before approval");
    invitedUser=String(signup.user_id);
  } else {
    // Existing owner-directed onboarding: create a passwordless user who
    // must prove email ownership with an OTP before registering a tablet.
    const createUser=await fetch(base+"/auth/v1/admin/users",{
      method:"POST",headers:{"apikey":serviceKey,"authorization":"Bearer "+serviceKey,
        "content-type":"application/json"},
      body:JSON.stringify({email,email_confirm:false})
    });
    if(!createUser.ok)throw Error("Could not create Auth user. If customer registered, approve their pending signup.");
    const invited=await createUser.json();
    if(!uuid(invited?.id))throw Error("Auth creation did not provide a valid user ID");
    invitedUser=String(invited.id);
  }
  // If a retry happens after partial onboarding, avoid duplicate memberships.
  const existing=await rows("brew_memberships?select=id&user_id=eq."+invitedUser+"&organization_id=eq."+orgId);
  if(!existing.length)await insertOne("brew_memberships",{
    user_id:invitedUser,organization_id:orgId,role:"owner",is_active:true
  });
  const account=await insertOne("brew_customer_accounts",{
    email,user_id:invitedUser,organization_id:orgId,branch_id:branchId,
    license_issue_id:issue.id,plan:issue.plan,expires_at:issue.expires_at,
    status:"invited",created_by:userId
  });
  if(signup){
    const review=await api("brew_signup_requests?user_id=eq."+invitedUser,{
      method:"PATCH",headers:{"prefer":"return=minimal"},
      body:JSON.stringify({status:"approved",reviewed_at:new Date().toISOString(),
        updated_at:new Date().toISOString()})
    });
    if(!review.ok)throw Error("Account created but signup approval needs administrator review");
  }
  return {created:true,id:account.id,email,plan:issue.plan,
    organization_id:orgId,branch_id:branchId,
    notice:"Account created. Customer signs in from BrewPOS Android using an email OTP, then registers the tablet."};
}
async function changeCustomerLicense(input: Record<string,unknown>) {
  if(!uuid(input.account_id))throw Error("Choose a customer account");
  const issue=await getIssue(input.license_issue_id);
  const current=await rows("brew_customer_accounts?select=id,email,organization_id,license_issue_id&id=eq."+input.account_id);
  if(current.length!==1)throw Error("Customer account not found");
  if((await rows("brew_customer_accounts?select=id&license_issue_id=eq."+issue.id)).some(x=>x.id!==input.account_id))
    throw Error("License already belongs to another account");
  const row=current[0];
  // Require same customer identity. Issuing a new activation for a DIFFERENT
  // customer must never silently transfer account or expose tenant data.
  const orgs=await rows("brew_organizations?select=name&id=eq."+row.organization_id);
  if(orgs.length!==1||String(orgs[0].name).trim().toLowerCase()!==String(issue.customer_name).trim().toLowerCase())
    throw Error("License customer name does not match this account's business");
  const r=await api("brew_customer_accounts?id=eq."+input.account_id,{
    method:"PATCH",headers:{"prefer":"return=representation"},
    body:JSON.stringify({license_issue_id:issue.id,plan:issue.plan,expires_at:issue.expires_at,updated_at:new Date().toISOString()})
  });
  if(!r.ok)throw Error("Account plan could not be updated");
  const updated=await r.json();
  if(!Array.isArray(updated)||updated.length!==1)throw Error("Account update was not confirmed");
  return {updated:true,email:row.email,plan:issue.plan,expires_at:issue.expires_at,
    notice:"Cloud account plan updated. The tablet also needs the newly signed BP1 activation code."};
}

// Cloud account approval does not require an offline device license.
// Only a verified pending Auth identity is permitted to become a tenant owner.
async function approveCloudSignup(input:Record<string,unknown>, owner:string){
  const userId=String(input.user_id||"");
  if(!uuid(userId))throw Error("Choose a pending signup request");
  const plan=String(input.plan||"TRIAL").toUpperCase();
  if(!["TRIAL","MONTHLY","LIFETIME"].includes(plan))throw Error("Unknown account plan");
  const matches=await rows("brew_signup_requests?select=user_id,email,business_name,status&user_id=eq."+userId);
  if(matches.length!==1||matches[0].status!=="pending")throw Error("Signup is no longer pending");
  const request=matches[0];
  const identityResponse=await fetch(base+"/auth/v1/admin/users/"+userId,{
    headers:{apikey:serviceKey,authorization:"Bearer "+serviceKey},cache:"no-store"
  });
  if(!identityResponse.ok)throw Error("Could not verify the registered account");
  const authResult=await identityResponse.json();
  const verified=authResult?.user??authResult;
  if(verified?.id!==userId||!verified.email_confirmed_at||
     String(verified.email||"").trim().toLowerCase()!==String(request.email))
     throw Error("Customer must verify their email before approval");
  const business=normalizeCustomer(request.business_name);
  const email=String(request.email).trim().toLowerCase();
  const existing=await rows("brew_customer_accounts?select=id,user_id,status&email=eq."+encodeURIComponent(email));
  if(existing.length){
    if(existing.length===1&&existing[0].user_id===userId)
      return {approved:true,already_created:true,account_id:existing[0].id};
    throw Error("This customer email is already assigned to another account");
  }
  // Stable key allows recovery from a partial network failure on a retry.
  const slug="brew-cloud-"+userId;
  let orgs=await rows("brew_organizations?select=id,name&slug=eq."+slug);
  if(!orgs.length)orgs=[await insertOne("brew_organizations",{name:business,slug})];
  if(orgs[0].name!==business)throw Error("Tenant name mismatch; request owner support");
  const orgId=String(orgs[0].id);
  let branches=await rows("brew_branches?select=id&organization_id=eq."+orgId+"&name=eq.Main%20Branch");
  if(!branches.length)branches=[await insertOne("brew_branches",{
    organization_id:orgId,name:"Main Branch",timezone:"Asia/Manila"
  })];
  const branchId=String(branches[0].id);
  const member=await rows("brew_memberships?select=id,role,is_active&organization_id=eq."+
    orgId+"&user_id=eq."+userId);
  if(member.length===0)await insertOne("brew_memberships",{
    organization_id:orgId,user_id:userId,role:"owner",is_active:true
  });
  else if(member[0].role!=="owner"||member[0].is_active!==true)
    throw Error("Tenant membership is inactive or has an unexpected role");
  const expires=plan==="LIFETIME"?null:
    new Date(Date.now()+(plan==="TRIAL"?7:30)*86400000).toISOString();
  const account=await insertOne("brew_customer_accounts",{
    email,user_id:userId,organization_id:orgId,branch_id:branchId,
    license_issue_id:null,plan,expires_at:expires,status:"active",created_by:owner
  });
  const marked=await api("brew_signup_requests?user_id=eq."+userId,{
    method:"PATCH",headers:{"prefer":"return=representation"},
    body:JSON.stringify({status:"approved",reviewed_at:new Date().toISOString(),
      updated_at:new Date().toISOString()})
  });
  if(!marked.ok)throw Error("Cloud account created but signup status needs review");
  return {approved:true,account_id:account.id,organization_id:orgId,
    branch_id:branchId,email,plan,expires_at:expires,
    message:"Cloud login approved. Customer can sign in and register their branch."};
}
async function rejectCloudSignup(input:Record<string,unknown>){
  const id=String(input.user_id||"");
  if(!uuid(id))throw Error("Choose a pending signup");
  const req=await rows("brew_signup_requests?select=user_id,status&user_id=eq."+id);
  if(req.length!==1||req[0].status!=="pending")throw Error("Signup is not pending");
  const r=await api("brew_signup_requests?user_id=eq."+id,{
    method:"PATCH",headers:{"prefer":"return=representation"},
    body:JSON.stringify({status:"rejected",reviewed_at:new Date().toISOString()})
  });
  if(!r.ok)throw Error("Could not reject signup");
  return {rejected:true,user_id:id};
}
async function setCloudAccountStatus(input:Record<string,unknown>){
  if(!uuid(input.account_id)||!["active","suspended"].includes(String(input.status)))
    throw Error("Choose an account and valid status");
  const r=await api("brew_customer_accounts?id=eq."+input.account_id,{
    method:"PATCH",headers:{"prefer":"return=representation"},
    body:JSON.stringify({status:input.status,updated_at:new Date().toISOString()})
  });
  if(!r.ok)throw Error("Account status change failed");
  const updated=await r.json();
  if(!Array.isArray(updated)||updated.length!==1)throw Error("Account not found");
  return {updated:true,account_id:input.account_id,status:input.status};
}
// Owner-only menu management for approved BrewPOS café tenants.
// No anonymous access and no client-provided elevated role is trusted.
function catalogText(value:unknown,label:string,max=80):string{
  const v=String(value??"").trim();
  if(v.length<2||v.length>max||/[\u0000-\u001F\u007F]/.test(v))
    throw Error(label+" must contain 2–"+max+" printable characters");
  return v;
}
function catalogMoney(value:unknown):number{
  const n=Number(value);
  if(!Number.isSafeInteger(n)||n<1||n>100000000)
    throw Error("Price must be a valid positive amount in centavos");
  return n;
}
function catalogQty(value:unknown):string{
  const raw=String(value??"0").trim();
  if(!/^(?:0|[1-9][0-9]{0,6})(?:\.[0-9]{1,3})?$/.test(raw))
    throw Error("Stock quantity must be 0–9999999 with up to 3 decimals");
  return raw;
}
async function tenantBranch(org:unknown,branch:unknown){
  if(!uuid(org)||!uuid(branch))throw Error("Select a valid café and branch");
  const accounts=await rows("brew_customer_accounts?select=id,status,expires_at&organization_id=eq."+org+"&limit=2");
  const active=accounts.find(a=>a.status==="active"&&(!a.expires_at||Date.parse(String(a.expires_at))>Date.now()));
  if(!active)throw Error("This café is not an active approved Cloud POS account");
  const result=await rows("brew_branches?select=id,organization_id,is_active&organization_id=eq."+org+
    "&id=eq."+branch+"&is_active=eq.true");
  if(result.length!==1)throw Error("Branch does not belong to this approved café");
  return {organization_id:org,branch_id:branch};
}
async function catalogList(){
  const [accounts,orgs,branches,products]=await Promise.all([
    rows("brew_customer_accounts?select=organization_id,status,expires_at&order=created_at.desc&limit=100"),
    rows("brew_organizations?select=id,name&order=name.asc&limit=100"),
    rows("brew_branches?select=id,name,organization_id,is_active&order=name.asc&limit=200"),
    rows("brew_products?select=id,organization_id,branch_id,name,category,price_centavos,stock_quantity,track_stock,active,version,updated_at&order=updated_at.desc&limit=500")
  ]);
  const approved=new Set(accounts.filter(a=>a.status==="active"&&
    (!a.expires_at||Date.parse(String(a.expires_at))>Date.now())).map(a=>String(a.organization_id)));
  return {
    branches:branches.filter(b=>b.is_active===true&&approved.has(String(b.organization_id)))
      .map(b=>({...b,organization_name:orgs.find(o=>o.id===b.organization_id)?.name||"Café"})),
    products:products.filter(p=>approved.has(String(p.organization_id)))
  };
}
async function catalogCreate(input:Record<string,unknown>){
  const tenant=await tenantBranch(input.organization_id,input.branch_id);
  const name=catalogText(input.name,"Product name");
  const category=catalogText(input.category,"Category",48);
  const price=catalogMoney(input.price_centavos);
  if(typeof input.track_stock!=="boolean")throw Error("Choose stock tracking on or off");
  const quantity=input.track_stock?catalogQty(input.stock_quantity):"0";
  const existing=await rows("brew_products?select=id,category,name&organization_id=eq."+tenant.organization_id+
    "&branch_id=eq."+tenant.branch_id+"&name=eq."+encodeURIComponent(name)+"&limit=10");
  if(existing.some(p=>String(p.name).toLowerCase()===name.toLowerCase()&&
      String(p.category).toLowerCase()===category.toLowerCase()))
    throw Error("A product with this name and category already exists in this branch");
  const product=await insertOne("brew_products",{
    ...tenant,name,category,price_centavos:price,
    stock_quantity:quantity,track_stock:input.track_stock,active:true,version:1
  });
  return {created:true,product};
}
async function catalogUpdate(input:Record<string,unknown>){
  if(!uuid(input.product_id))throw Error("Select an existing product");
  const tenant=await tenantBranch(input.organization_id,input.branch_id);
  const products=await rows("brew_products?select=id,organization_id,branch_id,version&id=eq."+
    input.product_id+"&organization_id=eq."+tenant.organization_id+
    "&branch_id=eq."+tenant.branch_id);
  if(products.length!==1)throw Error("Product not found in selected branch");
  const name=catalogText(input.name,"Product name");
  const category=catalogText(input.category,"Category",48);
  const price=catalogMoney(input.price_centavos);
  if(typeof input.active!=="boolean"||typeof input.track_stock!=="boolean")
    throw Error("Product visibility and stock options are required");
  const version=Number(input.version);
  if(!Number.isSafeInteger(version)||version!==Number(products[0].version))
    throw Error("Product was modified elsewhere; refresh before editing");
  const result=await api("brew_products?id=eq."+input.product_id+
    "&organization_id=eq."+tenant.organization_id+
    "&branch_id=eq."+tenant.branch_id+"&version=eq."+version,{
      method:"PATCH",headers:{"prefer":"return=representation"},
      body:JSON.stringify({name,category,price_centavos:price,
        active:input.active,track_stock:input.track_stock,version:version+1,
        updated_at:new Date().toISOString()})
    });
  if(!result.ok)throw Error("Cloud product update failed");
  const saved=await result.json();
  if(!Array.isArray(saved)||saved.length!==1)
    throw Error("Product changed while saving. Refresh and retry");
  return {updated:true,product:saved[0]};
}
async function route(action: string, input: Record<string,unknown>, userId: string) {
  if(action==="catalog_list")return await catalogList();
  if(action==="catalog_create")return await catalogCreate(input);
  if(action==="catalog_update")return await catalogUpdate(input);
  if(action==="signup_requests"){
    return {requests:await rows("brew_signup_requests?select=user_id,email,business_name,android_device_id,status,requested_at&order=requested_at.desc&limit=100")};
  }
  if (action === "accounts") {
    return {accounts:await rows("brew_customer_accounts?select=id,email,organization_id,branch_id,license_issue_id,plan,expires_at,status,created_at&order=created_at.desc&limit=100")};
  }
  if (action === "approve_cloud_signup") return await approveCloudSignup(input,userId);
  if (action === "reject_cloud_signup") return await rejectCloudSignup(input);
  if (action === "set_cloud_account_status") return await setCloudAccountStatus(input);
  if (action === "provision_customer") return await provisionCustomer(input,userId);
  if (action === "change_customer_license") return await changeCustomerLicense(input);
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
  if (!base || !serviceKey) return answer(req,503,{error:"Owner console is not configured"});
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
