import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
const page=(name)=>readFileSync(new URL("../public/"+name,import.meta.url),"utf8");
const api=readFileSync(new URL("../../supabase/functions/brewpos-cafe/index.ts",import.meta.url),"utf8");
test("café owner portal is separate from Azurate owner console",()=>{
 const html=page("cafe.html"),main=page("index.html");
 assert.match(html,/id="loginScreen"/);
 assert.match(html,/id="workspace"/);
 assert.match(html,/id="productForm"/);
 assert.match(html,/id="branchSelect"/);
 for(const id of ["dashboard","menu","inventory","kiosk","sales","devices","subscription"])
   assert.match(html,new RegExp('id="'+id+'"'));
 assert.match(main,/href="cafe\.html"/);
 assert.doesNotMatch(html,/id="issueForm"|generate signed offline activation/i);
});
test("Café frontend does not ship privileged keys or persist login secrets",()=>{
 const js=page("cafe.v1.js");
 assert.match(js,/auth\("verify"/);
 assert.match(js,/auth\("otp"/);
 assert.match(js,/brewpos-cafe/);
 assert.match(js,/catalog_create/);
 assert.match(js,/catalog_update/);
 assert.match(js,/session/);
 assert.doesNotMatch(js,/SUPABASE_SERVICE_ROLE_KEY|BREWPOS_LICENSE_PRIVATE_KEY|localStorage|sessionStorage/);
 assert.doesNotMatch(js,/innerHTML\s*=/);
 assert.match(js,/cafe\("snapshot",scope\(\)\)/);
});
test("Cafe API checks verified JWT, active subscription, tenant-scoped branch, and roles",()=>{
 assert.match(api,/auth\/v1\/user/);
 assert.match(api,/email_confirmed_at/);
 assert.match(api,/brew_memberships/);
 assert.match(api,/brew_customer_accounts/);
 assert.match(api,/Date\.parse\(x\.expires_at\)>Date\.now\(\)/);
 assert.match(api,/owner","manager/);
 assert.match(api,/scopeFor\(scopes,data\.organization_id,data\.branch_id\)/);
 assert.match(api,/branch_id=eq\./);
 assert.doesNotMatch(api,/function.*issueLicense|signBP1/);
});
