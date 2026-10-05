(function (root, factory) {
  const core = factory();
  if (typeof module === 'object' && module.exports) module.exports = core;
  else root.BanxuCore = core;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  const closed = item => ['done', 'returned', 'ignored'].includes(item.status);
  const review = item => item.status === 'review';
  const validStatuses = {
    task: ['todo', 'waiting', 'done', 'ignored', 'review'],
    followup: ['todo', 'waiting', 'done', 'ignored', 'review'],
    leave: ['tracking', 'returned', 'ignored', 'approved', 'review']
  };
  const escape = value => String(value == null ? '' : value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  function endOfDay(now) { const d = new Date(now); d.setHours(23,59,59,999); return +d; }
  function sortItems(items) {
    return [...items].sort((a,b) => (a.dueAt || Infinity) - (b.dueAt || Infinity) || b.createdAt - a.createdAt);
  }
  function today(items, now = Date.now()) {
    return sortItems(items.filter(i => i.kind !== 'leave' && !closed(i) && !review(i) && (!i.dueAt || i.dueAt <= endOfDay(now))));
  }
  function pendingLeaves(items) { return sortItems(items.filter(i => i.kind === 'leave' && !closed(i))); }
  function missingTime(items) { return sortItems(items.filter(i => !closed(i) && !(Number(i.dueAt) > 0))); }
  function reminderAt(item, leadMinutes = 15) { return item.dueAt > 0 && !closed(item) && !review(item) ? Math.max(1, item.dueAt - (item.kind === 'leave' ? 0 : leadMinutes * 60000)) : 0; }
  function counts(items, now = Date.now()) {
    return {today:today(items,now).length, leave:pendingLeaves(items).length, review:items.filter(review).length, missingTime:missingTime(items).length,
      overdue:items.filter(i => !closed(i) && !review(i) && i.dueAt > 0 && i.dueAt < now).length};
  }
  function validateItem(data) {
    if (!validStatuses[data.kind]) return '请选择事项类型';
    if (!String(data.title || '').trim()) return '请填写事项名称';
    if (String(data.title).length > 160) return '事项名称请控制在 160 字以内';
    if (!validStatuses[data.kind].includes(data.status)) return '事项状态不适用于当前类型';
    if (data.kind === 'leave' && data.status === 'approved' && !String(data.student || '').trim()) return '请先填写学生姓名';
    if (data.kind === 'leave' && data.status === 'approved' && !(Number(data.dueAt) > 0)) return '批准请假前，请确认预计返校时间';
    for (const field of ['dueAt','remindAt']) if (data[field] != null && (!Number.isFinite(Number(data[field])) || Number(data[field]) < 0)) return '日期格式不正确';
    return '';
  }
  // The browser preview only resolves a single clock expression with a simple relative day.
  // Device notifications use the native parser and preserve their original receivedAt anchor.
  function previewTime(text, receivedAt, kind) {
    const number = value => {
      if (/^\d+$/.test(value)) return Number(value);
      const digits = {'零':0,'〇':0,'一':1,'二':2,'两':2,'三':3,'四':4,'五':5,'六':6,'七':7,'八':8,'九':9};
      if (value === '十') return 10;
      if (value.includes('十')) { const [tens, ones] = value.split('十'); return (tens ? digits[tens] : 1) * 10 + (ones ? digits[ones] : 0); }
      return digits[value];
    };
    const clocks = [...text.matchAll(/(凌晨|早上|上午|中午|下午|晚上|早|晚)?\s*([0-9零〇一二两三四五六七八九十]{1,3})(?:点|时|[:：])(?:(半)|([0-9零〇一二两三四五六七八九十]{1,3})(?:分)?)?/g)];
    const unknown = {dueAt:0,dueText:'',timePrecision:'unknown',timeNote:'尚无明确时间，事项已添加，可随时补充。'};
    if (clocks.length !== 1 || /(?:年|月|日|周|星期|礼拜|大后天|昨天|昨日|前天)/.test(text)) return unknown;
    const clock = clocks[0];
    if (kind === 'leave' && !/^(?:\s|前|左右|预计|大约|会|将|再|能够|可以|到|就){0,8}(返校|回校|回学校)/.test(text.slice(clock.index + clock[0].length))) return unknown;
    const day = [...text.slice(0,clock.index+clock[0].length).matchAll(/今天|明天|后天|今晚|明早|明晚|今早/g)].pop();
    let hour = number(clock[2]), minute = clock[3] ? 30 : clock[4] ? number(clock[4]) : 0;
    const period = clock[1] || (day && /晚/.test(day[0]) ? '晚' : day && /早/.test(day[0]) ? '早' : '');
    if (/下午|晚上|晚/.test(period) && hour < 12) hour += 12;
    if (period === '中午' && hour < 11) hour += 12;
    if (/凌晨|早上|上午|早/.test(period) && hour === 12) hour = 0;
    if (!Number.isInteger(hour) || !Number.isInteger(minute) || hour < 0 || hour > 23 || minute < 0 || minute > 59) return unknown;
    const date = new Date(receivedAt);
    if (day) date.setDate(date.getDate() + (/^明/.test(day[0]) ? 1 : day[0] === '后天' ? 2 : 0));
    date.setHours(hour,minute,0,0);
    return {dueAt:+date,dueText:day ? text.slice(day.index,clock.index+clock[0].length).trim() : clock[0].trim(),timePrecision:day?'exact':'estimated',timeNote:day?'按消息接收日期换算。':'原文未写日期，预览按消息接收当天安排；可直接修改。'};
  }
  function parseLocal(text, sourceTitle = '', receivedAt = Date.now(), leadMinutes = 15) {
    const kind = /请假|返校|续假/.test(text) ? 'leave' : /反馈|回复|回访|联系|晚点|稍后/.test(text) ? 'followup' : 'task';
    const prefix = kind === 'leave' ? '跟踪请假返校' : kind === 'followup' ? '跟进消息' : '处理通知事项';
    const result = {kind,title:prefix + (sourceTitle ? ' · ' + sourceTitle.slice(0,30) : ''),student:'',detail:text.slice(0,3000),status:kind==='leave'?'tracking':'todo',needsReview:false,confidence:0,...previewTime(text,receivedAt,kind)};
    result.remindAt = reminderAt(result,leadMinutes);
    return result;
  }
  function captureStatus(state = {}, preview = false) {
    const permission = state.permissions?.notificationAccess === true;
    const enabled = state.settings?.captureEnabled === true;
    const d = state.captureDiagnostics;
    const available = !!d && typeof d.connected === 'boolean';
    const common = {permissionLabel:preview?'仅示例':permission?'已授权':'未授权',
      serviceLabel:preview?'仅示例':!permission?'等待授权':!available?'暂无诊断信息':d.connected?'已连接':'未连接',
      isOppo:/oppo|realme|oneplus/i.test(String(d?.deviceManufacturer || '')),
      outcome:({saved:'已保存到消息收件箱。',duplicate:'这条消息已接收过，已跳过重复内容。',conversation_filtered:'通知标题不符合「只接收这些会话」的条件。',group_summary:'这是应用的汇总通知，等待单条消息通知。',ongoing:'这是持续显示的状态通知，已跳过。',no_text:'通知中没有可读取的文字，请检查原应用是否隐藏了消息内容。',outgoing_only:'这是你自己发送的消息，已跳过。',malformed:'通知格式无法读取，等待下一条文字通知。',storage_error:'消息保存失败，请检查手机可用空间后重试。'})[d?.lastOutcome] || '还没有接收记录。'};
    if (preview) return {...common,mode:'preview',tone:'neutral',title:'通知接收诊断 · 示例',detail:'预览无法读取手机通知，也不会执行真实重连。请在安卓 App 中查看设备状态。'};
    if (!enabled) return {...common,mode:'disabled',tone:'neutral',title:'接收通知已关闭',detail:'在下方打开「开始接收通知」并保存设置后，才会接收新消息。'};
    if (!permission) return {...common,mode:'permission_required',tone:'warning',title:'等待通知访问授权',detail:'请在系统设置中允许光合待办读取通知。提醒通知权限与通知访问权限分别设置。'};
    if (!available) return {...common,mode:'unavailable',tone:'neutral',title:'暂无服务连接信息',detail:'当前安装版本未提供接收诊断，请更新 App 后查看。已授权不代表服务已连接。'};
    if (!d.connected) return {...common,mode:'disconnected',tone:'warning',title:'接收服务尚未连接',detail:'通知访问已授权，但系统暂未连接接收服务。可以请求重新连接，再检查后台设置。'};
    return {...common,mode:'connected',tone:'good',title:'通知接收服务已连接',detail:'新消息会按已保存的来源设置接收。连接正常时，也可能因通知隐藏文字或会话筛选而未保存。'};
  }
  function notificationRepairMessage(status) {
    return ({connected:'接收服务当前已连接，请用一条新的文字通知检查接收。',requested:'已向系统请求重新连接，连接结果会显示在诊断卡中。',disabled:'请先打开「开始接收通知」并保存设置。',permission_required:'请先在系统设置中开启光合待办的通知访问权限。',throttled:'刚刚已请求连接，请稍后查看服务状态。',unavailable:'暂时无法请求连接，请检查通知访问与后台设置。',preview:'这是界面预览，不能重新连接手机通知服务。'})[status] || '系统未返回连接结果，请查看诊断卡中的服务状态。';
  }
  function defaults() {
    return {settings:{teacherName:'老师',className:'我的班级',model:'deepseek-flash',hasApiKey:false,cloudEnabled:false,captureEnabled:false,allowedPackages:['com.tencent.mm'],conversationFilters:'',leadMinutes:15},
      permissions:{notificationAccess:false,postNotifications:false,exactAlarms:false,batteryOptimized:true},captureDiagnostics:null,items:[],inbox:[],lastError:'',processing:false,apiTest:{status:'idle',message:''},version:'0.3.1'};
  }
  function samples(now = Date.now()) {
    const at = (h,m=0,day=0) => { const d=new Date(now);d.setDate(d.getDate()+day);d.setHours(h,m,0,0);return +d; };
    const make = (id, props) => Object.assign({id:'demo-'+id,kind:'task',title:'',student:'',detail:'',status:'todo',dueAt:0,remindAt:0,createdAt:now-id*60000,updatedAt:now,sourceId:'demo-source-'+id,sourceTitle:'高二班主任工作群',sourceText:'',sourcePackage:'com.tencent.mm',confidence:0.96,needsReview:false,isDemo:true},props);
    return [
      make(1,{title:'提交本周班级情况汇总',detail:'核对出勤与班级活动记录，提交给年级组。',dueAt:at(16,30),sourceText:'各位班主任，请于今天16:30前提交本周班级情况汇总。'}),
      make(2,{title:'收齐研学活动家长回执',detail:'检查学生姓名与家长签字，整理后交至年级办公室。',dueAt:at(18),sourceText:'今天18点前请各班收齐研学活动家长回执，并检查签字。'}),
      make(3,{kind:'followup',title:'等陈一然家长补充回执',student:'陈一然',status:'waiting',dueAt:at(20),sourceTitle:'陈一然妈妈',detail:'家长答应晚饭后发来，届时核对签字。',sourceText:'老师，回执我今天晚上8点前发给您。'}),
      make(4,{kind:'leave',title:'林沐阳 · 请假返校',student:'林沐阳',status:'tracking',dueAt:at(14,30),sourceTitle:'林沐阳妈妈',detail:'上午处理家中事务，下午返校。请到点核实本人已到班。',sourceText:'老师，沐阳今天上午请假半天，下午2点半回校。'}),
      make(5,{kind:'leave',title:'许知夏 · 请假返校',student:'许知夏',status:'tracking',dueAt:at(18,30),sourceTitle:'许知夏爸爸',detail:'晚自习前返校，核对到班情况。',sourceText:'许知夏今天请假，晚上6点半前返校。'}),
      make(6,{title:'准备下周班会材料',status:'todo',dueAt:0,needsReview:false,timePrecision:'unknown',timeNote:'通知未给出提交时间，可稍后补充。',sourceTitle:'年级工作群',detail:'已记入待办，提交时间待后续通知。',sourceText:'各班提前准备下周班会的材料，具体上交时间稍后通知。'}),
      make(7,{title:'核对下周值日安排',status:'done',dueAt:at(9),sourceText:'请各班主任核对下周值日安排。'}),
      make(8,{title:'准备下周班会提纲',dueAt:at(17,0,3),detail:'整理讨论主题和班级活动安排。',sourceText:'下周班会提纲请在三天后17:00前准备好。'})
    ];
  }
  return {closed,review,escape,endOfDay,sortItems,today,pendingLeaves,missingTime,reminderAt,counts,validateItem,parseLocal,defaults,samples,validStatuses,captureStatus,notificationRepairMessage};
});
