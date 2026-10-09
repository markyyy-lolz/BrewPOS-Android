// BrewPOS hybrid sync API v1 — ALL business routes require a live Supabase Auth access token.
// Runs with verify_jwt=false to support new publishable key/JWT formats;
// EVERY authenticated route explicitly validates the bearer at /auth/v1/user.
// Service-role credentials are read only from server environment and never returned.
const base = Deno.env.get("SUPABASE_URL") || "";
const secret = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
const common = {
  "content-type": "application/json; charset=utf-8",
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "POST, OPTIONS",
  "access-control-allow-headers": "authorization, apikey, content-type, x-client-info",
  "cache-control": "no-store",
  "x-content-type-options": "nosniff",
};
const answer=(status:number, obj:unknown)=>new Response(JSON.stringify(obj),{status,headers:common});
const uuid=(s:unknown):s is string=>typeof s==="string" &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(s);
async function service(path:string,init:RequestInit={}) {
  const headers=new Headers(init.headers);
  headers.set("apikey",secret);headers.set("authorization",`Bearer ${secret}`);
  if (init.body!==undefined)headers.set("content-type","application/json");
  return await fetch(`${base}/rest/v1/${path}`,{...init,headers});
}
async function rows(path:string) {
  const r=await service(path);if(!r.ok)throw new Error("Database query unavailable");
  return await r.json() as Record<string,unknown>[];
}
async function authenticatedUser(req:Request):Promise<string|null> {
  const auth=req.headers.get("authorization")||"";
  const token=auth.match(/^Bearer ([A-Za-z0-9_\-.]+)$/i)?.[1];
  if (!token)return null;
  const r=await fetch(`${base}/auth/v1/user`,{headers:{
    "apikey":secret,"authorization":`Bearer ${token}`,"cache-control":"no-store"
  }});
  if(!r.ok)return null;
  const user=await r.json();
  return uuid(user?.id)?user.id:null;
}
Deno.serve(async(req)=>{
  if(req.method==="OPTIONS")return new Response(null,{status:204,headers:common});
  if(req.method!=="POST")return answer(405,{error:"POST required"});
  if(!base||!secret)return answer(503,{error:"Cloud service not configured"});
  const userId=await authenticatedUser(req).catch(()=>null);
  if(!userId)return answer(401,{error:"Sign in with a confirmed BrewPOS staff account"});
  const length=Number(req.headers.get("content-length")||"0");
  if(length>160000)return answer(413,{error:"Payload too large"});
  let data:any;
  try { const raw=await req.text(); if(raw.length>160000)return answer(413,{error:"Payload too large"});data=JSON.parse(raw) }
  catch {return answer(400,{error:"Invalid JSON"})}
  if(!data || typeof data!=="object")return answer(400,{error:"Invalid request"});
  try {
    const membership=await rows(`brew_memberships?select=organization_id,role&user_id=eq.${userId}&is_active=eq.true`);
    if(data.action==="whoami"){
      // Only return the current user's authorized branch list.
      const authorized=[];
      for(const m of membership){
        const branch=await rows(`brew_branches?select=id,name,organization_id&organization_id=eq.${m.organization_id}&is_active=eq.true`);
        authorized.push({organization_id:m.organization_id,role:m.role,branches:branch});
      }
      return answer(200,{memberships:authorized});
    }
    if(!uuid(data.organization_id)||!uuid(data.branch_id)||!uuid(data.installation_id))
      return answer(400,{error:"Invalid branch or device identity"});
    const member=membership.find(m=>m.organization_id===data.organization_id);
    if(!member)return answer(403,{error:"You are not an active member of this business"});
    const branch=await rows(`brew_branches?select=id&organization_id=eq.${data.organization_id}&id=eq.${data.branch_id}&is_active=eq.true`);
    if(branch.length!==1)return answer(403,{error:"Branch unavailable"});
    if(data.action==="register"){
      if(!["owner","manager"].includes(String(member.role)))return answer(403,{error:"Manager authorization required"});
      const deviceId=data.device_id;
      if(deviceId!==undefined&&!uuid(deviceId))return answer(400,{error:"Invalid device ID"});
      // Idempotent registration for this installation within one tenant.
      const existing=await rows(`brew_devices?select=id,is_active&organization_id=eq.${data.organization_id}&installation_id=eq.${data.installation_id}`);
      if(existing.length){
        if(!existing[0].is_active)return answer(403,{error:"Device was revoked"});
        return answer(200,{device_id:existing[0].id,registered:true});
      }
      const payload={
        organization_id:data.organization_id,branch_id:data.branch_id,installation_id:data.installation_id,
        display_name: String(data.display_name||"BrewPOS Android").slice(0,80)
      };
      const r=await service("brew_devices?select=id",{method:"POST",headers:{"prefer":"return=representation"},body:JSON.stringify(payload)});
      if(!r.ok)return answer(409,{error:"Device registration failed. Check for an existing registration."});
      const created=await r.json();return answer(201,{device_id:created?.[0]?.id,registered:true});
    }
    if(data.action!=="sync")return answer(400,{error:"Unknown action"});
    if(!["owner","manager","cashier"].includes(String(member.role)))return answer(403,{error:"Cashier authorization required"});
    const devices=await rows(`brew_devices?select=id,is_active&organization_id=eq.${data.organization_id}&branch_id=eq.${data.branch_id}&installation_id=eq.${data.installation_id}`);
    if(devices.length!==1||!devices[0].is_active)return answer(403,{error:"Device not registered or revoked"});
    if(!data.event||typeof data.event!=="object"||data.event.installation_id!==data.installation_id||!uuid(data.event.event_id))
      return answer(400,{error:"Invalid event"});
    if(data.event.event_type!=="sale.completed"||data.event.payload_version!==1)
      return answer(400,{error:"Unsupported event type"});
    const r=await service("rpc/brew_ingest_sale",{method:"POST",body:JSON.stringify({
      p_organization_id:data.organization_id,p_branch_id:data.branch_id,
      p_device_id:devices[0].id,p_payload:data.event
    })});
    if(!r.ok){
      if(r.status===409||r.status===400)return answer(400,{error:"Sale rejected by validation. Keep the local sale pending."});
      return answer(502,{error:"Cloud database did not acknowledge sale. Local event remains pending."});
    }
    const ack=await r.json();
    if(ack?.accepted===true&&ack?.event_id===data.event.event_id)
      return answer(200,{accepted:true,event_id:ack.event_id,duplicate:ack.duplicate===true});
    return answer(502,{error:"Unexpected acknowledgement; keep local event pending"});
  }catch(_e){
    return answer(503,{error:"Cloud unavailable. Local data remains safe; retry later."});
  }
});
