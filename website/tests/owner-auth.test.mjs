import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
const repo=path=>readFileSync(new URL("../../"+path,import.meta.url),"utf8");

test("Azurate Owner can request and verify email OTP without creating a customer",()=>{
  const html=repo("website/public/owner.html");
  const js=repo("website/public/owner.js");
  assert.ok(html.includes('id="sendLoginOtp"'));
  assert.ok(html.includes('name="code"'));
  assert.ok(html.includes('autocomplete="one-time-code"'));
  assert.ok(js.includes('"/auth/v1/otp"'));
  assert.ok(js.includes("create_user:false"));
  assert.ok(js.includes('"/auth/v1/verify"'));
  assert.ok(js.includes('type:"email"'));
  assert.ok(js.includes('await owner("overview")'));
  assert.equal(js.includes('grant_type=password'),false);
  assert.doesNotMatch(js,/(?:localStorage|sessionStorage)\.(?:setItem|getItem|removeItem)/);

});

test("Owner backend verifies JWT and grants access exclusively by UUID",()=>{
  const api=repo("supabase/functions/brewpos-owner/index.ts");
  const start=api.indexOf("async function checkedOwner(");
  const end=api.indexOf("function integerCentavos",start);
  const auth=api.slice(start,end);
  assert.ok(auth.includes("/auth/v1/user"));
  assert.ok(auth.includes("user?.email_confirmed_at"));
  assert.ok(auth.includes("uuid(user?.id)"));
  assert.ok(auth.includes('brew_owner_accounts?select=user_id&user_id=eq.'));
  assert.ok(auth.includes('is_active=eq.true'));
  assert.ok(auth.includes("matches[0].user_id === user.id"));
  assert.equal(auth.includes("user_metadata"),false);
  assert.equal(auth.includes("email==="),false);
  assert.ok(api.includes('await checkedOwner(req)'));
  assert.ok(api.includes('if (!base || !serviceKey)'));
});

test("Owner registry is private and cannot be self-promoted from browser/client",()=>{
  const sql=repo("supabase/drafts/brewpos_owner_private_allowlist.sql");
  assert.ok(sql.includes("user_id uuid primary key references auth.users(id)"));
  assert.ok(sql.includes("enable row level security"));
  assert.ok(sql.includes("revoke all on public.brew_owner_accounts from public, anon, authenticated, service_role"));
  assert.ok(sql.includes("grant select on public.brew_owner_accounts to service_role"));
  assert.ok(sql.includes("using (false)"));
  assert.equal(sql.includes("insert into public.brew_owner_accounts"),false);
});
