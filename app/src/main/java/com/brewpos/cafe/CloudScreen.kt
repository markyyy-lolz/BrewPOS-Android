package com.brewpos.cafe

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Cloud setup is optional. Core POS remains offline-first; no sale is erased on error. */
@Composable
fun CloudScreen(ctx:Context,db:StoreDb,onUpdated:()->Unit={}) {
    val scope=rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var useCode by remember { mutableStateOf(true) }
    var codeSent by remember { mutableStateOf(false) }
    var emailCode by remember { mutableStateOf("") }
    var business by remember { mutableStateOf("") }
    var loggedIn by remember { mutableStateOf(BrewCloud.signedIn(ctx)) }
    var linked by remember { mutableStateOf(BrewCloud.signedBranch(ctx)) }
    var response by remember { mutableStateOf<JSONObject?>(null) }
    var organization by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Sign in to link your Android counter to BrewPOS Cloud.") }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableIntStateOf(db.pendingSyncCount()) }
    suspend fun loadBranches(){
        val data=BrewCloud.memberships(ctx)
        response=data
        status=if(data.optJSONArray("memberships")?.length()==0)
            "Not yet approved. Request a Cloud POS account and wait for Azurate owner approval."
            else "Select your authorized branch and register this tablet."
    }
    LaunchedEffect(loggedIn) {
        if(loggedIn)runCatching { loadBranches() }.onFailure { status=it.message ?: "Cloud unavailable" }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("Cloud POS · Account login",fontWeight=FontWeight.ExtraBold,fontSize=28.sp,color=Color(0xFF30241D))
        Text("Verified cloud login and branch setup. For guaranteed offline checkout, switch to Offline License mode.",color=Color(0xFF756458))
        Surface(color=Color.White,shape=RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("$pending pending local event(s)",fontWeight=FontWeight.Bold,fontSize=20.sp)
                Text(if(linked)"Tablet linked to a branch" else "This tablet is not linked to a cloud branch yet",
                    color=Color(0xFF756458),fontSize=13.sp)
                Text(status,fontSize=12.sp,color=Color(0xFF756458))
                if(!loggedIn) {
                    OutlinedTextField(email,{email=it;codeSent=false},label={Text("Account email")},
                        singleLine=true,modifier=Modifier.fillMaxWidth())
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        FilterChip(selected=useCode,onClick={useCode=true},label={Text("Email code")})
                        FilterChip(selected=!useCode,onClick={useCode=false},label={Text("Password")})
                    }
                    if(useCode) {
                        if(codeSent)OutlinedTextField(emailCode,{
                            emailCode=it.filter(Char::isDigit).take(8)
                        },label={Text("Verification code")},singleLine=true,modifier=Modifier.fillMaxWidth())
                        Button(enabled=!busy&&email.contains("@")&&(!codeSent||emailCode.length>=6),
                            onClick={
                                busy=true
                                scope.launch{
                                    if(codeSent)runCatching{
                                        BrewCloud.verifyEmailCode(ctx,email,emailCode)
                                    }.onSuccess{
                                        loggedIn=true;status="Email verified. Checking approved branch…"
                                        onUpdated()
                                    }.onFailure{status=it.message?:"Invalid email code"}
                                    else runCatching{
                                        BrewCloud.sendEmailCode(email)
                                    }.onSuccess{
                                        codeSent=true;status="Check your email for the verification code."
                                    }.onFailure{status=it.message?:"Cannot send email code"}
                                    busy=false
                                }
                            },modifier=Modifier.fillMaxWidth()){
                            Text(if(busy)"Please wait…"else if(codeSent)"Verify & sign in →"else"Send email code →")
                        }
                        if(codeSent)TextButton(onClick={
                            codeSent=false;emailCode="";status=""
                        }){Text("Request another code")}
                    } else {
                        OutlinedTextField(password,{password=it},label={Text("Password")},singleLine=true,
                            visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                        Button(enabled=!busy&&email.contains("@")&&password.isNotBlank(),onClick={
                            busy=true;scope.launch {
                                runCatching{BrewCloud.login(ctx,email,password)}
                                    .onSuccess{password="";loggedIn=true;status="Signed in";onUpdated()}
                                    .onFailure{status=it.message?:"Cloud sign-in failed"}
                                busy=false
                            }
                        },modifier=Modifier.fillMaxWidth()){Text("Sign in to Cloud POS →")}
                    }
                } else {
                    if(response?.optJSONArray("memberships")?.length()==0){
                        Text("REQUEST CLOUD ACCESS",fontWeight=FontWeight.Bold,color=Color(0xFF254D3B))
                        OutlinedTextField(business,{business=it},
                            label={Text("Café / business name")},singleLine=true,
                            modifier=Modifier.fillMaxWidth())
                        Text("Azurate must approve this verified account before you can sell.",
                            fontSize=12.sp,color=Color(0xFF756458))
                        Button(enabled=!busy&&business.trim().length>=2,onClick={
                            busy=true;scope.launch{
                                runCatching{
                                    val deviceId=android.provider.Settings.Secure.getString(
                                        ctx.contentResolver,android.provider.Settings.Secure.ANDROID_ID)
                                    BrewCloud.requestCloudApproval(ctx,business,deviceId)
                                }.onSuccess{
                                    status="Approval request submitted. Open Azurate Owner Dashboard → Client Approvals."
                                    loadBranches();onUpdated()
                                }.onFailure{status=it.message?:"Request failed"}
                                busy=false
                            }
                        },modifier=Modifier.fillMaxWidth()){Text("Request approval from Azurate")}
                    }
                    response?.optJSONArray("memberships")?.let { memberships ->
                        for(i in 0 until memberships.length()){
                            val item=memberships.getJSONObject(i)
                            val org=item.optString("organization_id")
                            val role=item.optString("role")
                            Text("Organization $org · $role",fontSize=12.sp,fontWeight=FontWeight.Bold)
                            val branches=item.optJSONArray("branches") ?: continue
                            for(j in 0 until branches.length()) {
                                val b=branches.getJSONObject(j)
                                val id=b.optString("id")
                                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                                    RadioButton(selected=organization==org&&branch==id,onClick={
                                        organization=org;branch=id
                                    })
                                    Text(b.optString("name","Branch"),fontSize=13.sp)
                                }
                            }
                        }
                    }
                    Button(enabled=!busy&&organization.isNotBlank()&&branch.isNotBlank(),onClick={
                        busy=true
                        scope.launch{
                            runCatching { BrewCloud.registerDevice(ctx,organization,branch) }
                                .onSuccess { linked=true;status="Tablet registered. Sync will retry automatically when online.";onUpdated() }
                                .onFailure { status=it.message ?: "Device registration failed" }
                            busy=false
                        }
                    }) { Text("Register device & enable sync") }
                    OutlinedButton(enabled=!busy&&linked,onClick={
                        busy=true
                        scope.launch {
                            runCatching { BrewCloud.uploadPending(ctx) }
                                .onSuccess { status="$it event(s) uploaded; catalog refreshed when queue is clear";pending=db.pendingSyncCount() }
                                .onFailure { status=it.message ?: "Cloud unavailable; sales remain queued" }
                            busy=false
                        }
                    }) { Text("Sync transactions & download menu") }
                    TextButton(onClick={
                        BrewCloud.signOut(ctx);loggedIn=false;linked=false;response=null
                        status="Signed out. All offline transactions are preserved.";onUpdated()
                    }) { Text("Sign out of Cloud") }
                }
            }
        }
        Text("Cloud checkout requires an approved non-expired account, a registered device, and recent online verification. A separate offline BP1 license supports unplugged checkout.",
            fontSize=12.sp,color=Color(0xFF756458))
    }
}
