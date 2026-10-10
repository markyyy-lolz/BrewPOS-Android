package com.brewpos.cafe

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class InventorySyncTest {
    private lateinit var context: Context
    private lateinit var db: StoreDb
    private val org=UUID.randomUUID().toString()
    private val branch=UUID.randomUUID().toString()
    private val product=UUID.randomUUID().toString()
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        context.deleteDatabase("brewpos_local.db")
        db=StoreDb(context)
    }
    @After fun close() { db.close();context.deleteDatabase("brewpos_local.db") }
    private fun catalog(stock: Int=20, tenant: String=org)=JSONArray().put(JSONObject()
        .put("id",product).put("organization_id",tenant).put("branch_id",branch)
        .put("name","Latte").put("category","Coffee").put("price_centavos",12000)
        .put("stock_quantity",stock).put("version",1).put("active",true).put("track_stock",true))
    private fun checkout()=db.checkout(listOf(CartLine(db.products().single(),quantity=2)),"Takeout","Cash",0,24000,"")
    @Test fun freshInstallHasNoInventedCafeStock() { assertTrue(db.products().isEmpty()) }
    @Test fun saleAndStockPersistUntilMatchingAckThenCatalogRefresh() {
        db.bind(org,branch,"owner");db.replaceCatalog(org,branch,catalog());checkout()
        assertEquals(18,db.products().single().stock)
        val event=db.pendingSyncBatch().single()
        val payload=JSONObject(event.second)
        assertEquals(org,payload.getString("organization_id"))
        assertEquals(2,payload.getInt("payload_version"))
        assertEquals(product,payload.getJSONArray("line_items").getJSONObject(0).getString("product_id"))
        assertThrows(IllegalArgumentException::class.java) { db.replaceCatalog(org,branch,catalog(20)) }
        db.close();db=StoreDb(context)
        assertEquals(event,db.pendingSyncBatch().single())
        assertFalse(db.acknowledgeSyncedEvent(UUID.randomUUID().toString()))
        assertTrue(db.acknowledgeSyncedEvent(event.first));assertFalse(db.acknowledgeSyncedEvent(event.first))
        db.replaceCatalog(org,branch,catalog(15));assertEquals(15,db.products().single().stock)
        assertEquals(1,db.sales().size)
    }
    @Test fun branchCannotBeChangedOrForeignCatalogImported() {
        db.bind(org,branch,"owner");db.replaceCatalog(org,branch,catalog())
        assertThrows(IllegalArgumentException::class.java) {db.bind(org,UUID.randomUUID().toString(),"owner")}
        assertThrows(IllegalArgumentException::class.java) {db.replaceCatalog(org,branch,catalog(0,UUID.randomUUID().toString()))}
        assertEquals(20,db.products().single().stock)
    }
    @Test fun shortageIsNotClampedAndCannotBeSold() {
        db.bind(org,branch,"owner");db.replaceCatalog(org,branch,catalog(-3))
        assertEquals(-3,db.products().single().stock)
        assertThrows(IllegalArgumentException::class.java) {checkout()}
        assertEquals(0,db.pendingSyncCount())
    }
    @Test fun unboundHistoricalSalesCannotBeAssignedToArbitraryCafe() {
        db.saveProduct(Product(name="Local Latte",category="Coffee",priceCents=12000,stock=20))
        checkout()
        assertThrows(IllegalArgumentException::class.java) {db.bind(org,branch,"owner")}
        assertEquals(1,db.pendingSyncCount())
    }
    @Test fun upgradePreservesExistingV2ProductsAndPendingSales() {
        db.close()
        val old=context.openOrCreateDatabase("brewpos_local.db",Context.MODE_PRIVATE,null)
        old.execSQL("CREATE TABLE products (id INTEGER PRIMARY KEY,name TEXT NOT NULL,category TEXT NOT NULL,price_cents INTEGER NOT NULL,stock INTEGER NOT NULL,track_stock INTEGER NOT NULL,active INTEGER NOT NULL,icon TEXT NOT NULL)")
        old.execSQL("INSERT INTO products VALUES(1,'Existing Latte','Coffee',12000,7,1,1,'cup')")
        old.execSQL("CREATE TABLE sales (id INTEGER PRIMARY KEY)")
        old.execSQL("INSERT INTO sales VALUES(1)")
        old.execSQL("CREATE TABLE sync_outbox (event_id TEXT PRIMARY KEY,sale_id INTEGER,installation_id TEXT,payload TEXT,state TEXT,created_at INTEGER)")
        old.execSQL("INSERT INTO sync_outbox VALUES('legacy',1,'old','{}','pending',0)")
        old.version=2
        old.close()
        db=StoreDb(context)
        assertEquals(7,db.products().single().stock)
        assertNull(db.products().single().cloudId)
        assertEquals(1,db.pendingSyncCount())
        assertEquals("legacy",db.pendingSyncBatch().single().first)
        assertThrows(IllegalArgumentException::class.java) {db.bind(org,branch,"owner")}
    }
}
