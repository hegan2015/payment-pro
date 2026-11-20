/* =====================================================================
 * payment-pro 管理控制台 · 风控运营页（IA 分组 7）
 * 端点（实探自 RiskOpsController / RiskProfileOpsController，均 /api/risk 前缀）：
 *   规则 GET/POST /rules · POST /rules/{id}/{publish,disable,shadow} · POST /rules/rollback · POST /shadow · POST /reload
 *   引擎 GET /engine · GET /versions · POST /evaluate · GET /alerts
 *   限额 GET/POST /quotas · POST /quotas/{id}/disable · POST /quotas/rollback · GET /quotas/usage
 *   名单 GET/POST /lists · DELETE /lists/{id} · POST /lists/rollback · GET /lists/hit
 *   评分 GET/POST /thresholds · POST /thresholds/rollback · GET /decisions[/{id}] · POST /decisions/{id}/review
 *   画像 GET /profile/status · POST /profile/retention · GET /device · /device/accounts · /account/devices
 *        GET /ip · /ip/sandbox · /behavior · /behavior/analyze
 *   AML  GET /aml/status · GET/POST /aml/rules · POST /aml/rules/{id}/disable · GET /aml/reports[/{id}[/audit]]
 *        POST /aml/reports/{id}/{submit,close} · POST /aml/scan · GET /aml/txns
 *        GET/POST /aml/watchlist · POST /aml/watchlist/{id}/remove · POST /aml/screen · GET /aml/screenings
 * 复用：lpDef/lpSet 分页、#detailDrawer（新增 riskRule/riskDecision/amlReport 三类）、askConfirm+sensitiveApi、yuan/badge。
 * ===================================================================== */

const RK_SCENE = [['PAY','PAY 支付'],['REFUND','REFUND 退款'],['WITHDRAW','WITHDRAW 提现'],['TOPUP','TOPUP 充值'],['TRANSFER','TRANSFER 转账'],['ALL','ALL 全场景']];
const RK_ACTION = [['PASS','PASS 放行'],['CHALLENGE','CHALLENGE 挑战'],['LIMIT','LIMIT 限额'],['REJECT','REJECT 拒绝'],['MANUAL_REVIEW','MANUAL_REVIEW 人审']];
const RK_DIMS = ['USER','MERCHANT','CHANNEL','METHOD','CARD'];
const RK_PERIOD = [['SINGLE','SINGLE 单笔即时'],['DAY','DAY 日'],['MONTH','MONTH 月'],['TOTAL','TOTAL 累计']];
const RK_VTYPE = [['USER','USER 用户'],['MERCHANT','MERCHANT 商户'],['CARD','CARD 卡号'],['DEVICE','DEVICE 设备'],['IP','IP 地址']];
const RK_LTYPE = [['BLACK','BLACK 黑名单'],['WHITE','WHITE 白名单'],['GRAY','GRAY 灰名单']];
const RK_AML_TYPE = [['LARGE_AMOUNT','LARGE_AMOUNT 大额'],['DAY_AMOUNT','DAY_AMOUNT 日累计'],['STRUCTURING','STRUCTURING 拆分'],
  ['FAST_IN_OUT','FAST_IN_OUT 快进快出'],['NIGHT','NIGHT 夜间'],['CROSS_BORDER','CROSS_BORDER 跨境'],['CUSTOM','CUSTOM 自定义 DSL']];
const RK_DSL_HINT = 'QLExpress 布尔表达式。变量：payerId / merchantId / method / cardNo / cardBin / deviceId / ip / scene / channelCode / amount(分) / hour / now。\n'
  + '函数：txnCount(payerId, 窗口ms) · quotaUsed(key) · inList(类型, 值) · deviceRisk(deviceId) · ipRisk(ip) · hourOfDay() · amountDeviation()。\n'
  + '示例：amount > 500000 && txnCount(payerId, 86400000) > 10\n'
  + '护栏：编译失败 / 执行异常 / 超时一律放行并告警（degraded），不会误拦正常交易。';

const PPRisk = {
  rules: [], quotas: [], lists: [], engine: null, amlRules: [],
  init() {
    /* ---- 客户端分页注册（复用既有 lpDef 框架） ---- */
    lpDef('rkRule', { table:'rkRuleTable', timeField:'updatedAt', render:rkRuleRows, filters:[
      { k:'kw', label:'关键字', ph:'规则 ID / 名称 / 表达式', fields:['id','name','conditionExpr'] },
      { k:'scene', label:'场景', type:'select', field:'scene', opts:[opt('','全部')].concat(RK_SCENE) },
      { k:'status', label:'状态', type:'select', field:'status', opts:[opt('','全部'),opt('DRAFT','DRAFT 草稿'),opt('PUBLISHED','PUBLISHED 已发布'),opt('DISABLED','DISABLED 停用')] },
      { k:'shadow', label:'影子', type:'select', opts:[opt('','全部'),opt('true','影子中'),opt('false','正式生效')], test:(r,v)=>String(!!r.shadow)===v } ] });
    lpDef('rkVersion', { table:'rkVersionTable', render:rkVersionRows, filters:[
      { k:'kw', label:'关键字', ph:'版本 / 操作人 / 变更说明', fields:['version','operator','changeNote','source'] },
      { k:'kind', label:'类型', type:'select', field:'kind', opts:[opt('','全部'),opt('RULES','RULES 规则'),opt('QUOTAS','QUOTAS 限额'),opt('LISTS','LISTS 名单'),opt('THRESHOLDS','THRESHOLDS 阈值')] },
      { k:'status', label:'状态', type:'select', field:'status', opts:[opt('','全部'),opt('ACTIVE','ACTIVE 当前'),opt('SUPERSEDED','SUPERSEDED 历史')] },
      ...DATE_FILTERS ] });
    lpDef('rkAlert', { table:'rkAlertTable', render:rkAlertRows, filters:[
      { k:'kw', label:'关键字', ph:'规则 ID / 告警内容 / traceId', fields:['ruleId','message','traceId'] },
      { k:'kind', label:'类型', type:'select', field:'kind', opts:[opt('','全部'),opt('RULE_COMPILE','RULE_COMPILE 编译失败'),
        opt('RULE_EXEC','RULE_EXEC 执行异常'),opt('RULE_TIMEOUT','RULE_TIMEOUT 超时'),opt('CONFIG_PARSE_FAIL','CONFIG_PARSE_FAIL 配置解析失败')] },
      ...DATE_FILTERS ] });
    lpDef('rkQuota', { table:'rkQuotaTable', timeField:'updatedAt', render:rkQuotaRows, filters:[
      { k:'kw', label:'关键字', ph:'限额 ID / 名称 / 作用域', fields:['id','name','scopeKey','dimensions'] },
      { k:'period', label:'周期', type:'select', field:'period', opts:[opt('','全部')].concat(RK_PERIOD) },
      { k:'status', label:'状态', type:'select', field:'status', opts:[opt('','全部'),opt('ENABLED','ENABLED'),opt('DISABLED','DISABLED')] } ] });
    lpDef('rkUsage', { table:'rkUsageTable', timeField:'updatedAt', render:rkUsageRows, filters:[
      { k:'kw', label:'关键字', ph:'限额键 / 周期键', fields:['quotaKey','periodKey'] } ] });
    lpDef('rkList', { table:'rkListTable', render:rkListRows, filters:[
      { k:'kw', label:'关键字', ph:'值 / 原因 / 操作人', fields:['value','reason','operator','id'] },
      { k:'listType', label:'名单', type:'select', field:'listType', opts:[opt('','全部')].concat(RK_LTYPE) },
      { k:'valueType', label:'值类型', type:'select', field:'valueType', opts:[opt('','全部')].concat(RK_VTYPE) },
      { k:'status', label:'状态', type:'select', field:'status', opts:[opt('','全部'),opt('ENABLED','ENABLED'),opt('DISABLED','DISABLED'),opt('EXPIRED','EXPIRED')] } ] });
    lpDef('rkDecision', { table:'rkDecisionTable', render:rkDecisionRows, filters:[
      { k:'kw', label:'关键字', ph:'付款方 / 商户 / 业务号 / traceId', fields:['payerId','merchantId','bizNo','traceId','id'] },
      { k:'action', label:'处置', type:'select', field:'action', opts:[opt('','全部')].concat(RK_ACTION) },
      { k:'shadow', label:'影子', type:'select', opts:[opt('','全部'),opt('true','影子'),opt('false','正式')], test:(r,v)=>String(!!r.shadow)===v },
      { k:'degraded', label:'降级', type:'select', opts:[opt('','全部'),opt('true','已降级放行'),opt('false','正常')], test:(r,v)=>String(!!r.degraded)===v },
      ...DATE_FILTERS ] });
    lpDef('rkBehavior', { table:'rkBehaviorTable', render:rkBehaviorRows, filters:[
      { k:'kw', label:'关键字', ph:'付款方 / 设备 / IP / traceId', fields:['payerId','deviceId','ip','traceId','region'] },
      { k:'action', label:'动作', type:'select', field:'action', opts:[opt('','全部'),opt('PAY','PAY'),opt('REFUND','REFUND'),opt('LOGIN','LOGIN')] },
      ...DATE_FILTERS ] });
    lpDef('rkIpSandbox', { table:'rkIpSandboxTable', render:rkIpSandboxRows, filters:[
      { k:'kw', label:'关键字', ph:'CIDR / 地域 / 国家', fields:['cidr','region','country'] } ] });
    lpDef('rkAmlRule', { table:'rkAmlRuleTable', timeField:'updatedAt', render:rkAmlRuleRows, filters:[
      { k:'kw', label:'关键字', ph:'规则 ID / 名称 / 表达式', fields:['id','name','conditionExpr'] },
      { k:'ruleType', label:'类型', type:'select', field:'ruleType', opts:[opt('','全部')].concat(RK_AML_TYPE) },
      { k:'status', label:'状态', type:'select', field:'status', opts:[opt('','全部'),opt('ENABLED','ENABLED'),opt('DISABLED','DISABLED')] } ] });
    lpDef('rkAmlReport', { table:'rkAmlReportTable', render:rkAmlReportRows, filters:[
      { k:'kw', label:'关键字', ph:'报告号 / 付款方 / 商户 / 规则', fields:['reportNo','payerId','merchantId','ruleId'] },
      { k:'status', label:'状态', type:'select', field:'status', opts:[opt('','全部'),opt('PENDING','PENDING 待报送'),opt('SUBMITTED','SUBMITTED 已报送'),opt('CLOSED','CLOSED 已关闭')] },
      { k:'ruleType', label:'类型', type:'select', field:'ruleType', opts:[opt('','全部')].concat(RK_AML_TYPE) },
      ...DATE_FILTERS ] });
    lpDef('rkWatch', { table:'rkWatchTable', timeField:'updatedAt', render:rkWatchRows, filters:[
      { k:'kw', label:'关键字', ph:'名称 / 别名 / 国家', fields:['name','aliases','country'] },
      { k:'listType', label:'名单类型', type:'select', field:'listType', opts:[opt('','全部'),opt('SANCTION','SANCTION 制裁'),opt('PEP','PEP 政要'),opt('INTERNAL','INTERNAL 内部')] },
      { k:'status', label:'状态', type:'select', field:'status', opts:[opt('','全部'),opt('ENABLED','ENABLED'),opt('REMOVED','REMOVED')] } ] });
    lpDef('rkScreen', { table:'rkScreenTable', render:rkScreenRows, filters:[
      { k:'kw', label:'关键字', ph:'主体掩码 / traceId', fields:['subjectMask','traceId','matchType'] },
      { k:'hit', label:'命中', type:'select', opts:[opt('','全部'),opt('true','命中'),opt('false','未命中')], test:(r,v)=>String(!!r.hit)===v } ] });
    lpDef('rkAmlTxn', { table:'rkAmlTxnTable', render:rkAmlTxnRows, filters:[
      { k:'kw', label:'关键字', ph:'付款方 / 商户 / 地域', fields:['payerId','merchantId','region','country'] },
      { k:'direction', label:'方向', type:'select', field:'direction', opts:[opt('','全部'),opt('IN','IN 流入'),opt('OUT','OUT 流出')] },
      ...DATE_FILTERS ] });

    /* ---- 详情抽屉：新增三类单据（复用既有 #detailDrawer / 面包屑 / 返回） ---- */
    DD_KINDS.riskRule = '风控规则'; DD_KINDS.riskDecision = '风控决策'; DD_KINDS.amlReport = '可疑报告';
    DD_RENDER.riskRule = ddRiskRule; DD_RENDER.riskDecision = ddRiskDecision; DD_RENDER.amlReport = ddAmlReport;

    /* ---- 按钮与表单 ---- */
    const on = (id, fn) => { const el = document.getElementById(id); if (el) el.addEventListener('click', fn); };
    on('btnRkRuleNew', () => rkRuleEdit(null));
    on('btnRkRuleSave', () => rkRuleSave());
    on('btnRkRuleCancel', () => { const e = document.getElementById('rkRuleEditor'); if (e) e.hidden = true; });
    on('btnRkRulesRefresh', () => loadRkRules());
    on('btnRkRuleRollback', () => rkRollback('rules', '规则集'));
    on('btnRkShadowOn', () => rkGlobalShadow(true));
    on('btnRkShadowOff', () => rkGlobalShadow(false));
    on('btnRkReload', () => rkReload());
    on('btnRkVersionQuery', () => rkLoadVersions());
    on('btnRkEvaluate', () => rkEvaluate());
    on('btnRkEngineRefresh', () => loadRkEngine());
    on('btnRkQuotaNew', () => rkQuotaEdit(null));
    on('btnRkQuotaSave', () => rkQuotaSave());
    on('btnRkQuotaCancel', () => { const e = document.getElementById('rkQuotaEditor'); if (e) e.hidden = true; });
    on('btnRkQuotaRollback', () => rkRollback('quotas', '限额规则'));
    on('btnRkUsageQuery', () => rkLoadUsage());
    on('btnRkListAdd', () => rkListAdd());
    on('btnRkListHit', () => rkListHit());
    on('btnRkListRollback', () => rkRollback('lists', '名单'));
    on('btnRkThSave', () => rkThresholdSave());
    on('btnRkThRollback', () => rkRollback('thresholds', '评分阈值'));
    on('btnRkDecisionQuery', () => rkLoadDecisions());
    on('btnRkDeviceQuery', () => rkDeviceQuery());
    on('btnRkAccountQuery', () => rkAccountQuery());
    on('btnRkIpQuery', () => rkIpQuery());
    on('btnRkBehaviorQuery', () => rkLoadBehavior());
    on('btnRkBehaviorAnalyze', () => rkBehaviorAnalyze());
    on('btnRkRetention', () => rkRetention());
    on('btnRkProfileRefresh', () => loadRkProfile());
    on('btnRkAmlRuleNew', () => rkAmlRuleEdit(null));
    on('btnRkAmlRuleSave', () => rkAmlRuleSave());
    on('btnRkAmlRuleCancel', () => { const e = document.getElementById('rkAmlRuleEditor'); if (e) e.hidden = true; });
    on('btnRkAmlScan', () => rkAmlScan());
    on('btnRkAmlReportQuery', () => rkLoadAmlReports());
    on('btnRkWatchAdd', () => rkWatchAdd());
    on('btnRkScreen', () => rkScreen());
    on('btnRkAmlRefresh', () => loadRkAml());
    on('btnRkAmlTxnQuery', () => rkLoadAmlTxns());

    /* ---- 行内操作委托 ---- */
    document.addEventListener('click', async ev => {
      const t = ev.target;
      const hit = sel => t.closest && t.closest(sel);
      let el;
      if ((el = hit('[data-rk-rule-edit]'))) { ev.preventDefault(); ev.stopPropagation(); rkRuleEdit(el.dataset.rkRuleEdit); return; }
      if ((el = hit('[data-rk-rule-pub]'))) { ev.preventDefault(); ev.stopPropagation(); await rkRulePublish(el.dataset.rkRulePub); return; }
      if ((el = hit('[data-rk-rule-off]'))) { ev.preventDefault(); ev.stopPropagation(); await rkRuleDisable(el.dataset.rkRuleOff); return; }
      if ((el = hit('[data-rk-rule-shadow]'))) { ev.preventDefault(); ev.stopPropagation(); await rkRuleShadow(el.dataset.rkRuleShadow, el.dataset.on === '1'); return; }
      if ((el = hit('[data-rk-quota-edit]'))) { ev.preventDefault(); ev.stopPropagation(); rkQuotaEdit(el.dataset.rkQuotaEdit); return; }
      if ((el = hit('[data-rk-quota-off]'))) { ev.preventDefault(); ev.stopPropagation(); await rkQuotaDisable(el.dataset.rkQuotaOff); return; }
      if ((el = hit('[data-rk-list-del]'))) { ev.preventDefault(); ev.stopPropagation(); await rkListRemove(el.dataset.rkListDel); return; }
      if ((el = hit('[data-rk-review]'))) { ev.preventDefault(); ev.stopPropagation(); await rkReview(el.dataset.rkReview, el.dataset.approve === '1'); return; }
      if ((el = hit('[data-rk-aml-edit]'))) { ev.preventDefault(); ev.stopPropagation(); rkAmlRuleEdit(el.dataset.rkAmlEdit); return; }
      if ((el = hit('[data-rk-aml-off]'))) { ev.preventDefault(); ev.stopPropagation(); await rkAmlRuleDisable(el.dataset.rkAmlOff); return; }
      if ((el = hit('[data-rk-str-submit]'))) { ev.preventDefault(); ev.stopPropagation(); await rkStrMove(el.dataset.rkStrSubmit, 'submit'); return; }
      if ((el = hit('[data-rk-str-close]'))) { ev.preventDefault(); ev.stopPropagation(); await rkStrMove(el.dataset.rkStrClose, 'close'); return; }
      if ((el = hit('[data-rk-watch-del]'))) { ev.preventDefault(); ev.stopPropagation(); await rkWatchRemove(el.dataset.rkWatchDel); return; }
      if ((el = hit('[data-rk-dev]'))) { ev.preventDefault(); ev.stopPropagation();
        const i = document.getElementById('rkDeviceId'); if (i) { i.value = el.dataset.rkDev; PPNav.go('rk-profile'); rkDeviceQuery(); } return; }
      if ((el = hit('[data-rk-ip]'))) { ev.preventDefault(); ev.stopPropagation();
        const i = document.getElementById('rkIp'); if (i) { i.value = el.dataset.rkIp; PPNav.go('rk-profile'); rkIpQuery(); } return; }
    });
  }
};

/* ---------- 通用 helper ---------- */
const rkBadge = (v, txt) => `<span class="badge ${esc(v || '')}">${esc(txt || v || '—')}</span>`;
const rkYes = (v, on, off) => v ? `<span class="pp-tag on">${esc(on || '是')}</span>` : `<span class="pp-tag off">${esc(off || '否')}</span>`;
const rkJson = s => { if (s == null || s === '') return null; if (typeof s === 'object') return s;
  try { return JSON.parse(s); } catch (e) { return null; } };
const rkVal = id => { const e = document.getElementById(id); return e ? String(e.value || '').trim() : ''; };
const rkNum = id => { const v = rkVal(id); if (v === '') return null; const n = Number(v); return isFinite(n) ? n : null; };
const rkFen = id => { const v = rkVal(id); return v === '' ? null : toFen(v); };
const rkSet = (id, v) => { const e = document.getElementById(id); if (e) e.value = (v == null ? '' : v); };
const rkCheck = (id, v) => { const e = document.getElementById(id); if (e) e.checked = !!v; };
const rkChecked = id => { const e = document.getElementById(id); return !!(e && e.checked); };
function rkBox(id, kind, html) { const e = document.getElementById(id); if (e) e.innerHTML = `<div class="box ${kind}">${html}</div>`; }
function rkFactorTable(factors) {
  const fs = rkJson(factors) || (Array.isArray(factors) ? factors : []);
  if (!fs.length) return ddBox('plain', '无评分因子留痕（未开启评分或命中硬规则直接处置）。');
  return ddTable(['因子','名称','权重','取值','贡献分','说明'], fs.map(f => `<tr>
    <td class="pp-mono">${esc(f.code)}</td><td>${esc(f.name || '—')}</td><td class="num">${f.weight != null ? f.weight : '—'}</td>
    <td class="muted">${esc(f.value)}</td><td class="num ${f.score ? 'neg' : ''}">${f.score != null ? f.score : '—'}</td>
    <td class="muted wrap">${esc(f.detail || '—')}</td></tr>`).join(''), '');
}
function rkGuard(reason) { return `<div class="box warn">${reason}<br>该操作为敏感操作，需二次认证（X-Step-Up-Token），并写入版本快照与操作审计。</div>`; }

/* ================= 7.1 规则与版本 ================= */
async function loadRkRules() {
  if (!hasPerm('risk:read')) { lpSet('rkRule', []); lpSet('rkVersion', []); return; }
  lpLoading('rkRule', 12);
  const [r, e] = await Promise.all([tryApi('/risk/rules'), tryApi('/risk/engine')]);
  if (r.ok && Array.isArray(r.data)) { PPRisk.rules = r.data; lpSet('rkRule', r.data, true); }
  else lpError('rkRule', 12, (r.err && r.err.message) || '返回形状异常');
  const box = document.getElementById('rkRuleEngine');
  if (box) {
    if (e.ok) { PPRisk.engine = e.data; box.innerHTML = rkEngineSummary(e.data); }
    else box.innerHTML = `<div class="box danger">引擎状态加载失败：${esc(e.err.message)}</div>`;
  }
  await rkLoadVersions();
}
function rkEngineSummary(e) {
  const deg = (e.compileFailures || 0) + (e.execErrors || 0) + (e.timeouts || 0) + (e.configParseFailures || 0);
  return `<div class="pp-inline">
    <span class="pp-tag info">引擎 ${esc(e.engine || '—')}</span>
    <span class="pp-tag ${e.engineEnabled ? 'on' : 'bad'}">规则引擎 ${e.engineEnabled ? '启用' : '停用'}</span>
    <span class="pp-tag ${e.globalShadow ? 'warn' : 'on'}">全局影子 ${e.globalShadow ? '开（只记录不拦截）' : '关（正式拦截）'}</span>
    <span class="pp-tag">规则版本 ${esc(e.rulesVersion)}</span>
    <span class="pp-tag">规则数 ${esc(e.ruleCount)}</span>
    <span class="pp-tag">配置源 ${esc(e.configProvider || '—')}</span>
    ${deg ? `<span class="pp-tag bad">降级告警 ${deg}</span>` : '<span class="pp-tag on">无降级</span>'}
    <button class="btn small ghost" data-pp-goto="rk-engine">引擎与试算 →</button></div>
    ${deg ? `<div class="box warn" style="margin-top:8px"><b>护栏生效</b>：编译失败 ${e.compileFailures || 0} · 执行异常 ${e.execErrors || 0} · 超时 ${e.timeouts || 0} · 配置解析失败 ${e.configParseFailures || 0}
      —— 一律<b>放行并告警</b>（决策留痕 degraded=true），不会误拦正常交易。</div>` : ''}`;
}
function rkRuleRows(rows) {
  const t = document.getElementById('rkRuleTable'); if (!t) return;
  const w = hasPerm('risk:write');
  t.innerHTML = `<tr><th>规则 ID</th><th>名称</th><th>场景</th><th class="num">优先级</th><th>动作</th><th class="num">分值</th>
    <th>状态</th><th class="num">版本</th><th class="num">灰度%</th><th>影子</th><th>编译</th><th>操作</th></tr>` +
    (rows.length ? rows.map(r => {
      const cs = r.compileStatus || '—';
      const cBad = /FAIL/i.test(cs);
      return `<tr data-dd="riskRule:${esc(r.id)}">
        <td class="dd-cell"><b>${esc(r.id)}</b></td><td class="wrap">${esc(r.name || '—')}</td>
        <td>${rkBadge('kind', r.scene)}</td><td class="num">${r.priority != null ? r.priority : '—'}</td>
        <td>${rkBadge(r.action)}</td><td class="num">${r.score != null ? r.score : '—'}</td>
        <td>${rkBadge(r.status)}</td><td class="num">${r.version != null ? r.version : '—'}</td>
        <td class="num">${r.grayPercent != null ? r.grayPercent : 100}${r.whitelist ? ' <span class="pp-tag info">白名单</span>' : ''}</td>
        <td>${rkYes(r.shadow, '影子', '正式')}</td>
        <td>${cBad ? `<span class="pp-tag bad" title="${esc(r.compileError || '')}">${esc(cs)}</span>` : `<span class="pp-tag on">${esc(cs)}</span>`}</td>
        <td>${w ? `<button class="btn small ghost" data-rk-rule-edit="${esc(r.id)}">编辑</button>
          <button class="btn small" data-rk-rule-pub="${esc(r.id)}">发布</button>
          <button class="btn small ghost" data-rk-rule-shadow="${esc(r.id)}" data-on="${r.shadow ? '0' : '1'}">${r.shadow ? '转正式' : '转影子'}</button>
          <button class="btn small danger" data-rk-rule-off="${esc(r.id)}">停用</button>` : '<span class="muted">只读</span>'}</td></tr>`;
    }).join('') : emptyRow(12, '暂无风控规则（配置中心 risk-rules 下发或在此新建）'));
}
function rkRuleEdit(id) {
  const ed = document.getElementById('rkRuleEditor'); if (!ed) return;
  const r = id ? PPRisk.rules.find(x => String(x.id) === String(id)) : null;
  ed.hidden = false;
  document.getElementById('rkRuleEditorTitle').textContent = r ? ('编辑规则 ' + r.id) : '新建规则';
  rkSet('rkRuleId', r ? r.id : 'UIIA_' + uuid().slice(0, 8));
  const idEl = document.getElementById('rkRuleId'); if (idEl) idEl.readOnly = !!r;
  rkSet('rkRuleName', r ? r.name : '');
  rkSet('rkRuleScene', r ? (r.scene || 'PAY') : 'PAY');
  rkSet('rkRulePriority', r ? r.priority : 100);
  rkSet('rkRuleAction', r ? (r.action || 'REJECT') : 'REJECT');
  rkSet('rkRuleScore', r ? r.score : 60);
  rkSet('rkRuleGray', r && r.grayPercent != null ? r.grayPercent : 100);
  rkSet('rkRuleWhitelist', r ? r.whitelist : '');
  rkCheck('rkRuleShadow', r ? r.shadow : true);
  rkSet('rkRuleExpr', r ? r.conditionExpr : 'amount > 500000 && txnCount(payerId, 86400000) > 10');
  ed.scrollIntoView({ block:'nearest' });
}
async function rkRuleSave() {
  let rule;
  try {
    rule = { id: rkVal('rkRuleId'), name: rkVal('rkRuleName'), scene: rkVal('rkRuleScene'),
      priority: rkNum('rkRulePriority'), conditionExpr: rkVal('rkRuleExpr'), action: rkVal('rkRuleAction'),
      score: rkNum('rkRuleScore'), grayPercent: rkNum('rkRuleGray'), whitelist: rkVal('rkRuleWhitelist') || null,
      shadow: rkChecked('rkRuleShadow') };
    if (!rule.id) throw new Error('请填写规则 ID');
    if (!rule.conditionExpr) throw new Error('请填写 QLExpress 条件表达式');
  } catch (e) { toast(e.message, false); return; }
  const ok = await askConfirm({ title:'保存风控规则', okText:'确认保存',
    html:`规则 <b>${esc(rule.id)}</b> · 场景 ${esc(rule.scene)} · 动作 ${esc(rule.action)} · 分值 ${rule.score}
      <div class="pp-code light">${esc(rule.conditionExpr)}</div>${rkGuard('保存会生成新的规则集版本快照（可一键回滚）。')}` });
  if (!ok) return;
  try {
    await sensitiveApi('/risk/rules', 'POST', rule, `保存风控规则 <b>${esc(rule.id)}</b>（${esc(rule.action)}）`);
    toast('规则已保存');
    const ed = document.getElementById('rkRuleEditor'); if (ed) ed.hidden = true;
    await loadRkRules();
  } catch (e) { toast(e.message, false); }
}
async function rkRulePublish(id) {
  const r = PPRisk.rules.find(x => String(x.id) === String(id)) || {};
  const gray = prompt(`发布规则 ${id}\n灰度比例 0~100（按 payerId FNV 哈希取模；白名单恒生效）`,
    r.grayPercent != null ? String(r.grayPercent) : '100');
  if (gray === null) return;
  const g = Number(gray);
  if (!isFinite(g) || g < 0 || g > 100) { toast('灰度比例必须是 0~100', false); return; }
  const ok = await askConfirm({ title:'发布风控规则', okText:'确认发布',
    html:`规则 <b>${esc(id)}</b> → PUBLISHED · 灰度 <b>${g}%</b>${r.whitelist ? ` · 白名单 ${esc(r.whitelist)}` : ''}
      ${rkGuard('发布后正式参与拦截（除影子模式外）。')}` });
  if (!ok) return;
  try {
    await sensitiveApi('/risk/rules/' + encodeURIComponent(id) + '/publish', 'POST',
      { grayPercent: g, whitelist: r.whitelist || null }, `发布风控规则 <b>${esc(id)}</b>（灰度 ${g}%）`);
    toast('规则已发布'); await loadRkRules();
  } catch (e) { toast(e.message, false); }
}
async function rkRuleDisable(id) {
  const ok = await askConfirm({ title:'停用风控规则', okText:'确认停用',
    html:`规则 <b>${esc(id)}</b> → DISABLED，不再参与决策。${rkGuard('停用会生成版本快照。')}` });
  if (!ok) return;
  try { await sensitiveApi('/risk/rules/' + encodeURIComponent(id) + '/disable', 'POST', {}, `停用风控规则 <b>${esc(id)}</b>`);
    toast('规则已停用'); await loadRkRules(); } catch (e) { toast(e.message, false); }
}
async function rkRuleShadow(id, on) {
  const ok = await askConfirm({ title: on ? '切换为影子模式' : '退出影子模式', okText:'确认执行', danger: on ? false : true,
    html:`规则 <b>${esc(id)}</b> → ${on ? '影子（只记录命中不拦截）' : '<b>正式生效（会真实拦截）</b>'}
      ${rkGuard(on ? '影子模式用于灰度观测。' : '退出影子后命中即真实拦截，请确认灰度比例与白名单。')}` });
  if (!ok) return;
  try { await sensitiveApi('/risk/rules/' + encodeURIComponent(id) + '/shadow', 'POST', { on },
      `规则 <b>${esc(id)}</b> ${on ? '进入' : '退出'}影子模式`);
    toast('影子模式已切换'); await loadRkRules(); } catch (e) { toast(e.message, false); }
}
async function rkGlobalShadow(on) {
  const ok = await askConfirm({ title: on ? '开启全局影子' : '关闭全局影子', okText:'确认执行', danger: !on,
    html: on ? '全局影子开启后，所有规则命中<b>只记录不拦截</b>（用于上线观测）。'
             : '关闭全局影子后，规则命中将<b>真实拦截交易</b>。请确认规则集与灰度已验证。' + rkGuard('全局开关影响所有场景。') });
  if (!ok) return;
  try { await sensitiveApi('/risk/shadow', 'POST', { on }, `${on ? '开启' : '关闭'}风控<b>全局影子</b>开关`);
    toast('全局影子已' + (on ? '开启' : '关闭')); await loadRkRules(); } catch (e) { toast(e.message, false); }
}
async function rkReload() {
  try { const r = await api('/risk/reload', 'POST', {}); toast('配置已重载' + (r && r.rulesVersion != null ? '（规则版本 ' + r.rulesVersion + '）' : '')); await loadRkRules(); }
  catch (e) { toast(e.message, false); }
}
async function rkRollback(kind, label) {
  const ok = await askConfirm({ title:'一键回滚' + label, okText:'确认回滚',
    html:`将 <b>${esc(label)}</b> 回滚到上一个版本快照（ACTIVE → SUPERSEDED，历史版本重新生效）。
      ${rkGuard('回滚为高危操作，会立即改变线上风控行为。')}` });
  if (!ok) return;
  try { const r = await sensitiveApi('/risk/' + kind + '/rollback', 'POST', {}, `一键回滚<b>${esc(label)}</b>到上一版本`);
    toast(label + '已回滚' + (r && r.version != null ? '（版本 ' + r.version + '）' : ''));
    if (kind === 'rules') await loadRkRules();
    else if (kind === 'quotas') await loadRkQuotas();
    else if (kind === 'lists') await loadRkLists();
    else await loadRkScore();
  } catch (e) { toast(e.message, false); }
}
async function rkLoadVersions() {
  lpLoading('rkVersion', 8);
  const kind = rkVal('rkVersionKind'), limit = rkNum('rkVersionLimit') || 50;
  const p = new URLSearchParams({ limit: String(limit) });
  if (kind) p.set('kind', kind);
  const r = await tryApi('/risk/versions?' + p.toString());
  if (r.ok && Array.isArray(r.data)) lpSet('rkVersion', r.data, true);
  else lpError('rkVersion', 8, (r.err && r.err.message) || '返回形状异常');
}
function rkVersionRows(rows) {
  const t = document.getElementById('rkVersionTable'); if (!t) return;
  t.innerHTML = `<tr><th>快照 ID</th><th>类型</th><th class="num">版本</th><th>状态</th><th>来源</th><th>操作人</th><th>变更说明</th><th>时间 / 载荷</th></tr>` +
    (rows.length ? rows.map(v => `<tr>
      <td>${esc(v.id)}</td><td>${rkBadge('kind', v.kind)}</td><td class="num"><b>${esc(v.version)}</b></td>
      <td>${rkBadge(v.status)}</td><td class="muted">${esc(v.source || '—')}</td><td>${esc(v.operator || '—')}</td>
      <td class="wrap muted">${esc(v.changeNote || '—')}</td>
      <td class="muted">${fmtTime(v.createdAt)}<details><summary class="link-btn">载荷</summary>
        <div class="pp-code light" style="max-width:420px">${esc(v.payload || '—')}</div></details></td></tr>`).join('')
      : emptyRow(8, '暂无版本快照'));
}

/* ================= 7.2 引擎与试算 ================= */
async function loadRkEngine() {
  if (!hasPerm('risk:read')) { lpSet('rkAlert', []); return; }
  const box = document.getElementById('rkEngineBox');
  const [e, a] = await Promise.all([tryApi('/risk/engine'), tryApi('/risk/alerts?limit=200')]);
  if (box) {
    if (e.ok) {
      PPRisk.engine = e.data;
      const d = e.data, th = d.thresholds || {};
      const thRows = Object.values(th).map(x => `<tr><td>${esc(x.scene)}</td><td class="num">${x.challengeScore}</td>
        <td class="num">${x.limitScore}</td><td class="num">${x.rejectScore}</td><td class="num">${x.reviewScore}</td>
        <td class="num">${ppFen(x.limitAmount)}</td><td class="num">${esc(x.version)}</td><td class="muted">${fmtTime(x.updatedAt)}</td></tr>`).join('');
      box.innerHTML = `<div class="stat-cards">
        <div class="stat-card accent"><div class="label">已评估 / 命中</div><div class="value">${d.evaluated || 0} / ${d.hits || 0}</div></div>
        <div class="stat-card ${d.compileFailures ? 'bad' : 'ok'}"><div class="label">编译失败 / 已编译</div><div class="value">${d.compileFailures || 0} / ${d.compiledExpressions || 0}</div></div>
        <div class="stat-card ${d.execErrors ? 'bad' : 'ok'}"><div class="label">执行异常</div><div class="value">${d.execErrors || 0}</div></div>
        <div class="stat-card ${d.timeouts ? 'warn' : 'ok'}"><div class="label">超时（软/硬 ms）</div><div class="value">${d.timeouts || 0}<span style="font-size:12px"> · ${d.timeoutMs || '—'}/${d.hardTimeoutMs || '—'}</span></div></div>
        <div class="stat-card ${d.configParseFailures ? 'bad' : 'ok'}"><div class="label">配置解析失败 / 重载</div><div class="value">${d.configParseFailures || 0} / ${d.configReloads || 0}</div></div>
        <div class="stat-card"><div class="label">告警条数</div><div class="value">${d.alertCount || 0}</div></div></div>
        <div class="tag-line">
          <span class="tag">引擎 <b>${esc(d.engine)}</b></span><span class="tag">配置源 <b>${esc(d.configProvider)}</b></span>
          <span class="tag">轮询 <b>${esc(d.pollSeconds)}s</b></span><span class="tag">限额计数 <b>${esc(d.quotaProvider)}</b></span>
          <span class="tag">规则 <b>${d.engineEnabled ? '开' : '关'}</b></span><span class="tag">限额 <b>${d.quotaEnabled ? '开' : '关'}</b></span>
          <span class="tag">名单 <b>${d.listEnabled ? '开' : '关'}</b></span><span class="tag">评分 <b>${d.scoreEnabled ? '开' : '关'}</b></span>
          <span class="tag">全局影子 <b>${d.globalShadow ? '开' : '关'}</b></span>
          <span class="tag">版本 规则<b>${esc(d.rulesVersion)}</b>/限额<b>${esc(d.quotasVersion)}</b>/名单<b>${esc(d.listsVersion)}</b>/阈值<b>${esc(d.thresholdsVersion)}</b></span></div>
        ${rkEngineSummary(d)}
        <div style="margin-top:10px">${ddTable(['场景','挑战分','限额分','拒绝分','人审分','限额金额(元)','版本','更新时间'], thRows, '无阈值配置')}</div>`;
    } else box.innerHTML = `<div class="box danger">引擎状态加载失败：${esc(e.err.message)}</div>`;
  }
  lpLoading('rkAlert', 6);
  if (a.ok && Array.isArray(a.data)) lpSet('rkAlert', a.data, true);
  else lpError('rkAlert', 6, (a.err && a.err.message) || '返回形状异常');
}
function rkAlertRows(rows) {
  const t = document.getElementById('rkAlertTable'); if (!t) return;
  t.innerHTML = `<tr><th>告警 ID</th><th>类型</th><th>规则</th><th class="wrap">内容</th><th>traceId</th><th>时间</th></tr>` +
    (rows.length ? rows.map(a => `<tr>
      <td>${esc(a.id)}</td><td>${rkBadge(/FAIL|EXEC|TIMEOUT/.test(a.kind || '') ? 'DEGRADED' : 'kind', a.kind)}</td>
      <td class="pp-mono">${esc(a.ruleId || '—')}</td><td class="wrap muted">${esc(a.message || '—')}</td>
      <td class="muted">${esc(a.traceId || '—')}</td><td class="muted">${fmtTime(a.createdAt)}</td></tr>`).join('')
      : emptyRow(6, '暂无告警（护栏未触发）'));
}
async function rkEvaluate() {
  const box = document.getElementById('rkEvalResult');
  if (!box) return;
  let req;
  try {
    req = { payerId: rkVal('rkEvalPayer'), merchantId: rkVal('rkEvalMer'), amount: rkFen('rkEvalAmount'),
      method: rkVal('rkEvalMethod'), scene: rkVal('rkEvalScene'), channelCode: rkVal('rkEvalChannel') || null,
      cardBin: rkVal('rkEvalBin') || null, deviceId: rkVal('rkEvalDevice') || null, ip: rkVal('rkEvalIp') || null,
      traceId: rkVal('rkEvalTrace') || null, bizNo: rkVal('rkEvalBizNo') || null };
    if (!req.payerId) throw new Error('请填写付款方 ID');
    if (req.amount == null) throw new Error('请填写金额（元）');
  } catch (e) { toast(e.message, false); return; }
  box.innerHTML = '<div class="box plain"><span class="spinner"></span>正在试算（与受理同一口径，会落决策留痕）…</div>';
  try {
    const d = await api('/risk/evaluate', 'POST', req);
    const hits = rkJson(d.hitRules) || d.hitRules || [];
    box.innerHTML = `<div class="box ${d.pass ? 'ok' : 'danger'}">
      <b>${d.pass ? '放行' : '拦截'}</b> · 处置 ${rkBadge(d.action)} · 风险分 <b>${d.score != null ? d.score : 0}</b>
      ${d.shadow ? '<span class="pp-tag warn">影子（不真实拦截）</span>' : ''}
      ${d.degraded ? '<span class="pp-tag bad">降级放行 degraded</span>' : ''}
      · 规则版本 ${esc(d.ruleVersion)} · 决策留痕 ${d.decisionId ? `<button class="link-btn" data-dd="riskDecision:${esc(d.decisionId)}">#${esc(d.decisionId)}</button>` : '—'}
      <br>命中规则：${hits.length ? hits.map(h => `<span class="pp-tag info">${esc(h)}</span>`).join(' ') : '无'}
      ${d.reason ? `<br>原因：${esc(d.reason)}（${esc(d.code || '')}）` : ''}
      ${d.listHit ? `<br>名单命中：<span class="pp-mono">${esc(typeof d.listHit === 'object' ? JSON.stringify(d.listHit) : d.listHit)}</span>` : ''}
      ${d.quotaHit ? `<br>限额命中：<span class="pp-mono">${esc(typeof d.quotaHit === 'object' ? JSON.stringify(d.quotaHit) : d.quotaHit)}</span>` : ''}</div>
      ${rkFactorTable(d.factors)}`;
  } catch (e) { box.innerHTML = `<div class="box danger">试算失败：${esc(e.message)}</div>`; }
}

/* ================= 7.3 多维限额 ================= */
async function loadRkQuotas() {
  if (!hasPerm('risk:read')) { lpSet('rkQuota', []); lpSet('rkUsage', []); return; }
  lpLoading('rkQuota', 10);
  const r = await tryApi('/risk/quotas');
  if (r.ok && Array.isArray(r.data)) { PPRisk.quotas = r.data; lpSet('rkQuota', r.data, true); }
  else lpError('rkQuota', 10, (r.err && r.err.message) || '返回形状异常');
  await rkLoadUsage();
}
function rkQuotaRows(rows) {
  const t = document.getElementById('rkQuotaTable'); if (!t) return;
  const w = hasPerm('risk:write');
  t.innerHTML = `<tr><th>限额 ID</th><th>名称</th><th>维度组合</th><th>周期</th><th class="num">金额上限(元)</th>
    <th class="num">笔数上限</th><th>作用域</th><th>场景</th><th>状态</th><th>操作</th></tr>` +
    (rows.length ? rows.map(q => `<tr>
      <td><b>${esc(q.id)}</b></td><td class="wrap">${esc(q.name || '—')}</td>
      <td>${String(q.dimensions || '').split(',').filter(Boolean).map(d => `<span class="pp-tag info">${esc(d)}</span>`).join(' ') || '—'}</td>
      <td>${rkBadge('kind', q.period)}</td><td class="num">${ppFen(q.limitAmount)}</td>
      <td class="num">${q.limitCount != null && q.limitCount > 0 ? q.limitCount : '不限'}</td>
      <td class="pp-mono muted">${esc(q.scopeKey || '全量')}</td><td>${esc(q.scene || 'ALL')}</td>
      <td>${rkBadge(q.status)} <span class="muted">v${esc(q.version)}</span></td>
      <td>${w ? `<button class="btn small ghost" data-rk-quota-edit="${esc(q.id)}">编辑</button>
        <button class="btn small danger" data-rk-quota-off="${esc(q.id)}">停用</button>` : '<span class="muted">只读</span>'}</td></tr>`).join('')
      : emptyRow(10, '暂无限额规则'));
}
function rkQuotaEdit(id) {
  const ed = document.getElementById('rkQuotaEditor'); if (!ed) return;
  const q = id ? PPRisk.quotas.find(x => String(x.id) === String(id)) : null;
  ed.hidden = false;
  document.getElementById('rkQuotaEditorTitle').textContent = q ? ('编辑限额 ' + q.id) : '新建限额';
  rkSet('rkQuotaId', q ? q.id : 'UIIA_Q_' + uuid().slice(0, 6));
  const idEl = document.getElementById('rkQuotaId'); if (idEl) idEl.readOnly = !!q;
  rkSet('rkQuotaName', q ? q.name : '');
  rkSet('rkQuotaDims', q ? q.dimensions : 'USER');
  rkSet('rkQuotaPeriod', q ? (q.period || 'DAY') : 'DAY');
  rkSet('rkQuotaAmount', q && q.limitAmount != null && q.limitAmount > 0 ? yuan(q.limitAmount) : '');
  rkSet('rkQuotaCount', q && q.limitCount != null && q.limitCount > 0 ? q.limitCount : '');
  rkSet('rkQuotaScope', q ? q.scopeKey : '');
  rkSet('rkQuotaScene', q ? (q.scene || 'ALL') : 'ALL');
  ed.scrollIntoView({ block:'nearest' });
}
async function rkQuotaSave() {
  let q;
  try {
    q = { id: rkVal('rkQuotaId'), name: rkVal('rkQuotaName'), dimensions: rkVal('rkQuotaDims'),
      period: rkVal('rkQuotaPeriod'), limitAmount: rkFen('rkQuotaAmount'), limitCount: rkNum('rkQuotaCount'),
      scopeKey: rkVal('rkQuotaScope') || null, scene: rkVal('rkQuotaScene') };
    if (!q.id || !q.dimensions) throw new Error('请填写限额 ID 与维度组合');
    if (!(q.limitAmount > 0) && !(q.limitCount > 0)) throw new Error('金额上限与笔数上限至少一项 > 0');
  } catch (e) { toast(e.message, false); return; }
  const ok = await askConfirm({ title:'保存多维限额', okText:'确认保存',
    html:`限额 <b>${esc(q.id)}</b> · 维度 ${esc(q.dimensions)} × 周期 ${esc(q.period)}<br>
      金额上限 <b>${q.limitAmount ? '¥ ' + yuan(q.limitAmount) : '不限'}</b> · 笔数上限 <b>${q.limitCount || '不限'}</b>
      ${rkGuard('限额直接拦截受理，保存即生效并生成版本快照。')}` });
  if (!ok) return;
  try { await sensitiveApi('/risk/quotas', 'POST', q, `保存多维限额 <b>${esc(q.id)}</b>`);
    toast('限额已保存'); const ed = document.getElementById('rkQuotaEditor'); if (ed) ed.hidden = true;
    await loadRkQuotas(); } catch (e) { toast(e.message, false); }
}
async function rkQuotaDisable(id) {
  const ok = await askConfirm({ title:'停用限额规则', okText:'确认停用',
    html:`限额 <b>${esc(id)}</b> → DISABLED。${rkGuard('停用后该维度不再拦截。')}` });
  if (!ok) return;
  try { await sensitiveApi('/risk/quotas/' + encodeURIComponent(id) + '/disable', 'POST', {}, `停用限额 <b>${esc(id)}</b>`);
    toast('限额已停用'); await loadRkQuotas(); } catch (e) { toast(e.message, false); }
}
async function rkLoadUsage() {
  lpLoading('rkUsage', 6);
  const pre = rkVal('rkUsagePrefix');
  const r = await tryApi('/risk/quotas/usage' + (pre ? '?keyPrefix=' + encodeURIComponent(pre) : ''));
  if (r.ok && Array.isArray(r.data)) lpSet('rkUsage', r.data, true);
  else lpError('rkUsage', 6, (r.err && r.err.message) || '返回形状异常');
}
function rkUsageRows(rows) {
  const t = document.getElementById('rkUsageTable'); if (!t) return;
  t.innerHTML = `<tr><th>限额键</th><th>周期键</th><th class="num">已用金额(元)</th><th class="num">已用笔数</th><th>用量</th><th>更新时间</th></tr>` +
    (rows.length ? rows.map(u => {
      const m = PPRisk.quotas.find(q => String(u.quotaKey || '').startsWith('Q_' + String(q.id).replace(/^Q_/, '') + ':')) || null;
      return `<tr><td class="pp-mono">${esc(u.quotaKey)}</td><td>${esc(u.periodKey)}</td>
        <td class="num">${ppFen(u.usedAmount)}</td><td class="num">${u.usedCount != null ? u.usedCount : '—'}</td>
        <td style="min-width:120px">${m ? ppMeter(u.usedAmount, m.limitAmount) : '<span class="muted">无匹配规则</span>'}</td>
        <td class="muted">${fmtTime(u.updatedAt)}</td></tr>`;
    }).join('') : emptyRow(6, '暂无用量计数（Redis / 内存计数器）'));
}

/* ================= 7.4 名单管理 ================= */
async function loadRkLists() {
  if (!hasPerm('risk:read')) { lpSet('rkList', []); return; }
  lpLoading('rkList', 10);
  const r = await tryApi('/risk/lists');
  if (r.ok && Array.isArray(r.data)) { PPRisk.lists = r.data; lpSet('rkList', r.data, true); }
  else lpError('rkList', 10, (r.err && r.err.message) || '返回形状异常');
}
function rkListRows(rows) {
  const t = document.getElementById('rkListTable'); if (!t) return;
  const w = hasPerm('risk:write');
  t.innerHTML = `<tr><th>ID</th><th>名单</th><th>值类型</th><th>值</th><th>处置</th><th>原因</th><th>过期</th><th>状态</th><th>操作人</th><th>操作</th></tr>` +
    (rows.length ? rows.map(l => `<tr>
      <td>${esc(l.id)}</td><td>${rkBadge(l.listType === 'GRAY' ? 'GRAYLIST' : l.listType, l.listType)}</td>
      <td>${rkBadge('kind', l.valueType)}</td><td class="pp-mono"><b>${esc(l.value)}</b></td>
      <td>${rkBadge(l.action)}</td><td class="wrap muted">${esc(l.reason || '—')}</td>
      <td class="muted">${l.expireAt ? fmtTime(l.expireAt) : '永不过期'}</td>
      <td>${rkBadge(l.status)}</td><td>${esc(l.operator || '—')}</td>
      <td>${w ? `<button class="btn small danger" data-rk-list-del="${esc(l.id)}">删除</button>` : '<span class="muted">只读</span>'}</td></tr>`).join('')
      : emptyRow(10, '暂无名单条目'));
}
async function rkListAdd() {
  let e;
  try {
    const exp = rkVal('rkListExpire');
    e = { listType: rkVal('rkListType'), valueType: rkVal('rkListVType'), value: rkVal('rkListValue'),
      action: rkVal('rkListAction') || null, reason: rkVal('rkListReason') || null,
      expireAt: exp ? new Date(exp + 'T23:59:59').getTime() : null };
    if (!e.value) throw new Error('请填写名单值');
  } catch (err) { toast(err.message, false); return; }
  const ok = await askConfirm({ title:'新增名单条目', okText:'确认新增',
    html:`${esc(e.listType)} · ${esc(e.valueType)} = <b>${esc(e.value)}</b> · 处置 ${esc(e.action || '按名单类型推导')}
      ${rkGuard('黑名单会直接拦截交易；白名单会跳过拦截与评分升级（WHITE &gt; BLACK &gt; GRAY）。')}` });
  if (!ok) return;
  try { await sensitiveApi('/risk/lists', 'POST', e, `新增名单 <b>${esc(e.listType)}/${esc(e.valueType)}=${esc(e.value)}</b>`);
    toast('名单已新增'); rkSet('rkListValue', ''); await loadRkLists(); } catch (err) { toast(err.message, false); }
}
async function rkListRemove(id) {
  const ok = await askConfirm({ title:'删除名单条目', okText:'确认删除',
    html:`名单条目 <b>${esc(id)}</b> 将被移除（生成版本快照，可回滚）。${rkGuard('删除白名单可能使原本放行的主体重新被拦截。')}` });
  if (!ok) return;
  try { await sensitiveApi('/risk/lists/' + encodeURIComponent(id), 'DELETE', undefined, `删除名单条目 <b>${esc(id)}</b>`);
    toast('名单已删除'); await loadRkLists(); } catch (e) { toast(e.message, false); }
}
async function rkListHit() {
  const vt = rkVal('rkHitVType'), v = rkVal('rkHitValue');
  if (!v) { toast('请填写要试探的值', false); return; }
  const r = await tryApi('/risk/lists/hit?valueType=' + encodeURIComponent(vt) + '&value=' + encodeURIComponent(v));
  if (!r.ok) { rkBox('rkHitResult', 'danger', '试探失败：' + esc(r.err.message)); return; }
  const d = r.data || {};
  rkBox('rkHitResult', d.hit ? 'warn' : 'ok',
    `${d.hit ? '<b>命中</b>' : '未命中'} · 名单 ${esc(d.listType || '—')} · 处置 ${esc(d.action || '—')}
     · 条目 ${esc(d.entryId || '—')} · 原因 ${esc(d.reason || '—')}`);
}

/* ================= 7.5 评分与处置 ================= */
async function loadRkScore() {
  if (!hasPerm('risk:read')) { lpSet('rkDecision', []); return; }
  const scene = rkVal('rkThScene') || 'PAY';
  const t = await tryApi('/risk/thresholds?scene=' + encodeURIComponent(scene));
  const box = document.getElementById('rkThBox');
  if (t.ok && t.data && t.data.scene) {
    const d = t.data;
    rkSet('rkThScene', d.scene); rkSet('rkThChallenge', d.challengeScore); rkSet('rkThLimit', d.limitScore);
    rkSet('rkThReject', d.rejectScore); rkSet('rkThReview', d.reviewScore);
    rkSet('rkThAmount', d.limitAmount != null ? yuan(d.limitAmount) : '');
    if (box) box.innerHTML = ddKv([['场景', esc(d.scene)], ['版本', esc(d.version)], ['更新时间', fmtTime(d.updatedAt)],
      ['限额金额（元）', ppFen(d.limitAmount)]]) +
      ddTable(['风险分区间','处置','说明'], [
        [`0 ~ ${d.challengeScore - 1}`, 'PASS 放行', '低风险，直接放行'],
        [`${d.challengeScore} ~ ${d.limitScore - 1}`, 'CHALLENGE 挑战', '需二次校验（短信/人脸等）'],
        [`${d.limitScore} ~ ${d.rejectScore - 1}`, 'LIMIT 限额', '降额或限额内放行'],
        [`${d.rejectScore} ~ ${d.reviewScore - 1}`, 'REJECT 拒绝', '直接拦截'],
        [`≥ ${d.reviewScore}`, 'MANUAL_REVIEW 人审', '挂起人工复核（见下方决策列表）']
      ].map(r => `<tr><td class="pp-mono">${esc(r[0])}</td><td>${rkBadge(r[1].split(' ')[0])}</td><td class="muted">${esc(r[2])}</td></tr>`).join(''), '');
  } else if (box) box.innerHTML = `<div class="box warn">阈值加载失败或该场景未配置：${esc(t.err ? t.err.message : '无数据')}</div>`;
  await rkLoadDecisions();
}
async function rkLoadDecisions() {
  lpLoading('rkDecision', 12);
  const p = new URLSearchParams({ limit: String(rkNum('rkDecisionLimit') || 100) });
  const payer = rkVal('rkDecisionPayer'); if (payer) p.set('payerId', payer);
  const r = await tryApi('/risk/decisions?' + p.toString());
  if (r.ok && Array.isArray(r.data)) {
    lpSet('rkDecision', r.data, true);
    const withF = r.data.find(d => { const f = rkJson(d.factors); return f && f.length; });
    const fb = document.getElementById('rkFactorBox');
    if (fb) fb.innerHTML = withF ? rkFactorTable(withF.factors) : ddBox('plain', '最近决策无评分因子留痕，可在「引擎与试算」发起一次试算查看因子权重。');
  } else lpError('rkDecision', 12, (r.err && r.err.message) || '返回形状异常');
}
function rkDecisionRows(rows) {
  const t = document.getElementById('rkDecisionTable'); if (!t) return;
  const w = hasPerm('risk:write');
  t.innerHTML = `<tr><th>决策 ID</th><th>付款方</th><th>商户</th><th class="num">金额(元)</th><th>场景/方式</th>
    <th class="num">风险分</th><th>处置</th><th>命中规则</th><th>影子/降级</th><th>人审</th><th>时间</th><th>操作</th></tr>` +
    (rows.length ? rows.map(d => {
      const hits = rkJson(d.hitRules) || [];
      return `<tr data-dd="riskDecision:${esc(d.id)}">
        <td class="dd-cell">${esc(d.id)}</td><td class="pp-mono">${esc(d.payerId || '—')}</td>
        <td class="pp-mono">${esc(d.merchantId || '—')}</td><td class="num">${ppFen(d.amount)}</td>
        <td>${esc(d.scene || '—')} / ${esc(d.method || '—')}</td><td class="num ${d.score ? 'neg' : ''}">${d.score != null ? d.score : 0}</td>
        <td>${rkBadge(d.action)}</td><td class="wrap muted">${hits.length ? hits.map(esc).join(', ') : '—'}</td>
        <td>${d.shadow ? '<span class="pp-tag warn">影子</span>' : ''}${d.degraded ? '<span class="pp-tag bad">降级</span>' : ''}${!d.shadow && !d.degraded ? '<span class="muted">—</span>' : ''}</td>
        <td>${d.reviewStatus ? rkBadge(d.reviewStatus) : '<span class="muted">—</span>'}</td>
        <td class="muted">${fmtTime(d.createdAt)}</td>
        <td>${(w && /REVIEW/i.test(d.action || '') && !/APPROVED|REJECTED/.test(d.reviewStatus || '')) ?
          `<button class="btn small" data-rk-review="${esc(d.id)}" data-approve="1">批准</button>
           <button class="btn small danger" data-rk-review="${esc(d.id)}" data-approve="0">驳回</button>` : '<span class="muted">—</span>'}</td></tr>`;
    }).join('') : emptyRow(12, '暂无决策留痕'));
}
async function rkThresholdSave() {
  let d;
  try {
    d = { scene: rkVal('rkThScene') || 'PAY', challengeScore: rkNum('rkThChallenge'), limitScore: rkNum('rkThLimit'),
      rejectScore: rkNum('rkThReject'), reviewScore: rkNum('rkThReview'), limitAmount: rkFen('rkThAmount') };
    if ([d.challengeScore, d.limitScore, d.rejectScore, d.reviewScore].some(x => x == null)) throw new Error('请填写全部四个分档阈值');
    if (!(d.challengeScore <= d.limitScore && d.limitScore <= d.rejectScore && d.rejectScore <= d.reviewScore))
      throw new Error('阈值必须递增：挑战 ≤ 限额 ≤ 拒绝 ≤ 人审');
  } catch (e) { toast(e.message, false); return; }
  const ok = await askConfirm({ title:'修改评分阈值', okText:'确认修改',
    html:`场景 <b>${esc(d.scene)}</b> · 挑战 ${d.challengeScore} / 限额 ${d.limitScore} / 拒绝 ${d.rejectScore} / 人审 ${d.reviewScore}
      · 限额金额 ${d.limitAmount ? '¥ ' + yuan(d.limitAmount) : '—'}${rkGuard('阈值决定分级处置，直接影响拦截率。')}` });
  if (!ok) return;
  try { await sensitiveApi('/risk/thresholds', 'POST', d, `修改 <b>${esc(d.scene)}</b> 场景评分阈值`);
    toast('阈值已更新'); await loadRkScore(); } catch (e) { toast(e.message, false); }
}
async function rkReview(id, approve) {
  const ok = await askConfirm({ title: approve ? '批准人审决策' : '驳回人审决策', okText: approve ? '确认批准' : '确认驳回', danger: !approve,
    html:`决策 <b>${esc(id)}</b> 将被<b>${approve ? '批准放行' : '驳回拦截'}</b>。${rkGuard('人审裁决恒需二次认证，operator 取当前会话用户，杜绝自审自批。')}` });
  if (!ok) return;
  try { await sensitiveApi('/risk/decisions/' + encodeURIComponent(id) + '/review', 'POST', { approve },
      `${approve ? '批准' : '驳回'}风控人审决策 <b>${esc(id)}</b>`);
    toast('人审裁决已提交'); await rkLoadDecisions(); } catch (e) { toast(e.message, false); }
}

/* ================= 7.6 设备指纹 / IP 画像 / 图谱 / 行为 ================= */
async function loadRkProfile() {
  if (!hasPerm('risk:read')) { lpSet('rkBehavior', []); lpSet('rkIpSandbox', []); return; }
  const [s, sb] = await Promise.all([tryApi('/risk/profile/status'), tryApi('/risk/ip/sandbox')]);
  const box = document.getElementById('rkProfileStatus');
  if (box) {
    if (s.ok) {
      const d = s.data;
      box.innerHTML = `<div class="stat-cards">
        <div class="stat-card accent"><div class="label">设备档案 / 设备-账户关系</div><div class="value">${d.deviceCount || 0} / ${d.deviceAccountRelCount || 0}</div></div>
        <div class="stat-card"><div class="label">IP 画像</div><div class="value">${d.ipCount || 0}</div></div>
        <div class="stat-card"><div class="label">行为序列</div><div class="value">${d.behaviorCount || 0}</div></div>
        <div class="stat-card ${d.deviceProfileScoreEnabled ? 'ok' : ''}"><div class="label">设备评分</div><div class="value">${d.deviceProfileScoreEnabled ? '开' : '关'}</div></div>
        <div class="stat-card ${d.ipProfileScoreEnabled ? 'ok' : ''}"><div class="label">IP 评分</div><div class="value">${d.ipProfileScoreEnabled ? '开' : '关'}</div></div></div>
        <div class="tag-line">
          <span class="tag">设备采集 <b>${d.deviceEnabled ? '开' : '关'}</b></span><span class="tag">掩码 <b>${d.maskEnabled ? '开' : '关'}</b></span>
          <span class="tag">多账户阈值 <b>${esc(d.multiAccountThreshold)}</b></span><span class="tag">漂移阈值 <b>${esc(d.driftThreshold)}</b></span>
          <span class="tag">IP 多账户阈值 <b>${esc(d.ipMultiAccountThreshold)}</b></span><span class="tag">IP 历史拒绝阈值 <b>${esc(d.ipHistoryRejectThreshold)}</b></span>
          <span class="tag">IP 数据源 <b>${esc(d.ipDataSource)}</b></span><span class="tag">外部 API <b>${d.ipExternalApi ? '开' : '关（沙箱内置库）'}</b></span>
          <span class="tag">行为窗口 <b>${esc(d.behaviorWindowMillis)}ms</b></span><span class="tag">行为上限 <b>${esc(d.behaviorMaxCount)}</b></span>
          <span class="tag">留存 设备<b>${esc(d.deviceRetentionDays)}d</b>/IP<b>${esc(d.ipRetentionDays)}d</b>/行为<b>${esc(d.behaviorRetentionDays)}d</b></span></div>`;
    } else box.innerHTML = `<div class="box danger">画像开关加载失败：${esc(s.err.message)}</div>`;
  }
  lpLoading('rkIpSandbox', 5);
  if (sb.ok && Array.isArray(sb.data)) lpSet('rkIpSandbox', sb.data, true);
  else lpError('rkIpSandbox', 5, (sb.err && sb.err.message) || '返回形状异常');
  await rkLoadBehavior();
}
function rkIpSandboxRows(rows) {
  const t = document.getElementById('rkIpSandboxTable'); if (!t) return;
  t.innerHTML = `<tr><th>CIDR</th><th>地域</th><th>国家</th><th>代理</th><th>IDC</th></tr>` +
    (rows.length ? rows.map(r => `<tr><td class="pp-mono"><button class="link-btn" data-rk-ip="${esc(r.cidr.split('/')[0])}">${esc(r.cidr)}</button></td>
      <td>${esc(r.region || '—')}</td><td>${esc(r.country || '—')}</td>
      <td>${r.proxy ? '<span class="pp-tag warn">是</span>' : '<span class="pp-tag off">否</span>'}</td>
      <td>${r.idc ? '<span class="pp-tag warn">是</span>' : '<span class="pp-tag off">否</span>'}</td></tr>`).join('')
      : emptyRow(5, '暂无内置 IP 库'));
}
async function rkDeviceQuery() {
  const id = rkVal('rkDeviceId'), box = document.getElementById('rkDeviceBox'), graph = document.getElementById('rkGraphBox');
  if (!id) { toast('请输入设备指纹 ID', false); return; }
  if (box) box.innerHTML = '<div class="box plain"><span class="spinner"></span>查询设备画像…</div>';
  const [d, a] = await Promise.all([tryApi('/risk/device?deviceId=' + encodeURIComponent(id)),
    tryApi('/risk/device/accounts?deviceId=' + encodeURIComponent(id))]);
  if (box) {
    if (d.ok) {
      const x = d.data, tags = rkJson(x.tags) || [];
      box.innerHTML = ddKv([['设备 ID', `<span class="pp-mono">${esc(x.deviceId)}</span>`], ['掩码', esc(x.deviceMask)],
        ['稳定性', rkBadge(x.stability === 'STABLE' ? 'ACTIVE' : 'kind', x.stability)], ['漂移次数', x.driftCount],
        ['关联账户数', x.accountCount], ['风险分', x.riskScore], ['风险设备', x.risky ? '<span class="pp-tag bad">是</span>' : '否'],
        ['标签', tags.length ? tags.map(t => `<span class="pp-tag warn">${esc(t)}</span>`).join(' ') : '—'],
        ['首次 / 最近', `${ppMs(x.firstSeen)} / ${ppMs(x.lastSeen)}`],
        ['留存至', ppMs(x.retentionUntil)], ['已过期', x.expired ? '是' : '否'], ['说明', esc(x.detail || '—')]]);
    } else box.innerHTML = `<div class="box danger">设备查询失败：${esc(d.err.message)}</div>`;
  }
  if (graph) {
    const rels = (a.ok && Array.isArray(a.data)) ? a.data : [];
    graph.innerHTML = rels.length ? `<div class="pp-graph"><div class="pp-node hub"><b>设备</b><span class="pp-mono">${esc(id)}</span></div>
      ${rels.map(r => `<div class="pp-node"><b>${esc(r.accountId)}</b>
        <span class="muted">交易 ${r.txnCount != null ? r.txnCount : '—'} 笔 · 最近 ${ppMs(r.lastSeen)}</span></div>`).join('')}</div>
      <div class="pp-note">共 ${rels.length} 个关联账户（设备-账户图谱）；点账户可在「会员」页检索。</div>`
      : ddBox('plain', '该设备无关联账户样本（沙箱未上报或已过留存期）。');
  }
}
async function rkAccountQuery() {
  const id = rkVal('rkAccountId'), box = document.getElementById('rkAccountBox');
  if (!id) { toast('请输入账户 / 付款方 ID', false); return; }
  if (box) box.innerHTML = '<div class="box plain"><span class="spinner"></span>查询账户设备图谱…</div>';
  const r = await tryApi('/risk/account/devices?accountId=' + encodeURIComponent(id));
  if (!box) return;
  if (!r.ok) { box.innerHTML = `<div class="box danger">查询失败：${esc(r.err.message)}</div>`; return; }
  const rels = Array.isArray(r.data) ? r.data : [];
  box.innerHTML = rels.length ? `<div class="pp-graph"><div class="pp-node hub"><b>账户</b><span class="pp-mono">${esc(id)}</span></div>
    ${rels.map(x => `<div class="pp-node"><b class="pp-mono">${esc(x.deviceId)}</b>
      <span class="muted">交易 ${x.txnCount != null ? x.txnCount : '—'} 笔 · 最近 ${ppMs(x.lastSeen)}</span>
      <button class="link-btn" data-rk-dev="${esc(x.deviceId)}">查设备画像</button></div>`).join('')}</div>`
    : ddBox('plain', '该账户无设备关联样本。');
}
async function rkIpQuery() {
  const ip = rkVal('rkIp'), box = document.getElementById('rkIpBox');
  if (!ip) { toast('请输入 IP', false); return; }
  if (box) box.innerHTML = '<div class="box plain"><span class="spinner"></span>查询 IP 画像…</div>';
  const r = await tryApi('/risk/ip?ip=' + encodeURIComponent(ip));
  if (!box) return;
  if (!r.ok) { box.innerHTML = `<div class="box danger">查询失败：${esc(r.err.message)}</div>`; return; }
  const x = r.data, tags = rkJson(x.tags) || [];
  box.innerHTML = ddKv([['IP', `<span class="pp-mono">${esc(x.ip)}</span>`], ['地域', esc(x.region)], ['国家', esc(x.country)],
    ['代理', x.proxy ? '<span class="pp-tag warn">是</span>' : '否'], ['IDC', x.idc ? '<span class="pp-tag warn">是</span>' : '否'],
    ['关联账户数', x.accountCount], ['历史拒绝次数', x.rejectCount], ['风险分', x.riskScore],
    ['风险 IP', x.risky ? '<span class="pp-tag bad">是</span>' : '否'],
    ['标签', tags.length ? tags.map(t => `<span class="pp-tag warn">${esc(t)}</span>`).join(' ') : '—'],
    ['数据源', esc(x.source)], ['首次 / 最近', `${ppMs(x.firstSeen)} / ${ppMs(x.lastSeen)}`],
    ['留存至', ppMs(x.retentionUntil)], ['说明', esc(x.detail || '—')]]);
}
async function rkLoadBehavior() {
  lpLoading('rkBehavior', 9);
  const p = new URLSearchParams({ limit: String(rkNum('rkBehaviorLimit') || 100) });
  ['#rkBehaviorPayer:payerId', '#rkBehaviorDevice:deviceId', '#rkBehaviorIp:ip'].forEach(pair => {
    const [sel, key] = pair.split(':'), v = rkVal(sel.slice(1)); if (v) p.set(key, v);
  });
  const r = await tryApi('/risk/behavior?' + p.toString());
  if (r.ok && Array.isArray(r.data)) lpSet('rkBehavior', r.data, true);
  else lpError('rkBehavior', 9, (r.err && r.err.message) || '返回形状异常');
}
function rkBehaviorRows(rows) {
  const t = document.getElementById('rkBehaviorTable'); if (!t) return;
  t.innerHTML = `<tr><th>ID</th><th>付款方</th><th>设备</th><th>IP</th><th>地域</th><th>动作</th><th class="num">金额(元)</th><th>traceId</th><th>时间</th></tr>` +
    (rows.length ? rows.map(b => `<tr>
      <td>${esc(b.id)}</td><td class="pp-mono">${esc(b.payerId || '—')}</td>
      <td class="pp-mono">${b.deviceId ? `<button class="link-btn" data-rk-dev="${esc(b.deviceId)}">${esc(b.deviceId)}</button>` : '—'}</td>
      <td class="pp-mono">${b.ip ? `<button class="link-btn" data-rk-ip="${esc(b.ip)}">${esc(b.ip)}</button>` : '—'}</td>
      <td class="muted">${esc(b.region || '—')} / ${esc(b.country || '—')}</td>
      <td>${rkBadge('kind', b.action)}</td><td class="num">${ppFen(b.amount)}</td>
      <td class="muted">${esc(b.traceId || '—')}</td><td class="muted">${fmtTime(b.createdAt)}</td></tr>`).join('')
      : emptyRow(9, '暂无行为序列'));
}
async function rkBehaviorAnalyze() {
  const p = new URLSearchParams();
  ['#rkBehaviorPayer:payerId', '#rkBehaviorDevice:deviceId', '#rkBehaviorIp:ip'].forEach(pair => {
    const [sel, key] = pair.split(':'), v = rkVal(sel.slice(1)); if (v) p.set(key, v);
  });
  if (![...p.keys()].length) { toast('请至少填写付款方 / 设备 / IP 之一', false); return; }
  const r = await tryApi('/risk/behavior/analyze?' + p.toString());
  if (!r.ok) { rkBox('rkBehaviorResult', 'danger', '分析失败：' + esc(r.err.message)); return; }
  const d = r.data;
  rkBox('rkBehaviorResult', (d.geoJump || d.crossBorder || d.burst) ? 'warn' : 'ok',
    `窗口 ${esc(d.windowMillis)}ms 内 <b>${esc(d.count)}</b> 笔 / ¥ <b>${yuan(d.amount || 0)}</b>（上限 ${esc(d.maxCount)}）
     · 地域 ${esc((d.regions || []).join(', ') || '—')} · 国家 ${esc((d.countries || []).join(', ') || '—')}
     <br>地理跳变 ${d.geoJump ? '<span class="pp-tag bad">是</span>' : '否'}
     · 跨境 ${d.crossBorder ? '<span class="pp-tag bad">是</span>' : '否'}
     · 突发 ${d.burst ? '<span class="pp-tag warn">是</span>' : '否'}`);
}
async function rkRetention() {
  const ok = await askConfirm({ title:'执行画像留存期清理', okText:'确认清理',
    html:`按配置留存期（设备 / IP / 行为）清理过期画像数据。${rkGuard('清理不可逆，但不影响账务与交易数据。')}` });
  if (!ok) return;
  try { const r = await sensitiveApi('/risk/profile/retention', 'POST', {}, '执行<b>画像留存期清理</b>');
    toast('留存期清理已执行'); rkBox('rkRetentionResult', 'ok', `<span class="pp-mono">${esc(JSON.stringify(r))}</span>`);
    await loadRkProfile(); } catch (e) { toast(e.message, false); }
}

/* ================= 7.7 AML 与可疑报告 ================= */
async function loadRkAml() {
  if (!hasPerm('risk:read')) { ['rkAmlRule','rkAmlReport','rkWatch','rkScreen','rkAmlTxn'].forEach(k => lpSet(k, [])); return; }
  const [s, r] = await Promise.all([tryApi('/risk/aml/status'), tryApi('/risk/aml/rules')]);
  const box = document.getElementById('rkAmlStatus');
  if (box) {
    if (s.ok) {
      const d = s.data;
      box.innerHTML = `<div class="stat-cards">
        <div class="stat-card accent"><div class="label">AML 规则</div><div class="value">${d.ruleCount || 0}</div></div>
        <div class="stat-card warn"><div class="label">待报送 STR</div><div class="value">${d.pendingReports || 0}</div></div>
        <div class="stat-card ok"><div class="label">已报送 / 已关闭</div><div class="value">${d.submittedReports || 0} / ${d.closedReports || 0}</div></div>
        <div class="stat-card"><div class="label">监测流水 / 筛查</div><div class="value">${d.txnCount || 0} / ${d.screeningCount || 0}</div></div>
        <div class="stat-card ${d.degradedCount ? 'bad' : 'ok'}"><div class="label">降级次数</div><div class="value">${d.degradedCount || 0}</div></div></div>
        <div class="tag-line">
          <span class="tag">AML <b>${d.amlEnabled ? '开' : '关'}</b></span><span class="tag">硬拦截 <b>${d.hardBlockEnabled ? '开' : '关'}</b></span>
          <span class="tag">评分 <b>${d.amlScoreEnabled ? '开' : '关'}</b></span><span class="tag">名单筛查 <b>${d.screeningEnabled ? '开' : '关'}</b></span>
          <span class="tag">差错登记 <b>${d.disputeEnabled ? '开' : '关'}</b></span><span class="tag">自动扫描 <b>${d.autoScanEnabled ? '开' : '关'}</b></span>
          <span class="tag">大额阈值 <b>¥ ${yuan(d.largeAmountThreshold || 0)}</b></span><span class="tag">日累计 <b>¥ ${yuan(d.dayAmountThreshold || 0)}</b></span>
          <span class="tag">拆分笔数 <b>${esc(d.splitCount)}</b></span><span class="tag">结构化 <b>${esc(d.structuringCount)} 笔 / ${esc(d.structuringRatio)}%</b></span>
          <span class="tag">快进快出 <b>${esc(d.fastWindowMillis)}ms / ${esc(d.fastRatio)}%</b></span>
          <span class="tag">夜间时段 <b>${esc((d.nightHours || []).join(','))}</b></span>
          <span class="tag">跨境阈值 <b>¥ ${yuan(d.crossBorderAmountThreshold || 0)}</b></span>
          <span class="tag">名单源 <b>${esc(d.watchlistSource)}</b></span><span class="tag">留存 <b>${esc(d.retentionDays)}d</b></span></div>
        <div class="pp-flow" style="margin-top:10px"><span class="muted">STR 状态机</span>
          <span class="st on">PENDING 待报送</span><span class="ar">→</span>
          <span class="st">SUBMITTED 已报送</span><span class="ar">→</span>
          <span class="st">CLOSED 已关闭</span>
          <span class="muted">（PENDING 也可直接 CLOSED；每次流转写审计）</span></div>`;
    } else box.innerHTML = `<div class="box danger">AML 状态加载失败：${esc(s.err.message)}</div>`;
  }
  lpLoading('rkAmlRule', 11);
  if (r.ok && Array.isArray(r.data)) { PPRisk.amlRules = r.data; lpSet('rkAmlRule', r.data, true); }
  else lpError('rkAmlRule', 11, (r.err && r.err.message) || '返回形状异常');
  await Promise.all([rkLoadAmlReports(), rkLoadWatchlist(), rkLoadScreenings(), rkLoadAmlTxns()]);
}
function rkAmlRuleRows(rows) {
  const t = document.getElementById('rkAmlRuleTable'); if (!t) return;
  const w = hasPerm('risk:write');
  t.innerHTML = `<tr><th>规则 ID</th><th>名称</th><th>类型</th><th class="num">阈值(元)</th><th class="num">窗口(ms)</th>
    <th class="num">最少笔数</th><th class="num">比例%</th><th class="num">分值</th><th>硬规则/实时</th><th>状态</th><th>操作</th></tr>` +
    (rows.length ? rows.map(r => `<tr>
      <td><b>${esc(r.id)}</b></td><td class="wrap">${esc(r.name || '—')}</td><td>${rkBadge('kind', r.ruleType)}</td>
      <td class="num">${ppFen(r.threshold)}</td><td class="num">${r.windowMillis != null ? r.windowMillis : '—'}</td>
      <td class="num">${r.minCount != null ? r.minCount : '—'}</td><td class="num">${r.ratio != null ? r.ratio : '—'}</td>
      <td class="num">${r.score != null ? r.score : '—'}</td>
      <td>${r.hardRule ? '<span class="pp-tag bad">硬</span>' : '<span class="pp-tag off">软</span>'} ${r.realtime ? '<span class="pp-tag info">实时</span>' : '<span class="pp-tag off">批量</span>'}</td>
      <td>${rkBadge(r.status)} <span class="muted">${esc(r.compileStatus || '')}</span></td>
      <td>${w ? `<button class="btn small ghost" data-rk-aml-edit="${esc(r.id)}">编辑</button>
        <button class="btn small danger" data-rk-aml-off="${esc(r.id)}">停用</button>` : '<span class="muted">只读</span>'}</td></tr>`).join('')
      : emptyRow(11, '暂无 AML 规则'));
}
function rkAmlRuleEdit(id) {
  const ed = document.getElementById('rkAmlRuleEditor'); if (!ed) return;
  const r = id ? PPRisk.amlRules.find(x => String(x.id) === String(id)) : null;
  ed.hidden = false;
  document.getElementById('rkAmlRuleEditorTitle').textContent = r ? ('编辑 AML 规则 ' + r.id) : '新建 AML 规则';
  rkSet('rkAmlRuleId', r ? r.id : 'UIIA_AML_' + uuid().slice(0, 6));
  const idEl = document.getElementById('rkAmlRuleId'); if (idEl) idEl.readOnly = !!r;
  rkSet('rkAmlRuleName', r ? r.name : '');
  rkSet('rkAmlRuleType', r ? (r.ruleType || 'CUSTOM') : 'CUSTOM');
  rkSet('rkAmlRuleExpr', r ? r.conditionExpr : '');
  rkSet('rkAmlRuleThreshold', r && r.threshold != null ? yuan(r.threshold) : '');
  rkSet('rkAmlRuleWindow', r ? r.windowMillis : '');
  rkSet('rkAmlRuleMinCount', r ? r.minCount : 1);
  rkSet('rkAmlRuleRatio', r ? r.ratio : '');
  rkSet('rkAmlRuleScore', r ? r.score : 60);
  rkCheck('rkAmlRuleHard', r ? r.hardRule : false);
  rkCheck('rkAmlRuleRealtime', r ? r.realtime : false);
  ed.scrollIntoView({ block:'nearest' });
}
async function rkAmlRuleSave() {
  let r;
  try {
    r = { id: rkVal('rkAmlRuleId'), name: rkVal('rkAmlRuleName'), ruleType: rkVal('rkAmlRuleType'),
      conditionExpr: rkVal('rkAmlRuleExpr') || null, threshold: rkFen('rkAmlRuleThreshold'),
      windowMillis: rkNum('rkAmlRuleWindow'), minCount: rkNum('rkAmlRuleMinCount'), ratio: rkNum('rkAmlRuleRatio'),
      score: rkNum('rkAmlRuleScore'), hardRule: rkChecked('rkAmlRuleHard'), realtime: rkChecked('rkAmlRuleRealtime') };
    if (!r.id) throw new Error('请填写规则 ID');
    if (r.ruleType === 'CUSTOM' && !r.conditionExpr) throw new Error('CUSTOM 类型必须填写条件表达式');
  } catch (e) { toast(e.message, false); return; }
  const ok = await askConfirm({ title:'保存 AML 规则', okText:'确认保存',
    html:`规则 <b>${esc(r.id)}</b> · 类型 ${esc(r.ruleType)} · 阈值 ${r.threshold ? '¥ ' + yuan(r.threshold) : '—'} · 分值 ${r.score}
      ${r.hardRule ? '<br><b>硬规则</b>：命中直接处置（受硬拦截开关约束）' : ''}${rkGuard('AML 规则命中会生成可疑报告 STR。')}` });
  if (!ok) return;
  try { await sensitiveApi('/risk/aml/rules', 'POST', r, `保存 AML 规则 <b>${esc(r.id)}</b>`);
    toast('AML 规则已保存'); const ed = document.getElementById('rkAmlRuleEditor'); if (ed) ed.hidden = true;
    await loadRkAml(); } catch (e) { toast(e.message, false); }
}
async function rkAmlRuleDisable(id) {
  const ok = await askConfirm({ title:'停用 AML 规则', okText:'确认停用',
    html:`AML 规则 <b>${esc(id)}</b> → DISABLED。${rkGuard('停用后不再生成该类可疑报告。')}` });
  if (!ok) return;
  try { await sensitiveApi('/risk/aml/rules/' + encodeURIComponent(id) + '/disable', 'POST', {}, `停用 AML 规则 <b>${esc(id)}</b>`);
    toast('AML 规则已停用'); await loadRkAml(); } catch (e) { toast(e.message, false); }
}
async function rkLoadAmlReports() {
  lpLoading('rkAmlReport', 12);
  const p = new URLSearchParams({ limit: String(rkNum('rkAmlReportLimit') || 100) });
  ['#rkAmlReportStatus:status', '#rkAmlReportPayer:payerId', '#rkAmlReportDate:bizDate'].forEach(pair => {
    const [sel, key] = pair.split(':'); let v = rkVal(sel.slice(1));
    if (key === 'bizDate') v = v.replace(/-/g, '');
    if (v) p.set(key, v);
  });
  const r = await tryApi('/risk/aml/reports?' + p.toString());
  if (r.ok && Array.isArray(r.data)) lpSet('rkAmlReport', r.data, true);
  else lpError('rkAmlReport', 12, (r.err && r.err.message) || '返回形状异常');
}
function rkAmlReportRows(rows) {
  const t = document.getElementById('rkAmlReportTable'); if (!t) return;
  const w = hasPerm('risk:write');
  t.innerHTML = `<tr><th>报告号</th><th>付款方</th><th>商户</th><th>规则</th><th class="num">金额(元)</th><th class="num">笔数</th>
    <th>会计日</th><th class="num">分值</th><th>处置</th><th>状态</th><th>差错单</th><th>操作</th></tr>` +
    (rows.length ? rows.map(r => `<tr data-dd="amlReport:${esc(r.id)}">
      <td class="dd-cell"><b>${esc(r.reportNo || r.id)}</b></td><td class="pp-mono">${esc(r.payerId || '—')}</td>
      <td class="pp-mono">${esc(r.merchantId || '—')}</td><td>${rkBadge('kind', r.ruleType)}<br><span class="muted pp-mono">${esc(r.ruleId || '')}</span></td>
      <td class="num">${ppFen(r.txnAmount)}</td><td class="num">${r.txnCount != null ? r.txnCount : '—'}</td>
      <td class="muted">${ppBizDate(r.bizDate)}</td><td class="num">${r.score != null ? r.score : '—'}</td>
      <td>${rkBadge(r.disposition)}${r.hardRule ? ' <span class="pp-tag bad">硬</span>' : ''}</td>
      <td>${rkBadge(r.status)}</td>
      <td>${r.disputeId ? `<button class="link-btn" data-dd="dispute:${esc(r.disputeId)}">#${esc(r.disputeId)}</button>` : '<span class="muted">—</span>'}</td>
      <td>${w ? `${r.status === 'PENDING' ? `<button class="btn small" data-rk-str-submit="${esc(r.id)}">报送</button>` : ''}
        ${r.status !== 'CLOSED' ? `<button class="btn small danger" data-rk-str-close="${esc(r.id)}">关闭</button>` : ''}
        <button class="btn small ghost" data-dd="amlReport:${esc(r.id)}">详情</button>` : `<button class="btn small ghost" data-dd="amlReport:${esc(r.id)}">详情</button>`}</td></tr>`).join('')
      : emptyRow(12, '暂无可疑报告'));
}
async function rkStrMove(id, act) {
  const label = act === 'submit' ? '报送监管' : '关闭报告';
  const c = await askConfirm({ title: label, okText: '确认' + label, danger: act !== 'submit',
    html:`可疑报告 <b>${esc(id)}</b> → ${act === 'submit' ? 'SUBMITTED 已报送' : 'CLOSED 已关闭'}
      ${rkGuard('STR 状态流转会写入报告审计（操作人取当前会话用户）。')}`,
    input:{ label:'备注（必填）', placeholder: act === 'submit' ? '报送渠道 / 回执编号' : '关闭原因（排除嫌疑 / 重复报告等）' } });
  if (!c) return;
  try { await sensitiveApi('/risk/aml/reports/' + encodeURIComponent(id) + '/' + act, 'POST', { note: c.value },
      `可疑报告 <b>${esc(id)}</b> ${label}`);
    toast(label + '成功'); await rkLoadAmlReports();
    if ($('#detailDrawer').open) ddShow();
  } catch (e) { toast(e.message, false); }
}
async function rkAmlScan() {
  const bizDate = rkVal('rkAmlScanDate').replace(/-/g, ''), payerId = rkVal('rkAmlScanPayer');
  const ok = await askConfirm({ title:'触发 AML 扫描', okText:'确认扫描',
    html:`会计日 <b>${bizDate ? ppBizDate(bizDate) : '全部'}</b> · 付款方 <b>${esc(payerId || '全部')}</b>
      <div class="box warn">扫描会按批量规则生成可疑报告并可能登记差错单（不自动动账）。需二次认证。</div>` });
  if (!ok) return;
  try {
    const r = await sensitiveApi('/risk/aml/scan', 'POST', { bizDate: bizDate || null, payerId: payerId || null },
      `触发 <b>AML 扫描</b>${bizDate ? '（' + ppBizDate(bizDate) + '）' : ''}`);
    toast('扫描完成');
    rkBox('rkAmlScanResult', 'ok', `<span class="pp-mono">${esc(JSON.stringify(r))}</span>`);
    await Promise.all([rkLoadAmlReports(), rkLoadAmlTxns()]);
  } catch (e) { toast(e.message, false); }
}
async function rkLoadWatchlist() {
  lpLoading('rkWatch', 9);
  const r = await tryApi('/risk/aml/watchlist');
  if (r.ok && Array.isArray(r.data)) lpSet('rkWatch', r.data, true);
  else lpError('rkWatch', 9, (r.err && r.err.message) || '返回形状异常');
}
function rkWatchRows(rows) {
  const t = document.getElementById('rkWatchTable'); if (!t) return;
  const w = hasPerm('risk:write');
  t.innerHTML = `<tr><th>ID</th><th>类型</th><th>名称</th><th>别名</th><th>国家</th><th>状态</th><th>来源</th><th>操作人</th><th>操作</th></tr>` +
    (rows.length ? rows.map(x => `<tr>
      <td>${esc(x.id)}</td><td>${rkBadge('kind', x.listType)}</td><td><b>${esc(x.name)}</b></td>
      <td class="wrap muted">${esc(x.aliases || '—')}</td><td>${esc(x.country || '—')}</td>
      <td>${rkBadge(x.status)}</td><td>${esc(x.source || '—')}</td><td>${esc(x.operator || '—')}</td>
      <td>${w && x.status !== 'REMOVED' ? `<button class="btn small danger" data-rk-watch-del="${esc(x.id)}">移除</button>` : '<span class="muted">—</span>'}</td></tr>`).join('')
      : emptyRow(9, '暂无制裁 / PEP 名单'));
}
async function rkWatchAdd() {
  const body = { listType: rkVal('rkWatchType'), name: rkVal('rkWatchName'),
    aliases: rkVal('rkWatchAliases') || null, country: rkVal('rkWatchCountry') || null };
  if (!body.name) { toast('请填写名单名称', false); return; }
  const ok = await askConfirm({ title:'新增筛查名单', okText:'确认新增',
    html:`${esc(body.listType)} · <b>${esc(body.name)}</b> · 别名 ${esc(body.aliases || '—')} · 国家 ${esc(body.country || '—')}
      ${rkGuard('命中筛查名单会生成可疑报告并可能拦截。')}` });
  if (!ok) return;
  try { await sensitiveApi('/risk/aml/watchlist', 'POST', body, `新增筛查名单 <b>${esc(body.name)}</b>`);
    toast('名单已新增'); rkSet('rkWatchName', ''); await rkLoadWatchlist(); } catch (e) { toast(e.message, false); }
}
async function rkWatchRemove(id) {
  const ok = await askConfirm({ title:'移除筛查名单', okText:'确认移除',
    html:`名单条目 <b>${esc(id)}</b> → REMOVED（保留留痕）。${rkGuard('移除后不再参与筛查命中。')}` });
  if (!ok) return;
  try { await sensitiveApi('/risk/aml/watchlist/' + encodeURIComponent(id) + '/remove', 'POST', {}, `移除筛查名单 <b>${esc(id)}</b>`);
    toast('名单已移除'); await rkLoadWatchlist(); } catch (e) { toast(e.message, false); }
}
async function rkScreen() {
  const body = { subjectType: rkVal('rkScreenType'), subject: rkVal('rkScreenSubject') };
  if (!body.subject) { toast('请填写筛查主体', false); return; }
  try {
    const d = await api('/risk/aml/screen', 'POST', body);
    rkBox('rkScreenResult', d.hit ? 'warn' : 'ok',
      `${d.hit ? '<b>命中筛查名单</b>' : '未命中'} · 主体 ${esc(body.subjectType)}=${esc(body.subject)}
       · 匹配条目 ${esc(d.matchedEntryId || d.entryId || '—')} · 匹配方式 ${esc(d.matchType || '—')}
       ${d.reportId ? `· 报告 <button class="link-btn" data-dd="amlReport:${esc(d.reportId)}">#${esc(d.reportId)}</button>` : ''}
       <div class="pp-mono" style="margin-top:6px">${esc(JSON.stringify(d))}</div>`);
    await rkLoadScreenings();
  } catch (e) { rkBox('rkScreenResult', 'danger', '筛查失败：' + esc(e.message)); }
}
async function rkLoadScreenings() {
  lpLoading('rkScreen', 8);
  const r = await tryApi('/risk/aml/screenings?limit=200');
  if (r.ok && Array.isArray(r.data)) lpSet('rkScreen', r.data, true);
  else lpError('rkScreen', 8, (r.err && r.err.message) || '返回形状异常');
}
function rkScreenRows(rows) {
  const t = document.getElementById('rkScreenTable'); if (!t) return;
  t.innerHTML = `<tr><th>ID</th><th>主体类型</th><th>主体掩码</th><th>名单范围</th><th>命中</th><th>匹配条目</th><th>匹配方式</th><th>报告 / 时间</th></tr>` +
    (rows.length ? rows.map(s => `<tr>
      <td>${esc(s.id)}</td><td>${rkBadge('kind', s.subjectType)}</td><td class="pp-mono">${esc(s.subjectMask || '—')}</td>
      <td>${esc(s.listType || '—')}</td><td>${s.hit ? '<span class="pp-tag bad">命中</span>' : '<span class="pp-tag on">未命中</span>'}</td>
      <td>${esc(s.matchedEntryId || '—')}</td><td>${esc(s.matchType || '—')}</td>
      <td class="muted">${s.reportId ? `<button class="link-btn" data-dd="amlReport:${esc(s.reportId)}">#${esc(s.reportId)}</button> · ` : ''}${fmtTime(s.createdAt)}</td></tr>`).join('')
      : emptyRow(8, '暂无筛查留痕'));
}
async function rkLoadAmlTxns() {
  lpLoading('rkAmlTxn', 11);
  const p = new URLSearchParams({ limit: String(rkNum('rkAmlTxnLimit') || 100) });
  const payer = rkVal('rkAmlTxnPayer'); if (payer) p.set('payerId', payer);
  const d = rkVal('rkAmlTxnDate').replace(/-/g, ''); if (d) p.set('bizDate', d);
  const r = await tryApi('/risk/aml/txns?' + p.toString());
  if (r.ok && Array.isArray(r.data)) lpSet('rkAmlTxn', r.data, true);
  else lpError('rkAmlTxn', 11, (r.err && r.err.message) || '返回形状异常');
}
function rkAmlTxnRows(rows) {
  const t = document.getElementById('rkAmlTxnTable'); if (!t) return;
  t.innerHTML = `<tr><th>ID</th><th>付款方</th><th>商户</th><th class="num">金额(元)</th><th>方向</th><th>场景/方式</th>
    <th>地域</th><th>国家</th><th class="num">小时</th><th>会计日</th><th>时间</th></tr>` +
    (rows.length ? rows.map(x => `<tr>
      <td>${esc(x.id)}</td><td class="pp-mono">${esc(x.payerId || '—')}</td><td class="pp-mono">${esc(x.merchantId || '—')}</td>
      <td class="num">${ppFen(x.amount)}</td><td>${rkBadge(x.direction === 'IN' ? 'ACTIVE' : 'kind', x.direction)}</td>
      <td>${esc(x.scene || '—')} / ${esc(x.method || '—')}</td><td class="muted">${esc(x.region || '—')}</td>
      <td>${esc(x.country || '—')}</td><td class="num">${x.hourOfDay != null ? x.hourOfDay : '—'}</td>
      <td class="muted">${ppBizDate(x.bizDate)}</td><td class="muted">${fmtTime(x.createdAt)}</td></tr>`).join('')
      : emptyRow(11, '暂无监测流水'));
}

/* ================= 详情抽屉渲染（复用既有 #detailDrawer） ================= */
async function ddRiskRule(id) {
  const r = await tryApi('/risk/rules');
  const rule = (r.ok && Array.isArray(r.data) ? r.data : []).find(x => String(x.id) === String(id));
  if (!rule) return ddBox('danger', `风控规则 <b>${esc(id)}</b> 未找到（可能已被回滚或停用清理）。`);
  const tags = [];
  if (rule.shadow) tags.push('<span class="pp-tag warn">影子模式</span>');
  if (/FAIL/i.test(rule.compileStatus || '')) tags.push('<span class="pp-tag bad">编译失败（护栏放行）</span>');
  let html = ddKv([['规则 ID', `<b>${esc(rule.id)}</b>`], ['名称', esc(rule.name)], ['场景', esc(rule.scene)],
    ['优先级（小者先）', rule.priority], ['动作', rkBadge(rule.action)], ['贡献分', rule.score],
    ['状态', rkBadge(rule.status)], ['版本', rule.version],
    ['灰度比例', (rule.grayPercent != null ? rule.grayPercent : 100) + '%（payerId FNV 哈希 %100）'],
    ['灰度白名单', esc(rule.whitelist || '—')], ['编译状态', esc(rule.compileStatus || '—')],
    ['编译错误', esc(rule.compileError || '—')], ['创建 / 更新', `${fmtTime(rule.createdAt)} / ${fmtTime(rule.updatedAt)}`]]) +
    (tags.length ? `<div class="pp-inline" style="margin-bottom:10px">${tags.join('')}</div>` : '');
  html += ddSec('QLExpress 条件表达式', `<div class="pp-code">${esc(rule.conditionExpr || '—')}</div>
    <div class="pp-note">${esc(RK_DSL_HINT).replace(/\n/g, '<br>')}</div>`);
  const v = await tryApi('/risk/versions?kind=RULES&limit=10');
  const vs = (v.ok && Array.isArray(v.data)) ? v.data : [];
  html += ddSec('规则集版本快照（最近 10）', ddTable(['快照','版本','状态','来源','操作人','说明','时间'], vs.map(x => `<tr>
    <td>${esc(x.id)}</td><td class="num">${esc(x.version)}</td><td>${rkBadge(x.status)}</td><td>${esc(x.source || '—')}</td>
    <td>${esc(x.operator || '—')}</td><td class="wrap muted">${esc(x.changeNote || '—')}</td><td class="muted">${fmtTime(x.createdAt)}</td></tr>`).join(''), '无版本快照'), vs.length + ' 条');
  html += ddSec('跳转', `<div class="dd-actions">
    <button class="btn small ghost" data-pp-goto="rk-rules">规则与版本</button>
    <button class="btn small ghost" data-pp-goto="rk-engine">引擎与试算</button>
    <button class="btn small ghost" data-pp-goto="rk-score">评分与处置</button></div>`);
  return html;
}
async function ddRiskDecision(id) {
  const r = await tryApi('/risk/decisions/' + encodeURIComponent(id));
  if (!r.ok || !r.data) return ddBox('danger', `风控决策 <b>${esc(id)}</b> 查询失败：${esc(r.err ? r.err.message : '返回空')}`);
  const d = r.data, hits = rkJson(d.hitRules) || [];
  let html = ddKv([['决策 ID', `<b>${esc(d.id)}</b>`], ['付款方', esc(d.payerId)], ['商户', esc(d.merchantId)],
    ['金额（元）', ppFen(d.amount)], ['场景 / 方式', `${esc(d.scene)} / ${esc(d.method)}`],
    ['渠道', esc(d.channelCode || '—')], ['卡掩码', esc(d.cardMask || '—')],
    ['设备', d.deviceId ? `<button class="link-btn" data-rk-dev="${esc(d.deviceId)}">${esc(d.deviceId)}</button>` : '—'],
    ['IP', d.ip ? `<button class="link-btn" data-rk-ip="${esc(d.ip)}">${esc(d.ip)}</button>` : '—'],
    ['风险分', `<b>${d.score != null ? d.score : 0}</b>`], ['处置', rkBadge(d.action)],
    ['是否放行', d.pass ? '<span class="pp-tag on">放行</span>' : '<span class="pp-tag bad">拦截</span>'],
    ['影子 / 降级', `${d.shadow ? '影子' : '正式'} / ${d.degraded ? '<b>降级放行</b>' : '正常'}`],
    ['规则版本', esc(d.ruleVersion)], ['原因 / 码', `${esc(d.reason || '—')} / ${esc(d.code || '—')}`],
    ['人审状态', d.reviewStatus ? rkBadge(d.reviewStatus) : '—'],
    ['业务号', d.bizNo ? esc(d.bizNo) : '—'], ['traceId', esc(d.traceId || '—')], ['时间', fmtTime(d.createdAt)]]);
  html += ddSec('命中规则', hits.length ? hits.map(h => `<span class="pp-tag info">${esc(h)}</span>`).join(' ') : ddBox('plain', '未命中规则'));
  html += ddSec('评分因子', rkFactorTable(d.factors));
  if (d.listHit) html += ddSec('名单命中', `<div class="pp-code light">${esc(typeof d.listHit === 'string' ? d.listHit : JSON.stringify(d.listHit))}</div>`);
  if (d.quotaHit) html += ddSec('限额命中', `<div class="pp-code light">${esc(typeof d.quotaHit === 'string' ? d.quotaHit : JSON.stringify(d.quotaHit))}</div>`);
  if (d.bizNo) {
    const refs = await ddResolve(d.bizNo);
    html += ddSec('关联单据', refs.length ? `<div class="dd-actions">${ddRefButtons(refs)}</div>` : ddBox('plain', '未解析出关联支付单 / 交易单'));
    html += ddSec('账务凭证', await ddVoucherCard(d.bizNo, '风控关联'));
  }
  if (d.disputeId) html += ddSec('关联差错单', `<div class="dd-actions">${ddLink('dispute', d.disputeId, '差错单 #' + d.disputeId)}</div>`);
  if (hasPerm('risk:write') && /REVIEW/i.test(d.action || '') && !/APPROVED|REJECTED/.test(d.reviewStatus || '')) {
    html += ddSec('人工复核', `<div class="dd-actions">
      <button class="btn small" data-rk-review="${esc(d.id)}" data-approve="1">批准放行</button>
      <button class="btn small danger" data-rk-review="${esc(d.id)}" data-approve="0">驳回拦截</button>
      <span class="pp-note">需二次认证；operator 取当前会话用户。</span></div>`);
  }
  return html;
}
async function ddAmlReport(id) {
  const [r, a] = await Promise.all([tryApi('/risk/aml/reports/' + encodeURIComponent(id)),
    tryApi('/risk/aml/reports/' + encodeURIComponent(id) + '/audit')]);
  if (!r.ok || !r.data) return ddBox('danger', `可疑报告 <b>${esc(id)}</b> 查询失败：${esc(r.err ? r.err.message : '返回空')}`);
  const d = r.data, det = rkJson(d.detail) || {};
  let html = ddKv([['报告号', `<b>${esc(d.reportNo || d.id)}</b>`], ['付款方', esc(d.payerId)], ['商户', esc(d.merchantId)],
    ['规则', `${esc(d.ruleId)} / ${esc(d.ruleType)}`], ['金额（元）', ppFen(d.txnAmount)], ['笔数', d.txnCount],
    ['会计日', ppBizDate(d.bizDate)], ['分值', d.score], ['处置', rkBadge(d.disposition)],
    ['硬规则', d.hardRule ? '是' : '否'], ['状态', rkBadge(d.status)],
    ['操作人', esc(d.operator || '—')], ['报送备注', esc(d.submitNote || '—')],
    ['差错单', d.disputeId ? ddLink('dispute', d.disputeId, '#' + d.disputeId) : '—'],
    ['创建时间', fmtTime(d.createdAt)]]);
  html += ddSec('STR 状态机', `<div class="pp-flow">
    <span class="st ${d.status === 'PENDING' ? 'on' : ''}">PENDING 待报送</span><span class="ar">→</span>
    <span class="st ${d.status === 'SUBMITTED' ? 'on' : ''}">SUBMITTED 已报送</span><span class="ar">→</span>
    <span class="st ${d.status === 'CLOSED' ? 'on' : ''}">CLOSED 已关闭</span></div>
    ${hasPerm('risk:write') ? `<div class="dd-actions" style="margin-top:8px">
      ${d.status === 'PENDING' ? `<button class="btn small" data-rk-str-submit="${esc(d.id)}">报送监管</button>` : ''}
      ${d.status !== 'CLOSED' ? `<button class="btn small danger" data-rk-str-close="${esc(d.id)}">关闭报告</button>` : ''}
      <span class="pp-note">需二次认证 + 备注必填。</span></div>` : ''}`);
  if (det.explain || det.observations) {
    const ob = det.observations || {};
    html += ddSec('命中解释', ddBox('warn', esc(det.explain || '—')) +
      ddTable(['观测项','值'], Object.entries(ob).map(([k, v]) => `<tr><td class="pp-mono">${esc(k)}</td>
        <td class="num">${/amount|in|out/i.test(k) && typeof v === 'number' ? esc(yuan(v)) : esc(v)}</td></tr>`).join(''), ''));
  }
  html += ddSec('原始明细', `<details><summary class="link-btn">展开 JSON</summary>
    <div class="pp-code light">${esc(typeof d.detail === 'string' ? d.detail : JSON.stringify(d.detail || {}))}</div></details>`);
  const audits = (a.ok && Array.isArray(a.data)) ? a.data : [];
  html += ddSec('报告审计', audits.length ? `<ul class="pp-tl">${audits.map(x => `<li>
    <b>${esc(x.action)}</b> ${esc(x.fromStatus || '—')} → <b>${esc(x.toStatus || '—')}</b>
    · ${esc(x.operator || '—')} · ${fmtTime(x.createdAt)}${x.note ? `<br>${esc(x.note)}` : ''}</li>`).join('')}</ul>`
    : ddBox('plain', '暂无审计记录'), audits.length + ' 条');
  html += ddSec('跳转', `<div class="dd-actions"><button class="btn small ghost" data-pp-goto="rk-aml">AML 与可疑报告</button>
    <button class="btn small ghost" data-pp-goto="disputes">差错平台</button></div>`);
  return html;
}