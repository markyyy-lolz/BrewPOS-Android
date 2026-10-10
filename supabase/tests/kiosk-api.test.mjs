import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
import ts from 'typescript';
import {webcrypto} from 'node:crypto';

const source=ts.transpileModule(
  await readFile(new URL('../functions/brewpos-kiosk/index.ts',import.meta.url),'utf8'),
  {compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.None}}
).outputText;
const org='10000000-0000-0000-0000-000000000001';
const branch='20000000-0000-0000-0000-000000000001';
const install='30000000-0000-0000-0000-000000000001';
const user='40000000-0000-0000-0000-000000000001';
const device='50000000-0000-0000-0000-000000000001';
const order='60000000-0000-0000-0000-000000000001';
function harness({role='owner',configured=false}={}){
  let handler;const calls=[];
  const mockFetch=async(url,init={})=>{
    calls.push({url,init});
    let data;
    if(url.endsWith('/auth/v1/user'))data={id:user,email_confirmed_at:'2026-01-01'};
    else if(url.includes('brew_memberships?'))data=[{role}];
    else if(url.includes('brew_branches?'))data=[{id:branch}];
    else if(url.includes('brew_devices?'))data=[{id:device,branch_id:branch,is_active:true}];
    else if(url.includes('brew_kiosk_devices?'))data=[{device_id:device,organization_id:org,branch_id:branch,
      enabled:true,brew_devices:{is_active:true,installation_id:install}}];
    else if(url.includes('rpc/brew_catalog'))data=[];
    else if(url.includes('rpc/brew_kiosk_create_order'))data={id:order,number:'K-12',total_centavos:10000,payment_status:'unpaid'};
    else if(url.includes('brew_kiosk_orders?'))data=[{id:order,kiosk_device_id:device,payment_choice:'cashier',payment_status:'unpaid'}];
    else throw Error('Unexpected '+url);
    return new Response(JSON.stringify(data),{status:200});
  };
  vm.runInNewContext(source,{
    Deno:{env:{get:k=>({SUPABASE_URL:'https://test.local',SUPABASE_SERVICE_ROLE_KEY:'server-test',
      ...(configured?{BREWPOS_QRPH_TEST_SECRET_KEY:'sk_test_key',BREWPOS_QRPH_TEST_PUBLIC_KEY:'pk_test_key',BREWPOS_QRPH_TEST_ORGANIZATION_ID:org}:{})})[k]},serve:f=>handler=f},
    fetch:mockFetch,Request,Response,Headers,TextEncoder,Uint8Array,crypto:webcrypto,Date,
    btoa:x=>Buffer.from(x,'binary').toString('base64'),
  });
  async function call(data,headers={}){
    return await handler(new Request('https://test.local',{method:'POST',
      headers:{'content-type':'application/json',...headers},body:JSON.stringify({...data,installation_id:install})}));
  }
  return {calls,call};
}
test('no bearer, no kiosk token: catalog cannot be read',async()=>{
  const h=harness();const r=await h.call({action:'catalog'});
  assert.equal(r.status,403);assert.equal(h.calls.length,0);
});
test('manager pairing requires verified auth and branch membership',async()=>{
  const h=harness({role:'cashier'});
  const r=await h.call({action:'pair',organization_id:org,branch_id:branch},
    {authorization:'Bearer verified-test'});
  assert.equal(r.status,403);
  assert.equal(h.calls.some(x=>x.url.includes('brew_kiosk_devices?on_conflict')),false);
});
test('QR Ph is unavailable without dedicated sandbox tenant credentials',async()=>{
  const h=harness();const r=await h.call({
    action:'order',submission_id:order,service:'Dine-in',payment:'qrph',
    items:[{product_id:order,quantity:1,size:'Regular',sugar:'100%',ice:'Regular',extra_shot:false}]
  },{'x-kiosk-token':'a'.repeat(64)});
  assert.equal(r.status,503);
  assert.equal(h.calls.some(x=>x.url.includes('rpc/brew_kiosk_create_order')),false);
});
test('cashier orders are not marked paid by the kiosk',async()=>{
  const h=harness();
  const r=await h.call({
    action:'order',submission_id:order,service:'Takeout',payment:'cashier',
    items:[{product_id:order,quantity:1,size:'Regular',sugar:'100%',ice:'Regular',extra_shot:false}]
  },{'x-kiosk-token':'a'.repeat(64)});
  assert.equal(r.status,200);
  assert.equal((await r.json()).payment_status,'unpaid');
  assert.equal(h.calls.some(x=>x.url.includes('rpc/brew_kiosk_finalize_order')),false);
});
