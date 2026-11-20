/* =====================================================================
 * payment-pro 管理控制台 · 渠道运营页（IA 分组 6）
 * 端点：GET /api/channel/{configs,adapters,metrics,route,{code}/limit,trades/{bizNo}/side,bills,bills/{id}}
 *       POST /api/channel/{code}/limit · /{code}/maintenance · /bills/download · /bills/{id}/reconcile
 * 复用：lpDef/lpSet 客户端分页、#detailDrawer（channel / channelBill / dispute）、askConfirm + sensitiveApi、yuan/badge。
 * 顶层只声明，DOM 与全局依赖全部在 PPChannel.init() 内（由 index.html 内联脚本尾部调用）。
 * ===================================================================== */

const PPChannel = {
  configs: [], adapters: [], metrics: [], limits: [],
  init() {
    lpDef('chConfig', { table:'chConfigTable', timeField:'updatedAt', render:ppChConfigRows, filters:[
      { k:'kw', label:'关键字', ph:'渠道编码 / 名称 / 适配器', fields:['channelCode','name','adapterType','config'] },
      { k:'status', label:'启用状态', type:'select', field:'status', opts:[opt('','全部'),opt('ENABLED','ENABLED 启用'),opt('DISABLED','DISABLED 停用')] },
      { k:'env', label:'环境', type:'select', field:'env', opts:[opt('','全部'),opt('PROD','PROD 生产'),opt('SANDBOX','SANDBOX 沙箱')] },
      { k:'adapterType', label:'适配器类型', type:'select', field:'adapterType',
        opts:[opt('','全部'),opt('MOCK','MOCK'),opt('SANDBOX','SANDBOX'),opt('ECHO','ECHO')] } ] });
    lpDef('chAdapter', { table:'chAdapterTable', render:ppChAdapterRows, filters:[
      { k:'kw', label:'关键字', ph:'渠道编码 / SPI 实现类', fields:['channelCode','adapterClass','adapterType'] },
      { k:'async', label:'异步能力', type:'select', opts:[opt('','全部'),opt('true','支持异步回执'),opt('false','仅同步')],
        test:(r,v)=>String(!!r.asyncCapable)===v },
      { k:'bill', label:'对账文件', type:'select', opts:[opt('','全部'),opt('true','支持'),opt('false','不支持')],
        test:(r,v)=>String(!!r.billSupported)===v } ] });
    lpDef('chMetric', { table:'chMetricTable', timeField:'windowStart', render:ppChMetricRows, filters:[
      { k:'kw', label:'关键字', ph:'渠道编码', fields:['channelCode'] },
      { k:'breaker', label:'熔断', type:'select', opts:[opt('','全部'),opt('true','熔断打开'),opt('false','正常')],
        test:(r,v)=>String(!!r.breakerOpen)===v } ] });
    lpDef('chLimit', { table:'chLimitTable', render:ppChLimitRows, filters:[
      { k:'kw', label:'关键字', ph:'渠道编码', fields:['channelCode','reason'] },
      { k:'avail', label:'可用性', type:'select', opts:[opt('','全部'),opt('true','可用'),opt('false','不可用')],
        test:(r,v)=>String(!!r.available)===v } ] });

    const on = (id, fn) => { const el = document.getElementById(id); if (el) el.addEventListener('click', fn); };
    on('btnChRoute', () => ppChRoute());
    on('btnChLimitQuery', () => ppChLimitDetail());
    on('btnChLimitSave', () => ppChSaveLimit());
    on('btnChMaintSave', () => ppChSaveMaint(false));
    on('btnChMaintClear', () => ppChSaveMaint(true));
    on('btnChSide', () => ppChSide());
    on('btnChBillDownload', () => ppChBillDownload());
    const rd = document.getElementById('chRouteAmount');
    if (rd) rd.addEventListener('keydown', e => { if (e.key === 'Enter') ppChRoute(); });
    const sb = document.getElementById('chSideBizNo');
    if (sb) sb.addEventListener('keydown', e => { if (e.key === 'Enter') ppChSide(); });
    const lc = document.getElementById('chLimitCode');
    if (lc) lc.addEventListener('change', () => ppChLimitDetail());
    /* 行内操作：触发比对 / 差异明细（委托，避开既有 data-dd 行点击） */
    document.addEventListener('click', async ev => {
      const rc = ev.target.closest && ev.target.closest('[data-ch-reconcile]');
      if (rc) { ev.preventDefault(); ev.stopPropagation(); await ppChReconcile(rc.dataset.chReconcile); return; }
      const cf = ev.target.closest && ev.target.closest('[data-ch-cfg-limit]');
      if (cf) { ev.preventDefault(); PPNav.go('ch-limit'); const s = document.getElementById('chLimitCode');
        if (s) { s.value = cf.dataset.chCfgLimit; ppChLimitDetail(); } }
    });
  },
  fillSelects() {
    ['#chRouteCode', '#chLimitCode', '#chBillCode'].forEach(sel => {
      const el = document.querySelector(sel); if (!el) return;
      const old = el.value;
      const blank = sel === '#chRouteCode' ? '<option value="">自动择优</option>' : '<option value="">请选择渠道</option>';
      el.innerHTML = blank + PPChannel.configs.map(c =>
        `<option value="${esc(c.channelCode)}">${esc(c.channelCode)}${c.status === 'ENABLED' ? '' : '（停用）'}</option>`).join('');
      if (old) el.value = old;
    });
  }
};

/* ---------- 展示 helper：ppFen / ppBizDate / ppMs / ppPct / ppMeter 见 console-nav.js（共享） ---------- */

/* ---------- 6.1 配置与适配器 ---------- */
async function loadChConfig() {
  if (!hasPerm('channel:read')) { lpSet('chConfig', []); lpSet('chAdapter', []); return; }
  lpLoading('chConfig', 11); lpLoading('chAdapter', 7);
  const [c, a] = await Promise.all([tryApi('/channel/configs'), tryApi('/channel/adapters')]);
  if (c.ok && Array.isArray(c.data)) { PPChannel.configs = c.data; lpSet('chConfig', c.data, true); PPChannel.fillSelects(); }
  else lpError('chConfig', 11, (c.err && c.err.message) || '返回形状异常');
  if (a.ok && Array.isArray(a.data)) { PPChannel.adapters = a.data; lpSet('chAdapter', a.data, true); }
  else lpError('chAdapter', 7, (a.err && a.err.message) || '返回形状异常');
}
function ppChConfigRows(rows) {
  const t = document.getElementById('chConfigTable'); if (!t) return;
  const ad = {}; PPChannel.adapters.forEach(a => ad[a.channelCode] = a);
  t.innerHTML = `<tr><th>渠道编码</th><th>名称</th><th>SPI 适配器</th><th>启用</th><th>环境</th>
    <th class="num">费率(bp)</th><th class="num">权重</th><th class="num">单笔限额</th><th class="num">日限额</th><th>维护窗</th><th>操作</th></tr>` +
    (rows.length ? rows.map(c => {
      const a = ad[c.channelCode] || {};
      const maint = (c.maintenanceStart > 0 || c.maintenanceEnd > 0)
        ? `<span class="pp-tag warn">${ppMs(c.maintenanceStart)} → ${ppMs(c.maintenanceEnd)}</span>` : '<span class="muted">无</span>';
      return `<tr>
        <td><b>${esc(c.channelCode)}</b></td><td class="wrap">${esc(c.name || '—')}</td>
        <td><span class="badge kind">${esc(c.adapterType || '—')}</span> <span class="muted pp-mono">${esc(a.adapterClass || '—')}</span></td>
        <td><span class="badge ${esc(c.status)}">${esc(c.status || '—')}</span></td>
        <td>${c.env === 'SANDBOX' ? '<span class="pp-tag warn">沙箱</span>' : '<span class="pp-tag on">生产</span>'}</td>
        <td class="num">${c.feeBps != null ? c.feeBps : '—'}</td><td class="num">${c.weight != null ? c.weight : '—'}</td>
        <td class="num">${ppFen(c.singleLimit)}</td><td class="num">${ppFen(c.dailyLimit)}</td>
        <td>${maint}</td>
        <td><button class="btn small ghost" data-ch-cfg-limit="${esc(c.channelCode)}">限额/维护</button></td></tr>`;
    }).join('') : emptyRow(11, '暂无渠道配置'));
}
function ppChAdapterRows(rows) {
  const t = document.getElementById('chAdapterTable'); if (!t) return;
  t.innerHTML = `<tr><th>渠道编码</th><th>SPI 实现类</th><th>类型</th><th>环境</th><th>异步回执</th><th>对账文件</th><th>配置状态</th></tr>` +
    (rows.length ? rows.map(a => `<tr>
      <td><b>${esc(a.channelCode)}</b></td><td class="pp-mono">${esc(a.adapterClass || '—')}</td>
      <td><span class="badge kind">${esc(a.adapterType || '—')}</span></td>
      <td>${a.env === 'SANDBOX' ? '<span class="pp-tag warn">沙箱</span>' : '<span class="pp-tag on">生产</span>'}</td>
      <td>${a.asyncCapable ? '<span class="pp-tag info">支持</span>' : '<span class="pp-tag off">同步</span>'}</td>
      <td>${a.billSupported ? '<span class="pp-tag on">支持</span>' : '<span class="pp-tag off">不支持</span>'}</td>
      <td><span class="badge ${esc(a.configStatus)}">${esc(a.configStatus || '—')}</span></td></tr>`).join('')
      : emptyRow(7, '暂无 SPI 适配器注册'));
}

/* ---------- 6.2 路由与评分 ---------- */
async function loadChRoute() {
  if (!hasPerm('channel:read')) { lpSet('chMetric', []); return; }
  if (!PPChannel.configs.length) {
    const c = await tryApi('/channel/configs');
    if (c.ok && Array.isArray(c.data)) { PPChannel.configs = c.data; PPChannel.fillSelects(); }
  }
  lpLoading('chMetric', 8);
  const m = await tryApi('/channel/metrics');
  if (m.ok && Array.isArray(m.data)) { PPChannel.metrics = m.data; lpSet('chMetric', m.data, true); }
  else lpError('chMetric', 8, (m.err && m.err.message) || '返回形状异常');
  await ppChRoute();
}
function ppChMetricRows(rows) {
  const t = document.getElementById('chMetricTable'); if (!t) return;
  t.innerHTML = `<tr><th>渠道</th><th class="num">成功</th><th class="num">失败</th><th class="num">成功率</th>
    <th class="num">平均耗时(ms)</th><th class="num">样本</th><th>熔断</th><th>统计窗口起点</th></tr>` +
    (rows.length ? rows.map(m => `<tr>
      <td><b>${esc(m.channelCode)}</b></td><td class="num">${m.successCount != null ? m.successCount : '—'}</td>
      <td class="num ${m.failCount ? 'neg' : ''}">${m.failCount != null ? m.failCount : '—'}</td>
      <td class="num">${ppPct(m.successRate)}</td><td class="num">${m.avgCostMs != null ? m.avgCostMs : '—'}</td>
      <td class="num">${m.sampleCount != null ? m.sampleCount : '—'}</td>
      <td>${m.breakerOpen ? '<span class="pp-tag bad">熔断打开</span>' : '<span class="pp-tag on">闭合</span>'}</td>
      <td class="muted">${ppMs(m.windowStart)}</td></tr>`).join('') : emptyRow(8, '暂无渠道指标'));
}
async function ppChRoute() {
  const box = document.getElementById('chRouteResult'), cand = document.getElementById('chRouteCandTable');
  if (!box) return;
  const amtEl = document.getElementById('chRouteAmount');
  const params = new URLSearchParams();
  const yuanVal = Number(amtEl && amtEl.value);
  if (isFinite(yuanVal) && yuanVal > 0) params.set('amount', String(Math.round(yuanVal * 100)));
  ['#chRouteCode:channelCode', '#chRouteBin:cardBin', '#chRouteType:type'].forEach(pair => {
    const [sel, key] = pair.split(':'), el = document.querySelector(sel);
    if (el && el.value) params.set(key, el.value);
  });
  box.innerHTML = '<div class="box plain"><span class="spinner"></span>正在按受理口径试算路由…</div>';
  const r = await tryApi('/channel/route?' + params.toString());
  if (!r.ok) { box.innerHTML = `<div class="box danger">路由试算失败：${esc(r.err.message)}</div>`; if (cand) cand.innerHTML = ''; return; }
  const d = r.data || {};
  box.innerHTML = `<div class="box ok"><b>择优命中 ${esc(d.channelCode || '—')}</b> · 评分 <b>${d.score != null ? Number(d.score).toFixed(3) : '—'}</b><br>${esc(d.reason || '')}</div>`;
  const cs = d.candidates || [];
  if (cand) cand.innerHTML = `<tr><th>渠道</th><th>可选</th><th class="num">评分</th><th class="num">成功率</th>
    <th class="num">费率(bp)</th><th class="num">耗时(ms)</th><th class="num">权重</th><th class="num">单笔限额</th>
    <th class="num">日剩余</th><th>维护/熔断</th><th class="wrap">评分说明</th></tr>` +
    (cs.length ? cs.map(c => `<tr>
      <td><b>${esc(c.channelCode)}</b></td>
      <td>${c.eligible ? '<span class="pp-tag on">可选</span>' : '<span class="pp-tag bad">排除</span>'}</td>
      <td class="num">${c.score != null ? Number(c.score).toFixed(3) : '—'}</td>
      <td class="num">${ppPct(c.successRate)}</td><td class="num">${c.feeBps != null ? c.feeBps : '—'}</td>
      <td class="num">${c.avgCostMs != null ? c.avgCostMs : '—'}</td><td class="num">${c.weight != null ? c.weight : '—'}</td>
      <td class="num">${ppFen(c.singleLimit)}</td><td class="num">${ppFen(c.dailyRemaining)}</td>
      <td>${c.inMaintenance ? '<span class="pp-tag warn">维护中</span>' : ''}${c.breakerOpen ? '<span class="pp-tag bad">熔断</span>' : ''}${!c.inMaintenance && !c.breakerOpen ? '<span class="muted">—</span>' : ''}</td>
      <td class="muted wrap">${esc(c.reason || '—')}</td></tr>`).join('') : emptyRow(11, '无候选渠道'));
}

/* ---------- 6.3 限额与维护窗 ---------- */
async function loadChLimit() {
  if (!hasPerm('channel:read')) { lpSet('chLimit', []); return; }
  if (!PPChannel.configs.length) {
    const c = await tryApi('/channel/configs');
    if (c.ok && Array.isArray(c.data)) { PPChannel.configs = c.data; PPChannel.fillSelects(); }
  }
  lpLoading('chLimit', 10);
  const codes = PPChannel.configs.map(c => c.channelCode);
  const rs = await Promise.all(codes.map(c => tryApi('/channel/' + encodeURIComponent(c) + '/limit')));
  const rows = [];
  rs.forEach((r, i) => { if (r.ok && r.data && r.data.channelCode) rows.push(r.data); });
  PPChannel.limits = rows;
  lpSet('chLimit', rows, true);
  const sel = document.getElementById('chLimitCode');
  if (sel && !sel.value && codes.length) sel.value = codes[0];
  await ppChLimitDetail();
}
function ppChLimitRows(rows) {
  const t = document.getElementById('chLimitTable'); if (!t) return;
  t.innerHTML = `<tr><th>渠道</th><th class="num">单笔限额</th><th class="num">日限额</th><th class="num">今日已用</th>
    <th>日用量</th><th class="num">日剩余</th><th class="num">笔数</th><th>维护窗</th><th>可用</th><th>原因</th></tr>` +
    (rows.length ? rows.map(l => `<tr>
      <td><b>${esc(l.channelCode)}</b></td><td class="num">${ppFen(l.singleLimit)}</td><td class="num">${ppFen(l.dailyLimit)}</td>
      <td class="num">${ppFen(l.usedAmount)}</td><td style="min-width:120px">${ppMeter(l.usedAmount, l.dailyLimit)}</td>
      <td class="num">${ppFen(l.dailyRemaining)}</td><td class="num">${l.usedCount != null ? l.usedCount : '—'}</td>
      <td>${(l.maintenanceStart > 0 || l.maintenanceEnd > 0) ? `<span class="pp-tag warn">${ppMs(l.maintenanceStart)} → ${ppMs(l.maintenanceEnd)}</span>` : '<span class="muted">无</span>'}</td>
      <td>${l.available ? '<span class="pp-tag on">可用</span>' : '<span class="pp-tag bad">不可用</span>'}${l.inMaintenance ? ' <span class="pp-tag warn">维护中</span>' : ''}</td>
      <td class="muted">${esc(l.reason || '—')}</td></tr>`).join('') : emptyRow(10, '暂无渠道限额数据'));
}
async function ppChLimitDetail() {
  const sel = document.getElementById('chLimitCode'), box = document.getElementById('chLimitDetail');
  if (!box) return;
  const code = sel && sel.value;
  if (!code) { box.innerHTML = '<div class="box plain">请选择渠道后查看限额与维护窗。</div>'; return; }
  box.innerHTML = '<div class="box plain"><span class="spinner"></span>加载中…</div>';
  const r = await tryApi('/channel/' + encodeURIComponent(code) + '/limit');
  if (!r.ok) { box.innerHTML = `<div class="box danger">加载失败：${esc(r.err.message)}</div>`; return; }
  const l = r.data || {};
  box.innerHTML = ddKv([
    ['渠道', `<b>${esc(l.channelCode)}</b>`], ['会计日', ppBizDate(l.bizDate)],
    ['单笔限额（元）', ppFen(l.singleLimit)], ['日限额（元）', ppFen(l.dailyLimit)],
    ['今日已用（元）', ppFen(l.usedAmount)], ['今日笔数', l.usedCount != null ? l.usedCount : '—'],
    ['日剩余（元）', ppFen(l.dailyRemaining)], ['可用性', l.available ? '<span class="pp-tag on">可用</span>' : '<span class="pp-tag bad">不可用</span>'],
    ['维护窗', `${ppMs(l.maintenanceStart)} → ${ppMs(l.maintenanceEnd)}`],
    ['维护中', l.inMaintenance ? '<span class="pp-tag warn">是</span>' : '否'],
    ['判定原因', esc(l.reason || '—')],
    ['限额来源 / 维护来源', `${esc(l.limitSource || '—')} / ${esc(l.maintenanceSource || '—')}`] ]) +
    `<div style="margin-top:8px">${ppMeter(l.usedAmount, l.dailyLimit)}</div>`;
  const s = document.getElementById('chLimitSingle'), d = document.getElementById('chLimitDaily');
  if (s) s.value = l.singleLimit != null ? yuan(l.singleLimit) : '';
  if (d) d.value = l.dailyLimit != null ? yuan(l.dailyLimit) : '';
  const ms = document.getElementById('chMaintStart'), me2 = document.getElementById('chMaintEnd');
  const toLocal = v => { if (!v || Number(v) <= 0) return ''; const dt = new Date(Number(v));
    const p = n => String(n).padStart(2, '0');
    return `${dt.getFullYear()}-${p(dt.getMonth()+1)}-${p(dt.getDate())}T${p(dt.getHours())}:${p(dt.getMinutes())}`; };
  if (ms) ms.value = toLocal(l.maintenanceStart);
  if (me2) me2.value = toLocal(l.maintenanceEnd);
}
async function ppChSaveLimit() {
  const code = (document.getElementById('chLimitCode') || {}).value;
  if (!code) { toast('请先选择渠道', false); return; }
  let single = null, daily = null;
  try {
    const sv = (document.getElementById('chLimitSingle') || {}).value;
    const dv = (document.getElementById('chLimitDaily') || {}).value;
    if (sv !== '' && sv != null) single = toFen(sv);
    if (dv !== '' && dv != null) daily = toFen(dv);
  } catch (e) { toast(e.message, false); return; }
  const ok = await askConfirm({ title:'修改渠道限额', okText:'确认修改',
    html:`渠道 <b>${esc(code)}</b><br>单笔限额：<b>${single == null ? '不限（清空）' : '¥ ' + yuan(single)}</b><br>
      日限额：<b>${daily == null ? '不限（清空）' : '¥ ' + yuan(daily)}</b>
      <div class="box warn">限额直接影响受理与路由择优；清空表示不限。该操作需二次认证。</div>` });
  if (!ok) return;
  try {
    const r = await sensitiveApi('/channel/' + encodeURIComponent(code) + '/limit', 'POST',
      { singleLimit: single, dailyLimit: daily },
      `修改渠道 <b>${esc(code)}</b> 限额（单笔 ${single == null ? '不限' : yuan(single)} 元 / 日 ${daily == null ? '不限' : yuan(daily)} 元）`);
    toast('限额已更新：' + (r && r.channelCode ? r.channelCode : code));
    await loadChLimit();
  } catch (e) { toast(e.message, false); }
}
async function ppChSaveMaint(clear) {
  const code = (document.getElementById('chLimitCode') || {}).value;
  if (!code) { toast('请先选择渠道', false); return; }
  let start = 0, end = 0;
  if (!clear) {
    const sv = (document.getElementById('chMaintStart') || {}).value;
    const ev = (document.getElementById('chMaintEnd') || {}).value;
    if (!sv || !ev) { toast('请填写维护窗起止时间', false); return; }
    start = new Date(sv).getTime(); end = new Date(ev).getTime();
    if (!isFinite(start) || !isFinite(end) || end <= start) { toast('维护窗结束时间必须晚于开始时间', false); return; }
  }
  const ok = await askConfirm({ title: clear ? '清除维护窗' : '设置渠道维护窗', okText:'确认执行',
    html:`渠道 <b>${esc(code)}</b><br>${clear ? '清除维护窗后该渠道立即恢复可路由。' :
      `维护窗：<b>${fmtTime(start)}</b> → <b>${fmtTime(end)}</b><br>窗口内该渠道被路由排除，受理会改走其它渠道。`}
      <div class="box warn">该操作需二次认证。</div>` });
  if (!ok) return;
  try {
    await sensitiveApi('/channel/' + encodeURIComponent(code) + '/maintenance', 'POST', { start, end },
      `${clear ? '清除' : '设置'}渠道 <b>${esc(code)}</b> 维护窗`);
    toast(clear ? '维护窗已清除' : '维护窗已设置');
    await loadChLimit();
  } catch (e) { toast(e.message, false); }
}

/* ---------- 6.4 渠道流水 · 渠道侧直查 ---------- */
async function ppChSide() {
  const inp = document.getElementById('chSideBizNo'), box = document.getElementById('chSideResult');
  if (!box) return;
  const no = (inp && inp.value || '').trim();
  if (!no) { toast('请输入渠道业务号', false); return; }
  box.innerHTML = '<div class="box plain"><span class="spinner"></span>正在查询渠道侧（SPI 只读视图）…</div>';
  const r = await tryApi('/channel/trades/' + encodeURIComponent(no) + '/side');
  if (!r.ok) { box.innerHTML = `<div class="box danger">渠道侧查询失败：${esc(r.err.message)}</div>`; return; }
  const d = r.data || {};
  const pairs = Object.entries(d).filter(([k]) => k !== 'raw').map(([k, v]) =>
    [k, (v && typeof v === 'object') ? `<span class="pp-mono">${esc(JSON.stringify(v))}</span>`
      : (/amount/i.test(k) && typeof v === 'number') ? esc(yuan(v)) : esc(v)]);
  box.innerHTML = `<div class="box ok">渠道侧返回（与本地 CAS 快照 <button class="link-btn" data-dd="channel:${esc(no)}">本地流水 ${esc(no)}</button> 区分）</div>` +
    (pairs.length ? ddKv(pairs) : ddBox('plain', '渠道侧无返回字段')) +
    (d.raw ? `<div class="pp-code light">${esc(typeof d.raw === 'string' ? d.raw : JSON.stringify(d.raw))}</div>` : '');
}

/* ---------- 6.5 对账文件与比对 ---------- */
async function loadChBills() {
  if (!PPChannel.configs.length) {
    const c = await tryApi('/channel/configs');
    if (c.ok && Array.isArray(c.data)) { PPChannel.configs = c.data; PPChannel.fillSelects(); }
  }
  const dt = document.getElementById('chBillDate');
  if (dt && !dt.value) {
    const d = new Date(), p = n => String(n).padStart(2, '0');
    dt.value = `${d.getFullYear()}-${p(d.getMonth()+1)}-${p(d.getDate())}`;
  }
  await loadChannelBills();
}
async function ppChBillDownload() {
  const code = (document.getElementById('chBillCode') || {}).value;
  const date = ((document.getElementById('chBillDate') || {}).value || '').replace(/-/g, '');
  const box = document.getElementById('chBillResult');
  if (!code) { toast('请选择渠道', false); return; }
  if (!/^\d{8}$/.test(date)) { toast('请选择会计日（YYYY-MM-DD）', false); return; }
  const ok = await askConfirm({ title:'拉取渠道对账文件', okText:'确认拉取',
    html:`渠道 <b>${esc(code)}</b> · 会计日 <b>${ppBizDate(date)}</b>
      <div class="box warn">下载会落盘对账文件并登记解析结果；失败会登记差错单（不自动动账）。该操作需二次认证。</div>` });
  if (!ok) return;
  if (box) box.innerHTML = '<div class="box plain"><span class="spinner"></span>正在拉取…</div>';
  try {
    const b = await sensitiveApi('/channel/bills/download', 'POST', { channelCode: code, bizDate: date },
      `拉取渠道 <b>${esc(code)}</b> ${ppBizDate(date)} 对账文件`);
    if (box) box.innerHTML = `<div class="box ${b && b.status === 'FAILED' ? 'warn' : 'ok'}">
      账单 <b>${esc(b && b.id)}</b> · 状态 <span class="badge ${esc(b && b.status)}">${esc(b && b.status)}</span>
      · 笔数 ${b && b.totalCount != null ? b.totalCount : '—'} · 金额 ¥ ${yuan((b && b.totalAmount) || 0)}<br>${esc((b && b.message) || '')}
      ${b && b.id ? ` <button class="link-btn" data-dd="channelBill:${esc(b.id)}">查看账单详情</button>` : ''}</div>`;
    toast('对账文件已拉取');
    await loadChannelBills();
  } catch (e) { if (box) box.innerHTML = `<div class="box danger">拉取失败：${esc(e.message)}</div>`; toast(e.message, false); }
}
async function ppChReconcile(id) {
  const ok = await askConfirm({ title:'触发逐笔比对', okText:'确认比对',
    html:`渠道账单 <b>${esc(id)}</b><br>逐笔比对渠道明细与本地流水，差异会<b>登记差错单</b>（source=PAYMENT，策略 MANUAL，不自动动账）。
      <div class="box warn">该操作需二次认证。</div>` });
  if (!ok) return;
  try {
    const b = await sensitiveApi('/channel/bills/' + encodeURIComponent(id) + '/reconcile', 'POST', {},
      `对渠道账单 <b>${esc(id)}</b> 触发逐笔比对（差异登记差错单）`);
    toast(`比对完成：一致 ${(b && b.matchedCount) || 0} 笔 / 差异 ${(b && b.diffCount) || 0} 笔 / 差错单 ${(b && b.disputeCount) || 0} 个`);
    await loadChannelBills();
    if (b && b.id) ddPush('channelBill', b.id);
  } catch (e) { toast(e.message, false); }
}