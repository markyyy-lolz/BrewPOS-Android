"use strict";
// BrewPOS browser client: only public publishable key, never service_role/secret key.
const URLBASE="https://rfxzbuocxersgbshczbj.supabase.co";
const PUBLIC_KEY="sb_publishable_CR-CJKna_rcjQi7gg1CZGQ__DnXI6Lb";
const $=id=>document.getElementById(id);
let token=null, memberships=[];
const headers=(useAuth=true)=>({"apikey":PUBLIC_KEY,...(useAuth&&token?{"Authorization":"Bearer "+token}:{})});
function message(text){$("portalError").textContent=text||"";}
function money(cents){return new Intl.NumberFormat("en-PH",{style:"currency",currency:"PHP"}).format(Number(cents||0)/100);}
async function endpoint(path,options={}) {
  const r=await fetch(URLBASE+path,{...options,headers:{...headers(),...(options.headers||{})},cache:"no-store"});
  if(!r.ok)throw new Error(r.status===401||r.status===403?"Session expired or permission denied":("Cloud request failed ("+r.status+")"));
  return await r.json();
}
async function fetchMemberships(){
 const x=await endpoint("/rest/v1/brew_memberships?select=organization_id,role&is_active=eq.true");
 memberships=Array.isArray(x)?x:[];
 if(!memberships.length)throw new Error("Your staff account is not assigned to a BrewPOS organization. Ask Azurate to provision your membership.");
 const select=$("orgSelect"); select.replaceChildren();
 memberships.forEach((m,i)=>{const option=document.createElement("option");option.value=m.organization_id;option.textContent="Authorized business "+(i+1)+" ("+m.role+")";select.append(option);});
}
async function loadSales(){
 message(""); const org=$("orgSelect").value;
 if(!memberships.some(m=>m.organization_id===org))throw new Error("Unauthorized organization");
 const data=await endpoint("/rest/v1/brew_sales?select=local_receipt_no,total_centavos,payment_method,status,received_at&organization_id=eq."+encodeURIComponent(org)+"&order=received_at.desc&limit=100");
 const arr=Array.isArray(data)?data:[];
 const total=arr.reduce((a,b)=>a+Number(b.total_centavos||0),0);
 $("revenue").textContent=money(total);
 $("ordersCount").textContent=String(arr.length);
 $("average").textContent=money(arr.length?Math.round(total/arr.length):0);
 $("cloudState").textContent="Online";
 $("syncMessage").textContent="Read-only cloud data. Showing the most recent "+arr.length+" synced sale(s); this is not an all-time report.";
 const body=$("salesRows");body.replaceChildren();
 if(arr.length===0){const row=document.createElement("tr"),col=document.createElement("td");col.colSpan=5;col.textContent="No cloud sales yet. Offline orders appear after tablet sync.";row.append(col);body.append(row);return;}
 arr.forEach(s=>{
   const tr=document.createElement("tr");
   [s.local_receipt_no,new Date(s.received_at).toLocaleString("en-PH"),s.payment_method,money(s.total_centavos),s.status].forEach(v=>{
     const td=document.createElement("td");td.textContent=String(v??"");tr.append(td);
   });
   body.append(tr);
 });
}
$("loginForm").addEventListener("submit",async ev=>{
 ev.preventDefault();message("");
 const submit=$("loginForm").querySelector("button");submit.disabled=true;
 const f=new FormData(ev.currentTarget);
 try {
  const r=await fetch(URLBASE+"/auth/v1/token?grant_type=password",{
    method:"POST",cache:"no-store",headers:{"content-type":"application/json","apikey":PUBLIC_KEY},
    body:JSON.stringify({email:String(f.get("email")||""),password:String(f.get("password")||"")})
  });
  if(!r.ok)throw new Error("Invalid credentials or unconfirmed staff email");
  const x=await r.json();
  token=x.access_token;
  if(!token)throw new Error("Cloud login did not return a valid token");
  await fetchMemberships();
  $("loginPanel").hidden=true;$("merchantData").hidden=false;$("signOut").hidden=false;
  await loadSales();
  $("loginForm").reset();
 }catch(e){
  token=null;$("merchantData").hidden=true;$("loginPanel").hidden=false;$("signOut").hidden=true;
  message(e.message||"Could not connect to BrewPOS Cloud");
 }finally{submit.disabled=false;}
});
$("signOut").addEventListener("click",()=>{
 token=null;memberships=[];$("merchantData").hidden=true;$("loginPanel").hidden=false;
 $("signOut").hidden=true;message("Signed out.");
});
$("refresh").addEventListener("click",()=>loadSales().catch(e=>message(e.message)));
$("orgSelect").addEventListener("change",()=>loadSales().catch(e=>message(e.message)));
