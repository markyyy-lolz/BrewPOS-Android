import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
const root = path => readFileSync(new URL("../../"+path,import.meta.url),"utf8");

test("mobile Create Account uses OTP user creation; ordinary login cannot silently sign up",()=>{
  const cloud=root("app/src/main/java/com/brewpos/cafe/BrewCloud.kt");
  const dialog=root("app/src/main/java/com/brewpos/cafe/BrewSignupDialog.kt");
  const app=root("app/src/main/java/com/brewpos/cafe/MainActivity.kt");
  const screen=root("app/src/main/java/com/brewpos/cafe/CloudScreen.kt");
  assert.match(cloud,/suspend fun sendRegistrationCode/);
  assert.match(cloud,/sendRegistrationCode[\\s\\S]*?create_user", true/);
  assert.match(cloud,/sendLoginCode[\\s\\S]*?create_user", false/);
  assert.match(cloud,/registerWithCode[\\s\\S]*?loginWithCode/);
  assert.match(dialog,/Verify OTP & create account/);
  assert.match(dialog,/waiting for Azurate approval/i);
  assert.match(app,/Create account • Email OTP/);
  assert.match(screen,/Create account • Email OTP/);
});
test("verified registration request endpoint never issues a plan or authorizes a device",()=>{
  const sync=root("supabase/functions/brewpos-sync/index.ts");
  const location=sync.indexOf('if(data.action==="request_account")');
  const end=sync.indexOf('if(data.action==="whoami")',location);
  assert.ok(location>0 && end>location);
  const handler=sync.slice(location,end);
  assert.match(sync,/user\\?\.email_confirmed_at/);
  assert.match(handler,/brew_signup_requests/);
  assert.match(handler,/identity.email/);
  assert.match(handler,/status:"pending"/);
  assert.doesNotMatch(handler,/brew_memberships\\?on_conflict|brew_licenses|license_issue_id|signBP1|brew_devices/);
});
test("signup table is isolated from client direct reads and cannot assign paid plans",()=>{
  const sql=root("supabase/drafts/brewpos_signup_email_otp.sql");
  assert.match(sql,/brew_signup_requests/);
  assert.match(sql,/enable row level security/);
  assert.match(sql,/revoke all on public\\.brew_signup_requests from public, anon, authenticated, service_role/);
  assert.match(sql,/grant select, insert, update on public\\.brew_signup_requests to service_role/);
  assert.doesNotMatch(sql,/plan text|role text|license_issue_id/);
});
test("owner approval validates email identity, business and device against a real BP1 issue",()=>{
  const owner=root("supabase/functions/brewpos-owner/index.ts");
  assert.match(owner,/if\\(signup.status!=="pending"\\)/);
  assert.match(owner,/issue\\.customer_name/);
  assert.match(owner,/issue\\.android_device_id/);
  assert.match(owner,/auth\\/v1\\/admin\\/users\\//);
  assert.match(owner,/authUser\\.email_confirmed_at/);
  assert.match(owner,/action==="signup_requests"/);
  assert.match(owner,/checkedOwner\\(req\\)/);
});
test("owner dashboard displays real verified requests, not simulated registrations",()=>{
  const html=root("website/public/owner.html");
  const js=root("website/public/owner.js");
  assert.match(html,/id="signupRows"/);
  assert.match(html,/id="signupSelection"/);
  assert.match(js,/owner\\("signup_requests"\\)/);
  assert.match(js,/owner\\("provision_customer"/);
});
