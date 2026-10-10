import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {runInNewContext} from "node:vm";

const js=readFileSync(new URL("../public/owner.js",import.meta.url),"utf8");

test("OTP owner login retains form across await when event.currentTarget becomes null",async()=>{
  const start=js.indexOf('el("loginForm").addEventListener("submit"');
  const end=js.indexOf('for(const b of document.querySelectorAll("[data-tab]"))',start);
  assert.ok(start>=0 && end>start,"Could not find the real Owner login handler");
  const handler=js.slice(start,end);

  let callback, loggedIn=false, sessionCleared=false, resetCount=0;
  let tab=null, errorMessage=null, loginCreds=null, ownerAction=null;
  const form={reset(){resetCount++}};
  const button={disabled:false};
  const scope={
    el(id){
      if(id==="loginForm")return {addEventListener(name,fn){assert.equal(name,"submit");callback=fn}};
      if(id==="loginButton")return button;
      throw Error("Unexpected lookup "+id);
    },
    FormData:class{
      constructor(f){assert.equal(f,form)}
      get(key){return {email:"owner@example.test",code:"123456"}[key]||""}
    },
    loginError(message){errorMessage=message},
    async login(email,code){loginCreds={email,code}},
    async owner(action){ownerAction=action},
    setSignedIn(value){loggedIn=value},
    switchTab(value){tab=value},
    clearSession(){sessionCleared=true}
  };
  runInNewContext(handler,scope);
  assert.equal(typeof callback,"function");
  const event={currentTarget:form,preventDefault(){}};
  const work=callback(event);
  // Browser event dispatch is over. Async continuations must no longer use currentTarget.
  event.currentTarget=null;
  await work;
  assert.deepEqual(loginCreds,{email:"owner@example.test",code:"123456"});
  assert.equal(ownerAction,"overview");
  assert.equal(resetCount,1);
  assert.equal(loggedIn,true);
  assert.equal(tab,"overview");
  assert.equal(sessionCleared,false,"A UI reset error must not log out a verified Owner");
  assert.equal(errorMessage,"");
  assert.equal(button.disabled,false);
});

test("Owner OTP form reference is captured before first await",()=>{
  const start=js.indexOf('el("loginForm").addEventListener("submit"');
  const end=js.indexOf('for(const b of document.querySelectorAll("[data-tab]"))',start);
  const handler=js.slice(start,end);
  assert.match(handler,/const form\s*=\s*event\.currentTarget;/);
  assert.match(handler,/new FormData\(form\)/);
  assert.match(handler,/form\.reset\(\)/);
  assert.doesNotMatch(handler,/event\.currentTarget\.reset\(\)/);
});
