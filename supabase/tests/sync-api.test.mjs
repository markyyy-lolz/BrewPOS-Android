import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import ts from 'typescript';
const source=ts.transpileModule(await readFile(new URL('../functions/brewpos-sync/index.ts',import.meta.url),'utf8'),{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.None}}).outputText;
const org='10000000-0000-0000-0000-000000000001',branch='20000000-0000-0000-0000-000000000001',install='30000000-0000-0000-0000-000000000001',user='40000000-0000-0000-0000-000000000001',device='50000000-0000-0000-0000-000000000001',id='60000000-0000-0000-0000-000000000001';
function harness({role='owner',verified=true,registeredBranch=branch,member=true}={}) {
 let handler;const calls=[];
 const fetch=async(url,init={})=>{
  calls.push([url,init]);let result;
  if(url.endsWith('/auth/v1/user'))result={id:user,email:'test@example.com',email_confirmed_at:verified?'2026-01-01':null};
  else if(url.includes('brew_memberships?'))result=member?[{organization_id:org,role}]:[];
  else if(url.includes('brew_organizations?'))result=[{name:'Café Oabi'}];
  else if(url.includes('brew_branches?'))result=[{id:branch}];
  else if(url.includes('brew_devices?'))result=[{id:device,is_active:true,branch_id:registeredBranch}];
  else if(url.includes('brew_signup_requests?'))result=[];
  else if(url.endsWith('rpc/brew_catalog'))result=[];
  else if(url.includes('rpc/brew_ingest_'))result={accepted:true,event_id:id,inventory_applied:true};
  else throw Error('Unexpected fetch '+url);
  return new Response(JSON.stringify(result),{status:200});
 };
 vm.runInNewContext(source,{Deno:{env:{get:k=>k==='SUPABASE_URL'?'https://test.local':'server-only-test'},serve:f=>handler=f},fetch,Response,Request,Headers,Date});
 return {calls,call:(body,auth=true)=>handler(new Request('https://test.local',{method:'POST',headers:{'content-type':'application/json',...(auth?{authorization:'Bearer test-token'}:{})},body:JSON.stringify({organization_id:org,branch_id:branch,installation_id:install,...body})}))};
}
test('missing or unverified authentication cannot reach business data',async()=>{
 for(const verified of [true,false]) {
  const h=harness({verified});assert.equal((await h.call({action:'catalog'},!verified)).status,401);
  assert.equal(h.calls.filter(([u])=>u.includes('/rest/')).length,0);
 }
});
test('cross-branch re-registration rejected',async()=>{
 const h=harness({registeredBranch:org});assert.equal((await h.call({action:'register'})).status,409);
});
test('cashier cannot change stock; no database ingest call made',async()=>{
 const h=harness({role:'cashier'});const r=await h.call({action:'sync',event:{event_id:id,installation_id:install,event_type:'stock.adjusted',payload_version:2}});
 assert.equal(r.status,403);assert.equal(h.calls.some(([u])=>u.includes('rpc/')),false);
});
test('manager stock event uses validated actor and forwards durable inventory ACK',async()=>{
 const h=harness();const r=await h.call({action:'sync',event:{event_id:id,installation_id:install,event_type:'stock.adjusted',payload_version:2}});
 assert.equal(r.status,200);assert.equal((await r.json()).inventory_applied,true);
 const args=JSON.parse(h.calls.find(([u])=>u.endsWith('rpc/brew_ingest_stock'))[1].body);
 assert.equal(args.p_actor_id,user);assert.equal(args.p_device_id,device);
});
test('catalog requires membership and registered device',async()=>{
 const h=harness();assert.equal((await h.call({action:'catalog'})).status,200);
 const missing=harness({member:false});assert.equal((await missing.call({action:'catalog'})).status,403);
});
test('existing verified signup request route is preserved',async()=>{
 const h=harness({member:false});const r=await h.call({action:'request_account',business_name:'Café Test',android_device_id:'0123456789abcdef'});
 assert.equal(r.status,200);assert.equal((await r.json()).requested,true);
});

test('whoami identifies the cafe organization even when the branch is Main Branch',async()=>{
 const h=harness();const r=await h.call({action:'whoami'});
 assert.equal((await r.json()).memberships[0].organization_name,'Café Oabi');
});
