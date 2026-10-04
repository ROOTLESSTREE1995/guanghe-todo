const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const C = require('../app/src/main/assets/core.js');
const now = new Date(2026,9,2,10).getTime();
const item = props => ({id:'1',kind:'task',title:'待办',status:'todo',dueAt:0,createdAt:now,...props});

test('today includes overdue and undated items, excluding future/review/closed/leaves', () => {
  const rows = [item({id:'overdue',dueAt:now-86400000}),item({id:'today',dueAt:now+1000}),item({id:'undated'}),item({id:'future',dueAt:now+86400000}),item({id:'review',status:'review'}),item({id:'closed',status:'done'}),item({id:'leave',kind:'leave',status:'approved'})];
  assert.deepEqual(C.today(rows,now).map(i=>i.id),['overdue','today','undated']);
});
test('pending leaves include automatic tracking and legacy states but exclude closed records',()=>{
  const rows=[item({id:'tracking',kind:'leave',status:'tracking'}),item({id:'review',kind:'leave',status:'review'}),item({id:'approved',kind:'leave',status:'approved'}),item({kind:'leave',status:'returned'}),item({kind:'leave',status:'ignored'})];
  assert.deepEqual(C.pendingLeaves(rows).map(i=>i.id),['tracking','review','approved']);
});
test('tracking accepts unknown student and time without a confirmation gate',()=>{
  assert.equal(C.validateItem(item({kind:'leave',status:'tracking',student:'',dueAt:0})), '');
  assert.equal(C.validateItem(item({kind:'task',status:'todo',dueAt:0})), '');
});
test('leave approval requires student and a confirmed date',()=>{
  assert.ok(C.validateItem(item({kind:'leave',status:'approved'})));
  assert.ok(C.validateItem(item({kind:'leave',status:'approved',student:'测试学生'})));
  assert.equal(C.validateItem(item({kind:'leave',status:'approved',student:'测试学生',dueAt:now+60000})), '');
});
test('invalid status/type combinations and nonfinite timestamps rejected',()=>{
  assert.ok(C.validateItem(item({status:'returned'})));
  assert.ok(C.validateItem(item({dueAt:NaN})));
  assert.ok(C.validateItem(item({dueAt:-1})));
});
test('local classification does not infer student identity or ambiguous deadline',()=>{
  const result=C.parseLocal('老师，孩子明天下午请假，晚点返校。','某某妈妈');
  assert.equal(result.kind,'leave');assert.equal(result.status,'tracking');assert.equal(result.student,'');assert.equal(result.dueAt,0);assert.equal(result.remindAt,0);assert.equal(result.needsReview,false);assert.equal(result.timePrecision,'unknown');
});
test('untrusted notification markup is escaped',()=>{
  assert.equal(C.escape('<img src=x onerror="alert(1)">&'), '&lt;img src=x onerror=&quot;alert(1)&quot;&gt;&amp;');
});
test('all example rows are flagged and have unique identities',()=>{
  const rows=C.samples(now);assert.ok(rows.every(i=>i.isDemo));assert.equal(new Set(rows.map(i=>i.id)).size,rows.length);
});
function preview(initial) {
  const memory=new Map();if(initial)memory.set('banxu-preview-v1',JSON.stringify(initial));
  const context={window:{BanxuCore:C},localStorage:{getItem:k=>memory.get(k)||null,setItem:(k,v)=>memory.set(k,v)},crypto:{randomUUID:()=>Math.random().toString(16)},Date,Math,JSON};
  vm.runInNewContext(fs.readFileSync(require.resolve('../app/src/main/assets/bridge.js'),'utf8'),context);
  return {api:context.window.Banxu,memory};
}
test('clearing examples preserves non-demo records',()=>{
  const state=C.defaults();state.items=[item({id:'real',isDemo:false}),item({id:'demo',isDemo:true})];
  const {api}=preview(state);api.clearDemo();assert.deepEqual(JSON.parse(api.getState()).items.map(i=>i.id),['real']);
});
test('preview refuses and never persists API keys',()=>{
  const {api,memory}=preview();const r=JSON.parse(api.saveSettings(JSON.stringify({apiKey:'test-do-not-persist'})));
  assert.equal(r.ok,false);assert.ok(![...memory.values()].join('').includes('test-do-not-persist'));
});
test('manual preview intake retains source and automatically tracks leave',()=>{
  const {api}=preview(C.defaults());api.ingestManual(JSON.stringify({title:'家长群',text:'学生明天下午返校，请帮忙登记。'}));
  const state=JSON.parse(api.getState());assert.equal(state.items[0].status,'tracking');assert.equal(state.items[0].needsReview,false);assert.equal(state.items[0].sourceId,state.inbox[0].id);assert.equal(state.items[0].sourceText,state.inbox[0].text);assert.equal(state.items[0].dueAt,0);
});
test('deleting one source removes only its linked items',()=>{
  const initial=C.defaults();initial.inbox=[{id:'a'},{id:'b'}];
  initial.items=[item({id:'a1',sourceId:'a'}),item({id:'a2',sourceId:'a'}),item({id:'b1',sourceId:'b'}),item({id:'manual',sourceId:''})];
  const {api}=preview(initial);api.deleteInbox(JSON.stringify({id:'a'}));const s=JSON.parse(api.getState());
  assert.deepEqual(s.inbox.map(i=>i.id),['b']);assert.deepEqual(s.items.map(i=>i.id),['b1','manual']);
});

test('missing-time count matches its list including leave and excluding closed records',()=>{
  const rows=[item({id:'task'}),item({id:'leave',kind:'leave',status:'tracking'}),item({id:'legacy',status:'review'}),item({id:'dated',dueAt:now}),item({id:'done',status:'done'})];
  assert.deepEqual(C.missingTime(rows).map(i=>i.id),['task','leave','legacy']);
  assert.equal(C.counts(rows,now).missingTime,3);
});
test('preview resolves 明早9点 using the original message receipt date',()=>{
  const receivedAt=new Date(2026,11,31,23,58).getTime();
  const result=C.parseLocal('请明早9点提交统计表','班主任群',receivedAt,15);
  assert.equal(result.kind,'task');assert.equal(result.status,'todo');assert.equal(result.needsReview,false);
  assert.equal(result.dueAt,new Date(2027,0,1,9).getTime());assert.equal(result.remindAt,result.dueAt-15*60000);
  assert.equal(result.dueText,'明早9点');assert.equal(result.timePrecision,'exact');
});
test('preview resolves 明天下午两点半 and rejects invalid clock values',()=>{
  const result=C.parseLocal('学生明天下午两点半返校','家长',now);
  assert.equal(result.status,'tracking');assert.equal(result.dueAt,new Date(2026,9,3,14,30).getTime());assert.equal(result.remindAt,result.dueAt);
  assert.equal(C.parseLocal('请明天25点提交','',now).dueAt,0);
  assert.equal(C.parseLocal('请明天9:75提交','',now).dueAt,0);
});
test('preview automatically adds a task and computes reminders across complete and reopen',()=>{
  const {api}=preview(C.defaults());
  api.ingestManual(JSON.stringify({title:'班主任群',text:'请明早9点提交汇总'}));
  let row=JSON.parse(api.getState()).items[0];assert.equal(row.status,'todo');assert.equal(row.needsReview,false);assert.equal(row.remindAt,row.dueAt-15*60000);
  api.saveItem(JSON.stringify({id:row.id,status:'done'}));row=JSON.parse(api.getState()).items[0];assert.equal(row.remindAt,0);
  api.saveItem(JSON.stringify({id:row.id,status:'todo'}));row=JSON.parse(api.getState()).items[0];assert.equal(row.remindAt,row.dueAt-15*60000);
});
test('manual date editing clears prior inference without affecting other metadata',()=>{
  const initial=C.defaults();initial.items=[item({dueAt:now,timePrecision:'estimated',dueText:'明天',timeNote:'暂按18点',detail:'保留备注'})];
  const {api}=preview(initial);api.saveItem(JSON.stringify({id:'1',dueAt:now+60000}));let row=JSON.parse(api.getState()).items[0];
  assert.equal(row.timePrecision,'manual');assert.equal(row.timeNote,'');assert.equal(row.dueText,'');assert.equal(row.detail,'保留备注');
  api.saveItem(JSON.stringify({id:'1',dueAt:0}));row=JSON.parse(api.getState()).items[0];assert.equal(row.status,'todo');assert.equal(row.timePrecision,'unknown');assert.equal(row.remindAt,0);
});
test('preview saves an undated tracking record and derives a reminder when its date is added',()=>{
  const {api}=preview(C.defaults());assert.equal(JSON.parse(api.saveItem(JSON.stringify({kind:'leave',title:'跟踪返校',student:'',dueAt:0}))).ok,true);
  let row=JSON.parse(api.getState()).items[0];assert.equal(row.status,'tracking');assert.equal(row.remindAt,0);
  api.saveItem(JSON.stringify({id:row.id,dueAt:now+3600000}));row=JSON.parse(api.getState()).items[0];assert.equal(row.remindAt,row.dueAt);
  api.saveItem(JSON.stringify({id:row.id,status:'returned'}));row=JSON.parse(api.getState()).items[0];assert.equal(row.remindAt,0);
});

test('limited preview parser does not reuse a leave start time or override an unsupported date',()=>{
  assert.equal(C.parseLocal('学生明早9点请假，后天返校。','家长',now).dueAt,0);
  assert.equal(C.parseLocal('请11月8日9点提交材料','班主任群',now).dueAt,0);
});


test('preview widget requests report unsupported and preserve task data',()=>{
  const initial=C.defaults();initial.items=[item({id:'keep'})];initial.version='0.2.0';
  const {api}=preview(initial),before=JSON.parse(api.getState());
  const result=JSON.parse(api.requestWidget(JSON.stringify({kind:'todo'})));
  assert.equal(result.ok,false);assert.match(result.error,/预览/);
  assert.deepEqual(JSON.parse(api.getState()).items,before.items);
  assert.equal(before.version,'0.3.0');
});
