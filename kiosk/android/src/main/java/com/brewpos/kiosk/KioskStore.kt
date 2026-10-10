package com.brewpos.kiosk

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Durable device outbox: never clear on network/printer failure. */
class KioskStore(ctx:Context):SQLiteOpenHelper(ctx,"brewpos-kiosk.db",null,1){
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE outbox(id TEXT PRIMARY KEY,payload TEXT NOT NULL,status TEXT NOT NULL,created INTEGER NOT NULL,error TEXT)")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){}
    fun setting(key:String):String?=readableDatabase.rawQuery("SELECT value FROM meta WHERE key=?",arrayOf(key))
        .use{c->if(c.moveToFirst())c.getString(0) else null}
    fun set(key:String,value:String){
        writableDatabase.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)",arrayOf(key,value))
    }
    fun installationId():String=setting("installation_id")?:UUID.randomUUID().toString().also{set("installation_id",it)}
    fun menu():JSONArray=JSONArray(setting("menu")?:"[]")
    fun replaceMenu(items:JSONArray){require(items.length()<=1000);set("menu",items.toString())}
    fun createOrder(request:JSONObject):Triple<String,String,String>{
        require(request.optString("paymentMethod")=="counter"){"Only Pay at Counter works on this kiosk."}
        require(request.optString("service") in setOf("Dine-in","Takeout"))
        val lines=request.getJSONArray("lines");require(lines.length() in 1..80)
        val products=menu()
        val byId=(0 until products.length()).associate{val p=products.getJSONObject(it);p.getString("id") to p}
        var sum=0L
        val packed=JSONArray()
        for(i in 0 until lines.length()){
            val item=lines.getJSONObject(i)
            val product=byId[item.getString("productId")]?:throw IllegalStateException("Unknown cached menu item.")
            require(product.optBoolean("active"))
            val qty=item.getInt("quantity");require(qty in 1..99)
            val version=item.getLong("productVersion")
            require(version==product.getLong("version")){"Menu changed. Please restart the order."}
            var price=product.getLong("priceCentavos")
            val mods=product.optJSONArray("modifiers")?:JSONArray()
            val selections=item.optJSONArray("options")?:JSONArray()
            for(j in 0 until mods.length()){
                val group=mods.getJSONObject(j)
                val id=group.getString("id")
                val options=group.optJSONArray("options")?:JSONArray()
                val choice=(0 until selections.length()).map{selections.getJSONObject(it)}
                    .firstOrNull{it.getString("groupId")==id}
                val selected=choice?.optJSONArray("optionIds")?:JSONArray()
                if(group.optBoolean("required"))require(selected.length()>0)
                if(!group.optBoolean("multiple"))require(selected.length()<=1)
                require(selected.length()<=group.optInt("maxSelections",4))
                for(k in 0 until selected.length()){
                    val oid=selected.getString(k)
                    val modifier=(0 until options.length()).map{options.getJSONObject(it)}
                        .firstOrNull{it.getString("id")==oid} ?:throw IllegalArgumentException("Invalid modifier.")
                    price=Math.addExact(price,modifier.getLong("deltaCentavos"))
                }
            }
            require(price>=0&&price==item.getLong("unitCentavos")){"Price changed. Reconfirm selection."}
            sum=Math.addExact(sum,Math.multiplyExact(price,qty.toLong()))
            packed.put(JSONObject().put("productId",product.getString("id"))
                .put("name",product.getString("name")).put("quantity",qty)
                .put("unitCentavos",price).put("productVersion",version)
                .put("options",selections).put("optionLabel",item.optString("optionLabel")))
        }
        require(sum in 1..100000000)
        val uuid=UUID.randomUUID().toString()
        val number="K-"+uuid.replace("-","").take(9).uppercase()
        val order=JSONObject().put("id",uuid).put("number",number)
            .put("installationId",installationId())
            .put("service",request.getString("service"))
            .put("notes",request.optString("notes").take(60))
            .put("paymentMethod","counter").put("lines",packed)
            .put("totalCentavos",sum).put("status","AwaitingPayment")
        val payload=order.toString()
        writableDatabase.execSQL("INSERT INTO outbox(id,payload,status,created) VALUES(?,?,'queued',?)",
            arrayOf(uuid,payload,System.currentTimeMillis()))
        return Triple(uuid,payload,number)
    }
    fun queued():List<Pair<String,String>>{
        val list=mutableListOf<Pair<String,String>>()
        readableDatabase.rawQuery("SELECT id,payload FROM outbox WHERE status='queued' ORDER BY created,id LIMIT 100",null)
            .use{c->while(c.moveToNext())list.add(c.getString(0) to c.getString(1))}
        return list
    }
    fun markSent(id:String)=writableDatabase.execSQL("UPDATE outbox SET status='sent' WHERE id=?",arrayOf(id))
    fun markReview(id:String,msg:String)=writableDatabase.execSQL("UPDATE outbox SET status='review',error=? WHERE id=?",arrayOf(msg.take(500),id))
}
