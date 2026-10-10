import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
const repo = p => readFileSync(new URL("../../"+p,import.meta.url),"utf8");

test("client accounts are provisioned only by verified owner and issued BP1 license",()=>{
  const backend=repo("supabase/functions/brewpos-owner/index.ts");
  assert.match(backend,/checkedOwner\(req\)/);
  assert.match(backend,/brew_activation_issues\?select=/);
  assert.match(backend,/provision_customer/);
  assert.match(backend,/change_customer_license/);
  assert.match(backend,/brew_customer_accounts/);
  assert.match(backend,/auth\/v1\/admin\/users/);
  assert.doesNotMatch(backend,/password\s*:\s*["']/);
});
test("customer database cannot be read or updated directly by anon or authenticated",()=>{
  const sql=repo("supabase/drafts/brewpos_customer_account_provisioning.sql");
  assert.match(sql,/enable row level security/i);
  assert.match(sql,/revoke all.*public, anon, authenticated, service_role/i);
  assert.match(sql,/grant select, insert, update.*service_role/i);
  assert.match(sql,/license_issue_id uuid not null unique/i);
});
test("BrewPOS Android email OTP login forbids public signup and keeps encrypted sessions",()=>{
  const kotlin=repo("app/src/main/java/com/brewpos/cafe/BrewCloud.kt");
  const screen=repo("app/src/main/java/com/brewpos/cafe/CloudScreen.kt");
  assert.match(kotlin,/create_user", false/);
  assert.match(kotlin,/auth\/v1\/otp/);
  assert.match(kotlin,/auth\/v1\/verify/);
  assert.match(kotlin,/store\(ctx, parseTokens\(response\)\)/);
  assert.match(kotlin,/AndroidKeyStore/);
  assert.match(screen,/Send email sign-in code/);
  assert.match(screen,/Verify code & sign in/);
});
test("owner customer form uses server authorization, not client-side plan assignment",()=>{
  const ui=repo("website/public/owner.html");
  const js=repo("website/public/owner.js");
  assert.match(ui,/Client accounts/);
  assert.match(ui,/accountCreateForm/);
  assert.match(js,/owner\("provision_customer"/);
  assert.match(js,/owner\("change_customer_license"/);
  assert.doesNotMatch(js,/SUPABASE_SERVICE_ROLE_KEY|BREWPOS_LICENSE_PRIVATE_KEY_PKCS8_B64/);
});
