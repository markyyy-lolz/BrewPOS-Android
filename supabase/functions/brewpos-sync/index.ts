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
async function authenticatedUser(req:Request):Promise<{id:string,email:string}|null> {
  const auth=req.headers.get("authorization")||"";
  const token=auth.match(/^Bearer ([A-Za-z0-9_\-.]+)$/i)?.[1];
  if (!token)return null;
  const r=await fetch(`${base}/auth/v1/user`,{headers:{
    "apikey":secret,"authorization":`Bearer ${token}`,"cache-control":"no-store"
  }});
  if(!r.ok)return null;
  const user=await r.json();
  // Registration and sync both require a Supabase-verified email, not just a token.
  return uuid(user?.id) && user?.email_confirmed_at &&
    typeof user.email==="string" && user.email.includes("@")
    ? {id:user.id,email:user.email.trim().toLowerCase()} : null;
}
Deno.serve(async(req)=>{
  if(req.method==="OPTIONS")return new Response(null,{status:204,headers:common});
  if(req.method!=="POST")return answer(405,{error:"POST required"});
  if(!base||!secret)return answer(503,{error:"Cloud service not configured"});
  const identity=await authenticatedUser(req).catch(()=>null);
  if(!identity)return answer(401,{error:"Sign in with a verified BrewPOS email account"});
  const userId=identity.id;
  const length=Number(req.headers.get("content-length")||"0");
  if(length>160000)return answer(413,{error:"Payload too large"});
  let data:any;
  try { const raw=await req.text(); if(raw.length>160000)return answer(413,{error:"Payload too large"});data=JSON.parse(raw) }
  catch {return answer(400,{error:"Invalid JSON"})}
  if(!data || typeof data!=="object")return answer(400,{error:"Invalid request"});
  try {
    const membership=await rows(`brew_memberships?select=organization_id,role&user_id=eq.${userId}&is_active=eq.true`);
    if(data.action==="request_account"){
      if(membership.length)return answer(409,{error:"This user already belongs to a registered BrewPOS business"});
      const business=String(data.business_name||"").trim();
      const device=String(data.android_device_id||"").trim().toLowerCase();
      if(business.length<2||business.length>80||/[|\u0000-\u001f\u007f]/.test(business))
        return answer(400,{error:"Business name must contain 2 to 80 printable characters"});
      if(!/^[0-9a-f]{16}$/.test(device))
        return answer(400,{error:"Android device ID must contain 16 lowercase hex characters"});
      const previous=await rows("brew_signup_requests?select=status&user_id=eq."+userId);
      if(previous.length && previous[0].status==="approved")
        return answer(409,{error:"Account was already approved. Refresh your branch list"});
      const write=await service("brew_signup_requests?on_conflict=user_id",{
        method:"POST",
        headers:{"prefer":"resolution=merge-duplicates,return=representation"},
        body:JSON.stringify({
          user_id:userId,email:identity.email,business_name:business,
          android_device_id:device,status:"pending",
          updated_at:new Date().toISOString()
        })
      });
      if(!write.ok)return answer(503,{error:"Could not save account request. Please retry later."});
      return answer(200,{requested:true,status:"pending",
        message:"Email verified. Waiting for Azurate to approve a signed BP1 license and assign your cloud branch."});
    }
    if(data.action==="whoami"){
      // Only return the current user's authorized branch list.
      const authorized=[];
      for(const m of membership){
        const branch=await rows(`brew_branches?select=id,name,organization_id&organization_id=eq.${m.organization_id}&is_active=eq.true`);
        const org=await rows(`brew_organizations?select=name&id=eq.${m.organization_id}`);
        authorized.push({organization_id:m.organization_id,organization_name:org[0]?.name||"",role:m.role,branches:branch});
      }
      const requests=await rows("brew_signup_requests?select=status,business_name,requested_at&user_id=eq."+userId);
      return answer(200,{memberships:authorized,
        account_request:requests.length ? requests[0] : null});
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
      const existing=await rows(`brew_devices?select=id,is_active,branch_id&organization_id=eq.${data.organization_id}&installation_id=eq.${data.installation_id}`);
      if(existing.length){
        if(existing[0].branch_id!==data.branch_id)return answer(409,{error:"Installation is already bound to another branch"});
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
    if(!["sync","catalog"].includes(data.action))return answer(400,{error:"Unknown action"});
    if(!["owner","manager","cashier"].includes(String(member.role)))return answer(403,{error:"Cashier authorization required"});
    const devices=await rows(`brew_devices?select=id,is_active&organization_id=eq.${data.organization_id}&branch_id=eq.${data.branch_id}&installation_id=eq.${data.installation_id}`);
    if(devices.length!==1||!devices[0].is_active)return answer(403,{error:"Device not registered or revoked"});
    if(data.action==="catalog") {
      const catalog=await service("rpc/brew_catalog",{method:"POST",body:JSON.stringify({
        p_organization_id:data.organization_id,p_branch_id:data.branch_id
      })});
      if(!catalog.ok)return answer(503,{error:"Catalog unavailable; keep the local menu"});
      return answer(200,{products:await catalog.json(),organization_id:data.organization_id,branch_id:data.branch_id});
    }
    if(!data.event||typeof data.event!=="object"||data.event.installation_id!==data.installation_id||!uuid(data.event.event_id))
      return answer(400,{error:"Invalid event"});
    const stock=data.event.event_type==="stock.adjusted";
    if(stock&&!['owner','manager'].includes(String(member.role)))
      return answer(403,{error:"Manager authorization required for inventory changes"});
    if(!["sale.completed","stock.adjusted"].includes(data.event.event_type)||
      !(stock ? data.event.payload_version===2 : [1,2].includes(data.event.payload_version)))
      return answer(400,{error:"Unsupported event type or version"});
    const args:Record<string,unknown>={p_organization_id:data.organization_id,p_branch_id:data.branch_id,
      p_device_id:devices[0].id,p_payload:data.event};
    if(stock)args.p_actor_id=userId;
    const r=await service(stock?"rpc/brew_ingest_stock":"rpc/brew_ingest_sale",{
      method:"POST",body:JSON.stringify(args)
    });
    if(!r.ok){
      if(r.status>=400&&r.status<500)return answer(400,{error:"Event rejected by validation. Keep the local sale pending."});
      return answer(502,{error:"Cloud database did not acknowledge sale. Local event remains pending."});
    }
    const ack=await r.json();
    if(ack?.accepted===true&&ack?.event_id===data.event.event_id)
      return answer(200,{accepted:true,event_id:ack.event_id,duplicate:ack.duplicate===true,inventory_applied:ack.inventory_applied===true});
    return answer(502,{error:"Unexpected acknowledgement; keep local event pending"});
  }catch(_e){
    return answer(503,{error:"Cloud unavailable. Local data remains safe; retry later."});
  }
});
