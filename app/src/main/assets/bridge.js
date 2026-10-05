(function () {
  'use strict';
  const C = window.BanxuCore;
  window.isBanxuPreview = !window.Banxu;
  if (window.Banxu) return;
  const storageKey='banxu-preview-v1';
  let state;
  try {state=JSON.parse(localStorage.getItem(storageKey));} catch (_) {}
  if (!state || !state.settings || !Array.isArray(state.items)) {
    state=C.defaults(); state.items=C.samples();
  }
  state.version=C.defaults().version;
  // A browser preview must never present cached device diagnostics as live status.
  state.captureDiagnostics=null;
  const persist=() => {try {localStorage.setItem(storageKey,JSON.stringify(state));}catch(_) {}};
  const response=(fn) => {try {const result=fn() || {};persist();return JSON.stringify({ok:true,...result});}catch(e){return JSON.stringify({ok:false,error:e.message || '操作未完成'});}};
  const uuid=() => typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : 'p-'+Date.now()+'-'+Math.random().toString(16).slice(2);
  window.Banxu={
    getState:()=>JSON.stringify(state),
    saveSettings:(raw)=>response(()=>{const p=JSON.parse(raw);if ('apiKey' in p) {delete p.apiKey;throw Error('预览版不保存密钥，请在安卓 App 中配置');}if(p.cloudEnabled || p.captureEnabled) throw Error('请在安卓 App 中启用通知读取和云端识别');Object.assign(state.settings,p);}),
    ingestManual:(raw)=>response(()=>{const p=JSON.parse(raw);if(!p.text?.trim())throw Error('请输入消息内容');const id=uuid(),now=Date.now();state.inbox.unshift({id,packageName:'manual',title:p.title||'手动粘贴',text:p.text,receivedAt:now,status:'processed',error:'',isDemo:true});state.items.unshift({...C.parseLocal(p.text,p.title,now,state.settings.leadMinutes),id:uuid(),createdAt:now,updatedAt:now,sourceId:id,sourceTitle:p.title||'手动粘贴',sourceText:p.text,sourcePackage:'manual',isDemo:true});}),
    saveItem:(raw)=>response(()=>{const p=JSON.parse(raw);const old=state.items.find(i=>i.id===p.id);const data=Object.assign({id:uuid(),kind:'task',status:p.kind==='leave'?'tracking':'todo',student:'',detail:'',dueAt:0,remindAt:0,createdAt:Date.now(),sourceTitle:'手动记录',sourceText:'',isDemo:true},old||{},p,{updatedAt:Date.now()});const error=C.validateItem(data);if(error)throw Error(error);if('dueAt' in p&&(!old||Number(p.dueAt)!==Number(old.dueAt))){data.dueText='';data.timeNote='';data.timePrecision=data.dueAt>0?'manual':'unknown';}data.needsReview=data.status==='review';data.remindAt=C.reminderAt(data,state.settings.leadMinutes);if(old)Object.assign(old,data);else state.items.unshift(data);}),
    deleteItem:(raw)=>response(()=>{const {id}=JSON.parse(raw);state.items=state.items.filter(i=>i.id!==id);}),
    deleteInbox:(raw)=>response(()=>{const {id}=JSON.parse(raw);state.inbox=state.inbox.filter(i=>i.id!==id);state.items=state.items.filter(i=>i.sourceId!==id);}),
    retryInbox:(raw)=>response(()=>{const row=state.inbox.find(i=>i.id===JSON.parse(raw).id);if(!row)throw Error('消息已不存在');if(!state.items.some(i=>i.sourceId===row.id))state.items.unshift({...C.parseLocal(row.text,row.title,row.receivedAt,state.settings.leadMinutes),id:uuid(),createdAt:Date.now(),updatedAt:Date.now(),sourceId:row.id,sourceText:row.text,sourceTitle:row.title,sourcePackage:row.packageName,isDemo:true});row.status='processed';row.error='';}),
    dismissInbox:(raw)=>response(()=>{const row=state.inbox.find(i=>i.id===JSON.parse(raw).id);if(row)row.status='ignored';}),
    seedDemo:()=>response(()=>{if(state.items.some(i=>i.id.startsWith('demo-')))return;state.items.push(...C.samples());}),
    clearDemo:()=>response(()=>{state.items=state.items.filter(i=>!i.isDemo);state.inbox=state.inbox.filter(i=>!i.isDemo);}),
    processInbox:()=>response(()=>{}),
    testApi:()=>JSON.stringify({ok:false,error:'连接测试需在安卓 App 中使用你自己的 Key'}),
    requestWidget:()=>JSON.stringify({ok:false,error:'这是界面预览，桌面小组件需在安卓 App 中添加'}),
    repairNotificationListener:()=>JSON.stringify({ok:true,status:'preview'}),
    openSystemSettings:()=>JSON.stringify({ok:false,error:'这是界面预览，手机权限需在安卓 App 中设置'}),
    requestNotificationPermission:()=>JSON.stringify({ok:false,error:'这是界面预览，手机权限需在安卓 App 中设置'})
  };
  persist();
})();
