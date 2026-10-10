// BrewPOS Android kiosk DEVELOPMENT API. Deploy only with reviewed SQL, approved merchant,
// signed device pairing, and PayMongo test keys. No client receives a service-role/PayMongo key.
const base=Deno.env.get("SUPABASE_URL")||"";
const serviceKey=Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")||"";
const paymongoTestKey=Deno.env.get("BREWPOS_QRPH_TEST_SECRET_KEY")||"";
const paymongoTestPublic=Deno.env.get("BREWPOS_QRPH_TEST_PUBLIC_KEY")||"";
const allowedTestOrg=Deno.env.get("BREWPOS_QRPH_TEST_ORGANIZATION_ID")||"";
const common={
  "content-type":"application/json","access-control-allow-origin":"*",
  "access-control-allow-methods":"POST, OPTIONS",
  "access-control-allow-headers":"authorization, apikey, content-type, x-kiosk-token",
  "cache-control":"no-store",
};
const reply=(status:number,obj:unknown)=>new Response(JSON.stringify(obj),{status,headers:common});
const uuid=(v:unknown):v is string=>typeof v==="string"&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v);
const hex=(bytes:Uint8Array)=>Array.from(bytes,b=>b.toString(16).padStart(2,"0")).join("");
const sha=async(value:string)=>hex(new Uint8Array(await crypto.subtle.digest("SHA-256",new TextEncoder().encode(value))));
async function api(path:string,opts:RequestInit={}) {
  const headers=new Headers(opts.headers);
  headers.set("apikey",serviceKey);headers.set("authorization","Bearer "+serviceKey);
  if(opts.body!==undefined)headers.set("content-type","application/json");
  const r=await fetch(base+"/rest/v1/"+path,{...opts,headers});
  if(!r.ok)throw new Error("Cloud database rejected the request ("+r.status+")");
  const raw=await r.text();
  return raw?JSON.parse(raw):null;
}
async function member(req:Request) {
  const token=req.headers.get("authorization")?.match(/^Bearer ([A-Za-z0-9._-]+)$/i)?.[1];
  if(!token)return null;
  const r=await fetch(base+"/auth/v1/user",{headers:{apikey:serviceKey,authorization:"Bearer "+token}});
  if(!r.ok)return null;
  const person=await r.json();
  if(!uuid(person?.id)||!person?.email_confirmed_at)return null;
  return person.id as string;
}
async function authorized(user:string,org:string,roles:string[]) {
  const r=await api("brew_memberships?select=role&organization_id=eq."+org+
    "&user_id=eq."+user+"&is_active=eq.true");
  return (r as {role:string}[]).some(x=>roles.includes(x.role));
}
async function validBranch(org:string,branch:string) {
  const r=await api("brew_branches?select=id&organization_id=eq."+org+"&id=eq."+branch+"&is_active=eq.true");
  return r.length===1;
}
async function kioskAuth(req:Request,installation:unknown) {
  const credential=req.headers.get("x-kiosk-token")||"";
  if(!/^[0-9a-f]{64}$/.test(credential)||!uuid(installation))return null;
  const digest=await sha(credential);
  const found=await api("brew_kiosk_devices?select=device_id,organization_id,branch_id,enabled,brew_devices!inner(installation_id,is_active)&token_sha256=eq."+digest);
  const kiosk=found?.[0];
  if(!kiosk?.enabled||!kiosk.brew_devices?.is_active||kiosk.brew_devices.installation_id!==installation ||
    !await validBranch(kiosk.organization_id,kiosk.branch_id))return null;
  return kiosk as {device_id:string,organization_id:string,branch_id:string};
}
async function paymongo(path:string,body:unknown,clientKey:string,idempotency:string) {
  const headers:Record<string,string>={
    authorization:"Basic "+btoa(clientKey+":"),
    "content-type":"application/json",
    "idempotency-key":idempotency,
  };
  const r=await fetch("https://api.paymongo.com/v1/"+path,{
    method:"POST",headers,body:JSON.stringify({data:{attributes:body}})
  });
  if(!r.ok)throw new Error("PayMongo test payment initialization failed ("+r.status+")");
  return await r.json();
}
async function verifiedIntent(order:any) {
  if(!paymongoTestKey||order.organization_id!==allowedTestOrg||!order.provider_intent_id)return false;
  const r=await fetch("https://api.paymongo.com/v1/payment_intents/"+encodeURIComponent(order.provider_intent_id),{
    headers:{authorization:"Basic "+btoa(paymongoTestKey+":")}
  });
  if(!r.ok)return false;
  const attrs=(await r.json()).data?.attributes;
  return attrs?.status==="succeeded"&&attrs?.currency==="PHP"&&
    attrs?.amount===order.total_centavos&&attrs?.livemode===false;
}
async function verifyAndSettle(order:any){
  if(order.payment_choice!=="qrph"||order.payment_status!=="awaiting_qrph"||!await verifiedIntent(order))return order;
  const result=await api("rpc/brew_kiosk_finalize_order",{method:"POST",body:JSON.stringify({
    p_order_id:order.id,p_source:"verified_qrph",p_actor_id:null,
    p_tendered:order.total_centavos,p_provider_intent:order.provider_intent_id
  })});
  if(result?.accepted!==true)throw new Error("Payment settlement unavailable");
  order.payment_status="paid";
  return order;
}
Deno.serve(async(req)=>{
  if(req.method==="OPTIONS")return new Response(null,{status:204,headers:common});
  if(req.method!=="POST")return reply(405,{error:"POST required"});
  if(!base||!serviceKey)return reply(503,{error:"BrewPOS kiosk is not configured"});
  const declared=Number(req.headers.get("content-length")||"0");
  if(declared>48000)return reply(413,{error:"Request too large"});
  let data:any;
  try {
    const raw=await req.text();
    if(raw.length>48000)return reply(413,{error:"Request too large"});
    data=JSON.parse(raw);
  } catch {return reply(400,{error:"Invalid request body"})}
  if(!data||typeof data!=="object"||Array.isArray(data))return reply(400,{error:"Invalid request"});
  try {
    if(data.action==="pair"){
      const user=await member(req);
      if(!user)return reply(401,{error:"Verified manager sign-in required"});
      if(!uuid(data.organization_id)||!uuid(data.branch_id)||!uuid(data.installation_id))
        return reply(400,{error:"Invalid pairing identity"});
      if(!await authorized(user,data.organization_id,["owner","manager"])||
         !await validBranch(data.organization_id,data.branch_id))
        return reply(403,{error:"Manager is not authorized for this branch"});
      const existing=await api("brew_devices?select=id,branch_id,is_active&organization_id=eq."+
        data.organization_id+"&installation_id=eq."+data.installation_id);
      if(existing.length && (existing[0].branch_id!==data.branch_id||!existing[0].is_active))
        return reply(409,{error:"Installation belongs to another branch or is revoked"});
      let device=existing[0]?.id;
      if(!device){
        const created=await api("brew_devices?select=id",{
          method:"POST",headers:{"prefer":"return=representation"},
          body:JSON.stringify({organization_id:data.organization_id,branch_id:data.branch_id,
            installation_id:data.installation_id,display_name:"BrewPOS Self-Ordering Kiosk"})
        });
        device=created[0]?.id;
      }
      if(!uuid(device))throw new Error("Pairing registration unavailable");
      const credential=hex(crypto.getRandomValues(new Uint8Array(32)));
      await api("brew_kiosk_devices?on_conflict=device_id",{
        method:"POST",headers:{"prefer":"resolution=merge-duplicates"},
        body:JSON.stringify({device_id:device,organization_id:data.organization_id,
          branch_id:data.branch_id,token_sha256:await sha(credential),enabled:true,paired_at:new Date().toISOString()})
      });
      return reply(200,{paired:true,kiosk_token:credential});
    }
    if(data.action==="cashier_orders"||data.action==="cashier_pay"){
      const user=await member(req);
      if(!user)return reply(401,{error:"Verified cashier sign-in required"});
      if(!uuid(data.organization_id)||!uuid(data.branch_id)||!uuid(data.installation_id)
        ||!await authorized(user,data.organization_id,["owner","manager","cashier"])
        ||!await validBranch(data.organization_id,data.branch_id))
        return reply(403,{error:"Cashier not authorized for this branch"});
      const known=await api("brew_devices?select=id&organization_id=eq."+data.organization_id+
        "&branch_id=eq."+data.branch_id+"&installation_id=eq."+data.installation_id+"&is_active=eq.true");
      if(known.length!==1)return reply(403,{error:"Cashier device is not registered"});
      if(data.action==="cashier_orders"){
        const rows=await api("brew_kiosk_orders?select=id,order_number,items,total_centavos,service_type,payment_choice,payment_status,created_at"+
          "&organization_id=eq."+data.organization_id+"&branch_id=eq."+data.branch_id+
          "&order=created_at.desc&limit=80");
        return reply(200,{orders:rows});
      }
      if(!uuid(data.order_id)||!Number.isSafeInteger(data.tendered_centavos) || data.tendered_centavos<0)
        return reply(400,{error:"Invalid cash payment"});
      const order=(await api("brew_kiosk_orders?select=*&id=eq."+data.order_id+
        "&organization_id=eq."+data.organization_id+"&branch_id=eq."+data.branch_id))[0];
      if(!order||order.payment_choice!=="cashier")return reply(409,{error:"Not an unpaid cashier order"});
      const result=await api("rpc/brew_kiosk_finalize_order",{method:"POST",body:JSON.stringify({
        p_order_id:order.id,p_source:"cashier",p_actor_id:user,
        p_tendered:data.tendered_centavos,p_provider_intent:null
      })});
      return reply(200,result);
    }
    const kiosk=await kioskAuth(req,data.installation_id);
    if(!kiosk)return reply(403,{error:"Unpaired or revoked kiosk device"});
    if(data.action==="catalog"){
      const catalog=await api("rpc/brew_catalog",{method:"POST",body:JSON.stringify({
        p_organization_id:kiosk.organization_id,p_branch_id:kiosk.branch_id
      })});
      return reply(200,{products:catalog.filter((x:any)=>x.active)});
    }
    if(data.action==="order"){
      if(!uuid(data.submission_id)||!["Dine-in","Takeout"].includes(data.service)
        ||!["cashier","qrph"].includes(data.payment)
        ||!Array.isArray(data.items)||data.items.length<1||data.items.length>40)
        return reply(400,{error:"Invalid order or payment choice"});
      // QR Ph is deliberately disabled for unprovisioned/real merchant tenants.
      if(data.payment==="qrph"&&(!paymongoTestKey||!paymongoTestPublic||
        kiosk.organization_id!==allowedTestOrg))
        return reply(503,{error:"QR Ph not activated for this café. Select Pay at Cashier."});
      const result=await api("rpc/brew_kiosk_create_order",{method:"POST",body:JSON.stringify({
        p_device_id:kiosk.device_id,p_submission_id:data.submission_id,
        p_service:data.service,p_payment:data.payment,p_items:data.items
      })});
      if(data.payment==="cashier")return reply(200,result);
      const order=(await api("brew_kiosk_orders?select=*&id=eq."+result.id))[0];
      if(!order||order.kiosk_device_id!==kiosk.device_id)throw new Error("Order unavailable");
      if(order.provider_intent_id && order.provider_qr_image)
        return reply(200,{...result,qr_image:order.provider_qr_image,payment_status:order.payment_status});
      const stable="brewpos-kiosk-"+order.id;
      const intent=await paymongo("payment_intents",{
        amount:order.total_centavos,currency:"PHP",payment_method_allowed:["qrph"],
        description:"BrewPOS order K-"+order.order_number
      },paymongoTestKey,stable+"-intent");
      const intentId=intent?.data?.id;
      const clientKey=intent?.data?.attributes?.client_key;
      if(!intentId||!clientKey)throw new Error("QR Ph payment intent unavailable");
      const method=await paymongo("payment_methods",{type:"qrph"},
        paymongoTestPublic,stable+"-method");
      const methodId=method?.data?.id;
      if(!methodId)throw new Error("QR Ph method unavailable");
      const attached=await paymongo("payment_intents/"+encodeURIComponent(intentId)+"/attach",
        {payment_method:methodId,client_key:clientKey},paymongoTestPublic,stable+"-attach");
      const qr=attached?.data?.attributes?.next_action?.code?.image_url;
      if(typeof qr!=="string"||!qr.startsWith("data:image/"))throw new Error("QR Ph image unavailable");
      await api("brew_kiosk_orders?id=eq."+order.id+"&kiosk_device_id=eq."+kiosk.device_id,{
        method:"PATCH",headers:{"prefer":"return=minimal"},body:JSON.stringify({
          provider_intent_id:intentId,provider_qr_image:qr,payment_status:"awaiting_qrph"
        })
      });
      return reply(200,{...result,qr_image:qr,payment_status:"awaiting_qrph"});
    }
    if(data.action==="status"){
      if(!uuid(data.order_id))return reply(400,{error:"Invalid order"});
      const order=(await api("brew_kiosk_orders?select=*&id=eq."+data.order_id+
        "&kiosk_device_id=eq."+kiosk.device_id))[0];
      if(!order)return reply(404,{error:"Order not found"});
      await verifyAndSettle(order);
      return reply(200,{payment_status:order.payment_status,number:"K-"+order.order_number});
    }
    return reply(400,{error:"Unknown kiosk action"});
  }catch(_error){
    return reply(503,{error:"Kiosk cloud unavailable. No payment has been assumed; contact the cashier."});
  }
});
