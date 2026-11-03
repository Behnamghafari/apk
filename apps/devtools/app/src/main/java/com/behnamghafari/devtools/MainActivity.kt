package com.behnamghafari.devtools

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import android.webkit.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
 private lateinit var site: WebView
 private lateinit var panel: WebView
 private lateinit var root: LinearLayout
 private lateinit var browserArea: LinearLayout
 private lateinit var handle: View
 private lateinit var address: EditText
 private lateinit var devButton: Button
 private lateinit var menuButton: Button
 private var devOpen=false
 private var apiFullscreen=false
 private var panelHeight=280
 private var dragStartY=0f
 private var dragStartHeight=0
 private val http=OkHttpClient.Builder().connectTimeout(30,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).build()

 @SuppressLint("SetJavaScriptEnabled")
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xff0f172a.toInt())}
  val toolbar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(5,5,5,5);setBackgroundColor(0xff111c30.toInt())}
  devButton=Button(this).apply{text="DEV";textSize=11f;setPadding(8,0,8,0);setOnClickListener{if(apiFullscreen)exitApiFullscreen()else toggleDevTools()}}
  address=EditText(this).apply{setSingleLine(true);textSize=13f;hint="Search or enter website";setTextColor(0xfff1f5f9.toInt());setHintTextColor(0xff94a3b8.toInt());setPadding(10,0,10,0);setBackgroundColor(0xff1e293b.toInt());imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_GO;setOnEditorActionListener{_,_,_->loadAddress();true}}
  val go=Button(this).apply{text="GO";textSize=11f;setPadding(5,0,5,0);setOnClickListener{loadAddress()}}
  menuButton=Button(this).apply{text="⋮";textSize=20f;setPadding(4,0,4,0);setOnClickListener{showMenu()}}
  toolbar.addView(devButton,LinearLayout.LayoutParams(52,48))
  toolbar.addView(address,LinearLayout.LayoutParams(0,48,1f))
  toolbar.addView(go,LinearLayout.LayoutParams(48,48))
  toolbar.addView(menuButton,LinearLayout.LayoutParams(44,48))
  root.addView(toolbar,LinearLayout.LayoutParams(-1,58))

  browserArea=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
  site=WebView(this).apply{
   settings.javaScriptEnabled=true;settings.domStorageEnabled=true;settings.databaseEnabled=true
   settings.loadsImagesAutomatically=true;settings.javaScriptCanOpenWindowsAutomatically=true
   addJavascriptInterface(SiteBridge(),"DevToolsBridge")
   webViewClient=object:WebViewClient(){
    override fun onPageStarted(v:WebView,url:String,favicon:android.graphics.Bitmap?){address.setText(url)}
    override fun onPageFinished(v:WebView,url:String){address.setText(url);inject();emit("navigation",JSONObject().put("url",url))}
    override fun shouldInterceptRequest(v:WebView,r:WebResourceRequest):WebResourceResponse?{
     val h=JSONObject();r.requestHeaders.forEach{(k,x)->h.put(k,x)}
     emit("network",JSONObject().put("url",r.url.toString()).put("method",r.method).put("headers",h).put("source","webview"))
     return super.shouldInterceptRequest(v,r)
    }
   }
   webChromeClient=object:WebChromeClient(){
    override fun onConsoleMessage(m:ConsoleMessage):Boolean{emit("console",JSONObject().put("level",m.messageLevel().name.lowercase()).put("message",m.message()).put("source",m.sourceId()).put("line",m.lineNumber()));return true}
    override fun onProgressChanged(v:WebView,p:Int){if(p==100)address.setText(v.url?: "")}
   }
  }
  browserArea.addView(site,LinearLayout.LayoutParams(-1,0,1f))
  handle=View(this).apply{setBackgroundColor(0xff334155.toInt());visibility=View.GONE;setOnTouchListener{_,e->
   when(e.action){MotionEvent.ACTION_DOWN->{dragStartY=e.rawY;dragStartHeight=panelHeight;true}
    MotionEvent.ACTION_MOVE->{val delta=(dragStartY-e.rawY).toInt();panelHeight=(dragStartHeight+delta).coerceIn(130,resources.displayMetrics.heightPixels-180);panel.layoutParams=LinearLayout.LayoutParams(-1,dp(panelHeight));true}
    MotionEvent.ACTION_UP->true;else->true}
  }}
  browserArea.addView(handle,LinearLayout.LayoutParams(-1,dp(8)))
  panel=WebView(this).apply{settings.javaScriptEnabled=true;settings.domStorageEnabled=true;addJavascriptInterface(PanelBridge(),"NativeTools");webViewClient=WebViewClient()}
  panel.visibility=View.GONE
  browserArea.addView(panel,LinearLayout.LayoutParams(-1,dp(panelHeight)))
  root.addView(browserArea,LinearLayout.LayoutParams(-1,0,1f))
  setContentView(root)
  panel.loadUrl("file:///android_asset/devtools.html")
  site.loadUrl("https://www.google.com")
 }
 private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
 private fun loadAddress(){var u=address.text.toString().trim();if(u.isEmpty())return;if(!u.contains("://"))u=if(u.contains(".")&&!u.contains(" "))"https://$u" else "https://www.google.com/search?q="+android.net.Uri.encode(u);site.loadUrl(u)}
 private fun toggleDevTools(){if(apiFullscreen){exitApiFullscreen()};devOpen=!devOpen;panel.visibility=if(devOpen)View.VISIBLE else View.GONE;handle.visibility=if(devOpen)View.VISIBLE else View.GONE;devButton.text=if(devOpen)"CLOSE" else "DEV";site.visibility=View.VISIBLE}
 private fun showMenu(){val p=android.widget.PopupMenu(this,menuButton);p.menu.add("API Tester").setOnMenuItemClickListener{openApiFullscreen();true};p.menu.add("Reload page").setOnMenuItemClickListener{site.reload();true};p.menu.add("Back").setOnMenuItemClickListener{if(site.canGoBack())site.goBack();true};p.menu.add("Forward").setOnMenuItemClickListener{if(site.canGoForward())site.goForward();true};p.menu.add("Home").setOnMenuItemClickListener{site.loadUrl("https://www.google.com");true};p.show()}
 private fun openApiFullscreen(){apiFullscreen=true;devOpen=false;site.visibility=View.GONE;handle.visibility=View.GONE;panel.visibility=View.VISIBLE;panel.layoutParams=LinearLayout.LayoutParams(-1,0,1f);devButton.text="BROWSER";panel.evaluateJavascript("window.DevToolsUI&&window.DevToolsUI.openApi()",null)}
 private fun exitApiFullscreen(){apiFullscreen=false;panel.layoutParams=LinearLayout.LayoutParams(-1,dp(panelHeight));site.visibility=View.VISIBLE;devOpen=false;panel.visibility=View.GONE;handle.visibility=View.GONE;devButton.text="DEV"}
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
  @JavascriptInterface fun closeApi(){runOnUiThread{exitApiFullscreen()}}
  @JavascriptInterface fun inspect(k:String){runOnUiThread{val js=when(k){"dom"->"document.documentElement.outerHTML";"storage"->"JSON.stringify({local:Object.keys(localStorage).reduce((a,k)=>(a[k]=localStorage.getItem(k),a),{}),session:Object.keys(sessionStorage).reduce((a,k)=>(a[k]=sessionStorage.getItem(k),a),{})})";else->"JSON.stringify(performance.getEntriesByType('resource').map(x=>({name:x.name,duration:x.duration,size:x.transferSize})))"};site.evaluateJavascript(js){v->emit("inspect",JSONObject().put("kind",k).put("value",v?:"null"))}}}
  @JavascriptInterface fun clearData(){runOnUiThread{site.clearCache(true);site.evaluateJavascript("localStorage.clear();sessionStorage.clear()",null);CookieManager.getInstance().removeAllCookies(null)}}
  @JavascriptInterface fun request(raw:String){Thread{val d=JSONObject(raw);val id=d.optString("id");try{val b=Request.Builder().url(d.getString("url"));val h=d.optJSONObject("headers")?:JSONObject();h.keys().forEach{b.addHeader(it,h.getString(it))};val m=d.optString("method","GET");val body=d.optString("body","");val rb=if(m=="GET"||m=="HEAD")null else body.toRequestBody(d.optString("contentType","application/json").toMediaTypeOrNull());val start=System.currentTimeMillis();http.newCall(b.method(m,rb).build()).execute().use{r->val rh=JSONObject();r.headers.names().forEach{rh.put(it,r.headers.values(it).joinToString(", "))};val result=JSONObject().put("id",id).put("status",r.code).put("duration",System.currentTimeMillis()-start).put("headers",rh).put("body",r.body?.string().orEmpty());runOnUiThread{panel.evaluateJavascript("window.DevToolsUI.apiResult("+JSONObject.quote(result.toString())+")",null)}}}catch(e:Exception){val result=JSONObject().put("id",id).put("error",e.message?:"Request failed");runOnUiThread{panel.evaluateJavascript("window.DevToolsUI.apiResult("+JSONObject.quote(result.toString())+")",null)}}}.start()}
 }
 @Suppress("DEPRECATION")
 override fun onBackPressed(){when{apiFullscreen->exitApiFullscreen();devOpen->toggleDevTools();site.canGoBack()->site.goBack();else->super.onBackPressed()}}
 override fun onDestroy(){if(::site.isInitialized)site.destroy();if(::panel.isInitialized)panel.destroy();super.onDestroy()}
}