package com.brewpos.cafe

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Espresso = Color(0xFF30241D)
private val Muted = Color(0xFF756458)
private val Sage = Color(0xFF254D3B)

@Composable
fun BrewSidebar(selected: String, onSelect: (String) -> Unit) {
    val wide=androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp>=1000
    Column(
        Modifier.width(if(wide)178.dp else 135.dp).fillMaxHeight()
            .background(Color(0xFFFFFCF8)).padding(horizontal=12.dp,vertical=20.dp),
        verticalArrangement=Arrangement.spacedBy(7.dp)
    ){
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Box(Modifier.size(37.dp).background(Color(0xFFF1E5D8),RoundedCornerShape(13.dp)),
                contentAlignment=Alignment.Center){Text("☕",fontSize=25.sp)}
            Column {
                Text("BREWPOS",fontWeight=FontWeight.Black,color=Espresso,
                    fontSize=if(wide)17.sp else 14.sp)
                Text("POINT OF SALE",fontSize=8.sp,letterSpacing=1.1.sp,color=Muted)
            }
        }
        Spacer(Modifier.height(25.dp))
        listOf(
            Triple("Dashboard","⌂","Dashboard"),
            Triple("POS","🛒","Orders"),
            Triple("Menu","☷","Menu"),
            Triple("Kiosk","▤","Kiosk Orders"),
            Triple("Orders","◫","Order History"),
            Triple("Reports","▥","Reports"),
            Triple("Cloud","☁","Cloud Sync"),
            Triple("Settings","⚙","Settings")
        ).forEach{(key,symbol,label)->
            val active=selected==key
            Row(
                Modifier.fillMaxWidth()
                    .background(if(active) Sage else Color.Transparent,RoundedCornerShape(15.dp))
                    .clickable{onSelect(key)}
                    .padding(horizontal=12.dp,vertical=13.dp),
                verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(10.dp)
            ){
                Text(symbol,fontSize=17.sp,color=if(active)Color.White else Espresso)
                Text(label,color=if(active)Color.White else Espresso,
                    fontSize=if(wide)13.sp else 11.sp,
                    fontWeight=if(active)FontWeight.Bold else FontWeight.Medium)
            }
        }
        Spacer(Modifier.weight(1f))
        Surface(color=Color(0xFFF4EFE5),shape=RoundedCornerShape(17.dp)){
            Column(Modifier.fillMaxWidth().padding(13.dp),
                verticalArrangement=Arrangement.spacedBy(7.dp)){
                Text("Good coffee.\nBrighter days.",fontSize=15.sp,
                    lineHeight=20.sp,color=Espresso,fontWeight=FontWeight.Black)
                Text("by Azurate Software Solutions",fontSize=9.sp,color=Muted,lineHeight=13.sp)
            }
        }
    }
}

@Composable
private fun StatCard(title: String, amount: String, caption: String, symbol: String, modifier: Modifier) {
    Surface(modifier, color = Color.White, shape = RoundedCornerShape(22.dp), shadowElevation = 1.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontSize = 12.sp, color = Muted)
                Text(symbol, fontSize = 20.sp, color = Sage)
            }
            Text(amount, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, color = Espresso)
            Text(caption, fontSize = 11.sp, color = Sage)
        }
    }
}

@Composable
fun DashboardView(db: StoreDb, products: List<Product>, sales: List<Sale>,
    onSell: () -> Unit, onOrders: () -> Unit, onMenu: () -> Unit
) {
    val start = remember { java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis }
    val daily = remember(sales) { db.stats(start) }
    val active = sales.count { it.status != "Served" }
    val low = products.count { it.active && it.trackStock && it.stock <= 5 }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Overview", fontSize = 29.sp, color = Espresso, fontWeight = FontWeight.Black)
                Text("One good day, one great cup at a time.", color = Muted, fontSize = 13.sp)
            }
            Button(onClick = onSell, shape = RoundedCornerShape(15.dp)) { Text("+ New order") }
        }
        BoxWithConstraints {
            if (maxWidth >= 800.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("Today's sales", peso(daily.revenueCents), "Stored locally", "↗", Modifier.weight(1f))
                    StatCard("Orders", daily.orders.toString(), "Today", "☕", Modifier.weight(1f))
                    StatCard("Barista queue", active.toString(), "Open orders", "▤", Modifier.weight(1f))
                    StatCard("Low stock", low.toString(), "Items to review", "!", Modifier.weight(1f))
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("Today's sales", peso(daily.revenueCents), "Locally saved", "↗", Modifier.weight(1f))
                    StatCard("Orders", daily.orders.toString(), "Today", "☕", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("Barista queue", active.toString(), "Open orders", "▤", Modifier.weight(1f))
                    StatCard("Low stock", low.toString(), "Items to review", "!", Modifier.weight(1f))
                }
            }
        }
        Surface(color = Espresso, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Made for the morning rush.", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Text("Beautifully simple checkout with a customer receipt and barista slip on one printer.",
                    color = Color(0xFFE2CABC), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSell) { Text("Open cashier →") }
                    OutlinedButton(onClick = onOrders) { Text("Barista queue", color = Color.White) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Recent orders", color = Espresso, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text(db.pendingSyncCount().toString() + " sale(s) queued locally · Upload not enabled yet", color = Muted, fontSize = 11.sp)
            }
            TextButton(onClick = onMenu) { Text("Manage menu →") }
        }
        if (sales.isEmpty()) Text("No sales recorded yet.", color = Muted)
        else sales.take(5).forEach { sale ->
            Surface(color = Color.White, shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sale.receiptNo, color = Espresso, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(dateTime(sale.createdAt), color = Muted, fontSize = 11.sp)
                    }
                    Text(sale.status, color = Sage, fontSize = 12.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(peso(sale.total.toLong()), color = Espresso, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
