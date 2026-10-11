import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";

const file=(name)=>readFileSync(new URL("../public/"+name,import.meta.url),"utf8");

test("BrewPOS Cloud signup requires business name and email only",()=>{
  const html=file("signup.html");
  const js=file("signup-cloud-v2.js");
  assert.match(html,/id="business"/);
  assert.match(html,/id="email"/);
  assert.doesNotMatch(html,/id="device"/);
  assert.match(html,/src="signup-cloud-v2\.js"/);
  assert.doesNotMatch(js,/validDevice|details\.device|el\("device"\)/);
  assert.match(js,/action:"request_account",business_name:details\.business/);
  assert.match(js,/\/auth\/v1\/verify/);
});

test("Mobile signup sends email code and submits registration without a device field",async()=>{
  const nodes={};
  const events={};
  const calls=[];
  for(const id of ["signupForm","business","email","otp","resend","submit","feedback","verification"]){
    nodes[id]={
      value:id==="business"?"Punong Tulay":id==="email"?"verified@example.com":"",
      hidden:false,disabled:false,readOnly:false,required:false,textContent:"",className:"",
      checkValidity:()=>true,
      addEventListener(event,callback){events[id+":"+event]=callback;}
    };
  }
  const context={
    document:{getElementById:id=>nodes[id]??null},
    location:{hash:"",pathname:"/signup.html",search:""},
    history:{replaceState(){}},
    URLSearchParams,
    fetch:async(url,init)=>{
      calls.push({url,body:JSON.parse(init.body)});
      const data=url.includes("/auth/v1/verify")?{access_token:"test-session"}:
        url.includes("/functions/v1/brewpos-sync")?{requested:true,status:"pending"}:{};
      return {ok:true,status:200,json:async()=>data};
    }
  };
  vm.runInNewContext(file("signup-cloud-v2.js"),context);
  assert.equal(nodes["verification"].hidden,true);
  assert.equal(nodes["submit"].disabled,false);
  await events["signupForm:submit"]({preventDefault(){}});
  assert.equal(nodes["verification"].hidden,false);
  assert.equal(nodes["otp"].required,true);
  assert.match(nodes["feedback"].textContent,/Verification code requested/);
  assert.equal(calls.length,1);
  assert.equal(calls[0].body.email,"verified@example.com");
  nodes.otp.value="123456";
  await events["signupForm:submit"]({preventDefault(){}});
  assert.equal(nodes.submit.disabled,true);
  assert.match(nodes.feedback.textContent,/Registration submitted/);
  assert.equal(calls.length,3);
  assert.equal(calls[2].body.action,"request_account");
  assert.equal(calls[2].body.business_name,"Punong Tulay");
  assert.equal("android_device_id" in calls[2].body,false);
});
