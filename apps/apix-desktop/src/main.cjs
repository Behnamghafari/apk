const { app, BrowserWindow, ipcMain, dialog, shell, clipboard } = require('electron');
const path = require('node:path');
const fs = require('node:fs');
const http = require('node:http');
const { performance } = require('node:perf_hooks');
const Database = require('better-sqlite3');
const { WebSocket } = require('ws');

let win, db, mockServer, pendingSqliteRestorePath=null;
const sockets = new Map();
const MAX_RESPONSE = 5 * 1024 * 1024;

function emit(name, payload) {
  if (!win || win.isDestroyed()) return;
  const value = JSON.stringify(payload);
  const callbacks = {
    onResult: `window.BehnamTester && window.BehnamTester.onResult && window.BehnamTester.onResult(${value})`,
    onWebSocketEvent: `window.BehnamTester && window.BehnamTester.onWebSocketEvent && window.BehnamTester.onWebSocketEvent(${value})`,
    onOpenApiFileSelected: `window.onOpenApiFileSelected && window.onOpenApiFileSelected(${JSON.stringify(payload.raw)}, ${JSON.stringify(payload.name)})`,
    onJsonFileSelected: `window.onJsonFileSelected && window.onJsonFileSelected(${JSON.stringify(payload.type)}, ${JSON.stringify(payload.raw)})`,
    onBackupFileSelected: `window.onBackupFileSelected && window.onBackupFileSelected(${value})`,
    onBackupSaved: `window.onBackupSaved && window.onBackupSaved(${value})`,
    onSqliteBackupSelected: `window.onSqliteBackupSelected && window.onSqliteBackupSelected(${value})`,
    onUploadFilesSelected: `window.BehnamTester && window.BehnamTester.onUploadFilesSelected && window.BehnamTester.onUploadFilesSelected(${value})`,
    onUploadResult: `window.BehnamTester && window.BehnamTester.onUploadResult && window.BehnamTester.onUploadResult(${value})`,
    onSecurityAuditResult: `window.BehnamTester && window.BehnamTester.onSecurityAuditResult && window.BehnamTester.onSecurityAuditResult(${value})`,
    onMonitorResult: `window.BehnamTester && window.BehnamTester.onMonitorResult && window.BehnamTester.onMonitorResult(${value})`,
    onLoadTestResult: `window.BehnamTester && window.BehnamTester.onLoadTestResult && window.BehnamTester.onLoadTestResult(${value})`,
    toast: `console.info('[ApiX]', ${value})`
  };
  const script = callbacks[name];
  if (script) win.webContents.executeJavaScript(script).catch(err => console.error('ApiX callback', name, err));
}
function initDb() {
  const file = path.join(app.getPath('userData'), 'apix.sqlite');
  db = new Database(file);
  db.pragma('journal_mode = WAL');
  db.exec('CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)');
  db.exec('CREATE TABLE IF NOT EXISTS cookies (name TEXT, value TEXT, domain TEXT, path TEXT, expires TEXT, secure INTEGER DEFAULT 0, httpOnly INTEGER DEFAULT 0, PRIMARY KEY(name,domain,path))');
}
function makeWindow() {
  win = new BrowserWindow({
    width: 1440, height: 940, minWidth: 900, minHeight: 650,
    backgroundColor: '#0b1120',
    title: 'ApiX',
    autoHideMenuBar: true,
    webPreferences: { preload: path.join(__dirname, 'preload.cjs'), contextIsolation: true, nodeIntegration: false, sandbox: true }
  });
  win.loadFile(path.join(__dirname, '..', 'assets', 'tester.html'));
  win.on('closed', () => { win = null; });
}
app.whenReady().then(() => { initDb(); makeWindow(); app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) makeWindow(); }); });
app.on('window-all-closed', () => { for (const ws of sockets.values()) try { ws.close(1001, 'Application closing'); } catch {} if (mockServer) mockServer.close(); if (process.platform !== 'darwin') app.quit(); });
app.on('before-quit', () => { try { if (db) db.close(); } catch {} });

ipcMain.on('apix:sync', (event, action, ...args) => {
  try {
    if (action === 'shouldImportLegacyStorage') event.returnValue = db.prepare('SELECT COUNT(*) AS n FROM kv').get().n === 0;
    else if (action === 'getStorageSnapshot') event.returnValue = JSON.stringify(db.prepare('SELECT key,value FROM kv').all().map(r => ({key:r.key,value:JSON.parse(r.value)})));
    else if (action === 'setStorageValue') {
      const [key,value] = args;
      if (typeof key === 'string' && key.startsWith('bt_') && key.length <= 160 && String(value).length <= 8*1024*1024)
        db.prepare('INSERT INTO kv(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value').run(key, String(value));
      event.returnValue = true;
    } else if (action === 'getCookies') event.returnValue = JSON.stringify(db.prepare('SELECT * FROM cookies').all());
    else if (action === 'mockServerStatus') event.returnValue = !!mockServer && mockServer.listening;
    else if (action === 'getAppVersion') event.returnValue = app.getVersion();
    else if (action === 'wsSend') { const ws=sockets.get(args[0]); if(ws && ws.readyState===WebSocket.OPEN){ws.send(args[1]);event.returnValue=true;}else event.returnValue=false; }
    else if (action === 'wsClose') { const ws=sockets.get(args[0]); if(ws)ws.close(args[1]||1000,args[2]||'Closed');sockets.delete(args[0]);event.returnValue=true; }
    else if (action === 'startMockServer') event.returnValue = startMockServer(args[0]);
    else if (action === 'stopMockServer') { if(mockServer)mockServer.close();mockServer=null;event.returnValue=true; }
    else if (action === 'addCookie') {
      const [url,setCookie]=args;const u=new URL(url);const parts=String(setCookie).split(';').map(x=>x.trim());const [name,value]=parts[0].split('=');
      const domain=(parts.find(x=>/^domain=/i.test(x))||'').split('=')[1]||u.hostname;
      const cookiePath=(parts.find(x=>/^path=/i.test(x))||'Path=/').split('=')[1]||'/';
      db.prepare('INSERT OR REPLACE INTO cookies(name,value,domain,path,secure,httpOnly) VALUES(?,?,?,?,?,?)').run(name,value,domain,cookiePath,/secure/i.test(setCookie)?1:0,/httponly/i.test(setCookie)?1:0);
      event.returnValue=JSON.stringify({ok:true});
    }
    else if (action === 'deleteCookie') { db.prepare('DELETE FROM cookies WHERE name=? AND domain=? AND path=?').run(...args);event.returnValue=true; }
    else if (action === 'clearCookies') { db.prepare('DELETE FROM cookies').run();event.returnValue=true; }
    else if (action === 'restoreSqliteBackup') {
      const selected=pendingSqliteRestorePath ? [pendingSqliteRestorePath] : dialog.showOpenDialogSync(win,{properties:['openFile'],filters:[{name:'SQLite backup',extensions:['sqlite','db']}]});
      if(!selected){event.returnValue=JSON.stringify({ok:false,error:'Restore cancelled'});}
      else {
        const source=new Database(selected[0],{readonly:true,fileMustExist:true});
        try {
          const rows=source.prepare('SELECT key,value FROM kv').all();
          const map=new Map(rows.map(r=>[r.key,JSON.parse(r.value)]));
          const folders=Array.isArray(map.get('bt_folders'))?map.get('bt_folders').length:0;
          const collections=Array.isArray(map.get('bt_collections'))?map.get('bt_collections').length:0;
          const tx=db.transaction(()=>{db.prepare('DELETE FROM kv').run();const put=db.prepare('INSERT INTO kv(key,value) VALUES(?,?)');for(const row of rows)put.run(row.key,row.value);});
          tx();pendingSqliteRestorePath=null;event.returnValue=JSON.stringify({ok:true,count:rows.length,folders,collections});
        } finally { source.close(); }
      }
    }
    else event.returnValue = null;
  } catch (e) { event.returnValue = null; }
});

ipcMain.handle('apix:action', async (_event, action, ...args) => {
  if (action === 'importStorageRecords' || action === 'replaceStorageSnapshot') {
    const records = JSON.parse(args[0] || '[]');
    if (!Array.isArray(records)) throw new Error('Invalid storage records');
    const tx = db.transaction(() => {
      if (action === 'replaceStorageSnapshot') db.prepare('DELETE FROM kv').run();
      const put = db.prepare('INSERT INTO kv(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value');
      for (const r of records) if (r && typeof r.key === 'string' && r.key.startsWith('bt_') && r.key.length <= 160) put.run(r.key, JSON.stringify(r.value));
    });
    tx(); return records.length;
  }
  if (action === 'sendRequest') return sendRequest(args[0]);
  if (action === 'wsConnect') return connectWs(args[0]);
  if (action === 'wsSend') { const ws=sockets.get(args[0]); if (!ws || ws.readyState!==WebSocket.OPEN) return false; ws.send(args[1]); return true; }
  if (action === 'wsClose') { const ws=sockets.get(args[0]); if (ws) ws.close(args[1] || 1000,args[2] || 'Closed'); sockets.delete(args[0]); return true; }
  if (action === 'chooseOpenApiFile' || action === 'chooseJsonFile' || action === 'chooseBackupFile') {
    const res = await dialog.showOpenDialog(win,{properties:['openFile'],filters:[{name:'API definition and data',extensions:['json','yaml','yml','txt','*']}]});
    if (!res.canceled && res.filePaths[0]) {
      const raw=fs.readFileSync(res.filePaths[0],'utf8');
      if (action==='chooseOpenApiFile') emit('onOpenApiFileSelected',{raw,name:path.basename(res.filePaths[0])});
      else if (action==='chooseJsonFile') emit('onJsonFileSelected',{type:args[0],raw});
      else emit('onBackupFileSelected',raw);
    } return !res.canceled;
  }
  if (action === 'saveBackupFile') {
    const [raw,name] = args;
    const res=await dialog.showSaveDialog(win,{defaultPath:name || 'apix-backup.json',filters:[{name:'JSON backup',extensions:['json']}]});
    if (!res.canceled) fs.writeFileSync(res.filePath,raw,'utf8');
    emit('onBackupSaved',JSON.stringify({ok:!res.canceled,filePath:res.filePath})); return !res.canceled;
  }
  if (action === 'createSqliteBackup') {
    const res=await dialog.showSaveDialog(win,{defaultPath:'apix-backup.sqlite',filters:[{name:'SQLite database',extensions:['sqlite','db']}]});
    if (!res.canceled) { db.pragma('wal_checkpoint(FULL)'); fs.copyFileSync(path.join(app.getPath('userData'),'apix.sqlite'),res.filePath); }
    emit('onBackupSaved',JSON.stringify({ok:!res.canceled})); return !res.canceled;
  }
  if (action === 'chooseSqliteBackupFile') {
    const res=await dialog.showOpenDialog(win,{properties:['openFile'],filters:[{name:'SQLite backup',extensions:['sqlite','db']}]});
    if (!res.canceled) {
      pendingSqliteRestorePath=res.filePaths[0];
      const source=new Database(res.filePaths[0],{readonly:true,fileMustExist:true});
      try { const rows=source.prepare('SELECT key,value FROM kv').all();const map=new Map(rows.map(r=>[r.key,JSON.parse(r.value)]));emit('onSqliteBackupSelected',JSON.stringify({count:rows.length,folders:Array.isArray(map.get('bt_folders'))?map.get('bt_folders').length:0,collections:Array.isArray(map.get('bt_collections'))?map.get('bt_collections').length:0})); }
      finally { source.close(); }
    } return !res.canceled;
  }
  if (action === 'restoreSqliteBackup') throw new Error('Restore is not yet available in the Windows desktop build.');
  if (action === 'chooseUploadFiles') {
    const res=await dialog.showOpenDialog(win,{properties:['openFile','multiSelections']});
    if (!res.canceled) emit('onUploadFilesSelected',JSON.stringify(res.filePaths.map(p=>({name:path.basename(p),path:p,size:fs.statSync(p).size}))));
    return !res.canceled;
  }
  if (action === 'copyText') { clipboard.writeText(args[0] || ''); return true; }
  if (action === 'openPrivacyPolicy') { await shell.openExternal('https://github.com/Behnamghafari/apk/blob/main/apps/behnam-tester/PRIVACY.md'); return true; }
  if (action === 'openTelegram') { await shell.openExternal('https://t.me/behnamghafari'); return true; }
  if (action === 'toast') { emit('toast',args[0]); return true; }
  if (action === 'addCookie') {
    const [url,setCookie]=args; const u=new URL(url); const parts=String(setCookie).split(';').map(x=>x.trim()); const [name,value]=parts[0].split('=');
    const domain=(parts.find(x=>/^domain=/i.test(x))||'').split('=')[1]||u.hostname;
    const cookiePath=(parts.find(x=>/^path=/i.test(x))||'Path=/').split('=')[1]||'/';
    db.prepare('INSERT OR REPLACE INTO cookies(name,value,domain,path,secure,httpOnly) VALUES(?,?,?,?,?,?)').run(name,value,domain,cookiePath,/secure/i.test(setCookie)?1:0,/httponly/i.test(setCookie)?1:0);
    return JSON.stringify({ok:true});
  }
  if (action === 'deleteCookie') { db.prepare('DELETE FROM cookies WHERE name=? AND domain=? AND path=?').run(...args); return true; }
  if (action === 'clearCookies') { db.prepare('DELETE FROM cookies').run(); return true; }
  
  if (action === 'runSecurityAudit') { const result=await securityAudit(args[0]); emit('onSecurityAuditResult',JSON.stringify(result)); return true; }
  if (action === 'runMonitorCheck') { const result=await monitorCheck(args[0]); emit('onMonitorResult',JSON.stringify(result)); return true; }
  if (action === 'runLoadTest') { const result=await loadTest(args[0]); emit('onLoadTestResult',JSON.stringify(result)); return true; }
  if (action === 'sendUploadRequest') {
    const d=JSON.parse(args[0]);const form=new FormData();const field=d.fileField||'file';
    for(const file of d.files||[]){const bytes=fs.readFileSync(file.path);form.append(file.field||field,new Blob([bytes]),file.name||path.basename(file.path));}
    for(const item of d.fields||[])if(item.enabled!==false&&item.key)form.append(item.key,item.value||'');
    const headers={};for(const h of d.headers||[])if(h.enabled!==false&&h.key)headers[h.key]=h.value||'';
    if(d.authType==='bearer'&&d.bearerToken)headers.Authorization='Bearer '+d.bearerToken;
    const started=performance.now();const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),Math.min(600000,Math.max(5000,(d.timeout||120)*1000)));
    try {const response=await fetch(d.url,{method:d.method||'POST',headers,body:form,signal:controller.signal});const bytes=Buffer.from(await response.arrayBuffer()).subarray(0,MAX_RESPONSE);emit('onUploadResult',JSON.stringify({ok:response.ok,status:response.status,duration:Math.round(performance.now()-started),size:bytes.length,body:bytes.toString('utf8')}));}
    catch(e){emit('onUploadResult',JSON.stringify({ok:false,error:e.name==='AbortError'?'Upload timed out':e.message}));}
    finally{clearTimeout(timer);}return true;
  }
  if (action === 'restoreSqliteBackup') throw new Error('SQLite restore is not supported yet.');
  return null;
});

async function sendRequest(raw) {
  let id=''; let result;
  try {
    const d=typeof raw==='string'?JSON.parse(raw):raw; id=d.id || '';
    const url=new URL(d.url); if(!['http:','https:'].includes(url.protocol)) throw new Error('Only HTTP and HTTPS are supported.');
    const headers={}; for(const h of d.headers||[]) if(h && h.enabled!==false && h.key) headers[h.key]=h.value ?? '';
    const hasCookie=Object.keys(headers).some(k=>k.toLowerCase()==='cookie');
    if(!hasCookie){const host=url.hostname.toLowerCase(),pathName=url.pathname||'/';const jar=db.prepare('SELECT * FROM cookies').all().filter(c=>(host===c.domain.replace(/^\./,'').toLowerCase()||host.endsWith('.'+c.domain.replace(/^\./,'').toLowerCase()))&&pathName.startsWith(c.path||'/')&&(!c.secure||url.protocol==='https:')&&(!c.expires||Date.parse(c.expires)>Date.now()));if(jar.length)headers.Cookie=jar.map(c=>c.name+'='+c.value).join('; ');}
    const method=String(d.method||'GET').toUpperCase();
    let body;
    if(!['GET','HEAD'].includes(method)) {
      if(d.bodyType==='form') body=new URLSearchParams((d.form||[]).filter(x=>x.enabled!==false && x.key).map(x=>[x.key,x.value||'']));
      else if(d.bodyType!=='none') body=d.body || undefined;
    }
    const start=performance.now();
    const controller=new AbortController(); const timer=setTimeout(()=>controller.abort(),Math.min(300000,Math.max(5000,(d.timeout||60)*1000)));
    let response;
    try { response=await fetch(url,{method,headers,body,signal:controller.signal,redirect:d.corsPreflight?'manual':'follow'}); }
    finally { clearTimeout(timer); }
    for(const rawCookie of (response.headers.getSetCookie?.()||[])){
      const parts=rawCookie.split(';').map(x=>x.trim());const pair=parts.shift()||'';const eq=pair.indexOf('=');
      if(eq>0){const name=pair.slice(0,eq),value=pair.slice(eq+1);const domain=(parts.find(x=>/^domain=/i.test(x))||'').split('=')[1]||url.hostname;const cookiePath=(parts.find(x=>/^path=/i.test(x))||'Path=/').split('=')[1]||'/';const expires=(parts.find(x=>/^expires=/i.test(x))||'').slice(8)||null;const maxAge=(parts.find(x=>/^max-age=/i.test(x))||'').slice(8);if(maxAge==='0')db.prepare('DELETE FROM cookies WHERE name=? AND domain=? AND path=?').run(name,domain,cookiePath);else db.prepare('INSERT OR REPLACE INTO cookies(name,value,domain,path,expires,secure,httpOnly) VALUES(?,?,?,?,?,?,?)').run(name,value,domain,cookiePath,expires,/;\s*secure(?:;|$)/i.test(';'+rawCookie)?1:0,/httponly/i.test(rawCookie)?1:0);}
    }
    const buf=Buffer.from(await response.arrayBuffer()); const truncated=buf.length>MAX_RESPONSE;
    const bytes=buf.subarray(0,MAX_RESPONSE); const type=response.headers.get('content-type')||'';
    const textual=/json|text\/|xml|javascript|x-www-form-urlencoded/i.test(type)||!type;
    result={id,ok:response.ok,status:response.status,statusText:response.statusText,duration:Math.round(performance.now()-start),size:bytes.length,headers:Object.fromEntries(response.headers.entries()),body:textual?bytes.toString('utf8'):'[Binary response: '+bytes.length+' bytes]',contentType:type,truncated,finalUrl:response.url};
    if(truncated) result.body+='\n\n[Response truncated at 5 MB]';
  } catch(e) { result={id,error:e.name==='AbortError'?'Request timed out':e.message}; }
  emit('onResult',JSON.stringify(result)); return result;
}
function connectWs(raw) {
  const d=JSON.parse(raw), id=String(d.id||''); if(!id) throw new Error('Connection id is required');
  const headers={}; for(const h of d.headers||[]) if(h && h.enabled!==false && h.key) headers[h.key]=h.value||'';
  const ws=new WebSocket(d.url,{headers,perMessageDeflate:false,handshakeTimeout:30000});
  sockets.set(id,ws);
  const event=(name,extra={})=>emit('onWebSocketEvent',JSON.stringify({id,event:name,...extra}));
  event('connecting',{url:d.url});
  ws.on('open',()=>event('open'));
  ws.on('message',(data,isBinary)=>event('message',{data:isBinary?'[binary message]':data.toString(),binary:isBinary}));
  ws.on('error',e=>event('error',{error:e.message}));
  ws.on('close',(code,reason)=>{event('closed',{code,reason:reason.toString()});sockets.delete(id);});
  return true;
}
async function securityAudit(raw) {
  try {
    const d=JSON.parse(raw),url=new URL(d.url),origin=new URL(d.origin||'https://apix.invalid'),method=String(d.method||'PUT').toUpperCase();
    if(!['http:','https:'].includes(url.protocol)||!['http:','https:'].includes(origin.protocol))throw new Error('URL یا Origin معتبر نیست');
    if(!['POST','PUT','PATCH','DELETE'].includes(method))throw new Error('متد آزمایشی مجاز نیست');
    const tokenA=String(d.tokenA||'').trim(),tokenB=String(d.tokenB||'').trim(),probeParam=String(d.probeParam||'').trim(),probeValue=String(d.probeValue||'apix-test');
    if(tokenA.length!==0!== (tokenB.length!==0))throw new Error('برای مقایسه دسترسی هر دو توکن را وارد کن');
    if(probeParam.length>80||/[\r\n]/.test(probeParam)||probeValue.length>300)throw new Error('پارامتر آزمایشی معتبر نیست');
    const checks=[];const check=(name,ok,detail,severity='REVIEW',confidence=severity==='HIGH'?'متوسط':severity==='MEDIUM'?'پایین':'اطلاعاتی',remediation='پیکربندی و رفتار endpoint را با مستندات امنیتی سرویس تطبیق بده.',evidence=null,classification=ok?'بدون نشانه':'نیازمند بررسی')=>checks.push({name,ok,detail,severity,confidence,remediation,evidence,classification});
    const get=async(target,options={})=>{const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),15000);try{return await fetch(target,{...options,signal:controller.signal,redirect:'manual'});}finally{clearTimeout(timer);}};
    const first=await get(url);const h=Object.fromEntries(first.headers.entries());const finalUrl=first.url||url.href;const https=new URL(finalUrl).protocol==='https:';
    check('HTTPS',https,https?'ارتباط نهایی با HTTPS برقرار شد':'آدرس نهایی HTTPS نیست','HIGH');
    check('Strict-Transport-Security',!https||!!h['strict-transport-security'],h['strict-transport-security']||'هدر HSTS وجود ندارد');
    check('X-Content-Type-Options',h['x-content-type-options']?.toLowerCase()==='nosniff',h['x-content-type-options']||'هدر وجود ندارد');
    check('Clickjacking protection',!!(h['x-frame-options']||h['content-security-policy']?.toLowerCase().includes('frame-ancestors')),h['x-frame-options']||h['content-security-policy']||'سیاست frame-ancestors یا X-Frame-Options دیده نشد');
    check('Content-Security-Policy',!!h['content-security-policy'],h['content-security-policy']||'هدر CSP وجود ندارد');
    check('Referrer-Policy',!!h['referrer-policy'],h['referrer-policy']||'هدر وجود ندارد');
    check('Permissions-Policy',!!h['permissions-policy'],h['permissions-policy']||'هدر وجود ندارد');
    check('Server disclosure',!h.server,h.server||'هدر Server افشا نشده');
    check('Technology disclosure',!h['x-powered-by'],h['x-powered-by']||'هدر X-Powered-By افشا نشده');
    const cookies=first.headers.getSetCookie?.()||[];
    check('Cookie Secure flag',!cookies.length||cookies.every(x=>/;\s*secure(?:;|$)/i.test(';'+x)),!cookies.length?'کوکی تنظیم نشد':cookies.every(x=>/;\s*secure(?:;|$)/i.test(';'+x))?'تمام کوکی‌های پاسخ Secure هستند':'حداقل یک کوکی فاقد Secure است','MEDIUM');
    check('Cookie HttpOnly flag',!cookies.length||cookies.every(x=>/httponly/i.test(x)),!cookies.length?'کوکی تنظیم نشد':cookies.every(x=>/httponly/i.test(x))?'تمام کوکی‌های پاسخ HttpOnly هستند':'حداقل یک کوکی فاقد HttpOnly است','MEDIUM');
    const pre=await get(url,{method:'OPTIONS',headers:{Origin:origin.href,'Access-Control-Request-Method':method,'Access-Control-Request-Headers':'authorization,content-type'}});
    const ph=Object.fromEntries(pre.headers.entries()),allowOrigin=ph['access-control-allow-origin']||'',allowMethods=ph['access-control-allow-methods']||'',allowHeaders=ph['access-control-allow-headers']||'';
    check('CORS origin policy',allowOrigin==='*'||allowOrigin===origin.href,allowOrigin||'Access-Control-Allow-Origin در پاسخ OPTIONS وجود ندارد','MEDIUM');
    check('CORS requested method',allowMethods.split(',').some(x=>x.trim().toUpperCase()===method||x.trim()==='*'),allowMethods||'Access-Control-Allow-Methods ارسال نشده','MEDIUM');
    check('CORS requested headers',allowHeaders==='*'||(allowHeaders.toLowerCase().includes('authorization')&&allowHeaders.toLowerCase().includes('content-type')),allowHeaders||'Access-Control-Allow-Headers ارسال نشده','MEDIUM');
    const credentials=ph['access-control-allow-credentials']==='true';
    check('CORS wildcard with credentials',!(allowOrigin==='*'&&credentials),allowOrigin==='*'&&credentials?'استفاده از wildcard همراه credentials معتبر نیست':'ترکیب wildcard و credentials مشاهده نشد','HIGH');
    const allowed=(ph.allow||allowMethods).split(',').map(x=>x.trim().toUpperCase()).filter(Boolean);
    check('Dangerous HTTP methods',!allowed.includes('TRACE')&&!allowed.includes('TRACK'),allowed.length?'متدهای اعلام‌شده: '+allowed.join(', '):'فهرست متدها از پاسخ OPTIONS قابل استخراج نبود','HIGH');
    check('OPTIONS response',(pre.status>=200&&pre.status<300)||pre.status===204,'کد پاسخ OPTIONS: '+pre.status,'LOW');
    if(tokenA&&tokenB){
      const tokenFetch=async token=>{const r=await get(url,{headers:token?{Authorization:'Bearer '+token}:{}});return {status:r.status,body:(await r.text()).slice(0,262144)};};
      const anonymous=await tokenFetch(''),A=await tokenFetch(tokenA),B=await tokenFetch(tokenB),same=A.status>=200&&A.status<300&&B.status>=200&&B.status<300&&A.body===B.body;
      check('Unauthenticated resource access',!(anonymous.status>=200&&anonymous.status<300&&A.status>=200&&A.status<300&&anonymous.body===A.body),`پاسخ بدون توکن: ${anonymous.status}، پاسخ کاربر A: ${A.status}`,'HIGH');
      check('Cross-account access (BOLA/IDOR signal)',B.status===401||B.status===403||!same,B.status===401||B.status===403?`کاربر B با پاسخ ${B.status} رد شد؛ نشانه مثبت کنترل دسترسی`:same?'هر دو حساب پاسخ موفق و بدنه کاملاً یکسان گرفتند؛ احتمال دسترسی بین‌حسابی را دستی بررسی کن':`درخواست A: ${A.status}، درخواست B: ${B.status}`,'HIGH','پایین','در سمت سرور مالکیت هر شیء را برای هر درخواست بررسی کن؛ صرفاً به شناسه ارسالی یا احراز هویت اکتفا نکن.',{anonymousStatus:anonymous.status,accountAStatus:A.status,accountBStatus:B.status,successfulBodiesIdentical:same},same?'نشانه احتمالی؛ تأیید مالکیت لازم است':'با این مقایسه نشانه‌ای مشاهده نشد');
    }else check('Cross-account access (BOLA/IDOR signal)',true,'برای این آزمون دو توکن مجزا وارد نشده است؛ بررسی انجام نشد','INFO');
    if(probeParam){
      const base=new URL(url);const probe=async value=>{const u=new URL(base);u.searchParams.set(probeParam,value);const r=await get(u);return {status:r.status,body:(await r.text()).slice(0,98304)};};
      const baseline=await probe(probeValue),single=await probe(probeValue+"'"),double=await probe(probeValue+'"'),signatures=['sql syntax','sqlite_exception','sqlstate','unterminated quoted','mysql_fetch','postgresql','odbc','ora-'],quoteText=(single.body+' '+double.body).toLowerCase(),signature=signatures.find(x=>quoteText.includes(x)),sqlSignal=!!signature||((single.status>=500&&single.status<600)&&!(baseline.status>=500&&baseline.status<600));
      const sqlEvidence={parameter:probeParam,baselineStatus:baseline.status,singleQuoteStatus:single.status,doubleQuoteStatus:double.status,databaseErrorSignature:signature||'',responseExcerpt:(single.body+'\n'+double.body).slice(0,1200)};
      check('SQL injection (error signal)',!sqlSignal,sqlSignal?'پس از افزودن کوتیشن، نشانه خطای پایگاه داده یا تغییر غیرعادی کد پاسخ دیده شد؛ exploit تأیید نشده است':'با probe کوتیشن، نشانه واضح خطای SQL مشاهده نشد؛ نبود SQLi ثابت نمی‌شود',sqlSignal?'HIGH':'INFO',sqlSignal?'پایین':'اطلاعاتی','از query پارامتری و prepared statement استفاده کن؛ جزئیات خطای پایگاه داده را به پاسخ عمومی ارسال نکن.',sqlEvidence,sqlSignal?'نشانه احتمالی؛ تأیید دستی لازم است':'نشانه‌ای مشاهده نشد');
      const marker='apixxssprobe'+Date.now().toString(36),reflected=await probe(probeValue+'<apix-probe>'+marker+'</apix-probe>'),isReflected=reflected.body.toLowerCase().includes(marker.toLowerCase());
      check('Reflected input (XSS indicator)',!isReflected,isReflected?'marker بی‌خطر ورودی در پاسخ بازتاب شد؛ اجرای کد بررسی نشده و XSS تأیید نشده است':'marker آزمایشی عیناً در پاسخ دیده نشد',isReflected?'MEDIUM':'INFO',isReflected?'پایین':'اطلاعاتی','ورودی را متناسب با context خروجی HTML، attribute، JavaScript یا URL کدگذاری کن؛ برای داده متنی از textContent استفاده کن.',{parameter:probeParam,status:reflected.status,marker,markerReflected:isReflected,responseExcerpt:reflected.body.slice(0,1200)},isReflected?'بازتاب ورودی؛ نیازمند بررسی context و encoding':'نشانه‌ای مشاهده نشد');
    }else{
      check('SQL injection (error signal)',true,'نام پارامتر Query تعیین نشده؛ آزمون اجرا نشد','INFO');
      check('Reflected input (XSS indicator)',true,'نام پارامتر Query تعیین نشده؛ آزمون اجرا نشد','INFO');
    }
    return {status:first.status,finalUrl,origin:origin.href,method,checks,passed:checks.filter(x=>x.ok).length,failed:checks.filter(x=>!x.ok).length};
  } catch(e) { return {error:e.name==='AbortError'?'Request timed out':e.message}; }
}
async function monitorCheck(raw) {
  const d=JSON.parse(raw),started=performance.now(),id=d.id||'',checkedAt=Date.now();
  try{
    const headers={};for(const h of d.headers||[])if(h&&h.enabled!==false&&h.key)headers[h.key]=h.value||'';
    const controller=new AbortController(),timeout=Math.min(30000,Math.max(5000,(Number(d.timeoutSeconds)||15)*1000)),timer=setTimeout(()=>controller.abort(),timeout);
    let response;try{response=await fetch(d.url,{method:d.method||'GET',headers,signal:controller.signal});}finally{clearTimeout(timer);}
    const duration=Math.round(performance.now()-started),expected=Number(d.expectedStatus)||0;
    return {id,checkedAt,healthy:expected>0?response.status===expected:response.status>=200&&response.status<400,status:response.status,duration,finalUrl:response.url};
  }catch(e){return {id,checkedAt,healthy:false,status:null,duration:Math.round(performance.now()-started),error:e.name==='AbortError'?'Request timed out':e.message};}
}
async function loadTest(raw) {
  const d=JSON.parse(raw),total=Math.min(500,Math.max(1,Number(d.total)||30)),concurrency=Math.min(total,20,Math.max(1,Number(d.concurrency)||5));
  const method=String(d.method||'GET').toUpperCase(),headers={};
  for(const h of d.headers||[])if(h&&h.enabled!==false&&h.key)headers[h.key]=h.value||'';if(d.contentType&&!Object.keys(headers).some(k=>k.toLowerCase()==='content-type'))headers['Content-Type']=d.contentType;
  const durations=[],statuses=[],errors=[];let next=0;
  async function worker(){while(next<total){next++;const t=performance.now();try{
    const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),30000);
    try{const response=await fetch(d.url,{method,headers,body:['POST','PUT','PATCH'].includes(method)?(d.body||''):undefined,signal:controller.signal});statuses.push(response.status);await response.arrayBuffer();}
    finally{clearTimeout(timer);}
  }catch(e){errors.push(e.name==='AbortError'?'Request timed out':e.message);}
  durations.push(Math.round(performance.now()-t));}}
  const started=performance.now();await Promise.all(Array.from({length:concurrency},worker));
  const elapsed=Math.max(1,Math.round(performance.now()-started)),sorted=durations.slice().sort((a,b)=>a-b),success=statuses.filter(x=>x>=200&&x<400).length;
  const completed=durations.length,networkErrors=errors.length;
  return {total,completed,success,httpErrors:statuses.length-success,networkErrors,notCompleted:total-completed,timedOut:total-completed>0,avg:sorted.length?Math.round(sorted.reduce((a,b)=>a+b,0)/sorted.length):0,min:sorted[0]||0,max:sorted.at(-1)||0,p95:sorted.length?sorted[Math.floor((sorted.length-1)*.95)]:0,elapsed,rps:completed*1000/elapsed,statuses};
}
function startMockServer(raw) {
  const routes=JSON.parse(raw); if(!Array.isArray(routes)||routes.length<1||routes.length>100) throw new Error('Configure between 1 and 100 mock routes.');
  if(mockServer) mockServer.close();
  mockServer=http.createServer((req,res)=>{let body='';req.on('data',c=>body+=c);req.on('end',()=>{const route=routes.find(r=>String(r.method||'GET').toUpperCase()===req.method && (r.path||'/')===req.url.split('?')[0]);if(!route){res.writeHead(404,{'content-type':'application/json'});return res.end(JSON.stringify({error:'Not found'}));}const headers=route.headers||{'content-type':'application/json'};res.writeHead(Number(route.status)||200,headers);res.end(route.body??'{}');});});
  mockServer.on('error',error=>emit('toast','Mock Server: '+error.message));
  mockServer.listen(8787,'127.0.0.1'); return JSON.stringify({ok:true,url:'http://127.0.0.1:8787'});
}
