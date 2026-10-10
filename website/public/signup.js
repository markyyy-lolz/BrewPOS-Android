"use strict";
// GitHub Pages public signup: publishable key only. Auth session exists in memory,
// and authorization is exclusively enforced by the BrewPOS Supabase Edge Function.
const BASE = "https://rfxzbuocxersgbshczbj.supabase.co";
const PUBLIC_KEY = "sb_publishable_CR-CJKna_rcjQi7gg1CZGQ__DnXI6Lb";
const el = id => document.getElementById(id);
let stage = "send", access = null, busy = false;
let details = null;
const validDevice = id => /^[a-f0-9]{16}$/.test(id);
function feedback(message,error=false) {
  const e=el("feedback");e.textContent=message;e.className=error?"error":"subtle";
}
async function post(path, payload, token=null) {
  const headers={"content-type":"application/json","apikey":PUBLIC_KEY};
  if(token)headers.authorization="Bearer "+token;
  const r=await fetch(BASE+path,{
    method:"POST",cache:"no-store",headers,body:JSON.stringify(payload)
  });
  let obj={};try{obj=await r.json()}catch{}
  if(!r.ok)throw Error(r.status===429?"Too many requests. Try later.":
    "Could not complete this step. Check the email code, connectivity, and BrewPOS Auth setup.");
  return obj;
}
function setStage(next) {
  stage=next;
  el("verification").hidden=next==="send"||next==="complete";
  el("submit").textContent=next==="send"?"Send verification code →":
    next==="verify"?"Verify email & create account →":
    next==="retry"?"Retry approval request →":"Registration submitted";
  el("submit").disabled=next==="complete";
  for(const id of ["business","email","device"])el(id).readOnly=next!=="send";
  el("otp").required=next==="verify";
}
el("signupForm").addEventListener("submit",async e=>{
  e.preventDefault();if(busy||stage==="complete")return;
  const btn=el("submit");busy=true;btn.disabled=true;feedback("");
  try {
    if(stage==="send"){
      const business=el("business").value.trim(),email=el("email").value.trim().toLowerCase();
      const device=el("device").value.trim().toLowerCase();
      if(business.length<2||business.length>80||!el("email").checkValidity()||!validDevice(device))
        throw Error("Enter your business name, valid email, and 16-character Android Device ID.");
      details={business,email,device};
      await post("/auth/v1/otp",{email,create_user:true});
      setStage("verify");
      feedback("Verification code requested. Check your email inbox and spam folder.");
    }else{
      if(stage==="verify"){
        const token=el("otp").value.trim();
        if(!/^[0-9]{6}$/.test(token))throw Error("Enter the 6-digit email OTP.");
        const verified=await post("/auth/v1/verify",{email:details.email,token,type:"email"});
        if(!verified.access_token)throw Error("Email verification did not return an authenticated session.");
        access=verified.access_token;
        el("otp").value="";
        setStage("retry"); // Subsequent retries never reuse a consumed OTP.
      }
      if(!access)throw Error("Session expired. Start registration again.");
      const result=await post("/functions/v1/brewpos-sync",{
        action:"request_account",business_name:details.business,android_device_id:details.device
      },access);
      if(!result.requested)throw Error("Registration request was not confirmed.");
      setStage("complete");
      feedback("Email verified. Registration submitted. Azurate will review your account and assign the purchased license.");
      access=null;
    }
  }catch(err){ feedback(err?.message||"Registration failed",true); }
  finally{busy=false;if(stage!=="complete")btn.disabled=false;}
});
setStage("send");
