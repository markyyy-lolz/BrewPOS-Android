package com.brewpos.cafe

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.text.selection.SelectionContainer
import kotlinx.coroutines.delay
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Calendar

private val Coffee = Color(0xFF4B3029)
private val Cocoa = Color(0xFF715146)
private val Cream = Color(0xFFF8F4EB)
private val Gold = Color(0xFFD3A66B)
private val Leaf = Color(0xFF386A54)
private val Pale = Color(0xFFF1E7D9)
private val CoffeeTheme = lightColorScheme(
    primary = Coffee, onPrimary = Color.White, secondary = Leaf,
    background = Cream, surface = Color.White, onSurface = Coffee,
    surfaceVariant = Pale, onSurfaceVariant = Cocoa,
    outline = Color(0xFFD9CFC3), error = Color(0xFFB3342D)
)

private fun todayStart(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = CoffeeTheme) { LicensedBrewPos() } }
    }
}


@Composable
private fun LicensedBrewPos() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("brewpos_activation", Context.MODE_PRIVATE) }
    val deviceId = remember { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device" }
    var activeKey by remember { mutableStateOf(prefs.getString("signed_license", "") ?: "") }
    var showRenewal by remember { mutableStateOf(false) }
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { delay(30_000); tick = System.currentTimeMillis() }
    }
    val status = LicenseManager.evaluate(activeKey, deviceId, tick, prefs.getLong("last_checked", 0))
    LaunchedEffect(tick, activeKey) {
        if (status.active && tick > prefs.getLong("last_checked", 0))
            prefs.edit().putLong("last_checked", tick).apply()
    }
    fun activate(code: String): String? {
        val now = System.currentTimeMillis()
        val candidate = LicenseManager.evaluate(code.trim(), deviceId, now, prefs.getLong("last_checked", 0))
        if (!candidate.active) return candidate.message
        prefs.edit().putString("signed_license", code.trim()).putLong("last_checked", now).apply()
        activeKey = code.trim()
        tick = now
        showRenewal = false
        return null
    }
    fun canCheckout(): Boolean {
        val now = System.currentTimeMillis()
        val checked = LicenseManager.evaluate(activeKey, deviceId, now, prefs.getLong("last_checked", 0))
        if (checked.active && now > prefs.getLong("last_checked", 0)) prefs.edit().putLong("last_checked", now).apply()
        return checked.active
    }
    if (!status.active || showRenewal) {
        ActivationScreen(deviceId, status, showRenewal && status.active, onCancel = { showRenewal = false }, onActivate = { code -> activate(code) })
    } else BrewPosApp(licenseStatus = status, onManageLicense = { showRenewal = true }, canCheckout = { canCheckout() })
}

@Composable
private fun ActivationScreen(
    deviceId: String, current: LicenseManager.Status, canCancel: Boolean,
    onCancel: () -> Unit, onActivate: (String) -> String?
) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    Surface(color = Cream, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("☕ BrewPOS Activation", color = Coffee, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("Azurate Software Solutions", color = Cocoa)
            Spacer(Modifier.height(18.dp))
            Text(current.message.ifBlank { "Enter your signed activation code." }, color = if(current.grace) Color(0xFF9E681A) else Cocoa)
            Spacer(Modifier.height(18.dp))
            Text("DEVICE ID", fontWeight = FontWeight.Bold)
            SelectionContainer { Text(deviceId, color = Coffee) }
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("BrewPOS Device ID", deviceId))
            }) { Text("Copy Device ID") }
            Text("Send this Device ID to Azurate Software Solutions to receive your license.", color = Cocoa)
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(value = input, onValueChange = { input = it; error = "" },
                label = { Text("Activation code") }, minLines = 3, maxLines = 5,
                modifier = Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { error = onActivate(input) ?: "" }, enabled = input.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("Activate BrewPOS")
            }
            if (canCancel) TextButton(onClick = onCancel) { Text("Back to POS") }
            Spacer(Modifier.height(8.dp))
            Text("Lifetime = one-time activation. Monthly/Trial = signed expiration date; an additional 72-hour offline grace period applies.",
                color = Cocoa, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun BrewPosApp(licenseStatus: LicenseManager.Status, onManageLicense: () -> Unit, canCheckout: () -> Boolean) {
    val context = LocalContext.current
    val db = remember { StoreDb(context.applicationContext) }
    val prefs = remember { context.getSharedPreferences("shop_prefs", Context.MODE_PRIVATE) }
    var shopName by remember { mutableStateOf(prefs.getString("shop_name", "Brew & Bean Coffee") ?: "Brew & Bean Coffee") }
    var footer by remember { mutableStateOf(prefs.getString("footer", "Thank you! Come back for another cup.") ?: "Thank you!") }
    var printerMac by remember { mutableStateOf(prefs.getString("printer_mac", "") ?: "") }
    var printerWidth by remember { mutableIntStateOf(prefs.getInt("printer_width", 32)) }
    var autoCut by remember { mutableStateOf(prefs.getBoolean("auto_cut", false)) }
    var autoPrintBoth by remember { mutableStateOf(prefs.getBoolean("auto_print_both", true)) }
    var pendingPrintJobs by remember { mutableIntStateOf(0) }
    var allProducts by remember { mutableStateOf(db.products(includeArchived = true)) }
    var allSales by remember { mutableStateOf(db.sales()) }
    val cart = remember { mutableStateListOf<CartLine>() }
    var tab by remember { mutableStateOf("POS") }
    var cartOnPhone by remember { mutableStateOf(false) }
    var customizing by remember { mutableStateOf<Product?>(null) }
    var editing by remember { mutableStateOf<Product?>(null) }
    var addingProduct by remember { mutableStateOf(false) }
    var receipt by remember { mutableStateOf<Sale?>(null) }
    var pendingPdf by remember { mutableStateOf<ByteArray?>(null) }
    var pendingFilename by remember { mutableStateOf("receipt.pdf") }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun refresh() { allProducts = db.products(includeArchived = true); allSales = db.sales() }
    fun alert(message: String) { scope.launch { snackbar.showSnackbar(message) } }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(requireNotNull(pendingPdf)) } ?: error("Cannot write file") }
                .onSuccess { alert("PDF saved") }.onFailure { alert("Failed to save: ${it.message}") }
        }
        pendingPdf = null
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(db.salesCsv()) } ?: error("Cannot write CSV") }
                .onSuccess { alert("Sales CSV exported") }.onFailure { alert("CSV export failed: ${it.message}") }
        }
    }
    val requestNearby = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        alert(if (granted) "Bluetooth access enabled. Select a paired printer in Settings." else "Bluetooth access is needed for thermal printing")
    }
    fun pdfBytes(sale: Sale): ByteArray = Receipts.pdf(shopName, footer, sale, db.saleLines(sale.id))
    fun outputPdf(sale: Sale, print: Boolean) {
        runCatching {
            val bytes = pdfBytes(sale)
            if (print) Receipts.openPrintDialog(context, bytes, sale.receiptNo)
            else { pendingPdf = bytes; pendingFilename = "${sale.receiptNo}.pdf"; savePdf.launch(pendingFilename) }
        }.onFailure { alert(it.message ?: "Receipt error") }
    }
    /** One configured printer for all slips. Combined job uses one socket and two sections. */
    fun thermalPrint(sale: Sale, which: String, automatic: Boolean = false) {
        if (pendingPrintJobs > 0 && !automatic) {
            alert("Printer is busy. Automatic slips are queued; check the paper before printing again.")
            return
        }
        if (!ThermalPrinter.permissionGranted(context)) {
            if (!automatic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                requestNearby.launch(Manifest.permission.BLUETOOTH_CONNECT)
            else alert("Sale saved. Allow Nearby Devices to print the receipt and barista slip.")
            return
        }
        if (printerMac.isBlank()) {
            if (!automatic) tab = "Settings"
            alert("Sale saved. Select ONE paired Bluetooth printer in Settings.")
            return
        }
        pendingPrintJobs++
        scope.launch {
            try {
                val lines = db.saleLines(sale.id)
                val bytes = when (which) {
                    "customer" -> TicketFormatter.customer(shopName, footer, sale, lines, printerWidth, autoCut)
                    "barista" -> TicketFormatter.barista(shopName, sale, lines, printerWidth, autoCut)
                    "both" -> TicketFormatter.both(shopName, footer, sale, lines, printerWidth, autoCut)
                    else -> error("Unknown print job")
                }
                ThermalPrinter.print(context, printerMac, bytes)
                    .onSuccess { alert("${if (which == "both") "Customer + barista slips" else if (which == "barista") "Barista slip" else "Customer receipt"} sent to printer") }
                    .onFailure { alert("Sale saved. Print may be incomplete: ${it.message}. Check paper before retrying.") }
            } catch (e: Exception) {
                alert("Sale saved. Could not prepare printout: ${e.message}")
            } finally {
                pendingPrintJobs = (pendingPrintJobs - 1).coerceAtLeast(0)
            }
        }
    }
    fun afterSale(saved: Sale) {
        cart.clear()
        refresh()
        receipt = saved
        if (autoPrintBoth) thermalPrint(saved, "both", automatic = true)
        else alert("Sale completed. Print both slips from the receipt screen.")
    }
    Scaffold(
        containerColor = Cream,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(
                Modifier.fillMaxWidth().background(Cream).statusBarsPadding().padding(horizontal = 20.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("☕  BREWPOS", color = Coffee, fontWeight = FontWeight.Black, fontSize = 20.sp)
                    Text(shopName, color = Cocoa, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Surface(color = Color.White, shape = RoundedCornerShape(22.dp)) {
                    TextButton(onClick = onManageLicense) {
                        Text(if (licenseStatus.grace) "⚠ Renew license" else "✓ ${licenseStatus.plan}", color = if (licenseStatus.grace) Color.Red else Leaf, fontSize = 11.sp)
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 1.dp) {
                listOf(Triple("POS", "☕", "Sell"), Triple("Orders", "🧾", "Orders"),
                    Triple("Menu", "📋", "Menu"), Triple("Reports", "📊", "Reports"),
                    Triple("Settings", "⚙", "Settings")).forEach { (key, symbol, label) ->
                    NavigationBarItem(selected = tab == key, onClick = { tab = key; cartOnPhone = false },
                        icon = { Text(symbol, fontSize = 20.sp) }, label = { Text(label, fontSize = 10.sp) })
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                "POS" -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    if (maxWidth >= 840.dp) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ProductCatalog(allProducts.filter { it.active }, Modifier.weight(1f), onProduct = { customizing = it })
                            CartPanel(cart, Modifier.width(390.dp).fillMaxHeight(), onSubmit = { service, method, discountText, tenderText, note ->
                                val discount = parseMoney(discountText)
                                val tendered = if (method == "Cash") parseMoney(tenderText) else 0
                                if (discount == null || (method == "Cash" && tendered == null)) alert("Enter valid payment amounts")
                                else if (!canCheckout()) alert("License expired. Renew the activation code before checkout.")
                                    else runCatching { db.checkout(cart.toList(), service, method, discount, tendered ?: 0, note) }
                                    .onSuccess { afterSale(it) }
                                    .onFailure { alert(it.message ?: "Checkout failed") }
                            })
                        }
                    } else {
                        if (cartOnPhone) {
                            Column(Modifier.fillMaxSize()) {
                                TextButton(onClick = { cartOnPhone = false }) { Text("← Back to menu") }
                                CartPanel(cart, Modifier.fillMaxWidth().weight(1f), onSubmit = { service, method, discountText, tenderText, note ->
                                    val discount = parseMoney(discountText)
                                    val tendered = if (method == "Cash") parseMoney(tenderText) else 0
                                    if (discount == null || (method == "Cash" && tendered == null)) alert("Enter valid payment amounts")
                                    else if (!canCheckout()) alert("License expired. Renew the activation code before checkout.")
                                    else runCatching { db.checkout(cart.toList(), service, method, discount, tendered ?: 0, note) }
                                        .onSuccess { cartOnPhone = false; afterSale(it) }
                                        .onFailure { alert(it.message ?: "Checkout failed") }
                                })
                            }
                        } else {
                            ProductCatalog(allProducts.filter { it.active }, Modifier.fillMaxSize().padding(horizontal = 14.dp), onProduct = { customizing = it })
                            Surface(Modifier.align(Alignment.BottomCenter).padding(14.dp).fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp), shadowElevation = 6.dp, color = Coffee) {
                                Row(Modifier.clickable { cartOnPhone = true }.padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("🛒  View cart (${cart.sumOf { it.quantity }})", color = Color.White, fontWeight = FontWeight.Bold)
                                    Text(peso(cart.sumOf { it.lineCents }.toLong()), color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                "Orders" -> OrdersView(allSales, onSelect = { receipt = it }, onNextStatus = { sale ->
                    val next = when (sale.status) { "Queued" -> "Preparing"; "Preparing" -> "Ready"; "Ready" -> "Served"; else -> "Served" }
                    runCatching { db.updateStatus(sale.id, next); refresh() }
                        .onFailure { alert(it.message ?: "Unable to update status") }
                })
                "Menu" -> MenuView(allProducts, onAdd = { addingProduct = true }, onEdit = { editing = it }, onRestore = { p ->
                    runCatching { db.saveProduct(p.copy(active = true)); refresh() }
                        .onSuccess { alert("${p.name} restored") }
                        .onFailure { alert(it.message ?: "Unable to restore") }
                }, onArchive = { p ->
                    runCatching { db.archiveProduct(p); refresh() }
                        .onSuccess { alert("${p.name} archived. Past sales are preserved.") }
                        .onFailure { e -> alert(e.message ?: "Unable to archive") }
                })
                "Reports" -> ReportsView(db, allSales)
                "Settings" -> SettingsView(shopName, footer, printerMac, printerWidth, autoCut, autoPrintBoth,
                    onSave = { newName, newFooter, mac, width, cut, autoBoth ->
                        shopName = newName.ifBlank { "Coffee Shop" }; footer = newFooter
                        printerMac = mac; printerWidth = width; autoCut = cut; autoPrintBoth = autoBoth
                        prefs.edit().putString("shop_name", shopName).putString("footer", footer)
                            .putString("printer_mac", mac).putInt("printer_width", width).putBoolean("auto_cut", cut)
                            .putBoolean("auto_print_both", autoBoth).apply()
                        alert("Settings saved")
                    }, onRequestPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                            requestNearby.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }, onExport = { exportCsv.launch("brewpos-sales.csv") })
            }
        }
    }
    customizing?.let { product ->
        CustomizeDialog(product, cart.filter { it.product.id == product.id }.sumOf { it.quantity }, onClose = { customizing = null }, onAdd = { line ->
            cart.add(line); customizing = null; alert("Added to cart")
        })
    }
    if (addingProduct || editing != null) {
        ProductEditor(editing, onClose = { addingProduct = false; editing = null }, onSave = { p ->
            runCatching { db.saveProduct(p); refresh() }
                .onSuccess { addingProduct = false; editing = null; alert("Menu updated") }
                .onFailure { alert(it.message ?: "Unable to save product") }
        })
    }
    receipt?.let { sale ->
        ReceiptDialog(sale, db.saleLines(sale.id), shopName, onClose = { receipt = null },
            onPdf = { outputPdf(sale, false) }, onPrint = { outputPdf(sale, true) },
            onBoth = { thermalPrint(sale, "both") }, onCustomer = { thermalPrint(sale, "customer") },
            onBarista = { thermalPrint(sale, "barista") }, isPrinting = pendingPrintJobs > 0)
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String = "") {
    Column(Modifier.padding(bottom = 14.dp)) {
        Text(title, fontWeight = FontWeight.ExtraBold, color = Coffee, fontSize = 25.sp)
        if (subtitle.isNotBlank()) Text(subtitle, color = Cocoa, fontSize = 13.sp)
    }
}

@Composable
private fun ProductCatalog(products: List<Product>, modifier: Modifier, onProduct: (Product) -> Unit) {
    var search by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }
    val categories = listOf("All", "Coffee", "Non-Coffee", "Tea", "Pastries")
    val filtered = products.filter {
        (category == "All" || it.category == category) && it.name.contains(search, ignoreCase = true)
    }
    Column(modifier) {
        SectionTitle("New order", "Tap a drink to customize and add it to the bill")
        OutlinedTextField(value = search, onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
            singleLine = true, label = { Text("Search coffee, pastries, tea...") }, leadingIcon = { Text("⌕", fontSize = 22.sp) })
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.forEach { item -> FilterChip(selected = item == category, onClick = { category = item }, label = { Text(item) }) }
        }
        Spacer(Modifier.height(10.dp))
        if (filtered.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No products found", color = Cocoa) }
        else LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 155.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 100.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(filtered, key = { it.id }) { p ->
                Surface(modifier = Modifier.fillMaxWidth().clickable(enabled = !p.trackStock || p.stock > 0) { onProduct(p) },
                    color = Color.White, shape = RoundedCornerShape(22.dp), shadowElevation = 1.dp) {
                    Column(Modifier.padding(15.dp)) {
                        Box(Modifier.fillMaxWidth().height(94.dp).background(Pale, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                            Text(p.icon, fontSize = 47.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(p.category.uppercase(), fontSize = 10.sp, color = Leaf, fontWeight = FontWeight.Bold)
                        Text(p.name, fontWeight = FontWeight.Bold, color = Coffee, fontSize = 15.sp,
                            maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        Text(peso(p.priceCents.toLong()), fontWeight = FontWeight.ExtraBold, color = Coffee, fontSize = 17.sp)
                        Text(if (!p.trackStock) "Available" else if (p.stock == 0) "Out of stock" else "${p.stock} left",
                            color = if (p.trackStock && p.stock < 6) Color(0xFFB04B2E) else Leaf, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomizeDialog(product: Product, existingCount: Int, onClose: () -> Unit, onAdd: (CartLine) -> Unit) {
    val drink = product.category in listOf("Coffee", "Non-Coffee", "Tea")
    var size by remember(product.id) { mutableStateOf(if (drink) "Regular" else "One size") }
    val extras = remember(product.id) { mutableStateListOf<Extra>() }
    var qty by remember(product.id) { mutableIntStateOf(1) }
    val delta = when (size) { "Small" -> -2000; "Large" -> 3000; else -> 0 }
    val unit = (product.priceCents + delta + extras.sumOf { it.priceCents }).coerceAtLeast(0)
    AlertDialog(onDismissRequest = onClose, title = { Text("${product.icon}  ${product.name}", color = Coffee, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                Text("Customize your order", color = Cocoa)
                if (drink) {
                    Spacer(Modifier.height(10.dp))
                    Text("Size", fontWeight = FontWeight.Bold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Small", "Regular", "Large").forEach { choice ->
                            FilterChip(selected = size == choice, onClick = { size = choice }, label = { Text(choice) })
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Add-ons (optional)", fontWeight = FontWeight.Bold)
                    drinkExtras.forEach { extra ->
                        Row(Modifier.fillMaxWidth().clickable { if (extra in extras) extras.remove(extra) else extras.add(extra) },
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = extra in extras, onCheckedChange = { checked ->
                                if (checked) { if (extra !in extras) extras.add(extra) } else extras.remove(extra)
                            })
                            Text("${extra.name}  +${peso(extra.priceCents.toLong())}", fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Quantity", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { if (qty > 1) qty-- }) { Text("−", fontSize = 22.sp) }
                    Text("$qty", fontWeight = FontWeight.Bold)
                    TextButton(onClick = { if (qty < 99) qty++ }) { Text("+", fontSize = 22.sp) }
                }
                Text("Total: ${peso((unit.toLong() * qty))}", fontSize = 22.sp, fontWeight = FontWeight.Black)
                if (product.trackStock) Text("In stock: ${product.stock}; in cart: $existingCount", color = Cocoa, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(CartLine(product, size, delta, extras.toList(), qty)) },
                enabled = !product.trackStock || existingCount + qty <= product.stock) { Text("Add to cart") }
        }, dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}

@Composable
private fun CartPanel(cart: List<CartLine>, modifier: Modifier = Modifier,
                      onSubmit: (String, String, String, String, String) -> Unit) {
    var service by remember { mutableStateOf("Dine-in") }
    var payment by remember { mutableStateOf("Cash") }
    var discountText by remember { mutableStateOf("0") }
    var tenderText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val discount = parseMoney(discountText)
    val totals = CartMath.totals(cart, discount ?: 0)
    val tendered = parseMoney(tenderText)
    val enough = payment != "Cash" || (tendered != null && tendered >= totals.payable)
    // Fresh order must not inherit the previous customer's instructions or cash tender.
    LaunchedEffect(cart.isEmpty()) {
        if (cart.isEmpty()) {
            service = "Dine-in"; payment = "Cash"; discountText = "0"; tenderText = ""; note = ""
        }
    }
    Surface(modifier, color = Color.White, shape = RoundedCornerShape(22.dp), shadowElevation = 1.dp) {
        Column(Modifier.fillMaxSize().padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Current bill", fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                Text("${cart.sumOf { it.quantity }} items", color = Cocoa, fontSize = 12.sp)
            }
            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Dine-in", "Takeout").forEach {
                    FilterChip(selected = service == it, onClick = { service = it }, label = { Text(it) })
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            if (cart.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🛍", fontSize = 42.sp)
                    Text("Your cart is empty", fontWeight = FontWeight.Bold)
                    Text("Add a coffee to start", color = Cocoa)
                }
            } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                items(cart, key = { it.key }) { line ->
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("${line.quantity}×", fontWeight = FontWeight.Bold, color = Leaf)
                        Column(Modifier.weight(1f)) {
                            Text(line.product.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            if (line.options.isNotBlank()) Text(line.options, color = Cocoa, fontSize = 11.sp)
                            Text(peso(line.lineCents.toLong()), fontWeight = FontWeight.Bold)
                        }
                        TextButton(onClick = { (cart as? MutableList<CartLine>)?.remove(line) }, contentPadding = PaddingValues(0.dp)) {
                            Text("Remove", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Cash", "GCash", "Maya", "Card").forEach {
                    FilterChip(selected = payment == it, onClick = { payment = it }, label = { Text(it, fontSize = 11.sp) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = discountText, onValueChange = { discountText = it }, modifier = Modifier.weight(1f),
                    label = { Text("Discount ₱") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                if (payment == "Cash") OutlinedTextField(value = tenderText, onValueChange = { tenderText = it }, modifier = Modifier.weight(1f),
                    label = { Text("Tendered ₱") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            OutlinedTextField(value = note, onValueChange = { note = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Order note / table no. (optional)") }, maxLines = 2, singleLine = true)
            Spacer(Modifier.height(10.dp))
            MoneyRow("Subtotal", peso(totals.subtotal.toLong()))
            MoneyRow("Discount", "− ${peso(totals.discount.toLong())}")
            MoneyRow("Total", peso(totals.payable.toLong()), prominent = true)
            if (payment == "Cash" && tendered != null && tendered >= totals.payable)
                MoneyRow("Change", peso((tendered - totals.payable).toLong()))
            Spacer(Modifier.height(10.dp))
            Button(onClick = { onSubmit(service, payment, discountText, tenderText, note.trim()) },
                enabled = cart.isNotEmpty() && discount != null && enough, modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp)) {
                Text("Complete sale  •  ${peso(totals.payable.toLong())}", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MoneyRow(name: String, value: String, prominent: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, fontSize = if (prominent) 19.sp else 13.sp, fontWeight = if (prominent) FontWeight.Bold else FontWeight.Normal)
        Text(value, fontSize = if (prominent) 21.sp else 13.sp, fontWeight = FontWeight.Bold, color = if (prominent) Leaf else Coffee)
    }
}

@Composable
private fun OrdersView(sales: List<Sale>, onSelect: (Sale) -> Unit, onNextStatus: (Sale) -> Unit) {
    var filter by remember { mutableStateOf("Open queue") }
    val filtered = if (filter == "Open queue") sales.filter { it.status != "Served" } else sales
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SectionTitle("Barista queue & orders", "Update preparation status, reprint receipts, or save PDFs")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Open queue", "All history").forEach { item ->
                FilterChip(selected = filter == item, onClick = { filter = item }, label = { Text(item) })
            }
        }
        Spacer(Modifier.height(8.dp))
        if (filtered.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (filter == "Open queue") "All orders served. Great work!" else "No sales yet.", color = Cocoa)
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(filtered, key = { it.id }) { s ->
                Surface(color = Color.White, shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(Modifier.clickable { onSelect(s) }, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.receiptNo, fontWeight = FontWeight.Bold)
                                Text(dateTime(s.createdAt), color = Cocoa, fontSize = 12.sp)
                                Text("${s.service} • ${s.payment}", color = Cocoa, fontSize = 12.sp)
                            }
                            Text(peso(s.total.toLong()), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                            Text("  ›", fontSize = 24.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("● ${s.status}", color = if (s.status == "Served") Leaf else Coffee,
                                fontSize = 12.sp, modifier = Modifier.weight(1f))
                            if (s.status != "Served") {
                                OutlinedButton(onClick = { onNextStatus(s) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                                    Text(when (s.status) { "Queued" -> "Start preparing"; "Preparing" -> "Mark ready"; else -> "Mark served" }, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuView(products: List<Product>, onAdd: () -> Unit, onEdit: (Product) -> Unit,
                     onRestore: (Product) -> Unit, onArchive: (Product) -> Unit) {
    var archiveCandidate by remember { mutableStateOf<Product?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { SectionTitle("Menu management", "Edit prices and product stock") }
            Button(onClick = onAdd) { Text("+ Product") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(products, key = { it.id }) { p ->
                Surface(color = Color.White, shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.icon, fontSize = 31.sp, modifier = Modifier.padding(end = 10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.Bold)
                            Text("${p.category} • ${if (p.active) (if (p.trackStock) "${p.stock} units" else "Untracked stock") else "Archived"}", color = Cocoa, fontSize = 11.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(peso(p.priceCents.toLong()), fontWeight = FontWeight.Bold)
                            Row {
                                if (!p.active) TextButton(onClick = { onRestore(p) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Restore") }
                                else {
                                    TextButton(onClick = { onEdit(p) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Edit") }
                                    TextButton(onClick = { archiveCandidate = p }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Archive", color = Color(0xFFAD4B3C)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    archiveCandidate?.let { p ->
        AlertDialog(onDismissRequest = { archiveCandidate = null }, title = { Text("Archive ${p.name}?") },
            text = { Text("The product will be hidden from new orders. Existing sales and receipt records will remain unchanged.") },
            confirmButton = { TextButton(onClick = { onArchive(p); archiveCandidate = null }) { Text("Archive") } },
            dismissButton = { TextButton(onClick = { archiveCandidate = null }) { Text("Cancel") } })
    }
}

@Composable
private fun ProductEditor(product: Product?, onClose: () -> Unit, onSave: (Product) -> Unit) {
    var name by remember(product?.id) { mutableStateOf(product?.name ?: "") }
    var price by remember(product?.id) { mutableStateOf(product?.priceCents?.let { "%.2f".format(java.util.Locale.US, it / 100.0) } ?: "") }
    var stock by remember(product?.id) { mutableStateOf(product?.stock?.toString() ?: "0") }
    var category by remember(product?.id) { mutableStateOf(product?.category ?: "Coffee") }
    var icon by remember(product?.id) { mutableStateOf(product?.icon ?: "☕") }
    var trackStock by remember(product?.id) { mutableStateOf(product?.trackStock ?: true) }
    val priceCents = parseMoney(price)
    val stockCount = stock.toIntOrNull()
    AlertDialog(onDismissRequest = onClose, title = { Text(if (product == null) "Add product" else "Edit product") },
        text = {
            Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Product name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(price, { price = it }, label = { Text("Regular price ₱") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(stock, { stock = it }, label = { Text("Stock (units)") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text("Category", fontWeight = FontWeight.Bold)
                Column {
                    listOf(listOf("Coffee", "Non-Coffee"), listOf("Tea", "Pastries")).forEach { group ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            group.forEach { choice -> FilterChip(selected = category == choice, onClick = { category = choice }, label = { Text(choice) }) }
                        }
                    }
                }
                OutlinedTextField(icon, { icon = it }, label = { Text("Emoji icon") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = trackStock, onCheckedChange = { trackStock = it })
                    Text("Track product stock and prevent overselling", fontSize = 12.sp)
                }
                Text("Stock is counted per menu item, not per raw ingredient.", fontSize = 11.sp, color = Cocoa)
            }
        }, confirmButton = {
            Button(onClick = {
                onSave(Product(id = product?.id ?: 0L, name = name.trim(), category = category,
                    priceCents = priceCents ?: 0, stock = stockCount ?: 0,
                    trackStock = trackStock, active = true, icon = icon.ifBlank { "☕" }.take(4)))
            }, enabled = name.isNotBlank() && priceCents != null && stockCount != null && stockCount >= 0) { Text("Save") }
        }, dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}

@Composable
private fun ReportsView(db: StoreDb, sales: List<Sale>) {
    val from = remember(sales) { todayStart() }
    val today = remember(sales) { db.stats(from) }
    val all = remember(sales) { db.stats(0) }
    val top = remember(sales) { db.topProducts(from) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("Sales overview", "Today's performance and historical totals")
        Metric("Today's sales", peso(today.revenueCents), "${today.orders} transactions")
        Spacer(Modifier.height(12.dp))
        Metric("Items sold today", today.items.toString(), "Completed orders only")
        Spacer(Modifier.height(12.dp))
        Metric("All-time sales", peso(all.revenueCents), "${all.orders} transactions")
        Spacer(Modifier.height(18.dp))
        Text("Today's best sellers", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (top.isEmpty()) Text("Complete a sale to see best sellers.", color = Cocoa)
        else top.forEachIndexed { i, item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${i + 1}. ${item.name}", fontWeight = FontWeight.Medium)
                Text("${item.sold} sold", color = Leaf)
            }
            HorizontalDivider()
        }
        Spacer(Modifier.height(20.dp))
        Text("This report reflects transactions stored on this Android device. It is not a cloud or consolidated multi-device report.", color = Cocoa, fontSize = 12.sp)
    }
}

@Composable
private fun Metric(title: String, number: String, caption: String) {
    Surface(color = Color.White, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(19.dp)) {
            Text(title, color = Cocoa)
            Text(number, color = Coffee, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Text(caption, color = Leaf, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SettingsView(initialName: String, initialFooter: String, initialMac: String,
                         initialWidth: Int, initialCut: Boolean, initialAutoBoth: Boolean,
                         onSave: (String, String, String, Int, Boolean, Boolean) -> Unit,
                         onRequestPermission: () -> Unit, onExport: () -> Unit) {
    val context = LocalContext.current
    var shop by remember(initialName) { mutableStateOf(initialName) }
    var footer by remember(initialFooter) { mutableStateOf(initialFooter) }
    var mac by remember(initialMac) { mutableStateOf(initialMac) }
    var width by remember(initialWidth) { mutableIntStateOf(initialWidth) }
    var cut by remember(initialCut) { mutableStateOf(initialCut) }
    var autoBoth by remember(initialAutoBoth) { mutableStateOf(initialAutoBoth) }
    var printerMenu by remember { mutableStateOf(false) }
    var deviceRefresh by remember { mutableIntStateOf(0) }
    val permitted = ThermalPrinter.permissionGranted(context)
    val paired = remember(deviceRefresh, permitted) { ThermalPrinter.paired(context) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("Shop settings", "Personalize the store and connect an ESC/POS receipt printer")
        Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("STORE PROFILE", color = Leaf, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                OutlinedTextField(shop, { shop = it }, label = { Text("Coffee shop name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(footer, { footer = it }, label = { Text("Receipt footer") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                HorizontalDivider()
                Text("BLUETOOTH THERMAL PRINTER", color = Leaf, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                if (!permitted) Button(onClick = onRequestPermission) { Text("Allow Nearby Devices") }
                else {
                    Text("Pair your printer first in Android Bluetooth Settings.", fontSize = 12.sp, color = Cocoa)
                    Box {
                        OutlinedButton(onClick = { deviceRefresh++; printerMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(paired.firstOrNull { it.address == mac }?.name ?: "Choose paired printer", maxLines = 1)
                        }
                        DropdownMenu(expanded = printerMenu, onDismissRequest = { printerMenu = false }) {
                            if (paired.isEmpty()) DropdownMenuItem(text = { Text("No paired devices found") }, onClick = { printerMenu = false })
                            paired.forEach { device ->
                                DropdownMenuItem(text = { Text("${device.name ?: "Bluetooth device"} (${device.address})") },
                                    onClick = { mac = device.address; printerMenu = false })
                            }
                        }
                    }
                }
                Text("Receipt width", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    FilterChip(selected = width == 32, onClick = { width = 32 }, label = { Text("58mm · 32 cols") })
                    FilterChip(selected = width == 48, onClick = { width = 48 }, label = { Text("80mm · 48 cols") })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = cut, onCheckedChange = { cut = it })
                    Text("Send auto-cut command (if supported)")
                }
                HorizontalDivider()
                Text("ONE-PRINTER WORKFLOW", color = Leaf, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoBoth, onCheckedChange = { autoBoth = it })
                    Text("Auto-print customer receipt, then barista slip after checkout", fontSize = 12.sp)
                }
                Text("Both use the printer above. With an auto-cutter, each slip is cut separately; otherwise tear along the separator. No drink prices appear on the barista slip.", fontSize = 11.sp, color = Cocoa)
                Button(onClick = { onSave(shop, footer, mac, width, cut, autoBoth) }, modifier = Modifier.fillMaxWidth()) { Text("Save settings") }
            }
        }
        Spacer(Modifier.height(15.dp))
        OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("Export sales summary (CSV)") }
        Text("CSV amounts are in centavos. Keep exports in a safe location; CSV is not a full database backup.", color = Cocoa, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Text("BrewPOS v1.1 • One-printer customer + barista slips • Android 8.0+", color = Cocoa, fontSize = 12.sp)
        Text("Payments named GCash, Maya, and Card are manually recorded. This app does not capture payments through any gateway.", color = Cocoa, fontSize = 12.sp)
        Text("Local data is not yet backed up or synchronized across devices. Do not clear app storage without exporting your records.", color = Color(0xFFAE4C37), fontSize = 12.sp)
    }
}

@Composable
private fun ReceiptDialog(sale: Sale, lines: List<SaleLine>, shopName: String,
                          onClose: () -> Unit, onPdf: () -> Unit, onPrint: () -> Unit,
                          onBoth: () -> Unit, onCustomer: () -> Unit, onBarista: () -> Unit, isPrinting: Boolean) {
    AlertDialog(onDismissRequest = onClose,
        title = { Column { Text("✓  Sale completed", color = Leaf, fontWeight = FontWeight.Black); Text(sale.receiptNo, color = Cocoa, fontSize = 13.sp) } },
        text = {
            Column(Modifier.heightIn(max = 410.dp).verticalScroll(rememberScrollState())) {
                Text(shopName, fontSize = 20.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text(dateTime(sale.createdAt), textAlign = TextAlign.Center, color = Cocoa, fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                lines.forEach { line ->
                    MoneyRow("${line.quantity}× ${line.productName}", peso(line.lineCents.toLong()))
                    if (line.options.isNotBlank()) Text(line.options, color = Cocoa, fontSize = 11.sp)
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                MoneyRow("Subtotal", peso(sale.subtotal.toLong()))
                MoneyRow("Discount", peso(sale.discount.toLong()))
                MoneyRow("TOTAL", peso(sale.total.toLong()), true)
                MoneyRow("Tendered", peso(sale.tendered.toLong()))
                MoneyRow("Change", peso(sale.change.toLong()))
                Text("${sale.service} • ${sale.payment}", color = Cocoa, fontSize = 12.sp)
                if (sale.notes.isNotEmpty()) Text("Note: ${sale.notes}", color = Cocoa, fontSize = 12.sp)
            }
        }, confirmButton = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    OutlinedButton(onClick = onPdf, modifier = Modifier.weight(1f)) { Text("Save PDF", fontSize = 12.sp) }
                    OutlinedButton(onClick = onPrint, modifier = Modifier.weight(1f)) { Text("Print / PDF", fontSize = 12.sp) }
                }
                Spacer(Modifier.height(6.dp))
                Button(onClick = onBoth, modifier = Modifier.fillMaxWidth(), enabled = !isPrinting) {
                    Text(if (isPrinting) "Printing on 1 printer..." else "Print BOTH • 1 Printer")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    OutlinedButton(onClick = onCustomer, modifier = Modifier.weight(1f), enabled = !isPrinting) {
                        Text("Customer only", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = onBarista, modifier = Modifier.weight(1f), enabled = !isPrinting) {
                        Text("Barista only", fontSize = 11.sp)
                    }
                }
            }
        }, dismissButton = { TextButton(onClick = onClose) { Text("Close") } })
}
