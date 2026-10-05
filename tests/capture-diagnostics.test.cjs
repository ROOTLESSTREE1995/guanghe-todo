const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const C = require('../app/src/main/assets/core.js');

function capturedState() {
  const state=C.defaults();
  state.settings.captureEnabled=true;
  state.permissions.notificationAccess=true;
  state.captureDiagnostics={connected:false,lastOutcome:'',deviceManufacturer:'OPPO',deviceModel:'Find X8'};
  return state;
}

test('notification authorization does not imply the service is connected',()=>{
  const state=capturedState(),status=C.captureStatus(state);
  assert.equal(status.mode,'disconnected');
  assert.equal(status.permissionLabel,'已授权');
  assert.equal(status.serviceLabel,'未连接');
  state.captureDiagnostics.connected=true;
  assert.equal(C.captureStatus(state).mode,'connected');
  state.permissions.notificationAccess=false;
  state.permissions.postNotifications=true;
  assert.equal(C.captureStatus(state).mode,'permission_required');
  assert.equal(C.captureStatus(state).serviceLabel,'等待授权');
});

test('disabled intake and older native versions never claim readiness',()=>{
  const state=capturedState();
  state.captureDiagnostics.connected=true;
  state.settings.captureEnabled=false;
  assert.equal(C.captureStatus(state).mode,'disabled');
  state.settings.captureEnabled=true;
  delete state.captureDiagnostics;
  assert.equal(C.captureStatus(state).mode,'unavailable');
  assert.equal(C.captureStatus(state).serviceLabel,'暂无诊断信息');
  assert.equal(C.captureStatus({}).mode,'disabled');
});

test('diagnostic reasons separate source filtering, redacted text and storage failures',()=>{
  const state=capturedState();
  state.captureDiagnostics.lastOutcome='conversation_filtered';
  assert.match(C.captureStatus(state).outcome,/通知标题/);
  state.captureDiagnostics.lastOutcome='no_text';
  assert.match(C.captureStatus(state).outcome,/没有可读取的文字/);
  state.captureDiagnostics.lastOutcome='storage_error';
  assert.match(C.captureStatus(state).outcome,/保存失败/);
  state.captureDiagnostics.lastOutcome='<private message text>';
  assert.doesNotMatch(C.captureStatus(state).outcome,/private/);
  for(const manufacturer of ['OPPO','realme','OnePlus']) {
    state.captureDiagnostics.deviceManufacturer=manufacturer;
    assert.equal(C.captureStatus(state).isOppo,true);
  }
  state.captureDiagnostics.deviceManufacturer='Xiaomi';
  assert.equal(C.captureStatus(state).isOppo,false);
});

test('reconnect responses describe submission, configuration and cooldown accurately',()=>{
  assert.match(C.notificationRepairMessage('requested'),/请求重新连接/);
  assert.doesNotMatch(C.notificationRepairMessage('requested'),/已修复|已连接/);
  assert.match(C.notificationRepairMessage('connected'),/当前已连接/);
  assert.match(C.notificationRepairMessage('disabled'),/保存设置/);
  assert.match(C.notificationRepairMessage('permission_required'),/通知访问权限/);
  assert.match(C.notificationRepairMessage('throttled'),/稍后/);
  assert.match(C.notificationRepairMessage('unavailable'),/暂时无法/);
  assert.match(C.notificationRepairMessage('unexpected'),/未返回连接结果/);
});

test('preview clears cached device diagnostics and refuses to represent a real reconnect',()=>{
  const state=capturedState();state.captureDiagnostics.connected=true;
  const memory=new Map([['banxu-preview-v1',JSON.stringify(state)]]);
  const window={BanxuCore:C};
  vm.runInNewContext(fs.readFileSync(require.resolve('../app/src/main/assets/bridge.js'),'utf8'),
    {window,localStorage:{getItem:k=>memory.get(k),setItem:(k,v)=>memory.set(k,v)},crypto:{},Date,Math,JSON});
  const after=JSON.parse(window.Banxu.getState());
  assert.equal(after.captureDiagnostics,null);
  assert.equal(C.captureStatus(after,true).mode,'preview');
  assert.equal(C.captureStatus(after,true).serviceLabel,'仅示例');
  assert.equal(JSON.parse(window.Banxu.repairNotificationListener()).status,'preview');
  assert.match(C.notificationRepairMessage('preview'),/不能重新连接/);
});

// A DOM boundary checks that async diagnostic updates do not replace a draft form.
function diagnosticUi(state=capturedState()) {
  const listeners={};let repairs=0,renderCount=0;
  const node=()=>({innerHTML:'',classList:{add(){},remove(){}},setAttribute(){},removeAttribute(){}});
  const app=node(),toast=node(),card=node(),form={draft:'尚未保存的班级名称'};
  Object.defineProperty(app,'innerHTML',{get:()=>app.html||'',set:value=>{app.html=value;renderCount++;}});
  const window={BanxuCore:C,isBanxuPreview:false,scrollTo(){},addEventListener(){},Banxu:{
    getState:()=>JSON.stringify(state),
    repairNotificationListener:()=>{repairs++;return JSON.stringify({ok:true,status:'requested'});}
  }};
  const document={activeElement:null,querySelector:s=>({'#app':app,'#toast':toast,'#capture-diagnostics':card,'#settings-form':form}[s]||null),addEventListener:(name,fn)=>{listeners[name]=fn;}};
  vm.runInNewContext(fs.readFileSync(require.resolve('../app/src/main/assets/app.js'),'utf8'),
    {window,document,Date,Intl,JSON,setInterval(){},setTimeout(){},clearTimeout(){}});
  const click=dataset=>listeners.click({target:{closest:()=>({dataset})}});
  click({action:'navigate',route:'settings'});
  return {window,app,toast,card,form,listeners,click,get repairs(){return repairs;},get renderCount(){return renderCount;}};
}

test('diagnostic settings expose OPPO system entry points without claiming a clipboard listener',()=>{
  const {app}=diagnosticUi();
  assert.match(app.innerHTML,/通知接收诊断/);
  assert.match(app.innerHTML,/OPPO \/ realme \/ 一加/);
  assert.match(app.innerHTML,/data-page="appDetails"/);
  assert.match(app.innerHTML,/data-page="battery"/);
  assert.match(app.innerHTML,/不会监听剪贴板/);
  assert.match(app.innerHTML,/切回手机桌面/);
});

test('receiving status updates and requesting reconnect preserve unsaved form changes',()=>{
  const state=capturedState(),ui=diagnosticUi(state),before=ui.renderCount;
  ui.listeners.input({target:{closest:()=>ui.form}});
  state.captureDiagnostics.connected=true;
  state.captureDiagnostics.lastEventAt=Date.now();
  ui.window.onNativeUpdate();
  assert.equal(ui.renderCount,before);
  assert.equal(ui.form.draft,'尚未保存的班级名称');
  assert.match(ui.card.innerHTML,/通知接收服务已连接/);
  assert.match(ui.card.innerHTML,/当前修改尚未保存/);
  ui.click({action:'repair-capture'});
  assert.equal(ui.repairs,1);
  assert.equal(ui.renderCount,before);
  assert.match(ui.toast.innerHTML,/已向系统请求/);
  assert.doesNotMatch(ui.toast.innerHTML,/已修复/);
});
