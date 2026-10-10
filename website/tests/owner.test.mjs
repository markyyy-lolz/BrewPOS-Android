import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { webcrypto, createPublicKey, verify } from "node:crypto";
import { signBP1, p1363ToDer } from "../../supabase/functions/brewpos-owner/license-signing.mjs";

const file = name => readFileSync(new URL("../public/"+name,import.meta.url),"utf8");
const backend = readFileSync(new URL("../../supabase/functions/brewpos-owner/index.ts",import.meta.url),"utf8");

test("owner portal uses a real API and does not expose privileged keys",()=>{
  const html=file("owner.html"),js=file("owner.js");
  assert.match(html,/noindex,nofollow/);
  assert.match(html,/Activation generator/);
  assert.match(html,/Issued licenses/);
  assert.match(html,/Cloud sales/);
  assert.match(js,/functions\/v1\/brewpos-owner/);
  assert.match(js,/auth\/v1\/token/);
  assert.match(backend,/auth\/v1\/user/);
  assert.match(backend,/BREWPOS_OWNER_USER_ID/);
  assert.match(backend,/email_confirmed_at/);
  assert.doesNotMatch(js,/SUPABASE_SERVICE_ROLE_KEY|BREWPOS_LICENSE_PRIVATE_KEY/);
  assert.doesNotMatch(html,/SUPABASE_SERVICE_ROLE_KEY|BREWPOS_LICENSE_PRIVATE_KEY/);
  assert.doesNotMatch(js, /(?:window|globalThis)\.(?:localStorage|sessionStorage)|(?:localStorage|sessionStorage)\.(?:getItem|setItem|removeItem)/);
  assert.match(backend,/ownerId/);
});
test("owner portal never fabricates sample transactions or offline records",()=>{
  const html=file("owner.html"),js=file("owner.js");
  assert.doesNotMatch(html,/BP-000182|₱24,580|demo metrics/i);
  assert.match(html,/Offline tablet sales appear after successful sync/);
  assert.match(js,/textContent/);
});
test("Android BP1 private-key compatibility and DER signature are verified",async()=>{
  const original=globalThis.crypto;
  if(!original)globalThis.crypto=webcrypto;
  const pair=await webcrypto.subtle.generateKey(
    {name:"ECDSA",namedCurve:"P-256"},true,["sign","verify"]
  );
  const privatePkcs8=new Uint8Array(await webcrypto.subtle.exportKey("pkcs8",pair.privateKey));
  const spki=new Uint8Array(await webcrypto.subtle.exportKey("spki",pair.publicKey));
  const privateB64=Buffer.from(privatePkcs8).toString("base64");
  const publicB64=Buffer.from(spki).toString("base64");
  const fields=["1","33cc20ad79509a5d","TRIAL","1792270000","28a84904-ef0a-47fa-9531-38c3f193508f","Test Café"];
  const issued=await signBP1(privateB64,publicB64,fields);
  const parts=issued.code.split(".");
  assert.equal(parts[0],"BP1");
  assert.equal(parts.length,3);
  const content=Buffer.from(parts[1],"base64url");
  const der=Buffer.from(parts[2],"base64url");
  assert.equal(content.toString("utf8"),fields.join("|"));
  assert.equal(der[0],0x30);
  const verifierKey=createPublicKey({key:Buffer.from(spki),format:"der",type:"spki"});
  assert.equal(verify("sha256",content,verifierKey,der),true);
  await assert.rejects(()=>signBP1(privateB64,"invalidAndroidPublicKey",fields),/does not match shipped Android/);
  assert.throws(()=>p1363ToDer(new Uint8Array(63)),/Unexpected P-256/);
  if(!original)delete globalThis.crypto;
});


test("owner page and Cloudflare CSP allow only dedicated BrewPOS API host",()=>{
  const html=file("owner.html");
  const headers=readFileSync(new URL("../public/_headers",import.meta.url),"utf8");
  assert.match(html,/connect-src 'self' https:\/\/rfxzbuocxersgbshczbj\.supabase\.co/);
  assert.match(headers,/connect-src 'self' https:\/\/challenges\.cloudflare\.com https:\/\/rfxzbuocxersgbshczbj\.supabase\.co/);
  assert.doesNotMatch(headers,/SUPABASE_SERVICE_ROLE_KEY|BREWPOS_LICENSE_PRIVATE_KEY/);
});

test("server BP1 public key is exactly the Android release verifier key",()=>{
  const android=readFileSync(new URL("../../app/src/main/java/com/brewpos/cafe/LicenseManager.kt",import.meta.url),"utf8");
  const server=readFileSync(new URL("../../supabase/functions/brewpos-owner/index.ts",import.meta.url),"utf8");
  const androidKey=android.match(/PUBLIC_KEY_DER_B64\\s*=\\s*"([^"]+)"/)?.[1];
  const serverKey=server.match(/const expectedSpki\\s*=\\s*"([^"]+)"/)?.[1];
  assert.ok(androidKey,"Android verification public key is defined");
  assert.equal(serverKey,androidKey,"Owner service must match installed Android verifier exactly");
  assert.match(android,/SHA256withECDSA/);
  assert.match(server,/signBP1\\(signingKeyB64, expectedSpki/);
});

test("tampered BP1 tokens cannot pass the Android-compatible signature verifier",async()=>{
  const pair=await webcrypto.subtle.generateKey({name:"ECDSA",namedCurve:"P-256"},true,["sign","verify"]);
  const privateB64=Buffer.from(await webcrypto.subtle.exportKey("pkcs8",pair.privateKey)).toString("base64");
  const spki=Buffer.from(await webcrypto.subtle.exportKey("spki",pair.publicKey));
  const code=(await signBP1(privateB64,spki.toString("base64"),[
    "1","33cc20ad79509a5d","MONTHLY","1792270000",
    "28a84904-ef0a-47fa-9531-38c3f193508f","Café Demo"
  ])).code;
  const [prefix,payload,signature]=code.split(".");
  assert.equal(prefix,"BP1");
  const publicKey=createPublicKey({key:spki,format:"der",type:"spki"});
  assert.equal(verify("sha256",Buffer.from(payload,"base64url"),publicKey,Buffer.from(signature,"base64url")),true);
  const altered=Buffer.from(Buffer.from(payload,"base64url").toString("utf8").replace("MONTHLY","LIFETIME"),"utf8");
  assert.equal(verify("sha256",altered,publicKey,Buffer.from(signature,"base64url")),false);
  await assert.rejects(()=>signBP1("",spki.toString("base64"),[
    "1","33cc20ad79509a5d","TRIAL","1792270000",
    "28a84904-ef0a-47fa-9531-38c3f193508f","Customer"
  ]),/signing key is not configured/);
});
