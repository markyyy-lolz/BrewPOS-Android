package com.azurate.brewpos.kiosk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Standalone public kiosk APK: no payment secret, owner credentials or service-role key inside. */
data class KioskBranch(val organizationId:String, val branchId:String, val name:String)
data class KioskProduct(val id:String, val name:String, val category:String, val price:Long, val stock:String, val tracked:Boolean)
data class KioskLine(
    val product:KioskProduct, val quantity:Int=1,
    val size:String="Regular", val sugar:String="100%", val ice:String="Regular", val extraShot:Boolean=false
)
data class KioskOrder(val id:String,val number:String,val amount:Long,val status:String,val qrImage:String?)
object KioskCloud {
    private const val BASE="https://rfxzbuocxersgbshczbj.supabase.co"
    private const val KEY="sb_publishable_CR-CJKna_rcjQi7gg1CZGQ__DnXI6Lb"
    private fun prefs(ctx:Context)=ctx.getSharedPreferences("brewpos_kiosk_secure",Context.MODE_PRIVATE)
    fun installationId(ctx:Context):String {
        val p=prefs(ctx)
        return p.getString("installation_id",null) ?: UUID.randomUUID().toString().also {
            check(p.edit().putString("installation_id",it).commit())
        }
    }
    private fun key():SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("brewpos_kiosk_token",null) as? SecretKey)?.let { return it }
        val spec=KeyGenParameterSpec.Builder("brewpos_kiosk_token",
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
            .apply { init(spec) }.generateKey()
    }
    private fun encrypt(value:String):String {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key())
        return Base64.encodeToString(cipher.iv+cipher.doFinal(value.toByteArray()),Base64.NO_WRAP)
    }
    private fun decrypt(value:String):String {
        val bytes=Base64.decode(value,Base64.NO_WRAP)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        return String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),StandardCharsets.UTF_8)
    }
    fun paired(ctx:Context)=token(ctx)!=null
    private fun token(ctx:Context):String?=runCatching {
        val encrypted=prefs(ctx).getString("token",null) ?: return null
        decrypt(encrypted)
    }.getOrNull()
    private fun post(path:String,payload:JSONObject,bearer:String?=null,kiosk:String?=null):JSONObject {
        val connection=(URL("$BASE$path").openConnection() as HttpURLConnection).apply {
            requestMethod="POST";connectTimeout=12000;readTimeout=24000;doOutput=true
            setRequestProperty("content-type","application/json")
            setRequestProperty("apikey",KEY)
            if(bearer!=null)setRequestProperty("authorization","Bearer $bearer")
            if(kiosk!=null)setRequestProperty("x-kiosk-token",kiosk)
        }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(StandardCharsets.UTF_8)) }
            val code=connection.responseCode
            val body=(if(code in 200..299)connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: "{}"
            val response=JSONObject(body)
            if(code !in 200..299)error(response.optString("error","Kiosk server unavailable ($code)"))
            return response
        } finally {connection.disconnect()}
    }
    private var ownerToken:String?=null // Pairing only; never persisted.
    suspend fun availableBranches(email:String,password:String):List<KioskBranch> = withContext(Dispatchers.IO) {
        val auth=post("/auth/v1/token?grant_type=password",
            JSONObject().put("email",email.trim()).put("password",password))
        ownerToken=auth.getString("access_token")
        val response=post("/functions/v1/brewpos-sync",
            JSONObject().put("action","whoami"),bearer=ownerToken)
        val branches=mutableListOf<KioskBranch>()
        val members=response.getJSONArray("memberships")
        for(i in 0 until members.length()){
            val member=members.getJSONObject(i)
            if(member.getString("role") !in listOf("owner","manager"))continue
            val org=member.getString("organization_id")
            val orgName=member.optString("organization_name","Café")
            val rows=member.getJSONArray("branches")
            for(j in 0 until rows.length()){
                val b=rows.getJSONObject(j)
                branches+=KioskBranch(org,b.getString("id"),"$orgName / ${b.getString("name")}")
            }
        }
        branches
    }
    suspend fun pair(ctx:Context,branch:KioskBranch) = withContext(Dispatchers.IO) {
        val bearer=ownerToken ?: error("Verify an authorized café manager first.")
        try {
            val response=post("/functions/v1/brewpos-kiosk",
                JSONObject().put("action","pair").put("organization_id",branch.organizationId)
                    .put("branch_id",branch.branchId).put("installation_id",installationId(ctx)),
                bearer=bearer)
            val credential=response.getString("kiosk_token")
            check(credential.length>=32)
            check(prefs(ctx).edit().putString("token",encrypt(credential))
                .putString("organization_id",branch.organizationId)
                .putString("branch_id",branch.branchId).commit())
        } finally {ownerToken=null}
    }
    private fun authenticated(ctx:Context,action:String):Pair<JSONObject,String> {
        val t=token(ctx) ?: error("This kiosk has not been paired by a manager.")
        return JSONObject().put("action",action).put("installation_id",installationId(ctx)) to t
    }
    suspend fun catalog(ctx:Context):List<KioskProduct> = withContext(Dispatchers.IO) {
        val (payload,t)=authenticated(ctx,"catalog")
        val response=post("/functions/v1/brewpos-kiosk",payload,kiosk=t)
        val rows=response.getJSONArray("products")
        val products=mutableListOf<KioskProduct>()
        for(i in 0 until rows.length()){
            val p=rows.getJSONObject(i)
            products+=KioskProduct(p.getString("id"),p.getString("name"),
                p.optString("category","Coffee"),p.getLong("price_centavos"),
                p.optString("stock_quantity","0"),p.optBoolean("track_stock",true))
        }
        products
    }
    /** A customer order is NOT a paid sale. QRPh requires confirmed gateway payment. */
    suspend fun order(ctx:Context,submissionId:String,service:String,payment:String,cart:List<KioskLine>):KioskOrder =
        withContext(Dispatchers.IO) {
            val (payload,t)=authenticated(ctx,"order")
            val items=JSONArray()
            cart.forEach { item ->
                items.put(JSONObject().put("product_id",item.product.id)
                    .put("quantity",item.quantity).put("size",item.size)
                    .put("sugar",item.sugar).put("ice",item.ice)
                    .put("extra_shot",item.extraShot))
            }
            payload.put("submission_id",submissionId).put("service",service)
                .put("payment",payment).put("items",items)
            val r=post("/functions/v1/brewpos-kiosk",payload,kiosk=t)
            KioskOrder(r.getString("id"),r.getString("number"),r.getLong("total_centavos"),
                r.getString("payment_status"),r.optString("qr_image").takeIf{it.isNotBlank()})
        }
    suspend fun status(ctx:Context,orderId:String):String=withContext(Dispatchers.IO) {
        val (payload,t)=authenticated(ctx,"status")
        post("/functions/v1/brewpos-kiosk",payload.put("order_id",orderId),kiosk=t)
            .getString("payment_status")
    }
}
