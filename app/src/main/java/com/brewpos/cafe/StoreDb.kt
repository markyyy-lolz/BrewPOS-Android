package com.brewpos.cafe

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** SQLite source of truth. Completed checkout and stock deductions are one transaction. */
class StoreDb(private val context: Context) : SQLiteOpenHelper(context, "brewpos_local.db", null, 3) {
    private val terminalId: String by lazy {
        val prefs = context.getSharedPreferences("brewpos_terminal", Context.MODE_PRIVATE)
        prefs.getString("installation_uuid", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("installation_uuid", it).commit()
        }
    }
    override fun onConfigure(db: SQLiteDatabase) { super.onConfigure(db); db.setForeignKeyConstraintsEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE products (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL, category TEXT NOT NULL, price_cents INTEGER NOT NULL,
            stock INTEGER NOT NULL DEFAULT 0, track_stock INTEGER NOT NULL DEFAULT 1,
            active INTEGER NOT NULL DEFAULT 1, icon TEXT NOT NULL DEFAULT '☕'
        )""")
        db.execSQL("""CREATE TABLE sales (
            id INTEGER PRIMARY KEY AUTOINCREMENT, receipt_no TEXT NOT NULL UNIQUE,
            created_at INTEGER NOT NULL, service TEXT NOT NULL, payment TEXT NOT NULL,
            subtotal INTEGER NOT NULL, discount INTEGER NOT NULL, total INTEGER NOT NULL,
            tendered INTEGER NOT NULL, change_cents INTEGER NOT NULL, notes TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'Queued'
        )""")
        db.execSQL("""CREATE TABLE sale_lines (
            id INTEGER PRIMARY KEY AUTOINCREMENT, sale_id INTEGER NOT NULL REFERENCES sales(id),
            product_id INTEGER NOT NULL, product_name TEXT NOT NULL, options TEXT NOT NULL,
            qty INTEGER NOT NULL, unit_cents INTEGER NOT NULL, line_cents INTEGER NOT NULL
        )""")
        db.execSQL("CREATE INDEX sale_date_idx ON sales(created_at)")
        db.execSQL("CREATE INDEX sale_line_sale_idx ON sale_lines(sale_id)")
        createSyncOutbox(db)
        createInventorySync(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion <= newVersion) { "Unsupported schema downgrade" }
        if (oldVersion < 2) createSyncOutbox(db)
        if (oldVersion < 3) createInventorySync(db)
    }

    /** Durable local sale queue. */
    private fun createSyncOutbox(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS sync_outbox (
            event_id TEXT PRIMARY KEY,
            sale_id INTEGER NOT NULL UNIQUE REFERENCES sales(id),
            installation_id TEXT NOT NULL,
            payload TEXT NOT NULL,
            state TEXT NOT NULL DEFAULT 'pending' CHECK(state IN ('pending','synced')),
            attempts INTEGER NOT NULL DEFAULT 0,
            last_error TEXT,
            created_at INTEGER NOT NULL,
            last_attempt_at INTEGER
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS sync_outbox_pending_idx ON sync_outbox(state,created_at)")
    }

    private fun createInventorySync(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE products ADD COLUMN cloud_id TEXT")
        db.execSQL("ALTER TABLE products ADD COLUMN cloud_version INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE UNIQUE INDEX products_cloud_id ON products(cloud_id)")
        db.execSQL("CREATE TABLE cloud_binding (singleton INTEGER PRIMARY KEY CHECK(singleton=1), org_id TEXT NOT NULL, branch_id TEXT NOT NULL, role TEXT NOT NULL)")
        db.execSQL("CREATE TABLE stock_outbox (event_id TEXT PRIMARY KEY, payload TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'pending', created_at INTEGER NOT NULL)")
    }
    fun binding(): Triple<String,String,String>? = readableDatabase.rawQuery(
        "SELECT org_id,branch_id,role FROM cloud_binding WHERE singleton=1", null).use {
        if(it.moveToFirst()) Triple(it.getString(0),it.getString(1),it.getString(2)) else null
    }
    fun bind(org: String, branch: String, role: String) {
        val db=writableDatabase
        db.beginTransaction()
        try {
            val previous=binding()
            require(previous==null || (previous.first==org && previous.second==branch)) {
                "This installation belongs to another branch. Its receipts cannot be reassigned."
            }
            require(previous!=null || pendingSyncCount()==0) { "Unlinked historical sales need owner reconciliation before linking." }
            db.insertWithOnConflict("cloud_binding",null,ContentValues().apply {
                put("singleton",1);put("org_id",org);put("branch_id",branch);put("role",role)
            },SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    /** Apply only after all local events ACK; recheck inside the SQLite write transaction. */
    fun replaceCatalog(org: String, branch: String, catalog: JSONArray) {
        val bound=binding() ?: error("Register the tablet first")
        require(bound.first==org && bound.second==branch)
        val db=writableDatabase
        db.beginTransaction()
        try {
            require(pendingSyncCount()==0) { "Pending events must reconcile before downloading stock." }
            db.execSQL("UPDATE products SET active=0")
            val seen=mutableSetOf<String>()
            for(i in 0 until catalog.length()) {
                val p=catalog.getJSONObject(i)
                require(p.getString("organization_id")==org && p.getString("branch_id")==branch)
                val id=UUID.fromString(p.getString("id")).toString()
                require(seen.add(id)) { "Duplicate catalog product" }
                val stock=p.get("stock_quantity").toString().toBigDecimal().intValueExact()
                val price=p.getLong("price_centavos")
                require(price in 0..Int.MAX_VALUE.toLong())
                val v=ContentValues().apply {
                    put("cloud_id",id);put("cloud_version",p.getLong("version"))
                    put("name",p.getString("name"));put("category",p.getString("category"))
                    put("price_cents",price);put("stock",stock)
                    put("track_stock",if(p.getBoolean("track_stock")) 1 else 0)
                    put("active",if(p.getBoolean("active")) 1 else 0)
                }
                if(db.update("products",v,"cloud_id=?",arrayOf(id))==0) db.insertOrThrow("products",null,v)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun installationId(): String = terminalId

    fun pendingSyncCount(): Int {
        readableDatabase.rawQuery("SELECT (SELECT COUNT(*) FROM sync_outbox WHERE state='pending') + (SELECT COUNT(*) FROM stock_outbox WHERE state='pending')", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** Outbox stays pending until authenticated cloud sync returns a matching ACK. */
    fun pendingSyncBatch(limit: Int = 25): List<Pair<String,String>> {
        val batch = mutableListOf<Pair<String,String>>()
        readableDatabase.rawQuery("""SELECT event_id,payload FROM (
            SELECT event_id,payload,created_at FROM sync_outbox WHERE state='pending'
            UNION ALL SELECT event_id,payload,created_at FROM stock_outbox WHERE state='pending'
        ) ORDER BY created_at,event_id LIMIT ?""", arrayOf(limit.coerceIn(1,100).toString())).use { c ->
            while(c.moveToNext()) batch += c.getString(0) to c.getString(1)
        }
        return batch
    }

    /** Call only after an authenticated backend has durably acknowledged the event. */
    fun acknowledgeSyncedEvent(eventId: String): Boolean {
        val v = ContentValues().apply { put("state", "synced"); putNull("last_error") }
        if(writableDatabase.update("sync_outbox",v,"event_id=? AND state='pending'",arrayOf(eventId))==1)return true
        return writableDatabase.update("stock_outbox",ContentValues().apply {put("state","synced")},"event_id=? AND state='pending'",arrayOf(eventId))==1
    }

    private fun insertProduct(db: SQLiteDatabase, product: Product): Long {
        val v = productValues(product)
        return db.insertOrThrow("products", null, v)
    }
    private fun productValues(p: Product) = ContentValues().apply {
        put("name", p.name.trim()); put("category", p.category); put("price_cents", p.priceCents)
        put("stock", p.stock); put("track_stock", if (p.trackStock) 1 else 0)
        put("active", if (p.active) 1 else 0); put("icon", p.icon)
    }
    fun saveProduct(p: Product) {
        require(p.name.isNotBlank() && p.priceCents >= 0)
        val bound=binding()
        if(bound!=null) {
            require(bound.third in listOf("owner","manager")) { "Manager permission required" }
            val db=writableDatabase
            db.beginTransaction()
            try {
                val old=products(true).singleOrNull {it.id==p.id} ?: error("Create cloud menu products through the owner catalog first")
                require(old.cloudId!=null && old.trackStock && old.copy(stock=p.stock)==p) { "Cloud menu details must be changed by the owner; only stock adjustments are available offline" }
                val delta=p.stock.toLong()-old.stock.toLong()
                require(delta!=0L && kotlin.math.abs(delta)<=100000) { "Enter a stock change of 1–100,000 units" }
                val id=UUID.randomUUID().toString();val now=System.currentTimeMillis()
                val payload=JSONObject().put("event_id",id).put("event_type","stock.adjusted").put("payload_version",2)
                    .put("installation_id",terminalId).put("organization_id",bound.first).put("branch_id",bound.second)
                    .put("product_id",old.cloudId).put("delta",delta).put("reason","Manual stock adjustment")
                    .put("created_at_ms",now)
                db.execSQL("UPDATE products SET stock=? WHERE id=?",arrayOf(p.stock,p.id))
                db.execSQL("INSERT INTO stock_outbox(event_id,payload,created_at) VALUES(?,?,?)",arrayOf(id,payload.toString(),now))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            BrewCloud.queueNow(context)
            return
        }
        require(p.stock>=0)
        val db = writableDatabase
        if (p.id == 0L) insertProduct(db, p)
        else require(db.update("products", productValues(p), "id=?", arrayOf(p.id.toString())) == 1) { "Product not found" }
    }
    fun archiveProduct(p: Product) {
        require(binding()==null) { "Cloud products must be archived by the owner" }
        val v = ContentValues().apply { put("active", 0) }
        writableDatabase.update("products", v, "id=?", arrayOf(p.id.toString()))
    }
    fun products(includeArchived: Boolean = false): List<Product> {
        val result = mutableListOf<Product>()
        val where = if (includeArchived) null else "active=1"
        readableDatabase.query("products", null, where, null, null, null, "category ASC, name ASC").use { c ->
            while (c.moveToNext()) result += Product(
                id = c.getLong(c.getColumnIndexOrThrow("id")),
                name = c.getString(c.getColumnIndexOrThrow("name")),
                category = c.getString(c.getColumnIndexOrThrow("category")),
                priceCents = c.getInt(c.getColumnIndexOrThrow("price_cents")),
                stock = c.getInt(c.getColumnIndexOrThrow("stock")),
                trackStock = c.getInt(c.getColumnIndexOrThrow("track_stock")) == 1,
                active = c.getInt(c.getColumnIndexOrThrow("active")) == 1,
                icon = c.getString(c.getColumnIndexOrThrow("icon")),
                cloudId = c.getString(c.getColumnIndexOrThrow("cloud_id")),
                cloudVersion = c.getLong(c.getColumnIndexOrThrow("cloud_version"))
            )
        }
        return result
    }

    fun checkout(lines: List<CartLine>, service: String, payment: String,
                 discountCents: Int, tendered: Int, notes: String): Sale {
        require(lines.isNotEmpty()) { "Cart is empty" }
        val totals = CartMath.totals(lines, discountCents)
        val acceptedTender = if (payment == "Cash") tendered else totals.payable
        val change = CartMath.change(totals.payable, acceptedTender, payment)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val bound=binding()
            if(bound!=null)require(bound.third in listOf("owner","manager","cashier")) { "Cashier permission required" }
            // Re-read stock under the SQLite write transaction; trust no stale UI values.
            lines.groupBy { it.product.id }.forEach { (id, entries) ->
                val requested = entries.sumOf { it.quantity }
                db.rawQuery("SELECT stock, track_stock, active, cloud_id FROM products WHERE id=?", arrayOf(id.toString())).use { c ->
                    require(c.moveToFirst() && c.getInt(2) == 1) { "A product is no longer available" }
                    if(bound!=null)require(c.getString(3)!=null && entries.all {it.product.cloudId==c.getString(3)}) { "Refresh the approved cloud catalog" }
                    if (c.getInt(1) == 1) {
                        require(c.getInt(0) >= requested) { "Not enough stock for ${entries.first().product.name}" }
                        db.execSQL("UPDATE products SET stock=stock-? WHERE id=?", arrayOf(requested, id))
                    }
                }
            }
            val now = System.currentTimeMillis()
            val temp = "PENDING-$now-${System.nanoTime()}"
            val v = ContentValues().apply {
                put("receipt_no", temp); put("created_at", now); put("service", service)
                put("payment", payment); put("subtotal", totals.subtotal)
                put("discount", totals.discount); put("total", totals.payable)
                put("tendered", acceptedTender); put("change_cents", change); put("notes", notes); put("status", "Queued")
            }
            val saleId = db.insertOrThrow("sales", null, v)
            val receiptNo = "BP-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(now))}-${saleId.toString().padStart(5, '0')}"
            db.execSQL("UPDATE sales SET receipt_no=? WHERE id=?", arrayOf(receiptNo, saleId))
            lines.forEach { line ->
                db.insertOrThrow("sale_lines", null, ContentValues().apply {
                    put("sale_id", saleId); put("product_id", line.product.id)
                    put("product_name", line.product.name); put("options", line.options)
                    put("qty", line.quantity); put("unit_cents", line.unitCents)
                    put("line_cents", line.lineCents)
                })
            }
            // Save sale + durable sync event atomically: failed outbox write rolls back sale.
            // Existing local sale IDs are not globally unique; use the UUID as cloud sale ID.
            val eventId = UUID.randomUUID().toString()
            val snapshot = JSONObject().apply {
                put("payload_version", if(bound==null) 1 else 2)
                if(bound!=null) { put("organization_id",bound.first);put("branch_id",bound.second) }
                put("event_type", "sale.completed")
                put("event_id", eventId)
                put("client_sale_id", eventId)
                put("installation_id", terminalId)
                put("local_receipt_no", receiptNo)
                put("created_offline_at_ms", now)
                put("service_type", service)
                put("payment_method", payment)
                put("subtotal_centavos", totals.subtotal)
                put("discount_centavos", totals.discount)
                put("total_centavos", totals.payable)
                put("tendered_centavos", acceptedTender)
                put("change_centavos", change)
                put("notes", notes)
                put("line_items", JSONArray().apply {
                    lines.forEachIndexed { index, line ->
                        put(JSONObject().apply {
                            put("line_no", index + 1)
                            put("local_product_id", line.product.id)
                            if(bound!=null)put("product_id",line.product.cloudId)
                            put("product_name", line.product.name)
                            put("options", line.options)
                            put("quantity", line.quantity)
                            put("unit_centavos", line.unitCents)
                            put("line_centavos", line.lineCents)
                        })
                    }
                })
            }
            db.insertOrThrow("sync_outbox", null, ContentValues().apply {
                put("event_id", eventId)
                put("sale_id", saleId)
                put("installation_id", terminalId)
                put("payload", snapshot.toString())
                put("state", "pending")
                put("created_at", now)
            })
            db.setTransactionSuccessful()
            return Sale(saleId, receiptNo, now, service, payment, totals.subtotal,
                totals.discount, totals.payable, acceptedTender, change, notes, "Queued")
        } finally { db.endTransaction() }
    }

    fun sales(): List<Sale> {
        val out = mutableListOf<Sale>()
        readableDatabase.query("sales", null, null, null, null, null, "created_at DESC, id DESC", "250").use { c ->
            while (c.moveToNext()) out += Sale(
                c.getLong(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("receipt_no")),
                c.getLong(c.getColumnIndexOrThrow("created_at")),
                c.getString(c.getColumnIndexOrThrow("service")),
                c.getString(c.getColumnIndexOrThrow("payment")),
                c.getInt(c.getColumnIndexOrThrow("subtotal")),
                c.getInt(c.getColumnIndexOrThrow("discount")),
                c.getInt(c.getColumnIndexOrThrow("total")),
                c.getInt(c.getColumnIndexOrThrow("tendered")),
                c.getInt(c.getColumnIndexOrThrow("change_cents")),
                c.getString(c.getColumnIndexOrThrow("notes")),
                c.getString(c.getColumnIndexOrThrow("status"))
            )
        }
        return out
    }
    fun updateStatus(saleId: Long, status: String) {
        require(status in listOf("Queued", "Preparing", "Ready", "Served"))
        val values = ContentValues().apply { put("status", status) }
        require(writableDatabase.update("sales", values, "id=?", arrayOf(saleId.toString())) == 1)
    }

    fun saleLines(saleId: Long): List<SaleLine> {
        val out = mutableListOf<SaleLine>()
        readableDatabase.query("sale_lines", null, "sale_id=?", arrayOf(saleId.toString()), null, null, "id").use { c ->
            while (c.moveToNext()) out += SaleLine(
                c.getLong(c.getColumnIndexOrThrow("product_id")),
                c.getString(c.getColumnIndexOrThrow("product_name")),
                c.getString(c.getColumnIndexOrThrow("options")),
                c.getInt(c.getColumnIndexOrThrow("qty")),
                c.getInt(c.getColumnIndexOrThrow("unit_cents")),
                c.getInt(c.getColumnIndexOrThrow("line_cents"))
            )
        }
        return out
    }
    fun stats(sinceMs: Long): DailyStats {
        var count = 0; var revenue = 0L; var items = 0
        readableDatabase.rawQuery("SELECT COUNT(*), COALESCE(SUM(total), 0) FROM sales WHERE created_at>=?", arrayOf(sinceMs.toString())).use { c ->
            if (c.moveToFirst()) { count = c.getInt(0); revenue = c.getLong(1) }
        }
        readableDatabase.rawQuery("SELECT COALESCE(SUM(l.qty),0) FROM sale_lines l JOIN sales s ON s.id=l.sale_id WHERE s.created_at>=?", arrayOf(sinceMs.toString())).use { c ->
            if (c.moveToFirst()) items = c.getInt(0)
        }
        return DailyStats(count, revenue, items)
    }
    fun salesCsv(): ByteArray {
        fun quote(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
        val csv = StringBuilder("receipt_no,date,service,payment,status,subtotal_cents,discount_cents,total_cents,tendered_cents,change_cents,notes\n")
        readableDatabase.rawQuery("""SELECT receipt_no,created_at,service,payment,status,subtotal,discount,total,tendered,change_cents,notes
            FROM sales ORDER BY created_at,id""", null).use { c ->
            while (c.moveToNext()) {
                val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(c.getLong(1)))
                csv.append(listOf(quote(c.getString(0)), quote(date), quote(c.getString(2)),
                    quote(c.getString(3)), quote(c.getString(4)), c.getInt(5).toString(),
                    c.getInt(6).toString(), c.getInt(7).toString(), c.getInt(8).toString(),
                    c.getInt(9).toString(), quote(c.getString(10))).joinToString(","))
                    .append('\n')
            }
        }
        return ("\uFEFF" + csv.toString()).toByteArray(Charsets.UTF_8)
    }

    fun topProducts(sinceMs: Long): List<TopProduct> {
        val out = mutableListOf<TopProduct>()
        readableDatabase.rawQuery("""SELECT l.product_name, SUM(l.qty) AS units
            FROM sale_lines l JOIN sales s ON s.id=l.sale_id
            WHERE s.created_at >= ? GROUP BY l.product_id, l.product_name
            ORDER BY units DESC LIMIT 5""", arrayOf(sinceMs.toString())).use { c ->
            while (c.moveToNext()) out += TopProduct(c.getString(0), c.getInt(1))
        }
        return out
    }
}
