package com.brewpos.kiosk

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.widget.*
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler
import androidx.webkit.WebViewAssetLoader.ResourcesPathHandler
import androidx.webkit.WebViewClientCompat
import org.json.JSONObject
import org.json.JSONArray
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Native Android kiosk: shared static UI, trusted appassets origin only,
 * Android-keystore sealed pairing secret and SQLite outbox. No service-role key.
 * This screen is not a device-owner lock task. Configure Android Enterprise
 * dedicated-device policy separately before leaving it unattended in public.
 */
class KioskActivity : Activity() {
    private lateinit var store: KioskStore
    private lateinit var web: WebView
    private val io=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private val appOrigin="https://appassets.androidplatform.net"
    private lateinit var cfg: Config
    private data class Config(val url:String,val role:String,val key:String,val branch:String)
    private val pref by lazy { getSharedPreferences("brew_kiosk_pairing", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        store=KioskStore(this)
        window.decorView.systemUiVisibility=(View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        val saved=loadConfig()
        if(saved==null)showPairing() else {cfg=saved;showKiosk()}
    }
    private fun secretKey():SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (ks.getKey("brew_kiosk_pair",null) as? SecretKey)?.let{return it}
        val spec=KeyGenParameterSpec.Builder("brew_kiosk_pair",
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build()
        return KeyGenerator.getInstance("AES","AndroidKeyStore").apply{init(spec)}.generateKey()
    }
    private fun seal(s:String):String{
        val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE,secretKey())
        return Base64.encodeToString(c.iv+c.doFinal(s.toByteArray(StandardCharsets.UTF_8)),Base64.NO_WRAP)
    }
    private fun unseal(s:String):String{
        val raw=Base64.decode(s,Base64.NO_WRAP)
        val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE,secretKey(),GCMParameterSpec(128,raw.copyOfRange(0,12)))
        return String(c.doFinal(raw.copyOfRange(12,raw.size)),StandardCharsets.UTF_8)
    }
    private fun loadConfig():Config? {
        return try {
            val value=pref.getString("paired",null) ?: return null
            val o=JSONObject(unseal(value))
            Config(o.getString("url"),o.getString("role"),o.getString("key"),o.getString("branch"))
        }catch(_:Exception){null}
    }
    private fun showPairing(){
        val root=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setPadding(38,54,38,35);setBackgroundColor(Color.rgb(247,249,245))
        }
        val title=TextView(this).apply{text="☕ BrewPOS Kiosk Setup";textSize=26f}
        root.addView(title)
        val hint=TextView(this).apply{text="Staff-only setup. Pair this device to an approved café LAN hub. Requires trusted HTTPS certificate.";textSize=14f}
        root.addView(hint)
        fun field(label:String,value:String=""):EditText{
            val x=EditText(this).apply{this.hint=label;setText(value);setSingleLine(true)}
            root.addView(x);return x
        }
        val address=field("LAN Hub HTTPS URL (e.g. https://192.168.1.15:8443)")
        val branch=field("Café branch display name")
        val roles=Spinner(this).apply{adapter=ArrayAdapter(this@KioskActivity,android.R.layout.simple_spinner_dropdown_item,
            listOf("kiosk","cashier","kitchen"))};root.addView(roles)
        val key=field("Device pairing key (32+ random characters)")
        val save=Button(this).apply{text="PAIR DEVICE AND OPEN KIOSK"}
        root.addView(save)
        save.setOnClickListener{
            val url=address.text.toString().trim().trimEnd('/')
            val role=roles.selectedItem.toString()
            val token=key.text.toString().trim()
            if(!url.startsWith("https://")||token.length<32 ||branch.text.isBlank()){
                Toast.makeText(this,"HTTPS URL, branch and a 32+ character pairing key are required.",Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val data=Config(url,role,token,branch.text.toString().trim())
            save.isEnabled=false
            io.execute{
                val ok=try{fetchWith(data,"GET","/v1/catalog",null)!=null}catch(_:Exception){false}
                main.post{
                    save.isEnabled=true
                    if(!ok){Toast.makeText(this,"Could not verify café HTTPS LAN hub or pairing key.",Toast.LENGTH_LONG).show();return@post}
                    pref.edit().putString("paired",seal(JSONObject().put("url",url).put("role",role)
                        .put("key",token).put("branch",data.branch).toString())).apply()
                    cfg=data;showKiosk()
                }
            }
        }
        val scroll=ScrollView(this).apply{addView(root)}
        setContentView(scroll)
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun showKiosk(){
        val loader=WebViewAssetLoader.Builder().addPathHandler("/ui/",AssetsPathHandler(this)).build()
        web=WebView(this)
        web.settings.javaScriptEnabled=true
        web.settings.domStorageEnabled=false
        web.settings.allowFileAccess=false
        web.settings.allowContentAccess=false
        web.settings.setSupportMultipleWindows(false)
        web.webChromeClient=WebChromeClient()
        web.webViewClient=object:WebViewClientCompat(){
            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest)=loader.shouldInterceptRequest(request.url)
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean=
                request.url.scheme!="https"||request.url.host!="appassets.androidplatform.net"
        }
        // JS bridge is restricted to a bundled HTTPS appassets origin. NEVER load
        // third-party pages inside this WebView while the bridge is installed.
        web.addJavascriptInterface(Bridge(),"BrewKiosk")
        setContentView(web)
        web.loadUrl("$appOrigin/ui/index.html")
        io.execute{
            runCatching{refreshCatalog()}
            syncQueued()
        }
        main.postDelayed(object:Runnable{
            override fun run(){io.execute{syncQueued()};main.postDelayed(this,15000)}
        },15000)
    }
    private fun fetchWith(c:Config,method:String,path:String,body:String?):JSONObject{
        val url=URL(c.url+path)
        require(url.protocol=="https")
        val conn=(url.openConnection() as HttpsURLConnection)
        conn.connectTimeout=5000;conn.readTimeout=8000;conn.requestMethod=method
        conn.setRequestProperty("Authorization","Bearer "+c.key)
        conn.setRequestProperty("Content-Type","application/json")
        if(body!=null){conn.doOutput=true;conn.outputStream.use{it.write(body.toByteArray(StandardCharsets.UTF_8))}}
        try{
            val status=conn.responseCode
            val data=(if(status in 200..299)conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use{it.readText()}?:""
            if(status !in 200..299)throw IllegalStateException("LAN hub responded $status: "+data.take(100))
            return JSONObject(data)
        }finally{conn.disconnect()}
    }
    private fun fetch(method:String,path:String,body:String?=null)=fetchWith(cfg,method,path,body)
    private fun refreshCatalog(){
        val reply=fetch("GET","/v1/catalog")
        val products=reply.getJSONArray("products")
        store.replaceMenu(products)
    }
    private fun syncQueued(){
        if(cfg.role!="kiosk")return
        for((id,payload) in store.queued()){
            try{
                val r=fetch("POST","/v1/orders",payload)
                if(r.optBoolean("accepted"))store.markSent(id) else break
            }catch(e:Exception){
                // Authorization/pricing conflicts are never silently dropped.
                val msg=e.message?:"LAN disconnected"
                if(msg.contains(" 400")||msg.contains(" 409"))store.markReview(id,msg)
                break
            }
        }
    }
    inner class Bridge{
        @JavascriptInterface fun postMessage(raw:String){
            io.execute {
                var id=""
                try{
                    val request=JSONObject(raw);id=request.getString("id")
                    val action=request.getString("action")
                    val data=request.optJSONObject("payload")?:JSONObject()
                    val response:Any=when(action){
                        "bootstrap"->{
                            if(cfg.role=="kiosk")runCatching{refreshCatalog()}
                            JSONObject().put("mode",cfg.role).put("branchName",cfg.branch)
                                .put("hubConnected",runCatching{fetch("GET","/v1/catalog");true}.getOrDefault(false))
                        }
                        "menu"->store.menu()
                        "createOrder"->{
                            if(cfg.role!="kiosk")throw IllegalStateException("Only the customer kiosk can submit an order.")
                            val saved=store.createOrder(data)
                            val delivered=try{
                                val ack=fetch("POST","/v1/orders",saved.second)
                                val accepted=ack.optBoolean("accepted")
                                if(accepted)store.markSent(saved.first)
                                accepted
                            }catch(_:Exception){false}
                            JSONObject().put("number",saved.third).put("delivered",delivered)
                        }
                        "listOrders"->{
                            if(cfg.role=="kiosk")throw IllegalStateException("Staff-only queue.")
                            fetch("GET","/v1/orders")
                        }
                        "updateStatus"->{
                            if(cfg.role=="kiosk")throw IllegalStateException("Staff-only action.")
                            val uuid=UUID.fromString(data.getString("id"))
                            fetch("PATCH","/v1/orders/$uuid/status",JSONObject().put("next",data.getString("next")).toString())
                        }
                        else->throw IllegalArgumentException("Unknown kiosk action.")
                    }
                    reply(id,true,response,null)
                }catch(e:Exception){reply(id,false,JSONObject(),e.message)}
            }
        }
    }
    private fun reply(id:String,ok:Boolean,result:Any,error:String?){
        val data=JSONObject().put("id",id).put("ok",ok).put("result",result).put("error",error)
        main.post{if(::web.isInitialized)web.evaluateJavascript("window.BrewPOSReply("+data.toString()+");",null)}
    }
    override fun onDestroy(){io.shutdownNow();if(::web.isInitialized)web.destroy();super.onDestroy()}
}
