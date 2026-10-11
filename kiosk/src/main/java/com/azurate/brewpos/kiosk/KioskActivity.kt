package com.azurate.brewpos.kiosk

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
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

private val Dark = Color(0xFF2D1F1A)
private val Caramel = Color(0xFFC4602F)
private val Bean = Color(0xFF876043)
private val Canvas = Color(0xFFF8F5F0)
private val Moss = Color(0xFF254D3B)
private val Soft = Color(0xFFF1EAE0)
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
        // Let Compose respect Android status/navigation insets; previous legacy
        // LAYOUT_FULLSCREEN flags caused the BrewPOS header to be clipped.
        setContent {
            MaterialTheme(colorScheme=KioskColors) {
                Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),color=Canvas) {
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
    val scope=rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var branches by remember { mutableStateOf<List<KioskBranch>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var emailCodeMode by remember { mutableStateOf(true) }
    var emailSent by remember { mutableStateOf(false) }
    var emailCode by remember { mutableStateOf("") }
    BoxWithConstraints(Modifier.fillMaxSize().background(Canvas)) {
        val wide=maxWidth>=720.dp
        @Composable fun BrandHero(modifier:Modifier) {
            Column(modifier.background(Brush.linearGradient(listOf(Moss,Color(0xFF4A7154),Dark)))
                .padding(if(wide)26.dp else 15.dp),verticalArrangement=Arrangement.SpaceBetween) {
                Text(if(wide)"☕  BREWPOS\n      S E L F   O R D E R"else"☕ BREWPOS  •  SELF ORDER",
                    color=Color.White,fontWeight=FontWeight.Black,
                    fontSize=if(wide)23.sp else 19.sp,lineHeight=26.sp)
                if(wide) Column(verticalArrangement=Arrangement.spacedBy(9.dp)) {
                    Text("Good coffee.\nBrighter days.",color=Color.White,fontWeight=FontWeight.Black,
                        fontSize=37.sp,lineHeight=42.sp)
                    Text("Your next favorite cup starts here.",color=Color(0xFFE7ECE2),fontSize=13.sp)
                } else Text("A beautiful way to order your next favorite cup.",
                    color=Color(0xFFE7ECE2),fontSize=13.sp)
                if(wide) Text("AZURATE SOFTWARE SOLUTIONS",color=Color(0xFFC4D4C8),
                    fontSize=10.sp,letterSpacing=1.sp)
            }
        }
        @Composable fun Setup(modifier:Modifier) {
            Box(modifier.padding(18.dp),contentAlignment=Alignment.Center) {
                Surface(color=Color.White,shape=RoundedCornerShape(25.dp),shadowElevation=5.dp,
                    modifier=Modifier.fillMaxWidth().widthIn(max=500.dp)) {
                    Column(Modifier.then(if(wide)Modifier.verticalScroll(rememberScrollState())else Modifier)
                        .padding(if(wide)24.dp else 18.dp),
                        verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("Connect your kiosk",fontSize=27.sp,fontWeight=FontWeight.Black,color=Dark)
                        Text("One-time secure manager setup. Customers will see the self-order menu after pairing.",
                            color=Bean,fontSize=13.sp,lineHeight=19.sp)
                        HorizontalDivider(color=Soft)
                        if(branches.isEmpty()){
                            OutlinedTextField(email,{email=it;emailSent=false},
                                label={Text("Manager email")},singleLine=true,
                                shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth())
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                FilterChip(selected=emailCodeMode,onClick={emailCodeMode=true},
                                    label={Text("Email code")})
                                FilterChip(selected=!emailCodeMode,onClick={emailCodeMode=false},
                                    label={Text("Password")})
                            }
                            if(emailCodeMode) {
                                if(emailSent) OutlinedTextField(emailCode,{emailCode=it.filter{c->c.isDigit()}.take(8)},
                                    label={Text("Verification code")},singleLine=true,
                                    shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth())
                                Button(enabled=!busy&&email.isNotBlank()&&(!emailSent||emailCode.length>=6),
                                    onClick={
                                        busy=true
                                        scope.launch {
                                            if(!emailSent){
                                                runCatching{KioskCloud.sendManagerEmailCode(email)}
                                                    .onSuccess{emailSent=true;status="Check the manager email for a verification code."}
                                                    .onFailure{status=it.message?:"Email code request failed"}
                                            } else {
                                                runCatching{KioskCloud.branchesByEmailCode(email,emailCode)}
                                                    .onSuccess{branches=it;emailCode="";status=if(it.isEmpty())"No authorized manager branch found."else"Choose your café branch."}
                                                    .onFailure{status=it.message?:"Verification failed"}
                                            }
                                            busy=false
                                        }
                                    },modifier=Modifier.fillMaxWidth().heightIn(min=55.dp),
                                    shape=RoundedCornerShape(16.dp),
                                    colors=ButtonDefaults.buttonColors(containerColor=Caramel)){
                                    Text(if(busy)"Please wait…"else if(emailSent)"Verify email code →"else"Send email code →",
                                        fontWeight=FontWeight.Bold)
                                }
                                if(emailSent)TextButton(onClick={
                                    emailSent=false;emailCode="";status=""
                                }){Text("Request a new code")}
                            } else {
                                OutlinedTextField(password,{password=it},
                                    label={Text("Password")},
                                    visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                    singleLine=true,shape=RoundedCornerShape(14.dp),
                                    modifier=Modifier.fillMaxWidth())
                                Button(enabled=!busy&&email.isNotBlank()&&password.isNotBlank(),
                                    onClick={
                                        busy=true
                                        scope.launch {
                                            runCatching{KioskCloud.availableBranches(email,password)}
                                                .onSuccess{branches=it;password="";status=if(it.isEmpty())"No authorized branch available."else"Choose your café branch."}
                                                .onFailure{status=it.message?:"Could not verify manager"}
                                            busy=false
                                        }
                                    },modifier=Modifier.fillMaxWidth().heightIn(min=55.dp),
                                    shape=RoundedCornerShape(16.dp),
                                    colors=ButtonDefaults.buttonColors(containerColor=Caramel)) {
                                    Text(if(busy)"Verifying…"else"Verify & select branch →",
                                        fontWeight=FontWeight.Bold)
                                }
                            }
                        } else {
                            Text("Choose a branch",fontWeight=FontWeight.Bold,color=Moss)
                            branches.forEach{branch->
                                OutlinedButton(enabled=!busy,onClick={
                                    busy=true
                                    scope.launch {
                                        runCatching{KioskCloud.pair(ctx,branch)}
                                            .onSuccess{onPaired()}
                                            .onFailure{status=it.message?:"Pairing failed"}
                                        busy=false
                                    }
                                },modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),
                                    shape=RoundedCornerShape(15.dp)){Text(branch.name)}
                            }
                        }
                        if(status.isNotBlank())Text(status,color=if(status.contains("fail",true))Color.Red else Moss,fontSize=12.sp)
                        Text("Manager credentials are not saved to this tablet.",fontSize=11.sp,color=Bean)
                    }
                }
            }
        }
        if(wide) Row(Modifier.fillMaxSize()){
            BrandHero(Modifier.weight(1f).fillMaxHeight())
            Setup(Modifier.weight(1f).fillMaxHeight())
        }else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .imePadding()){
            BrandHero(Modifier.fillMaxWidth().height(126.dp))
            Setup(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun CustomerKiosk(){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var menu by remember { mutableStateOf<List<KioskProduct>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var step by remember { mutableStateOf("browse") }
    var paymentChoice by remember { mutableStateOf("cashier") }
    var qrPhTestEnabled by remember { mutableStateOf(false) }
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
                .onSuccess { menu=it;qrPhTestEnabled=KioskCloud.qrPhTestEnabled;error="";loading=false }
                .onFailure { error="Connection needed to load the café menu: ${it.message}";loading=false }
        }
    }
    LaunchedEffect(Unit){load()}
    // Automatically pick up newly saved cloud menu items while the kiosk is idle.
    LaunchedEffect(step) {
        if(step=="browse"){
            while(true){
                delay(20000)
                if(!loading)load()
            }
        }
    }
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
        // The browsing hero contains the BrewPOS mark. Keep a compact
        // navigation header only for review/confirmation so the customer
        // browsing screen matches the full-bleed reference without duplication.
        if(step!="browse") Row(Modifier.fillMaxWidth().background(Color.White)
            .padding(horizontal=17.dp,vertical=10.dp),
            horizontalArrangement=Arrangement.SpaceBetween,
            verticalAlignment=Alignment.CenterVertically){
            Text("❧  BrewPOS",color=Dark,fontSize=22.sp,fontWeight=FontWeight.Black,
                fontFamily=androidx.compose.ui.text.font.FontFamily.Serif)
            Text("SELF ORDER",color=Moss,fontSize=10.sp,letterSpacing=2.sp)
        }
        when(step){
            "browse" -> ReferenceBrowse(
                menu=menu,loading=loading,error=error,
                service=service,onService={service=it},
                category=category,onCategory={category=it},
                cart=cart.toList(),qrPhEnabled=qrPhTestEnabled,
                onAdd={selected=it},onCart={paymentChoice="cashier";step="cart"},
                onPayment={choice->paymentChoice=choice;step="cart"},
                onRefresh={load()}
            )
            "cart" -> Column(Modifier.fillMaxSize().padding(24.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically){
                    TextButton(onClick={step="browse"}){Text("← Menu")}
                    Spacer(Modifier.width(12.dp))
                    Text("Review your order",fontSize=26.sp,fontWeight=FontWeight.Black,color=Dark)
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
                Text("Total: ${money(quotedTotal(cart))}",fontSize=27.sp,fontWeight=FontWeight.Black,color=Caramel)
                Text("Final price is checked by BrewPOS Cloud before accepting the order.",color=Bean,fontSize=12.sp)
                Spacer(Modifier.height(9.dp))
                Text("Payment method",fontFamily=androidx.compose.ui.text.font.FontFamily.Serif,
                    color=Dark,fontWeight=FontWeight.Black,fontSize=19.sp)
                Row(horizontalArrangement=Arrangement.spacedBy(9.dp)){
                    FilterChip(selected=paymentChoice=="cashier",
                        onClick={paymentChoice="cashier"},
                        label={Text("▣  Pay at Cashier")})
                    FilterChip(selected=paymentChoice=="qrph",
                        onClick={paymentChoice="qrph"},
                        enabled=qrPhTestEnabled,
                        label={Text(if(qrPhTestEnabled)"▦  QR Ph TEST"else"▦  QR Ph unavailable")})
                }
                Button(modifier=Modifier.fillMaxWidth().heightIn(min=64.dp),
                    enabled=!busy&&cart.isNotEmpty()&&(paymentChoice=="cashier"||qrPhTestEnabled),
                    onClick={
                        busy=true;error=""
                        val id=submission
                        scope.launch {
                            runCatching { KioskCloud.order(ctx,id,service,paymentChoice,cart.toList()) }
                                .onSuccess { order=it;step="confirmation" }
                                .onFailure { error=it.message?:"Order could not be submitted" }
                            busy=false
                        }
                    },colors=ButtonDefaults.buttonColors(containerColor=Moss),
                    shape=RoundedCornerShape(16.dp)){
                    Text(if(busy)"Submitting order…"
                        else if(paymentChoice=="cashier")"Place Order · Pay at Cashier  →"
                        else "Place Order · QR Ph TEST  →",fontSize=15.sp,
                        fontWeight=FontWeight.Bold)
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
                        service="Dine-in";paymentChoice="cashier";error="";step="browse";load()
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

@Composable
private fun PremiumProductCard(product:KioskProduct,available:Boolean,onClick:()->Unit){
    val art=when {
        product.category.contains("pastr",true)->"🥐"
        product.category.contains("food",true)->"🥪"
        product.category.contains("tea",true)->"🍵"
        product.category.contains("non",true)->"🥤"
        else->"☕"
    }
    val tone=when {
        product.category.contains("tea",true)->Color(0xFFDBE8D5)
        product.category.contains("pastr",true)->Color(0xFFF0D9BE)
        else->Color(0xFFE9D9CA)
    }
    Surface(Modifier.fillMaxWidth().clickable(enabled=available,onClick=onClick),
        shape=RoundedCornerShape(20.dp),color=Color.White,shadowElevation=2.dp){
        Column(Modifier.padding(9.dp)){
            Box(Modifier.fillMaxWidth().height(118.dp)
                .background(Brush.verticalGradient(listOf(tone,Canvas)),RoundedCornerShape(14.dp)),
                contentAlignment=Alignment.Center){
                Box(Modifier.size(87.dp).background(Color.White.copy(alpha=.3f),CircleShape),
                    contentAlignment=Alignment.Center){Text(art,fontSize=54.sp)}
            }
            Column(Modifier.padding(horizontal=5.dp,vertical=8.dp)){
                Text(product.name,fontSize=15.sp,fontWeight=FontWeight.Bold,
                    minLines=2,maxLines=2,color=Dark)
                Text(product.category,fontSize=11.sp,color=Bean)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment=Alignment.CenterVertically){
                    Text(money(product.price),fontSize=16.sp,color=Caramel,
                        fontWeight=FontWeight.Black,modifier=Modifier.weight(1f))
                    Text(if(available)"＋"else"×",fontSize=21.sp,
                        color=if(available)Moss else Color.Red)
                }
            }
        }
    }
}
