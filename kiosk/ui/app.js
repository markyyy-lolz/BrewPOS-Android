"use strict";
(() => {
  const $=id=>document.getElementById(id);
  const peso=n=>new Intl.NumberFormat("en-PH",{style:"currency",currency:"PHP"}).format(n/100);
  const escape=s=>String(s??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
  let sequence=0;const waiting=new Map();
  const S={config:null,items:[],cart:[],category:"All",search:"",current:null,qty:1,choices:{},service:"Dine-in",queue:[],filter:"All"};
  function call(action,payload={}){
    const id=String(++sequence);
    return new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>{waiting.delete(id);reject(new Error("Device timed out. Ask café staff for help."));},20000);
      waiting.set(id,{resolve,reject,timer});
      const msg=JSON.stringify({id,action,payload});
      if(window.chrome?.webview)window.chrome.webview.postMessage(msg);
      else if(window.BrewKiosk?.postMessage)window.BrewKiosk.postMessage(msg);
      else{clearTimeout(timer);waiting.delete(id);reject(new Error("BrewPOS native app is required."));}
    });
  }
  window.BrewPOSReply=raw=>{
    let reply;try{reply=typeof raw==="string"?JSON.parse(raw):raw;}catch{return;}
    const p=waiting.get(String(reply.id));if(!p)return;
    clearTimeout(p.timer);waiting.delete(String(reply.id));
    if(reply.ok)p.resolve(reply.result);else p.reject(new Error(reply.error||"Operation failed"));
  };
  if(window.chrome?.webview)window.chrome.webview.addEventListener("message",e=>window.BrewPOSReply(e.data));
  function notice(text){const el=$("toast");el.textContent=text;el.style.display="block";clearTimeout(notice.timer);notice.timer=setTimeout(()=>el.style.display="none",4000);}
  const show=id=>$(id).hidden=false,hide=id=>$(id).hidden=true;
  function icon(c){return /coffee|espresso|latte/i.test(c)?"☕":/tea|matcha|milk/i.test(c)?"🍵":/bread|bake|pastr/i.test(c)?"🥐":/meal|rice|food/i.test(c)?"🍲":"🍹";}
  function menu(){
    const categories=["All",...new Set(S.items.filter(p=>p.active).map(p=>p.category))];
    $("cats").innerHTML=categories.map(x=>'<button class="'+(x===S.category?"active":"")+'" data-cat="'+escape(x)+'">'+escape(x)+"</button>").join("");
    $("cats").querySelectorAll("[data-cat]").forEach(b=>b.onclick=()=>{S.category=b.dataset.cat;menu()});
    const products=S.items.filter(x=>x.active && (S.category==="All"||x.category===S.category) && (x.name+" "+x.category).toLowerCase().includes(S.search));
    $("menu").innerHTML=products.map(p=>'<button class="product" data-id="'+escape(p.id)+'"><div class="art">'+icon(p.category)+'</div><div class="details"><div class="cat">'+escape(p.category)+'</div><h3>'+escape(p.name)+'</h3><strong>'+peso(p.priceCentavos)+"</strong></div></button>").join("");
    $("menu").querySelectorAll("[data-id]").forEach(b=>b.onclick=()=>customize(b.dataset.id));
    $("noMenu").hidden=products.length>0;
  }
  function unit(){
    if(!S.current)return 0;
    return S.current.priceCentavos+(S.current.modifiers||[]).reduce((sum,g)=>{
      const selected=S.choices[g.id]||[];
      return sum+selected.reduce((t,id)=>t+((g.options||[]).find(o=>o.id===id)?.deltaCentavos||0),0);
    },0);
  }
  function customize(id){
    S.current=S.items.find(p=>p.id===id);if(!S.current)return;
    S.qty=1;S.choices={};
    $("itemTitle").textContent=S.current.name;
    $("description").textContent=S.current.description||"Made fresh with care.";
    $("symbol").textContent=icon(S.current.category);
    const nodes=[];
    for(const g of (S.current.modifiers||[])){
      if(g.required && g.options?.length)S.choices[g.id]=[g.options[0].id];
      const options=(g.options||[]).map(o=>'<button data-group="'+escape(g.id)+'" data-option="'+escape(o.id)+'">'+escape(o.name)+'<small>'+(o.deltaCentavos?"+ "+peso(o.deltaCentavos):"Included")+"</small></button>").join("");
      nodes.push('<div class="mod"><h4>'+escape(g.name)+(g.required?" *":"")+'</h4><div class="option-list">'+options+"</div></div>");
    }
    $("options").innerHTML=nodes.join("");
    $("options").querySelectorAll("[data-option]").forEach(b=>b.onclick=()=>{
      const g=S.current.modifiers.find(x=>x.id===b.dataset.group),id=b.dataset.option;
      const current=S.choices[g.id]||[];
      if(g.multiple)S.choices[g.id]=current.includes(id)?current.filter(x=>x!==id):current.length<(g.maxSelections||4)?current.concat(id):current;
      else S.choices[g.id]=[id];
      redraw();
    });
    redraw();show("custom");
  }
  function redraw(){
    $("count").textContent=S.qty;
    $("add").textContent="Add to order · "+peso(unit()*S.qty);
    $("options").querySelectorAll("[data-option]").forEach(b=>b.classList.toggle("active",(S.choices[b.dataset.group]||[]).includes(b.dataset.option)));
  }
  function renderCart(){
    $("lines").innerHTML=S.cart.map((l,i)=>'<div class="line"><div><h4>'+escape(l.name)+" × "+l.quantity+'</h4><p>'+escape(l.optionLabel)+'</p><button data-remove="'+i+'">Remove</button></div><b>'+peso(l.quantity*l.unitCentavos)+"</b></div>").join("");
    $("lines").querySelectorAll("[data-remove]").forEach(b=>b.onclick=()=>{S.cart.splice(Number(b.dataset.remove),1);renderCart()});
    const total=S.cart.reduce((n,l)=>n+l.quantity*l.unitCentavos,0);
    $("subtotal").textContent=peso(total);$("total").textContent=peso(total);$("checkoutTotal").textContent=peso(total);
    $("linesLabel").textContent=S.cart.length+" item(s)";$("review").disabled=!S.cart.length;
  }
  function add(){
    const p=S.current;if(!p)return;
    const options=(p.modifiers||[]).map(g=>({groupId:g.id,optionIds:S.choices[g.id]||[]}));
    if((p.modifiers||[]).some(g=>g.required&&!(S.choices[g.id]||[]).length)){notice("Please choose required options.");return;}
    const labels=options.flatMap(x=>(p.modifiers.find(g=>g.id===x.groupId)?.options||[]).filter(o=>x.optionIds.includes(o.id)).map(o=>o.name));
    S.cart.push({productId:p.id,name:p.name,quantity:S.qty,unitCentavos:unit(),productVersion:p.version||1,options,optionLabel:labels.join(" • ")});
    renderCart();hide("custom");notice("Added to your order");
  }
  async function checkout(){
    if(!S.cart.length)return;
    $("confirm").disabled=true;
    try{
      const result=await call("createOrder",{service:S.service,notes:$("note").value.trim(),paymentMethod:"counter",lines:S.cart});
      $("orderNo").textContent=result.number;
      $("successDetail").textContent=result.delivered?
        "Your order reached the cashier. Please pay at the counter.":
        "Saved locally, but NOT delivered yet. Show this number to staff.";
      S.cart=[];renderCart();hide("checkout");show("success");
    }catch(e){notice(e.message)}finally{$("confirm").disabled=false;}
  }
  async function reload(){
    try{
      S.queue=(await call("listOrders")).orders||[];
      staffView();
    }catch(e){notice(e.message)}
  }
  function staffView(){
    const mode=S.config.mode;
    const statuses=mode==="kitchen"?["Paid","Preparing","Ready","Completed"]:["AwaitingPayment","Paid","Preparing","Ready","Completed"];
    $("staffTitle").textContent=mode==="kitchen"?"Barista Kitchen Display":"Cashier Payment Queue";
    $("staffDescription").textContent=mode==="kitchen"?"Only paid orders can enter preparation.":"Confirm payment in person before releasing an order.";
    $("filters").innerHTML=["All",...statuses].map(x=>'<button data-filter="'+x+'" class="'+(S.filter===x?"active":"")+'">'+x+"</button>").join("");
    $("filters").querySelectorAll("[data-filter]").forEach(b=>b.onclick=()=>{S.filter=b.dataset.filter;staffView()});
    $("tickets").innerHTML=S.queue.filter(o=>statuses.includes(o.status)&&(S.filter==="All"||o.status===S.filter)).map(o=>{
      let next=o.status==="Paid"?"Preparing":o.status==="Preparing"?"Ready":o.status==="Ready"?"Completed":null;
      if(mode==="cashier"&&o.status==="AwaitingPayment")next="Paid";
      const lines=(o.lines||[]).map(l=>'<p>'+l.quantity+"× "+escape(l.name)+"<small>"+escape(l.optionLabel||"")+"</small></p>").join("");
      return '<article class="ticket"><small>'+escape(o.status)+'</small><h3>'+escape(o.number)+'</h3><small>'+escape(o.service)+" • "+escape(o.notes||"")+'</small><div>'+lines+'</div><b>'+peso(o.totalCentavos)+'</b>'+(next?'<button class="ticket-action" data-next="'+next+'" data-id="'+escape(o.id)+'">'+(next==="Paid"?"Confirm Cash Payment":"Mark "+next)+"</button>":"")+"</article>";
    }).join("");
    $("tickets").querySelectorAll("[data-next]").forEach(b=>b.onclick=async()=>{
      if(b.dataset.next==="Paid"&&!confirm("Confirm CASH has been received from this customer?"))return;
      try{await call("updateStatus",{id:b.dataset.id,next:b.dataset.next});await reload();}
      catch(e){notice(e.message)}
    });
  }
  async function start(){
    try{
      S.config=await call("bootstrap");
      $("shop").textContent=S.config.branchName||"BrewPOS";
      $("connection").textContent=S.config.hubConnected?"● Café LAN connected":"● Offline • locally queued";
      if(S.config.mode==="kiosk"){
        S.items=await call("menu");menu();renderCart();
      }else{
        $("kiosk").hidden=true;$("staff").hidden=false;await reload();setInterval(reload,5000);
      }
    }catch(e){notice(e.message);$("noMenu").hidden=false;}
  }
  $("search").oninput=e=>{S.search=e.target.value.toLowerCase();menu()};
  $("minus").onclick=()=>{S.qty=Math.max(1,S.qty-1);redraw()};
  $("plus").onclick=()=>{S.qty=Math.min(99,S.qty+1);redraw()};
  $("add").onclick=add;
  $("review").onclick=()=>show("checkout");
  document.querySelectorAll("[data-close]").forEach(b=>b.onclick=()=>hide(b.dataset.close));
  document.querySelectorAll("[data-service]").forEach(b=>b.onclick=()=>{
    S.service=b.dataset.service;
    document.querySelectorAll("[data-service]").forEach(x=>x.classList.toggle("active",x===b));
  });
  $("confirm").onclick=checkout;
  $("restart").onclick=()=>{hide("success");S.search="";S.category="All";$("search").value="";menu()};
  $("refresh").onclick=reload;
  start();
})();