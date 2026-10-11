import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
const file=(name)=>readFileSync(new URL("../public/"+name,import.meta.url),"utf8");
test("BrewPOS cloud signup needs email and business only, never a device ID",()=>{
 const html=file("signup.html"),js=file("signup.js");
 assert.match(html,/id="business"/);
 assert.match(html,/id="email"/);
 assert.doesNotMatch(html,/id="device"/);
 assert.doesNotMatch(js,/validDevice|details\.device|el\("device"\)/);
 assert.match(js,/action:"request_account",business_name:details\.business/);
 assert.match(js,/auth\\/v1\\/verify/);
 assert.match(html,/src="signup-cloud-v2.js"/);
 const v2=file("signup-cloud-v2.js");
 assert.doesNotMatch(v2,/el\\("device"\\)|validDevice|details\\.device/);
 assert.match(v2,/action:"request_account",business_name:details\\.business/);
});
