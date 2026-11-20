/* =====================================================================
 * payment-pro 管理控制台 · 通知运营（IA 分组 10）· UI 泳道 B
 * ---------------------------------------------------------------------
 * 2 个实页（全局加载函数 = PPNotify.loadResend / PPNotify.loadTemplates）：
 *  notify-resend 查询与重发
 *    GET  /api/notify/query   ?appId&event&status&bizId&from&to&page&size（服务端分页，脱敏）
 *    GET  /api/notify/events  事件目录（legacy 冻结 / envelope / requiredFields）
 *    GET  /api/notify/{id}    单条详情（既有抽屉 kind=notify，含关联单据解析）
 *    POST /api/notify/{id}/resend    {requestId}          —— notify:send + X-Step-Up-Token
 *    POST /api/notify/resend/batch   {notifyIds[],requestId} ≤100 条
 *  notify-tpl 事件类型与模板
 *    GET  /api/platform/notify/templates            事件契约模板（NotifyEvent 单一事实来源）
 *    POST /api/platform/notify/templates/validate   payload 契约校验（只校验，不发送）
 *    GET  /api/notify/query?event=X&size=1          按事件真实记录数
 * ---------------------------------------------------------------------
 * 【自挂载】index.html 无这两页 section（泳道 A 只做 script 标签 + nav 布线），
 * 故 ppNrEnsure()/ppNtEnsure() 在 <main> 内建/填 section#tab-<tab>；
 * 加载函数自身保证 DOM + 列表注册 + 事件绑定就绪，不依赖 init() 调用时机。
 * 顶层只声明；DOM / 全局依赖全部在函数体内。
 * ===================================================================== */

const PP_NOTIFY_ST = [['PENDING', 'PENDING 待推送'], ['DELIVERED', 'DELIVERED 已送达'],
  ['FAILED', 'FAILED 失败（可重发）'], ['PULL', 'PULL 商户主动拉取']];

const PP_NR_HTML = `
  <div class="card"><h2>通知查询与重发<span class="section-desc">N-4 · 服务端分页 + 脱敏；重发属敏感写操作，恒需 X-Step-Up-Token</span></h2>
    <div class="row">
      <div class="field"><label>商户 appId</label><input id="nrAppId" placeholder="appM1 / sbx…" style="min-width:150px"></div>
      <div class="field"><label>事件类型</label><select id="nrEvent" style="min-width:190px"><option value="">全部事件</option></select></div>
      <div class="field"><label>状态</label><select id="nrStatus" style="min-width:170px"><option value="">全部状态</option></select></div>
      <div class="field"><label>业务单号</label><input id="nrBizId" placeholder="P… / O… / 分账单号" style="min-width:170px"></div>
      <div class="field"><label>起始日期</label><input id="nrFrom" type="date" style="min-width:142px"></div>
      <div class="field"><label>截止日期</label><input id="nrTo" type="date" style="min-width:142px"></div>
      <button class="btn" id="btnNrQuery">查询</button>
      <button class="btn ghost" id="btnNrReset">重置</button>
      <button class="btn ghost" id="btnNrDead">只看重发池</button>
    </div>
    <div class="row" style="margin-top:8px">
      <button class="btn danger" id="btnNrBatch" data-write="notify:send" disabled>批量重发</button>
      <button class="btn ghost" id="btnNrClear">清空选择</button>
      <button class="btn ghost" id="btnNrCsv">导出本页 CSV</button>
      <button class="btn ghost" data-pp-goto="notify-tpl">事件类型与模板</button>
      <button class="btn ghost" data-pp-goto="notify">通知记录（旧页）</button>
      <span class="muted" id="nrSelInfo" style="font-size:12px;align-self:center"></span>
    </div>
    <div id="nrSum" style="margin-top:10px"></div>
    <div class="table-wrap" style="margin-top:10px"><table id="nrTable"></table></div>
    <div class="row pager" id="nrPager"></div>
    <div id="nrOpBox" style="margin-top:10px"></div>
  </div>`;

const PP_NT_HTML = `
  <div class="card"><h2>事件类型与模板<span class="section-desc">N-3 · 模板即事件契约（NotifyEvent 枚举为单一事实来源，服务端强校验）</span></h2>
    <div class="row" style="margin-bottom:10px">
      <button class="btn" id="btnNtRefresh">刷新事件与模板</button>
      <button class="btn ghost" id="btnNtCounts">按事件统计记录数</button>
      <button class="btn ghost" id="btnNtCsv">导出事件 CSV</button>
      <button class="btn ghost" data-pp-goto="notify-resend">查询与重发</button>
    </div>
    <div id="ntCountBox"></div>
    <div class="table-wrap" style="margin-top:10px"><table id="ntEventTable"></table></div>
  </div>
  <div class="card"><h2>模板编辑与契约校验<span class="section-desc">编辑 payload → 服务端按 requiredFields 校验并渲染将投递的 body（只校验，不发送、不落库）</span></h2>
    <div class="row">
      <div class="field"><label>事件</label><select id="ntTplEvent" style="min-width:200px"></select></div>
      <div class="field"><label>业务号 bizId</label><input id="ntTplBizId" placeholder="P1234 / O1234" style="min-width:170px"></div>
      <button class="btn" id="btnNtValidate">校验并渲染 body</button>
      <button class="btn ghost" id="btnNtSkeleton">载入模板骨架</button>
    </div>
    <div class="field" style="margin-top:8px"><label>payload（JSON）</label>
      <textarea id="ntTplPayload" rows="8" style="width:100%;font-family:ui-monospace,Menlo,monospace;font-size:12px"></textarea></div>
    <div id="ntTplBox" style="margin-top:10px"></div>
  </div>
  <div class="card"><h2>签名与投递口径<span class="section-desc">双 SDK 与服务端冻结实现逐字节一致</span></h2>
    <div id="ntSign"></div>
  </div>`;

const PPNotify = {
  rows: [], total: 0, page: 1, size: 20, events: [], sel: new Set(), counts: {},
  templates: [], signSpec: null, bound: {},

  /** 幂等初始化（index.html 尾部按序调用；未调用也不影响 —— 加载函数会自挂载）。 */
  init() {
    ppNrEnsure();
    ppNtEnsure();
  },

  /* ---------- 2 个全局加载入口（泳道 A 按此名布线） ---------- */
  loadResend() { ppNrEnsure(); return loadNotifyResend(); },
  loadTemplates() { ppNtEnsure(); return loadNotifyTpl(); }
};

/* ---------- 自挂载与列表注册（两种调用顺序都安全） ---------- */
function ppNtSec(tab, marker, html) {
  let sec = document.getElementById('tab-' + tab);
  if (!sec) {
    const main = document.querySelector('main');
    if (!main) return null;
    sec = document.createElement('section');
    sec.id = 'tab-' + tab;
    sec.hidden = true;
    main.appendChild(sec);
  }
  if (!document.getElementById(marker)) sec.innerHTML = html;
  sec.querySelectorAll('[data-write]').forEach(el => { el.hidden = !hasPerm(el.dataset.write); });
  return sec;
}
/** lpDef 只在表存在时注册；若先前注册但未挂载（表当时不存在），补挂载过滤条与分页条。 */
function ppNtLp(key, spec) {
  if (!LP[key]) { if (document.getElementById(spec.table)) lpDef(key, spec); }
  else if (!LP[key].mounted) lpMount(key);
}
function ppNtOn(map) {
  Object.entries(map).forEach(([id, fn]) => { const el = document.getElementById(id); if (el) el.onclick = fn; });
}
function ppNrEnsure() {
  ppNtSec('notify-resend', 'nrTable', PP_NR_HTML);
  if (PPNotify.bound.resend) return;
  PPNotify.bound.resend = true;
  const st = document.getElementById('nrStatus');
  if (st) st.innerHTML = '<option value="">全部状态</option>' +
    PP_NOTIFY_ST.map(([v, l]) => `<option value="${esc(v)}">${esc(l)}</option>`).join('');
  ppNtOn({
    btnNrQuery: () => ppNrQuery(1),
    btnNrReset: () => {
      ['nrAppId', 'nrEvent', 'nrStatus', 'nrBizId', 'nrFrom', 'nrTo'].forEach(id => {
        const e = document.getElementById(id); if (e) e.value = '';
      });
      ppNrQuery(1);
    },
    btnNrDead: () => { const e = document.getElementById('nrStatus'); if (e) e.value = 'FAILED'; ppNrQuery(1); },
    btnNrBatch: () => ppNrBatch(),
    btnNrClear: () => { PPNotify.sel.clear(); ppNrPaint(); toast('已清空选择'); },
    btnNrCsv: () => ppNrCsv()
  });
  const t = document.getElementById('nrTable');
  if (t) t.addEventListener('click', ev => {
    const one = ev.target.closest && ev.target.closest('[data-nr-resend]');
    if (one) { ev.preventDefault(); ppNrResend(one.dataset.nrResend); return; }
    const chk = ev.target.closest && ev.target.closest('[data-nr-sel]');
    if (chk) {
      const id = chk.dataset.nrSel;
      if (chk.checked) PPNotify.sel.add(id); else PPNotify.sel.delete(id);
      ppNrSelInfo(); return;
    }
    const all = ev.target.closest && ev.target.closest('[data-nr-selall]');
    if (all) {
      PPNotify.rows.forEach(r => { if (all.checked) PPNotify.sel.add(r.id); else PPNotify.sel.delete(r.id); });
      ppNrPaint();
    }
  });
  ['nrBizId', 'nrAppId'].forEach(id => {
    const e = document.getElementById(id);
    if (e) e.addEventListener('keydown', ev => { if (ev.key === 'Enter') ppNrQuery(1); });
  });
  ['nrEvent', 'nrStatus'].forEach(id => {
    const e = document.getElementById(id);
    if (e) e.addEventListener('change', () => ppNrQuery(1));
  });
}
function ppNtEnsure() {
  ppNtSec('notify-tpl', 'ntEventTable', PP_NT_HTML);
  DD_KINDS.notifyTpl = '通知事件模板';
  DD_RENDER.notifyTpl = ppNtDdTemplate;
  ppNtLp('ntEvent', { table: 'ntEventTable', render: ppNtEventRows, filters: [
    { k: 'kw', label: '关键字', ph: '事件名 / 说明 / 必填字段', fields: ['event', 'description', 'requiredFields'] },
    { k: 'legacy', label: '冻结面', type: 'select', opts: [opt('', '全部'), opt('true', 'legacy 冻结事件'), opt('false', 'additive 新事件')],
      test: (r, v) => String(!!r.legacy) === v },
    { k: 'envelope', label: '信封', type: 'select', opts: [opt('', '全部'), opt('true', 'envelope 信封'), opt('false', '裸 payload')],
      test: (r, v) => String(!!r.envelope) === v } ] });
  if (PPNotify.bound.tpl) return;
  PPNotify.bound.tpl = true;
  ppNtOn({
    btnNtRefresh: () => loadNotifyTpl(),
    btnNtCounts: () => ppNtCounts(),
    btnNtCsv: () => ppNtCsv(),
    btnNtValidate: () => ppNtValidate(),
    btnNtSkeleton: () => ppNtFillSkeleton()
  });
  const sel = document.getElementById('ntTplEvent');
  if (sel) sel.onchange = () => ppNtFillSkeleton();
}

/* ===================== 页 1 · 通知查询与重发 ===================== */
async function loadNotifyResend() {
  ppNrEnsure();
  if (!PPNotify.events.length) {
    const r = await tryApi('/notify/events');
    if (r.ok) PPNotify.events = r.data || [];
  }
  const ev = document.getElementById('nrEvent');
  if (ev && ev.options.length <= 1 && PPNotify.events.length) {
    const cur = ev.value;
    ev.innerHTML = '<option value="">全部事件</option>' +
      PPNotify.events.map(e => `<option value="${esc(e.event)}">${esc(e.event)} · ${esc(e.description || '')}</option>`).join('');
    ev.value = cur;
  }
  await ppNrQuery(PPNotify.page || 1, true);
}
function ppNrQs(page) {
  const p = new URLSearchParams({ page: String(page || 1), size: String(PPNotify.size || 20) });
  const map = { appId: 'nrAppId', event: 'nrEvent', status: 'nrStatus', bizId: 'nrBizId' };
  Object.entries(map).forEach(([k, id]) => { const v = ppVal(id); if (v) p.set(k, v); });
  const f = ppVal('nrFrom'), t = ppVal('nrTo');
  if (f) p.set('from', String(new Date(f + 'T00:00:00+08:00').getTime()));
  if (t) p.set('to', String(new Date(t + 'T23:59:59.999+08:00').getTime()));
  return p.toString();
}
async function ppNrQuery(page, silent) {
  PPNotify.page = page || 1;
  const t = document.getElementById('nrTable');
  if (t) loadingRow(t, 13, '查询通知记录…');
  const pg = document.getElementById('nrPager'); if (pg) pg.innerHTML = '';
  let d;
  try { d = await api('/notify/query?' + ppNrQs(PPNotify.page)); }
  catch (e) { if (t) errorRow(t, 13, e.message); return; }
  PPNotify.rows = d.records || []; PPNotify.total = d.total || 0;
  PPNotify.page = d.page || PPNotify.page; PPNotify.size = d.size || PPNotify.size;
  ppNrPaint();
  if (!silent) toast(`查询完成：共 ${PPNotify.total} 条`);
}
function ppNrSelInfo() {
  const box = document.getElementById('nrSelInfo');
  if (box) box.innerHTML = PPNotify.sel.size
    ? `已选 <b>${PPNotify.sel.size}</b> 条 / 单批上限 100 条${PPNotify.sel.size > 100 ? ' <span class="neg">（超出上限，请减少选择）</span>' : ''}`
    : '未选择记录（勾选左侧复选框后可批量重发）';
  const b = document.getElementById('btnNrBatch');
  if (b) b.disabled = !PPNotify.sel.size;
}
function ppNrPaint() {
  const t = document.getElementById('nrTable'); if (!t) return;
  const canS = hasPerm('notify:send');
  const rows = PPNotify.rows;
  const allOn = rows.length && rows.every(r => PPNotify.sel.has(r.id));
  t.innerHTML = `<tr>${canS ? `<th><input type="checkbox" class="pp-chk" data-nr-selall="1" ${allOn ? 'checked' : ''} aria-label="全选本页"></th>` : ''}
    <th>通知 ID</th><th>事件</th><th>业务号</th><th>关联凭证</th><th>状态</th><th class="num">尝试</th><th class="num">重发</th>
    <th>回调地址</th><th>签名（预览）</th><th>appId</th><th>最近错误</th><th>创建时间</th>${canS ? '<th>操作</th>' : ''}</tr>` +
    (rows.length ? rows.map(r => {
      const resendable = r.status === 'FAILED' || r.status === 'PULL';
      return `<tr>${canS ? `<td><input type="checkbox" class="pp-chk" data-nr-sel="${esc(r.id)}" ${PPNotify.sel.has(r.id) ? 'checked' : ''} aria-label="选择 ${esc(r.id)}"></td>` : ''}
        <td>${ddLink('notify', r.id, r.id)}</td>
        <td>${ppBadge('kind', r.event)}</td><td class="pp-mono">${esc(r.bizId || '—')}</td>
        <td>${r.bizId ? ddLink('voucher', r.bizId, '凭证') : '<span class="muted">—</span>'}</td>
        <td>${ppBadge(r.status, r.status)}</td><td class="num">${ppNum(r.attempts)}</td>
        <td class="num">${ppNum(r.resendCount)}</td>
        <td class="pp-mono" style="max-width:220px;word-break:break-all">${esc(r.callbackUrl || '（主动拉取）')}</td>
        <td class="pp-mono">${esc(r.signature || '—')}<br><span class="muted">${esc(r.signType || '')}</span></td>
        <td class="pp-mono">${esc(r.appId || '—')}</td>
        <td>${esc(r.lastError || '—')}</td><td>${fmtTime(r.createdAt)}</td>
        ${canS ? `<td>${resendable
          ? `<button class="btn small" data-nr-resend="${esc(r.id)}">重发</button>`
          : `<span class="muted" title="DELIVERED / PENDING 不重复推送">跳过</span>`}</td>` : ''}</tr>`;
    }).join('') : emptyRow(canS ? 14 : 12, '无匹配通知记录（可调整过滤条件）'));
  renderPager(document.getElementById('nrPager'), PPNotify.page, PPNotify.size, PPNotify.total,
    p => ppNrQuery(p), sz => { PPNotify.size = sz; ppNrQuery(1); });
  ppNrSelInfo();
  const sum = document.getElementById('nrSum');
  if (sum) {
    const c = st => rows.filter(r => r.status === st).length;
    sum.innerHTML = ddBox('info', `服务端分页：共 <b>${PPNotify.total}</b> 条，本页 ${rows.length} 条
      （DELIVERED ${c('DELIVERED')} · FAILED ${c('FAILED')} · PENDING ${c('PENDING')} · PULL ${c('PULL')}）。
      签名仅返回预览（<span class="pp-mono">前8位****(len=64)</span>），密钥 / nonce 明文不下发。
      点通知 ID 打开详情抽屉（自动解析关联支付单 / 交易单 / 退款单）；点「凭证」直查账务凭证。`);
  }
}
async function ppNrResend(id) {
  const r = (PPNotify.rows || []).find(x => x.id === id) || {};
  const req = ppReqId('UI-rs');
  const html = `<div class="box warn"><b>单条通知重发（敏感操作，需二次认证）</b></div>
    ${ddKv([['通知 ID', esc(id)], ['事件', esc(r.event || '—')], ['业务号', esc(r.bizId || '—')],
      ['当前状态', ppBadge(r.status, r.status || '—')], ['已尝试 / 已重发', `${ppNum(r.attempts)} / ${ppNum(r.resendCount)}`],
      ['回调地址', esc(r.callbackUrl || '（主动拉取）')], ['幂等键', esc(req)]])}
    <div class="pp-note">仅 FAILED / PULL 重置 PENDING 重推；DELIVERED / PENDING 跳过（不重复推送）。
      不产生新记录，resendCount 累加；同 requestId 重复调用返回首次结果。</div>`;
  const box = document.getElementById('nrOpBox');
  try {
    const d = await ppSensitive('通知重发', html, '/notify/' + encodeURIComponent(id) + '/resend', 'POST', { requestId: req }, html);
    if (!d) return;
    ppResult(box, '重发已受理', d); toast('重发已受理'); await ppNrQuery(PPNotify.page, true);
  } catch (e) { ppErrBox(box, '通知重发', e); }
}
async function ppNrBatch() {
  const ids = [...PPNotify.sel];
  if (!ids.length) { toast('请先勾选要重发的通知', false); return; }
  if (ids.length > 100) { toast('单批上限 100 条，当前 ' + ids.length + ' 条', false); return; }
  const req = ppReqId('UI-rsb');
  const picked = ids.map(i => (PPNotify.rows || []).find(x => x.id === i) || { id: i });
  const skip = picked.filter(x => x.status === 'DELIVERED' || x.status === 'PENDING').length;
  const html = `<div class="box warn"><b>批量通知重发（敏感操作，需二次认证）</b>：${ids.length} 条</div>
    ${ddKv([['选中条数', String(ids.length)], ['预计跳过（已送达 / 在途）', String(skip)],
      ['预计重推', String(ids.length - skip)], ['单批上限', '100 条'], ['幂等键', esc(req)]])}
    <div class="pp-code light">${esc(ids.join(', '))}</div>
    <div class="pp-note">逐条 CAS 重置，已送达 / 在途跳过；requestId 必填（NOTIFY_RESEND_BATCH 幂等）。
      超 100 条后端返回 A0413。</div>`;
  const box = document.getElementById('nrOpBox');
  try {
    const d = await ppSensitive('批量通知重发', html, '/notify/resend/batch', 'POST', { notifyIds: ids, requestId: req }, html);
    if (!d) return;
    ppResult(box, '批量重发已受理', d);
    PPNotify.sel.clear(); toast('批量重发已受理'); await ppNrQuery(PPNotify.page, true);
  } catch (e) { ppErrBox(box, '批量通知重发', e); }
}
function ppNrCsv() {
  if (!PPNotify.rows.length) { toast('当前页无数据可导出', false); return; }
  ppCsvLocal('notify-query-p' + PPNotify.page + '.csv',
    ['通知ID', '事件', '业务号', '状态', '尝试', '重发次数', '回调地址', '签名预览', '签名算法', 'appId', '最近错误', '创建时间'],
    PPNotify.rows.map(r => [r.id, r.event, r.bizId, r.status, r.attempts, r.resendCount, r.callbackUrl,
      r.signature, r.signType, r.appId, r.lastError, fmtTime(r.createdAt)]));
}

/* ===================== 页 2 · 事件类型与模板 ===================== */
async function loadNotifyTpl() {
  ppNtEnsure();
  lpLoading('ntEvent', 8, '加载事件目录与模板…');
  const [ev, tpl] = await Promise.all([tryApi('/notify/events'), tryApi('/platform/notify/templates')]);
  if (!ev.ok) { lpError('ntEvent', 8, ev.err.message); return; }
  PPNotify.events = ev.data || [];
  PPNotify.templates = tpl.ok ? ((tpl.data && tpl.data.templates) || []) : [];
  PPNotify.signSpec = tpl.ok ? (tpl.data && tpl.data.signSpec) : null;
  /* 事件目录 + 服务端模板骨架合并（模板端点不可用时按契约本地推导，明确标注） */
  const byEvent = {};
  PPNotify.templates.forEach(t => { byEvent[t.event] = t; });
  const merged = PPNotify.events.map(e => Object.assign({}, e, {
    bodyTemplate: byEvent[e.event] ? byEvent[e.event].bodyTemplate : null,
    idempotentKey: byEvent[e.event] ? byEvent[e.event].idempotentKey : 'event:bizId',
    serverTemplate: !!byEvent[e.event]
  }));
  lpSet('ntEvent', merged);
  const sel = document.getElementById('ntTplEvent');
  if (sel) {
    const cur = sel.value;
    sel.innerHTML = merged.map(e => `<option value="${esc(e.event)}">${esc(e.event)} · ${esc(e.description || '')}</option>`).join('');
    sel.value = cur || (merged[0] && merged[0].event) || '';
  }
  ppNtFillSkeleton();
  const box = document.getElementById('ntSign');
  if (box) box.innerHTML = ppNtSignHtml(tpl.ok ? '' : tpl.err.message);
  await ppNtCounts(true);
}
function ppNtEventRows(rows) {
  const t = document.getElementById('ntEventTable'); if (!t) return;
  t.innerHTML = `<tr><th>事件名</th><th>说明</th><th>冻结面</th><th>信封</th><th>payload 必填字段</th>
    <th class="num">记录数</th><th>模板来源</th><th>body 模板</th><th>操作</th></tr>` +
    (rows.length ? rows.map(e => {
      const req = e.requiredFields || [];
      const skeleton = e.bodyTemplate || (e.legacy ? { bizId: '<业务号>', amount: '<分>' }
        : Object.assign({ event: e.event, bizId: '<业务号>', timestamp: '<毫秒>' },
            req.reduce((o, k) => (o[k] = '<' + k + '>', o), {})));
      const n = PPNotify.counts[e.event];
      return `<tr><td>${ddLink('notifyTpl', e.event, e.event)}</td><td>${esc(e.description || '—')}</td>
        <td>${e.legacy ? ppTag('bad', 'legacy 冻结') : ppTag('on', 'additive 新增')}</td>
        <td>${e.envelope ? ppTag('on', 'envelope') : ppTag('off', '裸 payload')}</td>
        <td class="pp-mono">${req.length ? esc(req.join(', ')) : '<span class="muted">无强校验</span>'}</td>
        <td class="num">${n == null ? '<span class="muted">未统计</span>' : n}</td>
        <td>${e.serverTemplate ? ppTag('on', '服务端契约') : ppTag('off', '本地推导')}</td>
        <td><details><summary class="muted" style="cursor:pointer;font-size:12px">查看模板</summary>
          <div class="pp-code light">${esc(JSON.stringify(skeleton, null, 2))}</div></details></td>
        <td><button class="btn small ghost" data-nt-pick="${esc(e.event)}">编辑校验</button>
          <button class="btn small ghost" data-nt-goto="${esc(e.event)}">查记录</button></td></tr>`;
    }).join('') : emptyRow(9, '暂无事件目录'));
  /* 行内操作：编辑校验 / 跳查询页并预置事件过滤 */
  t.onclick = ev => {
    const pick = ev.target.closest && ev.target.closest('[data-nt-pick]');
    if (pick) {
      ev.preventDefault();
      const sel = document.getElementById('ntTplEvent');
      if (sel) sel.value = pick.dataset.ntPick;
      ppNtFillSkeleton();
      const ta = document.getElementById('ntTplPayload');
      if (ta) ta.focus();
      return;
    }
    const go = ev.target.closest && ev.target.closest('[data-nt-goto]');
    if (go) {
      ev.preventDefault();
      const name = go.dataset.ntGoto;
      if (window.PPNav) PPNav.go('notify-resend');
      setTimeout(() => {
        const e = document.getElementById('nrEvent');
        if (e) { e.value = name; ppNrQuery(1); }
      }, 150);
    }
  };
}
function ppNtSignHtml(err) {
  const s = PPNotify.signSpec || {};
  return ddKv([['签名算法', esc(s.algorithm || 'HMAC_SHA256')],
      ['签名口径', esc(s.canonical || 'TreeMap 排序 k=v&（剔除 sign、跳过 null）+ HMAC-SHA256 小写 hex')],
      ['时间戳', esc(s.timestamp || '毫秒 timestamp（±300s 窗口）')],
      ['nonce', esc(s.nonce || '可选，TTL 300s 防重放')],
      ['RSA2', esc(s.rsa2 || 'SHA256withRSA + Base64（sign_type=RSA2）')],
      ['布尔 canonical', '小写 true / false（与 Java String.valueOf 一致）']]) +
    `<div class="pp-obs"><b>订阅与投递口径</b>：商户 appId 在开放平台注册并授予 <span class="pp-mono">notify:read</span> /
      <span class="pp-mono">notify:write</span> scope 后，可通过 <span class="pp-mono">/openapi/notify.query</span> /
      <span class="pp-mono">/openapi/notify.resend</span> 自助补查与重发（HMAC 验签，只能操作自己的记录）；
      平台侧回调地址由下单 / 支付请求的 callbackUrl 指定。</div>
    ${err ? ddBox('warn', '服务端模板端点不可用（' + esc(err) + '）：body 模板按事件契约本地推导，必填字段与信封口径仍来自 /api/notify/events。')
      : ddBox('info', '<b>模板 = 事件契约</b>：body 骨架由后端 <span class="pp-mono">NotifyEvent</span> 枚举渲染（单一事实来源），'
        + '上方编辑器可改 payload 并做<b>服务端契约校验</b>（只校验不发送）。平台无「可编辑模板表」——'
        + '冻结事件 body 逐字节不可变，新事件必填字段由服务端强校验，避免模板漂移导致商户解析失败。')}`;
}
function ppNtCurrentTpl() {
  const name = ppVal('ntTplEvent');
  const rows = (LP['ntEvent'] && LP['ntEvent'].rows) || [];
  return rows.find(x => x.event === name) || PPNotify.events.find(x => x.event === name) || null;
}
function ppNtFillSkeleton() {
  const e = ppNtCurrentTpl();
  const ta = document.getElementById('ntTplPayload');
  if (!e || !ta) return;
  const req = e.requiredFields || [];
  const payload = {};
  req.forEach(k => { payload[k] = /amount|fee|net/i.test(k) ? 0 : '<' + k + '>'; });
  if (!req.length) { payload.bizId = '<业务号>'; payload.amount = 0; }
  ta.value = JSON.stringify(payload, null, 2);
  const box = document.getElementById('ntTplBox');
  if (box) box.innerHTML = ddBox('plain', `已载入 <b>${esc(e.event)}</b> 模板骨架（必填字段：${esc(req.join(', ') || '无')}）。
    修改后点「校验并渲染 body」走服务端契约校验；<b>不会发送任何通知</b>。`);
}
async function ppNtValidate() {
  const event = ppVal('ntTplEvent');
  if (!event) { toast('请选择事件', false); return; }
  const raw = ppVal('ntTplPayload');
  let payload;
  try { payload = raw ? JSON.parse(raw) : {}; }
  catch (e) { toast('payload 不是合法 JSON：' + e.message, false); return; }
  if (payload === null || typeof payload !== 'object' || Array.isArray(payload)) {
    toast('payload 必须是 JSON 对象', false); return;
  }
  const box = document.getElementById('ntTplBox');
  ppLoadingBox(box, '服务端契约校验中（只校验，不发送）…');
  const r = await ppTryPost('/platform/notify/templates/validate',
    { event, bizId: ppVal('ntTplBizId') || '<业务号>', payload });
  if (!r.ok) { ppErrBox(box, '模板校验', r.err); return; }
  const d = r.data || {};
  box.innerHTML = ddBox(d.valid ? 'info' : 'danger', d.valid
      ? '<b>契约校验通过</b>：必填字段齐备，下方即将投递的 body 形状与服务端一致。'
      : '<b>契约校验未通过</b>：缺少必填字段 ' + esc((d.missingFields || []).join(', '))) +
    ddKv([['事件', esc(d.event || event)], ['已登记', d.registered ? ppTag('on', '是') : ppTag('off', '否（按冻结事件原样透传）')],
      ['冻结事件', d.legacy ? ppTag('bad', '是（body 逐字节不变）') : ppTag('on', '否（envelope）')],
      ['必填字段', esc((d.requiredFields || []).join(', ') || '无')],
      ['缺失字段', (d.missingFields || []).length ? `<span class="neg">${esc(d.missingFields.join(', '))}</span>` : '无'],
      ['校验结论', d.valid ? ppTag('on', '通过') : ppTag('bad', '不通过')]]) +
    ddSec('将投递的 body（服务端渲染）', `<div class="pp-code light">${esc(JSON.stringify(d.renderedBody || {}, null, 2))}</div>`) +
    `<div class="pp-note">${esc(d.note || '')}</div>`;
  toast(d.valid ? '契约校验通过（未发送）' : '契约校验未通过', !!d.valid);
}
async function ppNtCounts(silent) {
  const box = document.getElementById('ntCountBox');
  if (!PPNotify.events.length) { const r = await tryApi('/notify/events'); if (r.ok) PPNotify.events = r.data || []; }
  if (!silent) ppLoadingBox(box, `按事件统计真实记录数（${PPNotify.events.length} 次只读分页查询，size=1）…`);
  const res = await Promise.all(PPNotify.events.map(e =>
    tryApi('/notify/query?event=' + encodeURIComponent(e.event) + '&page=1&size=1')));
  PPNotify.counts = {};
  res.forEach((r, i) => { if (r.ok) PPNotify.counts[PPNotify.events[i].event] = (r.data && r.data.total) || 0; });
  if (LP['ntEvent']) lpSet('ntEvent', LP['ntEvent'].rows, true);
  const total = Object.values(PPNotify.counts).reduce((s, x) => s + x, 0);
  const legacy = PPNotify.events.filter(e => e.legacy).reduce((s, e) => s + (PPNotify.counts[e.event] || 0), 0);
  const add = total - legacy;
  if (box) box.innerHTML = `<div class="stat-cards">
      <div class="stat-card accent"><div class="label">事件类型</div><div class="value">${PPNotify.events.length}</div></div>
      <div class="stat-card"><div class="label">legacy 冻结事件</div><div class="value">${PPNotify.events.filter(e => e.legacy).length}</div></div>
      <div class="stat-card ok"><div class="label">additive 新事件</div><div class="value">${PPNotify.events.filter(e => !e.legacy).length}</div></div>
      <div class="stat-card"><div class="label">通知总量（按事件合计）</div><div class="value">${total}</div></div>
      <div class="stat-card"><div class="label">冻结面 / 新增面</div><div class="value">${legacy} / ${add}</div></div>
    </div>${ppCons('事件计数守恒（legacy + additive = 总量）', legacy + add, total, { raw: true, leftLabel: 'legacy+additive', rightLabel: 'Σtotal' })}`;
  if (!silent) toast('事件计数已刷新');
}
function ppNtCsv() {
  const rows = LP['ntEvent'] ? lpVisible('ntEvent') : [];
  if (!rows.length) { toast('无事件可导出', false); return; }
  ppCsvLocal('notify-events.csv', ['事件', '说明', '冻结', '信封', '必填字段', '记录数', '幂等键'],
    rows.map(e => [e.event, e.description, e.legacy ? 'Y' : 'N', e.envelope ? 'Y' : 'N',
      (e.requiredFields || []).join('|'), PPNotify.counts[e.event], e.idempotentKey || 'event:bizId']));
}
async function ppNtDdTemplate(event) {
  if (!PPNotify.templates.length) {
    const r = await tryApi('/platform/notify/templates');
    if (r.ok) PPNotify.templates = (r.data && r.data.templates) || [];
  }
  const t = PPNotify.templates.find(x => x.event === event) ||
    (PPNotify.events || []).find(x => x.event === event);
  if (!t) return ddBox('warn', '未找到事件：' + esc(event));
  const cnt = await tryApi('/notify/query?event=' + encodeURIComponent(event) + '&page=1&size=5');
  const recs = cnt.ok ? ((cnt.data && cnt.data.records) || []) : [];
  const total = cnt.ok ? ((cnt.data && cnt.data.total) || 0) : null;
  const req = t.requiredFields || [];
  const skeleton = t.bodyTemplate || (t.legacy ? { bizId: '<业务号>', amount: '<分>' }
    : Object.assign({ event, bizId: '<业务号>', timestamp: '<毫秒>' }, req.reduce((o, k) => (o[k] = '<' + k + '>', o), {})));
  return ddKv([['事件', esc(event)], ['说明', esc(t.description || '—')],
      ['契约面', t.legacy ? ppTag('bad', 'legacy 冻结（body 逐字节不变）') : ppTag('on', 'additive（envelope）')],
      ['信封', t.envelope ? ppTag('on', 'envelope') : ppTag('off', '裸 payload')],
      ['必填字段', esc(req.join(', ') || '无强校验')],
      ['幂等键', esc(t.idempotentKey || 'event:bizId')],
      ['真实记录数', total == null ? '查询失败' : String(total)]]) +
    ddSec('body 模板（服务端契约渲染）', `<div class="pp-code light">${esc(JSON.stringify(skeleton, null, 2))}</div>`) +
    ddSec('最近通知记录', ddTable(['通知 ID', '业务号', '状态', 'appId', '创建时间'],
      recs.map(n => `<tr><td>${ddLink('notify', n.id, n.id)}</td><td class="pp-mono">${esc(n.bizId || '—')}</td>
        <td>${ppBadge(n.status, n.status)}</td><td class="pp-mono">${esc(n.appId || '—')}</td>
        <td>${fmtTime(n.createdAt)}</td></tr>`).join(''), '该事件暂无通知记录'), recs.length + ' 条') +
    `<div class="pp-inline" style="margin-top:8px">
      <button class="btn small ghost" data-pp-goto="notify-resend">查询与重发</button>
      <button class="btn small ghost" data-pp-goto="open-docs">API 文档</button></div>`;
}

/* ===================== 显式挂 window（历史坑：const 不进 window） ===================== */
window.PPNotify = PPNotify;
/* 兼容两种 nav 布线：loader:'PPNotify.loadResend'（点号路径）与 window[loader] 直取。 */
['loadResend', 'loadTemplates'].forEach(k => {
  window['PPNotify.' + k] = function () { return PPNotify[k](); };
});