/* =====================================================================
 * payment-pro 管理控制台 · 清结算（IA 分组 5）+ 资金账务（IA 分组 4）运营实页
 * ---------------------------------------------------------------------
 * 端点（全部实探后落地，零后端改动）：
 *  清结算 /api/settle：cycles(GET/POST) · batches · batches/{id} · batch/run ·
 *          fee-rules(GET/POST) · fee/calc · shares?shareId · shares/summary ·
 *          shares(POST) · shares/back · withdraw(POST) · withdraw/{id} ·
 *          withdraw/{id}/reconcile · withdraw/{id}/receipt
 *  支付侧分账 /api/payments/{id}/share(s) · /api/shares/{id}[/back|/execute]
 *  账务 /api/accounting：subjects · subject-balance · subjects/{code}/balance ·
 *          accounts/{id}/subject · subject-mapping · day · days · day-cut · day-end ·
 *          day-end-report · general-ledger · detail-ledger · balance-sheet ·
 *          reserve-recon · reserve-recon/logs · fx-rate(s) · currencies ·
 *          exchange · exchange/{bizNo} · trial/by-currency；/api/accounts/currency
 * 复用：lpDef/lpSet 客户端分页 + renderPager 服务端分页、#detailDrawer（新增 10 类 kind）、
 *       askConfirm + sensitiveApi（敏感写）/ guardedApi（后端未强制 step-up 的触发类）、
 *       ppFen/ppBizDate/ppMs/ppMeter（console-nav.js 共享 helper）、yuan/badge/.pp-*。
 * 顶层只声明数据与函数；DOM 与全局依赖全部在 PPFund.init() 内（index.html 尾部按序调用）。
 * ===================================================================== */

/* ---------- 共享 helper（console-ops.js 亦复用；本文件先于其加载） ---------- */
/** 会计日输入（YYYY-MM-DD）→ 后端 ms（Asia/Shanghai 日零点，与 ReconOps/Ledger 同口径） */
const ppDayMs = v => (v === null || v === undefined || v === '') ? null
  : new Date(String(v).trim() + 'T00:00:00+08:00').getTime();
/** ms / yyyyMMdd → YYYY-MM-DD（用于过滤比对与 input 回填） */
const ppDayStr = v => {
  if (v === null || v === undefined || v === '') return '';
  const s = String(v);
  if (/^\d{8}$/.test(s)) return s.slice(0,4) + '-' + s.slice(4,6) + '-' + s.slice(6,8);
  const n = Number(s);
  if (!isFinite(n) || n <= 0) return '';
  const d = new Date(n + 8 * 3600 * 1000);
  return d.toISOString().slice(0, 10);
};
const ppNum = v => (v === null || v === undefined || v === '') ? '—' : esc(String(v));
/** 汇率定点数 → 可读小数（rate / rateScale） */
const ppRate = (r, scale) => (r === null || r === undefined) ? '—'
  : (Number(r) / (Number(scale) || 10000)).toFixed(6).replace(/0+$/, '').replace(/\.$/, '.0');
const ppBadge = (v, txt) => `<span class="badge ${esc(String(v == null ? '' : v))}">${esc(txt == null ? (v == null ? '—' : v) : txt)}</span>`;
const ppTag = (cls, txt) => `<span class="pp-tag ${cls}">${esc(txt)}</span>`;
const ppYes = (v, yes, no) => v ? ppTag('on', yes || '是') : ppTag('off', no || '否');

/** 守恒可视化：左值 vs 右值，diff=0 绿色满格；≠0 红色告警（金额单位分） */
function ppCons(label, left, right, opts) {
  opts = opts || {};
  const l = Number(left || 0), r = Number(right || 0), diff = l - r;
  const ok = diff === 0;
  const base = Math.max(Math.abs(l), Math.abs(r), 1);
  const pct = Math.min(100, Math.abs(r) / base * 100);
  const fmt = opts.raw ? (x => esc(String(x))) : (x => '¥ ' + yuan(x));
  return `<div class="pp-cons ${ok ? '' : 'bad'}">
    <div class="pp-inline" style="justify-content:space-between">
      <b>${esc(label)}</b>
      <span class="pp-tag ${ok ? 'on' : 'bad'}">${ok ? '守恒 diff = 0' : '不守恒 diff = ' + fmt(diff)}</span>
    </div>
    <div class="pp-inline" style="margin-top:6px">
      <span class="muted" style="font-size:11px;min-width:88px">${esc(opts.leftLabel || '左值')}</span>
      <b class="pp-mono">${fmt(l)}</b>
      <span class="muted" style="font-size:11px;min-width:88px">${esc(opts.rightLabel || '右值')}</span>
      <b class="pp-mono">${fmt(r)}</b>
    </div>
    <div class="pp-meter ${ok ? '' : 'bad'}" style="margin-top:6px"><i style="width:${ok ? 100 : pct.toFixed(1)}%"></i></div>
    ${opts.note ? `<div class="pp-note">${opts.note}</div>` : ''}
  </div>`;
}
/** 状态机流程条：states 为顺序数组，cur 为当前态（失败态标红） */
function ppFlow(states, cur, failStates) {
  const fail = failStates || ['FAILED', 'REJECTED', 'CANCELLED', 'ROLLED_BACK'];
  const idx = states.indexOf(cur);
  return `<div class="pp-flow">${states.map((s, i) => {
    const on = s === cur;
    const cls = on ? (fail.includes(s) ? 'st bad' : 'st on') : (idx >= 0 && i < idx ? 'st done' : 'st');
    return `${i ? '<span class="ar">→</span>' : ''}<span class="${cls}">${esc(s)}</span>`;
  }).join('')}${cur && idx < 0 ? `<span class="ar">·</span><span class="st bad">${esc(cur)}</span>` : ''}</div>`;
}
/** 写操作结果盒：已知字段做 kv，其余原文 JSON（可复制排查） */
function ppResult(el, title, data, ok) {
  const box = typeof el === 'string' ? document.getElementById(el) : el;
  if (!box) return;
  if (data instanceof Error) { box.innerHTML = `<div class="box danger"><b>${esc(title)}失败</b>：${esc(data.message)}</div>`; return; }
  const pairs = Object.entries(data || {}).filter(([k, v]) => v !== null && typeof v !== 'object' && k !== 'message');
  box.innerHTML = `<div class="box ${ok === false ? 'danger' : 'info'}"><b>${esc(title)}</b>
    ${data && data.message ? `<div style="margin-top:4px">${esc(data.message)}</div>` : ''}
    ${pairs.length ? `<div class="kv" style="margin-top:8px">${pairs.slice(0, 16).map(([k, v]) =>
      `<div><span class="k">${esc(k)}</span><span class="v pp-mono">${esc(/(amount|fee|net|gross|balance|Amount|Fee|Net|Balance)$/i.test(k) && typeof v === 'number' ? yuan(v) : v)}</span></div>`).join('')}</div>` : ''}
    <details style="margin-top:8px"><summary class="muted" style="cursor:pointer;font-size:12px">原始返回</summary>
      <div class="pp-code light">${esc(JSON.stringify(data, null, 2))}</div></details></div>`;
}
/** CSV 下载：/api/** 需 Bearer，裸链接会 401 → 带 token 取 blob 后本地保存（第 1 棒先例） */
async function ppCsv(path, filename) {
  const headers = {};
  if (token) headers['Authorization'] = 'Bearer ' + token;
  const res = await fetch('/api' + path, { headers });
  if (res.status === 401) { dropStepUp(); showLogin('会话已过期，请重新登录'); throw new Error('会话已过期，请重新登录'); }
  if (!res.ok) {
    let msg = '下载失败 ' + res.status;
    try { const j = await res.json(); if (j && j.error) msg = j.error; } catch (e) {}
    throw new Error(msg);
  }
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = filename || 'export.csv';
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 5000);
  toast('CSV 已下载：' + (filename || 'export.csv'));
}
/** 无列表端点的单据（提现单 / 分账单）：从审计留痕路径反查单号（只读，需 audit:read） */
async function ppAuditIds(path, re, size) {
  if (!hasPerm('audit:read')) return { ids: [], note: '当前角色无 audit:read，无法从审计留痕发现单号，请手工输入单号查询。' };
  const r = await tryApi('/audit?action=POST&path=' + encodeURIComponent(path) + '&size=' + (size || 100));
  if (!r.ok) return { ids: [], note: '审计留痕不可用：' + ((r.err && r.err.message) || '未知错误') };
  const items = (r.data && r.data.items) || [];
  const ids = [];
  items.forEach(i => { const m = re.exec(String(i.path || '')); if (m && !ids.includes(m[1])) ids.push(m[1]); });
  return { ids, note: `从 ${items.length} 条审计留痕（共 ${r.data.total || items.length} 条）反查到 ${ids.length} 个单号` };
}
/** 通用只读表格渲染（非分页小表） */
function ppSmallTable(id, heads, body, emptyText) {
  const t = document.getElementById(id); if (!t) return;
  t.innerHTML = `<tr>${heads.map(h => `<th>${esc(h)}</th>`).join('')}</tr>` + (body || emptyRow(heads.length, emptyText || '暂无数据'));
}
const ppVal = id => { const e = document.getElementById(id); return e ? String(e.value || '').trim() : ''; };
const ppChecked = id => { const e = document.getElementById(id); return !!(e && e.checked); };
const ppInt = (id, dflt) => { const v = ppVal(id); return v === '' ? dflt : Number(v); };
/** 金额输入（元）→ 分；空返回 null（用于可空字段） */
const ppFenIn = id => { const v = ppVal(id); return v === '' ? null : toFen(v); };
const ppFenInSigned = id => { const v = ppVal(id); return v === '' ? null : toFenSigned(v); };
/** 敏感写统一入口：二次确认 + step-up（无 token 自动弹口令再验框） */
async function ppSensitive(title, html, path, method, body, reason, okText) {
  const c = await askConfirm({ title, html, okText: okText || '确认执行' });
  if (!c) return null;
  return await sensitiveApi(path, method, body, reason || html);
}
/** 触发类（后端未强制 step-up）：二次确认 + guardedApi（若后端判敏感则自动弹框取 token 重试） */
async function ppGuarded(title, html, path, method, body, okText) {
  const c = await askConfirm({ title, html, okText: okText || '确认触发', danger: false });
  if (!c) return null;
  return await guardedApi(path, method, body, html);
}
const ppErrBox = (el, title, e) => {
  const box = typeof el === 'string' ? document.getElementById(el) : el;
  if (box) box.innerHTML = `<div class="box danger"><b>${esc(title)}失败</b>：${esc(e && e.message || e)}</div>`;
};
/** 只读 POST（试算类）：tryApi 只支持 GET，这里补一个不抛异常的 POST 包装 */
async function ppTryPost(path, body) {
  try { return { ok:true, data: await api(path, 'POST', body) }; }
  catch (e) { return { ok:false, err:e }; }
}
const ppLoadingBox = (el, text) => {
  const box = typeof el === 'string' ? document.getElementById(el) : el;
  if (box) box.innerHTML = `<div class="box plain"><span class="spinner"></span>${esc(text || '加载中…')}</div>`;
};

/* ---------- 第 3 棒补充共享 helper（非 /api 前缀端点 / 跨域 actuator / 本地导出） ---------- */
/** 幂等键：前缀 + 时间戳 + 随机段（测试数据统一 UII3 前缀，便于收尾识别） */
const ppReqId = prefix => String(prefix || 'UI') + '-' + Date.now().toString(36) + '-' +
  Math.random().toString(36).slice(2, 10);
/** 原始路径请求（/actuator、/openapi、/v3 等非 /api 前缀）：带 Bearer + step-up，形状与 api() 一致 */
async function ppRaw(path, method, body, opts) {
  opts = opts || {};
  const h = {};
  if (body !== undefined) h['Content-Type'] = 'application/json';
  if (token) h['Authorization'] = 'Bearer ' + token;
  const st = opts.stepToken || (stepUp && stepUp.expiresAt > Date.now() ? stepUp.token : null);
  if (st) h['X-Step-Up-Token'] = st;
  const res = await fetch(path, { method: method || 'GET', headers: h,
    body: body === undefined ? undefined : JSON.stringify(body) });
  let data = null;
  const txt = await res.text();
  try { data = txt ? JSON.parse(txt) : null; } catch (e) { data = { raw: txt }; }
  if (!res.ok) throw apiError(data && typeof data === 'object' ? data : { error: txt }, res.status);
  return data;
}
async function ppRawTry(path, method, body) {
  try { return { ok:true, data: await ppRaw(path, method, body) }; }
  catch (e) { return { ok:false, err:e }; }
}
/** 跨域绝对地址请求（域 actuator 1808x）：8s 超时；CORS/网络失败给出可执行诊断 */
async function ppAbs(url, method, body) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), 8000);
  try {
    const h = body === undefined ? {} : { 'Content-Type': 'application/json' };
    const res = await fetch(url, { method: method || 'GET', headers: h, signal: ctl.signal,
      body: body === undefined ? undefined : JSON.stringify(body) });
    const txt = await res.text();
    let data = null; try { data = txt ? JSON.parse(txt) : null; } catch (e) { data = { raw: txt }; }
    if (!res.ok) throw apiError(data && typeof data === 'object' ? data : { error: txt }, res.status);
    return data;
  } catch (e) {
    if (e && (e.name === 'AbortError' || e instanceof TypeError)) {
      const err = new Error('浏览器无法直连该域 actuator（跨域 CORS 未放行 / 端口不可达）：' + url);
      err.cors = true; err.url = url; throw err;
    }
    throw e;
  } finally { clearTimeout(timer); }
}
/** 复制到剪贴板（无 clipboard API 时回落 textarea + execCommand） */
async function ppCopy(text) {
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) { await navigator.clipboard.writeText(text); toast('已复制到剪贴板'); return true; }
  } catch (e) { /* 继续回落 */ }
  try {
    const ta = document.createElement('textarea');
    ta.value = text; ta.style.position = 'fixed'; ta.style.left = '-9999px';
    document.body.appendChild(ta); ta.select();
    const ok = document.execCommand('copy'); ta.remove();
    toast(ok ? '已复制到剪贴板' : '复制失败，请手工选中复制', ok); return ok;
  } catch (e) { toast('复制失败，请手工选中复制', false); return false; }
}
/** 本地文本下载（SDK 示例 / 契约 / 客户端 CSV 导出，零后端） */
function ppDlText(filename, text, mime) {
  const blob = new Blob([text], { type: (mime || 'text/plain') + ';charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = filename; document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 5000);
  toast('已下载：' + filename);
}
/** 客户端 CSV（带 BOM，Excel 中文不乱码）；rows 为二维数组 */
function ppCsvLocal(filename, heads, rows) {
  const cell = v => '"' + String(v == null ? '' : v).replace(/"/g, '""') + '"';
  const csv = [heads.map(cell).join(',')].concat((rows || []).map(r => r.map(cell).join(','))).join('\r\n');
  ppDlText(filename, '﻿' + csv, 'text/csv');
}
/** 代码块 + 复制按钮（curl / SDK 示例通用） */
function ppCode(text, label) {
  const id = 'ppc' + Math.random().toString(36).slice(2, 9);
  setTimeout(() => { const b = document.getElementById(id); if (b) b.onclick = () => ppCopy(text); }, 0);
  return `<div class="pp-inline" style="justify-content:space-between;margin-bottom:4px">
    <span class="muted" style="font-size:12px">${esc(label || '可复制命令')}</span>
    <button class="btn small ghost" id="${id}" type="button">复制</button></div>
    <div class="pp-code light">${esc(text)}</div>`;
}

/* ---------- 模块状态 ---------- */
const PPFund = {
  batches: [], feeRules: [], subjects: [], subjectBal: [], shareDetails: [], shareSummary: null,
  withdraws: [], days: [], fxRates: [], currencies: [], mcAccounts: [], reserveLogs: [],
  glPage: 1, glSize: 20, dlPage: 1, dlSize: 20,
  init() {
    /* 客户端分页注册（22 个既有表 id 不动，新增 12 个） */
    lpDef('settleBatch', { table:'settleBatchTable', timeField:'createdAt', render:ppSettleBatchRows, filters:[
      { k:'kw', label:'关键字', ph:'批次号 / 商户 ID / 失败原因', fields:['id','merchantId','failReason'] },
      { k:'status', label:'状态', type:'select', field:'status', opts:[opt('','全部'),opt('PENDING','PENDING 待结算'),opt('SETTLING','SETTLING 结算中'),opt('SETTLED','SETTLED 已结算'),opt('FAILED','FAILED 失败')] },
      { k:'day', label:'会计日', ph:'2026-11-29', test:(r,v) => ppDayStr(r.bizDate) === String(v).trim() },
      ...DATE_FILTERS ] });
    lpDef('feeRule', { table:'feeRuleTable', timeField:'updatedAt', render:ppFeeRuleRows, filters:[
      { k:'kw', label:'关键字', ph:'规则 ID / 商户 / 产品 / 方式', fields:['id','merchantId','productType','method'] },
      { k:'feeType', label:'计费类型', type:'select', field:'feeType', opts:[opt('','全部'),opt('FIXED','FIXED 固定比例'),opt('TIERED','TIERED 阶梯')] },
      { k:'status', label:'状态', type:'select', field:'status', opts:[opt('','全部'),opt('ACTIVE','ACTIVE'),opt('DISABLED','DISABLED')] } ] });
    lpDef('shareDetail', { table:'shareDetailTable', render:ppShareDetailRows, filters:[
      { k:'kw', label:'关键字', ph:'明细 ID / 收款账户 / 凭证号', fields:['id','receiverAccountId','bizNo','paymentId'] },
      { k:'type', label:'类型', type:'select', field:'type', opts:[opt('','全部'),opt('SHARE','SHARE 分账'),opt('SHARE_BACK','SHARE_BACK 回退')] },
      { k:'status', label:'状态', type:'select', field:'status', opts:[opt('','全部'),opt('SETTLED','SETTLED'),opt('PENDING','PENDING'),opt('FAILED','FAILED')] } ] });
    lpDef('withdraw', { table:'withdrawTable', render:ppWithdrawRows, filters:[
      { k:'kw', label:'关键字', ph:'提现单号 / 账户 / 商户 / requestId / 卡号', fields:['id','accountId','merchantId','requestId','bankCardNo','channelOrderNo'] },
      { k:'status', label:'状态', type:'select', field:'status', opts:[opt('','全部'),opt('PENDING','PENDING 出款中'),opt('SUCCESS','SUCCESS 成功'),opt('FAILED','FAILED 失败')] },
      ...DATE_FILTERS ] });
    lpDef('subject', { table:'subjectTable', render:ppSubjectRows, filters:[
      { k:'kw', label:'关键字', ph:'科目编码 / 名称 / 系统户', fields:['subjectCode','name','systemAccount'] },
      { k:'type', label:'类别', type:'select', field:'type', opts:[opt('','全部'),opt('ASSET','ASSET 资产'),opt('LIABILITY','LIABILITY 负债'),opt('COMMON','COMMON 共同'),opt('EQUITY','EQUITY 权益'),opt('INCOME','INCOME 收入'),opt('EXPENSE','EXPENSE 费用')] },
      { k:'level', label:'层级', type:'select', field:'level', opts:[opt('','全部'),opt('1','一级（汇总）'),opt('2','二级（明细）')] },
      { k:'sys', label:'固定映射', type:'select', opts:[opt('','全部'),opt('true','系统户固定映射'),opt('false','可映射')], test:(r,v) => String(!!r.systemFlag) === v } ] });
    lpDef('subjectBal', { table:'subjectBalTable', timeField:'updatedAt', render:ppSubjectBalRows, filters:[
      { k:'kw', label:'关键字', ph:'科目编码 / 名称', fields:['subjectCode','subjectName'] },
      { k:'type', label:'类别', type:'select', field:'type', opts:[opt('','全部'),opt('ASSET','资产'),opt('LIABILITY','负债'),opt('COMMON','共同'),opt('EQUITY','权益'),opt('INCOME','收入'),opt('EXPENSE','费用')] },
      { k:'nz', label:'发生额', type:'select', opts:[opt('','全部'),opt('true','本期有发生'),opt('false','本期零发生')], test:(r,v) => String((r.debitTotal || 0) + (r.creditTotal || 0) > 0) === v } ] });
    lpDef('acctDay', { table:'acctDayTable', timeField:'bizDate', render:ppAcctDayRows, filters:[
      { k:'status', label:'状态', type:'select', field:'status', opts:[opt('','全部'),opt('OPEN','OPEN 未日切'),opt('CUT','CUT 已日切'),opt('CLOSED','CLOSED 已日终')] },
      { k:'day', label:'会计日', ph:'2026-11-29', test:(r,v) => ppDayStr(r.bizDate) === String(v).trim() } ] });
    lpDef('reserveLog', { table:'reserveLogTable', timeField:'checkedAt', render:ppReserveLogRows, filters:[
      { k:'kw', label:'关键字', ph:'核对 ID / 渠道 / 差错单号', fields:['id','channelCode','disputeNo'] },
      { k:'pass', label:'结果', type:'select', opts:[opt('','全部'),opt('true','账实一致'),opt('false','存在差异')], test:(r,v) => String(!!r.pass) === v },
      { k:'level', label:'告警级别', type:'select', field:'level', opts:[opt('','全部'),opt('NONE','NONE'),opt('WARN','WARN'),opt('ERROR','ERROR')] },
      ...DATE_FILTERS ] });
    lpDef('fxRate', { table:'fxRateTable', timeField:'createdAt', render:ppFxRateRows, filters:[
      { k:'pair', label:'币对', ph:'CNY/USD', test:(r,v) => (r.fromCurrency + '/' + r.toCurrency).toLowerCase().includes(String(v).toLowerCase()) },
      { k:'source', label:'来源', ph:'sandbox-test / manual', fields:['source','operator'] } ] });
    lpDef('mcAcc', { table:'mcAccTable', render:ppMcAccRows, filters:[
      { k:'kw', label:'关键字', ph:'账户 / 会员 / 币种', fields:['id','memberId','memberName','currency'] },
      { k:'cur', label:'币种', ph:'USD', test:(r,v) => String(r.currency || 'CNY').toUpperCase().includes(String(v).toUpperCase()) } ] });

    /* 详情抽屉：新增 6 类 kind（既有 14 类不动） */
    Object.assign(DD_KINDS, { settleBatch:'结算批次', feeRule:'费率规则', shareOrder:'分账单',
      withdraw:'提现单', subject:'会计科目', exchange:'换汇单', reserveLog:'账实核对留痕' });
    Object.assign(DD_RENDER, { settleBatch:ddSettleBatch, feeRule:ddFeeRule, shareOrder:ddShareOrder,
      withdraw:ddWithdraw, subject:ddSubject, exchange:ddExchange, reserveLog:ddReserveLog });

    const on = (id, fn) => { const el = document.getElementById(id); if (el) el.addEventListener('click', async () => {
      const btn = el; btn.disabled = true;
      try { await fn(); } catch (e) { toast(e.message, false); } finally { btn.disabled = false; } }); };
    /* 清结算 · 周期与批次 */
    on('btnScCycleQuery', () => ppScCycleQuery());
    on('btnScCycleSave', ppScCycleSave);
    on('btnScBatchQuery', () => ppScBatchQuery());
    on('btnScBatchRun', ppScBatchRun);
    /* 清结算 · 费率 */
    on('btnSfCalc', ppSfCalc);
    on('btnSfRuleQuery', () => ppSfRuleQuery());
    on('btnSfRuleSave', ppSfRuleSave);
    /* 清结算 · 分账 */
    on('btnSsSettleQuery', () => ppSsSettleQuery());
    on('btnSsRecord', () => ppSsRecord(false));
    on('btnSsBack', () => ppSsRecord(true));
    on('btnSsPayShares', () => ppSsPayShares());
    on('btnSsShareCreate', ppSsShareCreate);
    on('btnSsRecent', ppSsRecent);
    /* 清结算 · 提现 */
    on('btnWdCreate', ppWdCreate);
    on('btnWdScan', ppWdScan);
    on('btnWdQuery', ppWdQuery);
    on('btnWdReconcile', ppWdReconcile);
    on('btnWdReceipt', ppWdReceipt);
    /* 账务 · 科目 */
    on('btnSbQuery', ppSbQuery);
    on('btnSbOne', ppSbOne);
    on('btnSmQuery', ppSmQuery);
    on('btnSmSave', ppSmSave);
    /* 账务 · 账簿报表 */
    on('btnGlQuery', () => ppGlQuery(1));
    on('btnDlQuery', () => ppDlQuery(1));
    on('btnBsQuery', ppBsQuery);
    /* 账务 · 日切 */
    on('btnDcRefresh', () => ppDcLoad());
    on('btnDcCut', () => ppDcRun('day-cut'));
    on('btnDcEnd', () => ppDcRun('day-end'));
    on('btnDerQuery', ppDerQuery);
    /* 账务 · 账实核对 */
    on('btnRlQuery', () => ppRlQuery());
    on('btnRlRun', ppRlRun);
    /* 账务 · 多币种 */
    on('btnMcOpen', ppMcOpen);
    on('btnFxQuery', ppFxQuery);
    on('btnFxSave', ppFxSave);
    on('btnExRun', ppExRun);
    on('btnExQuery', ppExQuery);
    on('btnTcQuery', ppTcQuery);

    /* 行内操作委托（避开既有 data-dd 行点击） */
    document.addEventListener('click', async ev => {
      const t = ev.target;
      if (!t.closest) return;
      const fee = t.closest('[data-fee-edit]');
      if (fee) { ev.preventDefault(); ev.stopPropagation(); ppSfRuleFill(fee.dataset.feeEdit); return; }
      const sh = t.closest('[data-share-op]');
      if (sh) { ev.preventDefault(); ev.stopPropagation(); await ppShareOp(sh.dataset.shareOp, sh.dataset.shareId); return; }
      const wd = t.closest('[data-wd-op]');
      if (wd) { ev.preventDefault(); ev.stopPropagation(); await ppWdOp(wd.dataset.wdOp, wd.dataset.wdId); return; }
      const sb = t.closest('[data-subject-bal]');
      if (sb) { ev.preventDefault(); ev.stopPropagation();
        const i = document.getElementById('sbCode'); if (i) i.value = sb.dataset.subjectBal; ppSbOne(); return; }
      const fx = t.closest('[data-fx-fill]');
      if (fx) { ev.preventDefault(); ev.stopPropagation(); ppFxFill(fx.dataset.fxFill); return; }
    });
    /* 回车查询 */
    [['scMerchant', ppScCycleQuery], ['ssSettleShareId', ppSsSettleQuery], ['ssPaymentId', ppSsPayShares],
     ['wdIdInput', ppWdQuery], ['sbCode', ppSbOne], ['smAccountId', ppSmQuery], ['exQueryBizNo', ppExQuery],
     ['glSubject', () => ppGlQuery(1)], ['dlBizNo', () => ppDlQuery(1)]].forEach(([id, fn]) => {
      const el = document.getElementById(id);
      if (el) el.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); fn(); } });
    });
  }
};

/* ===================== 5.3 清结算 · 周期与批次 ===================== */
async function loadSettleCycle() {
  if (!hasPerm('settle:read')) { lpSet('settleBatch', []); return; }
  await Promise.all([ppScCycleQuery(true), ppScBatchQuery(true)]);
}
async function ppScCycleQuery(silent) {
  const mid = ppVal('scMerchant');
  const box = document.getElementById('scCycleBox');
  if (!mid) { if (box) box.innerHTML = '<div class="box plain">请输入商户 ID 后查询结算周期（缺省 T+1，费率缺省 60bps）。</div>'; return; }
  ppLoadingBox('scCycleBox', '查询结算周期…');
  const r = await tryApi('/settle/cycles?merchantId=' + encodeURIComponent(mid));
  if (!r.ok) { ppErrBox('scCycleBox', '周期查询', r.err); return; }
  const c = r.data || {};
  const days = document.getElementById('scCycleDays'), bps = document.getElementById('scCycleFeeBps');
  if (days) days.value = c.cycleDays == null ? '' : c.cycleDays;
  if (bps) bps.value = c.feeBps == null ? '' : c.feeBps;
  if (box) box.innerHTML = `<div class="kv">
    <div><span class="k">商户</span><span class="v">${ddLink('member', c.merchantId, c.merchantId)}</span></div>
    <div><span class="k">结算周期</span><span class="v"><b>T+${ppNum(c.cycleDays)}</b></span></div>
    <div><span class="k">商户级费率(bp)</span><span class="v">${c.feeBps == null ? '未设置（走三层缺省）' : c.feeBps}</span></div>
    <div><span class="k">配置来源</span><span class="v">${ppBadge(c.status, c.status === 'DEFAULT' ? 'DEFAULT 缺省' : 'CUSTOM 商户自定义')}</span></div>
    <div><span class="k">创建 / 更新</span><span class="v">${ppMs(c.createdAt)} / ${ppMs(c.updatedAt)}</span></div></div>
    <div class="pp-note">周期决定批次归集日：T+N 表示会计日后 N 天出批；费率三层优先级 = 商户+产品+方式规则 &gt; 商户周期 feeBps &gt; 全局缺省（settle.fee.default-bps，默认 60bps）。</div>`;
  if (!silent) toast('周期已加载：T+' + ppNum(c.cycleDays));
}
async function ppScCycleSave() {
  const mid = ppVal('scMerchant');
  if (!mid) throw new Error('请先填写商户 ID');
  const days = ppInt('scCycleDays', null);
  if (days === null || !isFinite(days) || days < 0 || days > 30) throw new Error('周期天数需为 0~30 的整数（T+0 ~ T+30）');
  const bpsRaw = ppVal('scCycleFeeBps');
  const bps = bpsRaw === '' ? null : Number(bpsRaw);
  if (bps !== null && (!isFinite(bps) || bps < 0 || bps > 10000)) throw new Error('费率 bp 需为 0~10000');
  const body = { merchantId: mid, cycleDays: days, feeBps: bps };
  const r = await ppSensitive('修改商户结算周期（敏感操作）',
    `<div class="summary">商户 <b>${esc(mid)}</b> → 周期 <b>T+${days}</b>${bps === null ? '，费率清除（回落缺省）' : `，商户级费率 <b>${bps} bp</b>`}</div>
     <div class="box warn">只影响<b>新受理单</b>的计费与归集；在途单与历史批次用受理快照，不会被追溯改写。</div>`,
    '/settle/cycles', 'POST', body, `即将修改商户 ${esc(mid)} 的结算周期/费率（影响新单计费）。`, '确认修改');
  if (!r) return;
  ppResult('scCycleBox', '周期已保存', r);
  toast('结算周期已保存');
  await ppScBatchQuery(true);
}
async function ppScBatchQuery(silent) {
  lpLoading('settleBatch', 12);
  const mid = ppVal('scBatchMerchant'), day = ppDayMs(ppVal('scBatchDate'));
  const qs = [];
  if (mid) qs.push('merchantId=' + encodeURIComponent(mid));
  if (day != null) qs.push('bizDate=' + day);
  const r = await tryApi('/settle/batches' + (qs.length ? '?' + qs.join('&') : ''));
  if (!r.ok) { lpError('settleBatch', 12, r.err.message); return; }
  PPFund.batches = Array.isArray(r.data) ? r.data : [];
  lpSet('settleBatch', PPFund.batches, !!silent);
}
function ppSettleBatchRows(rows) {
  const t = document.getElementById('settleBatchTable'); if (!t) return;
  t.innerHTML = `<tr><th>批次号</th><th>商户</th><th>会计日</th><th>周期</th><th class="num">笔数</th>
    <th class="num">总额（元）</th><th class="num">手续费</th><th class="num">退款</th><th class="num">净额</th>
    <th>分片</th><th>状态</th><th>操作</th></tr>` +
    (rows.length ? rows.map(b => `<tr data-dd="settleBatch:${esc(b.id)}">
      <td class="dd-cell"><b>${esc(b.id)}</b></td>
      <td>${ddLink('member', b.merchantId, b.merchantId)}</td>
      <td>${ppBizDate(b.bizDate)}</td><td>T+${ppNum(b.cycleDays)}</td>
      <td class="num">${ppNum(b.recordCount)}</td>
      <td class="num">${ppFen(b.gross)}</td><td class="num">${ppFen(b.fee)}</td>
      <td class="num">${ppFen(b.refund)}</td><td class="num"><b>${ppFen(b.net)}</b></td>
      <td class="pp-mono">${ppNum(b.shardIndex)}/${ppNum(b.shardTotal)}</td>
      <td>${ppBadge(b.status)}${b.failReason ? `<div class="pp-note neg">${esc(b.failReason)}</div>` : ''}</td>
      <td><button class="btn small ghost" data-dd="settleBatch:${esc(b.id)}">详情</button></td></tr>`).join('')
      : emptyRow(12, '暂无结算批次（可按商户/会计日过滤，或触发跑批）'));
}
async function ppScBatchRun() {
  const day = ppDayMs(ppVal('scRunDate'));
  const si = ppInt('scRunShardIndex', 0), st = ppInt('scRunShardTotal', 1);
  if (!isFinite(si) || !isFinite(st) || st < 1 || si < 0 || si >= st) throw new Error('分片参数非法：需 0 ≤ shardIndex < shardTotal');
  const force = ppChecked('scRunForce');
  const body = { bizDate: day, shardIndex: si, shardTotal: st, force };
  const r = await ppSensitive('触发结算跑批（敏感操作）',
    `<div class="summary">会计日 <b>${day == null ? '当前会计日' : ppDayStr(day)}</b> · 分片 <b>${si}/${st}</b>${force ? ' · <b class="neg">force=true 强制重跑</b>' : ''}</div>
     <div class="box danger"><b>后果：</b>按周期归集结算记录并<b>真实划转商户净额</b>（复式记账）。幂等：同会计日同分片重复触发不重复动账；force 用于失败批次重跑。</div>`,
    '/settle/batch/run', 'POST', body, '即将触发结算跑批（真实划转商户净额，需二次认证）。', '确认跑批');
  if (!r) return;
  ppResult('scRunBox', '跑批已执行', r);
  toast('结算跑批完成');
  await ppScBatchQuery(true);
}
async function ddSettleBatch(id) {
  const r = await tryApi('/settle/batches/' + encodeURIComponent(id));
  if (!r.ok) return ddBox('danger', '批次加载失败：' + esc(r.err.message));
  const b = r.data || {};
  let html = ddKv([
    ['批次号', `<b class="pp-mono">${esc(b.id)}</b>`], ['商户', ddLink('member', b.merchantId, b.merchantId)],
    ['会计日', ppBizDate(b.bizDate)], ['周期', 'T+' + ppNum(b.cycleDays)],
    ['分片', `${ppNum(b.shardIndex)} / ${ppNum(b.shardTotal)}`], ['状态', ppBadge(b.status)],
    ['记录数', ppNum(b.recordCount)], ['创建', ppMs(b.createdAt)], ['结算完成', ppMs(b.settledAt)],
    b.failReason ? ['失败原因', `<span class="neg">${esc(b.failReason)}</span>`] : null ]);
  html += ddSec('状态机', ppFlow(['PENDING','SETTLING','SETTLED'], b.status) +
    (b.status === 'FAILED' ? '<div class="pp-note neg">FAILED 可用 force=true 重跑（幂等，不重复动账）。</div>' : ''));
  html += ddSec('金额守恒（总额 − 手续费 − 退款 = 净额）',
    ppCons('批次净额守恒', Number(b.gross || 0) - Number(b.fee || 0) - Number(b.refund || 0), Number(b.net || 0),
      { leftLabel:'gross−fee−refund', rightLabel:'net' }));
  const rec = await tryApi('/settle/records?merchantId=' + encodeURIComponent(b.merchantId || ''));
  if (rec.ok && Array.isArray(rec.data)) {
    const day = ppDayStr(b.bizDate);
    const mine = rec.data.filter(x => ppDayStr(x.createdAt) === day).slice(0, 20);
    html += ddSec('该商户结算记录（会计日匹配，前 20 条）', ddTable(
      ['结算单','类型','支付单','总额','手续费','净额','状态'],
      mine.map(x => `<tr><td>${ddLink('settle', x.id, x.id)}</td><td>${ppBadge(x.type)}</td>
        <td>${ddLink('payment', x.paymentId, x.paymentId)}</td><td class="num">${ppFen(x.gross)}</td>
        <td class="num">${ppFen(x.fee)}</td><td class="num">${ppFen(x.net)}</td><td>${ppBadge(x.status)}</td></tr>`).join(''),
      '该会计日无匹配结算记录'), mine.length + ' 条');
  }
  html += ddSec('关联', `<div class="pp-inline">
    <button class="btn small ghost" data-pp-goto="merchant-bill">商户账单</button>
    <button class="btn small ghost" data-pp-goto="settle">结算记录与汇总</button>
    <button class="btn small ghost" data-pp-goto="settle-fee">费率规则</button></div>`);
  return html;
}

/* ===================== 5.4 清结算 · 费率规则 ===================== */
async function loadSettleFee() {
  if (!hasPerm('settle:read')) { lpSet('feeRule', []); return; }
  await ppSfRuleQuery(true);
}
async function ppSfRuleQuery(silent) {
  lpLoading('feeRule', 11);
  const mid = ppVal('sfRuleMerchant');
  const r = await tryApi('/settle/fee-rules' + (mid ? '?merchantId=' + encodeURIComponent(mid) : ''));
  if (!r.ok) { lpError('feeRule', 11, r.err.message); return; }
  PPFund.feeRules = Array.isArray(r.data) ? r.data : [];
  lpSet('feeRule', PPFund.feeRules, !!silent);
}
function ppFeeRuleRows(rows) {
  const t = document.getElementById('feeRuleTable'); if (!t) return;
  t.innerHTML = `<tr><th>规则 ID</th><th>商户</th><th>产品</th><th>方式</th><th>计费类型</th>
    <th class="num">费率(bp)</th><th>阶梯</th><th class="num">分成(bp)</th><th>状态</th><th class="num">版本</th><th>操作</th></tr>` +
    (rows.length ? rows.map(f => `<tr data-dd="feeRule:${esc(f.id)}">
      <td class="dd-cell"><b>${esc(f.id)}</b></td><td>${ddLink('member', f.merchantId, f.merchantId)}</td>
      <td>${ppBadge('kind', f.productType)}</td><td>${ppBadge('kind', f.method)}</td>
      <td>${ppBadge(f.feeType === 'TIERED' ? 'COMBO' : 'ACTIVE', f.feeType)}</td>
      <td class="num">${f.feeBps == null ? '—' : f.feeBps}</td>
      <td class="pp-mono" style="font-size:11px">${(f.tiers || []).map(x => `${ppFen(x.min)}~${x.max == null ? '∞' : ppFen(x.max)}:${x.bps}bp`).join('<br>') || '—'}</td>
      <td class="num">${f.shareRatio == null ? '—' : f.shareRatio}</td>
      <td>${ppBadge(f.status)}</td><td class="num">${ppNum(f.version)}</td>
      <td><button class="btn small ghost" data-fee-edit="${esc(f.id)}">载入编辑</button></td></tr>`).join('')
      : emptyRow(11, '暂无费率规则（未命中规则时走商户周期 feeBps → 全局缺省 60bps）'));
}
async function ppSfCalc() {
  const amount = ppFenIn('sfCalcAmount');
  if (amount == null) throw new Error('请填写试算金额（元）');
  const body = { merchantId: ppVal('sfCalcMerchant') || null, productType: ppVal('sfCalcProduct') || null,
    method: ppVal('sfCalcMethod') || null, amount };
  ppLoadingBox('sfCalcBox', '计费试算中（只算不落库）…');
  const r = await ppTryPost('/settle/fee/calc', body);
  if (!r.ok) { ppErrBox('sfCalcBox', '计费试算', r.err); return; }
  const c = r.data || {};
  const box = document.getElementById('sfCalcBox');
  box.innerHTML = `<div class="stat-cards">
      <div class="stat-card"><div class="label">交易额（元）</div><div class="value">${yuan(c.amount || 0)}</div></div>
      <div class="stat-card accent"><div class="label">手续费（元）</div><div class="value">${yuan(c.fee || 0)}</div></div>
      <div class="stat-card ok"><div class="label">商户净额（元）</div><div class="value">${yuan(c.net || 0)}</div></div>
      <div class="stat-card"><div class="label">命中费率</div><div class="value" style="font-size:17px">${ppNum(c.feeBps)} bp</div></div>
    </div>
    ${ppCons(' amount − fee = net', Number(c.amount || 0) - Number(c.fee || 0), Number(c.net || 0), { leftLabel:'amount−fee', rightLabel:'net' })}
    <div class="kv" style="margin-top:10px">
      <div><span class="k">计费类型</span><span class="v">${ppBadge('kind', c.feeType)}</span></div>
      <div><span class="k">来源</span><span class="v">${ppTag(c.source === 'DEFAULT' ? 'off' : 'on', c.source || '—')}</span></div>
      <div><span class="k">命中规则</span><span class="v">${c.ruleId ? ddLink('feeRule', c.ruleId, c.ruleId) : '未命中（缺省）'}</span></div>
      <div><span class="k">规则版本</span><span class="v">${ppNum(c.ruleVersion)}</span></div></div>
    <div class="pp-note">只读试算：不落库、不动账、不需二次认证。来源 RULE &gt; MERCHANT &gt; DEFAULT。</div>`;
}
function ppSfRuleFill(id) {
  const f = PPFund.feeRules.find(x => x.id === id);
  if (!f) { toast('规则不在当前列表，请先查询', false); return; }
  const set = (i, v) => { const e = document.getElementById(i); if (e) e.value = v == null ? '' : v; };
  set('sfMerchant', f.merchantId); set('sfProduct', f.productType); set('sfMethod', f.method);
  set('sfType', f.feeType); set('sfBps', f.feeBps); set('sfShareRatio', f.shareRatio); set('sfStatus', f.status);
  set('sfTiers', f.tiersJson || (f.tiers ? JSON.stringify(f.tiers) : ''));
  set('sfRuleId', f.id);
  PPNav.go('settle-fee');
  toast('已载入规则 ' + f.id + '，修改后保存将生成新版本');
}
async function ppSfRuleSave() {
  const mid = ppVal('sfMerchant');
  if (!mid) throw new Error('请填写商户 ID');
  const feeType = ppVal('sfType') || 'FIXED';
  const bpsRaw = ppVal('sfBps');
  const tiersRaw = ppVal('sfTiers');
  let tiers = null;
  if (feeType === 'TIERED') {
    if (!tiersRaw) throw new Error('TIERED 阶梯计费必须填写阶梯 JSON');
    try { tiers = JSON.parse(tiersRaw); } catch (e) { throw new Error('阶梯 JSON 解析失败：' + e.message); }
    if (!Array.isArray(tiers) || !tiers.length) throw new Error('阶梯需为非空数组 [{min,max,bps}]');
    tiers.forEach(t => { if (typeof t.bps !== 'number' || t.bps < 0 || t.bps > 10000) throw new Error('阶梯 bps 需为 0~10000'); });
  } else if (bpsRaw === '' || !isFinite(Number(bpsRaw)) || Number(bpsRaw) < 0 || Number(bpsRaw) > 10000) {
    throw new Error('FIXED 固定比例需填写 0~10000 的 bp');
  }
  const shareRaw = ppVal('sfShareRatio');
  const body = { id: ppVal('sfRuleId') || null, merchantId: mid, productType: ppVal('sfProduct') || 'TRADE',
    method: ppVal('sfMethod') || 'BALANCE', feeType, feeBps: bpsRaw === '' ? null : Number(bpsRaw),
    tiers, tiersJson: tiers ? JSON.stringify(tiers) : null,
    shareRatio: shareRaw === '' ? null : Number(shareRaw), status: ppVal('sfStatus') || 'ACTIVE' };
  const r = await ppSensitive('修改费率规则（敏感操作）',
    `<div class="summary">商户 <b>${esc(mid)}</b> · ${esc(body.productType)}/${esc(body.method)} · <b>${esc(feeType)}</b>
      ${feeType === 'TIERED' ? `· 阶梯 ${tiers.length} 档` : `· <b>${body.feeBps} bp</b>`}${body.shareRatio != null ? ` · 分成 ${body.shareRatio} bp` : ''}</div>
     <div class="box warn">费率变更<b>只影响新单</b>；在途单与历史结算单用受理快照。保存生成新版本（version+1），旧版本留痕可回查。</div>
     <div class="pp-code light">${esc(JSON.stringify(body, null, 2))}</div>`,
    '/settle/fee-rules', 'POST', body, `即将修改商户 ${esc(mid)} 的费率规则（影响新单计费，需二次认证）。`, '确认保存');
  if (!r) return;
  ppResult('sfRuleBox', '费率规则已保存', r);
  toast('费率规则已保存');
  await ppSfRuleQuery(true);
}
async function ddFeeRule(id) {
  const f = PPFund.feeRules.find(x => x.id === id);
  let rule = f;
  if (!rule) {
    const r = await tryApi('/settle/fee-rules');
    if (r.ok && Array.isArray(r.data)) { PPFund.feeRules = r.data; rule = r.data.find(x => x.id === id); }
  }
  if (!rule) return ddBox('danger', '费率规则不存在或已下线：' + esc(id));
  let html = ddKv([
    ['规则 ID', `<b class="pp-mono">${esc(rule.id)}</b>`], ['商户', ddLink('member', rule.merchantId, rule.merchantId)],
    ['产品 / 方式', `${esc(rule.productType)} / ${esc(rule.method)}`], ['计费类型', ppBadge('kind', rule.feeType)],
    ['固定费率', rule.feeBps == null ? '—' : rule.feeBps + ' bp'],
    ['分成比例', rule.shareRatio == null ? '—' : rule.shareRatio + ' bp'],
    ['状态', ppBadge(rule.status)], ['版本', ppNum(rule.version)],
    ['创建 / 更新', `${ppMs(rule.createdAt)} / ${ppMs(rule.updatedAt)}`] ]);
  html += ddSec('阶梯档位', (rule.tiers || []).length ? ddTable(['下限（元）','上限（元）','费率(bp)','1000 元样例手续费'],
    rule.tiers.map(t => `<tr><td class="num">${ppFen(t.min)}</td><td class="num">${t.max == null ? '∞' : ppFen(t.max)}</td>
      <td class="num">${t.bps}</td><td class="num">${(t.min <= 100000 && (t.max == null || t.max >= 100000)) ? yuan(Math.floor(100000 * t.bps / 10000)) : '—'}</td></tr>`).join(''))
    : ddBox('plain', '非阶梯计费（FIXED 固定比例）'));
  html += ddSec('三层费率优先级', `<div class="pp-flow"><span class="st on">① 商户+产品+方式规则</span><span class="ar">→</span>
    <span class="st">② 商户周期 feeBps</span><span class="ar">→</span><span class="st">③ 全局缺省 60bps</span></div>
    <div class="pp-note">命中即止；在途单用受理快照，热更不追溯。</div>`);
  html += ddSec('操作', `<div class="pp-inline"><button class="btn small ghost" data-fee-edit="${esc(rule.id)}">载入编辑</button>
    <button class="btn small ghost" data-pp-goto="settle-fee">前往费率页试算</button></div>`);
  return html;
}

/* ===================== 5.5 清结算 · 分账明细 ===================== */
async function loadSettleSplit() {
  if (!hasPerm('settle:read')) { lpSet('shareDetail', []); return; }
  const sid = ppVal('ssSettleShareId');
  if (sid) await ppSsSettleQuery(true);
  else {
    lpSet('shareDetail', []);
    const box = document.getElementById('ssSummaryBox');
    if (box) box.innerHTML = '<div class="box plain">输入分账单号（shareId，如 SH1）查询结算侧分账明细与净额守恒；或点「最近分账单」从审计留痕发现单号。</div>';
  }
  const pid = ppVal('ssPaymentId');
  if (pid) await ppSsPayShares(true);
}
async function ppSsSettleQuery(silent) {
  const sid = ppVal('ssSettleShareId');
  if (!sid) throw new Error('请填写分账单号 shareId');
  lpLoading('shareDetail', 10);
  ppLoadingBox('ssSummaryBox', '查询分账守恒…');
  const [d, s] = await Promise.all([
    tryApi('/settle/shares?shareId=' + encodeURIComponent(sid)),
    tryApi('/settle/shares/summary?shareId=' + encodeURIComponent(sid)) ]);
  if (!d.ok) { lpError('shareDetail', 10, d.err.message); }
  else { PPFund.shareDetails = Array.isArray(d.data) ? d.data : []; lpSet('shareDetail', PPFund.shareDetails, !!silent); }
  const box = document.getElementById('ssSummaryBox');
  if (!s.ok) { ppErrBox('ssSummaryBox', '分账汇总', s.err); return; }
  const m = s.data || {}; PPFund.shareSummary = m;
  const set = (i, v) => { const e = document.getElementById(i); if (e && v != null) e.value = v; };
  set('ssMerchantId', m.merchantId); set('ssPaymentId2', m.paymentId);
  box.innerHTML = `<div class="stat-cards">
      <div class="stat-card"><div class="label">结算净额（元）</div><div class="value">${yuan(m.settleNet || 0)}</div></div>
      <div class="stat-card accent"><div class="label">已分账净额（元）</div><div class="value">${yuan(m.allocated || 0)}</div></div>
      <div class="stat-card"><div class="label">已回退（元）</div><div class="value">${yuan(m.backAmount || 0)}</div></div>
      <div class="stat-card ${m.balanced ? 'ok' : 'bad'}"><div class="label">守恒 diff（元）</div><div class="value">${yuan(m.diff || 0)}</div></div>
    </div>
    ${ppCons('Σ分账净额 = 结算净额', m.allocated, m.settleNet, { leftLabel:'Σ allocated', rightLabel:'settleNet',
      note:'超额分账被守恒护栏拒绝（400，绝不动账）；回退后 allocated 自动扣减。' })}
    <div class="kv" style="margin-top:10px">
      <div><span class="k">分账单</span><span class="v">${ddLink('shareOrder', m.shareId, m.shareId)}</span></div>
      <div><span class="k">支付单</span><span class="v">${ddLink('payment', m.paymentId, m.paymentId)}</span></div>
      <div><span class="k">商户</span><span class="v">${ddLink('member', m.merchantId, m.merchantId)}</span></div>
      <div><span class="k">分账总额 / 手续费</span><span class="v">¥ ${yuan(m.shareAmount || 0)} / ¥ ${yuan(m.shareFee || 0)}</span></div>
      <div><span class="k">回退净额</span><span class="v">¥ ${yuan(m.backNet || 0)}</span></div>
      <div><span class="k">明细笔数</span><span class="v">${ppNum(m.detailCount)}</span></div></div>`;
  if (!silent) toast(m.balanced ? '分账守恒 diff=0' : '分账不守恒 diff=' + yuan(m.diff || 0), !!m.balanced);
}
function ppShareDetailRows(rows) {
  const t = document.getElementById('shareDetailTable'); if (!t) return;
  t.innerHTML = `<tr><th>明细 ID</th><th>类型</th><th>收款账户</th><th class="num">金额（元）</th>
    <th class="num">手续费</th><th class="num">净额</th><th>状态</th><th>凭证号</th><th>支付单</th><th>创建时间</th></tr>` +
    (rows.length ? rows.map(d => `<tr>
      <td class="pp-mono">${esc(d.id)}</td>
      <td>${ppBadge(d.type === 'SHARE_BACK' ? 'REVERSE' : 'SETTLED', d.type)}</td>
      <td>${ddLink('account', d.receiverAccountId, d.receiverAccountId)}</td>
      <td class="num">${ppFen(d.amount)}</td><td class="num">${ppFen(d.fee)}</td>
      <td class="num"><b>${ppFen(d.net)}</b></td><td>${ppBadge(d.status)}</td>
      <td>${ddLink('voucher', d.bizNo, d.bizNo)}</td>
      <td>${ddLink('payment', d.paymentId, d.paymentId)}</td>
      <td class="muted">${fmtTime(d.createdAt)}</td></tr>`).join('')
      : emptyRow(10, '该分账单暂无结算侧明细'));
}
async function ppSsRecord(back) {
  const sid = ppVal('ssSettleShareId');
  if (!sid) throw new Error('请先填写分账单号 shareId');
  const amount = ppFenIn('ssAmount');
  if (amount == null) throw new Error('请填写分账金额（元）');
  const recv = ppVal('ssRecvAcc');
  if (!recv) throw new Error('请填写收款方账户 ID');
  const body = { shareId: sid, paymentId: ppVal('ssPaymentId2') || null, merchantId: ppVal('ssMerchantId') || null,
    merchantAccountId: ppVal('ssMerchantAcc') || null, receiverAccountId: recv, amount, fee: ppFenIn('ssFee') || 0 };
  const r = await ppSensitive(back ? '分账回退（敏感操作）' : '登记分账（敏感操作）',
    `<div class="summary">分账单 <b>${esc(sid)}</b> · 收款方 <b>${esc(recv)}</b> · 金额 <b>¥ ${yuan(amount)}</b> · 手续费 ¥ ${yuan(body.fee)}</div>
     <div class="box danger"><b>${back ? '回退：资金反向划转（商户回收 / 收款方扣回）' : '分账：商户账户出账 → 收款方入账'}</b>，复式记账真实动账、不可逆。
     ${back ? '回退幂等：同键重复提交返回同一明细，不重复动账。' : '守恒护栏：Σ分账净额不得超过结算净额，超额拒绝 400 且绝不动账。'}</div>`,
    back ? '/settle/shares/back' : '/settle/shares', 'POST', body,
    `即将${back ? '回退' : '登记'}分账 ¥ ${yuan(amount)}（真实动账，需二次认证）。`, back ? '确认回退' : '确认分账');
  if (!r) return;
  ppResult('ssRecordBox', back ? '分账回退已登记' : '分账已登记', r);
  toast(back ? '分账回退完成' : '分账登记完成');
  await ppSsSettleQuery(true);
}
async function ppSsPayShares(silent) {
  const pid = ppVal('ssPaymentId');
  if (!pid) throw new Error('请填写支付单号');
  ppSmallTable('ssShareOrderTable', ['分账单','类型','总额（元）','状态','明细数','执行时间','操作'], '', '加载中…');
  const r = await tryApi('/payments/' + encodeURIComponent(pid) + '/shares');
  if (!r.ok) { ppSmallTable('ssShareOrderTable', ['分账单','类型','总额（元）','状态','明细数','执行时间','操作'],
    emptyRow(7, '加载失败：' + r.err.message)); return; }
  const list = Array.isArray(r.data) ? r.data : [];
  ppSmallTable('ssShareOrderTable', ['分账单','类型','总额（元）','状态','明细数','执行时间','操作'],
    list.map(s => `<tr><td>${ddLink('shareOrder', s.id, s.id)}</td><td>${ppBadge('kind', s.shareType)}</td>
      <td class="num">${ppFen(s.totalAmount)}</td><td>${ppBadge(s.status)}</td>
      <td class="num">${(s.details || []).length}</td><td class="muted">${ppMs(s.executeAt)}</td>
      <td><button class="btn small ghost" data-share-op="detail" data-share-id="${esc(s.id)}">详情</button>
        ${s.status === 'PENDING' || s.status === 'FAILED' ? `<button class="btn small" data-share-op="execute" data-share-id="${esc(s.id)}" ${hasPerm('payment:refund') ? '' : 'hidden'}>执行</button>` : ''}
        ${s.status === 'SHARED' ? `<button class="btn small danger" data-share-op="back" data-share-id="${esc(s.id)}" ${hasPerm('payment:refund') ? '' : 'hidden'}>回退</button>` : ''}</td></tr>`).join(''),
    '该支付单尚无分账单');
  if (!silent) toast('分账单 ' + list.length + ' 个');
}
async function ppSsShareCreate() {
  const pid = ppVal('ssPaymentId');
  if (!pid) throw new Error('请先填写支付单号（分账挂在该支付单下）');
  const raw = ppVal('ssRecv');
  if (!raw) throw new Error('请填写接收方明细，每行「账户ID:金额(元)」或「账户ID:比例bp」');
  const receivers = raw.split(/\n+/).map(x => x.trim()).filter(Boolean).map(line => {
    const m = /^([^:：]+)[:：]\s*([\d.]+)\s*(bp|%)?$/i.exec(line);
    if (!m) throw new Error('接收方行格式错误：' + line);
    const accountId = m[1].trim(), v = Number(m[2]);
    if (/bp/i.test(m[3] || '')) return { accountId, ratioBps: Math.round(v) };
    return { accountId, amount: Math.round(v * 100) };
  });
  if (!receivers.length) throw new Error('接收方明细为空');
  const body = { requestId: ppVal('ssRequestId') || ('UII2-SH-' + uuid()), shareType: ppVal('ssShareType') || 'REALTIME',
    delayMs: ppInt('ssDelayMs', 0) || 0, receivers };
  const r = await ppSensitive('新建分账编排（敏感操作）',
    `<div class="summary">支付单 <b>${esc(pid)}</b> · ${esc(body.shareType)}${body.delayMs ? ` · 延迟 ${body.delayMs} ms` : ''} · 接收方 ${receivers.length} 个</div>
     <div class="box danger">分账执行时<b>真实划转</b>（商户 → 各接收方），Σ明细必须等于可分账净额，超额拒绝。requestId 幂等。</div>
     <div class="pp-code light">${esc(JSON.stringify(body, null, 2))}</div>`,
    '/payments/' + encodeURIComponent(pid) + '/share', 'POST', body,
    `即将为支付单 ${esc(pid)} 新建分账（真实动账，需二次认证）。`, '确认创建');
  if (!r) return;
  ppResult('ssCreateBox', '分账单已受理', r);
  toast('分账单已受理：' + (r.id || ''));
  await ppSsPayShares(true);
}
async function ppSsRecent() {
  ppSmallTable('ssRecentTable', ['分账单','来源审计路径','操作'], '', '扫描审计留痕…');
  const r = await ppAuditIds('/api/shares', /\/api\/shares\/([^/]+)/, 100);
  const rows = await Promise.all(r.ids.slice(0, 30).map(async id => {
    const d = await tryApi('/shares/' + encodeURIComponent(id));
    return d.ok ? d.data : { id, status: 'UNKNOWN' };
  }));
  ppSmallTable('ssRecentTable', ['分账单','支付单','总额（元）','状态','明细数','操作'],
    rows.map(s => `<tr><td>${ddLink('shareOrder', s.id, s.id)}</td><td>${ddLink('payment', s.paymentId, s.paymentId)}</td>
      <td class="num">${ppFen(s.totalAmount)}</td><td>${ppBadge(s.status)}</td><td class="num">${(s.details || []).length}</td>
      <td><button class="btn small ghost" data-share-op="detail" data-share-id="${esc(s.id)}">详情</button></td></tr>`).join(''),
    r.note || '未发现分账单');
  const note = document.getElementById('ssRecentNote');
  if (note) note.textContent = r.note || '';
}
async function ppShareOp(op, id) {
  if (op === 'detail') { ddPush('shareOrder', id); return; }
  const path = op === 'execute' ? '/shares/' + encodeURIComponent(id) + '/execute'
    : '/shares/' + encodeURIComponent(id) + '/back';
  const body = op === 'back' ? { requestId: 'UII2-SHB-' + uuid() } : {};
  const r = await ppSensitive(op === 'execute' ? '执行分账（敏感操作）' : '分账回退（敏感操作）',
    `<div class="summary">分账单 <b>${esc(id)}</b></div>
     <div class="box danger">${op === 'execute'
       ? '手动/补单驱动分账执行（outbox DEAD 后的运维兜底）：<b>真实划转</b>至各接收方，幂等不重复动账。'
       : '整单回退：各接收方<b>扣回</b>、商户回收，登记 SHARE_BACK 明细并反向复式记账；幂等，已 BACKED 再回退拒绝。'}</div>`,
    path, 'POST', body, `即将${op === 'execute' ? '执行' : '回退'}分账单 ${esc(id)}（真实动账，需二次认证）。`,
    op === 'execute' ? '确认执行' : '确认回退');
  if (!r) return;
  toast(op === 'execute' ? '分账执行已提交' : '分账回退已提交');
  ddPush('shareOrder', id);
  const pid = ppVal('ssPaymentId'); if (pid) await ppSsPayShares(true);
}
async function ddShareOrder(id) {
  const r = await tryApi('/shares/' + encodeURIComponent(id));
  if (!r.ok) return ddBox('danger', '分账单加载失败：' + esc(r.err.message));
  const s = r.data || {};
  const details = s.details || [];
  const sum = details.filter(d => d.status !== 'BACKED').reduce((a, d) => a + Number(d.amount || 0), 0);
  let html = ddKv([
    ['分账单', `<b class="pp-mono">${esc(s.id)}</b>`], ['支付单', ddLink('payment', s.paymentId, s.paymentId)],
    ['商户', ddLink('member', s.merchantId, s.merchantId)], ['商户账户', ddLink('account', s.merchantAccountId, s.merchantAccountId)],
    ['分账类型', ppBadge('kind', s.shareType)], ['状态', ppBadge(s.status)],
    ['总额（元）', `<b>${ppFen(s.totalAmount)}</b>`], ['requestId', `<span class="pp-mono">${esc(s.requestId || '—')}</span>`],
    ['受理 / 执行 / 共享 / 回退', `${ppMs(s.createdAt)} / ${ppMs(s.executeAt)} / ${ppMs(s.sharedAt)} / ${ppMs(s.backedAt)}`],
    s.failReason ? ['失败原因', `<span class="neg">${esc(s.failReason)}</span>`] : null ]);
  html += ddSec('状态机', ppFlow(['PENDING','SHARED','BACKED'], s.status));
  html += ddSec('守恒（Σ未回退明细 = 分账总额）', ppCons('分账明细守恒', sum, s.totalAmount,
    { leftLabel:'Σ details', rightLabel:'totalAmount' }));
  html += ddSec('接收方明细', ddTable(['明细','收款账户','金额（元）','状态','凭证号'],
    details.map(d => `<tr><td class="pp-mono">${esc(d.id)}</td><td>${ddLink('account', d.receiverAccountId, d.receiverAccountId)}</td>
      <td class="num">${ppFen(d.amount)}</td><td>${ppBadge(d.status)}</td><td>${ddLink('voucher', d.bizNo, d.bizNo)}</td></tr>`).join(''),
    '暂无明细'), details.length + ' 条');
  const ss = await tryApi('/settle/shares/summary?shareId=' + encodeURIComponent(id));
  if (ss.ok && ss.data && ss.data.shareId) {
    const m = ss.data;
    html += ddSec('结算侧守恒', ppCons('Σ分账净额 = 结算净额', m.allocated, m.settleNet,
      { leftLabel:'allocated', rightLabel:'settleNet', note:`回退 ¥ ${yuan(m.backAmount || 0)} · 明细 ${m.detailCount} 笔` }));
  }
  if (hasPerm('payment:refund')) {
    html += ddSec('运维操作（需二次认证）', `<div class="pp-inline">
      <button class="btn small" data-share-op="execute" data-share-id="${esc(s.id)}">执行分账</button>
      <button class="btn small danger" data-share-op="back" data-share-id="${esc(s.id)}">整单回退</button>
      <button class="btn small ghost" data-pp-goto="settle-split">结算侧分账明细</button></div>`);
  }
  return html;
}

/* ===================== 5.6 清结算 · 提现单 ===================== */
async function loadWithdraw() {
  if (!hasPerm('settle:read')) { lpSet('withdraw', []); return; }
  if (!PPFund.withdraws.length) await ppWdScan(true);
  else lpSet('withdraw', PPFund.withdraws, true);
}
async function ppWdScan(silent) {
  lpLoading('withdraw', 12);
  const r = await ppAuditIds('/api/settle/withdraw', /\/api\/settle\/withdraw\/([^/]+)/, 100);
  const note = document.getElementById('wdNote');
  if (note) note.textContent = r.note || '';
  if (!r.ids.length) {
    PPFund.withdraws = [];
    lpSet('withdraw', []);
    if (!silent) toast(r.note || '未发现提现单，请手工输入单号查询', false);
    return;
  }
  const rows = await Promise.all(r.ids.slice(0, 40).map(async id => {
    const d = await tryApi('/settle/withdraw/' + encodeURIComponent(id));
    return d.ok ? d.data : null;
  }));
  PPFund.withdraws = rows.filter(Boolean);
  lpSet('withdraw', PPFund.withdraws, !!silent);
  if (!silent) toast('已加载提现单 ' + PPFund.withdraws.length + ' 个');
}
async function ppWdQuery() {
  const id = ppVal('wdIdInput');
  if (!id) throw new Error('请填写提现单号（W…）');
  const r = await tryApi('/settle/withdraw/' + encodeURIComponent(id));
  if (!r.ok) { toast(r.err.message, false); ppErrBox('wdOpBox', '提现单查询', r.err); return; }
  const w = r.data;
  const i = PPFund.withdraws.findIndex(x => x.id === w.id);
  if (i >= 0) PPFund.withdraws[i] = w; else PPFund.withdraws.unshift(w);
  lpSet('withdraw', PPFund.withdraws, true);
  const op = document.getElementById('wdOpId'); if (op) op.value = w.id;
  ddPush('withdraw', w.id);
}
function ppWithdrawRows(rows) {
  const t = document.getElementById('withdrawTable'); if (!t) return;
  t.innerHTML = `<tr><th>提现单号</th><th>账户</th><th>商户</th><th class="num">金额（元）</th><th class="num">手续费</th>
    <th>银行卡</th><th>出金渠道</th><th>状态</th><th class="num">补单次数</th><th>requestId</th><th>创建时间</th><th>操作</th></tr>` +
    (rows.length ? rows.map(w => `<tr data-dd="withdraw:${esc(w.id)}">
      <td class="dd-cell"><b>${esc(w.id)}</b></td><td>${ddLink('account', w.accountId, w.accountId)}</td>
      <td>${ddLink('member', w.merchantId, w.merchantId)}</td>
      <td class="num"><b>${ppFen(w.amount)}</b></td><td class="num">${ppFen(w.fee)}</td>
      <td class="pp-mono">${esc(w.bankCardNo || '—')}</td>
      <td>${ppTag(w.channelCode === 'SANDBOX_BANK' ? 'warn' : 'info', w.channelCode || '—')}</td>
      <td>${ppBadge(w.status)}${w.failReason ? `<div class="pp-note neg">${esc(w.failReason)}</div>` : ''}</td>
      <td class="num">${ppNum(w.reconcileCount)}</td>
      <td class="pp-mono" style="font-size:11px">${esc(w.requestId || '—')}</td>
      <td class="muted">${fmtTime(w.createdAt)}</td>
      <td><button class="btn small ghost" data-wd-op="reconcile" data-wd-id="${esc(w.id)}" ${hasPerm('settle:write') ? '' : 'hidden'}>补单</button></td></tr>`).join('')
      : emptyRow(12, '暂无提现单（点「从审计发现」或手工输入单号；受理提现后自动入列）'));
}
async function ppWdCreate() {
  const acc = ppVal('wdAccount');
  if (!acc) throw new Error('请填写出金账户 ID');
  const amount = ppFenIn('wdAmount');
  if (amount == null) throw new Error('请填写提现金额（元）');
  const card = ppVal('wdCard');
  if (!card) throw new Error('请填写银行卡号（沙箱出款）');
  const body = { accountId: acc, amount, bankCardNo: card, requestId: ppVal('wdRequestId') || ('UII2-WD-' + uuid()),
    fee: ppFenIn('wdFee') || 0 };
  const r = await ppSensitive('受理提现出金（敏感操作）',
    `<div class="summary">账户 <b>${esc(acc)}</b> · 金额 <b>¥ ${yuan(amount)}</b> · 手续费 ¥ ${yuan(body.fee)} · 卡号 ${esc(card)}</div>
     <div class="box danger"><b>真实动账：</b>冻结 → 沙箱出款（SANDBOX_BANK，不接真实银行）→ 成功扣减余额 / 失败回滚解冻。
     requestId 幂等：同键重复提交返回同一提现单，不重扣。</div>`,
    '/settle/withdraw', 'POST', body, `即将受理提现 ¥ ${yuan(amount)}（真实动账，需二次认证）。`, '确认出金');
  if (!r) return;
  ppResult('wdCreateBox', '提现单已受理', r);
  toast('提现单 ' + (r.id || '') + ' 状态 ' + (r.status || ''));
  if (r.id) {
    const i = PPFund.withdraws.findIndex(x => x.id === r.id);
    if (i >= 0) PPFund.withdraws[i] = r; else PPFund.withdraws.unshift(r);
    lpSet('withdraw', PPFund.withdraws, true);
    const op = document.getElementById('wdOpId'); if (op) op.value = r.id;
  }
}
async function ppWdOp(op, id) {
  const wid = id || ppVal('wdOpId');
  if (!wid) throw new Error('请填写提现单号');
  if (op === 'reconcile') {
    const r = await ppSensitive('提现补单收敛（敏感操作）',
      `<div class="summary">提现单 <b>${esc(wid)}</b></div>
       <div class="box warn">向沙箱渠道回查真实状态并收敛本地状态机（幂等，可多次）；终态 SUCCESS 后不再变更，乱序回执被拒绝。</div>`,
      '/settle/withdraw/' + encodeURIComponent(wid) + '/reconcile', 'POST', {},
      `即将对提现单 ${esc(wid)} 触发补单收敛（需二次认证）。`, '确认补单');
    if (!r) return;
    ppResult('wdOpBox', '补单收敛结果', r);
  } else {
    const status = ppVal('wdReceiptStatus') || 'SUCCESS';
    const reason = ppVal('wdReceiptReason');
    const r = await ppSensitive('注入出款回执（敏感操作）',
      `<div class="summary">提现单 <b>${esc(wid)}</b> → 回执状态 <b>${esc(status)}</b>${reason ? ` · 原因 ${esc(reason)}` : ''}</div>
       <div class="box danger">模拟渠道异步回执：SUCCESS 扣减余额并清在途户；FAILED <b>回滚解冻</b>。终态后乱序回执拒绝（状态机保护）。</div>`,
      '/settle/withdraw/' + encodeURIComponent(wid) + '/receipt', 'POST', { status, reason: reason || null },
      `即将为提现单 ${esc(wid)} 注入 ${esc(status)} 回执（真实动账，需二次认证）。`, '确认注入');
    if (!r) return;
    ppResult('wdOpBox', '回执处理结果', r);
  }
  const d = await tryApi('/settle/withdraw/' + encodeURIComponent(wid));
  if (d.ok) {
    const i = PPFund.withdraws.findIndex(x => x.id === wid);
    if (i >= 0) PPFund.withdraws[i] = d.data; else PPFund.withdraws.unshift(d.data);
    lpSet('withdraw', PPFund.withdraws, true);
  }
  toast('提现单处置完成');
}
async function ppWdReconcile() { await ppWdOp('reconcile'); }
async function ppWdReceipt() { await ppWdOp('receipt'); }
async function ddWithdraw(id) {
  const r = await tryApi('/settle/withdraw/' + encodeURIComponent(id));
  if (!r.ok) return ddBox('danger', '提现单加载失败：' + esc(r.err.message));
  const w = r.data || {};
  let html = ddKv([
    ['提现单号', `<b class="pp-mono">${esc(w.id)}</b>`], ['账户', ddLink('account', w.accountId, w.accountId)],
    ['商户', ddLink('member', w.merchantId, w.merchantId)],
    ['金额 / 手续费（元）', `<b>${ppFen(w.amount)}</b> / ${ppFen(w.fee)}`],
    ['银行卡', `<span class="pp-mono">${esc(w.bankCardNo || '—')}</span>`],
    ['出金渠道', ppTag(w.channelCode === 'SANDBOX_BANK' ? 'warn' : 'info', w.channelCode || '—')],
    ['渠道订单号', `<span class="pp-mono">${esc(w.channelOrderNo || '—')}</span>`],
    ['状态', ppBadge(w.status)], ['补单次数', ppNum(w.reconcileCount)],
    ['requestId', `<span class="pp-mono">${esc(w.requestId || '—')}</span>`],
    ['账务凭证号', ddLink('voucher', w.bizNo, w.bizNo)],
    ['创建 / 更新', `${ppMs(w.createdAt)} / ${ppMs(w.updatedAt)}`],
    w.failReason ? ['失败原因', `<span class="neg">${esc(w.failReason)}</span>`] : null ]);
  html += ddSec('状态机', ppFlow(['PENDING','SUCCESS'], w.status) +
    '<div class="pp-note">PENDING 出款中（资金冻结在 SYS_PAYOUT）→ SUCCESS 扣减余额并清在途户；FAILED 回滚解冻。终态不可逆，乱序回执拒绝。</div>');
  const v = await ddFetchVoucher(w.bizNo);
  if (v) html += ddSec('账务凭证', await ddVoucherCard(w.bizNo, '提现'));
  if (hasPerm('settle:write')) {
    html += ddSec('运维操作（需二次认证）', `<div class="pp-inline">
      <button class="btn small" data-wd-op="reconcile" data-wd-id="${esc(w.id)}">补单收敛</button>
      <button class="btn small ghost" data-wd-op="receipt" data-wd-id="${esc(w.id)}">注入回执（用下方状态）</button>
      <button class="btn small ghost" data-pp-goto="withdraw">前往提现单页</button></div>
      <div class="pp-note">回执状态/原因在「提现单」页处置区填写后生效。</div>`);
  }
  return html;
}

/* ===================== 4.4 资金账务 · 会计科目与余额 ===================== */
const PP_SUBJ_TYPE = { ASSET:'资产', LIABILITY:'负债', COMMON:'共同', EQUITY:'权益', INCOME:'收入', EXPENSE:'费用' };
async function loadSubjects() {
  if (!hasPerm('accounting:read')) { lpSet('subject', []); lpSet('subjectBal', []); return; }
  lpLoading('subject', 9); lpLoading('subjectBal', 11);
  const [s, b] = await Promise.all([tryApi('/accounting/subjects'), tryApi('/accounting/subject-balance')]);
  if (s.ok && Array.isArray(s.data)) { PPFund.subjects = s.data; lpSet('subject', s.data, true); }
  else lpError('subject', 9, (s.err && s.err.message) || '返回形状异常');
  if (b.ok && Array.isArray(b.data)) { PPFund.subjectBal = b.data; lpSet('subjectBal', b.data, true); ppSbSum(); }
  else lpError('subjectBal', 11, (b.err && b.err.message) || '返回形状异常');
}
function ppSubjectRows(rows) {
  const t = document.getElementById('subjectTable'); if (!t) return;
  t.innerHTML = `<tr><th>科目编码</th><th>名称</th><th>类别</th><th>借贷方向</th><th>父科目</th>
    <th class="num">层级</th><th>系统户固定映射</th><th>状态</th><th>操作</th></tr>` +
    (rows.length ? rows.map(s => `<tr data-dd="subject:${esc(s.subjectCode)}">
      <td class="dd-cell"><b class="pp-mono">${esc(s.subjectCode)}</b></td><td>${esc(s.name)}</td>
      <td>${ppBadge('kind', (PP_SUBJ_TYPE[s.type] || s.type) + ' ' + s.type)}</td>
      <td>${ppTag(s.direction === 'D' ? 'info' : 'warn', s.direction === 'D' ? 'D 借' : 'C 贷')}</td>
      <td class="pp-mono">${s.parentCode ? ddLink('subject', s.parentCode, s.parentCode) : '—'}</td>
      <td class="num">${ppNum(s.level)}</td>
      <td>${s.systemAccount ? `${ppTag('on', '固定')} <span class="pp-mono">${esc(s.systemAccount)}</span>` : ppTag('off', '可映射')}</td>
      <td>${ppBadge(s.status)}</td>
      <td><button class="btn small ghost" data-subject-bal="${esc(s.subjectCode)}">余额</button></td></tr>`).join('')
      : emptyRow(9, '暂无科目'));
}
function ppSubjectBalRows(rows) {
  const t = document.getElementById('subjectBalTable'); if (!t) return;
  t.innerHTML = `<tr><th>科目</th><th>名称</th><th>类别</th><th>方向</th><th>会计日</th>
    <th class="num">期初余额</th><th class="num">本期借方</th><th class="num">本期贷方</th><th class="num">期末余额</th>
    <th class="num">分录数</th><th class="num">账户数</th></tr>` +
    (rows.length ? rows.map(b => `<tr data-dd="subject:${esc(b.subjectCode)}">
      <td class="dd-cell"><b class="pp-mono">${esc(b.subjectCode)}</b></td><td>${esc(b.subjectName || '—')}</td>
      <td>${ppBadge('kind', PP_SUBJ_TYPE[b.type] || b.type)}</td>
      <td>${ppTag(b.direction === 'D' ? 'info' : 'warn', b.direction || '—')}</td>
      <td>${ppBizDate(b.bizDate)}</td>
      <td class="num">${ppFen(b.openingBalance)}</td><td class="num">${ppFen(b.debitTotal)}</td>
      <td class="num">${ppFen(b.creditTotal)}</td><td class="num"><b>${ppFen(b.balance)}</b></td>
      <td class="num">${ppNum(b.entryCount)}</td><td class="num">${ppNum(b.accountCount)}</td></tr>`).join('')
      : emptyRow(11, '暂无科目余额（日切后生成快照）'));
}
function ppSbSum() {
  const box = document.getElementById('sbSumBox'); if (!box) return;
  const leaf = PPFund.subjectBal.filter(x => x.level === 2);
  const use = leaf.length ? leaf : PPFund.subjectBal;
  const d = use.reduce((a, x) => a + Number(x.debitTotal || 0), 0);
  const c = use.reduce((a, x) => a + Number(x.creditTotal || 0), 0);
  const bal = use.reduce((a, x) => a + Number(x.balance || 0), 0);
  box.innerHTML = `<div class="stat-cards">
      <div class="stat-card"><div class="label">科目数（明细级）</div><div class="value">${use.length}</div></div>
      <div class="stat-card accent"><div class="label">本期借方合计（元）</div><div class="value">${yuan(d)}</div></div>
      <div class="stat-card accent"><div class="label">本期贷方合计（元）</div><div class="value">${yuan(c)}</div></div>
      <div class="stat-card ${bal === 0 ? 'ok' : 'bad'}"><div class="label">Σ余额（元）</div><div class="value">${yuan(bal)}</div></div>
    </div>
    ${ppCons('试算平衡 Σ借 = Σ贷', d, c, { leftLabel:'Σ debit', rightLabel:'Σ credit' })}
    ${ppCons('资产负债守恒 Σ余额 = 0', bal, 0, { leftLabel:'Σ balance', rightLabel:'0',
      note:'余额按科目方向带符号（资产/费用借方为正，负债/权益/收入贷方为正），全科目 Σ 应为 0。' })}`;
}
async function ppSbQuery() {
  const day = ppDayMs(ppVal('sbDate'));
  lpLoading('subjectBal', 11);
  const r = await tryApi('/accounting/subject-balance' + (day != null ? '?bizDate=' + day : ''));
  if (!r.ok) { lpError('subjectBal', 11, r.err.message); return; }
  PPFund.subjectBal = Array.isArray(r.data) ? r.data : [];
  lpSet('subjectBal', PPFund.subjectBal);
  ppSbSum();
  toast('科目余额已刷新' + (day != null ? '（会计日 ' + ppDayStr(day) + '）' : '（当前会计日）'));
}
async function ppSbOne() {
  const code = ppVal('sbCode');
  if (!code) throw new Error('请填写科目编码');
  const day = ppDayMs(ppVal('sbDate'));
  ppLoadingBox('sbOneBox', '查询科目余额…');
  const r = await tryApi('/accounting/subjects/' + encodeURIComponent(code) + '/balance' + (day != null ? '?bizDate=' + day : ''));
  if (!r.ok) { ppErrBox('sbOneBox', '科目余额', r.err); return; }
  const b = r.data || {};
  document.getElementById('sbOneBox').innerHTML = `<div class="kv">
      <div><span class="k">科目</span><span class="v">${ddLink('subject', b.subjectCode, b.subjectCode + ' ' + (b.subjectName || ''))}</span></div>
      <div><span class="k">类别 / 方向</span><span class="v">${esc(PP_SUBJ_TYPE[b.type] || b.type)} / ${esc(b.direction)}</span></div>
      <div><span class="k">会计日</span><span class="v">${ppBizDate(b.bizDate)}</span></div>
      <div><span class="k">期初余额（元）</span><span class="v">${ppFen(b.openingBalance)}</span></div>
      <div><span class="k">本期借方（元）</span><span class="v">${ppFen(b.debitTotal)}</span></div>
      <div><span class="k">本期贷方（元）</span><span class="v">${ppFen(b.creditTotal)}</span></div>
      <div><span class="k">期末余额（元）</span><span class="v"><b>${ppFen(b.balance)}</b></span></div>
      <div><span class="k">分录 / 账户数</span><span class="v">${ppNum(b.entryCount)} / ${ppNum(b.accountCount)}</span></div></div>
    ${ppCons('期初 + 借 − 贷 = 期末（借方科目）', Number(b.openingBalance || 0) + Number(b.debitTotal || 0) - Number(b.creditTotal || 0), b.balance,
      { leftLabel:'期初+借−贷', rightLabel:'期末', note:'贷方科目口径为 期初 + 贷 − 借；系统户余额符号以科目方向为准。' })}`;
}
async function ppSmQuery() {
  const aid = ppVal('smAccountId');
  if (!aid) throw new Error('请填写账户 ID');
  ppLoadingBox('smCurBox', '查询账户科目映射…');
  const r = await tryApi('/accounting/accounts/' + encodeURIComponent(aid) + '/subject');
  if (!r.ok) { ppErrBox('smCurBox', '科目映射查询', r.err); return; }
  const code = typeof r.data === 'object' ? (r.data.subjectCode || JSON.stringify(r.data)) : r.data;
  const sel = document.getElementById('smSubjectCode');
  if (sel && code) sel.value = String(code);
  document.getElementById('smCurBox').innerHTML = `<div class="box info">账户 ${ddLink('account', aid, aid)} 当前映射科目：
    <b>${ddLink('subject', String(code), String(code))}</b>
    <div class="pp-note">系统户（SYS_*）为固定映射，不可改；普通账户缺省落 2001 客户余额 / 2999 其他待映射。</div></div>`;
}
async function ppSmSave() {
  const aid = ppVal('smAccountId'), code = ppVal('smSubjectCode');
  if (!aid || !code) throw new Error('请填写账户 ID 与目标科目编码');
  const subj = PPFund.subjects.find(s => s.subjectCode === code);
  if (subj && subj.systemFlag) throw new Error('科目 ' + code + ' 为系统户固定映射，不允许改挂');
  const r = await ppSensitive('修改账户科目映射（敏感操作）',
    `<div class="summary">账户 <b>${esc(aid)}</b> → 科目 <b>${esc(code)}</b>${subj ? '（' + esc(subj.name) + '）' : ''}</div>
     <div class="box warn">映射影响<b>后续分录</b>的科目归属与报表口径；历史分录不改写。系统户固定映射不可改。</div>`,
    '/accounting/subject-mapping', 'POST', { accountId: aid, subjectCode: code },
    `即将把账户 ${esc(aid)} 的科目映射改为 ${esc(code)}（需二次认证）。`, '确认映射');
  if (!r) return;
  ppResult('smCurBox', '科目映射已保存', typeof r === 'object' ? r : { result: r });
  toast('科目映射已保存');
  await ppSmQuery();
}
async function ddSubject(code) {
  const [b, s] = await Promise.all([
    tryApi('/accounting/subjects/' + encodeURIComponent(code) + '/balance'),
    tryApi('/accounting/subjects') ]);
  const meta = (s.ok && Array.isArray(s.data) ? s.data : PPFund.subjects).find(x => x.subjectCode === code) || {};
  let html = ddKv([
    ['科目编码', `<b class="pp-mono">${esc(code)}</b>`], ['名称', esc(meta.name || '—')],
    ['类别', ppBadge('kind', (PP_SUBJ_TYPE[meta.type] || meta.type || '—'))],
    ['借贷方向', ppTag(meta.direction === 'D' ? 'info' : 'warn', meta.direction === 'D' ? 'D 借' : 'C 贷')],
    ['父科目', meta.parentCode ? ddLink('subject', meta.parentCode, meta.parentCode) : '—'],
    ['层级', ppNum(meta.level)], ['系统户', meta.systemAccount ? `<span class="pp-mono">${esc(meta.systemAccount)}</span>` : '—'],
    ['固定映射', ppYes(meta.systemFlag, '是（不可改挂）', '否（可映射）')], ['状态', ppBadge(meta.status)] ]);
  if (b.ok && b.data && b.data.subjectCode) {
    const x = b.data;
    html += ddSec('当前会计日余额（' + ppBizDate(x.bizDate) + '）', `<div class="kv">
      <div><span class="k">期初（元）</span><span class="v">${ppFen(x.openingBalance)}</span></div>
      <div><span class="k">本期借方（元）</span><span class="v">${ppFen(x.debitTotal)}</span></div>
      <div><span class="k">本期贷方（元）</span><span class="v">${ppFen(x.creditTotal)}</span></div>
      <div><span class="k">期末（元）</span><span class="v"><b>${ppFen(x.balance)}</b></span></div>
      <div><span class="k">分录 / 账户数</span><span class="v">${ppNum(x.entryCount)} / ${ppNum(x.accountCount)}</span></div></div>`);
  } else html += ddSec('余额', ddBox('plain', '该科目当前会计日尚无余额快照（汇总级科目或零发生）。'));
  const dl = await tryApi('/accounting/detail-ledger?subjectCode=' + encodeURIComponent(code) + '&page=1&size=10');
  if (dl.ok && dl.data && Array.isArray(dl.data.items)) {
    html += ddSec('最近明细账（前 10 条）', ddTable(['分录','凭证号','账户','方向','金额（元）','余额后','币种','时间'],
      dl.data.items.map(e => `<tr><td class="pp-mono">${esc(e.id)}</td><td>${ddLink('voucher', e.bizNo, e.bizNo)}</td>
        <td>${ddLink('account', e.accountId, e.accountId)}</td><td>${ppTag(e.dir === 'D' ? 'info' : 'warn', e.dir)}</td>
        <td class="num">${ppFen(e.amount)}</td><td class="num">${ppFen(e.balanceAfter)}</td>
        <td>${esc(e.currency || 'CNY')}</td><td class="muted">${fmtTime(e.createdAt)}</td></tr>`).join(''),
      '暂无明细'), (dl.data.total || 0) + ' 条');
  }
  html += ddSec('关联', `<div class="pp-inline"><button class="btn small ghost" data-pp-goto="gl-reports">总账 / 明细账</button>
    <button class="btn small ghost" data-pp-goto="daycut">日切与日终批</button></div>`);
  return html;
}

/* ===================== 4.5 资金账务 · 总账 / 明细账 / 资产负债表 ===================== */
async function loadGlReports() {
  if (!hasPerm('accounting:read')) return;
  await Promise.all([ppGlQuery(1, true), ppDlQuery(1, true), ppBsQuery(true)]);
}
function ppGlQs() {
  const qs = [];
  const sc = ppVal('glSubject'), day = ppDayMs(ppVal('glDate'));
  if (sc) qs.push('subjectCode=' + encodeURIComponent(sc));
  if (day != null) qs.push('bizDate=' + day);
  return qs;
}
async function ppGlQuery(page, silent) {
  PPFund.glPage = page || 1;
  const t = document.getElementById('glTable');
  if (t) loadingRow(t, 10);
  const qs = ppGlQs().concat(['page=' + PPFund.glPage, 'size=' + PPFund.glSize]);
  const r = await tryApi('/accounting/general-ledger?' + qs.join('&'));
  if (!r.ok) { if (t) errorRow(t, 10, r.err.message); return; }
  const d = r.data || {}, items = d.items || [];
  t.innerHTML = `<tr><th>科目</th><th>名称</th><th>类别</th><th>方向</th><th>会计日</th>
    <th class="num">期初余额</th><th class="num">本期借方</th><th class="num">本期贷方</th><th class="num">期末余额</th><th class="num">分录/账户</th></tr>` +
    (items.length ? items.map(x => `<tr data-dd="subject:${esc(x.subjectCode)}">
      <td class="dd-cell"><b class="pp-mono">${esc(x.subjectCode)}</b></td><td>${esc(x.subjectName || '—')}</td>
      <td>${ppBadge('kind', PP_SUBJ_TYPE[x.type] || x.type)}</td><td>${esc(x.direction || '—')}</td>
      <td>${ppBizDate(x.bizDate)}</td><td class="num">${ppFen(x.openingBalance)}</td>
      <td class="num">${ppFen(x.debitTotal)}</td><td class="num">${ppFen(x.creditTotal)}</td>
      <td class="num"><b>${ppFen(x.closingBalance)}</b></td>
      <td class="num">${ppNum(x.entryCount)} / ${ppNum(x.accountCount)}</td></tr>`).join('')
      : emptyRow(10, '该条件无总账记录'));
  renderPager(document.getElementById('glPager'), d.page || PPFund.glPage, d.size || PPFund.glSize, d.total || 0,
    p => ppGlQuery(p), sz => { PPFund.glSize = sz; ppGlQuery(1); });
  if (!silent) toast('总账 ' + (d.total || 0) + ' 条');
}
async function ppDlQuery(page, silent) {
  PPFund.dlPage = page || 1;
  const t = document.getElementById('dlTable');
  if (t) loadingRow(t, 11);
  const qs = [];
  const add = (k, v) => { if (v !== '' && v != null) qs.push(k + '=' + encodeURIComponent(v)); };
  add('accountId', ppVal('dlAccount')); add('subjectCode', ppVal('dlSubject'));
  add('bizNo', ppVal('dlBizNo')); add('refId', ppVal('dlRefId')); add('dir', ppVal('dlDir'));
  const day = ppDayMs(ppVal('dlDate'));
  if (day != null) qs.push('bizDate=' + day);
  const from = ppDayMs(ppVal('dlFrom')), to = ppDayMs(ppVal('dlTo'));
  if (from != null) qs.push('dateFrom=' + from);
  if (to != null) qs.push('dateTo=' + to);
  qs.push('page=' + PPFund.dlPage, 'size=' + PPFund.dlSize);
  const r = await tryApi('/accounting/detail-ledger?' + qs.join('&'));
  if (!r.ok) { if (t) errorRow(t, 11, r.err.message); return; }
  const d = r.data || {}, items = d.items || [];
  t.innerHTML = `<tr><th>分录 ID</th><th>凭证号</th><th>账户</th><th>科目</th><th>方向</th>
    <th class="num">金额（元）</th><th class="num">余额后</th><th>币种</th><th class="num">汇率</th><th>摘要 / refId</th><th>会计日</th></tr>` +
    (items.length ? items.map(e => `<tr>
      <td class="pp-mono">${esc(e.id)}</td><td>${ddLink('voucher', e.bizNo, e.bizNo)}</td>
      <td>${ddLink('account', e.accountId, e.accountId)}</td>
      <td>${ddLink('subject', e.subjectCode, e.subjectCode || '—')}</td>
      <td>${ppTag(e.dir === 'D' ? 'info' : 'warn', e.dir === 'D' ? 'D 借' : 'C 贷')}</td>
      <td class="num"><b>${ppFen(e.amount)}</b></td><td class="num">${ppFen(e.balanceAfter)}</td>
      <td>${esc(e.currency || 'CNY')}</td>
      <td class="num">${e.exchangeRate != null ? ppRate(e.exchangeRate, e.rateScale) : '—'}</td>
      <td class="wrap" style="font-size:12px">${esc(e.subject || '—')}<div class="pp-mono pp-note">${esc(e.refId || '')}</div></td>
      <td>${ppBizDate(e.bizDate)}</td></tr>`).join('')
      : emptyRow(11, '该条件无明细账记录'));
  renderPager(document.getElementById('dlPager'), d.page || PPFund.dlPage, d.size || PPFund.dlSize, d.total || 0,
    p => ppDlQuery(p), sz => { PPFund.dlSize = sz; ppDlQuery(1); });
  if (!silent) toast('明细账 ' + (d.total || 0) + ' 条');
}
async function ppBsQuery(silent) {
  const day = ppDayMs(ppVal('bsDate'));
  ppLoadingBox('bsBox', '生成资产负债表…');
  const r = await tryApi('/accounting/balance-sheet' + (day != null ? '?bizDate=' + day : ''));
  if (!r.ok) { ppErrBox('bsBox', '资产负债表', r.err); return; }
  const rows = Array.isArray(r.data) ? r.data : [];
  const box = document.getElementById('bsBox');
  if (!rows.length) { box.innerHTML = '<div class="box plain">该会计日无资产负债表数据（日切后生成）。</div>'; return; }
  const groups = {};
  rows.forEach(x => { (groups[x.type] = groups[x.type] || []).push(x); });
  const sum = (arr, k) => arr.reduce((a, x) => a + Number(x[k] || 0), 0);
  const leaf = rows.filter(x => x.level === 2);
  const use = leaf.length ? leaf : rows;
  const d = sum(use, 'debitTotal'), c = sum(use, 'creditTotal'), bal = sum(use, 'balance');
  const asset = sum(groups.ASSET || [], 'balance'), liab = sum(groups.LIABILITY || [], 'balance'),
    equity = sum(groups.EQUITY || [], 'balance'), common = sum(groups.COMMON || [], 'balance');
  box.innerHTML = `<div class="stat-cards">
      <div class="stat-card"><div class="label">会计日</div><div class="value" style="font-size:17px">${ppBizDate(rows[0].bizDate)}</div></div>
      <div class="stat-card accent"><div class="label">资产（元）</div><div class="value">${yuan(asset)}</div></div>
      <div class="stat-card accent"><div class="label">负债（元）</div><div class="value">${yuan(liab)}</div></div>
      <div class="stat-card"><div class="label">权益（元）</div><div class="value">${yuan(equity)}</div></div>
      <div class="stat-card ${bal === 0 ? 'ok' : 'bad'}"><div class="label">Σ余额（元）</div><div class="value">${yuan(bal)}</div></div>
    </div>
    ${ppCons('试算平衡 Σ借 = Σ贷', d, c, { leftLabel:'Σ debit', rightLabel:'Σ credit' })}
    ${ppCons('全科目 Σ余额 = 0', bal, 0, { leftLabel:'Σ balance', rightLabel:'0',
      note:`资产 ${yuan(asset)} + 负债 ${yuan(liab)} + 共同 ${yuan(common)} + 权益 ${yuan(equity)} + 损益 ${yuan(sum(groups.INCOME || [], 'balance') + sum(groups.EXPENSE || [], 'balance'))}（带方向符号）` })}
    ${Object.keys(groups).map(k => `<div class="dd-sec"><h4>${esc(PP_SUBJ_TYPE[k] || k)} ${esc(k)}<span class="cnt">${groups[k].length} 科目</span></h4>
      ${ddTable(['科目','名称','方向','期初（元）','本期借方','本期贷方','期末（元）','分录数'],
        groups[k].map(x => `<tr><td>${ddLink('subject', x.subjectCode, x.subjectCode)}</td><td>${esc(x.subjectName || '—')}</td>
          <td>${esc(x.direction)}</td><td class="num">${ppFen(x.openingBalance)}</td><td class="num">${ppFen(x.debitTotal)}</td>
          <td class="num">${ppFen(x.creditTotal)}</td><td class="num"><b>${ppFen(x.balance)}</b></td><td class="num">${ppNum(x.entryCount)}</td></tr>`).join(''))}</div>`).join('')}`;
  if (!silent) toast('资产负债表已生成');
}

/* ===================== 4.6 资金账务 · 日切与日终批 ===================== */
async function loadDaycut() {
  if (!hasPerm('accounting:read')) { lpSet('acctDay', []); return; }
  await ppDcLoad(true);
}
async function ppDcLoad(silent) {
  ppLoadingBox('dcBox', '加载会计日…');
  lpLoading('acctDay', 10);
  const limit = ppInt('dcLimit', 20) || 20;
  const [cur, days, rep] = await Promise.all([
    tryApi('/accounting/day'), tryApi('/accounting/days?limit=' + limit),
    tryApi('/accounting/day-end-report') ]);
  const box = document.getElementById('dcBox');
  if (!cur.ok) ppErrBox('dcBox', '当前会计日', cur.err);
  else {
    const d = cur.data || {};
    const dayInput = document.getElementById('dcDate');
    if (dayInput && !dayInput.value) dayInput.value = ppDayStr(d.bizDate);
    const derInput = document.getElementById('derDate');
    if (derInput && !derInput.value) derInput.value = ppDayStr(d.bizDate);
    box.innerHTML = `<div class="stat-cards">
        <div class="stat-card accent"><div class="label">当前会计日</div><div class="value" style="font-size:19px">${ppBizDate(d.bizDate)}</div></div>
        <div class="stat-card ${d.status === 'OPEN' ? '' : 'ok'}"><div class="label">状态</div><div class="value" style="font-size:17px">${esc(d.status || '—')}</div></div>
        <div class="stat-card"><div class="label">开窗时间</div><div class="value" style="font-size:14px">${ppMs(d.openedAt)}</div></div>
        <div class="stat-card"><div class="label">日切 / 日终</div><div class="value" style="font-size:14px">${ppMs(d.cutAt)} / ${ppMs(d.closedAt)}</div></div>
      </div>
      ${ppFlow(['OPEN','CUT','CLOSED'], d.status)}
      <div class="pp-note">日切：冻结当前会计日 → 生成科目余额快照 → 推进到下一会计日（幂等 + CAS + 分布式锁）。
        日终批：试算平衡校验 + 损益结转（可重跑幂等）。<b>两者均为敏感操作，需二次认证。</b></div>`;
  }
  if (days.ok && Array.isArray(days.data)) { PPFund.days = days.data; lpSet('acctDay', days.data, !!silent); }
  else lpError('acctDay', 10, (days.err && days.err.message) || '返回形状异常');
  if (rep.ok) ppDerRender(rep.data);
  if (!silent) toast('会计日已刷新');
}
function ppAcctDayRows(rows) {
  const t = document.getElementById('acctDayTable'); if (!t) return;
  t.innerHTML = `<tr><th>会计日</th><th>状态</th><th>开窗</th><th>日切</th><th>日终</th>
    <th class="num">快照 Σ余额</th><th class="num">借方合计</th><th class="num">贷方合计</th><th class="num">账户/分录</th><th>连续性</th></tr>` +
    (rows.length ? rows.map(d => `<tr>
      <td><b>${ppBizDate(d.bizDate)}</b></td><td>${ppBadge(d.status === 'OPEN' ? 'PENDING' : 'SETTLED', d.status)}</td>
      <td class="muted">${ppMs(d.openedAt)}</td><td class="muted">${ppMs(d.cutAt)}</td><td class="muted">${ppMs(d.closedAt)}</td>
      <td class="num">${d.balanceSum == null ? '—' : ppFen(d.balanceSum)}</td>
      <td class="num">${d.totalDebit == null ? '—' : ppFen(d.totalDebit)}</td>
      <td class="num">${d.totalCredit == null ? '—' : ppFen(d.totalCredit)}</td>
      <td class="num">${ppNum(d.accounts)} / ${ppNum(d.entries)}</td>
      <td>${d.continuous == null ? '<span class="muted">—</span>' : ppYes(d.continuous, '连续', '断档')}</td></tr>`).join('')
      : emptyRow(10, '暂无会计日记录'));
}
async function ppDcRun(kind) {
  const day = ppDayMs(ppVal('dcDate'));
  const si = ppInt('dcShardIndex', 0), st = ppInt('dcShardTotal', 1);
  if (!isFinite(si) || !isFinite(st) || st < 1 || si < 0 || si >= st) throw new Error('分片参数非法：需 0 ≤ shardIndex < shardTotal');
  const body = { bizDate: day, shardIndex: si, shardTotal: st };
  const cut = kind === 'day-cut';
  const r = await ppSensitive(cut ? '执行日切（敏感操作）' : '执行日终批（敏感操作）',
    `<div class="summary">会计日 <b>${day == null ? '当前会计日' : ppDayStr(day)}</b> · 分片 <b>${si}/${st}</b></div>
     <div class="box danger">${cut
       ? '<b>日切后果：</b>冻结当前会计日、生成科目余额快照并<b>推进会计日</b>；跨日后新分录落新会计日。幂等 + CAS + 分布式锁，重复触发不重复推进。'
       : '<b>日终批后果：</b>试算平衡校验 + <b>损益结转</b>（收入/费用科目结转至 4001 损益结转，真实动账）。失败可重跑，幂等不重复结转。'}</div>
     <div class="pp-note">共享栈会计日已推进多次，请先确认当前会计日再操作；本操作需二次认证（X-Step-Up-Token）。</div>`,
    '/accounting/' + kind, 'POST', body,
    `即将执行${cut ? '日切' : '日终批'}（${day == null ? '当前会计日' : ppDayStr(day)}，需二次认证）。`, cut ? '确认日切' : '确认日终');
  if (!r) return;
  ppResult('dcOpBox', cut ? '日切已执行' : '日终批已执行', r);
  toast(cut ? '日切完成' : '日终批完成');
  await ppDcLoad(true);
}
async function ppDerQuery() {
  const day = ppDayMs(ppVal('derDate'));
  ppLoadingBox('derBox', '加载日终报告…');
  const r = await tryApi('/accounting/day-end-report' + (day != null ? '?bizDate=' + day : ''));
  if (!r.ok) { ppErrBox('derBox', '日终报告', r.err); return; }
  ppDerRender(r.data);
}
function ppDerRender(d) {
  const box = document.getElementById('derBox'); if (!box) return;
  if (!d || d.bizDate == null) { box.innerHTML = '<div class="box plain">该会计日尚无日终报告（先执行日终批）。</div>'; return; }
  box.innerHTML = `<div class="stat-cards">
      <div class="stat-card"><div class="label">会计日</div><div class="value" style="font-size:17px">${ppBizDate(d.bizDate)}</div></div>
      <div class="stat-card ${d.balanced ? 'ok' : 'bad'}"><div class="label">试算平衡</div><div class="value" style="font-size:17px">${d.balanced ? '平衡' : '不平衡'}</div></div>
      <div class="stat-card accent"><div class="label">收入合计（元）</div><div class="value">${yuan(d.incomeTotal || 0)}</div></div>
      <div class="stat-card accent"><div class="label">费用合计（元）</div><div class="value">${yuan(d.expenseTotal || 0)}</div></div>
      <div class="stat-card ${(d.profit || 0) >= 0 ? 'ok' : 'bad'}"><div class="label">本期损益（元）</div><div class="value">${yuan(d.profit || 0)}</div></div>
    </div>
    ${ppCons('Σ借 = Σ贷', d.totalDebit, d.totalCredit, { leftLabel:'totalDebit', rightLabel:'totalCredit' })}
    ${ppCons('Σ余额 = 0', d.balanceSum, 0, { leftLabel:'balanceSum', rightLabel:'0' })}
    ${ppCons('收入 − 费用 = 损益', Number(d.incomeTotal || 0) - Number(d.expenseTotal || 0), d.profit, { leftLabel:'收入−费用', rightLabel:'profit' })}
    <div class="kv" style="margin-top:10px">
      <div><span class="k">损益结转</span><span class="v">${d.transferred ? ppTag('on', '已结转') : ppTag('warn', '未结转')}</span></div>
      <div><span class="k">结转凭证</span><span class="v">${d.transferBizNo ? ddLink('voucher', d.transferBizNo, d.transferBizNo) : '—'}</span></div>
      <div><span class="k">科目 / 分录数</span><span class="v">${ppNum(d.subjectCount)} / ${ppNum(d.entryCount)}</span></div>
      <div><span class="k">历史遗留分录</span><span class="v">${ppNum(d.legacyEntryCount)}</span></div>
      <div><span class="k">执行状态</span><span class="v">${ppBadge(d.status)}</span></div>
      <div><span class="k">更新时间</span><span class="v">${ppMs(d.updatedAt)}</span></div></div>`;
}

/* ===================== 4.8 资金账务 · 账实核对日志（P1-E reserve-recon） ===================== */
async function loadReserveLogsPage() {
  if (!hasPerm('accounting:read')) { lpSet('reserveLog', []); return; }
  await ppRlQuery(true);
}
async function ppRlQuery(silent) {
  lpLoading('reserveLog', 10);
  const qs = [];
  const ch = ppVal('rlChannel'), day = ppDayMs(ppVal('rlDateFilter')), limit = ppInt('rlLimit', 200) || 200;
  if (ch) qs.push('channelCode=' + encodeURIComponent(ch));
  if (day != null) qs.push('bizDate=' + day);
  qs.push('limit=' + limit);
  const r = await tryApi('/accounting/reserve-recon/logs?' + qs.join('&'));
  if (!r.ok) { lpError('reserveLog', 10, r.err.message); return; }
  PPFund.reserveLogs = Array.isArray(r.data) ? r.data : [];
  lpSet('reserveLog', PPFund.reserveLogs, !!silent);
  ppRlStats();
  if (!silent) toast('核对留痕 ' + PPFund.reserveLogs.length + ' 条');
}
function ppReserveLogRows(rows) {
  const t = document.getElementById('reserveLogTable'); if (!t) return;
  t.innerHTML = `<tr><th>核对 ID</th><th>会计日</th><th>渠道</th><th class="num">账面余额（元）</th>
    <th class="num">实际余额（元）</th><th class="num">差额</th><th>结果</th><th>告警级别</th><th>方向</th><th>差错单</th></tr>` +
    (rows.length ? rows.map(c => `<tr data-dd="reserveLog:${esc(c.id)}">
      <td class="dd-cell"><b>${esc(c.id)}</b></td><td>${c.bizDate == null ? '—' : ppBizDate(c.bizDate)}</td>
      <td>${esc(c.channelCode || '全渠道')}</td>
      <td class="num">${ppFen(c.bookBalance)}</td><td class="num">${ppFen(c.actualBalance)}</td>
      <td class="num ${c.diff === 0 ? '' : 'neg'}"><b>${ppFen(c.diff)}</b></td>
      <td>${ppBadge(c.pass ? 'APPROVED' : 'REJECTED', c.pass ? '账实一致' : '存在差异')}</td>
      <td>${ppTag(c.level === 'ERROR' ? 'bad' : (c.level === 'WARN' ? 'warn' : 'off'), c.level || 'NONE')}</td>
      <td>${esc(c.direction || '—')}</td>
      <td>${c.disputeNo ? ddLink('dispute', c.disputeNo, c.disputeNo) : '—'}</td></tr>`).join('')
      : emptyRow(10, '暂无核对留痕（点「立即核对」生成）'));
}
function ppRlStats() {
  const box = document.getElementById('rlBox'); if (!box) return;
  const rows = PPFund.reserveLogs;
  if (!rows.length) { box.innerHTML = '<div class="box plain">暂无核对留痕。</div>'; return; }
  const latest = rows[0];
  const bad = rows.filter(x => !x.pass);
  const sumDiff = rows.reduce((a, x) => a + Number(x.diff || 0), 0);
  const byLevel = {}, byChannel = {};
  rows.forEach(x => { byLevel[x.level || 'NONE'] = (byLevel[x.level || 'NONE'] || 0) + 1;
    const k = x.channelCode || '全渠道'; byChannel[k] = (byChannel[k] || 0) + 1; });
  box.innerHTML = `<div class="stat-cards">
      <div class="stat-card"><div class="label">留痕条数</div><div class="value">${rows.length}</div></div>
      <div class="stat-card ${bad.length ? 'bad' : 'ok'}"><div class="label">差异条数</div><div class="value">${bad.length}</div></div>
      <div class="stat-card ${latest.pass ? 'ok' : 'bad'}"><div class="label">最近一次差额（元）</div><div class="value">${yuan(latest.diff || 0)}</div></div>
      <div class="stat-card ${sumDiff === 0 ? 'ok' : 'bad'}"><div class="label">Σ差额（元）</div><div class="value">${yuan(sumDiff)}</div></div>
      <div class="stat-card"><div class="label">一致率</div><div class="value">${((rows.length - bad.length) / rows.length * 100).toFixed(1)}%</div></div>
    </div>
    ${ppCons('最近一次账实核对 账面 = 实际', latest.bookBalance, latest.actualBalance,
      { leftLabel:'bookBalance', rightLabel:'actualBalance', note:`核对 ${esc(latest.id)} · ${fmtTime(latest.checkedAt)}${latest.disputeNo ? ' · 已登记差错单 ' + esc(latest.disputeNo) : ''}` })}
    <div class="pp-inline" style="margin-top:8px">${Object.entries(byLevel).map(([k, v]) =>
      ppTag(k === 'ERROR' ? 'bad' : (k === 'WARN' ? 'warn' : 'off'), `${k} ${v}`)).join('')}
      ${Object.entries(byChannel).map(([k, v]) => ppTag('info', `${k} ${v}`)).join('')}</div>`;
  const db = document.getElementById('rlDiffBox');
  if (db) db.innerHTML = bad.length ? `<div class="box warn"><b>${bad.length} 条差异待处置</b>
      <div class="table-wrap" style="margin-top:8px"><table><tr><th>核对 ID</th><th>渠道</th><th>差额（元）</th><th>级别</th><th>方向</th><th>差错单</th><th>归因</th></tr>
      ${bad.slice(0, 30).map(c => `<tr><td>${esc(c.id)}</td><td>${esc(c.channelCode || '全渠道')}</td>
        <td class="num neg">${ppFen(c.diff)}</td><td>${ppTag(c.level === 'ERROR' ? 'bad' : 'warn', c.level || 'NONE')}</td>
        <td>${esc(c.direction || '—')}</td><td>${c.disputeNo ? ddLink('dispute', c.disputeNo, c.disputeNo) : '—'}</td>
        <td class="wrap" style="font-size:12px">${Number(c.diff) > 0 ? '银行侧多记（长款）：账面 &lt; 实际' : '银行侧少记（短款）：账面 &gt; 实际'}</td></tr>`).join('')}
      </table></div>
      <div class="pp-inline" style="margin-top:8px"><button class="btn small ghost" data-pp-goto="disputes">前往差错平台处置</button>
        <button class="btn small ghost" data-pp-goto="reserve">备付金核对（银行侧事件）</button></div></div>`
    : '<div class="box info">全部核对账实一致（diff=0），无待处置差异。</div>';
}
async function ppRlRun() {
  const day = ppDayMs(ppVal('rlDate'));
  const chRaw = ppVal('rlChannels');
  const channels = chRaw ? chRaw.split(/[,，\s]+/).filter(Boolean) : null;
  const r = await ppSensitive('触发账实核对（敏感操作）',
    `<div class="summary">会计日 <b>${day == null ? '当前会计日' : ppDayStr(day)}</b>${channels ? ` · 渠道 <b>${esc(channels.join(', '))}</b>` : ' · 全渠道'}</div>
     <div class="box warn">逐渠道比对账面余额与存管实际余额；<b>差异会触发 ERROR 告警并自动登记差错单</b>（不自动动账）。需二次认证。</div>`,
    '/accounting/reserve-recon', 'POST', { bizDate: day, channels },
    '即将触发账实核对（差异会建差错单，需二次认证）。', '确认核对');
  if (!r) return;
  ppResult('rlRunBox', '核对已执行', Array.isArray(r) ? { count: r.length, items: r } : r);
  toast('账实核对完成');
  await ppRlQuery(true);
}

/** 账实核对留痕详情（P1-E reserve-recon 口径，与既有 reserve kind 的 /reserve/logs 区分） */
async function ddReserveLog(id) {
  let c = PPFund.reserveLogs.find(x => String(x.id) === String(id));
  if (!c) {
    const r = await tryApi('/accounting/reserve-recon/logs?limit=200');
    if (r.ok && Array.isArray(r.data)) { PPFund.reserveLogs = r.data; c = r.data.find(x => String(x.id) === String(id)); }
  }
  if (!c) return ddBox('danger', `核对留痕 <b>${esc(id)}</b> 不在最近拉取的留痕中（请提高「拉取上限」后重试）。`);
  let html = ddKv([
    ['核对 ID', `<b>${esc(c.id)}</b>`], ['会计日', c.bizDate == null ? '—' : ppBizDate(c.bizDate)],
    ['渠道', esc(c.channelCode || '全渠道')],
    ['账面余额（元）', ppFen(c.bookBalance)], ['实际余额（元）', ppFen(c.actualBalance)],
    ['差额（元）', `<b class="${c.diff === 0 ? '' : 'neg'}">${ppFen(c.diff)}</b>`],
    ['结果', ppBadge(c.pass ? 'APPROVED' : 'REJECTED', c.pass ? '账实一致' : '存在差异')],
    ['告警级别', ppTag(c.level === 'ERROR' ? 'bad' : (c.level === 'WARN' ? 'warn' : 'off'), c.level || 'NONE')],
    ['方向', esc(c.direction || '—')],
    ['差错单', c.disputeNo ? ddLink('dispute', c.disputeNo, c.disputeNo) : '—'],
    ['核对时间', ppMs(c.checkedAt)] ]);
  html += ddSec('账实守恒', ppCons('账面 = 实际', c.bookBalance, c.actualBalance,
    { leftLabel:'bookBalance', rightLabel:'actualBalance',
      note: c.pass ? 'diff=0，无需处理。' : '差异已触发告警并自动登记 source=RESERVE 差错单（不自动动账），需人工处置。' }));
  if (c.disputeNo) {
    const dr = await tryApi('/disputes?bizNo=' + encodeURIComponent(c.disputeNo) + '&page=1&size=10');
    const items = (dr.ok && dr.data && dr.data.items) || [];
    if (items.length) html += ddSec('关联差错单', ddDisputeTable(items), items.length + ' 条');
  }
  html += ddSec('跳转', `<div class="pp-inline"><button class="btn small ghost" data-pp-goto="disputes">差错平台</button>
    <button class="btn small ghost" data-pp-goto="reserve-logs">账实核对日志</button>
    <button class="btn small ghost" data-pp-goto="reserve">备付金核对（银行侧事件）</button>
    ${ddLink('account', 'SYS_CASH', 'SYS_CASH 账户详情')}</div>`);
  return html;
}

/* ===================== 4.9 资金账务 · 多币种与汇率（P2-C） ===================== */
async function loadMulticurrency() {
  if (!hasPerm('accounting:read')) { lpSet('fxRate', []); lpSet('mcAcc', []); return; }
  lpLoading('fxRate', 11); lpLoading('mcAcc', 8);
  const [cur, rates, accs, trial] = await Promise.all([
    tryApi('/accounting/currencies'), tryApi('/accounting/fx-rates?limit=' + (ppInt('fxLimit', 50) || 50)),
    tryApi('/accounts'), tryApi('/accounting/trial/by-currency') ]);
  if (cur.ok && Array.isArray(cur.data)) PPFund.currencies = cur.data;
  if (rates.ok && Array.isArray(rates.data)) { PPFund.fxRates = rates.data; lpSet('fxRate', rates.data, true); }
  else lpError('fxRate', 11, (rates.err && rates.err.message) || '返回形状异常');
  if (accs.ok && Array.isArray(accs.data)) {
    PPFund.mcAccounts = accs.data.filter(a => (a.currency || 'CNY') !== 'CNY');
    lpSet('mcAcc', PPFund.mcAccounts, true);
  } else lpError('mcAcc', 8, (accs.err && accs.err.message) || '返回形状异常');
  const box = document.getElementById('mcBox');
  if (box) box.innerHTML = `<div class="pp-inline"><b>已启用币种：</b>
      ${(PPFund.currencies.length ? PPFund.currencies : ['CNY']).map(c => ppTag(c === 'CNY' ? 'on' : 'info', c)).join('')}
      <span class="muted" style="font-size:12px">本位币 CNY · 非 CNY 账户 ${PPFund.mcAccounts.length} 个</span></div>
    <div class="pp-note">多币种开户按 (memberId, accountType, currency) 幂等；缺省 currency=CNY 与既有 typed 开户逐字节等价。
      汇率为定点数（rate / rateScale），换汇按中间价折算、买卖价差计汇兑损益。</div>`;
  if (trial.ok) ppTcRender(trial.data);
}
function ppFxRateRows(rows) {
  const t = document.getElementById('fxRateTable'); if (!t) return;
  t.innerHTML = `<tr><th>汇率 ID</th><th>币对</th><th class="num">买入价</th><th class="num">卖出价</th>
    <th class="num">中间价</th><th class="num">精度</th><th class="num">版本</th><th>生效时间</th><th>会计日</th><th>来源</th><th>操作人</th></tr>` +
    (rows.length ? rows.map(r => `<tr>
      <td class="pp-mono"><b>${esc(r.id)}</b></td>
      <td>${ppTag('info', r.fromCurrency + ' → ' + r.toCurrency)}</td>
      <td class="num">${ppRate(r.buyRate, r.rateScale)}</td><td class="num">${ppRate(r.sellRate, r.rateScale)}</td>
      <td class="num"><b>${ppRate(r.midRate, r.rateScale)}</b></td><td class="num">${ppNum(r.rateScale)}</td>
      <td class="num">${ppNum(r.version)}</td><td class="muted">${ppMs(r.effectiveAt)}</td>
      <td>${ppBizDate(r.bizDate)}</td><td>${esc(r.source || '—')}</td><td>${esc(r.operator || '—')}</td></tr>`).join('')
      : emptyRow(11, '暂无汇率版本'));
}
function ppMcAccRows(rows) {
  const t = document.getElementById('mcAccTable'); if (!t) return;
  t.innerHTML = `<tr><th>账户</th><th>会员</th><th>币种</th><th>账户类型</th><th class="num">余额（原币）</th>
    <th>等级</th><th>状态</th><th>创建时间</th></tr>` +
    (rows.length ? rows.map(a => `<tr data-dd="account:${esc(a.id)}">
      <td class="dd-cell"><b>${esc(a.id)}</b></td><td>${ddLink('member', a.memberId, a.memberName || a.memberId)}</td>
      <td>${ppTag('info', a.currency || 'CNY')}</td><td>${ppBadge('kind', a.accountType || 'BALANCE')}</td>
      <td class="num"><b>${ppFen(a.balance)}</b></td><td>${esc(a.level || '—')}</td>
      <td>${ppBadge(a.status)}</td><td class="muted">${fmtTime(a.createdAt)}</td></tr>`).join('')
      : emptyRow(8, '暂无非 CNY 账户'));
}
async function ppMcOpen() {
  const mid = ppVal('mcMember');
  if (!mid) throw new Error('请填写会员 ID');
  const cur = (ppVal('mcCurrency') || 'CNY').toUpperCase();
  const body = { memberId: mid, accountType: ppVal('mcAccType') || 'BALANCE', currency: cur };
  const r = await ppSensitive('多币种开户（敏感操作）',
    `<div class="summary">会员 <b>${esc(mid)}</b> · 账户类型 <b>${esc(body.accountType)}</b> · 币种 <b>${esc(cur)}</b></div>
     <div class="box warn">按 (memberId, accountType, currency) <b>幂等</b>：已存在则返回同一账户，不重复开户、不动账。</div>`,
    '/accounts/currency', 'POST', body, `即将为会员 ${esc(mid)} 开立 ${esc(cur)} 账户（需二次认证）。`, '确认开户');
  if (!r) return;
  ppResult('mcOpenBox', '开户结果', r);
  toast('账户已就绪：' + (r.id || ''));
  await loadMulticurrency();
}
function ppFxFill(pair) {
  const [f, t] = String(pair).split('/');
  const set = (i, v) => { const e = document.getElementById(i); if (e) e.value = v || ''; };
  set('fxFrom', f); set('fxTo', t);
  ppFxQuery();
}
async function ppFxQuery() {
  const from = (ppVal('fxFrom') || 'CNY').toUpperCase(), to = (ppVal('fxTo') || 'USD').toUpperCase();
  const ver = ppVal('fxVersion');
  ppLoadingBox('fxCurBox', '查询汇率…');
  const r = await tryApi('/accounting/fx-rate?from=' + encodeURIComponent(from) + '&to=' + encodeURIComponent(to) + (ver ? '&version=' + encodeURIComponent(ver) : ''));
  if (!r.ok) { ppErrBox('fxCurBox', '汇率查询', r.err); return; }
  const x = r.data || {};
  const set = (i, v) => { const e = document.getElementById(i); if (e) e.value = v == null ? '' : v; };
  set('fxBuy', x.buyRate); set('fxSell', x.sellRate); set('fxMid', x.midRate); set('fxScale', x.rateScale);
  document.getElementById('fxCurBox').innerHTML = `<div class="kv">
      <div><span class="k">币对</span><span class="v"><b>${esc(x.fromCurrency)} → ${esc(x.toCurrency)}</b></span></div>
      <div><span class="k">买入 / 卖出 / 中间</span><span class="v pp-mono">${ppRate(x.buyRate, x.rateScale)} / ${ppRate(x.sellRate, x.rateScale)} / <b>${ppRate(x.midRate, x.rateScale)}</b></span></div>
      <div><span class="k">定点精度</span><span class="v">${ppNum(x.rateScale)}</span></div>
      <div><span class="k">版本</span><span class="v">${ppNum(x.version)}${ver ? '（历史快照）' : '（当前生效）'}</span></div>
      <div><span class="k">生效时间</span><span class="v">${ppMs(x.effectiveAt)}</span></div>
      <div><span class="k">会计日</span><span class="v">${ppBizDate(x.bizDate)}</span></div>
      <div><span class="k">来源 / 操作人</span><span class="v">${esc(x.source || '—')} / ${esc(x.operator || '—')}</span></div></div>
    <div class="pp-note">1 ${esc(x.fromCurrency)} = ${ppRate(x.midRate, x.rateScale)} ${esc(x.toCurrency)}（中间价）；
      买卖价差 ${ppRate(Number(x.sellRate || 0) - Number(x.buyRate || 0), x.rateScale)} 计汇兑损益。</div>`;
}
async function ppFxSave() {
  const from = (ppVal('fxFrom') || '').toUpperCase(), to = (ppVal('fxTo') || '').toUpperCase();
  if (!from || !to) throw new Error('请填写币对（from / to）');
  if (from === to) throw new Error('同币种不允许设置汇率');
  const buy = ppInt('fxBuy', null), sell = ppInt('fxSell', null), mid = ppInt('fxMid', null);
  if (buy == null || sell == null || mid == null) throw new Error('买入/卖出/中间价均为必填（定点整数）');
  if (buy <= 0 || sell <= 0 || mid <= 0) throw new Error('汇率需为正整数');
  const scale = ppInt('fxScale', 10000) || 10000;
  const body = { fromCurrency: from, toCurrency: to, buyRate: buy, sellRate: sell, midRate: mid,
    rateScale: scale, source: ppVal('fxSource') || 'console', operator: me(), requestId: 'UII2-FX-' + uuid() };
  const r = await ppSensitive('设置汇率版本（敏感操作）',
    `<div class="summary">${esc(from)} → ${esc(to)} · 买入 <b>${ppRate(buy, scale)}</b> / 卖出 <b>${ppRate(sell, scale)}</b> / 中间 <b>${ppRate(mid, scale)}</b>（精度 ${scale}）</div>
     <div class="box danger">生成<b>新汇率版本</b>并立即生效，影响后续换汇折算与汇兑损益；历史版本快照保留可回查。同 requestId 幂等。</div>`,
    '/accounting/fx-rate', 'POST', body, `即将设置 ${esc(from)}/${esc(to)} 汇率（影响换汇折算，需二次认证）。`, '确认发布');
  if (!r) return;
  ppResult('fxSaveBox', '汇率版本已发布', r);
  toast('汇率已发布：version ' + (r.version || ''));
  await loadMulticurrency();
}
async function ppExRun() {
  const from = ppVal('exFrom'), to = ppVal('exTo');
  if (!from || !to) throw new Error('请填写源账户与目标账户 ID');
  if (from === to) throw new Error('源账户与目标账户不能相同');
  const amount = ppFenIn('exAmount');
  if (amount == null) throw new Error('请填写换汇金额（源币种，元）');
  const bizNo = ppVal('exBizNo') || ('UII2-EX-' + uuid().slice(0, 8));
  const body = { fromAccountId: from, toAccountId: to, amount, bizNo,
    feeBps: ppInt('exFeeBps', null), rateVersion: ppInt('exRateVersion', null), operator: me() };
  const r = await ppSensitive('发起换汇（敏感操作）',
    `<div class="summary">账户 <b>${esc(from)}</b> → <b>${esc(to)}</b> · 金额 <b>¥ ${yuan(amount)}</b>（源币种）· 业务号 <b>${esc(bizNo)}</b>
      ${body.feeBps != null ? ` · 手续费 ${body.feeBps} bp` : ''}${body.rateVersion != null ? ` · 锁定汇率版本 ${body.rateVersion}` : ''}</div>
     <div class="box danger"><b>真实动账：</b>源币扣款 + 目标币入账 + 手续费 + 汇兑损益，生成两张凭证（${esc(bizNo)}-F / -T）。
     bizNo 幂等：同键同额返回同一换汇单；同键不同额拒绝。</div>`,
    '/accounting/exchange', 'POST', body, `即将换汇 ¥ ${yuan(amount)}（真实动账，需二次认证）。`, '确认换汇');
  if (!r) return;
  ppResult('exRunBox', '换汇已受理', r);
  toast('换汇完成：' + (r.bizNo || bizNo));
  const q = document.getElementById('exQueryBizNo'); if (q) q.value = r.bizNo || bizNo;
  await ppExQuery();
}
async function ppExQuery() {
  const bizNo = ppVal('exQueryBizNo');
  if (!bizNo) throw new Error('请填写换汇业务号');
  ppLoadingBox('exQueryBox', '查询换汇单…');
  const r = await tryApi('/accounting/exchange/' + encodeURIComponent(bizNo));
  if (!r.ok) { ppErrBox('exQueryBox', '换汇单查询', r.err); return; }
  const x = r.data || {};
  document.getElementById('exQueryBox').innerHTML = `<div class="kv">
      <div><span class="k">业务号</span><span class="v"><b class="pp-mono">${esc(x.bizNo)}</b></span></div>
      <div><span class="k">币对</span><span class="v">${esc(x.fromCurrency)} → ${esc(x.toCurrency)}</span></div>
      <div><span class="k">源账户 / 目标账户</span><span class="v">${ddLink('account', x.fromAccountId, x.fromAccountId)} → ${ddLink('account', x.toAccountId, x.toAccountId)}</span></div>
      <div><span class="k">源金额（元）</span><span class="v"><b>${ppFen(x.amount)}</b></span></div>
      <div><span class="k">手续费（元）</span><span class="v">${ppFen(x.fee)}（${ppNum(x.feeBps)} bp）</span></div>
      <div><span class="k">目标总额 / 净额</span><span class="v">${ppFen(x.grossToAmount)} / <b>${ppFen(x.netToAmount)}</b></span></div>
      <div><span class="k">汇兑损益（元）</span><span class="v ${Number(x.fxGain || 0) < 0 ? 'neg' : ''}">${ppFen(x.fxGain)}</span></div>
      <div><span class="k">汇率快照</span><span class="v pp-mono">买 ${ppRate(x.buyRate, x.rateScale)} / 卖 ${ppRate(x.sellRate, x.rateScale)} / 中 ${ppRate(x.midRate, x.rateScale)} · v${ppNum(x.rateVersion)}</span></div>
      <div><span class="k">汇率来源 / 生效</span><span class="v">${esc(x.rateSource || '—')} / ${ppMs(x.rateEffectiveAt)}</span></div>
      <div><span class="k">源 / 目标余额后</span><span class="v">${ppFen(x.fromBalanceAfter)} / ${ppFen(x.toBalanceAfter)}</span></div>
      <div><span class="k">两张凭证</span><span class="v">${ddLink('voucher', x.fromBizNo, x.fromBizNo)} · ${ddLink('voucher', x.toBizNo, x.toBizNo)}</span></div>
      <div><span class="k">损益 / 手续费户</span><span class="v pp-mono">${esc(x.fxGainAccount || '—')} / ${esc(x.feeAccount || '—')}</span></div>
      <div><span class="k">受理时间</span><span class="v">${ppMs(x.createdAt)}</span></div></div>
    ${ppCons('目标总额 − 手续费 = 目标净额', Number(x.grossToAmount || 0) - Number(x.fee || 0), x.netToAmount,
      { leftLabel:'gross−fee', rightLabel:'netToAmount', note:'换汇守恒：源币扣款 = 目标币入账（按汇率快照折算）+ 手续费 + 汇兑损益。' })}
    <div class="pp-inline" style="margin-top:8px"><button class="btn small ghost" data-dd="exchange:${esc(x.bizNo)}">抽屉详情</button></div>`;
}
async function ddExchange(bizNo) {
  const r = await tryApi('/accounting/exchange/' + encodeURIComponent(bizNo));
  if (!r.ok) return ddBox('danger', '换汇单加载失败：' + esc(r.err.message));
  const x = r.data || {};
  let html = ddKv([
    ['业务号', `<b class="pp-mono">${esc(x.bizNo)}</b>`],
    ['币对', `${esc(x.fromCurrency)} → ${esc(x.toCurrency)}`],
    ['源 → 目标账户', `${ddLink('account', x.fromAccountId, x.fromAccountId)} → ${ddLink('account', x.toAccountId, x.toAccountId)}`],
    ['源金额（元）', `<b>${ppFen(x.amount)}</b>`], ['手续费（元）', `${ppFen(x.fee)}（${ppNum(x.feeBps)} bp）`],
    ['目标总额 / 净额', `${ppFen(x.grossToAmount)} / ${ppFen(x.netToAmount)}`],
    ['汇兑损益（元）', ppFen(x.fxGain)],
    ['汇率快照', `中 ${ppRate(x.midRate, x.rateScale)} · v${ppNum(x.rateVersion)} · ${esc(x.rateSource || '')}`],
    ['受理时间', ppMs(x.createdAt)] ]);
  html += ddSec('守恒', ppCons('目标总额 − 手续费 = 净额', Number(x.grossToAmount || 0) - Number(x.fee || 0), x.netToAmount,
    { leftLabel:'gross−fee', rightLabel:'net' }));
  for (const bn of [x.fromBizNo, x.toBizNo]) {
    if (bn) html += ddSec('凭证 ' + bn, await ddVoucherCard(bn, bn === x.fromBizNo ? '源币扣款' : '目标币入账'));
  }
  return html;
}
async function ppTcQuery() {
  const cur = ppVal('tcCurrency');
  ppLoadingBox('tcBox', '逐币种试算…');
  const r = await tryApi('/accounting/trial/by-currency' + (cur ? '?currency=' + encodeURIComponent(cur.toUpperCase()) : ''));
  if (!r.ok) { ppErrBox('tcBox', '逐币种试算', r.err); return; }
  ppTcRender(r.data);
}
function ppTcRender(d) {
  const box = document.getElementById('tcBox'); if (!box) return;
  if (!d || !Array.isArray(d.byCurrency)) { box.innerHTML = '<div class="box plain">无试算数据。</div>'; return; }
  box.innerHTML = `<div class="stat-cards">
      <div class="stat-card ${d.balanced ? 'ok' : 'bad'}"><div class="label">全局试算（本位币）</div><div class="value" style="font-size:17px">${d.balanced ? '平衡' : '不平衡'}</div></div>
      <div class="stat-card accent"><div class="label">Σ借（元）</div><div class="value">${yuan(d.totalDebit || 0)}</div></div>
      <div class="stat-card accent"><div class="label">Σ贷（元）</div><div class="value">${yuan(d.totalCredit || 0)}</div></div>
      <div class="stat-card ${d.balanceSum === 0 ? 'ok' : 'bad'}"><div class="label">Σ余额（元）</div><div class="value">${yuan(d.balanceSum || 0)}</div></div>
      <div class="stat-card ${d.multiCurrencyBalanced ? 'ok' : 'bad'}"><div class="label">多币种平衡</div><div class="value" style="font-size:17px">${d.multiCurrencyBalanced ? '全部平衡' : '存在不平衡'}</div></div>
    </div>
    ${ppCons('全局 Σ借 = Σ贷', d.totalDebit, d.totalCredit, { leftLabel:'totalDebit', rightLabel:'totalCredit' })}
    ${ppCons('全局 Σ余额 = 0', d.balanceSum, 0, { leftLabel:'balanceSum', rightLabel:'0',
      note:`本位币 ${esc(d.baseCurrency || 'CNY')} · 账户 ${ppNum(d.accounts)} · 分录 ${ppNum(d.entries)} · 挂账户余额 ¥ ${yuan(d.suspenseBalance || 0)}` })}
    <div class="dd-sec"><h4>逐币种 D = C 与 Σbalance = 0<span class="cnt">${d.byCurrency.length} 币种</span></h4>
    ${d.byCurrency.map(c => `<div style="margin-bottom:10px">
      <div class="pp-inline"><b>${esc(c.currency)}</b>
        ${ppTag(c.balanced ? 'on' : 'bad', c.balanced ? 'D=C 平衡' : 'D≠C 不平衡')}
        ${ppTag(c.conserved ? 'on' : 'bad', c.conserved ? 'Σbalance=0 守恒' : 'Σbalance≠0')}
        <span class="muted" style="font-size:12px">账户 ${ppNum(c.accounts)} · 分录 ${ppNum(c.entries)}${c.currency !== (d.baseCurrency || 'CNY') ? ` · 折本位币汇率 ${ppRate(c.fxRateToBase, c.rateScale)}` : ''}</span></div>
      ${ppCons(c.currency + ' 试算平衡', c.totalDebit, c.totalCredit, { leftLabel:'Σ借', rightLabel:'Σ贷' })}
      ${ppCons(c.currency + ' 余额守恒', c.balanceSum, 0, { leftLabel:'Σbalance', rightLabel:'0',
        note: c.currency !== (d.baseCurrency || 'CNY') ? `原币毛余额 ${ppFen(c.grossBalance)} · 折本位币 Σ贷 ${ppFen(c.baseCreditTotal)} / 毛余额 ${ppFen(c.baseGrossBalance)}` : '' })}
    </div>`).join('')}</div>
    <div class="pp-note">只读试算：不动账、不需二次认证。任一币种 D≠C 或 Σbalance≠0 都会红色告警，需按凭证联查追差。</div>`;
}