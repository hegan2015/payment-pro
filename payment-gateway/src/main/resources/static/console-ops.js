/* =====================================================================
 * payment-pro 管理控制台 · 对账与差错（IA 分组 8）+ 支付编排（IA 分组 3）运营实页
 * ---------------------------------------------------------------------
 * 端点（实探后落地，零后端改动）：
 *  对账 /api/recon：daily(POST) · channel(POST) · channel-diffs · reports(服务端分页) ·
 *        reports/{id} · batches · progress · summary · dispute-sla ·
 *        reports/export.csv · dispute-sla/export.csv · channel-diffs/export.csv
 *  支付 /api/payments/{id}/close|cancel|race-repair|share(s) · /api/shares/{id}[/back|/execute] ·
 *        /api/refunds/{id} · /api/refunds/{id}/reconverge · /api/payments（编排观测）
 *  营销 /api/marketing-pay：best(只读试算) · pay · deduction/{pid} · alloc/{pid} ·
 *        conservation/{pid}(只读) · rollback/{pid} · realloc/{pid}
 * 复用：console-fund.js 的 ppCons/ppFlow/ppResult/ppCsv/ppAuditIds/ppSensitive/ppGuarded 等共享 helper，
 *       lpDef/lpSet 客户端分页 + renderPager 服务端分页、#detailDrawer（新增 4 类 kind）、
 *       askConfirm + sensitiveApi（敏感写）/ guardedApi（后端未强制 step-up 的触发类）。
 * 顶层只声明；DOM 与全局依赖全部在 PPOps.init() 内（index.html 尾部按序调用）。
 * ===================================================================== */

const PPOps = {
  batches: [], diffs: [], reports: [], shards: [], shardBatches: [], sla: [], summary: null,
  payments: [], payTs: 0, mktDetail: null,
  rrPage: 1, rrSize: 20,
  init() {
    lpDef('reconBatch', { table:'reconBatchTable', render:ppReconBatchRows, filters:[
      { k:'kw', label:'关键字', ph:'批次 ID / 报告 ID / 消息', fields:['id','reportId','message','channelCode'] },
      { k:'reconType', label:'类型', type:'select', field:'reconType', opts:[opt('','全部'),opt('DAILY','DAILY 日切批量'),opt('CHANNEL','CHANNEL 渠道内外')] },
      { k:'status', label:'状态', type:'select', field:'status', opts:[opt('','全部'),opt('RUNNING','RUNNING 进行中'),opt('SUCCESS','SUCCESS 成功'),opt('FAILED','FAILED 失败'),opt('PARTIAL','PARTIAL 部分失败')] },
      { k:'day', label:'会计日', ph:'2026-11-29', test:(r,v) => ppDayStr(r.bizDate) === String(v).trim() } ] });
    lpDef('channelDiff', { table:'channelDiffTable', render:ppChannelDiffRows, filters:[
      { k:'kw', label:'关键字', ph:'业务号 / 渠道订单号 / 差错单 / 建议', fields:['bizNo','channelOrderNo','disputeId','suggestion'] },
      { k:'channelCode', label:'渠道', ph:'MOCK_BANK', field:'channelCode' },
      { k:'diffType', label:'差异类型', type:'select', field:'diffType', opts:[opt('','全部'),opt('LONG','LONG 长款'),opt('SHORT','SHORT 短款'),opt('AMOUNT','AMOUNT 金额不符'),opt('STATUS','STATUS 状态不符'),opt('FEE','FEE 手续费差'),opt('MISSING','MISSING 缺失')] },
      { k:'status', label:'处置状态', type:'select', field:'status', opts:[opt('','全部'),opt('OPEN','OPEN 待处置'),opt('RESOLVED','RESOLVED 已处置'),opt('IGNORED','IGNORED 已驳回')] },
      { k:'day', label:'会计日', ph:'2026-11-29', test:(r,v) => ppDayStr(r.bizDate) === String(v).trim() } ] });
    lpDef('slaItem', { table:'slaTable', render:ppSlaRows, filters:[
      { k:'kw', label:'关键字', ph:'差错单号 / 类型 / 来源', fields:['disputeNo','type','source'] },
      { k:'status', label:'状态', type:'select', field:'status', opts:[opt('','全部'),opt('OPEN','OPEN'),opt('ASSIGNED','ASSIGNED'),opt('FIXING','FIXING'),opt('RESOLVED','RESOLVED'),opt('CLOSED','CLOSED'),opt('IGNORED','IGNORED')] },
      { k:'slow', label:'时效', type:'select', opts:[opt('','全部'),opt('true','超 1 小时未闭环'),opt('false','1 小时内')],
        test:(r,v) => String(ppSlaTotalMs(r) > 3600000) === v } ] });
    lpDef('shard', { table:'shardTable', render:ppShardRows, filters:[] });
    lpDef('shardBatch', { table:'shardBatchTable', render:ppShardBatchRows, filters:[
      { k:'kw', label:'关键字', ph:'批次 ID / 渠道 / 消息', fields:['id','channelCode','message','reportId'] },
      { k:'status', label:'状态', type:'select', field:'status', opts:[opt('','全部'),opt('RUNNING','RUNNING'),opt('SUCCESS','SUCCESS'),opt('FAILED','FAILED'),opt('PARTIAL','PARTIAL')] },
      { k:'retry', label:'重试', type:'select', opts:[opt('','全部'),opt('true','有重试（attempt>1）'),opt('false','首次即成功')], test:(r,v) => String((r.attempt || 1) > 1) === v } ] });
    lpDef('payOrch', { table:'payOrchTable', render:ppPayOrchRows, filters:[
      { k:'kw', label:'关键字', ph:'支付单 / 交易单 / requestId / 渠道单号', fields:['id','orderId','requestId','channelBizNo','xid'] },
      { k:'payMode', label:'支付模式', type:'select', field:'payMode', opts:[opt('','全部'),opt('SINGLE','SINGLE 单一'),opt('COMBO','COMBO 组合')] },
      { k:'marketingStatus', label:'营销状态', type:'select', field:'marketingStatus', opts:[opt('','全部'),opt('NONE','NONE 无营销'),opt('APPLIED','APPLIED 已抵扣'),opt('ROLLED_BACK','ROLLED_BACK 已回退'),opt('FAILED','FAILED 失败')] },
      { k:'status', label:'支付状态', type:'select', field:'status', opts:[opt('','全部'),opt('PROCESSING','处理中'),opt('SUCCESS','成功'),opt('FAILED','失败'),opt('REFUNDED','已退款'),opt('CLOSED','已关闭')] },
      ...DATE_FILTERS ] });
    lpDef('closeRev', { table:'closeRevTable', render:ppCloseRevRows, filters:[
      { k:'kw', label:'关键字', ph:'支付单 / 交易单 / requestId / 失败原因', fields:['id','orderId','requestId','failReason'] },
      { k:'only', label:'范围', type:'select', opts:[opt('','全部支付单'),opt('closed','仅关闭/撤销/失败'),opt('cancel','仅撤销（CANCEL）'),opt('race','仅竞态悬挂修复候选')],
        test:(r,v) => v === 'closed' ? (r.status === 'CLOSED' || r.status === 'FAILED' || !!r.closeType)
          : v === 'cancel' ? String(r.closeType || '').toUpperCase().includes('CANCEL')
          : v === 'race' ? (r.status === 'PROCESSING' || (r.status === 'CLOSED' && !!r.channelBizNo)) : true },
      { k:'closeType', label:'关闭类型', type:'select', field:'closeType', opts:[opt('','全部'),opt('CANCEL','CANCEL 撤销'),opt('TIMEOUT','TIMEOUT 超时'),opt('MANUAL','MANUAL 人工'),opt('USER','USER 用户')] },
      { k:'status', label:'状态', type:'select', field:'status', opts:[opt('','全部'),opt('PROCESSING','处理中'),opt('SUCCESS','成功'),opt('FAILED','失败'),opt('CLOSED','已关闭'),opt('REFUNDED','已退款')] },
      ...DATE_FILTERS ] });
    lpDef('mktPay', { table:'mktPayTable', render:ppMktPayRows, filters:[
      { k:'kw', label:'关键字', ph:'支付单 / 交易单 / requestId', fields:['id','orderId','requestId'] },
      { k:'marketingStatus', label:'营销状态', type:'select', field:'marketingStatus', opts:[opt('','全部'),opt('APPLIED','APPLIED 已抵扣'),opt('ROLLED_BACK','ROLLED_BACK 已回退'),opt('FAILED','FAILED 失败'),opt('PARTIAL','PARTIAL 部分')] },
      { k:'status', label:'支付状态', type:'select', field:'status', opts:[opt('','全部'),opt('SUCCESS','成功'),opt('FAILED','失败'),opt('PROCESSING','处理中'),opt('REFUNDED','已退款'),opt('CLOSED','已关闭')] },
      ...DATE_FILTERS ] });

    Object.assign(DD_KINDS, { reconBatch:'对账批次', reconReport:'对账报告', channelDiff:'渠道差异', marketingPay:'营销抵扣' });
    Object.assign(DD_RENDER, { reconBatch:ddReconBatch, reconReport:ddReconReport, channelDiff:ddChannelDiff, marketingPay:ddMarketingPay });

    const on = (id, fn) => { const el = document.getElementById(id); if (el) el.addEventListener('click', async () => {
      el.disabled = true;
      try { await fn(); } catch (e) { toast(e.message, false); } finally { el.disabled = false; } }); };
    /* 对账 · 日切批量 */
    on('btnRdRun', ppRdRun);
    on('btnRdProgress', () => ppRdProgress());
    on('btnRdQuery', () => ppRdQuery());
    /* 对账 · 渠道内外 */
    on('btnRcRun', ppRcRun);
    on('btnRcQuery', () => ppRcQuery());
    on('btnRcCsv', () => ppCsv('/recon/channel-diffs/export.csv' + ppRcQs(), 'recon-channel-diffs.csv'));
    /* 对账 · 报表 */
    on('btnRrQuery', () => ppRrQuery(1));
    on('btnRrCsv', () => ppCsv('/recon/reports/export.csv' + ppRrQs(), 'recon-reports.csv'));
    on('btnRsQuery', () => ppRsQuery());
    on('btnSlaQuery', () => ppSlaQuery());
    on('btnSlaCsv', () => ppCsv('/recon/dispute-sla/export.csv' + (ppVal('slaDate') ? '?bizDate=' + ppDayMs(ppVal('slaDate')) : ''), 'dispute-sla.csv'));
    /* 对账 · 分片 */
    on('btnShQuery', () => ppShQuery());
    on('btnShRerun', ppShRerun);
    /* 支付 · 编排 */
    on('btnPoShares', () => ppPoShares());
    on('btnPoRefundFind', ppPoRefundFind);
    on('btnPoRefund', ppPoRefund);
    on('btnPoReconverge', ppPoReconverge);
    on('btnPoRefresh', () => loadPayOrchestration(true));
    /* 支付 · 关闭与撤销 */
    on('btnCrClose', () => ppCrOp('close'));
    on('btnCrCancel', () => ppCrOp('cancel'));
    on('btnCrRace', () => ppCrOp('race-repair'));
    on('btnCrVoucher', () => { const b = ppVal('crBizNo'); if (!b) throw new Error('请填写凭证号 / 业务号'); ddPush('voucher', b); });
    on('btnCrRecent', ppCrRecent);
    /* 支付 · 营销抵扣编排 */
    on('btnMpBest', ppMpBest);
    on('btnMpQuery', () => ppMpQuery());
    on('btnMpRollback', () => ppMpOp('rollback'));
    on('btnMpRealloc', () => ppMpOp('realloc'));
    on('btnMpRefresh', () => loadMarketingPay(true));

    document.addEventListener('click', async ev => {
      const t = ev.target; if (!t.closest) return;
      const rd = t.closest('[data-rd-report]');
      if (rd) { ev.preventDefault(); ev.stopPropagation(); ddPush('reconReport', rd.dataset.rdReport); return; }
      const mp = t.closest('[data-mp-open]');
      if (mp) { ev.preventDefault(); ev.stopPropagation();
        const i = document.getElementById('mpPayId'); if (i) i.value = mp.dataset.mpOpen;
        PPNav.go('marketing-pay'); await ppMpQuery(); return; }
      const po = t.closest('[data-po-share]');
      if (po) { ev.preventDefault(); ev.stopPropagation();
        const i = document.getElementById('poPayId'); if (i) i.value = po.dataset.poShare;
        await ppPoShares(); return; }
      const rf = t.closest('[data-refund-reconverge]');
      if (rf) { ev.preventDefault(); ev.stopPropagation(); await ppPoReconverge(rf.dataset.refundReconverge); return; }
      const cr = t.closest('[data-cr-fill]');
      if (cr) { ev.preventDefault(); ev.stopPropagation();
        const i = document.getElementById('crPayId'); if (i) i.value = cr.dataset.crFill;
        PPNav.go('close-reverse'); toast('已载入支付单 ' + cr.dataset.crFill + '，选择处置动作'); return; }
    });
    [['rdDate', ppRdProgress], ['rcDate', ppRcQuery], ['rrDate', () => ppRrQuery(1)],
     ['poPayId', ppPoShares], ['poRefundId', ppPoRefund], ['crPayId', () => {}],
     ['mpPayId', ppMpQuery], ['mpMember', ppMpBest]].forEach(([id, fn]) => {
      const el = document.getElementById(id);
      if (el && fn) el.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); fn(); } });
    });
  },
  /** /api/payments 全量缓存（30s TTL，避免同页多次拉取 1900+ 行） */
  async payRows(force) {
    if (!force && PPOps.payments.length && Date.now() - PPOps.payTs < 30000) return PPOps.payments;
    const r = await tryApi('/payments');
    if (!r.ok) throw r.err;
    PPOps.payments = Array.isArray(r.data) ? r.data : [];
    PPOps.payTs = Date.now();
    return PPOps.payments;
  }
};

/* ===================== 8.2 对账 · 日切批量对账 ===================== */
async function loadReconDaycut() {
  if (!hasPerm('recon:read')) { lpSet('reconBatch', []); return; }
  await Promise.all([ppRdQuery(true), ppRdProgress(true)]);
}
async function ppRdQuery(silent) {
  lpLoading('reconBatch', 12);
  const qs = [];
  const day = ppDayMs(ppVal('rdFilterDate')), type = ppVal('rdFilterType');
  if (day != null) qs.push('bizDate=' + day);
  if (type) qs.push('reconType=' + encodeURIComponent(type));
  const r = await tryApi('/recon/batches' + (qs.length ? '?' + qs.join('&') : ''));
  if (!r.ok) { lpError('reconBatch', 12, r.err.message); return; }
  PPOps.batches = Array.isArray(r.data) ? r.data : [];
  lpSet('reconBatch', PPOps.batches, !!silent);
}
function ppReconBatchRows(rows) {
  const t = document.getElementById('reconBatchTable'); if (!t) return;
  t.innerHTML = `<tr><th>批次 ID</th><th>会计日</th><th>类型</th><th>渠道</th><th>分片</th>
    <th class="num">重试</th><th>进度</th><th class="num">通过/失败</th><th class="num">差错单</th><th>状态</th><th>报告</th><th>说明</th></tr>` +
    (rows.length ? rows.map(b => {
      const pct = b.total > 0 ? Math.round(b.checked / b.total * 100) : (b.status === 'SUCCESS' ? 100 : 0);
      return `<tr data-dd="reconBatch:${esc(b.id)}">
        <td class="dd-cell"><b>${esc(b.id)}</b></td><td>${ppBizDate(b.bizDate)}</td>
        <td>${ppBadge('kind', b.reconType)}</td><td>${esc(b.channelCode || '—')}</td>
        <td class="pp-mono">${ppNum(b.shardIndex)}/${ppNum(b.shardTotal)}</td>
        <td class="num">${ppNum(b.attempt)}</td>
        <td style="min-width:110px">${ppMeter(b.total > 0 ? b.checked : pct, b.total > 0 ? b.total : 100)}</td>
        <td class="num">${ppNum(b.passed)} / ${ppNum(b.failed)}</td>
        <td class="num ${b.disputeCount > 0 ? 'neg' : ''}">${ppNum(b.disputeCount)}</td>
        <td>${ppBadge(b.status === 'SUCCESS' ? 'SETTLED' : (b.status === 'FAILED' ? 'FAILED' : 'PROCESSING'), b.status)}</td>
        <td>${b.reportId ? `<button class="link-btn" data-rd-report="${esc(b.reportId)}">${esc(b.reportId)}</button>` : '—'}</td>
        <td class="wrap" style="font-size:12px">${esc(b.message || '—')}</td></tr>`;
    }).join('') : emptyRow(12, '暂无对账批次（触发日切批量对账后生成）'));
}
async function ppRdProgress(silent) {
  const day = ppDayMs(ppVal('rdDate')), type = ppVal('rdType') || 'DAILY';
  const qs = ['reconType=' + encodeURIComponent(type)];
  if (day != null) qs.push('bizDate=' + day);
  ppLoadingBox('rdProgressBox', '加载对账进度…');
  const r = await tryApi('/recon/progress?' + qs.join('&'));
  if (!r.ok) { ppErrBox('rdProgressBox', '对账进度', r.err); return; }
  const p = r.data || {};
  const pct = p.total > 0 ? Math.round(p.checked / p.total * 100) : (p.status === 'SUCCESS' ? 100 : 0);
  document.getElementById('rdProgressBox').innerHTML = `<div class="stat-cards">
      <div class="stat-card"><div class="label">会计日 / 类型</div><div class="value" style="font-size:15px">${ppBizDate(p.bizDate)} · ${esc(p.reconType || type)}</div></div>
      <div class="stat-card accent"><div class="label">进度</div><div class="value">${pct}%</div></div>
      <div class="stat-card ok"><div class="label">通过项</div><div class="value">${ppNum(p.passed)}</div></div>
      <div class="stat-card ${p.failed > 0 ? 'bad' : ''}"><div class="label">失败项</div><div class="value">${ppNum(p.failed)}</div></div>
      <div class="stat-card ${p.disputeCount > 0 ? 'warn' : ''}"><div class="label">差错单</div><div class="value">${ppNum(p.disputeCount)}</div></div>
    </div>
    <div class="pp-inline"><div class="pp-meter ${p.failed > 0 ? 'bad' : ''}" style="flex:1"><i style="width:${pct}%"></i></div>
      ${ppBadge(p.status === 'SUCCESS' ? 'SETTLED' : (p.status === 'FAILED' ? 'FAILED' : 'PROCESSING'), p.status)}</div>
    ${ppCons('核对项守恒 通过 + 失败 = 总项', Number(p.passed || 0) + Number(p.failed || 0), Math.max(Number(p.passed || 0) + Number(p.failed || 0), Number(p.total || 0)),
      { raw:true, leftLabel:'passed+failed', rightLabel:'total(口径)', note:'total 为逐笔核对基数（0 表示按检查项口径汇总），passed/failed 为六项核对结果计数。' })}
    ${(p.shards || []).length ? `<div class="dd-sec"><h4>分片进度<span class="cnt">${p.shards.length} 片</span></h4>
      ${ddTable(['分片','总数','已核对','通过','失败','差错','状态','更新时间'],
        p.shards.map(s => `<tr><td class="pp-mono">${ppNum(s.shardIndex)}/${ppNum(s.shardTotal)}</td>
          <td class="num">${ppNum(s.total)}</td><td class="num">${ppNum(s.checked)}</td>
          <td class="num">${ppNum(s.passed)}</td><td class="num ${s.failed > 0 ? 'neg' : ''}">${ppNum(s.failed)}</td>
          <td class="num">${ppNum(s.disputeCount)}</td><td>${ppBadge(s.status === 'SUCCESS' ? 'SETTLED' : 'PROCESSING', s.status)}</td>
          <td class="muted">${ppMs(s.updatedAt)}</td></tr>`).join(''))}</div>` : ''}`;
  if (!silent) toast('对账进度已刷新');
}
async function ppRdRun() {
  const day = ppDayMs(ppVal('rdDate'));
  const qs = day != null ? '?bizDate=' + day : '';
  const r = await ppGuarded('触发日切批量对账',
    `<div class="summary">会计日 <b>${day == null ? '当前会计日' : ppDayStr(day)}</b> · 六项口径与实时对账一致</div>
     <div class="box warn">逐笔回查五域（长耗时，网关超时放宽至 120s）。<b>幂等重跑不重复建单</b>；发现差异会登记差错单（不自动动账）。
     默认不自动跑批，需运营显式触发。</div>`,
    '/recon/daily' + qs, 'POST', {}, '确认跑批');
  if (!r) return;
  ppResult('rdRunBox', '批量对账已执行', Array.isArray(r) ? { checks: r.length, items: r } : r);
  toast('日切批量对账完成');
  await Promise.all([ppRdQuery(true), ppRdProgress(true)]);
}
async function ddReconBatch(id) {
  const b = PPOps.batches.find(x => x.id === id) ||
    (await (async () => { const r = await tryApi('/recon/batches'); return r.ok && Array.isArray(r.data) ? r.data.find(x => x.id === id) : null; })());
  if (!b) return ddBox('danger', '对账批次不存在：' + esc(id));
  let html = ddKv([
    ['批次 ID', `<b class="pp-mono">${esc(b.id)}</b>`], ['会计日', ppBizDate(b.bizDate)],
    ['类型', ppBadge('kind', b.reconType)], ['渠道', esc(b.channelCode || '全渠道')],
    ['分片', `${ppNum(b.shardIndex)} / ${ppNum(b.shardTotal)}`], ['重试次数', ppNum(b.attempt)],
    ['状态', ppBadge(b.status === 'SUCCESS' ? 'SETTLED' : (b.status === 'FAILED' ? 'FAILED' : 'PROCESSING'), b.status)],
    ['总数 / 已核对', `${ppNum(b.total)} / ${ppNum(b.checked)}`],
    ['通过 / 失败', `${ppNum(b.passed)} / ${ppNum(b.failed)}`],
    ['差错单数', ppNum(b.disputeCount)],
    ['报告', b.reportId ? `<button class="link-btn" data-rd-report="${esc(b.reportId)}">${esc(b.reportId)}</button>` : '—'],
    ['创建 / 更新', `${ppMs(b.createdAt)} / ${ppMs(b.updatedAt)}`] ]);
  html += ddSec('进度', ppMeter(b.total > 0 ? b.checked : (b.status === 'SUCCESS' ? 100 : 0), b.total > 0 ? b.total : 100) +
    `<div class="pp-note">${esc(b.message || '')}</div>`);
  html += ddSec('状态机', ppFlow(['RUNNING','SUCCESS'], b.status, ['FAILED','PARTIAL']));
  if (b.reportId) {
    const r = await tryApi('/recon/reports/' + encodeURIComponent(b.reportId));
    if (r.ok) html += ddSec('对账报告六项', ppReconChecks(r.data));
  }
  html += ddSec('关联', `<div class="pp-inline"><button class="btn small ghost" data-pp-goto="recon-shard">分片调度</button>
    <button class="btn small ghost" data-pp-goto="disputes">差错平台</button>
    <button class="btn small ghost" data-pp-goto="recon-report">对账报表</button></div>`);
  return html;
}
function ppReconChecks(rep) {
  const checks = (rep && rep.checks) || [];
  if (!checks.length) return ddBox('plain', '无检查项明细');
  return ddTable(['检查项','结果','明细','差错类型','缺失单号'],
    checks.map(c => `<tr><td>${esc(c.name)}</td>
      <td>${ppBadge(c.pass ? 'APPROVED' : 'REJECTED', c.pass ? '通过' : '不通过')}</td>
      <td class="wrap" style="font-size:12px">${esc(c.detail || '—')}</td>
      <td>${c.disputeType ? ppBadge('kind', c.disputeType) : '—'}</td>
      <td class="pp-mono" style="font-size:11px">${(c.missingBizNos || []).slice(0, 8).map(esc).join(', ') || '—'}</td></tr>`).join(''));
}

/* ===================== 8.3 对账 · 渠道内外对账 ===================== */
async function loadReconCross() {
  if (!hasPerm('recon:read')) { lpSet('channelDiff', []); return; }
  await ppRcQuery(true);
}
function ppRcQs() {
  const qs = [];
  const code = ppVal('rcFilterCode') || ppVal('rcCode'), day = ppDayMs(ppVal('rcFilterDate') || ppVal('rcDate'));
  if (code) qs.push('channelCode=' + encodeURIComponent(code));
  if (day != null) qs.push('bizDate=' + day);
  return qs.length ? '?' + qs.join('&') : '';
}
async function ppRcQuery(silent) {
  lpLoading('channelDiff', 12);
  const r = await tryApi('/recon/channel-diffs' + ppRcQs());
  if (!r.ok) { lpError('channelDiff', 12, r.err.message); return; }
  PPOps.diffs = Array.isArray(r.data) ? r.data : [];
  lpSet('channelDiff', PPOps.diffs, !!silent);
  ppRcSum();
}
function ppChannelDiffRows(rows) {
  const t = document.getElementById('channelDiffTable'); if (!t) return;
  t.innerHTML = `<tr><th>ID</th><th>渠道</th><th>会计日</th><th>业务号</th><th>差异类型</th>
    <th class="num">内部金额</th><th class="num">外部金额</th><th class="num">差额</th><th>内部/外部状态</th>
    <th>处置状态</th><th>差错单</th><th>处置建议</th></tr>` +
    (rows.length ? rows.map(d => {
      const diff = Number(d.externalAmount || 0) - Number(d.internalAmount || 0);
      return `<tr data-dd="channelDiff:${esc(d.id)}">
        <td class="dd-cell">${esc(d.id)}</td><td>${esc(d.channelCode)}</td><td>${ppBizDate(d.bizDate)}</td>
        <td>${ddLink('payment', d.bizNo, d.bizNo)}</td>
        <td>${ppBadge(d.diffType === 'LONG' ? 'SETTLED' : (d.diffType === 'SHORT' ? 'REJECTED' : 'CHALLENGE'), d.diffType)}</td>
        <td class="num">${ppFen(d.internalAmount)}</td><td class="num">${ppFen(d.externalAmount)}</td>
        <td class="num ${diff === 0 ? '' : 'neg'}"><b>${ppFen(diff)}</b></td>
        <td style="font-size:12px">${esc(d.internalStatus || '—')} / ${esc(d.externalStatus || '—')}</td>
        <td>${ppBadge(d.status === 'OPEN' ? 'OPEN' : 'RESOLVED', d.status)}</td>
        <td>${d.disputeId ? ddLink('dispute', d.disputeId, d.disputeId) : '—'}</td>
        <td class="wrap" style="font-size:12px">${esc(d.suggestion || '—')}</td></tr>`;
    }).join('') : emptyRow(12, '暂无渠道内外差异（触发渠道对账后生成）'));
}
function ppRcSum() {
  const box = document.getElementById('rcSumBox'); if (!box) return;
  const rows = PPOps.diffs;
  if (!rows.length) { box.innerHTML = '<div class="box info">当前过滤条件下无渠道差异，内外账一致。</div>'; return; }
  const byType = {}, byStatus = {};
  let long = 0, short = 0;
  rows.forEach(d => {
    byType[d.diffType] = (byType[d.diffType] || 0) + 1;
    byStatus[d.status] = (byStatus[d.status] || 0) + 1;
    const diff = Number(d.externalAmount || 0) - Number(d.internalAmount || 0);
    if (diff > 0) long += diff; else short += -diff;
  });
  box.innerHTML = `<div class="stat-cards">
      <div class="stat-card warn"><div class="label">差异笔数</div><div class="value">${rows.length}</div></div>
      <div class="stat-card bad"><div class="label">长款合计（元）</div><div class="value">${yuan(long)}</div></div>
      <div class="stat-card bad"><div class="label">短款合计（元）</div><div class="value">${yuan(short)}</div></div>
      <div class="stat-card"><div class="label">待处置 OPEN</div><div class="value">${byStatus.OPEN || 0}</div></div>
    </div>
    <div class="pp-inline">${Object.entries(byType).map(([k, v]) => ppTag(k === 'LONG' ? 'warn' : (k === 'SHORT' ? 'bad' : 'info'), `${k} ${v}`)).join('')}
      ${Object.entries(byStatus).map(([k, v]) => ppTag(k === 'OPEN' ? 'warn' : 'on', `${k} ${v}`)).join('')}</div>
    ${ppCons('长款 − 短款 = 净差额', long, short, { leftLabel:'长款Σ', rightLabel:'短款Σ',
      note:'长款=渠道多记（外部&gt;内部），短款=渠道少记；账单差异不自动动账，需人工确认后红冲蓝补。' })}
    <div class="pp-inline" style="margin-top:8px"><button class="btn small ghost" data-pp-goto="disputes">前往差错平台处置</button>
      <button class="btn small ghost" data-pp-goto="ch-bills">渠道对账文件</button></div>`;
}
async function ppRcRun() {
  const code = ppVal('rcCode'), day = ppDayMs(ppVal('rcDate'));
  const qs = [];
  if (code) qs.push('channelCode=' + encodeURIComponent(code));
  if (day != null) qs.push('bizDate=' + day);
  const r = await ppGuarded('触发渠道内外对账',
    `<div class="summary">渠道 <b>${esc(code || '全部渠道')}</b> · 会计日 <b>${day == null ? '当前会计日' : ppDayStr(day)}</b></div>
     <div class="box warn">内部渠道流水 vs 外部账单逐笔比对，按长款/短款/金额/状态/手续费分类<b>建差错单 + 处置建议</b>（不自动动账）。长耗时操作。</div>`,
    '/recon/channel' + (qs.length ? '?' + qs.join('&') : ''), 'POST', {}, '确认比对');
  if (!r) return;
  ppResult('rcRunBox', '渠道对账已执行', Array.isArray(r) ? { diffs: r.length, items: r } : r);
  toast('渠道内外对账完成');
  await ppRcQuery(true);
}
async function ddChannelDiff(id) {
  const d = PPOps.diffs.find(x => String(x.id) === String(id));
  if (!d) return ddBox('danger', '渠道差异不在当前列表，请先查询：' + esc(id));
  const diff = Number(d.externalAmount || 0) - Number(d.internalAmount || 0);
  let html = ddKv([
    ['差异 ID', esc(d.id)], ['渠道', esc(d.channelCode)], ['会计日', ppBizDate(d.bizDate)],
    ['业务号', ddLink('payment', d.bizNo, d.bizNo)], ['渠道订单号', `<span class="pp-mono">${esc(d.channelOrderNo || '—')}</span>`],
    ['差异类型', ppBadge('kind', d.diffType)],
    ['内部金额（元）', ppFen(d.internalAmount)], ['外部金额（元）', ppFen(d.externalAmount)],
    ['内部手续费 / 外部', `${ppFen(d.internalFee)} / ${ppFen(d.externalFee)}`],
    ['内部状态 / 外部', `${esc(d.internalStatus || '—')} / ${esc(d.externalStatus || '—')}`],
    ['处置状态', ppBadge(d.status === 'OPEN' ? 'OPEN' : 'RESOLVED', d.status)],
    ['差错单', d.disputeId ? ddLink('dispute', d.disputeId, d.disputeId) : '—'],
    ['创建 / 更新', `${ppMs(d.createdAt)} / ${ppMs(d.updatedAt)}`] ]);
  html += ddSec('长短款判定', ppCons('外部 − 内部 = 差额', Number(d.externalAmount || 0), Number(d.internalAmount || 0),
    { leftLabel:'external', rightLabel:'internal', note: diff > 0 ? '长款：渠道多记，需核实是否重复扣款/未达账' : (diff < 0 ? '短款：渠道少记，需核实是否漏扣/掉单' : '金额一致，差异在状态或手续费维度') }));
  html += ddSec('处置建议', ddBox('warn', esc(d.suggestion || '人工核对账单与平台流水')));
  const ch = await tryApi('/channel/trades/' + encodeURIComponent(d.bizNo));
  if (ch.ok && ch.data && ch.data.bizNo) html += ddSec('内部渠道流水', ddChannelTable([ch.data]));
  html += ddSec('关联', `<div class="pp-inline"><button class="btn small ghost" data-pp-goto="disputes">差错平台</button>
    <button class="btn small ghost" data-pp-goto="recon-cross">渠道内外对账</button></div>`);
  return html;
}

/* ===================== 8.4 对账 · 报表 / 汇总 / 差错时效 ===================== */
async function loadReconReport() {
  if (!hasPerm('recon:read')) { lpSet('slaItem', []); return; }
  await Promise.all([ppRrQuery(1, true), ppRsQuery(true), ppSlaQuery(true)]);
}
function ppRrQs() {
  const qs = [];
  const day = ppDayMs(ppVal('rrDate')), type = ppVal('rrType');
  if (day != null) qs.push('bizDate=' + day);
  if (type) qs.push('reconType=' + encodeURIComponent(type));
  return qs.length ? '?' + qs.join('&') : '';
}
async function ppRrQuery(page, silent) {
  PPOps.rrPage = page || 1;
  const t = document.getElementById('rrTable');
  if (t) loadingRow(t, 8);
  const sep = ppRrQs() ? '&' : '?';
  const r = await tryApi('/recon/reports' + ppRrQs() + sep + 'page=' + PPOps.rrPage + '&size=' + PPOps.rrSize);
  if (!r.ok) { if (t) errorRow(t, 8, r.err.message); return; }
  const d = r.data || {}, items = d.items || [];
  PPOps.reports = items;
  t.innerHTML = `<tr><th>报告 ID</th><th>会计日</th><th>类型</th><th>渠道</th><th>结果</th>
    <th class="num">检查项</th><th class="num">差错单</th><th>核对时间</th></tr>` +
    (items.length ? items.map(x => `<tr data-dd="reconReport:${esc(x.id)}">
      <td class="dd-cell"><b>${esc(x.id)}</b></td><td>${x.bizDate == null ? '—' : ppBizDate(x.bizDate)}</td>
      <td>${ppBadge('kind', x.reconType || 'REALTIME')}</td><td>${esc(x.channelCode || '—')}</td>
      <td>${ppBadge(x.pass ? 'APPROVED' : 'REJECTED', x.pass ? '全部通过' : '存在不一致')}</td>
      <td class="num">${(x.checks || []).length}</td>
      <td class="num ${(x.disputeIds || []).length ? 'neg' : ''}">${(x.disputeIds || []).length}</td>
      <td class="muted">${fmtTime(x.checkedAt)}</td></tr>`).join('') : emptyRow(8, '该条件无对账报告'));
  renderPager(document.getElementById('rrPager'), d.page || PPOps.rrPage, d.size || PPOps.rrSize, d.total || 0,
    p => ppRrQuery(p), sz => { PPOps.rrSize = sz; ppRrQuery(1); });
  if (!silent) toast('对账报告 ' + (d.total || 0) + ' 条');
}
async function ddReconReport(id) {
  const r = await tryApi('/recon/reports/' + encodeURIComponent(id));
  if (!r.ok) return ddBox('danger', '对账报告加载失败：' + esc(r.err.message));
  const x = r.data || {};
  let html = ddKv([
    ['报告 ID', `<b class="pp-mono">${esc(x.id)}</b>`],
    ['会计日', x.bizDate == null ? '—' : ppBizDate(x.bizDate)],
    ['类型', ppBadge('kind', x.reconType || 'REALTIME')], ['渠道', esc(x.channelCode || '全渠道')],
    ['结果', ppBadge(x.pass ? 'APPROVED' : 'REJECTED', x.pass ? '全部通过' : '存在不一致')],
    ['核对时间', ppMs(x.checkedAt)],
    ['差错单', (x.disputeIds || []).length ? (x.disputeIds || []).map(n => ddLink('dispute', n, n)).join(' ') : '无'] ]);
  html += ddSec('六项核对', ppReconChecks(x));
  const failed = (x.checks || []).filter(c => !c.pass);
  html += ddSec('守恒', ppCons('通过项 + 失败项 = 检查项总数',
    (x.checks || []).length - failed.length, (x.checks || []).length - failed.length,
    { raw:true, leftLabel:'passed', rightLabel:'passed', note:`共 ${(x.checks || []).length} 项，失败 ${failed.length} 项${failed.length ? '（红色告警，需按差错单处置）' : '（diff=0）'}` }));
  html += ddSec('关联', `<div class="pp-inline"><button class="btn small ghost" data-pp-goto="recon">对账运行与报告</button>
    <button class="btn small ghost" data-pp-goto="disputes">差错平台</button></div>`);
  return html;
}
async function ppRsQuery(silent) {
  const day = ppDayMs(ppVal('rsDate'));
  ppLoadingBox('rsBox', '加载对账汇总…');
  const r = await tryApi('/recon/summary' + (day != null ? '?bizDate=' + day : ''));
  if (!r.ok) { ppErrBox('rsBox', '对账汇总', r.err); return; }
  const s = r.data || {}; PPOps.summary = s;
  const dimTable = (title, arr) => `<div class="dd-sec"><h4>${esc(title)}<span class="cnt">${(arr || []).length} 项</span></h4>
    ${ddTable(['维度值','运行次数','通过','失败','差错单','涉及金额（元）'],
      (arr || []).slice(0, 30).map(x => `<tr><td><b>${esc(x.key == null ? '—' : x.key)}</b></td>
        <td class="num">${ppNum(x.runs)}</td><td class="num">${ppNum(x.passed)}</td>
        <td class="num ${x.failed > 0 ? 'neg' : ''}">${ppNum(x.failed)}</td>
        <td class="num">${ppNum(x.disputes)}</td><td class="num">${ppFen(x.amount)}</td></tr>`).join(''), '暂无数据')}</div>`;
  document.getElementById('rsBox').innerHTML = `<div class="stat-cards">
      <div class="stat-card"><div class="label">对账运行总数</div><div class="value">${ppNum(s.totalRuns)}</div></div>
      <div class="stat-card ok"><div class="label">通过</div><div class="value">${ppNum(s.passedRuns)}</div></div>
      <div class="stat-card ${s.failedRuns > 0 ? 'bad' : ''}"><div class="label">失败</div><div class="value">${ppNum(s.failedRuns)}</div></div>
      <div class="stat-card warn"><div class="label">差错单</div><div class="value">${ppNum(s.disputeCount)}</div></div>
      <div class="stat-card"><div class="label">通过率</div><div class="value">${s.totalRuns ? (s.passedRuns / s.totalRuns * 100).toFixed(1) + '%' : '—'}</div></div>
    </div>
    ${ppCons('运行守恒 通过 + 失败 = 总数', Number(s.passedRuns || 0) + Number(s.failedRuns || 0), s.totalRuns,
      { raw:true, leftLabel:'passed+failed', rightLabel:'totalRuns' })}
    ${dimTable('按会计日', s.byDay)}${dimTable('按渠道', s.byChannel)}
    ${dimTable('按差异类型', s.byDiffType)}${dimTable('差错单按类型', s.disputeByType)}
    ${dimTable('按商户（TOP 30）', s.byMerchant)}`;
  if (!silent) toast('对账汇总已加载');
}
const ppSlaTotalMs = r => Number(r.createToAssignMs || 0) + Number(r.assignToFixMs || 0) + Number(r.fixToResolveMs || 0);
const ppDur = ms => {
  if (ms == null || ms === '' || Number(ms) <= 0) return '—';
  const n = Number(ms);
  if (n < 60000) return (n / 1000).toFixed(1) + ' s';
  if (n < 3600000) return (n / 60000).toFixed(1) + ' min';
  return (n / 3600000).toFixed(2) + ' h';
};
async function ppSlaQuery(silent) {
  const day = ppDayMs(ppVal('slaDate'));
  lpLoading('slaItem', 9);
  ppLoadingBox('slaStageBox', '加载差错时效…');
  const r = await tryApi('/recon/dispute-sla' + (day != null ? '?bizDate=' + day : ''));
  if (!r.ok) { lpError('slaItem', 9, r.err.message); ppErrBox('slaStageBox', '差错时效', r.err); return; }
  const d = r.data || {};
  PPOps.sla = Array.isArray(d.items) ? d.items : [];
  lpSet('slaItem', PPOps.sla, !!silent);
  const stages = d.stages || [];
  document.getElementById('slaStageBox').innerHTML = `<div class="stat-cards">
      <div class="stat-card"><div class="label">差错单总数</div><div class="value">${ppNum(d.total)}</div></div>
      ${stages.map(s => `<div class="stat-card ${Number(s.avgMs || 0) > 3600000 ? 'warn' : ''}">
        <div class="label">${esc(s.name)}（均值）</div><div class="value" style="font-size:16px">${ppDur(s.avgMs)}</div>
        <div class="label">峰值 ${ppDur(s.maxMs)} · ${ppNum(s.count)} 单</div></div>`).join('')}
    </div>
    <div class="pp-note">时效口径：登记→分派→修复→核销三段耗时；未闭环单不计入对应段均值。超 1 小时未闭环可在列表「时效」过滤中筛出。</div>`;
  if (!silent) toast('差错时效 ' + PPOps.sla.length + ' 单');
}
function ppSlaRows(rows) {
  const t = document.getElementById('slaTable'); if (!t) return;
  t.innerHTML = `<tr><th>差错单号</th><th>类型</th><th>来源</th><th>状态</th><th>会计日</th>
    <th class="num">登记→分派</th><th class="num">分派→修复</th><th class="num">修复→核销</th><th class="num">合计</th></tr>` +
    (rows.length ? rows.map(x => {
      const tot = ppSlaTotalMs(x);
      return `<tr data-dd="dispute:${esc(x.disputeNo || x.id)}">
        <td class="dd-cell"><b>${esc(x.disputeNo || x.id)}</b></td>
        <td>${ppBadge('kind', x.type)}</td><td>${esc(x.source || '—')}</td>
        <td>${ppBadge(x.status)}</td><td>${x.bizDate == null ? '—' : ppBizDate(x.bizDate)}</td>
        <td class="num">${ppDur(x.createToAssignMs)}</td><td class="num">${ppDur(x.assignToFixMs)}</td>
        <td class="num">${ppDur(x.fixToResolveMs)}</td>
        <td class="num ${tot > 3600000 ? 'neg' : ''}"><b>${ppDur(tot)}</b></td></tr>`;
    }).join('') : emptyRow(9, '暂无差错时效数据'));
}

/* ===================== 8.5 对账 · 分片调度 ===================== */
/** 分片页重跑：以本页会计日/类型为粒度触发批量对账（幂等，不重复建单） */
async function ppShRerun() {
  const day = ppDayMs(ppVal('shDate'));
  const r = await ppGuarded('重跑该会计日批量对账',
    `<div class="summary">会计日 <b>${day == null ? '当前会计日' : ppDayStr(day)}</b> · 类型 <b>${esc(ppVal('shType') || 'DAILY')}</b></div>
     <div class="box warn">分片级重跑以会计日为粒度（后端暂无分片级触发端点）；<b>幂等重跑不重复建单</b>，失败分片会重新核对。</div>`,
    '/recon/daily' + (day != null ? '?bizDate=' + day : ''), 'POST', {}, '确认重跑');
  if (!r) return;
  ppResult('shBox', '重跑已执行', Array.isArray(r) ? { checks: r.length, items: r } : r);
  toast('批量对账重跑完成');
  await ppShQuery(true);
}
async function loadReconShard() {
  if (!hasPerm('recon:read')) { lpSet('shard', []); lpSet('shardBatch', []); return; }
  await ppShQuery(true);
}
async function ppShQuery(silent) {
  const day = ppDayMs(ppVal('shDate')), type = ppVal('shType') || 'DAILY';
  const qs = ['reconType=' + encodeURIComponent(type)];
  const bqs = [];
  if (day != null) { qs.push('bizDate=' + day); bqs.push('bizDate=' + day); }
  bqs.push('reconType=' + encodeURIComponent(type));
  lpLoading('shard', 8); lpLoading('shardBatch', 10);
  ppLoadingBox('shBox', '加载分片调度…');
  const [p, b] = await Promise.all([
    tryApi('/recon/progress?' + qs.join('&')),
    tryApi('/recon/batches?' + bqs.join('&')) ]);
  if (p.ok) {
    const d = p.data || {};
    PPOps.shards = d.shards || [];
    lpSet('shard', PPOps.shards, !!silent);
    const pct = d.total > 0 ? Math.round(d.checked / d.total * 100) : (d.status === 'SUCCESS' ? 100 : 0);
    const failed = PPOps.shards.filter(s => s.status === 'FAILED' || s.failed > 0);
    document.getElementById('shBox').innerHTML = `<div class="stat-cards">
        <div class="stat-card"><div class="label">会计日 / 类型</div><div class="value" style="font-size:15px">${ppBizDate(d.bizDate)} · ${esc(d.reconType || type)}</div></div>
        <div class="stat-card accent"><div class="label">分片数</div><div class="value">${PPOps.shards.length}</div></div>
        <div class="stat-card"><div class="label">总进度</div><div class="value">${pct}%</div></div>
        <div class="stat-card ${failed.length ? 'bad' : 'ok'}"><div class="label">异常分片</div><div class="value">${failed.length}</div></div>
        <div class="stat-card ${d.disputeCount > 0 ? 'warn' : ''}"><div class="label">差错单</div><div class="value">${ppNum(d.disputeCount)}</div></div>
      </div>
      <div class="pp-inline"><div class="pp-meter ${failed.length ? 'bad' : ''}" style="flex:1"><i style="width:${pct}%"></i></div>
        ${ppBadge(d.status === 'SUCCESS' ? 'SETTLED' : (d.status === 'FAILED' ? 'FAILED' : 'PROCESSING'), d.status)}</div>
      ${failed.length ? `<div class="box danger" style="margin-top:10px"><b>${failed.length} 个分片存在失败项</b>：重跑幂等（不重复建单），可点「重跑该会计日」。</div>` : '<div class="box info" style="margin-top:10px">全部分片核对通过（diff=0）。</div>'}
      <div class="pp-note">分片策略由 recon 域按 shardTotal 均分账户/流水维度调度；网关侧只读观测进度与重试次数。
        <b>后端缺口</b>：暂无分片级触发/并发度配置/影子比对端点，重跑以会计日为粒度（POST /api/recon/daily）。</div>`;
  } else ppErrBox('shBox', '分片进度', p.err);
  if (b.ok && Array.isArray(b.data)) { PPOps.shardBatches = b.data; lpSet('shardBatch', b.data, !!silent); }
  else lpError('shardBatch', 10, (b.err && b.err.message) || '返回形状异常');
  if (!silent) toast('分片调度已刷新');
}
function ppShardRows(rows) {
  const t = document.getElementById('shardTable'); if (!t) return;
  t.innerHTML = `<tr><th>分片</th><th class="num">总数</th><th class="num">已核对</th><th>进度</th>
    <th class="num">通过</th><th class="num">失败</th><th class="num">差错单</th><th>状态</th></tr>` +
    (rows.length ? rows.map(s => `<tr>
      <td class="pp-mono"><b>${ppNum(s.shardIndex)} / ${ppNum(s.shardTotal)}</b></td>
      <td class="num">${ppNum(s.total)}</td><td class="num">${ppNum(s.checked)}</td>
      <td style="min-width:120px">${ppMeter(s.total > 0 ? s.checked : (s.status === 'SUCCESS' ? 100 : 0), s.total > 0 ? s.total : 100)}</td>
      <td class="num">${ppNum(s.passed)}</td><td class="num ${s.failed > 0 ? 'neg' : ''}">${ppNum(s.failed)}</td>
      <td class="num">${ppNum(s.disputeCount)}</td>
      <td>${ppBadge(s.status === 'SUCCESS' ? 'SETTLED' : (s.status === 'FAILED' ? 'FAILED' : 'PROCESSING'), s.status)}</td></tr>`).join('')
      : emptyRow(8, '暂无分片进度（触发批量对账后生成）'));
}
function ppShardBatchRows(rows) {
  const t = document.getElementById('shardBatchTable'); if (!t) return;
  t.innerHTML = `<tr><th>批次</th><th>会计日</th><th>类型</th><th>分片</th><th class="num">重试</th>
    <th class="num">通过/失败</th><th class="num">差错</th><th>状态</th><th>报告</th><th>更新时间</th></tr>` +
    (rows.length ? rows.map(b => `<tr data-dd="reconBatch:${esc(b.id)}">
      <td class="dd-cell"><b>${esc(b.id)}</b></td><td>${ppBizDate(b.bizDate)}</td>
      <td>${ppBadge('kind', b.reconType)}</td><td class="pp-mono">${ppNum(b.shardIndex)}/${ppNum(b.shardTotal)}</td>
      <td class="num ${(b.attempt || 1) > 1 ? 'neg' : ''}">${ppNum(b.attempt)}</td>
      <td class="num">${ppNum(b.passed)} / ${ppNum(b.failed)}</td><td class="num">${ppNum(b.disputeCount)}</td>
      <td>${ppBadge(b.status === 'SUCCESS' ? 'SETTLED' : (b.status === 'FAILED' ? 'FAILED' : 'PROCESSING'), b.status)}</td>
      <td>${b.reportId ? `<button class="link-btn" data-rd-report="${esc(b.reportId)}">报告</button>` : '—'}</td>
      <td class="muted">${ppMs(b.updatedAt)}</td></tr>`).join('')
      : emptyRow(10, '暂无分片批次'));
}

/* ===================== 3.2 支付 · 支付编排 ===================== */
async function loadPayOrchestration(force) {
  if (!hasPerm('payment:read')) { lpSet('payOrch', []); return; }
  lpLoading('payOrch', 12);
  let rows;
  try { rows = await PPOps.payRows(force); }
  catch (e) { lpError('payOrch', 12, e.message); return; }
  lpSet('payOrch', rows, true);
  const combo = rows.filter(p => p.payMode === 'COMBO' || p.method === 'COMBO');
  const legs = combo.reduce((a, p) => a + ((p.legs || []).length || 0), 0);
  const mkt = rows.filter(p => p.marketingStatus && p.marketingStatus !== 'NONE');
  const hung = rows.filter(p => p.status === 'PROCESSING');
  const closed = rows.filter(p => p.closeType || p.status === 'CLOSED');
  const box = document.getElementById('poBox');
  if (box) box.innerHTML = `<div class="stat-cards">
      <div class="stat-card"><div class="label">支付单总数</div><div class="value">${rows.length}</div></div>
      <div class="stat-card accent"><div class="label">组合支付 COMBO</div><div class="value">${combo.length}</div></div>
      <div class="stat-card"><div class="label">组合腿数（列表内）</div><div class="value">${legs}</div></div>
      <div class="stat-card"><div class="label">营销抵扣单</div><div class="value">${mkt.length}</div></div>
      <div class="stat-card ${hung.length ? 'warn' : 'ok'}"><div class="label">处理中（悬挂候选）</div><div class="value">${hung.length}</div></div>
      <div class="stat-card"><div class="label">关闭/撤销</div><div class="value">${closed.length}</div></div>
    </div>
    <div class="pp-note">组合支付按腿落账（bizNo = legId），点支付单详情抽屉可展开腿并逐腿联查凭证/渠道/结算。
      编排观测口径：payMode / marketingStatus / closeType / legs 均来自 /api/payments 列表快照。</div>`;
  const pid = ppVal('poPayId');
  if (pid) await ppPoShares(true);
}
function ppPayOrchRows(rows) {
  const t = document.getElementById('payOrchTable'); if (!t) return;
  t.innerHTML = `<tr><th>支付单</th><th>交易单</th><th>模式</th><th>方式</th><th class="num">订单额（元）</th>
    <th class="num">营销抵扣</th><th class="num">实付</th><th>营销状态</th><th class="num">腿数</th><th>状态</th><th>渠道</th><th>操作</th></tr>` +
    (rows.length ? rows.map(p => `<tr data-dd="payment:${esc(p.id)}">
      <td class="dd-cell"><b>${esc(p.id)}</b></td><td>${ddLink('order', p.orderId, p.orderId)}</td>
      <td>${ppBadge(p.payMode === 'COMBO' ? 'COMBO' : 'kind', p.payMode || 'SINGLE')}</td>
      <td>${esc(p.method || '—')}</td><td class="num">${ppFen(p.amount)}</td>
      <td class="num">${ppFen(p.marketingAmount)}</td><td class="num"><b>${ppFen(p.payAmount == null ? p.amount : p.payAmount)}</b></td>
      <td>${p.marketingStatus && p.marketingStatus !== 'NONE' ? ppBadge(p.marketingStatus === 'APPLIED' ? 'SETTLED' : (p.marketingStatus === 'ROLLED_BACK' ? 'REVERSE' : 'FAILED'), p.marketingStatus) : '<span class="muted">NONE</span>'}</td>
      <td class="num">${(p.legs || []).length || '—'}</td>
      <td>${ppBadge(p.status)}</td><td>${esc(p.channelCode || '—')}</td>
      <td><button class="btn small ghost" data-po-share="${esc(p.id)}">分账</button>
        ${p.marketingStatus && p.marketingStatus !== 'NONE' ? `<button class="btn small ghost" data-mp-open="${esc(p.id)}">营销</button>` : ''}</td></tr>`).join('')
      : emptyRow(12, '暂无支付单'));
}
async function ppPoShares(silent) {
  const pid = ppVal('poPayId');
  if (!pid) throw new Error('请填写支付单号');
  ppSmallTable('poShareTable', ['分账单','类型','总额（元）','状态','明细数','执行时间','操作'], '', '加载中…');
  const r = await tryApi('/payments/' + encodeURIComponent(pid) + '/shares');
  if (!r.ok) { ppSmallTable('poShareTable', ['分账单','类型','总额（元）','状态','明细数','执行时间','操作'],
    emptyRow(7, '加载失败：' + r.err.message)); return; }
  const list = Array.isArray(r.data) ? r.data : [];
  ppSmallTable('poShareTable', ['分账单','类型','总额（元）','状态','明细数','执行时间','操作'],
    list.map(s => `<tr><td>${ddLink('shareOrder', s.id, s.id)}</td><td>${ppBadge('kind', s.shareType)}</td>
      <td class="num">${ppFen(s.totalAmount)}</td><td>${ppBadge(s.status)}</td>
      <td class="num">${(s.details || []).length}</td><td class="muted">${ppMs(s.executeAt)}</td>
      <td><button class="btn small ghost" data-share-op="detail" data-share-id="${esc(s.id)}">详情</button>
        ${s.status === 'SHARED' ? `<button class="btn small danger" data-share-op="back" data-share-id="${esc(s.id)}" ${hasPerm('payment:refund') ? '' : 'hidden'}>回退</button>`
          : `<button class="btn small" data-share-op="execute" data-share-id="${esc(s.id)}" ${hasPerm('payment:refund') ? '' : 'hidden'}>执行</button>`}</td></tr>`).join(''),
    '该支付单尚无分账单（可在「清结算 › 分账明细」页新建）');
  if (!silent) toast('分账单 ' + list.length + ' 个');
}
/** 退款单发现：凭证 refId 反查 R\d+ 单号 + 结算 REFUND 记录（与既有抽屉同口径） */
async function ppFindRefunds(pid) {
  const cand = new Set();
  const v = await tryApi('/accounting/vouchers?refId=' + encodeURIComponent(pid) + '&withEntries=false&page=1&size=100');
  if (v.ok) ((v.data && v.data.items) || []).forEach(x => { if (/^R\d+$/.test(x.bizNo || '')) cand.add(x.bizNo); });
  const s = await tryApi('/settle/records?paymentId=' + encodeURIComponent(pid));
  if (s.ok && Array.isArray(s.data)) s.data.filter(x => x.type === 'REFUND' && x.bizId).forEach(x => cand.add(x.bizId));
  const out = [];
  for (const id of [...cand].slice(0, 12)) {
    const r = await tryApi('/refunds/' + encodeURIComponent(id));
    if (r.ok && r.data && r.data.id) out.push(r.data);
  }
  return out;
}
async function ppPoRefundFind() {
  const pid = ppVal('poRefundPayId');
  if (!pid) throw new Error('请填写支付单号以发现退款单');
  ppLoadingBox('poRefundBox', '反查退款单…');
  const list = await ppFindRefunds(pid);
  if (!list.length) {
    document.getElementById('poRefundBox').innerHTML = `<div class="box plain">支付单 ${esc(pid)} 未检索到独立退款单（可能未退款、历史数据或按腿退款）。
      可到「账务凭证」按 refId=${esc(pid)} 联查。</div>`;
    return;
  }
  const i = document.getElementById('poRefundId'); if (i) i.value = list[0].id;
  ppPoRefundRender(list);
}
function ppPoRefundRender(list) {
  const box = document.getElementById('poRefundBox'); if (!box) return;
  box.innerHTML = `<div class="table-wrap"><table><tr><th>退款单</th><th>支付单</th><th class="num">金额（元）</th>
    <th class="num">退手续费</th><th>状态</th><th>requestId</th><th>渠道订单号</th><th>创建时间</th><th>操作</th></tr>
    ${list.map(f => `<tr><td>${ddLink('refund', f.id, f.id)}</td><td>${ddLink('payment', f.paymentId, f.paymentId)}</td>
      <td class="num">${ppFen(f.amount)}</td><td class="num">${ppFen(f.feeRefund)}</td>
      <td>${ppBadge(f.status)}</td><td class="pp-mono" style="font-size:11px">${esc(f.requestId || '—')}</td>
      <td class="pp-mono" style="font-size:11px">${esc(f.channelOrderNo || '—')}</td><td class="muted">${fmtTime(f.createdAt)}</td>
      <td>${hasPerm('payment:reconverge,payment:refund') ? `<button class="btn small" data-refund-reconverge="${esc(f.id)}">补单收敛</button>` : ''}</td></tr>`).join('')}
    </table></div>
    <div class="pp-note">退款异步化：受理后 PROCESSING → 渠道回执 SUCCESS/FAILED；悬挂或回执丢失用「补单收敛」向渠道回查并推进状态机（幂等）。</div>`;
}
async function ppPoRefund() {
  const id = ppVal('poRefundId');
  if (!id) throw new Error('请填写退款单号（R…），或先用支付单号反查');
  ppLoadingBox('poRefundBox', '查询退款单…');
  const r = await tryApi('/refunds/' + encodeURIComponent(id));
  if (!r.ok) { ppErrBox('poRefundBox', '退款单查询', r.err); return; }
  ppPoRefundRender([r.data]);
}
async function ppPoReconverge(id) {
  const rid = id || ppVal('poRefundId');
  if (!rid) throw new Error('请填写退款单号');
  const r = await ppGuarded('退款补单收敛',
    `<div class="summary">退款单 <b>${esc(rid)}</b></div>
     <div class="box warn">向渠道回查真实退款状态并收敛本地状态机与账务（幂等，可多次）。终态后不再变更；
     若渠道仍处理中则保持 PROCESSING 等待下次回执。权限点 payment:reconverge / payment:refund。</div>`,
    '/refunds/' + encodeURIComponent(rid) + '/reconverge', 'POST', {}, '确认收敛');
  if (!r) return;
  ppResult('poRefundBox', '补单收敛结果', r);
  toast('退款补单收敛完成');
  const d = await tryApi('/refunds/' + encodeURIComponent(rid));
  if (d.ok && d.data && d.data.id) ppPoRefundRender([d.data]);
}

/* ===================== 3.4 支付 · 关闭与撤销 ===================== */
async function loadCloseReverse() {
  if (!hasPerm('payment:read')) { lpSet('closeRev', []); return; }
  lpLoading('closeRev', 11);
  let rows;
  try { rows = await PPOps.payRows(); }
  catch (e) { lpError('closeRev', 11, e.message); return; }
  lpSet('closeRev', rows, true);
}
function ppCloseRevRows(rows) {
  const t = document.getElementById('closeRevTable'); if (!t) return;
  t.innerHTML = `<tr><th>支付单</th><th>交易单</th><th class="num">金额（元）</th><th>状态</th><th>关闭类型</th>
    <th>关闭时间</th><th>渠道单号</th><th>失败原因</th><th>requestId</th><th>创建时间</th><th>操作</th></tr>` +
    (rows.length ? rows.map(p => `<tr data-dd="payment:${esc(p.id)}">
      <td class="dd-cell"><b>${esc(p.id)}</b></td><td>${ddLink('order', p.orderId, p.orderId)}</td>
      <td class="num">${ppFen(p.amount)}</td><td>${ppBadge(p.status)}</td>
      <td>${p.closeType ? ppBadge('kind', p.closeType) : '<span class="muted">—</span>'}</td>
      <td class="muted">${ppMs(p.closedAt)}</td>
      <td class="pp-mono" style="font-size:11px">${esc(p.channelBizNo || '—')}</td>
      <td class="wrap" style="font-size:12px">${esc(p.failReason || '—')}</td>
      <td class="pp-mono" style="font-size:11px">${esc(p.requestId || '—')}</td>
      <td class="muted">${fmtTime(p.createdAt)}</td>
      <td><button class="btn small ghost" data-cr-fill="${esc(p.id)}">载入</button></td></tr>`).join('')
      : emptyRow(11, '暂无支付单（用「范围」过滤只看关闭/撤销/失败）'));
}
async function ppCrOp(op) {
  const pid = ppVal('crPayId');
  if (!pid) throw new Error('请填写支付单号');
  const requestId = ppVal('crRequestId') || ('UII2-CR-' + uuid());
  const meta = {
    close: { title:'关闭支付单（敏感操作）', perm:'payment:refund,payment:pay',
      desc:'关闭未支付/处理中的支付单：释放冻结与预授权，登记 closeType；已支付单不可关闭（需走退款）。' },
    cancel: { title:'撤销支付单（敏感操作）', perm:'payment:refund',
      desc:'撤销当日已成功支付单：<b>真实反向动账</b>（原路退回 + 红冲凭证），与退款的区别是撤销不留退款单、按原凭证红冲。' },
    'race-repair': { title:'关闭竞态悬挂修复（敏感操作）', perm:'payment:refund',
      desc:'存量兜底：关闭与渠道扣款竞态导致的悬挂单，回查渠道真实状态后收敛（幂等）。受理侧防悬挂守卫已根治新增路径。' }
  }[op];
  const body = op === 'race-repair' ? {} : { requestId };
  const r = await ppSensitive(meta.title,
    `<div class="summary">支付单 <b>${esc(pid)}</b>${op === 'race-repair' ? '' : ` · requestId <b>${esc(requestId)}</b>`}</div>
     <div class="box danger">${meta.desc}<br>权限点 <span class="pp-mono">${esc(meta.perm)}</span>，需二次认证（X-Step-Up-Token）。</div>`,
    '/payments/' + encodeURIComponent(pid) + '/' + op, 'POST', body,
    `即将对支付单 ${esc(pid)} 执行 ${esc(op)}（可能真实动账，需二次认证）。`, '确认执行');
  if (!r) return;
  ppResult('crBox', op + ' 已执行', r);
  toast('支付单 ' + pid + ' ' + op + ' 完成');
  await loadCloseReverse();
  const d = await tryApi('/payments/' + encodeURIComponent(pid));
  if (d.ok) ddPush('payment', pid);
}
async function ppCrRecent() {
  ppLoadingBox('crRecentBox', '扫描审计留痕…');
  const [c, x] = await Promise.all([
    ppAuditIds('/api/payments', /\/api\/payments\/([^/]+)\/close/, 100),
    ppAuditIds('/api/payments', /\/api\/payments\/([^/]+)\/cancel/, 100) ]);
  const ids = [...new Set(c.ids.concat(x.ids))].slice(0, 30);
  if (!ids.length) {
    document.getElementById('crRecentBox').innerHTML = `<div class="box plain">${esc(c.note || x.note || '未发现关闭/撤销留痕')}</div>`;
    return;
  }
  const rows = await Promise.all(ids.map(async id => {
    const d = await tryApi('/payments/' + encodeURIComponent(id));
    return d.ok ? d.data : { id, status: 'UNKNOWN' };
  }));
  document.getElementById('crRecentBox').innerHTML = `<div class="pp-note">${esc(c.note || '')}</div>
    <div class="table-wrap"><table><tr><th>支付单</th><th>状态</th><th>关闭类型</th><th class="num">金额（元）</th><th>关闭时间</th><th>操作</th></tr>
    ${rows.map(p => `<tr><td>${ddLink('payment', p.id, p.id)}</td><td>${ppBadge(p.status)}</td>
      <td>${p.closeType ? ppBadge('kind', p.closeType) : '—'}</td><td class="num">${ppFen(p.amount)}</td>
      <td class="muted">${ppMs(p.closedAt)}</td>
      <td><button class="btn small ghost" data-cr-fill="${esc(p.id)}">载入处置</button></td></tr>`).join('')}</table></div>`;
}

/* ===================== 3.5 支付 · 营销抵扣编排（P2-E） ===================== */
const PP_MKT_SRC = { COUPON:'优惠券', RED_PACKET:'红包', POINTS:'积分', ACTIVITY:'活动', ORDER:'订单' };
async function loadMarketingPay(force) {
  if (!hasPerm('marketing:read') && !hasPerm('payment:read')) { lpSet('mktPay', []); return; }
  lpLoading('mktPay', 11);
  let rows;
  try { rows = await PPOps.payRows(force); }
  catch (e) { lpError('mktPay', 11, e.message); return; }
  const mkt = rows.filter(p => p.marketingStatus && p.marketingStatus !== 'NONE');
  lpSet('mktPay', mkt, true);
  const pid = ppVal('mpPayId');
  if (pid) await ppMpQuery(true);
}
function ppMktPayRows(rows) {
  const t = document.getElementById('mktPayTable'); if (!t) return;
  t.innerHTML = `<tr><th>支付单</th><th>交易单</th><th class="num">订单额（元）</th><th class="num">营销抵扣</th>
    <th class="num">实付</th><th>营销状态</th><th>支付状态</th><th>模式</th><th>失败原因</th><th>创建时间</th><th>操作</th></tr>` +
    (rows.length ? rows.map(p => `<tr data-dd="marketingPay:${esc(p.id)}">
      <td class="dd-cell"><b>${esc(p.id)}</b></td><td>${ddLink('order', p.orderId, p.orderId)}</td>
      <td class="num">${ppFen(p.amount)}</td><td class="num"><b>${ppFen(p.marketingAmount)}</b></td>
      <td class="num">${ppFen(p.payAmount == null ? p.amount : p.payAmount)}</td>
      <td>${ppBadge(p.marketingStatus === 'APPLIED' ? 'SETTLED' : (p.marketingStatus === 'ROLLED_BACK' ? 'REVERSE' : 'FAILED'), p.marketingStatus)}</td>
      <td>${ppBadge(p.status)}</td><td>${ppBadge(p.payMode === 'COMBO' ? 'COMBO' : 'kind', p.payMode || 'SINGLE')}</td>
      <td class="wrap" style="font-size:12px">${esc(p.failReason || '—')}</td>
      <td class="muted">${fmtTime(p.createdAt)}</td>
      <td><button class="btn small ghost" data-mp-open="${esc(p.id)}">抵扣详情</button></td></tr>`).join('')
      : emptyRow(11, '暂无营销抵扣支付单（marketingStatus ≠ NONE）'));
}
async function ppMpBest() {
  const mid = ppVal('mpMember');
  if (!mid) throw new Error('请填写会员 ID');
  const amount = ppFenIn('mpOrderAmount');
  if (amount == null) throw new Error('请填写订单金额（元）');
  const body = { memberId: mid, accountId: ppVal('mpAccount') || null, orderId: ppVal('mpOrderId') || null,
    orderAmount: amount, usePoints: ppChecked('mpUsePoints') };
  const mc = ppInt('mpMaxCand', null); if (mc != null) body.maxCandidatesPerSource = mc;
  const mn = ppInt('mpMaxNodes', null); if (mn != null) body.maxSearchNodes = mn;
  ppLoadingBox('mpBestBox', '最优组合计算中（只读试算，不核销不动账）…');
  const r = await ppTryPost('/marketing-pay/best', body);
  if (!r.ok) { ppErrBox('mpBestBox', '最优组合试算', r.err); return; }
  const d = r.data || {};
  const sel = d.selected || [], rej = d.rejected || [];
  const selSum = sel.reduce((a, x) => a + Number(x.amount || 0), 0);
  document.getElementById('mpBestBox').innerHTML = `<div class="stat-cards">
      <div class="stat-card"><div class="label">订单额（元）</div><div class="value">${yuan(d.orderAmount || 0)}</div></div>
      <div class="stat-card accent"><div class="label">最优抵扣（元）</div><div class="value">${yuan(d.totalDiscount || 0)}</div></div>
      <div class="stat-card ok"><div class="label">实付（元）</div><div class="value">${yuan(d.payAmount || 0)}</div></div>
      <div class="stat-card"><div class="label">候选 / 评估组合</div><div class="value" style="font-size:16px">${ppNum(d.candidateCount)} / ${ppNum(d.evaluatedCombinations)}</div></div>
      <div class="stat-card ${d.exhaustive ? 'ok' : 'warn'}"><div class="label">搜索完备性</div><div class="value" style="font-size:16px">${d.exhaustive ? '穷尽最优' : '预算内近似'}</div></div>
    </div>
    ${ppCons('Σ选中抵扣 = 总抵扣', selSum, d.totalDiscount, { leftLabel:'Σ selected', rightLabel:'totalDiscount' })}
    ${ppCons('订单额 − 抵扣 = 实付', Number(d.orderAmount || 0) - Number(d.totalDiscount || 0), d.payAmount,
      { leftLabel:'order−discount', rightLabel:'payAmount' })}
    <div class="kv" style="margin-top:10px">
      <div><span class="k">券 / 红包 / 积分 / 活动</span><span class="v">¥ ${yuan(d.couponAmount || 0)} / ¥ ${yuan(d.redPacketAmount || 0)} / ¥ ${yuan(d.pointsAmount || 0)} / ¥ ${yuan(d.activityAmount || 0)}</span></div>
      <div><span class="k">积分使用 / 兑换率</span><span class="v">${ppNum(d.pointsUsed)} 分 / ${ppNum(d.pointsExchangeRate)} 积分每分</span></div>
      <div><span class="k">预算 / 互斥遵守</span><span class="v">${ppYes(d.budgetRespected, '预算不超', '预算超限')} ${ppYes(d.mutexRespected, '互斥遵守', '互斥冲突')}</span></div>
      <div><span class="k">搜索节点 / 剪枝</span><span class="v">${ppNum(d.searchNodes)} / ${ppNum(d.prunedCandidates)}</span></div>
      <div><span class="k">输入指纹</span><span class="v pp-mono" style="font-size:11px">${esc(d.inputFingerprint || '—')}</span></div>
      <div><span class="k">叠加规则</span><span class="v">券 ${ppNum(d.rules && d.rules.maxCoupons)} / 红包 ${ppNum(d.rules && d.rules.maxRedPackets)} / 活动 ${ppNum(d.rules && d.rules.maxActivities)} / 积分可叠加 ${ppYes(d.rules && d.rules.pointsStackable)}</span></div></div>
    <div class="dd-sec"><h4>选中明细与理由<span class="cnt">${sel.length} 项</span></h4>
      ${ddTable(['来源','权益 ID','名称','抵扣（元）','原面额','截断','积分','选中理由'],
        sel.map(x => `<tr><td>${ppBadge('kind', PP_MKT_SRC[x.source] || x.source)}</td>
          <td class="pp-mono">${esc(x.refId)}</td><td class="wrap">${esc(x.name || '—')}</td>
          <td class="num"><b>${ppFen(x.amount)}</b></td><td class="num">${ppFen(x.originalAmount)}</td>
          <td>${x.truncated ? ppTag('warn', '截断') : ppTag('off', '全额')}</td>
          <td class="num">${ppNum(x.points)}</td><td class="wrap" style="font-size:12px">${esc(x.reason || '—')}</td></tr>`).join(''), '无选中项')}</div>
    <div class="dd-sec"><h4>落选明细与原因码<span class="cnt">${rej.length} 项</span></h4>
      ${ddTable(['来源','权益 ID','潜在抵扣（元）','落选码','原因'],
        rej.slice(0, 60).map(x => `<tr><td>${ppBadge('kind', PP_MKT_SRC[x.source] || x.source)}</td>
          <td class="pp-mono">${esc(x.refId)}</td><td class="num">${ppFen(x.potentialAmount)}</td>
          <td>${ppTag('bad', x.code || '—')}</td><td class="wrap" style="font-size:12px">${esc(x.reason || '—')}</td></tr>`).join(''), '无落选项')}</div>
    <div class="pp-note"><b>策略：</b>${esc(d.strategy || '—')}<br><b>解释：</b>${esc(d.explanation || '—')}</div>
    <div class="box info">只读试算：不核销权益、不动账、不需二次认证。相同输入的指纹一致（确定性），可用于回归比对。</div>`;
}
async function ppMpQuery(silent) {
  const pid = ppVal('mpPayId');
  if (!pid) throw new Error('请填写支付单号');
  ppLoadingBox('mpDedBox', '加载抵扣明细…');
  ppLoadingBox('mpAllocBox', '加载分摊…');
  ppLoadingBox('mpConsBox', '校验守恒…');
  const [d, a, c] = await Promise.all([
    tryApi('/marketing-pay/deduction/' + encodeURIComponent(pid)),
    tryApi('/marketing-pay/alloc/' + encodeURIComponent(pid)),
    tryApi('/marketing-pay/conservation/' + encodeURIComponent(pid)) ]);
  if (!d.ok) ppErrBox('mpDedBox', '抵扣明细', d.err);
  else ppMpDedRender(d.data);
  if (!a.ok) ppErrBox('mpAllocBox', '分摊视图', a.err);
  else ppMpAllocRender(a.data);
  if (!c.ok) ppErrBox('mpConsBox', '守恒校验', c.err);
  else ppMpConsRender(c.data);
  PPOps.mktDetail = { pid, ded: d.ok ? d.data : null, alloc: a.ok ? a.data : null, cons: c.ok ? c.data : null };
  if (!silent) toast('营销抵扣三件套已加载');
}
function ppMpDedRender(d) {
  const box = document.getElementById('mpDedBox'); if (!box || !d) return;
  const legs = d.legs || [];
  const legSum = legs.filter(l => l.status === 'USED' || l.status === 'APPLIED').reduce((a, l) => a + Number(l.amount || 0), 0);
  box.innerHTML = `<div class="stat-cards">
      <div class="stat-card"><div class="label">订单额（元）</div><div class="value">${yuan(d.orderAmount || 0)}</div></div>
      <div class="stat-card accent"><div class="label">已抵扣（元）</div><div class="value">${yuan(d.appliedAmount || 0)}</div></div>
      <div class="stat-card"><div class="label">已退回（元）</div><div class="value">${yuan(d.refundedAmount || 0)}</div></div>
      <div class="stat-card ok"><div class="label">净抵扣（元）</div><div class="value">${yuan(d.netAmount || 0)}</div></div>
      <div class="stat-card"><div class="label">实付（元）</div><div class="value">${yuan(d.payAmount || 0)}</div></div>
      <div class="stat-card ${d.status === 'APPLIED' ? 'ok' : (d.status === 'ROLLED_BACK' ? 'warn' : 'bad')}"><div class="label">营销状态</div><div class="value" style="font-size:16px">${esc(d.status || '—')}</div></div>
    </div>
    ${ppCons('已抵扣 − 已退回 = 净抵扣', Number(d.appliedAmount || 0) - Number(d.refundedAmount || 0), d.netAmount,
      { leftLabel:'applied−refunded', rightLabel:'netAmount' })}
    ${ppCons('订单额 = 实付 + 净抵扣', Number(d.payAmount || 0) + Number(d.netAmount || 0), d.orderAmount,
      { leftLabel:'pay+net', rightLabel:'orderAmount', note:'手续费 ¥ ' + yuan(d.fee || 0) + ' 另计（不参与抵扣守恒）' })}
    <div class="kv" style="margin-top:8px">
      <div><span class="k">支付单 / 交易单</span><span class="v">${ddLink('payment', d.paymentId, d.paymentId)} / ${ddLink('order', d.orderId, d.orderId)}</span></div>
      <div><span class="k">付款账户</span><span class="v">${ddLink('account', d.accountId, d.accountId)}</span></div></div>
    <div class="dd-sec"><h4>抵扣腿（按来源顺序：券→红包→积分→活动）<span class="cnt">${legs.length} 腿</span></h4>
      ${ddTable(['腿序','来源','权益 ID','核销 ID','金额（元）','积分','状态','凭证号','失败原因'],
        legs.map(l => `<tr><td class="num">${ppNum(l.legSeq)}</td>
          <td>${ppBadge('kind', PP_MKT_SRC[l.source] || l.source)}</td>
          <td class="pp-mono">${esc(l.refId || '—')}</td><td class="pp-mono">${esc(l.useId || '—')}</td>
          <td class="num"><b>${ppFen(l.amount)}</b></td><td class="num">${ppNum(l.points)}</td>
          <td>${ppBadge(l.status === 'USED' ? 'SETTLED' : (l.status === 'ROLLED_BACK' ? 'REVERSE' : 'FAILED'), l.status)}</td>
          <td>${ddLink('voucher', l.bizNo, l.bizNo)}</td>
          <td class="wrap" style="font-size:12px">${esc(l.failReason || '—')}</td></tr>`).join(''), '无抵扣腿')}</div>
    ${legs.length ? ppCons('Σ已核销腿 = 已抵扣额', legSum, d.appliedAmount, { leftLabel:'Σ USED legs', rightLabel:'appliedAmount' }) : ''}`;
}
function ppMpAllocRender(a) {
  const box = document.getElementById('mpAllocBox'); if (!box || !a) return;
  const dimTable = (title, arr) => ddTable(['分摊 ID','维度','来源','目标 ID','金额（元）','类型','凭证号','时间'],
    (arr || []).map(x => `<tr><td class="pp-mono">${esc(x.id)}</td><td>${ppBadge('kind', x.dimension)}</td>
      <td>${esc(PP_MKT_SRC[x.source] || x.source)}</td><td class="pp-mono">${esc(x.targetId || '—')}</td>
      <td class="num"><b>${ppFen(x.amount)}</b></td>
      <td>${ppBadge(x.type === 'USE' ? 'SETTLED' : 'REVERSE', x.type)}</td>
      <td>${ddLink('voucher', x.bizNo, x.bizNo)}</td><td class="muted">${fmtTime(x.createdAt)}</td></tr>`).join(''), '暂无分摊');
  box.innerHTML = `${ppCons('Σ来源分摊 = 抵扣总额', a.sourceSum, a.totalAmount, { leftLabel:'Σ source', rightLabel:'totalAmount' })}
    ${ppCons('Σ目标分摊 = 抵扣总额', a.allocated, a.totalAmount, { leftLabel:'Σ target', rightLabel:'totalAmount' })}
    ${ppCons('来源 − 目标 = 0', a.sourceSum, a.allocated, { leftLabel:'sourceSum', rightLabel:'allocated',
      note:`后端 diff=${ppNum(a.diff)} · sourceDiff=${ppNum(a.sourceDiff)} · balanced=${a.balanced ? 'true' : 'false'}` })}
    <div class="dd-sec"><h4>来源维度（权益侧）<span class="cnt">${(a.sources || []).length} 条</span></h4>${dimTable('来源', a.sources)}</div>
    <div class="dd-sec"><h4>目标维度（订单侧）<span class="cnt">${(a.targets || []).length} 条</span></h4>${dimTable('目标', a.targets)}</div>`;
}
function ppMpConsRender(c) {
  const box = document.getElementById('mpConsBox'); if (!box || !c) return;
  const diffs = [c.orderDiff, c.sourceDiff, c.targetDiff].filter(x => x != null);
  const allZero = diffs.every(x => Number(x) === 0) && c.balanced;
  box.innerHTML = `<div class="box ${allZero ? 'info' : 'danger'}"><b>${allZero ? '守恒成立（三项 diff 全 0）' : '守恒告警：存在非 0 diff'}</b>
      <div style="margin-top:4px">${esc(c.message || '')}</div></div>
    ${ppCons('营销抵扣 = Σ来源', c.marketingAmount, c.sourceSum, { leftLabel:'marketingAmount', rightLabel:'sourceSum' })}
    ${ppCons('营销抵扣 = Σ目标', c.marketingAmount, c.targetSum, { leftLabel:'marketingAmount', rightLabel:'targetSum' })}
    ${c.orderDiff == null ? '<div class="pp-note">未传 orderAmount/fundAmount，订单维度守恒未校验（可填下方金额后重算）。</div>'
      : ppCons('订单额 = 实付 + 营销抵扣', Number(c.orderAmount || 0), Number(c.fundAmount || 0) + Number(c.marketingAmount || 0),
        { leftLabel:'orderAmount', rightLabel:'fund+marketing' })}
    <div class="pp-form" style="margin-top:10px">
      <div class="field"><label>订单额（元，可空）</label><input id="mpConsOrder" type="number" min="0" step="0.01"></div>
      <div class="field"><label>实付资金额（元，可空）</label><input id="mpConsFund" type="number" min="0" step="0.01"></div>
      <button class="btn ghost" id="btnMpConsRecalc">重算守恒（只读）</button>
    </div>
    <div class="pp-note">只读校验：不动账、不需二次认证。三项 diff = orderDiff / sourceDiff / targetDiff。</div>`;
  const btn = document.getElementById('btnMpConsRecalc');
  if (btn) btn.addEventListener('click', async () => {
    const pid = ppVal('mpPayId');
    if (!pid) { toast('请先填写支付单号', false); return; }
    const qs = [];
    const o = ppFenIn('mpConsOrder'), f = ppFenIn('mpConsFund');
    if (o != null) qs.push('orderAmount=' + o);
    if (f != null) qs.push('fundAmount=' + f);
    ppLoadingBox('mpConsBox', '重算守恒…');
    const r = await tryApi('/marketing-pay/conservation/' + encodeURIComponent(pid) + (qs.length ? '?' + qs.join('&') : ''));
    if (!r.ok) { ppErrBox('mpConsBox', '守恒校验', r.err); return; }
    ppMpConsRender(r.data);
  });
}
async function ppMpOp(op) {
  const pid = ppVal('mpPayId');
  if (!pid) throw new Error('请填写支付单号');
  const requestId = 'UII2-MP-' + uuid();
  const back = op === 'rollback';
  const r = await ppSensitive(back ? '营销抵扣回退（敏感操作）' : '退回后重分摊（敏感操作）',
    `<div class="summary">支付单 <b>${esc(pid)}</b> · requestId <b>${esc(requestId)}</b></div>
     <div class="box danger">${back
       ? '<b>回退：</b>退回已核销权益（券/红包/积分/活动）+ 资金反向 + 目标维度重分摊。幂等：已 ROLLED_BACK 再回退不重复动账。权限点 payment:refund / marketing:write。'
       : '<b>重分摊：</b>退回后按剩余净额重新分摊目标维度，保证 Σ目标 = Σ来源 diff=0。幂等。权限点 marketing:write / payment:refund。'}</div>`,
    '/marketing-pay/' + op + '/' + encodeURIComponent(pid), 'POST', { requestId },
    `即将对支付单 ${esc(pid)} 执行营销${back ? '回退' : '重分摊'}（真实动账，需二次认证）。`, back ? '确认回退' : '确认重分摊');
  if (!r) return;
  ppResult('mpOpBox', back ? '营销回退已执行' : '重分摊已执行', r);
  toast(back ? '营销抵扣已回退' : '重分摊完成');
  await ppMpQuery(true);
  await loadMarketingPay(true);
}
async function ddMarketingPay(pid) {
  const [d, a, c] = await Promise.all([
    tryApi('/marketing-pay/deduction/' + encodeURIComponent(pid)),
    tryApi('/marketing-pay/alloc/' + encodeURIComponent(pid)),
    tryApi('/marketing-pay/conservation/' + encodeURIComponent(pid)) ]);
  if (!d.ok) return ddBox('danger', '营销抵扣加载失败：' + esc(d.err.message));
  const x = d.data || {};
  let html = ddKv([
    ['支付单', ddLink('payment', x.paymentId, x.paymentId)], ['交易单', ddLink('order', x.orderId, x.orderId)],
    ['付款账户', ddLink('account', x.accountId, x.accountId)],
    ['订单额（元）', ppFen(x.orderAmount)], ['手续费（元）', ppFen(x.fee)],
    ['已抵扣 / 已退回 / 净抵扣', `${ppFen(x.appliedAmount)} / ${ppFen(x.refundedAmount)} / <b>${ppFen(x.netAmount)}</b>`],
    ['实付（元）', ppFen(x.payAmount)],
    ['营销状态', ppBadge(x.status === 'APPLIED' ? 'SETTLED' : (x.status === 'ROLLED_BACK' ? 'REVERSE' : 'FAILED'), x.status)] ]);
  html += ddSec('守恒（订单额 = 实付 + 净抵扣）',
    ppCons('订单额守恒', Number(x.payAmount || 0) + Number(x.netAmount || 0), x.orderAmount,
      { leftLabel:'pay+net', rightLabel:'orderAmount' }));
  html += ddSec('抵扣腿', ddTable(['腿序','来源','权益 ID','金额（元）','状态','凭证号'],
    (x.legs || []).map(l => `<tr><td class="num">${ppNum(l.legSeq)}</td>
      <td>${ppBadge('kind', PP_MKT_SRC[l.source] || l.source)}</td><td class="pp-mono">${esc(l.refId || '—')}</td>
      <td class="num">${ppFen(l.amount)}</td>
      <td>${ppBadge(l.status === 'USED' ? 'SETTLED' : (l.status === 'ROLLED_BACK' ? 'REVERSE' : 'FAILED'), l.status)}</td>
      <td>${ddLink('voucher', l.bizNo, l.bizNo)}</td></tr>`).join(''), '无抵扣腿'), (x.legs || []).length + ' 腿');
  if (a.ok && a.data) {
    const y = a.data;
    html += ddSec('双维度分摊守恒', ppCons('Σ来源 = 抵扣总额', y.sourceSum, y.totalAmount, { leftLabel:'sourceSum', rightLabel:'totalAmount' }) +
      ppCons('Σ目标 = 抵扣总额', y.allocated, y.totalAmount, { leftLabel:'allocated', rightLabel:'totalAmount' }));
    html += ddSec('来源分摊', ddTable(['来源','目标 ID','金额（元）','类型','凭证号'],
      (y.sources || []).map(s => `<tr><td>${esc(PP_MKT_SRC[s.source] || s.source)}</td><td class="pp-mono">${esc(s.targetId || '—')}</td>
        <td class="num">${ppFen(s.amount)}</td><td>${ppBadge(s.type === 'USE' ? 'SETTLED' : 'REVERSE', s.type)}</td>
        <td>${ddLink('voucher', s.bizNo, s.bizNo)}</td></tr>`).join(''), '暂无'), (y.sources || []).length + ' 条');
    html += ddSec('目标分摊', ddTable(['来源','目标 ID','金额（元）','类型','凭证号'],
      (y.targets || []).map(s => `<tr><td>${esc(PP_MKT_SRC[s.source] || s.source)}</td><td class="pp-mono">${esc(s.targetId || '—')}</td>
        <td class="num">${ppFen(s.amount)}</td><td>${ppBadge(s.type === 'USE' ? 'SETTLED' : 'REVERSE', s.type)}</td>
        <td>${ddLink('voucher', s.bizNo, s.bizNo)}</td></tr>`).join(''), '暂无'), (y.targets || []).length + ' 条');
  }
  if (c.ok && c.data) {
    const z = c.data;
    html += ddSec('营销域守恒校验', ddBox(z.balanced ? 'info' : 'danger',
      `${esc(z.message || '')}<br>sourceDiff=${ppNum(z.sourceDiff)} · targetDiff=${ppNum(z.targetDiff)} · orderDiff=${z.orderDiff == null ? '未校验' : ppNum(z.orderDiff)}`));
  }
  if (hasPerm('payment:refund') || hasPerm('marketing:write')) {
    html += ddSec('运维操作（需二次认证）', `<div class="pp-inline">
      <button class="btn small ghost" data-mp-open="${esc(pid)}">前往营销抵扣编排页</button></div>
      <div class="pp-note">回退 / 重分摊在编排页处置区执行（带 requestId 幂等键）。</div>`);
  }
  return html;
}