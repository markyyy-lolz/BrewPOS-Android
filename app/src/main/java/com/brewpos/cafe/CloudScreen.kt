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
fun CloudScreen(ctx:Context,db:StoreDb) {
    val scope=rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
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
            "Account signed in, but no café membership exists yet. Assign this user in Supabase before registering."
            else "Select your authorized branch and register this tablet."
    }
    LaunchedEffect(loggedIn) {
        if(loggedIn)runCatching { loadBranches() }.onFailure { status=it.message ?: "Cloud unavailable" }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("BrewPOS Cloud",fontWeight=FontWeight.ExtraBold,fontSize=28.sp,color=Color(0xFF30241D))
        Text("Secure hybrid sync · Cashier stays fully offline even when cloud is unavailable.",color=Color(0xFF756458))
        Surface(color=Color.White,shape=RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("$pending pending local event(s)",fontWeight=FontWeight.Bold,fontSize=20.sp)
                Text(if(linked)"Tablet linked to a branch" else "This tablet is not linked to a cloud branch yet",
                    color=Color(0xFF756458),fontSize=13.sp)
                Text(status,fontSize=12.sp,color=Color(0xFF756458))
                if(!loggedIn) {
                    OutlinedTextField(email,{email=it},label={Text("Staff email")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(password,{password=it},label={Text("Password")},singleLine=true,
                        visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                    Button(enabled=!busy&&email.isNotBlank()&&password.isNotBlank(),onClick={
                        busy=true
                        scope.launch {
                            runCatching { BrewCloud.login(ctx,email,password) }
                              .onSuccess { password="";loggedIn=true;status="Signed in" }
                              .onFailure { status=it.message ?: "Cloud sign-in failed" }
                            busy=false
                        }
                    },modifier=Modifier.fillMaxWidth()) { Text("Sign in to Cloud") }
                } else {
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
                                .onSuccess { linked=true;status="Tablet registered. Sync will retry automatically when online." }
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
                        status="Signed out. All offline transactions are preserved."
                    }) { Text("Sign out of Cloud") }
                }
            }
        }
        Text("Cloud access requires a verified Supabase Auth staff account, organization membership, and manager approval for this tablet. New checkout sales remain safely queued until acknowledged.",
            fontSize=12.sp,color=Color(0xFF756458))
    }
}
