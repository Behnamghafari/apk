const { contextBridge, ipcRenderer } = require('electron');
const sync=(action,...args)=>ipcRenderer.sendSync('apix:sync',action,...args);
const invoke=(action,...args)=>ipcRenderer.invoke('apix:action',action,...args);
const api={
  shouldImportLegacyStorage:()=>sync('shouldImportLegacyStorage'),
  getStorageSnapshot:()=>sync('getStorageSnapshot')||'[]',
  setStorageValue:(...a)=>sync('setStorageValue',...a),
  getCookies:()=>sync('getCookies')||'[]',
  mockServerStatus:()=>sync('mockServerStatus'),
  getAppVersion:()=>sync('getAppVersion'),
  importStorageRecords:(...a)=>invoke('importStorageRecords',...a),
  replaceStorageSnapshot:(...a)=>invoke('replaceStorageSnapshot',...a),
  sendRequest:(...a)=>invoke('sendRequest',...a),
  wsConnect:(...a)=>invoke('wsConnect',...a),
  wsSend:(...a)=>invoke('wsSend',...a),
  wsClose:(...a)=>invoke('wsClose',...a),
  chooseOpenApiFile:()=>invoke('chooseOpenApiFile'),
  chooseJsonFile:(...a)=>invoke('chooseJsonFile',...a),
  chooseBackupFile:()=>invoke('chooseBackupFile'),
  saveBackupFile:(...a)=>invoke('saveBackupFile',...a),
  createSqliteBackup:()=>invoke('createSqliteBackup'),
  chooseSqliteBackupFile:()=>invoke('chooseSqliteBackupFile'),
  restoreSqliteBackup:()=>invoke('restoreSqliteBackup'),
  chooseUploadFiles:()=>invoke('chooseUploadFiles'),
  sendUploadRequest:(...a)=>invoke('sendUploadRequest',...a),
  copyText:(...a)=>invoke('copyText',...a),
  openPrivacyPolicy:()=>invoke('openPrivacyPolicy'),
  openTelegram:()=>invoke('openTelegram'),
  toast:(...a)=>invoke('toast',...a),
  addCookie:(...a)=>invoke('addCookie',...a),
  deleteCookie:(...a)=>invoke('deleteCookie',...a),
  clearCookies:()=>invoke('clearCookies'),
  startMockServer:(...a)=>invoke('startMockServer',...a),
  stopMockServer:()=>invoke('stopMockServer'),
  runSecurityAudit:(...a)=>invoke('runSecurityAudit',...a),
  runMonitorCheck:(...a)=>invoke('runMonitorCheck',...a),
  runLoadTest:(...a)=>invoke('runLoadTest',...a)
};
contextBridge.exposeInMainWorld('BehnamNative',api);

