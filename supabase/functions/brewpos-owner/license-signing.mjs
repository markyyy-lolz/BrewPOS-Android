/**
 * Pure ECDSA P-256 BP1 signer used only from the server-side owner function.
 * No private key is committed. Cross-runtime (Deno + Node) for signature tests.
 */
export const decodeBase64 = s => Uint8Array.from(atob(s), c => c.charCodeAt(0));
export const base64url = bytes =>
  btoa(String.fromCharCode(...bytes)).replace(/=/g,"").replace(/\+/g,"-").replace(/\//g,"_");
function asn1Integer(src) {
  let start=0;
  while (start < src.length-1 && src[start]===0) start++;
  const core=src.subarray(start);
  const prefix=(core[0]&0x80) ? [0] : [];
  return new Uint8Array([2,core.length+prefix.length,...prefix,...core]);
}
export function p1363ToDer(raw) {
  if (raw.length!==64)throw Error("Unexpected P-256 signature length");
  const r=asn1Integer(raw.subarray(0,32));
  const s=asn1Integer(raw.subarray(32,64));
  return new Uint8Array([0x30,r.length+s.length,...r,...s]);
}
export async function signBP1(pkcs8Base64, expectedSpki, fields) {
  if (!pkcs8Base64)throw Error("Owner signing key is not configured");
  if (!Array.isArray(fields)||fields.length!==6||fields[0]!=="1"||
      fields.some(x=>typeof x!=="string"||x.includes("|")))throw Error("Bad activation payload");
  const privateKey=await crypto.subtle.importKey(
    "pkcs8",decodeBase64(pkcs8Base64),{name:"ECDSA",namedCurve:"P-256"},true,["sign"]
  );
  const jwk=await crypto.subtle.exportKey("jwk",privateKey);
  if(!jwk.x||!jwk.y)throw Error("Missing P-256 public coordinates");
  const publicKey=await crypto.subtle.importKey(
    "jwk",{kty:"EC",crv:"P-256",x:jwk.x,y:jwk.y,key_ops:["verify"],ext:true},
    {name:"ECDSA",namedCurve:"P-256"},true,["verify"]
  );
  const spki=new Uint8Array(await crypto.subtle.exportKey("spki",publicKey));
  const actual=btoa(String.fromCharCode(...spki));
  if(actual!==expectedSpki)throw Error("Signing key does not match shipped Android public key");
  const raw=new TextEncoder().encode(fields.join("|"));
  const signature=new Uint8Array(await crypto.subtle.sign(
    {name:"ECDSA",hash:"SHA-256"},privateKey,raw
  ));
  const code="BP1."+base64url(raw)+"."+base64url(p1363ToDer(signature));
  return {code,raw,spki};
}
