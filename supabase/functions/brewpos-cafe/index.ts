// BrewPOS Café Owner Portal API: tenant-scoped owner/manager data access.
// Every request authenticates via Supabase Auth and validates an active membership
// AND an approved, non-expired café subscription. Never ship service-role keys.
const BASE=Deno.env.get("SUPABASE_URL")||"";
const SECRET=Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")||"";
const ALLOWED=new Set(["https://markyyy-lolz.github.io",
  "https://rfxzbuocxersgbshczbj.supabase.co"]);
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
function headers(req:Request):Headers {
  const h=new Headers({
    "content-type":"application/json; charset=utf-8",
    "cache-control":"no-store, max-age=0",
    "x-content-type-options":"nosniff",
    "vary":"Origin",
    "access-control-allow-methods":"POST, OPTIONS",
    "access-control-allow-headers":"authorization, apikey, content-type"
  });
  const origin=req.headers.get("origin")||"";
  if(ALLOWED.has(origin))h.set("access-control-allow-origin",origin);
  return h;
}
function reply(req:Request,status:number,payload:unknown):Response{
  return new Response(JSON.stringify(payload),{status,headers:headers(req)});
}
async function db(path:string,method="GET",body?:unknown):Promise<any>{
  const h=new Headers({"apikey":SECRET,"authorization":"Bearer "+SECRET});
  if(body!==undefined)h.set("content-type","application/json");
  if(method!=="GET")h.set("prefer","return=representation");
  const r=await fetch(BASE+"/rest/v1/"+path,{
    method,headers:h,body:body===undefined?undefined:JSON.stringify(body),cache:"no-store"
  });
  if(!r.ok)throw Error("Cloud database request failed");
  return await r.json();
}
async function rows(path:string):Promise<any[]>{
  const x=await db(path);
  if(!Array.isArray(x))throw Error("Unexpected cloud records");
  return x;
}
async function identity(req:Request):Promise<string|null>{
  const m=(req.headers.get("authorization")||"").match(/^Bearer ([A-Za-z0-9._-]+)$/);
  if(!m)return null;
  const r=await fetch(BASE+"/auth/v1/user",{headers:{
    apikey:SECRET,authorization:"Bearer "+m[1]
  },cache:"no-store"});
  if(!r.ok)return null;
  const u=await r.json();
  return UUID.test(u?.id||"")&&u?.email_confirmed_at?u.id:null;
}
async function memberships(id:string){
  const mem=await rows("brew_memberships?select=organization_id,role,is_active&user_id=eq."+id+"&is_active=eq.true");
  const result=[];
  for(const m of mem){
    if(!["owner","manager"].includes(m.role))continue;
    const a=await rows("brew_customer_accounts?select=id,plan,status,expires_at,organization_id&organization_id=eq."+m.organization_id+"&limit=2");
    const ent=a.find(x=>x.status==="active"&&
      (!x.expires_at||Date.parse(x.expires_at)>Date.now()));
    if(!ent)continue;
    const [org,branches]=await Promise.all([
      rows("brew_organizations?select=id,name&id=eq."+m.organization_id),
      rows("brew_branches?select=id,name,organization_id,is_active&organization_id=eq."+m.organization_id+"&is_active=eq.true&order=name.asc")
    ]);
    if(org.length!==1)continue;
    result.push({organization_id:m.organization_id,organization_name:org[0].name,
      role:m.role,plan:ent.plan,expires_at:ent.expires_at,branches});
  }
  return result;
}
function scopeFor(scope:any[],org:any,branch:any){
  if(typeof org!=="string"||!UUID.test(org)||typeof branch!=="string"||!UUID.test(branch))
    throw Error("Select a valid café branch");
  const member=scope.find(s=>s.organization_id===org&&
    s.branches.some((b:any)=>b.id===branch));
  if(!member)throw Error("This café branch is not authorized for your account");
  return member;
}
const description=(v:unknown,label:string,max:number)=>{
  const s=String(v??"").trim();
  if(s.length<2||s.length>max||/[\u0000-\u001f\u007f]/.test(s))
    throw Error(label+" must be 2–"+max+" printable characters");
  return s;
};
const money=(x:any)=>{
  const n=Number(x);
  if(!Number.isSafeInteger(n)||n<1||n>100000000)
    throw Error("Price must be positive centavos");
  return n;
};
const stock=(v:any)=>{
  const s=String(v??"0").trim();
  if(!/^(?:0|[1-9][0-9]{0,6})(?:\.[0-9]{1,3})?$/.test(s))
    throw Error("Invalid opening stock. Use at most 3 decimal places.");
  return s;
};
async function listProducts(org:string,branch:string){
  return rows("brew_products?select=id,organization_id,branch_id,name,category,price_centavos,stock_quantity,track_stock,active,version,updated_at&organization_id=eq."+
    org+"&branch_id=eq."+branch+"&order=name.asc&limit=500");
}
async function createProduct(org:string,branch:string,input:any){
  const name=description(input.name,"Product",80),
    category=description(input.category,"Category",48),
    price_centavos=money(input.price_centavos);
  if(typeof input.track_stock!=="boolean")throw Error("Stock option is required");
  const products=await listProducts(org,branch);
  if(products.some(p=>String(p.name).toLowerCase()===name.toLowerCase()&&
    String(p.category).toLowerCase()===category.toLowerCase()))
    throw Error("This item and category already exist in the selected branch");
  const result=await db("brew_products?select=id,name", "POST",{
    organization_id:org,branch_id:branch,name,category,price_centavos,
    track_stock:input.track_stock,
    stock_quantity:input.track_stock?stock(input.stock_quantity):"0",active:true,version:1
  });
  if(!Array.isArray(result)||result.length!==1)throw Error("Product was not saved");
  return {created:true,product:result[0]};
}
async function updateProduct(org:string,branch:string,input:any){
  if(!UUID.test(String(input.product_id||"")))throw Error("Select a valid product");
  const version=Number(input.version);
  if(!Number.isSafeInteger(version)||version<1)throw Error("Refresh this product before editing");
  const name=description(input.name,"Product",80),
    category=description(input.category,"Category",48),
    price_centavos=money(input.price_centavos);
  if(typeof input.active!=="boolean"||typeof input.track_stock!=="boolean")
    throw Error("Product visibility and stock options must be selected");
  const existing=await rows("brew_products?select=id,version&organization_id=eq."+org+
    "&branch_id=eq."+branch+"&id=eq."+input.product_id);
  if(existing.length!==1||Number(existing[0].version)!==version)
    throw Error("Product changed on another device. Refresh and try again");
  // No untracked stock overwrite: inventory adjustments must be audited in POS.
  const saved=await db("brew_products?select=id,name,version&organization_id=eq."+org+
    "&branch_id=eq."+branch+"&id=eq."+input.product_id+"&version=eq."+version,
    "PATCH",{name,category,price_centavos,active:input.active,
      track_stock:input.track_stock,version:version+1,updated_at:new Date().toISOString()});
  if(!Array.isArray(saved)||saved.length!==1)
    throw Error("Product was edited elsewhere. Refresh and retry");
  return {updated:true,product:saved[0]};
}
async function snapshot(org:string,branch:string){
  const [products,sales,kiosks,devices,kioskDevices,movements]=await Promise.all([
    listProducts(org,branch),
    rows("brew_sales?select=id,local_receipt_no,total_centavos,payment_method,service_type,status,received_at,created_offline_at&organization_id=eq."+org+"&branch_id=eq."+branch+"&order=received_at.desc&limit=250"),
    rows("brew_kiosk_orders?select=id,order_number,service_type,payment_choice,payment_status,total_centavos,items,created_at,paid_at&organization_id=eq."+org+"&branch_id=eq."+branch+"&order=created_at.desc&limit=150"),
    rows("brew_devices?select=id,display_name,is_active,last_seen_at,created_at&organization_id=eq."+org+"&branch_id=eq."+branch+"&order=created_at.desc&limit=100"),
    rows("brew_kiosk_devices?select=device_id,enabled,paired_at&organization_id=eq."+org+"&branch_id=eq."+branch+"&order=paired_at.desc&limit=100"),
    rows("brew_inventory_movements?select=id,product_id,change_qty,reason,created_at&organization_id=eq."+org+"&branch_id=eq."+branch+"&order=created_at.desc&limit=75")
  ]);
  return {products,sales,kiosk_orders:kiosks,devices,kiosk_devices:kioskDevices,
    movements,limits:{sales:250,kiosk_orders:150,movements:75}};
}
Deno.serve(async(req:Request)=>{
  if(req.method==="OPTIONS")return new Response(null,{status:204,headers:headers(req)});
  if(req.method!=="POST")return reply(req,405,{error:"POST required"});
  if(!BASE||!SECRET)return reply(req,503,{error:"Café portal temporarily unavailable"});
  try{
    const raw=await req.text();
    if(raw.length>16000)return reply(req,413,{error:"Payload too large"});
    let data:any;
    try{data=JSON.parse(raw);}catch{return reply(req,400,{error:"Invalid JSON"});}
    if(!data||typeof data!=="object"||Array.isArray(data))
      return reply(req,400,{error:"Invalid request body"});
    const user=await identity(req);
    if(!user)return reply(req,401,{error:"Sign in with a verified café owner email"});
    const scopes=await memberships(user);
    if(!scopes.length)return reply(req,403,{
      error:"Café account awaiting approval, expired, or missing owner/manager access"
    });
    if(data.action==="session")return reply(req,200,{cafes:scopes});
    const member=scopeFor(scopes,data.organization_id,data.branch_id);
    const org=member.organization_id,branch=data.branch_id;
    if(data.action==="snapshot")
      return reply(req,200,await snapshot(org,branch));
    if(data.action==="catalog_create")
      return reply(req,200,await createProduct(org,branch,data));
    if(data.action==="catalog_update")
      return reply(req,200,await updateProduct(org,branch,data));
    return reply(req,400,{error:"Unknown café portal action"});
  }catch(err){
    const message=err instanceof Error?err.message:"Cloud request failed";
    if(message.includes("database request")||message.includes("Unexpected cloud"))
      return reply(req,503,{error:"BrewPOS database unavailable. Please retry."});
    return reply(req,400,{error:message});
  }
});
