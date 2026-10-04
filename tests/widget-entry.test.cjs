const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const C = require('../app/src/main/assets/core.js');

// Execute the bundled UI with a small DOM boundary to exercise native callbacks.
function ui() {
  const listeners = {};
  const element = () => ({innerHTML:'',classList:{add(){},remove(){}},setAttribute(){},removeAttribute(){}});
  const app = element(), toast = element(), calls = [];
  const state = C.defaults();
  const window = {BanxuCore:C,isBanxuPreview:false,scrollTo(){},addEventListener(){},Banxu:{
    getState:()=>JSON.stringify(state),
    requestWidget:json=>{calls.push(JSON.parse(json));return JSON.stringify({ok:true,queued:true});}
  }};
  const document = {querySelector:s=>({'#app':app,'#toast':toast}[s]||null),addEventListener:(name,fn)=>{listeners[name]=fn;}};
  vm.runInNewContext(fs.readFileSync(require.resolve('../app/src/main/assets/app.js'),'utf8'),
    {window,document,Date,Intl,JSON,setInterval(){},setTimeout(){},clearTimeout(){}});
  const click = dataset => listeners.click({target:{closest:()=>({dataset})}});
  return {window,app,toast,calls,click};
}

test('widget header routing opens the correct page and rejects unknown routes',()=>{
  const {window,app}=ui();
  assert.equal(window.onNativeOpenRoute('leaves'),true);
  assert.match(app.innerHTML,/<h1>请假与返校<\/h1>/);
  const previous=app.innerHTML;
  assert.equal(window.onNativeOpenRoute('javascript:alert(1)'),false);
  assert.equal(app.innerHTML,previous);
  assert.equal(window.onNativeOpenRoute('today'),true);
  assert.match(app.innerHTML,/<h1>今日<\/h1>/);
});

test('settings offers both widget kinds and dispatches a pin request without claiming completion',()=>{
  const {click,app,toast,calls}=ui();
  click({action:'navigate',route:'settings'});
  assert.match(app.innerHTML,/data-kind="todo"/);
  assert.match(app.innerHTML,/data-kind="leave"/);
  assert.match(app.innerHTML,/手动添加方法/);
  click({action:'add-widget',kind:'leave'});
  assert.deepEqual(calls,[{kind:'leave'}]);
  assert.equal(toast.innerHTML,'');
});

test('pin callback reports only submission and stale widget item links explain a removed record',()=>{
  const {window,toast}=ui();
  window.onNativeWidgetPinResult('requested');
  assert.match(toast.innerHTML,/提交添加请求/);
  assert.doesNotMatch(toast.innerHTML,/已添加/);
  window.onNativeOpenItem('already-deleted');
  assert.match(toast.innerHTML,/已不存在/);
});
