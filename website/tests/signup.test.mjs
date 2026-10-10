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
