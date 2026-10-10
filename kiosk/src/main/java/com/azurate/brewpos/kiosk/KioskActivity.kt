package com.azurate.brewpos.kiosk

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

private val Dark = Color(0xFF30251F)
private val Bean = Color(0xFF876043)
private val Canvas = Color(0xFFF8F5F0)
private val Moss = Color(0xFF365747)
private val Soft = Color(0xFFF0E7DA)
private val KioskColors = lightColorScheme(
    primary=Dark, onPrimary=Color.White, background=Canvas,
    surface=Color.White, onSurface=Dark, secondary=Moss
)
private fun money(centavos:Long)="₱"+String.format(Locale.US,"%,.2f",centavos/100.0)
private fun lineUnit(line:KioskLine):Long = line.product.price+
    (if(line.size=="Large")3000 else 0)+(if(line.extraShot)4000 else 0)
private fun quotedTotal(lines:List<KioskLine>):Long = lines.sumOf { lineUnit(it)*it.quantity }

class KioskActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0x00000400 or 0x00000200 or 0x00001000
        setContent {
            MaterialTheme(colorScheme=KioskColors) {
                Surface(Modifier.fillMaxSize(),color=Canvas) {
                    if(KioskCloud.paired(applicationContext)) CustomerKiosk()
                    else PairingScreen(onPaired={ recreate() })
                }
            }
        }
    }
}

@Composable
private fun PairingScreen(onPaired:()->Unit) {
    val ctx=LocalContext.current
    val scope= rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var branches by remember { mutableStateOf<List<KioskBranch>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()),
        verticalArrangement=Arrangement.Center,
        horizontalAlignment=Alignment.CenterHorizontally) {
        Text("☕  BREWPOS SELF ORDER",fontSize=26.sp,fontWeight=FontWeight.Black,color=Dark)
        Spacer(Modifier.height(12.dp))
        Text("Manager-only device pairing",fontSize=19.sp,fontWeight=FontWeight.Bold)
        Text("Customers cannot use the kiosk until an authorized café manager pairs this Android device. No owner password is saved.",
            color=Bean,modifier=Modifier.widthIn(max=550.dp).padding(14.dp),textAlign=TextAlign.Center)
        if(branches.isEmpty()) {
            OutlinedTextField(email,{email=it},label={Text("Manager email")},singleLine=true,
                modifier=Modifier.widthIn(max=440.dp).fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(password,{password=it},label={Text("Password")},
                visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),
                singleLine=true,modifier=Modifier.widthIn(max=440.dp).fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Button(enabled=!busy&&email.isNotBlank()&&password.isNotBlank(), onClick={
                busy=true
                scope.launch {
                    runCatching { KioskCloud.availableBranches(email,password) }
                        .onSuccess { branches=it;password="";status=if(it.isEmpty())"No authorized owner/manager branch." else "Select a branch to pair." }
                        .onFailure { status=it.message?:"Cloud authorization failed" }
                    busy=false
                }
            }) { Text(if(busy)"Checking..." else "Verify manager & choose branch") }
        } else {
            branches.forEach { branch ->
                OutlinedButton(enabled=!busy,onClick={
                    busy=true
                    scope.launch {
                        runCatching { KioskCloud.pair(ctx,branch) }
                            .onSuccess { onPaired() }
                            .onFailure { status=it.message?:"Pairing failed" }
                        busy=false
                    }
                },modifier=Modifier.widthIn(max=550.dp).fillMaxWidth()) {
                    Text(branch.name)
                }
            }
        }
        Text(status,color=if(status.contains("failed",true))Color.Red else Moss,modifier=Modifier.padding(18.dp))
        Text("For secure unattended use, deploy Android lock-task/device-owner mode and restrict system navigation.",
            color=Bean,fontSize=12.sp,textAlign=TextAlign.Center)
    }
}

@Composable
private fun CustomerKiosk(){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var menu by remember { mutableStateOf<List<KioskProduct>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var step by remember { mutableStateOf("welcome") }
    var service by remember { mutableStateOf("Dine-in") }
    var category by remember { mutableStateOf("All") }
    var selected by remember { mutableStateOf<KioskProduct?>(null) }
    val cart=remember { mutableStateListOf<KioskLine>() }
    var busy by remember { mutableStateOf(false) }
    var order by remember { mutableStateOf<KioskOrder?>(null) }
    var submission by remember { mutableStateOf(UUID.randomUUID().toString()) }
    fun load(){
        loading=true
        scope.launch {
            runCatching { KioskCloud.catalog(ctx) }
                .onSuccess { menu=it;error="";loading=false }
                .onFailure { error="Connection needed to load the café menu: ${it.message}";loading=false }
        }
    }
    LaunchedEffect(Unit){load()}
    LaunchedEffect(order?.id) {
        while(order!=null && order?.status !in listOf("paid","cancelled")) {
            delay(4000)
            val current=order ?: break
            runCatching { KioskCloud.status(ctx,current.id) }
                .onSuccess { status -> order=current.copy(status=status) }
                .onFailure { error="Connection interrupted; payment status cannot be verified. Ask cashier for help." }
        }
    }
    Column(Modifier.fillMaxSize().background(Canvas)) {
        Row(Modifier.fillMaxWidth().background(Dark).padding(horizontal=24.dp,vertical=16.dp),
            horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
            Text("☕  BREWPOS",color=Color.White,fontSize=23.sp,fontWeight=FontWeight.Black)
            Text("SELF ORDER · ANDROID",color=Color(0xFFE9D6C3),fontSize=13.sp)
        }
        when(step){
            "welcome" -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                Column(horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(18.dp)) {
                    Text("Your next favorite cup",fontSize=38.sp,fontWeight=FontWeight.Black,color=Dark,textAlign=TextAlign.Center)
                    Text("Browse the menu, customize your drink and choose how to pay.",
                        textAlign=TextAlign.Center,color=Bean)
                    if(error.isNotBlank())Text(error,color=Color(0xFFB23E2B),textAlign=TextAlign.Center)
                    Button(enabled=!loading&&menu.isNotEmpty(),onClick={step="menu"},
                        modifier=Modifier.widthIn(min=280.dp).height(64.dp)) {
                        Text(if(loading)"Loading café menu..." else "Touch to order",fontSize=20.sp)
                    }
                    OutlinedButton(onClick={load()},enabled=!loading) { Text("Refresh menu") }
                }
            }
            "menu" -> Column(Modifier.fillMaxSize().padding(20.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("Our menu",fontSize=29.sp,fontWeight=FontWeight.Black,modifier=Modifier.weight(1f))
                    Button(onClick={step="cart"},enabled=cart.isNotEmpty()) { Text("Cart · ${cart.sumOf { it.quantity }} · ${money(quotedTotal(cart))}") }
                }
                val categories=listOf("All")+menu.map{it.category}.distinct().sorted()
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp),
                    modifier=Modifier.padding(vertical=12.dp)) {
                    categories.take(7).forEach { cat ->
                        FilterChip(selected=category==cat,onClick={category=cat},label={Text(cat)})
                    }
                }
                LazyVerticalGrid(columns=GridCells.Adaptive(190.dp),
                    horizontalArrangement=Arrangement.spacedBy(12.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    items(menu.filter{ category=="All"||it.category==category },key={it.id}){product ->
                        val canOrder=!product.tracked||product.stock.toBigDecimalOrNull()?.signum()==1
                        Surface(color=Color.White,shape=RoundedCornerShape(18.dp),
                            modifier=Modifier.fillMaxWidth().clickable(enabled=canOrder){selected=product}) {
                            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                                Text("☕",fontSize=39.sp)
                                Text(product.name,fontSize=18.sp,fontWeight=FontWeight.Bold)
                                Text(product.category,color=Bean)
                                Text(money(product.price),fontWeight=FontWeight.ExtraBold,fontSize=19.sp)
                                Text(if(canOrder)"Customize →" else "Sold out",color=if(canOrder)Moss else Color.Red)
                            }
                        }
                    }
                }
            }
            "cart" -> Column(Modifier.fillMaxSize().padding(24.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically){
                    TextButton(onClick={step="menu"}){Text("← Menu")}
                    Spacer(Modifier.width(12.dp))
                    Text("Your order",fontSize=29.sp,fontWeight=FontWeight.Black)
                }
                LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    items(cart.size) { index ->
                        val line=cart[index]
                        Surface(color=Color.White,shape=RoundedCornerShape(14.dp)) {
                            Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)){
                                    Text(line.product.name,fontWeight=FontWeight.Bold)
                                    Text("${line.size} · ${line.sugar} sugar · ${line.ice} ice"+if(line.extraShot)" · Extra shot" else "",color=Bean,fontSize=12.sp)
                                    Text(money(lineUnit(line)*line.quantity),fontWeight=FontWeight.Bold)
                                }
                                TextButton(onClick={if(line.quantity>1)cart[index]=line.copy(quantity=line.quantity-1) else cart.removeAt(index)}) {Text("−")}
                                Text("${line.quantity}")
                                TextButton(onClick={if(line.quantity<99)cart[index]=line.copy(quantity=line.quantity+1)}) {Text("+")}
                            }
                        }
                    }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(9.dp)){
                    FilterChip(selected=service=="Dine-in",onClick={service="Dine-in"},label={Text("Dine-in")})
                    FilterChip(selected=service=="Takeout",onClick={service="Takeout"},label={Text("Takeout")})
                }
                Text("Estimated total: ${money(quotedTotal(cart))}",fontSize=27.sp,fontWeight=FontWeight.Black)
                Text("Final price is checked by BrewPOS Cloud before accepting the order.",color=Bean,fontSize=12.sp)
                Spacer(Modifier.height(9.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    Button(modifier=Modifier.weight(1f).height(62.dp),enabled=!busy&&cart.isNotEmpty(),onClick={
                        busy=true;error=""
                        val id=submission
                        scope.launch {
                            runCatching { KioskCloud.order(ctx,id,service,"cashier",cart.toList()) }
                                .onSuccess { order=it;step="confirmation" }
                                .onFailure { error=it.message?:"Order could not be submitted" }
                            busy=false
                        }
                    }){Text("Pay at Cashier",fontSize=17.sp)}
                    OutlinedButton(modifier=Modifier.weight(1f).height(62.dp),enabled=!busy&&cart.isNotEmpty(),onClick={
                        busy=true;error=""
                        val id=submission
                        scope.launch {
                            runCatching { KioskCloud.order(ctx,id,service,"qrph",cart.toList()) }
                                .onSuccess { order=it;step="confirmation" }
                                .onFailure { error=it.message?:"QR Ph is unavailable" }
                            busy=false
                        }
                    }){Text("Pay via QR Ph",fontSize=17.sp)}
                }
                if(error.isNotBlank())Text(error,color=Color.Red,modifier=Modifier.padding(top=12.dp))
            }
            "confirmation" -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                val current=order
                if(current!=null)Column(Modifier.widthIn(max=660.dp).padding(18.dp),
                    horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    Text("ORDER ${current.number}",fontSize=40.sp,fontWeight=FontWeight.Black)
                    Text(money(current.amount),fontSize=29.sp,fontWeight=FontWeight.Bold)
                    if(current.status=="paid") {
                        Text("✓ Payment verified",color=Moss,fontSize=26.sp,fontWeight=FontWeight.Bold)
                        Text("Your order has been sent for preparation.",textAlign=TextAlign.Center)
                    } else {
                        Text("Payment not yet confirmed",color=Bean,fontSize=20.sp)
                        if(current.qrImage!=null) {
                            QrImage(current.qrImage)
                            Text("Scan with your banking or e-wallet app. Do not leave until payment is confirmed.",textAlign=TextAlign.Center)
                        } else {
                            Text("Please present order ${current.number} to the cashier and pay there.",fontSize=19.sp,textAlign=TextAlign.Center)
                        }
                    }
                    if(error.isNotBlank())Text(error,color=Color.Red,textAlign=TextAlign.Center)
                    OutlinedButton(onClick={
                        cart.clear();order=null;submission=UUID.randomUUID().toString()
                        service="Dine-in";error="";step="welcome";load()
                    }){ Text("Start another order") }
                }
            }
        }
    }
    selected?.let { p ->
        var size by remember(p.id){mutableStateOf("Regular")}
        var sugar by remember(p.id){mutableStateOf("100%")}
        var ice by remember(p.id){mutableStateOf("Regular")}
        var shot by remember(p.id){mutableStateOf(false)}
        var qty by remember(p.id){mutableIntStateOf(1)}
        AlertDialog(onDismissRequest={selected=null},
            title={Text(p.name,fontWeight=FontWeight.Black)},
            text={
                Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("Choose your drink",color=Bean)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf("Regular","Large").forEach {
                            FilterChip(selected=size==it,onClick={size=it},label={Text(it)})
                        }
                    }
                    Text("Sugar",fontWeight=FontWeight.Bold)
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        listOf("0%","50%","100%").forEach {
                            FilterChip(selected=sugar==it,onClick={sugar=it},label={Text(it)})
                        }
                    }
                    Text("Ice",fontWeight=FontWeight.Bold)
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        listOf("No ice","Less","Regular").forEach {
                            FilterChip(selected=ice==it,onClick={ice=it},label={Text(it)})
                        }
                    }
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Checkbox(checked=shot,onCheckedChange={shot=it})
                        Text("Extra espresso shot · +₱40")
                    }
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Text("Quantity",modifier=Modifier.weight(1f))
                        TextButton(onClick={if(qty>1)qty--}){Text("−")}
                        Text("$qty")
                        TextButton(onClick={if(qty<99)qty++}){Text("+")}
                    }
                    Text("Estimated: ${money(lineUnit(KioskLine(p,qty,size,sugar,ice,shot))*qty)}",fontSize=20.sp,fontWeight=FontWeight.Bold)
                }
            },
            confirmButton={Button(onClick={cart.add(KioskLine(p,qty,size,sugar,ice,shot));selected=null}){Text("Add to order")}},
            dismissButton={TextButton(onClick={selected=null}){Text("Cancel")}}
        )
    }
}

@Composable
private fun QrImage(data:String){
    val bitmap= remember(data) {
        runCatching {
            require(data.startsWith("data:image/png;base64,")||data.startsWith("data:image/jpeg;base64,"))
            val bytes=Base64.decode(data.substringAfter("base64,"),Base64.DEFAULT)
            require(bytes.size in 100..500000)
            BitmapFactory.decodeByteArray(bytes,0,bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    if(bitmap!=null)Image(bitmap,contentDescription="Dynamic QR Ph payment code",
        modifier=Modifier.size(240.dp).background(Color.White).padding(8.dp))
    else Text("Unable to display payment QR. Ask the cashier for assistance.",color=Color.Red)
}
