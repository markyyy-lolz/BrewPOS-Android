import test from 'node:test';
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
const { onRequestPost, onRequestGet } = await import(pathToFileURL(new URL('../functions/api/inquiry.js', import.meta.url).pathname).href);
const base = { name:'Juan Dela Cruz', business:'Coffee Shop', email:'juan@example.com', plan:'Monthly plan', message:'Two tablets' };
const env = { RESEND_API_KEY:'test-key', BREWPOS_TO_EMAIL:'owner@example.com', BREWPOS_FROM_EMAIL:'BrewPOS <contact@example.com>' };
const request=(body,extra={})=>new Request('https://brewpos.pages.dev/api/inquiry',{method:'POST',headers:{'Content-Type':'application/json','Origin':'https://brewpos.pages.dev',...extra},body:JSON.stringify(body)});
const parse=(r)=>r.json();
test('valid inquiry securely submits to email backend',async()=>{
 const old=globalThis.fetch;
 globalThis.fetch=async (url,options)=>{
   assert.equal(url,'https://api.resend.com/emails');
   assert.equal(options.headers.Authorization,'Bearer test-key');
   assert.equal(JSON.parse(options.body).reply_to,'juan@example.com');
   return Response.json({id:'delivery-id'});
 };
 try { const r=await onRequestPost({request:request(base),env}); assert.equal(r.status,200);assert.equal((await parse(r)).ok,true); }
 finally {globalThis.fetch=old;}
});
test('invalid email is rejected',async()=>{
 const r=await onRequestPost({request:request({...base,email:'wrong'}),env});assert.equal(r.status,400);
});
test('missing email credentials fails closed',async()=>{
 const r=await onRequestPost({request:request(base),env:{}});assert.equal(r.status,503);
});
test('cross-site submission is blocked',async()=>{
 const r=await onRequestPost({request:request(base,{'Origin':'https://evil.example'}),env});assert.equal(r.status,403);
});
test('honeypot discards bot submission',async()=>{
 const r=await onRequestPost({request:request({...base,website:'spam'}),env:{}});assert.equal(r.status,200);
});
test('oversized payload is blocked',async()=>{
 const r=await onRequestPost({request:request({...base,message:'x'.repeat(13000)}),env});assert.equal(r.status,413);
});
test('GET request is not accepted',()=>{assert.equal(onRequestGet().status,405)});

test('new plan selections are valid inquiry categories',async()=>{
  for (const plan of ['Offline Basic — monthly','Offline Basic — lifetime','Offline Premium — planned']) {
    const r=await onRequestPost({request:request({...base,plan}),env:{}});
    assert.equal(r.status,503, 'Valid plan should reach email configuration check: '+plan);
  }
});
