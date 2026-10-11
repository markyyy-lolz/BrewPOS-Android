package com.azurate.brewpos.kiosk

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.util.Locale

private val Ink=Color(0xFF251C16)
private val Forest=Color(0xFF143C2B)
private val ForestLight=Color(0xFF23513B)
private val Ivory=Color(0xFFF8F4ED)
private val Cream=Color(0xFFEDE7DB)
private val Gold=Color(0xFFD2AF73)
private val Quiet=Color(0xFF897C70)
private fun peso(amount:Long)="₱"+String.format(Locale.US,"%,.2f",amount/100.0)
private fun lineTotal(lines:List<KioskLine>):Long=lines.sumOf{
    (it.product.price+(if(it.size=="Large")3000L else 0L)+(if(it.extraShot)4000L else 0L))*it.quantity
}
private const val HERO_PHOTO="https://images.unsplash.com/photo-1461023058943-07fcbe16d735?w=1100&q=85&fit=crop"
internal fun productPhoto(p:KioskProduct):String {
    val name=p.name.lowercase(Locale.ROOT)
    val category=p.category.lowercase(Locale.ROOT)
    val photo=when {
        "croissant" in name || "pastr" in category -> "photo-1555507036-ab1f4038808a"
        "muffin" in name || "bakery" in category || "bread" in category -> "photo-1607958996333-41aef7caefaa"
        "matcha" in name || "tea" in category -> "photo-1515823064-d6e0c04616a7"
        "cappuccino" in name || "latte" in name -> "photo-1509042239860-f550ce710b93"
        "caramel" in name || "macchiato" in name -> "photo-1461023058943-07fcbe16d735"
        "americano" in name || "espresso" in name || "coffee" in category -> "photo-1495474472287-4d71bcdd2085"
        "food" in category || "sandwich" in name -> "photo-1528735602780-2552fd46c7af"
        else -> "photo-1509042239860-f550ce710b93"
    }
    return "https://images.unsplash.com/"+photo+"?w=500&q=78&fit=crop"
}
private fun symbol(p:KioskProduct):String{
    val c=p.category.lowercase(Locale.ROOT)
    return when{
        "pastr" in c||"bread" in c ->"🥐"
        "food" in c ->"🥪"
        "tea" in c ->"🍵"
        "non" in c ->"🥤"
        else->"☕"
    }
}
@Composable
private fun Photo(url:String,description:String,modifier:Modifier=Modifier){
    val ctx=LocalContext.current
    val request=remember(ctx,url){ImageRequest.Builder(ctx).data(url).crossfade(true).build()}
    AsyncImage(model=request,contentDescription=description,
        modifier=modifier,contentScale=ContentScale.Crop)
}
@Composable
private fun Hero(compact:Boolean) {
    Box(Modifier.fillMaxWidth().height(if(compact)197.dp else 232.dp)
        .clip(RoundedCornerShape(24.dp)).background(Forest)) {
        Box(Modifier.matchParentSize().background(
            Brush.horizontalGradient(listOf(Color(0xFF24140E),Forest,Color(0xFF8D6B49)))))
        Photo(HERO_PHOTO,"Freshly prepared coffee",Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(
            Color(0xF5130D08),Color(0xD618130C),Color(0x17100906)))))
        Column(Modifier.fillMaxSize().padding(if(compact)17.dp else 25.dp),
            verticalArrangement=Arrangement.SpaceBetween) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("❧",color=Gold,fontSize=28.sp)
                Spacer(Modifier.width(8.dp))
                Column{
                    Text("BrewPOS",fontFamily=FontFamily.Serif,color=Color.White,
                        fontWeight=FontWeight.Bold,fontSize=if(compact)24.sp else 28.sp)
                    Text("S E L F   O R D E R",color=Gold,fontSize=9.sp,
                        fontWeight=FontWeight.Bold,letterSpacing=1.sp)
                }
                Spacer(Modifier.weight(1f))
                Text("GOOD COFFEE\nBRIGHTER DAYS",color=Color.White.copy(alpha=.82f),
                    fontSize=8.sp,textAlign=TextAlign.End,lineHeight=12.sp)
            }
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
                Text("Great Coffee\nBrighter Days",fontFamily=FontFamily.Serif,
                    fontSize=if(compact)31.sp else 40.sp,
                    lineHeight=if(compact)32.sp else 42.sp,
                    color=Color.White,fontWeight=FontWeight.SemiBold)
                Text("Crafted for your everyday moments.",color=Color(0xFFE7DECF),
                    fontSize=if(compact)11.sp else 13.sp)
                Row(horizontalArrangement=Arrangement.spacedBy(13.dp),
                    verticalAlignment=Alignment.CenterVertically){
                    Text("✦  Premium ingredients",fontSize=9.sp,color=Gold)
                    Text("♡  Made with care",fontSize=9.sp,color=Gold)
                }
            }
        }
    }
}
@Composable
private fun ServiceCard(
    label:String,subtitle:String,emoji:String,selected:Boolean,compact:Boolean,
    onClick:()->Unit,modifier:Modifier=Modifier
){
    Surface(modifier.clickable(onClick=onClick).height(if(compact)102.dp else 112.dp),
        shape=RoundedCornerShape(20.dp),color=if(selected)Forest else Color.White,
        border=BorderStroke(if(selected)1.5.dp else 1.dp,
            if(selected)Gold else Color(0xFFE7DDD1)),
        shadowElevation=if(selected)4.dp else 1.dp){
        Row(Modifier.fillMaxSize()
            .background(if(selected)Brush.linearGradient(listOf(Forest,ForestLight))
                else Brush.linearGradient(listOf(Color.White,Color(0xFFFAF5EE))))
            .padding(if(compact)12.dp else 15.dp),
            verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(if(compact)7.dp else 12.dp)){
            Text(emoji,fontSize=if(compact)26.sp else 33.sp)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){
                Text(label,fontFamily=FontFamily.Serif,fontWeight=FontWeight.Bold,
                    color=if(selected)Color.White else Ink,
                    fontSize=if(compact)18.sp else 23.sp,maxLines=1)
                Text(subtitle,color=if(selected)Color(0xFFD7E0D6)else Quiet,
                    fontSize=if(compact)10.sp else 12.sp,lineHeight=14.sp,
                    maxLines=2)
            }
            if(!compact){
                Box(Modifier.size(27.dp).background(if(selected)Color(0xFF365A43)else Cream,CircleShape),
                    contentAlignment=Alignment.Center){
                    Text("›",fontSize=20.sp,color=if(selected)Gold else Ink)
                }
            }
        }
    }
}
@Composable
private fun CategoryTabs(categories:List<String>,selected:String,onSelect:(String)->Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        .padding(vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        categories.forEach { category ->
            val active=category==selected
            val icon=when{
                category=="All"->"✦"
                category.contains("tea",ignoreCase=true)->"🍵"
                category.contains("pastr",ignoreCase=true)->"🥐"
                category.contains("non",ignoreCase=true)->"🥤"
                category.contains("food",ignoreCase=true)->"🥪"
                else->"☕"
            }
            Surface(Modifier.clickable{onSelect(category)},
                shape=RoundedCornerShape(32.dp),
                color=if(active)Forest else Color(0xFFFBF8F2),
                border=if(active)null else BorderStroke(1.dp,Color(0xFFEAE3D9))){
                Row(Modifier.padding(horizontal=15.dp,vertical=10.dp),
                    verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(7.dp)){
                    Text(icon,fontSize=12.sp)
                    Text(category,color=if(active)Color.White else Ink,
                        fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                }
            }
        }
    }
}
@Composable
private fun KioskProductTile(p:KioskProduct,enabled:Boolean,compact:Boolean,onClick:()->Unit){
    Surface(Modifier.fillMaxWidth().clickable(enabled=enabled,onClick=onClick),
        shape=RoundedCornerShape(15.dp),color=Color.White,
        border=BorderStroke(1.dp,Color(0xFFE9E3D8)),shadowElevation=1.dp) {
        Column {
            Box(Modifier.fillMaxWidth().height(if(compact)104.dp else 137.dp)
                .background(Brush.linearGradient(listOf(Color(0xFFD8C7B5),
                    Color(0xFFEFE3D4)))),contentAlignment=Alignment.Center){
                Text(symbol(p),fontSize=50.sp)
                Photo(productPhoto(p),"Illustrative "+p.category+" photo",
                    Modifier.fillMaxSize().alpha(if(enabled)1f else .45f))
                if(!enabled)Surface(color=Color(0xF0FEF8EB),
                    shape=RoundedCornerShape(9.dp),
                    modifier=Modifier.align(Alignment.TopEnd).padding(7.dp)){
                    Text("SOLD OUT",color=Color(0xFFA05232),
                        modifier=Modifier.padding(horizontal=7.dp,vertical=5.dp),
                        fontWeight=FontWeight.Black,fontSize=9.sp)
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=10.dp),
                verticalArrangement=Arrangement.spacedBy(4.dp)){
                Text(p.name,fontFamily=FontFamily.Serif,
                    fontSize=if(compact)14.sp else 17.sp,
                    fontWeight=FontWeight.Bold,color=Ink,maxLines=2,
                    minLines=2,overflow=TextOverflow.Ellipsis,lineHeight=18.sp)
                Text(p.category,fontSize=10.sp,color=Quiet,
                    maxLines=1,overflow=TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth().padding(top=6.dp),
                    verticalAlignment=Alignment.CenterVertically){
                    Text(peso(p.price),fontWeight=FontWeight.Black,color=Ink,
                        fontSize=if(compact)14.sp else 16.sp,
                        modifier=Modifier.weight(1f))
                    Box(Modifier.size(30.dp)
                        .background(if(enabled)Forest else Color(0xFFCFD4CC),CircleShape),
                        contentAlignment=Alignment.Center){
                        Text(if(enabled)"+"else"×",color=Color.White,
                            fontSize=20.sp,fontWeight=FontWeight.Bold)
                    }
                }
            }
        }
    }
}
@Composable
private fun FooterButton(
    label:String,caption:String,emoji:String,
    selected:Boolean,enabled:Boolean,
    onClick:()->Unit,modifier:Modifier=Modifier
){
    Surface(modifier.heightIn(min=62.dp).clickable(enabled=enabled,onClick=onClick),
        color=if(selected)Forest else Color(0xFFFFFBF6),
        shape=RoundedCornerShape(17.dp),
        border=BorderStroke(1.dp,if(selected)Gold else Color(0xFFE5D6C5))){
        Row(Modifier.fillMaxWidth().padding(horizontal=11.dp,vertical=11.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Text(emoji,fontSize=23.sp)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){
                Text(label,fontFamily=FontFamily.Serif,
                    color=if(selected)Color.White else Ink,fontWeight=FontWeight.Bold,
                    fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(caption,fontSize=9.sp,
                    color=if(selected)Color(0xFFB8CFC1)else Quiet,
                    maxLines=2,lineHeight=11.sp)
            }
            Text("›",fontSize=23.sp,color=if(selected)Gold else Ink)
        }
    }
}
@Composable
internal fun ReferenceBrowse(
    menu:List<KioskProduct>,loading:Boolean,error:String,
    service:String,onService:(String)->Unit,
    category:String,onCategory:(String)->Unit,
    cart:List<KioskLine>,qrPhEnabled:Boolean,
    onAdd:(KioskProduct)->Unit,onCart:()->Unit,
    onPayment:(String)->Unit,onRefresh:()->Unit
){
    BoxWithConstraints(Modifier.fillMaxSize().background(Ivory)){
        val compact=maxWidth<530.dp
        val cols=when {maxWidth>=820.dp->4;maxWidth>=610.dp->3;else->2}
        val filter=menu.filter{category=="All"||it.category==category}
        val cats=listOf("All")+menu.map{it.category}.distinct().sorted()
        val count=cart.sumOf{it.quantity}
        Column(Modifier.fillMaxSize()){
            LazyVerticalGrid(columns=GridCells.Fixed(cols),modifier=Modifier.weight(1f),
                horizontalArrangement=Arrangement.spacedBy(if(compact)10.dp else 15.dp),
                verticalArrangement=Arrangement.spacedBy(13.dp),
                contentPadding=PaddingValues(start=if(compact)13.dp else 20.dp,
                    end=if(compact)13.dp else 20.dp,top=15.dp,bottom=18.dp)){
                item(span={GridItemSpan(maxLineSpan)}){Hero(compact)}
                item(span={GridItemSpan(maxLineSpan)}){
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                        ServiceCard("Dine-in","Enjoy your coffee\nhere with us.","☕",
                            service=="Dine-in",compact,{onService("Dine-in")},
                            Modifier.weight(1f))
                        ServiceCard("Takeout","Good coffee\non the go.","🛍",
                            service=="Takeout",compact,{onService("Takeout")},
                            Modifier.weight(1f))
                    }
                }
                item(span={GridItemSpan(maxLineSpan)}){
                    Column(verticalArrangement=Arrangement.spacedBy(7.dp)){
                        Row(verticalAlignment=Alignment.CenterVertically){
                            Text("Discover the menu",fontFamily=FontFamily.Serif,
                                fontSize=if(compact)21.sp else 26.sp,
                                fontWeight=FontWeight.Bold,color=Ink,
                                modifier=Modifier.weight(1f))
                            TextButton(onClick=onRefresh){
                                Text("↻ Refresh",color=Forest,fontSize=12.sp)
                            }
                        }
                        CategoryTabs(cats,category,onCategory)
                    }
                }
                if(error.isNotBlank()||(!loading&&menu.isEmpty())){
                    item(span={GridItemSpan(maxLineSpan)}){
                        Surface(color=Color.White,shape=RoundedCornerShape(16.dp)){
                            Column(Modifier.padding(17.dp),
                                verticalArrangement=Arrangement.spacedBy(8.dp)){
                                Text("Our menu is being prepared",color=Ink,
                                    fontFamily=FontFamily.Serif,fontSize=19.sp,
                                    fontWeight=FontWeight.Bold)
                                Text(if(error.isNotBlank())error
                                    else "There are no available café products yet. Please ask a staff member or refresh the menu.",
                                    color=Quiet,fontSize=12.sp,lineHeight=17.sp)
                                TextButton(onClick=onRefresh){
                                    Text("Refresh café menu →",color=Forest)
                                }
                            }
                        }
                    }
                }else if(loading&&menu.isEmpty()){
                    item(span={GridItemSpan(maxLineSpan)}){
                        LinearProgressIndicator(Modifier.fillMaxWidth(),color=Forest)
                    }
                }
                items(filter,key={it.id}){p->
                    val enabled=!p.tracked||(p.stock.toBigDecimalOrNull()?.signum()?:0)>0
                    KioskProductTile(p,enabled,compact,{onAdd(p)})
                }
            }
            Surface(color=Color(0xFFFDF8EF),shadowElevation=9.dp,
                modifier=Modifier.fillMaxWidth()){
                Column(Modifier.padding(horizontal=if(compact)10.dp else 20.dp,vertical=8.dp),
                    verticalArrangement=Arrangement.spacedBy(7.dp)){
                    Surface(Modifier.fillMaxWidth().clickable(enabled=count>0,onClick=onCart),
                        color=Forest,shape=RoundedCornerShape(15.dp)){
                        Row(Modifier.fillMaxWidth().padding(horizontal=17.dp,vertical=13.dp),
                            verticalAlignment=Alignment.CenterVertically){
                            Text("♧",fontSize=24.sp,color=Color.White)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)){
                                Text("Your Order",fontFamily=FontFamily.Serif,
                                    fontSize=17.sp,color=Color.White,fontWeight=FontWeight.Bold)
                                Text("$count items  •  "+peso(lineTotal(cart)),
                                    color=Color(0xFFD4E0D4),fontSize=11.sp)
                            }
                            Text(if(count==0)"Add items"else"View Cart  ›",
                                color=Color.White,fontSize=12.sp,fontWeight=FontWeight.Bold)
                        }
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        FooterButton("Pay at Cashier","Complete at counter","▣",
                            selected=false,enabled=count>0,
                            onClick={onPayment("cashier")},modifier=Modifier.weight(1f))
                        FooterButton("QR Ph",if(qrPhEnabled)"Pay securely by QR" else "Not available yet",
                            "▦",selected=qrPhEnabled,enabled=count>0&&qrPhEnabled,
                            onClick={onPayment("qrph")},modifier=Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
