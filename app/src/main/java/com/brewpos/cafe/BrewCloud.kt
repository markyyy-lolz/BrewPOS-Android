package com.brewpos.cafe

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.work.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * BrewPOS Cloud. No service-role secret in the APK. The published key below is a PUBLIC
 * browser/mobile key. Server validates every staff token and device on every upload.
 * Sales remain pending in SQLite unless the cloud returns a matching durable ACK.
 */
object BrewCloud {
    private const val BASE = "https://rfxzbuocxersgbshczbj.supabase.co"
    private const val PUBLISHABLE_KEY = "sb_publishable_CR-CJKna_rcjQi7gg1CZGQ__DnXI6Lb"
    private const val PREFS = "brewpos_cloud"
    private val lock = Mutex()
    private data class Tokens(val access: String, val refresh: String, val expiry: Long)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val saved = keystore.getKey("brewpos_cloud_session_aes", null) as? SecretKey
        if (saved != null) return saved
        val spec = KeyGenParameterSpec.Builder("brewpos_cloud_session_aes",
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply { init(spec) }.generateKey()
    }
    private fun seal(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val blob = cipher.iv + cipher.doFinal(text.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(blob, Base64.NO_WRAP)
    }
    private fun unseal(blob: String): String {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        require(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0,12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8)
    }
    private fun store(ctx:Context, tokens:Tokens) {
        val raw = JSONObject().put("access",tokens.access).put("refresh",tokens.refresh).put("expiry",tokens.expiry).toString()
        prefs(ctx).edit().putString("encrypted_tokens", seal(raw)).apply()
    }
    private fun loaded(ctx: Context): Tokens? {
        return try {
            val value = prefs(ctx).getString("encrypted_tokens",null) ?: return null
            val token = JSONObject(unseal(value))
            Tokens(token.getString("access"),token.getString("refresh"),token.getLong("expiry"))
        } catch (_:Exception) { null }
    }
    fun signedIn(ctx:Context):Boolean = loaded(ctx)!=null
    fun signedBranch(ctx:Context):Boolean =
        prefs(ctx).contains("org_id") && prefs(ctx).contains("branch_id")
    fun signOut(ctx:Context) {
        // Keeps local transactions and pending events intact for later reassignment by an owner.
        prefs(ctx).edit().remove("encrypted_tokens").apply()
    }
    private fun request(url:String, data:JSONObject,token:String?=null):JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod="POST"; connectTimeout=12000; readTimeout=22000; doOutput=true
            setRequestProperty("content-type","application/json")
            setRequestProperty("apikey",PUBLISHABLE_KEY)
            if(token!=null)setRequestProperty("authorization","Bearer $token")
        }
        try {
            connection.outputStream.use { it.write(data.toString().toByteArray(StandardCharsets.UTF_8)) }
            val responseCode = connection.responseCode
            val body = (if(responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if(responseCode !in 200..299) {
                throw IOException(when(responseCode) {
                    400,401 -> "Cloud sign-in failed or session expired"
                    403 -> "Staff/device access not authorized"
                    409 -> "Device already registered"
                    else -> "Cloud service error ($responseCode)"
                })
            }
            return JSONObject(body)
        } finally { connection.disconnect() }
    }
    private fun authPath(grant:String)="$BASE/auth/v1/token?grant_type=$grant"
    private fun syncPath()="$BASE/functions/v1/brewpos-sync"
    private fun parseTokens(data:JSONObject):Tokens {
        val expires = data.optLong("expires_in",3600)
        return Tokens(data.getString("access_token"),data.getString("refresh_token"),
            System.currentTimeMillis() + (expires.coerceAtLeast(60)-30)*1000)
    }
    suspend fun login(ctx:Context,email:String,password:String):Unit = withContext(Dispatchers.IO) {
        val res=request(authPath("password"),JSONObject().put("email",email.trim()).put("password",password))
        store(ctx,parseTokens(res))
        val database=StoreDb(ctx.applicationContext)
        database.binding()?.let { bound ->
            try {
                val members=request(syncPath(),JSONObject().put("action","whoami"),validToken(ctx)).getJSONArray("memberships")
                val member=(0 until members.length()).map {members.getJSONObject(it)}
                    .singleOrNull {it.getString("organization_id")==bound.first} ?: error("Account has no access to this tablet's café")
                database.bind(bound.first,bound.second,member.getString("role"))
            } catch(e:Exception) { signOut(ctx);throw e }
        }
    }
    private fun validToken(ctx:Context):String {
        val saved=loaded(ctx) ?: throw IOException("Sign into BrewPOS Cloud first")
        if(System.currentTimeMillis()<saved.expiry)return saved.access
        val updated=request(authPath("refresh_token"),JSONObject().put("refresh_token",saved.refresh))
        val next=parseTokens(updated)
        store(ctx,next)
        return next.access
    }
    suspend fun memberships(ctx:Context):JSONObject = withContext(Dispatchers.IO) {
        lock.withLock { request(syncPath(),JSONObject().put("action","whoami"),validToken(ctx)) }
    }
    fun installationId(ctx:Context):String = StoreDb(ctx.applicationContext).installationId()
    suspend fun registerDevice(ctx:Context,organizationId:String,branchId:String):Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            val database=StoreDb(ctx.applicationContext)
            val previous=database.binding()
            require(previous==null || (previous.first==organizationId && previous.second==branchId)) { "Installation already linked to another branch" }
            require(previous!=null || database.pendingSyncCount()==0) { "Unlinked historical sales need owner reconciliation first" }
            val memberships=request(syncPath(),JSONObject().put("action","whoami"),validToken(ctx)).getJSONArray("memberships")
            val member=(0 until memberships.length()).map {memberships.getJSONObject(it)}
                .singleOrNull {it.getString("organization_id")==organizationId} ?: error("No active membership")
            val body=JSONObject().put("action","register")
                .put("organization_id",organizationId).put("branch_id",branchId)
                .put("installation_id",installationId(ctx)).put("display_name","BrewPOS Android")
            val response=request(syncPath(),body,validToken(ctx))
            if(!response.optBoolean("registered"))throw IOException("Cloud device registration not confirmed")
            database.bind(organizationId,branchId,member.getString("role"))
            prefs(ctx).edit().putString("org_id",organizationId).putString("branch_id",branchId).apply()
        }
        schedule(ctx)
    }
    suspend fun uploadPending(ctx:Context):Int = withContext(Dispatchers.IO) {
        lock.withLock {
            val database=StoreDb(ctx.applicationContext)
            val bound=database.binding() ?: throw IOException("Register this tablet to securely bind its branch first")
            val org=bound.first
            val branch=bound.second
            var uploaded=0
            // Recheck the token for each batch (it can expire while offline).
            for((id,json) in database.pendingSyncBatch(40)) {
                val payload=JSONObject(json)
                require(payload.optString("organization_id")==org && payload.optString("branch_id")==branch && payload.optInt("payload_version")==2) {
                    "Legacy/unbound sale needs owner reconciliation; it will not be reassigned automatically"
                }
                val requestBody=JSONObject().put("action","sync")
                    .put("organization_id",org).put("branch_id",branch)
                    .put("installation_id",database.installationId())
                    .put("event",payload)
                val ack=request(syncPath(),requestBody,validToken(ctx))
                if(ack.optBoolean("accepted") && ack.optString("event_id")==id && ack.optBoolean("inventory_applied")) {
                    if(database.acknowledgeSyncedEvent(id))uploaded++
                } else throw IOException("Sale acknowledgement was not verified")
            }
            if(database.pendingSyncCount()==0) {
                val catalog=request(syncPath(),JSONObject().put("action","catalog")
                    .put("organization_id",org).put("branch_id",branch).put("installation_id",database.installationId()),validToken(ctx))
                database.replaceCatalog(org,branch,catalog.getJSONArray("products"))
            }
            uploaded
        }
    }
    fun schedule(ctx:Context) {
        val constraint=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val background=PeriodicWorkRequestBuilder<BrewCloudWorker>(15,TimeUnit.MINUTES)
            .setConstraints(constraint).build()
        WorkManager.getInstance(ctx.applicationContext).enqueueUniquePeriodicWork(
            "brewpos-sync-periodic",ExistingPeriodicWorkPolicy.KEEP,background)
        queueNow(ctx)
    }
    fun queueNow(ctx:Context) {
        val req=OneTimeWorkRequestBuilder<BrewCloudWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
        WorkManager.getInstance(ctx.applicationContext)
            .enqueueUniqueWork("brewpos-sync-now",ExistingWorkPolicy.KEEP,req)
    }
}
class BrewCloudWorker(ctx:Context,params:WorkerParameters):CoroutineWorker(ctx,params) {
    override suspend fun doWork():Result {
        if(!BrewCloud.signedIn(applicationContext)||!BrewCloud.signedBranch(applicationContext))return Result.success()
        return try {
            BrewCloud.uploadPending(applicationContext)
            if(StoreDb(applicationContext).pendingSyncCount()>0)Result.retry() else Result.success()
        }catch(_e:Exception) { Result.retry() }
    }
}
