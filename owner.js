"use strict";
// Browser uses ONLY the public Supabase key and the signed-in owner's JWT.
// The ECDSA private key + service_role key remain in the Edge Function.
const CLOUD = "https://rfxzbuocxersgbshczbj.supabase.co";
const PUBLISHABLE_KEY = "sb_publishable_CR-CJKna_rcjQi7gg1CZGQ__DnXI6Lb";
const el = id => document.getElementById(id);
const php = cents => new Intl.NumberFormat("en-PH",{style:"currency",currency:"PHP"}).format(Number(cents||0)/100);
const date = s => s ? new Date(s).toLocaleString("en-PH",{dateStyle:"medium",timeStyle:"short",timeZone:"Asia/Manila"}) : "Lifetime";
const validId = id => /^[0-9a-f]{16}$/.test(id);
let jwt = null, expiresAt = 0, currentTab = "overview";
let busy = false;
function status(message,kind="info") {
  const box = el("status");box.textContent = message || "";box.className = message ? "status show "+kind : "status";
}
function loginError(message){el("loginError").textContent=message||"";}
function setSignedIn(yes) {
  el("loginScreen").hidden=yes;
  el("app").hidden=!yes;
  if(!yes) {el("generatedCode").value="";el("codeResult").hidden=true;el("noCode").hidden=false;}
}
async function requestOwnerOtp(email) {
  const normalized=String(email).trim().toLowerCase();
  if(!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalized))
    throw Error("Enter a valid owner email address.");
  const response=await fetch(CLOUD+"/auth/v1/otp",{
    method:"POST",cache:"no-store",
    headers:{"content-type":"application/json","apikey":PUBLISHABLE_KEY},
    body:JSON.stringify({email:normalized,create_user:true})
  });
  if(!response.ok) {
    if(response.status===429)throw Error("Email OTP rate limit reached. Please wait before requesting another code.");
    if(response.status===422)throw Error("Supabase email signup is disabled. Enable Email signups or contact your Supabase administrator.");
    throw Error("Could not send email OTP. Check SMTP and Auth settings, then try again.");
  }
  // Public Auth signup is not admin registration: the server grants access ONLY
  // after an existing private invitation and verified email bind to an Auth UUID.
}
async function login(email,code) {
  const normalized=String(email).trim().toLowerCase();
  const token=String(code).trim();
  // Preserve the exact generated numeric token; do not truncate an 8-digit code.
  if(!/^[0-9]{6,8}$/.test(token))throw Error("Enter the full numeric email code.");
  const response=await fetch(CLOUD+"/auth/v1/verify",{
    method:"POST",cache:"no-store",
    headers:{"content-type":"application/json","apikey":PUBLISHABLE_KEY},
    body:JSON.stringify({email:normalized,token,type:"email"})
  });
  if(!response.ok)throw Error("Email verification code expired or invalid. Request a fresh code.");
  const body=await response.json();
  if(!body.access_token)throw Error("Supabase did not return a valid session.");
  jwt=body.access_token;
  expiresAt=Date.now()+Math.max(60,Number(body.expires_in)||3600)*1000;
}
async function owner(action,more={}) {
  if(!jwt || Date.now()>=expiresAt-10000) {
    clearSession();
    throw Error("Session expired. Sign in again.");
  }
  const response=await fetch(CLOUD+"/functions/v1/brewpos-owner",{
    method:"POST",cache:"no-store",
    headers:{"content-type":"application/json","apikey":PUBLISHABLE_KEY,"Authorization":"Bearer "+jwt},
    body:JSON.stringify({action,...more})
  });
  let json={};
  try { json=await response.json(); } catch {}
  if(!response.ok) {
    if(response.status===401||response.status===403) {
      clearSession();
      throw Error("Azurate owner access required or session expired. Confirm your owner account is provisioned.");
    }
    throw Error(String(json.error||"Cloud request failed ("+response.status+")"));
  }
  return json;
}
function clearSession() {
  jwt=null;expiresAt=0;
  setSignedIn(false);
  loginError("");
  // No JWT or password is stored in browser localStorage/sessionStorage.
}
function switchTab(tab) {
  const title={
    overview:["Overview","Your workspace","Live data from your dedicated BrewPOS Supabase project."],
    activation:["Activation generator","Generate a license","Signed activation codes bound to one Android Device ID."],
    licenses:["Issued licenses","Issuance history","Audit log of generated activations (no plaintext codes stored)."],
    accounts:["Client accounts","Customer onboarding","Invite customers and edit the plan according to their purchased signed license."],
    devices:["Devices","Registered tablets","Cloud device registrations and their last reported activity."],
    sales:["Cloud sales","Synchronized transactions","Read-only sales received by BrewPOS Cloud."]
  };
  if(!title[tab])return;
  currentTab=tab;
  for(const v of document.querySelectorAll(".view")) v.hidden = v.id !== tab;
  for(const b of document.querySelectorAll("[data-tab]"))b.classList.toggle("active",b.dataset.tab===tab);
  el("pageName").textContent=title[tab][0];
  el("pageTitle").textContent=title[tab][1];
  el("pageSubtitle").textContent=title[tab][2];
  status("");
  loadTab().catch(e=>status(e.message,"error"));
}
function setCells(bodyId,rows,keys,empty) {
  const target=el(bodyId);target.replaceChildren();
  if(!rows.length) {
    const tr=document.createElement("tr"),td=document.createElement("td");
    td.colSpan=keys.length;td.className="table-empty";td.textContent=empty;tr.append(td);target.append(tr);return;
  }
  for(const row of rows) {
    const tr=document.createElement("tr");
    for(const fn of keys) {
      const td=document.createElement("td");
      td.textContent=String(fn(row)??"—");
      tr.append(td);
    }
    target.append(tr);
  }
}
function fillOptions(id,items,valueOf,labelOf,placeholder) {
  const select=el(id), current=select.value;
  select.replaceChildren();
  const first=document.createElement("option");
  first.value=""; first.textContent=placeholder; select.append(first);
  for(const item of items) {
    const option=document.createElement("option");
    option.value=String(valueOf(item)); option.textContent=String(labelOf(item));
    select.append(option);
  }
  if(items.some(x=>String(valueOf(x))===current))select.value=current;
}
async function loadTab() {
  if(busy)return;
  busy=true;
  el("connection").textContent="● Checking cloud";
  el("refresh").disabled=true;
  try {
    if(currentTab==="overview"){
      const d=await owner("overview");
      el("revenue").textContent=php(d.recent_sales_centavos);
      el("orderCount").textContent=String(d.recent_sales_count??0);
      el("deviceCount").textContent=String(d.registered_devices??0);
      el("activeDeviceCount").textContent=String(d.active_devices??0)+" active devices";
      el("licenseCount").textContent=String(d.issued_licenses??0);
      el("orgCount").textContent=String(d.organization_count??0);
    } else if(currentTab==="licenses") {
      const data=await owner("licenses");
      setCells("licensesRows",data.licenses||[],[
        x=>x.customer_name,x=>x.android_device_id,x=>x.plan,
        x=>date(x.expires_at),x=>date(x.issued_at)
      ],"No activation codes have been issued yet.");
    } else if(currentTab==="accounts") {
      const [licenses,customers,signups]=await Promise.all([
        owner("licenses"), owner("accounts"), owner("signup_requests")
      ]);
      const registrationList=signups.requests||[];
      setCells("signupRows",registrationList,[
        x=>x.email,x=>x.business_name,x=>x.android_device_id,
        x=>x.status,x=>date(x.requested_at)
      ],"No OTP-verified registration requests yet.");
      fillOptions("signupSelection",registrationList.filter(x=>x.status==="pending"),
        x=>x.email,x=>x.business_name+" — "+x.email+" — "+x.android_device_id,
        "Select an email-verified registration");
      const issues=licenses.licenses||[], accounts=customers.accounts||[];
      setCells("accountRows",accounts,[
        x=>x.email,x=>x.plan,x=>date(x.expires_at),x=>x.status,x=>date(x.created_at)
      ],"No client cloud accounts. First issue a signed activation, then invite a customer.");
      const activeIssues=issues.filter(x=>!x.expires_at||new Date(x.expires_at).getTime()>Date.now());
      const used=new Set(accounts.map(x=>x.license_issue_id));
      fillOptions("accountIssue",activeIssues.filter(x=>!used.has(x.id)),
        x=>x.id,x=>x.customer_name+" — "+x.plan+" — "+x.android_device_id,
        "Select issued BP1 activation");
      fillOptions("accountToEdit",accounts,x=>x.id,x=>x.email+" — "+x.plan,"Select account");
      fillOptions("accountReplacementIssue",activeIssues.filter(x=>!used.has(x.id)),
        x=>x.id,x=>x.customer_name+" — "+x.plan+" — "+x.android_device_id,
        "Select replacement activation");
    } else if(currentTab==="devices") {
      const data=await owner("devices");
      setCells("devicesRows",data.devices||[],[
        x=>x.display_name,x=>x.installation_id,
        x=>x.is_active ? "Active" : "Revoked",
        x=>x.last_seen_at ? date(x.last_seen_at) : "Never synced"
      ],"No devices registered. Secure staff and branch onboarding is required.");
    } else if(currentTab==="sales") {
      const data=await owner("sales");
      setCells("salesRows",data.sales||[],[
        x=>x.local_receipt_no,x=>date(x.received_at),
        x=>x.payment_method,x=>php(x.total_centavos),x=>x.status
      ],"No synced cloud sales. Offline tablet sales are not included yet.");
    } else if(currentTab==="activation"){
      // Read-only owner auth check before exposing the issuance form.
      await owner("overview");
    }
    el("connection").textContent="● Cloud connected";
  } catch(e) {
    el("connection").textContent="● Needs attention";
    throw e;
  } finally{busy=false;el("refresh").disabled=false;}
}
el("sendLoginOtp").addEventListener("click",async()=>{
  loginError("");
  const button=el("sendLoginOtp");
  const email=String(new FormData(el("loginForm")).get("email")||"");
  button.disabled=true;
  try{
    await requestOwnerOtp(email);
    loginError("Email code requested. Check your inbox and spam folder.");
  }catch(e){loginError(e.message||"Could not request email code.");}
  finally{button.disabled=false;}
});
el("loginForm").addEventListener("submit",async event=>{
 event.preventDefault();loginError("");
 const button=el("loginButton");button.disabled=true;
 const data=new FormData(event.currentTarget);
 try{
   await login(String(data.get("email")||""),String(data.get("code")||""));
   // The server must authorize the verified Auth user ID before any owner data is displayed.
   await owner("overview");
   setSignedIn(true);
   event.currentTarget.reset();
   switchTab("overview");
 }catch(e){
   clearSession();
   loginError(e.message||"Unable to sign in");
 }finally{button.disabled=false;}
});
for(const b of document.querySelectorAll("[data-tab]"))b.addEventListener("click",()=>switchTab(b.dataset.tab));
el("goActivation").addEventListener("click",()=>switchTab("activation"));
el("refresh").addEventListener("click",()=>loadTab().catch(e=>status(e.message,"error")));
el("signOut").addEventListener("click",()=>{clearSession();loginError("Signed out.");});
el("issueForm").addEventListener("submit",async event=>{
 event.preventDefault();status("");
 const button=el("issueButton");button.disabled=true;
 el("generatedCode").value="";el("codeResult").hidden=true;el("noCode").hidden=false;
 const customer=el("customer").value.trim(),id=el("androidId").value.trim().toLowerCase(),plan=el("plan").value;
 try {
   if(!validId(id))throw Error("Android Device ID must be exactly 16 hexadecimal characters.");
   const out=await owner("issue",{customer,android_device_id:id,plan});
   if(!out.code || !out.code.startsWith("BP1.")) throw Error("Signing service returned an invalid code.");
   el("generatedCode").value=out.code;
   el("noCode").hidden=true;el("codeResult").hidden=false;
   el("issuedTo").textContent="Customer: "+out.customer+" • Device "+out.android_device_id;
   el("issueExpiry").textContent=out.expires_at?"Expires: "+date(out.expires_at):"Lifetime device-bound license";
   status("Signed BP1 activation issued and logged. Copy it securely; it cannot be retrieved later.","success");
 }catch(e){status(e.message||"Activation could not be generated","error");}
 finally {button.disabled=false;}
});
el("copyCode").addEventListener("click",async()=>{
 const code=el("generatedCode").value;
 if(!code)return;
 try{await navigator.clipboard.writeText(code);status("Activation code copied. Share it securely.","success");}
 catch {el("generatedCode").select();status("Select and manually copy the highlighted activation code.","info");}
});
el("signupSelection").addEventListener("change",()=>{
  const email=el("signupSelection").value;
  el("accountEmail").value=email;
  if(email)status("Signup selected. Issue a matching BP1 token for this customer's business and device before approving.","info");
});
el("accountCreateForm").addEventListener("submit",async event=>{
  event.preventDefault();status("");
  const button=el("accountCreateButton");button.disabled=true;
  try {
    const email=el("accountEmail").value.trim();
    const license_issue_id=el("accountIssue").value;
    if(!email||!license_issue_id)throw Error("Choose an email and issued license.");
    const result=await owner("provision_customer",{email,license_issue_id});
    el("accountCreateForm").reset();
    status("Client account created for "+result.email+". Tell them to open BrewPOS Android → Cloud and request an email sign-in code.","success");
    await loadTab();
  } catch(e) {status(e.message||"Client invitation failed","error");}
  finally {button.disabled=false;}
});
el("accountPlanForm").addEventListener("submit",async event=>{
  event.preventDefault();status("");
  const button=el("accountUpdateButton");button.disabled=true;
  try {
    const account_id=el("accountToEdit").value,license_issue_id=el("accountReplacementIssue").value;
    if(!account_id||!license_issue_id)throw Error("Choose the customer and new purchased license.");
    const result=await owner("change_customer_license",{account_id,license_issue_id});
    status("Account "+result.email+" now uses "+result.plan+". Send the new signed BP1 code to their tablet.","success");
    await loadTab();
  } catch(e) {status(e.message||"Plan update failed","error");}
  finally {button.disabled=false;}
});
setSignedIn(false);
