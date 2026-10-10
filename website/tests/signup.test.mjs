import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
const root = path => readFileSync(new URL("../../"+path,import.meta.url),"utf8");

test("Android Create Account triggers OTP signup, while sign-in never creates strangers",()=>{
  const cloud=root("app/src/main/java/com/brewpos/cafe/BrewCloud.kt");
  const dialog=root("app/src/main/java/com/brewpos/cafe/BrewSignupDialog.kt");
  const app=root("app/src/main/java/com/brewpos/cafe/MainActivity.kt");
  const screen=root("app/src/main/java/com/brewpos/cafe/CloudScreen.kt");
  const registration=cloud.slice(cloud.indexOf("suspend fun sendRegistrationCode"),
    cloud.indexOf("suspend fun requestAccountApproval"));
  const login=cloud.slice(cloud.indexOf("suspend fun sendLoginCode"),
    cloud.indexOf("suspend fun loginWithCode"));
  assert.ok(registration.includes('.put("create_user", true)'));
  assert.ok(login.includes('.put("create_user", false)'));
  assert.ok(cloud.includes("loginWithCode(ctx,email,code)"));
  assert.ok(dialog.includes("Verify OTP & create account"));
  assert.ok(dialog.includes("waiting for Azurate approval"));
  assert.ok(app.includes("Create account • Email OTP"));
  assert.ok(screen.includes("Create account • Email OTP"));
});
test("OTP signup only creates a pending request and grants no sales or paid access",()=>{
  const sync=root("supabase/functions/brewpos-sync/index.ts");
  const start=sync.indexOf('if(data.action==="request_account")');
  const end=sync.indexOf('if(data.action==="whoami")',start);
  assert.ok(start>0 && end>start);
  const handler=sync.slice(start,end);
  assert.ok(sync.includes("user?.email_confirmed_at"));
  assert.ok(handler.includes("brew_signup_requests"));
  assert.ok(handler.includes("identity.email"));
  assert.ok(handler.includes('status:"pending"'));
  for(const forbidden of ["brew_licenses","license_issue_id","signBP1","brew_devices?select"])
    assert.equal(handler.includes(forbidden),false);
});
test("registration table denies client direct reads and does not expose pricing fields",()=>{
  const sql=root("supabase/drafts/brewpos_signup_email_otp.sql");
  assert.ok(sql.includes("enable row level security"));
  assert.ok(sql.includes("from public, anon, authenticated, service_role"));
  assert.ok(sql.includes("to service_role"));
  assert.equal(sql.includes("license_issue_id"),false);
});
test("only owner can approve email, matching business and device for a signed BP1",()=>{
  const owner=root("supabase/functions/brewpos-owner/index.ts");
  for(const required of ['if(signup.status!=="pending")',"issue.customer_name",
    "issue.android_device_id","/auth/v1/admin/users/","authUser.email_confirmed_at",
    'action==="signup_requests"',"checkedOwner(req)"])
    assert.ok(owner.includes(required),required);
});
test("owner dashboard lists real signup requests and allows manual license matching",()=>{
  const html=root("website/public/owner.html");
  const js=root("website/public/owner.js");
  assert.ok(html.includes('id="signupRows"'));
  assert.ok(html.includes('id="signupSelection"'));
  assert.ok(js.includes('owner("signup_requests")'));
  assert.ok(js.includes('owner("provision_customer"'));
});

test("public GitHub Pages registration verifies email OTP before submitting approval",()=>{
  const page=root("website/public/signup.html");
  const js=root("website/public/signup.js");
  const index=root("website/public/index.html");
  assert.ok(page.includes('id="business"'));
  assert.ok(page.includes('id="device"'));
  assert.ok(page.includes('id="otp"'));
  assert.ok(page.includes('connect-src https://rfxzbuocxersgbshczbj.supabase.co'));
  assert.ok(js.includes('create_user:true'));
  assert.ok(js.includes('/auth/v1/verify'));
  assert.ok(js.includes('action:"request_account"'));
  assert.ok(js.includes('setStage("retry")'));
  assert.ok(index.includes('href="signup.html"'));
  for(const forbidden of ["service_role","PRIVATE_KEY_PKCS8","localStorage","sessionStorage"])
    assert.equal(js.includes(forbidden),false);
});

test("Supabase confirmation and magic-link emails send the real six-digit OTP instead of a link",()=>{
  for(const name of ["confirm-signup-otp.html","magic-link-otp.html"]) {
    const html=root("supabase/email-templates/"+name);
    assert.ok(html.includes("{{ .Token }}"),name+" must insert real generated Supabase OTP");
    assert.equal(html.includes("{{ .ConfirmationURL }}"),false,name+" must not contain click-only confirmation");
    assert.ok(html.includes("BrewPOS"));
  }
  const guide=root("supabase/email-templates/README.md");
  assert.ok(guide.includes("Confirm signup"));
  assert.ok(guide.includes("Magic Link"));
  assert.ok(guide.includes("not yet saved inside Supabase Auth"));
});
test("website and Android use exactly six digits and offer resend after a link-only email",()=>{
  const signup=root("website/public/signup.html");
  const js=root("website/public/signup.js");
  const cloud=root("app/src/main/java/com/brewpos/cafe/BrewCloud.kt");
  const dialog=root("app/src/main/java/com/brewpos/cafe/BrewSignupDialog.kt");
  const screen=root("app/src/main/java/com/brewpos/cafe/CloudScreen.kt");
  assert.ok(signup.includes('pattern="[0-9]{6}" maxlength="6"'));
  assert.ok(signup.includes('id="resend"'));
  assert.ok(js.includes('el("resend").addEventListener("click"'));
  assert.ok(js.includes('if(!/^[0-9]{6}$/.test(token))'));
  assert.ok(cloud.includes('Regex("^[0-9]{6}$")'));
  assert.ok(dialog.includes("Resend 6-digit code"));
  assert.ok(screen.includes("Resend 6-digit code"));
});

test("OTP requests always use BrewPOS GitHub Pages redirect, never localhost",()=>{
  const browser=root("website/public/signup.js");
  const android=root("app/src/main/java/com/brewpos/cafe/BrewCloud.kt");
  assert.ok(browser.includes("https://markyyy-lolz.github.io/BrewPOS-Android/signup.html"));
  assert.ok(browser.includes('"/auth/v1/otp?redirect_to="+encodeURIComponent(GITHUB_SIGNUP)'));
  assert.ok(browser.includes("await post(otpPath,{email,create_user:true})"));
  assert.ok(android.includes("https://markyyy-lolz.github.io/BrewPOS-Android/signup.html"));
  assert.ok(android.includes("URLEncoder.encode(PUBLIC_AUTH_REDIRECT"));
  assert.ok(android.includes("request(otpEndpoint(), JSONObject()"));
  assert.equal(browser.includes("localhost:3000"),false);
  assert.equal(android.includes("localhost:3000"),false);
});
test("legacy Magic Link fallback sanitizes access and refresh fragments before UI",()=>{
  const browser=root("website/public/signup.js");
  assert.ok(browser.includes('fragment.get("access_token")'));
  assert.ok(browser.includes('fragment.has("refresh_token")'));
  assert.ok(browser.includes('history.replaceState(null,"",location.pathname+location.search)'));
  assert.ok(browser.includes('setStage("link")'));
  assert.ok(browser.includes('BASE+"/auth/v1/user"'));
  assert.ok(browser.includes('identity.email_confirmed_at'));
  assert.ok(browser.includes('action:"request_account"'));
  assert.equal(browser.includes("localStorage"),false);
  assert.equal(browser.includes("sessionStorage"),false);
});


test("server accepts legitimate business names and rejects only real control characters",()=>{
  const source=root("supabase/functions/brewpos-sync/index.ts");
  const capture=source.match(/business\.length>80\|\|\/([^/]+)\/\.test\(business\)/);
  assert.ok(capture,"business-name validator must be present");
  const disallowed=new RegExp(capture[1]);
  for(const name of ["Punong Tulay","Brew & Bean Coffee","Shirene Store","Milk Tea","Silogan"])
    assert.equal(disallowed.test(name),false,"Unexpected rejection: "+name);
  for(const name of ["Bad|Name","Bad\\nName","Bad\\tName","Bad\\u0000Name"])
    assert.equal(disallowed.test(name),true,"Must block invalid value");
  assert.equal(source.includes(String.raw`/[|\\\\u0000-\\\\u001f\\\\u007f]/`),false,
    "double-escaped unicode ranges cause all business names to be rejected");
});
