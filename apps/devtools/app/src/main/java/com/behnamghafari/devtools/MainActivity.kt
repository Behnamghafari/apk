package com.behnamghafari.devtools
import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.*
import android.widget.LinearLayout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
 private lateinit var site: WebView
 private lateinit var panel: WebView
 private val http=OkHttpClient.Builder().connectTimeout(30,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).build()
 @SuppressLint("SetJavaScriptEnabled")
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
  site=WebView(this).apply{
   settings.javaScriptEnabled=true;settings.domStorageEnabled=true;settings.databaseEnabled=true
   addJavascriptInterface(SiteBridge(),"DevToolsBridge")
   webViewClient=object:WebViewClient(){
    override fun onPageFinished(v:WebView,url:String){inject();emit("navigation",JSONObject().put("url",url))}
    override fun shouldInterceptRequest(v:WebView,r:WebResourceRequest):WebResourceResponse?{
     val h=JSONObject();r.requestHeaders.forEach{(k,x)->h.put(k,x)}
     emit("network",JSONObject().put("url",r.url.toString()).put("method",r.method).put("headers",h).put("source","webview"))
     return super.shouldInterceptRequest(v,r)
    }
   }
   webChromeClient=object:WebChromeClient(){
    override fun onConsoleMessage(m:ConsoleMessage):Boolean{emit("console",JSONObject().put("level",m.messageLevel().name.lowercase()).put("message",m.message()).put("source",m.sourceId()).put("line",m.lineNumber()));return true}
   }
  }
  panel=WebView(this).apply{settings.javaScriptEnabled=true;settings.domStorageEnabled=true;addJavascriptInterface(PanelBridge(),"NativeTools");webViewClient=WebViewClient()}
  root.addView(site,LinearLayout.LayoutParams(-1,0,6f));root.addView(panel,LinearLayout.LayoutParams(-1,0,5f));setContentView(root)
  panel.loadUrl("file:///android_asset/devtools.html")
 }
 private fun inject(){
  val js="""(function(){if(window.__dt)return;window.__dt=true;const send=(k,d)=>{try{DevToolsBridge.postEvent(k,JSON.stringify(d))}catch(e){}};
  const f=fetch;window.fetch=function(i,o){const u=typeof i==='string'?i:i.url,m=(o&&o.method)||(i&&i.method)||'GET',h={};try{new Headers((o&&o.headers)||(i&&i.headers)||{}).forEach((v,k)=>h[k]=v)}catch(e){}send('network',{url:u,method:m,headers:h,source:'fetch',phase:'request'});let t=Date.now();return f.apply(this,arguments).then(r=>{let rh={};r.headers.forEach((v,k)=>rh[k]=v);send('network',{url:u,method:m,status:r.status,responseHeaders:rh,duration:Date.now()-t,source:'fetch',phase:'response'});return r}).catch(e=>{send('network',{url:u,method:m,error:String(e),source:'fetch'});throw e})};
  const op=XMLHttpRequest.prototype.open,sh=XMLHttpRequest.prototype.setRequestHeader,sx=XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open=function(m,u){this.__dt={method:m,url:u,headers:{}};return op.apply(this,arguments)};
  XMLHttpRequest.prototype.setRequestHeader=function(k,v){if(this.__dt)this.__dt.headers[k]=v;return sh.apply(this,arguments)};
  XMLHttpRequest.prototype.send=function(){let d=this.__dt||{},t=Date.now();send('network',{...d,source:'xhr',phase:'request'});this.addEventListener('loadend',()=>{let h={};try{this.getAllResponseHeaders().trim().split(/[\\r\\n]+/).forEach(z=>{let p=z.indexOf(':');if(p>0)h[z.slice(0,p)]=z.slice(p+1).trim()})}catch(e){}send('network',{url:d.url,method:d.method,status:this.status,responseHeaders:h,duration:Date.now()-t,source:'xhr',phase:'response'})},{once:true});return sx.apply(this,arguments)};
  ['log','info','warn','error'].forEach(l=>{let old=console[l];console[l]=function(){send('console',{level:l,message:[...arguments].map(String).join(' ')});return old.apply(this,arguments)}});addEventListener('error',e=>send('console',{level:'error',message:e.message,source:e.filename,line:e.lineno}));addEventListener('unhandledrejection',e=>send('console',{level:'error',message:String(e.reason)}));send('console',{level:'info',message:'Inspector connected'})})();"""
  site.evaluateJavascript(js,null)
 }
 private fun emit(k:String,d:JSONObject){if(!::panel.isInitialized)return;val p=JSONObject.quote(d.toString());runOnUiThread{panel.evaluateJavascript("window.DevToolsUI&&window.DevToolsUI.receive('"+k+"',"+p+")",null)}}
 inner class SiteBridge{@JavascriptInterface fun postEvent(k:String,j:String){try{emit(k,JSONObject(j))}catch(_:Exception){}}}
 inner class PanelBridge{
  @JavascriptInterface fun open(u:String){runOnUiThread{if(u.startsWith("https://")||u.startsWith("http://"))site.loadUrl(u)}}
  @JavascriptInterface fun inspect(k:String){runOnUiThread{val js=when(k){"dom"->"document.documentElement.outerHTML";"storage"->"JSON.stringify({local:Object.keys(localStorage).reduce((a,k)=>(a[k]=localStorage.getItem(k),a),{}),session:Object.keys(sessionStorage).reduce((a,k)=>(a[k]=sessionStorage.getItem(k),a),{})})";else->"JSON.stringify(performance.getEntriesByType('resource').map(x=>({name:x.name,duration:x.duration,size:x.transferSize})))"};site.evaluateJavascript(js){v->emit("inspect",JSONObject().put("kind",k).put("value",v?:"null"))}}}
  @JavascriptInterface fun clearData(){runOnUiThread{site.clearCache(true);site.evaluateJavascript("localStorage.clear();sessionStorage.clear()",null);CookieManager.getInstance().removeAllCookies(null)}}
  @JavascriptInterface fun request(raw:String){Thread{val d=JSONObject(raw);val id=d.optString("id");try{val b=Request.Builder().url(d.getString("url"));val h=d.optJSONObject("headers")?:JSONObject();h.keys().forEach{b.addHeader(it,h.getString(it))};val m=d.optString("method","GET");val body=d.optString("body","");val rb=if(m=="GET"||m=="HEAD")null else body.toRequestBody(d.optString("contentType","application/json").toMediaTypeOrNull());val start=System.currentTimeMillis();http.newCall(b.method(m,rb).build()).execute().use{r->val rh=JSONObject();r.headers.names().forEach{rh.put(it,r.headers.values(it).joinToString(", "))};val result=JSONObject().put("id",id).put("status",r.code).put("duration",System.currentTimeMillis()-start).put("headers",rh).put("body",r.body?.string().orEmpty());runOnUiThread{panel.evaluateJavascript("window.DevToolsUI.apiResult("+JSONObject.quote(result.toString())+")",null)}}}catch(e:Exception){val result=JSONObject().put("id",id).put("error",e.message?:"Request failed");runOnUiThread{panel.evaluateJavascript("window.DevToolsUI.apiResult("+JSONObject.quote(result.toString())+")",null)}}}.start()}
 }
 override fun onBackPressed(){if(::site.isInitialized&&site.canGoBack())site.goBack()else super.onBackPressed()}
 override fun onDestroy(){if(::site.isInitialized)site.destroy();if(::panel.isInitialized)panel.destroy();super.onDestroy()}
}