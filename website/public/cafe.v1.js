"use strict";
// BrewPOS Café Owner Portal. Never persist a JWT or secret outside page memory.
const CLOUD="https://rfxzbuocxersgbshczbj.supabase.co";
const PUBLISHABLE="sb_publishable_CR-CJKna_rcjQi7gg1CZGQ__DnXI6Lb";
const el=id=>document.getElementById(id);
const state={jwt:null,expiresAt:0,email:"",cafes:[],org:null,branch:null,tab:"dashboard",data:null,serial:0};
const php=c=>new Intl.NumberFormat("en-PH",{style:"currency",currency:"PHP"}).format(Number(c||0)/100);
const date=v=>v?new Date(v).toLocaleString("en-PH",{dateStyle:"medium",timeStyle:"short",timeZone:"Asia/Manila"}):"—";
const daysUntil=v=>v?Math.ceil((new Date(v).getTime()-Date.now())/86400000):null;
const clean=v=>String(v??"");
function text(id,value){el(id).textContent=clean(value);}
function showMessage(id,message,type="info"){
  const node=el(id);
  node.textContent=message||"";node.className="message"+(type==="info"?"":" "+type);
  node.hidden=!message;
}
function element(tag,textValue,cls){
  const n=document.createElement(tag);
  if(cls)n.className=cls;
  if(textValue!==undefined)n.textContent=clean(textValue);
  return n;
}
function empty(container,title,description){
  container.replaceChildren();
  const node=element("div",undefined,"empty-state");
  node.append(element("strong",title));
  if(description)node.append(element("p",description));
  container.append(node);
}
async function auth(path,data){
  const r=await fetch(CLOUD+"/auth/v1/"+path,{
    method:"POST",cache:"no-store",
    headers:{"content-type":"application/json","apikey":PUBLISHABLE},
    body:JSON.stringify(data)
  });
  let body={};try{body=await r.json()}catch{}
  if(!r.ok){
    if(r.status===429)throw Error("Too many login attempts. Try again in a few minutes.");
    if(r.status===422)throw Error("Email code unavailable. Contact Azurate Support.");
    throw Error(clean(body.msg||body.error_description||body.error||"Could not complete email login."));
  }
  return body;
}
async function cafe(action,data={}){
  if(!state.jwt||Date.now()>=state.expiresAt-10000){
    signOut();
    throw Error("Your secure session expired. Sign in again.");
  }
  const r=await fetch(CLOUD+"/functions/v1/brewpos-cafe",{
    method:"POST",cache:"no-store",
    headers:{"content-type":"application/json","apikey":PUBLISHABLE,
      authorization:"Bearer "+state.jwt},
    body:JSON.stringify({action,...data})
  });
  let response={};try{response=await r.json()}catch{}
  if(!r.ok){
    if(r.status===401){signOut();throw Error("Session expired. Please sign in again.");}
    throw Error(clean(response.error||"Café Cloud is not available right now."));
  }
  return response;
}
function signOut(){
  state.jwt=null;state.expiresAt=0;state.cafes=[];state.org=null;
  state.branch=null;state.data=null;state.serial++;
  el("workspace").hidden=true;el("loginScreen").hidden=false;
  el("verifyForm").hidden=true;el("loginForm").hidden=false;
  el("loginForm").reset();el("verifyForm").reset();
  showMessage("authStatus","");
}
function currentCafe(){return state.cafes.find(x=>x.organization_id===state.org)??null}
function selectedBranch(){return currentCafe()?.branches.find(x=>x.id===state.branch)??null}
function scope(){return {organization_id:state.org,branch_id:state.branch}}
function setupBranches(){
  const options=el("branchSelect");options.replaceChildren();
  for(const c of state.cafes){
    for(const b of c.branches){
      const opt=element("option",c.organization_name+" / "+b.name);
      opt.value=c.organization_id+"|"+b.id;options.append(opt);
    }
  }
  if(!state.org){
    const first=state.cafes.find(c=>c.branches.length);
    if(first){state.org=first.organization_id;state.branch=first.branches[0].id;}
  }
  options.value=state.org+"|"+state.branch;
  text("sidebarCafe",currentCafe()?.organization_name||"Your café");
  text("sidebarBranch",selectedBranch()?.name||"No active branch");
  text("accountCafe",currentCafe()?.organization_name||"—");
  text("accountBranch",selectedBranch()?.name||"—");
  text("accountRole",currentCafe()?.role||"—");
  text("accountEmail",state.email);
  text("planName","BREWPOS "+(currentCafe()?.plan||"CLOUD"));
  text("planExpiry",currentCafe()?.expires_at?date(currentCafe().expires_at):"Lifetime access");
  text("planDetail",currentCafe()?.expires_at?
    "Your cloud subscription is active until "+date(currentCafe().expires_at)+".":"Your café has an approved lifetime subscription.");
}
const tabMeta={
  dashboard:["Overview","GOOD COFFEE. SMARTER BUSINESS.","Your café at a glance.","A clearer picture of everything happening at your counter."],
  menu:["Menu manager","YOUR CAFÉ'S DIGITAL MENU","Make every cup count.","Create and manage the menu customers see on your kiosk."],
  inventory:["Inventory","STOCK & MOVEMENT VISIBILITY","Stay on top of stock.","Follow your tracked items and changes safely."],
  kiosk:["Kiosk orders","SELF-ORDERING EXPERIENCE","Every self-order matters.","Track real kiosk order and payment statuses."],
  sales:["Sales history","CLOUD RECEIPTS & REPORTS","See the full picture.","Your most recently synced café receipts."],
  devices:["Devices","CONNECTED COUNTERS & KIOSKS","Your connected team.","All registered devices in your selected branch."],
  subscription:["My subscription","YOUR BREWPOS CLOUD PLAN","You're in good hands.","Account permissions, expiry and security."]
};
function openTab(name){
  if(!(name in tabMeta))return;
  state.tab=name;
  for(const section of document.querySelectorAll(".view"))section.hidden=section.id!==name;
  for(const button of document.querySelectorAll("[data-tab]")){
    button.classList.toggle("active",button.dataset.tab===name);
  }
  text("crumb",tabMeta[name][0]);text("pageEyebrow",tabMeta[name][1]);
  text("pageTitle",tabMeta[name][2]);text("pageSubtitle",tabMeta[name][3]);
  el("sidebar").classList.remove("open");
  if(state.data)renderAll();
  window.scrollTo({top:0,behavior:"smooth"});
}
async function refresh(showToast=false){
  if(!state.org||!state.branch)return;
  const token=++state.serial;
  el("refresh").disabled=true;
  try{
    const data=await cafe("snapshot",scope());
    if(token!==state.serial)return;
    state.data=data;setupBranches();renderAll();
    if(showToast)showMessage("toast","Updated from your café's live BrewPOS Cloud.","success");
  }catch(error){
    if(token===state.serial)showMessage("toast",error.message||"Could not refresh café data","error");
  }finally{el("refresh").disabled=false;}
}
function tableEmpty(tbody,colspan,message){
  const node=el(tbody);node.replaceChildren();
  const tr=element("tr"),td=element("td",message,"table-empty");
  td.colSpan=colspan;tr.append(td);node.append(tr);
}
function cell(tr,value){
  const td=element("td",value);
  tr.append(td);return td;
}
function drawTable(tbody,rows,values,emptyMsg){
  const target=el(tbody);target.replaceChildren();
  if(!rows.length){tableEmpty(tbody,values.length,emptyMsg);return;}
  for(const row of rows){
    const tr=element("tr");
    for(const get of values)cell(tr,get(row));
    target.append(tr);
  }
}
function renderDashboard(){
  const d=state.data;if(!d)return;
  const sales=d.sales||[],products=d.products||[],kiosks=d.kiosk_orders||[];
  const revenue=sales.filter(s=>s.status!=="void"&&s.status!=="cancelled")
    .reduce((sum,s)=>sum+Number(s.total_centavos||0),0);
  text("statRevenue",php(revenue));text("statSales",sales.length);
  text("statKiosk",kiosks.length);
  text("statProducts",products.filter(p=>p.active&&(!p.track_stock||Number(p.stock_quantity)>0)).length);
  drawTable("latestSales",sales.slice(0,6),[
    r=>r.local_receipt_no,dateField=>date(dateField.received_at),
    r=>r.payment_method,r=>php(r.total_centavos)
  ],"No synced sales yet. Android cashier transactions will appear here after cloud sync.");
  const chart=el("salesBars");chart.replaceChildren();
  const fmt=x=>new Date(x).toLocaleDateString("sv-SE",{timeZone:"Asia/Manila"});
  const days=Array.from({length:7},(_,i)=>{
    const when=new Date(Date.now()-(6-i)*86400000);
    return {key:fmt(when),day:when.toLocaleDateString("en-PH",{weekday:"short",timeZone:"Asia/Manila"}),amount:0};
  });
  for(const s of sales){
    const item=days.find(d=>d.key===fmt(s.received_at));
    if(item&&s.status!=="cancelled"&&s.status!=="void")item.amount+=Number(s.total_centavos||0);
  }
  const max=Math.max(1,...days.map(x=>x.amount));
  for(const day of days){
    const col=element("div",undefined,"sales-bar");
    const area=element("div",undefined,"sales-bar-area");
    const bar=element("div",undefined,"sales-bar-fill");
    bar.style.height=Math.max(5,(day.amount/max)*122)+"px";
    bar.title=day.day+": "+php(day.amount);
    area.append(bar);col.append(area,element("small",day.day),element("strong",day.amount?php(day.amount):"—"));
    chart.append(col);
  }
  const insights=el("insights");insights.replaceChildren();
  const low=products.filter(p=>p.active&&p.track_stock&&Number(p.stock_quantity)<=5).length;
  const awaiting=kiosks.filter(k=>k.payment_status==="unpaid").length;
  const entries=[
    ["☕",products.length+" menu products",products.length?"Products on this branch's cloud catalog":"Create your first product in Menu manager"],
    ["▣",awaiting+" awaiting cashier payment",kiosks.length?"Payment must be confirmed in cashier app":"Kiosk orders will appear as customers order"],
    ["▤",low+" low/out-of-stock items",low?"Review stock in Android manager inventory":"Nothing needs immediate stock attention"]
  ];
  for(const [emoji,title,desc] of entries){
    const node=element("div",undefined,"insight");
    node.append(element("span",emoji,"insight-icon"));
    const detail=element("div");detail.append(element("strong",title),element("small",desc));node.append(detail);
    insights.append(node);
  }
}
function productIcon(category){
  const c=clean(category).toLowerCase();
  return c.includes("pastr")?"🥐":c.includes("food")||c.includes("sandwich")?"🥪":
    c.includes("milk")||c.includes("non")?"🥤":
    c.includes("tea")||c.includes("matcha")?"🍵":"☕";
}
function openingField(){
  const editing=Boolean(el("productId").value);
  el("openingStockWrap").hidden=!el("trackStock").checked||editing;
  el("visibleWrap").hidden=!editing;
}
function clearProduct(){
  el("productForm").reset();
  el("productId").value="";el("productVersion").value="";
  el("productCategory").value="Coffee";el("openingStock").value="0";
  el("menuFormTitle").textContent="Add a menu item";
  el("saveProduct").textContent="＋ Save menu item";openingField();
}
function chooseProduct(p){
  el("productId").value=p.id;
  el("productVersion").value=p.version;
  el("productName").value=p.name;
  el("productCategory").value=p.category;
  el("productPrice").value=(Number(p.price_centavos)/100).toFixed(2);
  el("trackStock").checked=p.track_stock===true;
  el("productActive").checked=p.active===true;
  text("menuFormTitle","Edit menu item");
  text("saveProduct","Save changes");
  openingField();
  document.querySelector(".menu-form-panel")?.scrollIntoView({behavior:"smooth",block:"start"});
}
function productCentavos(){
  const input=el("productPrice").value.trim();
  if(!/^[0-9]{1,7}(?:\.[0-9]{1,2})?$/.test(input))
    throw Error("Enter a valid price in pesos, e.g. 120.00");
  const [whole,decimal=""]=input.split(".");
  const n=Number(whole)*100+Number(decimal.padEnd(2,"0"));
  if(!Number.isSafeInteger(n)||n<1||n>100000000)
    throw Error("Price must be between ₱0.01 and ₱1,000,000");
  return n;
}
async function saveProduct(e){
  e.preventDefault();
  const button=el("saveProduct");button.disabled=true;
  try{
    const productId=el("productId").value;
    const payload={...scope(),
      name:el("productName").value.trim(),category:el("productCategory").value.trim(),
      price_centavos:productCentavos(),track_stock:el("trackStock").checked
    };
    if(productId){
      await cafe("catalog_update",{...payload,product_id:productId,
        version:Number(el("productVersion").value),active:el("productActive").checked});
      showMessage("toast","Menu item updated. Your kiosk can refresh to see the change.","success");
    }else{
      await cafe("catalog_create",{...payload,stock_quantity:payload.track_stock?
        el("openingStock").value.trim():"0"});
      showMessage("toast","Menu item saved to your café branch.","success");
    }
    clearProduct();await refresh(false);
  }catch(error){showMessage("toast",error.message||"Unable to save menu item","error")}
  finally{button.disabled=false;}
}
function renderMenu(){
  const products=state.data?.products||[];
  text("productCount",products.length+" products in this branch");
  const selected=el("categoryFilter").value||"All";
  const categories=["All",...new Set(products.map(p=>p.category).filter(Boolean))];
  const select=el("categoryFilter");select.replaceChildren();
  for(const name of categories){
    const opt=element("option",name==="All"?"All categories":name);
    opt.value=name;select.append(opt);
  }
  select.value=categories.includes(selected)?selected:"All";
  const query=el("productSearch").value.trim().toLowerCase();
  const list=products.filter(p=>(select.value==="All"||p.category===select.value)&&
    (p.name.toLowerCase().includes(query)||p.category.toLowerCase().includes(query)));
  const grid=el("productGrid");grid.replaceChildren();
  if(!list.length){empty(grid,"Your menu is ready to grow.",products.length?
    "No products match the current filter.":"Add your first item using the form to the left.");return;}
  for(const p of list){
    const card=element("article",undefined,"product-card");
    card.append(element("div",productIcon(p.category),"product-art"),
      element("div",p.name,"product-title"),element("div",p.category,"product-category"));
    if(!p.active||p.track_stock&&Number(p.stock_quantity)<=0)
      card.append(element("div",!p.active?"Hidden from kiosk":"Sold out","product-soldout"));
    const foot=element("div",undefined,"product-foot");
    foot.append(element("strong",php(p.price_centavos)));
    const button=element("button","Edit");button.type="button";
    button.addEventListener("click",()=>chooseProduct(p));foot.append(button);
    card.append(foot);grid.append(card);
  }
}
function renderInventory(){
  const d=state.data||{},products=d.products||[],tracked=products.filter(p=>p.track_stock);
  text("stockTracked",tracked.length);
  text("stockLow",tracked.filter(p=>Number(p.stock_quantity)>0&&Number(p.stock_quantity)<=5).length);
  text("stockOut",tracked.filter(p=>Number(p.stock_quantity)<=0).length);
  drawTable("stockRows",products,[
    p=>p.name,p=>p.category,p=>p.track_stock?clean(p.stock_quantity):"Not tracked",
    p=>!p.active?"Hidden":!p.track_stock?"Made to order":
      Number(p.stock_quantity)<=0?"Sold out":Number(p.stock_quantity)<=5?"Low stock":"Available"
  ],"No products yet. Add real items from Menu manager.");
  const byId=new Map(products.map(p=>[p.id,p.name]));
  drawTable("movementRows",d.movements||[],[
    m=>date(m.created_at),m=>byId.get(m.product_id)||"Product",
    m=>(Number(m.change_qty)>0?"+":"")+m.change_qty,m=>m.reason
  ],"No cloud stock movements recorded for this branch.");
}
function renderKiosk(){
  const grid=el("kioskCards");grid.replaceChildren();
  const orders=state.data?.kiosk_orders||[];
  if(!orders.length){empty(grid,"No self-orders yet.","Orders from the paired Android kiosk will appear here.");return;}
  for(const o of orders){
    const card=element("article",undefined,"order-card");
    const head=element("div",undefined,"order-card-head");
    head.append(element("strong","K-"+o.order_number));
    head.append(element("span",o.payment_status,
      "pill"+(o.payment_status==="paid"?" paid":o.payment_status==="cancelled"?" cancelled":"")));
    card.append(head,element("p",date(o.created_at)+" · "+o.service_type),
      element("p",o.payment_choice==="cashier"?"Pay at Cashier":"QR Ph payment"),
      element("strong",php(o.total_centavos)));
    if(Array.isArray(o.items)&&o.items.length){
      const list=element("ul");
      for(const item of o.items){
        const li=element("li",(item.quantity||1)+"× "+clean(item.name||"Menu item")+
          (item.options?" · "+clean(item.options):""));
        list.append(li);
      }
      card.append(list);
    }
    grid.append(card);
  }
}
function renderSales(){
  drawTable("salesRows",state.data?.sales||[],[
    s=>s.local_receipt_no,s=>date(s.received_at),s=>s.service_type,
    s=>s.payment_method,s=>s.status,s=>php(s.total_centavos)
  ],"No sales have been synchronized for this café yet.");
}
function renderDevices(){
  const d=state.data||{};
  for(const [target,list,icon,make] of [
    ["cashierDevices",d.devices||[],"📱",dev=>[dev.display_name,
      dev.is_active?"Active":"Disabled",dev.last_seen_at?"Last sync "+date(dev.last_seen_at):"Never synced"]],
    ["kioskDevices",d.kiosk_devices||[],"▣",dev=>["Self-Ordering Kiosk",
      dev.enabled?"Paired":"Disabled","Paired "+date(dev.paired_at)]]
  ]){
    const node=el(target);node.replaceChildren();
    if(!list.length){empty(node,"No devices yet.","Use the Android POS or kiosk to pair your devices.");continue;}
    for(const record of list){
      const [name,status,sub]=make(record);
      const item=element("div",undefined,"device");item.append(element("span",icon,"device-icon"));
      const details=element("div");details.append(element("strong",name),element("small",sub));
      item.append(details,element("span",status,"pill"+(status==="Active"||status==="Paired"?" paid":"")));
      node.append(item);
    }
  }
}
function renderAll(){
  if(!state.data)return;
  renderDashboard();renderMenu();renderInventory();renderKiosk();renderSales();renderDevices();setupBranches();
}
function safeCsv(value){
  let s=clean(value);
  if(/^[\s]*[=+\-@]/.test(s))s="'"+s;
  return '"'+s.replaceAll('"','""')+'"';
}
function exportSales(){
  const rows=state.data?.sales||[];
  if(!rows.length){showMessage("toast","No synchronized sales to export.","error");return;}
  const lines=[["Receipt","Received At (UTC)","Service","Payment","Status","Total PHP"],
    ...rows.map(s=>[s.local_receipt_no,s.received_at,s.service_type,s.payment_method,
      s.status,(Number(s.total_centavos)/100).toFixed(2)])];
  const csv="\uFEFF"+lines.map(row=>row.map(safeCsv).join(",")).join("\r\n");
  const blob=new Blob([csv],{type:"text/csv;charset=utf-8"});
  const url=URL.createObjectURL(blob);
  const link=document.createElement("a");link.href=url;link.download="BrewPOS-Cafe-Recent-Synced-Sales.csv";
  document.body.append(link);link.click();link.remove();
  setTimeout(()=>URL.revokeObjectURL(url),1000);
}
el("loginForm").addEventListener("submit",async e=>{
  e.preventDefault();
  const button=el("sendCode"),email=el("loginEmail").value.trim().toLowerCase();
  showMessage("authStatus","");
  if(!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)){showMessage("authStatus","Enter a valid café account email.","error");return;}
  button.disabled=true;
  try{
    await auth("otp",{email,create_user:false});
    state.email=email;text("sentTo",email);
    el("loginForm").hidden=true;el("verifyForm").hidden=false;
    el("emailCode").focus();
    showMessage("authStatus","A verification code has been requested. Check your email.","success");
  }catch(error){showMessage("authStatus",error.message,"error")}
  finally{button.disabled=false;}
});
el("verifyForm").addEventListener("submit",async e=>{
  e.preventDefault();
  const button=el("verifyCode"),token=el("emailCode").value.trim();
  if(!/^[0-9]{6,8}$/.test(token)){showMessage("authStatus","Enter the full 6–8 digit verification code.","error");return;}
  button.disabled=true;
  try{
    const account=await auth("verify",{type:"email",email:state.email,token});
    if(!account.access_token)throw Error("Email verification failed. Try a new code.");
    state.jwt=account.access_token;
    state.expiresAt=Date.now()+Math.max(60,Number(account.expires_in)||3600)*1000;
    const session=await cafe("session");
    if(!Array.isArray(session.cafes)||!session.cafes.some(c=>c.branches?.length))
      throw Error("Your café is not approved yet or your account lacks owner/manager access.");
    state.cafes=session.cafes;state.org=null;state.branch=null;
    setupBranches();
    el("loginScreen").hidden=true;el("workspace").hidden=false;
    openTab("dashboard");await refresh(true);
  }catch(error){
    showMessage("authStatus",error.message||"Café login failed","error");
    state.jwt=null;state.expiresAt=0;
  }finally{button.disabled=false;}
});
el("changeEmail").addEventListener("click",()=>{
  el("verifyForm").hidden=true;el("loginForm").hidden=false;
  el("verifyForm").reset();showMessage("authStatus","");
});
el("branchSelect").addEventListener("change",()=>{
  const parts=el("branchSelect").value.split("|");
  state.org=parts[0];state.branch=parts[1];state.data=null;
  clearProduct();setupBranches();refresh(true);
});
for(const nav of document.querySelectorAll("[data-tab]"))
  nav.addEventListener("click",()=>openTab(nav.dataset.tab));
for(const button of document.querySelectorAll("[data-go]"))
  button.addEventListener("click",()=>openTab(button.dataset.go));
for(const button of document.querySelectorAll("[data-refresh]"))
  button.addEventListener("click",()=>refresh(true));
el("navToggle").addEventListener("click",()=>el("sidebar").classList.toggle("open"));
el("signOut").addEventListener("click",signOut);
el("refresh").addEventListener("click",()=>refresh(true));
el("goMenu").addEventListener("click",()=>openTab("menu"));
el("productForm").addEventListener("submit",saveProduct);
el("resetProduct").addEventListener("click",clearProduct);
el("trackStock").addEventListener("change",openingField);
el("productSearch").addEventListener("input",renderMenu);
el("categoryFilter").addEventListener("change",renderMenu);
el("exportSales").addEventListener("click",exportSales);
// Refresh kiosk payment states only while that view is visible; no fabricated "paid" events.
setInterval(()=>{
  if(state.jwt&&state.tab==="kiosk"&&document.visibilityState==="visible")
    refresh(false);
},30000);
signOut();
