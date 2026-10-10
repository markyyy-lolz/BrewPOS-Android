"use strict";
// GitHub Pages public signup: publishable key only. Auth session exists in memory,
// and authorization is exclusively enforced by the BrewPOS Supabase Edge Function.
const BASE = "https://rfxzbuocxersgbshczbj.supabase.co";
const PUBLIC_KEY = "sb_publishable_CR-CJKna_rcjQi7gg1CZGQ__DnXI6Lb";
const GITHUB_SIGNUP = "https://markyyy-lolz.github.io/BrewPOS-Android/signup.html";
const otpPath = "/auth/v1/otp?redirect_to="+encodeURIComponent(GITHUB_SIGNUP);
const el = id => document.getElementById(id);
let stage = "send", access = null, busy = false;
let details = null;
// Immediately remove sensitive Magic Link JWTs from the browser's address bar.
// Numeric OTP remains the primary signup mechanism; links are fallback only.
const fragment = new URLSearchParams(location.hash.replace(/^#/, ""));
const callbackToken = fragment.get("access_token");
const callbackError = fragment.get("error_description");
if(fragment.has("access_token")||fragment.has("refresh_token")||fragment.has("error"))
  history.replaceState(null,"",location.pathname+location.search);

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
  if(!r.ok){
    if(r.status===429)throw Error("Too many requests. Wait a moment before trying again.");
    if(path.includes("/auth/v1/verify"))
      throw Error("Verification code was rejected or expired. Request a new email OTP.");
    if(path.includes("/functions/v1/brewpos-sync")){
      if(r.status===400)throw Error("Check the business name and Android Device ID, then retry approval.");
      if(r.status===401)throw Error("Verified login expired. Restart registration and request a fresh OTP.");
      if(r.status===409)throw Error("This registration is already linked or has been approved. Contact Azurate support.");
      if(r.status===503)throw Error("BrewPOS registration service is temporarily unavailable. Retry approval shortly.");
      throw Error("BrewPOS approval request could not be saved (HTTP "+r.status+"). Please retry.");
    }
    throw Error("Email authentication request failed. Check your email address and try again.");
  }
  return obj;
}
function setStage(next) {
  stage=next;
  el("verification").hidden=next==="send"||next==="complete"||next==="link";
  el("submit").textContent=next==="send"?"Send verification code →":
    next==="link"?"Finish email-verified registration →":
    next==="verify"?"Verify email & create account →":
    next==="retry"?"Retry approval request →":"Registration submitted";
  el("submit").disabled=next==="complete";
  for(const id of ["business","email","device"])el(id).readOnly=next!=="send"&&next!=="link";
  el("otp").required=next==="verify";
  el("resend").hidden=next!=="verify";
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
      await post(otpPath,{email,create_user:true});
      setStage("verify");
      feedback("Verification code requested. Check your email inbox and spam folder.");
    }else{
      if(stage==="link"){
        // Legacy email-link fallback. Verify the Supabase session and email
        // before submitting a PENDING request (never a paid license).
        const business=el("business").value.trim();
        const email=el("email").value.trim().toLowerCase();
        const device=el("device").value.trim().toLowerCase();
        if(business.length<2||business.length>80||!el("email").checkValidity()||!validDevice(device))
          throw Error("Enter your business, verified email and correct 16-digit device ID.");
        const response=await fetch(BASE+"/auth/v1/user",{
          headers:{apikey:PUBLIC_KEY,authorization:"Bearer "+access},
          cache:"no-store"
        });
        if(!response.ok)throw Error("Sign-in link session expired. Please request a new email.");
        const identity=await response.json();
        if(!identity.email_confirmed_at || String(identity.email).toLowerCase()!==email)
          throw Error("Enter the exact email address that received the sign-in link.");
        details={business,email,device};
        setStage("retry");
      }
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
el("resend").addEventListener("click",async()=>{
  if(busy||stage!=="verify"||!details)return;
  busy=true;el("resend").disabled=true;feedback("");
  try {
    await post(otpPath,{email:details.email,create_user:true});
    feedback("New six-digit code requested. Check inbox and spam. Supabase may limit resend frequency.");
  }catch(err){feedback(err?.message||"Unable to resend OTP.",true)}
  finally{busy=false;el("resend").disabled=false;}
});
setStage("send");
if(callbackError)
  feedback("Email link is invalid or expired. Please request a new six-digit OTP.",true);
if(callbackToken){
  access=callbackToken;
  setStage("link");
  feedback("Email sign-in link verified by Supabase. Enter business details to finish registration. The recommended flow remains six-digit email OTP.");
}
