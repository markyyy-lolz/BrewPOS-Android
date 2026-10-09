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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * StorePOS-inspired operational layout, deliberately distinct BrewPOS
 * hospitality branding. Shared colors keep POS, dashboard, sidebar and
 * receipt-management surfaces consistent across tablet and phone layouts.
 */
object BrewPalette {
    val Ink = Color(0xFF1C3532)
    val Muted = Color(0xFF637470)
    val Forest = Color(0xFF155E52)
    val DeepForest = Color(0xFF102F2B)
    val NavigationActive = Color(0xFF27554B)
    val Background = Color(0xFFF4F7F5)
    val Cream = Color(0xFFF5EBDD)
    val Accent = Color(0xFFE2A065)
    val Border = Color(0xFFE0E9E4)
    val Positive = Color(0xFF257964)
    val White = Color.White
}

@Composable
fun BrewSidebar(selected: String, businessType: String, onSelect: (String) -> Unit) {
    val tabs = listOf(
        Triple("Dashboard", "▦", "Overview"),
        Triple("POS", "☕", "New order"),
        Triple("Orders", "▤", "Kitchen queue"),
        Triple("Menu", "☰", "Products"),
        Triple("Reports", "▥", "Insights"),
        Triple("Cloud", "☁", "Sync center"),
        Triple("Settings", "⚙", "Preferences")
    )
    Column(
        Modifier.width(205.dp).fillMaxHeight().background(BrewPalette.DeepForest)
            .padding(horizontal = 12.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(43.dp)
                    .background(BrewPalette.NavigationActive, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) { Text("☕", fontSize = 24.sp) }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("BrewPOS", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                Text(businessType.uppercase(), color = Color(0xFFBDD8CD), fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(25.dp))
        Text(
            "WORKSPACE", color = Color(0xFF9BBCAF), fontSize = 10.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 13.dp, bottom = 8.dp)
        )
        tabs.forEach { (key, glyph, helper) ->
            val active = selected == key
            Row(
                Modifier.fillMaxWidth()
                    .background(
                        if (active) BrewPalette.NavigationActive else Color.Transparent,
                        RoundedCornerShape(13.dp)
                    )
                    .clickable { onSelect(key) }
                    .padding(horizontal = 13.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(glyph, fontSize = 20.sp, color = if (active) Color.White else Color(0xFFB6CEC3))
                Spacer(Modifier.width(13.dp))
                Column {
                    Text(
                        key, fontSize = 13.sp,
                        color = if (active) Color.White else Color(0xFFD2E0D9),
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                    )
                    if (active) Text(helper, fontSize = 10.sp, color = Color(0xFFB8D8CB))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Surface(
            color = Color(0xFF254B43), shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("●  LOCAL-FIRST POS", color = Color(0xFFC4E6D3), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("Cash sales are saved on this tablet before cloud sync.", color = Color(0xFFDFEBE5), fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
        Text(
            "AZURATE SOFTWARE SOLUTIONS", color = Color(0xFF86A79C),
            fontSize = 9.sp, modifier = Modifier.padding(top = 12.dp, start = 8.dp)
        )
    }
}

@Composable
private fun BrewStatCard(
    title: String, value: String, footnote: String, symbol: String,
    modifier: Modifier
) {
    Surface(
        modifier = modifier, color = BrewPalette.White,
        shape = RoundedCornerShape(20.dp), border = androidx.compose.foundation.BorderStroke(1.dp, BrewPalette.Border)
    ) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = BrewPalette.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(34.dp).background(BrewPalette.Cream, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) { Text(symbol, fontSize = 16.sp, color = BrewPalette.Forest) }
            }
            Text(value, color = BrewPalette.Ink, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
            Text(footnote, color = BrewPalette.Positive, fontSize = 11.sp)
        }
    }
}

@Composable
fun DashboardView(
    db: StoreDb,
    products: List<Product>,
    sales: List<Sale>,
    businessType: String,
    onSell: () -> Unit,
    onOrders: () -> Unit,
    onMenu: () -> Unit
) {
    val todayStart = remember(sales) {
        java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val daily = remember(sales) { db.stats(todayStart) }
    val open = sales.count { it.status != "Served" }
    val lowStock = products.count { it.active && it.trackStock && it.stock <= 5 }
    val pending = remember(sales) { db.pendingSyncCount() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(17.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your workspace", color = BrewPalette.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Good day, BrewPOS", color = BrewPalette.Ink, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Text("$businessType operations • Live local register summary", color = BrewPalette.Muted, fontSize = 12.sp)
            }
            Button(
                onClick = onSell,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrewPalette.Forest)
            ) { Text("+ New order") }
        }
        BoxWithConstraints {
            if (maxWidth >= 760.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BrewStatCard("Today's sales", peso(daily.revenueCents), "Saved on this tablet", "↗", Modifier.weight(1f))
                    BrewStatCard("Orders today", daily.orders.toString(), "Local completed orders", "▤", Modifier.weight(1f))
                    BrewStatCard("Kitchen queue", open.toString(), "Awaiting service", "☕", Modifier.weight(1f))
                    BrewStatCard("Low stock", lowStock.toString(), "Menu items to check", "!", Modifier.weight(1f))
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BrewStatCard("Today's sales", peso(daily.revenueCents), "Saved locally", "↗", Modifier.weight(1f))
                    BrewStatCard("Orders today", daily.orders.toString(), "Local orders", "▤", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BrewStatCard("Kitchen queue", open.toString(), "Awaiting service", "☕", Modifier.weight(1f))
                    BrewStatCard("Low stock", lowStock.toString(), "Review stock", "!", Modifier.weight(1f))
                }
            }
        }
        Surface(color = BrewPalette.DeepForest, shape = RoundedCornerShape(24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Ready for the next rush?", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "Take orders, send a barista/kitchen ticket, and keep the queue moving.",
                        color = Color(0xFFD0E4DB), fontSize = 12.sp, lineHeight = 17.sp
                    )
                    Text(
                        "$pending sale(s) pending cloud sync — local operations remain available",
                        color = Color(0xFFFFD2A5), fontSize = 11.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onSell,
                            colors = ButtonDefaults.buttonColors(containerColor = BrewPalette.Accent, contentColor = BrewPalette.DeepForest)
                        ) { Text("Open cashier") }
                        OutlinedButton(
                            onClick = onOrders,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) { Text("Kitchen queue") }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text("☕", fontSize = 43.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Recent orders", color = BrewPalette.Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("Last 5 transactions recorded on this tablet", color = BrewPalette.Muted, fontSize = 11.sp)
            }
            TextButton(onClick = onMenu) { Text("Manage menu →", color = BrewPalette.Forest) }
        }
        if (sales.isEmpty()) {
            Surface(
                color = Color.White, shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BrewPalette.Border)
            ) {
                Column(Modifier.fillMaxWidth().padding(23.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("▤", fontSize = 30.sp, color = BrewPalette.Forest)
                    Text("No orders recorded yet", color = BrewPalette.Ink, fontWeight = FontWeight.Bold)
                    Text("Your completed orders will appear here.", color = BrewPalette.Muted, fontSize = 12.sp)
                }
            }
        } else sales.take(5).forEach { sale ->
            Surface(
                color = Color.White, shape = RoundedCornerShape(15.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BrewPalette.Border)
            ) {
                Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(38.dp).background(BrewPalette.Cream, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) { Text("▤", color = BrewPalette.Forest, fontSize = 19.sp) }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(sale.receiptNo, color = BrewPalette.Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(dateTime(sale.createdAt), color = BrewPalette.Muted, fontSize = 11.sp)
                    }
                    Text(
                        sale.status, color = BrewPalette.Positive, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(peso(sale.total.toLong()), color = BrewPalette.Ink, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
