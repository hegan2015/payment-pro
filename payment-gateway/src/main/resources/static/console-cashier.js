/* =====================================================================
 * payment-pro 管理控制台 · 收银台运营 + 合并支付与分阶段（IA 分组 2）
 * ---------------------------------------------------------------------
 * 实探端点（零后端改动）：
 *  /api/cashier      ：methods(GET,只读编排预览) · code(POST,生成付款码/收款码) ·
 *                      code/{code}(GET) · code/parse(POST,只读解析)
 *  /api/payments     ：GET 收银台受理快照（转化漏斗 + 受理单列表，抽屉联查凭证/渠道/交易单）
 *  /api/openplatform/scopes ：GET 方法 → scope 目录（只读）
 *  /api/trade-plus   ：orders/merge(POST,不动账) · merges/{id}(GET) · merges/{id}/dissolve(POST,不动账)
 *                      merges/{id}/pay(POST,step-up) · orders/{id}/stage-plan(POST,不动账)
 *                      orders/{id}/stage-pay(POST,step-up) · orders/{id}/price(POST,step-up)
 *                      orders/{id}/adjust-price(POST,step-up+大额复核) · payments/{id}/cancel(POST,step-up+复核)
 *                      orders/{id}/price-logs(GET,只读)
 * 平台不自建 C 端收银台页面：C 端由业务方（ride-pro / H5 / 小程序 / App / PC）调 OpenAPI 自渲染，
 * 本页只做「治理侧」观测 + 编排预览 + 付款码运营；限流阈值在「平台治理 › 限流熔断」。
 * 顶层只声明；DOM / 全局依赖全部在 PPCashier.init()（index.html 尾部显式 window 暴露后调用）。
 * ===================================================================== */

const PP_CASHIER_OPEN = [
  ['cashier.methods', 'cashier:read', '渲染收银台方式与可用账户（业务方自渲染）'],
  ['cashier.code.create', 'cashier:code', '创建付款码 / 收款码（BAR/QR/URL/TOKEN）'],
  ['cashier.code.parse', 'cashier:read', '主扫码归一解析（微信/支付宝/云闪付前缀）'],
  ['cashier.code.query', 'cashier:read', '付款码状态查询（幂等只读）'],
  ['cashier.scanPay', 'cashier:pay', '被扫受理（商户扫用户码）'],
  ['cashier.verify', 'cashier:verify', '四型校验：口令 / 短信 / 生物 / 免密'],
  ['cashier.payVerified', 'cashier:pay', '校验通过后受理支付（verifyToken 一次性）'],
  ['cashier.payPassword.set', 'cashier:password', '设置 / 修改支付口令'],
  ['cashier.sms.send', 'cashier:sms', '发送支付短信验证码（沙箱回显）'],
  ['channel.sign', 'channel:sign', '签约 / 解约代扣协议'],
  ['channel.agent.pay', 'channel:agentpay', '协议代扣执行']
];
const PP_CODE_TYPES = [['USER_PRESENTED', 'USER_PRESENTED 付款码（被扫，用户出示）'],
  ['MERCHANT_PRESENTED', 'MERCHANT_PRESENTED 收款码（主扫，商户出码）']];

const PPCashier = {
  cashierMethods: null, scopes: null, payments: [], lastCode: null,
  orders: [], merges: {}, logs: [],

  /* 全局加载函数约定（console-nav.js loader 指向这里） */
  loadOps() { return loadCashierOps(); },
  loadCombo() { return loadComboPay(); },

  init() {
    lpDef('coMethod', { table:'coMethodTable', render:ppCoMethodRows, filters:[
      { k:'kw', label:'关键字', ph:'方法名 / scope / 说明', fields:['0','1','2'] } ] });
    lpDef('coPay', { table:'coPayTable', render:ppCoPayRows, filters:[
      { k:'kw', label:'关键字', ph:'支付单 / 交易单 / 账户 / 渠道单号 / 失败原因',
        fields:['id','orderId','payerAccountId','merchantAccountId','channelBizNo','failReason'] },
      { k:'method', label:'支付方式', type:'select', field:'method',
        opts:[opt('','全部'),opt('BALANCE','BALANCE 余额'),opt('CARD','CARD 银行卡'),
          opt('COMBO','COMBO 组合'),opt('QUICK_PAY','QUICK_PAY 快捷'),opt('SCAN','SCAN 扫码')] },
      { k:'status', label:'受理状态', type:'select', field:'status',
        opts:[opt('','全部'),opt('SUCCESS','SUCCESS 成功'),opt('PROCESSING','PROCESSING 处理中'),
          opt('FAILED','FAILED 失败'),opt('CLOSED','CLOSED 已关闭'),opt('CANCELLED','CANCELLED 已撤销'),
          opt('REFUNDED','REFUNDED 已退')] },
      { k:'bizType', label:'业务类型', type:'select', field:'bizType',
        opts:[opt('','全部'),opt('TRADE','TRADE 交易'),opt('TOPUP','TOPUP 充值'),opt('WITHDRAW','WITHDRAW 提现')] },
      ...DATE_FILTERS ] });
    lpDef('cpOrder', { table:'cpOrderTable', render:ppCpOrderRows, filters:[
      { k:'kw', label:'关键字', ph:'交易单 / 合并单 / 商户 / 主题', fields:['id','mergeId','merchantId','merchantName','subject'] },
      { k:'kind', label:'形态', type:'select', opts:[opt('','全部合并/分阶段'),opt('merge','仅合并支付子单'),opt('stage','仅分阶段单'),opt('price','仅改过价')],
        test:(r,v) => v === 'merge' ? !!r.mergeId : v === 'stage' ? !!r.stageCount : v === 'price' ? (r.priceChangeCount || 0) > 0 : true },
      { k:'status', label:'交易状态', type:'select', field:'status',
        opts:[opt('','全部'),opt('CREATED','CREATED 待付'),opt('PAYING','PAYING 支付中'),opt('PAID','PAID 已付'),
          opt('PARTIAL_REFUNDED','部分退'),opt('CLOSED','已关闭')] },
      ...DATE_FILTERS ] });
    lpDef('cpLog', { table:'cpLogTable', render:ppCpLogRows, filters:[
      { k:'direction', label:'方向', type:'select', field:'direction',
        opts:[opt('','全部'),opt('UP','UP 上调'),opt('DOWN','DOWN 下调')] } ] });

    /* 抽屉：合并单（新增 kind，不动既有 14 类） */
    DD_KINDS.merge = '合并支付单';
    DD_RENDER.merge = ddMerge;

    const on = (id, fn) => { const el = document.getElementById(id); if (el) el.addEventListener('click', fn); };
    on('btnCoMethods', () => ppCoMethods());
    on('btnCoCode', () => ppCoCode(false));
    on('btnCoParse', () => ppCoCode(true));
    on('btnCoCreate', () => ppCoCreate());
    on('btnCoRefresh', () => loadCashierOps());
    on('btnCpRefresh', () => loadComboPay());
    on('btnCpMerge', () => ppCpMergeQuery());
    on('btnCpMergeCreate', () => ppCpMergeCreate());
    on('btnCpStagePlan', () => ppCpStagePlan());
    on('btnCpStagePay', () => ppCpStagePayForm());
    on('btnCpPrice', () => ppCpPrice(false));
    on('btnCpAdjust', () => ppCpPrice(true));
    on('btnCpCancel', () => ppCpCancel());
    on('btnCpLogs', () => ppCpLogs());
    ['#coOrder','#coAccount'].forEach(s => { const e = document.querySelector(s);
      if (e) e.addEventListener('keydown', ev => { if (ev.key === 'Enter') ppCoMethods(); }); });
    ['#coCode'].forEach(s => { const e = document.querySelector(s);
      if (e) e.addEventListener('keydown', ev => { if (ev.key === 'Enter') ppCoCode(false); }); });
    ['#cpMergeId','#cpLogOrder'].forEach(s => { const e = document.querySelector(s);
      if (e) e.addEventListener('keydown', ev => { if (ev.key === 'Enter') s === '#cpMergeId' ? ppCpMergeQuery() : ppCpLogs(); }); });

    /* 行内操作 delegated */
    const co = document.getElementById('tab-cashier-ops');
    if (co) co.addEventListener('click', ev => {
      const b = ev.target.closest && ev.target.closest('[data-co-fill]');
      if (!b) return;
      ev.preventDefault();
      const map = { order:'coOrder', account:'coAccount', codeOrder:'coCodeOrder', codeAccount:'coCodeAccount' };
      const el = document.getElementById(map[b.dataset.coFill]);
      if (el) { el.value = b.dataset.coId; el.focus(); }
      if (b.dataset.coFill === 'order') ppCoMethods(); else toast('已填入 ' + b.dataset.coId);
    });
    const cp = document.getElementById('tab-combo-pay');
    if (cp) cp.addEventListener('click', ev => {
      const b = ev.target.closest && ev.target.closest('[data-cp-fill]');
      if (b) { ev.preventDefault(); ppCpFill(b.dataset.cpFill, b.dataset.cpId); }
    });
    ppCashierDrawerWire();
  }
};

/* ===================== 页 1 · 收银台运营 ===================== */
async function loadCashierOps() {
  lpSet('coMethod', PP_CASHIER_OPEN);
  const box = document.getElementById('coFunnel');
  if (box) ppLoadingBox(box, '统计收银台受理与转化…');
  lpLoading('coPay', 11, '加载收银台受理单…');
  const [p, s] = await Promise.all([tryApi('/payments'), tryApi('/openplatform/scopes')]);
  if (s.ok && s.data) {
    PPCashier.scopes = s.data;
    lpSet('coMethod', PP_CASHIER_OPEN.map(r => [r[0], s.data[r[0]] || r[1], r[2]]), true);
  }
  if (!p.ok) {
    if (box) box.innerHTML = ddBox('danger', '转化漏斗加载失败：' + esc(p.err.message));
    lpError('coPay', 11, p.err.message);
    return;
  }
  const rows = p.data || [];
  PPCashier.payments = rows;
  lpSet('coPay', rows);
  const cnt = k => rows.filter(k).length;
  const total = rows.length, ok = cnt(x => x.status === 'SUCCESS'), fail = cnt(x => x.status === 'FAILED'),
    proc = cnt(x => x.status === 'PROCESSING'), closed = cnt(x => x.status === 'CLOSED'),
    canc = cnt(x => x.status === 'CANCELLED'), ref = cnt(x => x.status === 'REFUNDED');
  const byMethod = {};
  rows.forEach(x => { const m = x.method || x.payMode || 'UNKNOWN'; byMethod[m] = byMethod[m] || { n:0, ok:0 };
    byMethod[m].n++; if (x.status === 'SUCCESS') byMethod[m].ok++; });
  const reasons = {};
  rows.filter(x => x.status === 'FAILED' && x.failReason).forEach(x => {
    const k = String(x.failReason).slice(0, 40); reasons[k] = (reasons[k] || 0) + 1; });
  const topReasons = Object.entries(reasons).sort((a, b) => b[1] - a[1]).slice(0, 8);
  if (box) box.innerHTML = `
    <div class="stat-cards">
      <div class="stat-card accent"><div class="label">受理单总数</div><div class="value">${total}</div></div>
      <div class="stat-card ok"><div class="label">成功</div><div class="value">${ok}</div></div>
      <div class="stat-card"><div class="label">处理中</div><div class="value">${proc}</div></div>
      <div class="stat-card bad"><div class="label">失败</div><div class="value">${fail}</div></div>
      <div class="stat-card"><div class="label">关闭 / 撤销 / 已退</div><div class="value">${closed} / ${canc} / ${ref}</div></div>
      <div class="stat-card ${total && ok / total >= 0.9 ? 'ok' : 'bad'}"><div class="label">成功率</div>
        <div class="value">${total ? (ok / total * 100).toFixed(1) + '%' : '—'}</div></div>
    </div>
    ${ppCons('成功率口径（成功 + 失败 + 处理中 + 关闭 + 撤销 + 已退 = 总数）', ok + fail + proc + closed + canc + ref, total, { raw:true, leftLabel:'分状态合计', rightLabel:'总数' })}
    <div class="table-wrap" style="margin-top:10px"><table>
      <tr><th>支付方式 / 模式</th><th class="num">笔数</th><th class="num">成功</th><th>成功率</th></tr>
      ${Object.keys(byMethod).length ? Object.entries(byMethod).map(([m, v]) =>
        `<tr><td>${ppBadge('kind', m)}</td><td class="num">${v.n}</td><td class="num">${v.ok}</td>
          <td>${ppMeter(v.ok, v.n)}</td></tr>`).join('') : emptyRow(4, '暂无支付单')}
    </table></div>
    <div class="table-wrap" style="margin-top:10px"><table>
      <tr><th>失败原因 TOP</th><th class="num">笔数</th><th>占失败比</th></tr>
      ${topReasons.length ? topReasons.map(([r, n]) => `<tr><td>${esc(r)}</td><td class="num">${n}</td>
        <td>${ppMeter(n, fail)}</td></tr>`).join('') : emptyRow(3, '无失败单（或失败原因未回填）')}
    </table></div>
    <div class="hint">口径说明：收银台由业务方（ride-pro / H5 / 小程序 / App / PC）自渲染，平台只提供 OpenAPI；
      本漏斗基于控制台 /api/payments 快照，非 C 端埋点。限流阈值与熔断状态见
      <button class="btn small ghost" data-pp-goto="sentinel">平台治理 › 限流熔断</button>。</div>`;
}
function ppCoMethodRows(rows) {
  const t = document.getElementById('coMethodTable'); if (!t) return;
  t.innerHTML = `<tr><th>OpenAPI 方法</th><th>所需 scope</th><th>能力说明</th><th>控制台对照入口</th></tr>` +
    (rows.length ? rows.map(r => `<tr><td class="pp-mono">${esc(r[0])}</td>
      <td><span class="pp-mono">${esc(r[1])}</span></td><td>${esc(r[2])}</td>
      <td>${r[0] === 'cashier.methods' ? '<button class="btn small ghost" data-co-scroll="methods">方式编排预览</button>'
        : r[0].startsWith('cashier.code') ? '<button class="btn small ghost" data-co-scroll="code">付款码运营</button>'
        : r[0].startsWith('channel.') ? '<button class="btn small ghost" data-pp-goto="ch-config">渠道配置</button>'
        : '<span class="muted">C 端由业务方调用</span>'}</td></tr>`).join('') : emptyRow(4, '暂无方法'));
}
/** 收银台受理单列表（真实后端数据 + 抽屉联查交易单 / 凭证 / 渠道流水） */
function ppCoPayRows(rows) {
  const t = document.getElementById('coPayTable'); if (!t) return;
  t.innerHTML = `<tr><th>支付单</th><th>交易单</th><th>业务</th><th>方式 / 模式</th><th class="num">金额（元）</th>
    <th class="num">实付（元）</th><th>状态</th><th>付款账户</th><th>渠道 / 渠道单号</th><th>凭证</th><th>创建时间</th></tr>` +
    (rows.length ? rows.map(p => {
      const combo = p.method === 'COMBO' || (p.legs || []).length > 0;
      return `<tr data-dd="payment:${esc(p.id)}"><td class="dd-cell"><b>${esc(p.id)}</b></td>
        <td>${ddLink('order', p.orderId, p.orderId)}</td>
        <td>${ppBadge('kind', p.bizType || 'TRADE')}</td>
        <td>${ppBadge(combo ? 'COMBO' : 'kind', (p.method || '—') + (combo ? ' · ' + (p.legs || []).length + ' 腿' : ''))}</td>
        <td class="num">${ppFen(p.amount)}</td>
        <td class="num">${ppFen(p.payAmount == null ? p.amount : p.payAmount)}</td>
        <td>${ppBadge(p.status, p.status)}${p.failReason ? `<br><span class="muted" style="font-size:11px">${esc(String(p.failReason).slice(0, 24))}</span>` : ''}</td>
        <td>${ddLink('account', p.payerAccountId, p.payerAccountId)}</td>
        <td>${esc(p.channelCode || '—')}<br>${p.channelBizNo ? ddLink('channel', p.channelBizNo, p.channelBizNo) : '<span class="muted">—</span>'}</td>
        <td>${combo ? '<span class="muted">按腿（见抽屉）</span>' : ddLink('voucher', p.id, '凭证')}</td>
        <td>${fmtTime(p.createdAt)}</td></tr>`;
    }).join('') : emptyRow(11, '暂无收银台受理单（可调整过滤条件）'));
}
async function ppCoMethods() {
  const oid = ppVal('coOrder'), aid = ppVal('coAccount');
  const box = document.getElementById('coBox');
  if (!oid) { toast('请输入交易单 ID', false); return; }
  ppLoadingBox(box, '加载收银台方式编排…');
  const r = await tryApi('/cashier/methods?orderId=' + encodeURIComponent(oid) + (aid ? '&accountId=' + encodeURIComponent(aid) : ''));
  if (!r.ok) { box.innerHTML = ddBox('danger', '方式编排加载失败：' + esc(r.err.message)); return; }
  const d = r.data || {}, o = d.order || {}, ms = d.methods || [];
  PPCashier.cashierMethods = d;
  box.innerHTML = ddKv([['交易单', ddLink('order', o.id, o.id)], ['商户', esc(o.merchantName || o.merchantId || '—')],
      ['主题', esc(o.subject || '—')], ['金额（元）', ppFen(o.amount)], ['状态', ppBadge(o.status, o.status)],
      ['产品', esc(PP_PRODUCT[o.productType] || o.productType || '—')], ['到期', o.expireAt ? fmtTime(o.expireAt) : '—']]) +
    ddSec('可用支付方式（后端编排结果，业务方据此渲染）', ms.length ? ms.map(m => `
      <div class="pp-prod"><div class="pp-inline" style="justify-content:space-between">
        <b>${esc(m.name || m.method)} <span class="pp-mono muted">${esc(m.method)}</span></b>
        ${m.enabled === false ? ppTag('off', '不可用') : ppTag('on', '可用')}</div>
        <div class="pp-note">${esc(m.hint || m.reason || '')}</div>
        ${ddTable(['账户', '会员', '类型', '币种', '余额（元）'], (m.accounts || []).map(a =>
          `<tr><td>${ddLink('account', a.id, a.id)}</td><td>${esc(a.memberName || a.memberId || '—')}</td>
            <td>${esc(a.type || '—')}</td><td>${esc(a.currency || 'CNY')}</td><td class="num">${ppFen(a.balance)}</td></tr>`).join(''), '无可用账户')}
      </div>`).join('') : ddBox('plain', '该交易单当前无可用支付方式'), ms.length + ' 种');
}
async function ppCoCode(parse) {
  const code = ppVal('coCode');
  const box = document.getElementById('coCodeBox');
  if (!code) { toast('请输入付款码 / 主扫码串', false); return; }
  ppLoadingBox(box, parse ? '解析码串（只读）…' : '查询付款码…');
  const r = parse ? await ppTryPost('/cashier/code/parse', { code })
    : await tryApi('/cashier/code/' + encodeURIComponent(code));
  if (!r.ok) { box.innerHTML = ddBox('danger', (parse ? '解析' : '查询') + '失败：' + esc(r.err.message)); return; }
  ppResult(box, parse ? '码串归一解析（只读，不动账）' : '付款码详情', r.data);
}
/** 付款码 / 收款码运营（真实写接口 POST /api/cashier/code，guardedApi 自动按需升级 step-up） */
async function ppCoCreate() {
  const type = ppVal('coCodeType') || 'USER_PRESENTED';
  const accountId = ppVal('coCodeAccount'), orderId = ppVal('coCodeOrder');
  if (!accountId && !orderId) { toast('付款/收款账户与交易单至少填一个', false); return; }
  let amount = null;
  const raw = ppVal('coCodeAmount');
  if (raw !== '') { try { amount = toFen(raw); } catch (e) { toast('金额非法：' + e.message, false); return; } }
  const req = ppVal('coCodeReq') || ppReqId('UI-code');
  const html = `<div class="box warn"><b>生成${type === 'USER_PRESENTED' ? '付款码（被扫）' : '收款码（主扫）'}</b>（真实写：落码表，一次性核销）</div>
    ${ddKv([['码类型', esc(type)], ['账户', esc(accountId || '（按订单推导）')], ['交易单', esc(orderId || '（定额码）')],
      ['金额（元）', amount == null ? '按订单金额' : ppFen(amount)], ['幂等键', esc(req)]])}
    <div class="pp-note">同 requestId 重复生成返回同一有效码；码有效期与一次性核销由后端 CAS 守卫。
      生成后可用「查询码 / 解析码串」复核状态（ACTIVE → CONSUMED / EXPIRED）。</div>`;
  try {
    const r = await ppGuarded('生成付款码 / 收款码', html, '/cashier/code', 'POST',
      { type, accountId: accountId || undefined, orderId: orderId || undefined, amount, requestId: req }, '确认生成');
    if (!r) return;
    PPCashier.lastCode = r;
    ppResult('coCodeBox', '码已生成', r);
    const code = r && (r.code || r.qrContent);
    if (code) { const e = document.getElementById('coCode'); if (e) e.value = code; }
    toast('码已生成');
  } catch (e) { ppErrBox('coCodeBox', '生成付款码', e); }
}

/* ===================== 页 2 · 合并支付与分阶段 ===================== */
async function loadComboPay() {
  lpLoading('cpOrder', 11, '加载合并 / 分阶段交易单…');
  const r = await tryApi('/orders');
  if (!r.ok) { lpError('cpOrder', 11, r.err.message); return; }
  PPCashier.orders = r.data || [];
  const rel = PPCashier.orders.filter(o => o.mergeId || o.stageCount || (o.priceChangeCount || 0) > 0);
  lpSet('cpOrder', rel);
  const box = document.getElementById('cpSum');
  if (box) {
    const mids = [...new Set(rel.filter(o => o.mergeId).map(o => o.mergeId))];
    const stages = rel.filter(o => o.stageCount);
    const priced = rel.filter(o => (o.priceChangeCount || 0) > 0);
    box.innerHTML = `<div class="stat-cards">
      <div class="stat-card accent"><div class="label">合并单（去重）</div><div class="value">${mids.length}</div></div>
      <div class="stat-card"><div class="label">合并子单</div><div class="value">${rel.filter(o => o.mergeId).length}</div></div>
      <div class="stat-card"><div class="label">分阶段单</div><div class="value">${stages.length}</div></div>
      <div class="stat-card"><div class="label">改过价单</div><div class="value">${priced.length}</div></div>
      <div class="stat-card"><div class="label">交易单全量</div><div class="value">${PPCashier.orders.length}</div></div></div>
      ${mids.length ? `<div class="pp-note">合并单号：${mids.slice(0, 12).map(m => ddLink('merge', m, m)).join(' ')}${mids.length > 12 ? ' …' : ''}</div>`
        : '<div class="box plain">当前无合并支付单。可在下方「创建合并单」用两笔 CREATED 交易单发起（不动账），或在交易单页下单后回到本页。</div>'}`;
  }
}
function ppCpOrderRows(rows) {
  const t = document.getElementById('cpOrderTable'); if (!t) return;
  t.innerHTML = `<tr><th>交易单</th><th>商户</th><th>合并单</th><th class="num">金额（元）</th><th>状态</th>
    <th class="num">期数</th><th class="num">已付期</th><th class="num">已付额（元）</th><th class="num">原价（元）</th>
    <th class="num">改价次数</th><th>操作</th></tr>` +
    (rows.length ? rows.map(o => `<tr><td>${ddLink('order', o.id, o.id)}</td>
      <td>${esc(o.merchantName || o.merchantId || '—')}</td>
      <td>${o.mergeId ? ddLink('merge', o.mergeId, o.mergeId) : '<span class="muted">—</span>'}</td>
      <td class="num">${ppFen(o.amount)}</td><td>${ppBadge(o.status, o.status)}</td>
      <td class="num">${ppNum(o.stageCount)}</td><td class="num">${ppNum(o.paidStage)}</td>
      <td class="num">${ppFen(o.paidAmount)}</td><td class="num">${ppFen(o.originalAmount)}</td>
      <td class="num">${ppNum(o.priceChangeCount)}</td>
      <td><button class="btn small ghost" data-cp-fill="stage" data-cp-id="${esc(o.id)}">分阶段</button>
        <button class="btn small ghost" data-cp-fill="price" data-cp-id="${esc(o.id)}">改价</button>
        <button class="btn small ghost" data-cp-fill="log" data-cp-id="${esc(o.id)}">留痕</button>
        ${o.paymentId ? `<button class="btn small ghost" data-cp-fill="cancel" data-cp-id="${esc(o.paymentId)}">撤销</button>` : ''}</td></tr>`).join('')
      : emptyRow(11, '暂无合并 / 分阶段 / 改价交易单'));
}
function ppCpFill(kind, id) {
  const map = { stage:'cpStageOrder', price:'cpPriceOrder', log:'cpLogOrder', cancel:'cpCancelPay' };
  const el = document.getElementById(map[kind]);
  if (el) { el.value = id; el.focus(); }
  if (kind === 'log') ppCpLogs();
  else toast('已填入 ' + id);
}
function ppCpLogRows(rows) {
  const t = document.getElementById('cpLogTable'); if (!t) return;
  t.innerHTML = `<tr><th>留痕 ID</th><th>交易单</th><th class="num">原价（元）</th><th class="num">新价（元）</th>
    <th class="num">差额（元）</th><th>方向</th><th>原因</th><th>操作人</th><th>退款单</th><th>补差单</th><th>时间</th></tr>` +
    (rows.length ? rows.map(l => `<tr><td class="pp-mono">${esc(l.id)}</td><td>${ddLink('order', l.orderId, l.orderId)}</td>
      <td class="num">${ppFen(l.oldAmount)}</td><td class="num">${ppFen(l.newAmount)}</td>
      <td class="num ${Number(l.delta) < 0 ? 'neg' : ''}">${ppFen(l.delta)}</td>
      <td>${ppBadge(l.direction === 'DOWN' ? 'REVERSE' : 'SETTLED', l.direction || '—')}</td>
      <td>${esc(l.reason || '—')}</td><td>${esc(l.operator || '—')}</td>
      <td>${ddLink('refund', l.refundId, l.refundId)}</td><td>${ddLink('order', l.supplementOrderId, l.supplementOrderId)}</td>
      <td>${fmtTime(l.createdAt)}</td></tr>`).join('') : emptyRow(11, '该交易单无改价留痕'));
}
async function ppCpLogs() {
  const id = ppVal('cpLogOrder');
  if (!id) { toast('请输入交易单 ID', false); return; }
  lpLoading('cpLog', 11, '加载改价留痕…');
  const r = await tryApi('/trade-plus/orders/' + encodeURIComponent(id) + '/price-logs');
  if (!r.ok) { lpError('cpLog', 11, r.err.message); return; }
  PPCashier.logs = r.data || [];
  lpSet('cpLog', PPCashier.logs);
}
async function ppCpMergeQuery() {
  const id = ppVal('cpMergeId');
  if (!id) { toast('请输入合并单 ID', false); return; }
  const box = document.getElementById('cpMergeBox');
  ppLoadingBox(box, '加载合并单…');
  const r = await tryApi('/trade-plus/merges/' + encodeURIComponent(id));
  if (!r.ok) { box.innerHTML = ddBox('danger', '合并单加载失败：' + esc(r.err.message)); return; }
  PPCashier.merges[id] = r.data;
  box.innerHTML = ppMergeHtml(r.data);
}
function ppMergeHtml(m) {
  const rels = m.rels || [];
  const sum = rels.reduce((s, x) => s + Number(x.amount || 0), 0);
  const fee = rels.reduce((s, x) => s + Number(x.fee || 0), 0);
  let html = ddKv([['合并单', esc(m.id)], ['商户', esc(m.merchantId || '—')], ['状态', ppBadge(m.status, m.status)],
    ['总额（元）', ppFen(m.totalAmount)], ['子单数 / 已付', `${ppNum(m.orderCount)} / ${ppNum(m.paidCount)}`],
    ['承载单', ddLink('order', m.carrierOrderId, m.carrierOrderId)], ['支付单', ddLink('payment', m.paymentId, m.paymentId)],
    ['幂等键', esc(m.requestId || '—')], ['创建 / 支付', `${fmtTime(m.createdAt)} / ${m.paidAt ? fmtTime(m.paidAt) : '—'}`]]);
  html += ddSec('金额守恒', ppCons('Σ子单金额 = 合并单总额', sum, m.totalAmount, { leftLabel:'Σrels.amount', rightLabel:'totalAmount' }));
  html += ddSec('子单与 fee 分摊', ddTable(['交易单', '金额（元）', '手续费（元）', '已退（元）', '子单状态'],
    rels.map(x => `<tr><td>${ddLink('order', x.orderId, x.orderId)}</td><td class="num">${ppFen(x.amount)}</td>
      <td class="num">${ppFen(x.fee)}</td><td class="num">${ppFen(x.refundedAmount)}</td>
      <td>${ppBadge(x.orderStatus, x.orderStatus)}</td></tr>`).join(''), '无子单'),
    rels.length + ' 单 · Σfee ¥ ' + yuan(fee));
  if (hasPerm('trade:write')) {
    html += ddSec('运维操作', `<div class="pp-inline">
      <button class="btn small" data-pp-merge-pay="${esc(m.id)}">合并支付（需二次认证）</button>
      <button class="btn small ghost" data-pp-merge-dissolve="${esc(m.id)}">拆分合并单（不动账）</button></div>
      <div class="pp-note">拆分为明确语义：子订单解绑后可个别支付；已支付的合并单不可拆分。</div>`);
  }
  return html;
}
async function ddMerge(id) {
  const r = await tryApi('/trade-plus/merges/' + encodeURIComponent(id));
  if (!r.ok) return ddBox('danger', '合并单加载失败：' + esc(r.err.message));
  PPCashier.merges[id] = r.data;
  return ppMergeHtml(r.data);
}
async function ppCpMergeCreate() {
  const ids = ppVal('cpMergeIds').split(/[\s,，;；]+/).filter(Boolean);
  if (ids.length < 2) { toast('至少 2 个交易单 ID（逗号 / 空格分隔）', false); return; }
  const req = ppVal('cpMergeReq') || ppReqId('UI-merge');
  const html = `<div class="box info"><b>创建合并单</b>（不动账）：把 ${ids.length} 笔交易单绑到一个合并主单。</div>
    <div class="kv">${ids.map(i => `<div><span class="k">子单</span><span class="v pp-mono">${esc(i)}</span></div>`).join('')}
    <div><span class="k">幂等键</span><span class="v pp-mono">${esc(req)}</span></div></div>
    <div class="pp-note">要求：同商户、均为 CREATED 未支付；创建后可拆分（dissolve）还原。</div>`;
  try {
    const r = await ppGuarded('创建合并支付单', html, '/trade-plus/orders/merge', 'POST', { orderIds: ids, requestId: req }, '确认创建');
    if (!r) return;
    ppResult('cpOpBox', '合并单已创建', r);
    const mid = r && (r.id || (r.merge && r.merge.id));
    if (mid) { const e = document.getElementById('cpMergeId'); if (e) e.value = mid; await ppCpMergeQuery(); }
    toast('合并单已创建'); await loadComboPay();
  } catch (e) { ppErrBox('cpOpBox', '创建合并单', e); }
}
async function ppCpMergeOp(op, id) {
  const m = PPCashier.merges[id] || {};
  try {
    if (op === 'dissolve') {
      const html = `<div class="box warn"><b>拆分合并单</b>（不动账）：子订单解绑，可个别支付。</div>
        ${ddKv([['合并单', esc(id)], ['子单数', ppNum(m.orderCount)], ['总额（元）', ppFen(m.totalAmount)]])}`;
      const r = await ppGuarded('拆分合并单', html, '/trade-plus/merges/' + encodeURIComponent(id) + '/dissolve', 'POST', {}, '确认拆分');
      if (r) { ppResult('cpOpBox', '合并单已拆分', r); toast('已拆分'); await loadComboPay(); await ppCpMergeQuery(); }
      return;
    }
    const c = await askConfirm({ title:'合并支付 · 付款账户', input:{ label:'付款账户 ID', placeholder:'A2', required:true },
      html:`<div class="box warn"><b>合并支付真实动账</b>：一次支付 ${ppNum(m.orderCount)} 笔子单，合计 <b>¥ ${yuan(m.totalAmount || 0)}</b></div>
        <div class="pp-note">可选 legs（组合支付分腿）请在下方 JSON 中填写；留空为单一余额支付。</div>` });
    if (!c) return;
    let legs = null;
    const raw = ppVal('cpMergeLegs');
    if (raw) { try { legs = JSON.parse(raw); } catch (e) { toast('legs 不是合法 JSON', false); return; } }
    const html = `<div class="box danger"><b>合并支付（真实动账，不可逆）</b></div>
      ${ddKv([['合并单', esc(id)], ['付款账户', esc(c.value)], ['总额（元）', ppFen(m.totalAmount)],
        ['分腿', legs ? esc(JSON.stringify(legs)) : '单一余额']])}
      <div class="pp-note">requestId 幂等；失败不产生悬挂单（受理前拒绝）。</div>`;
    const r = await sensitiveApi('/trade-plus/merges/' + encodeURIComponent(id) + '/pay', 'POST',
      { accountId: c.value, requestId: ppReqId('UI-mpay'), legs }, html);
    ppResult('cpOpBox', '合并支付已受理', r); toast('合并支付已受理');
    await loadComboPay(); await ppCpMergeQuery();
  } catch (e) {
    if (isReviewPending(e)) { const b = document.getElementById('cpOpBox'); if (b) b.innerHTML = reviewPendingBox(e); return; }
    ppErrBox('cpOpBox', '合并支付操作', e);
  }
}
async function ppCpStagePlan() {
  const id = ppVal('cpStageOrder');
  const raw = ppVal('cpStageAmounts');
  if (!id || !raw) { toast('请填写交易单 ID 与各期金额（元，逗号分隔）', false); return; }
  let amounts;
  try { amounts = raw.split(/[\s,，]+/).filter(Boolean).map(toFen); }
  catch (e) { toast('期额非法：' + e.message, false); return; }
  const o = (PPCashier.orders || []).find(x => x.id === id) || {};
  const sum = amounts.reduce((s, x) => s + x, 0);
  const html = `<div class="box info"><b>创建分阶段计划</b>（不动账）：${amounts.length} 期（定金 / 尾款）。</div>
    ${ppCons('Σ期额 = 订单额', sum, o.amount, { leftLabel:'Σamounts', rightLabel:'order.amount',
      note:'后端强校验 Σ期额 = 订单额，不等则拒绝（A0400）。' })}
    <div class="pp-note">各期（元）：${amounts.map(a => yuan(a)).join(' + ')}</div>`;
  if (o.amount != null && sum !== Number(o.amount)) { toast('Σ期额必须等于订单额 ¥ ' + yuan(o.amount), false); return; }
  try {
    const r = await ppGuarded('创建分阶段计划', html, '/trade-plus/orders/' + encodeURIComponent(id) + '/stage-plan',
      'POST', { amounts, requestId: ppReqId('UI-stage') }, '确认创建计划');
    if (!r) return;
    ppResult('cpOpBox', '分阶段计划已创建', r); toast('分阶段计划已创建'); await loadComboPay();
    const c = await askConfirm({ title:'分阶段付款', danger:false, okText:'稍后再付',
      input:{ label:'要付的期序 stageSeq（留空跳过）', required:false },
      html:'计划已创建。如需立即付某一期，填写期序（1 起）后点「确认执行」；否则点「稍后再付」。' });
    if (c && c.value) await ppCpStagePay(id, Number(c.value));
  } catch (e) { ppErrBox('cpOpBox', '创建分阶段计划', e); }
}
/** 表单直付某一期（无需先建计划弹窗） */
async function ppCpStagePayForm() {
  const id = ppVal('cpStageOrder'), seq = ppVal('cpStageSeq');
  if (!id || !seq) { toast('请填写交易单 ID 与期序 stageSeq', false); return; }
  await ppCpStagePay(id, Number(seq));
}
async function ppCpStagePay(id, seq) {
  const o = (PPCashier.orders || []).find(x => x.id === id) || {};
  try {
    const c = await askConfirm({ title:'分阶段付款 · 参数', input:{ label:'付款账户 ID', placeholder:'A2', required:true },
      html:`<div class="box warn"><b>分阶段付款真实动账</b></div>
        ${ddKv([['交易单', esc(id)], ['期序', ppNum(seq)], ['订单额（元）', ppFen(o.amount)],
          ['期额数组', esc(JSON.stringify(o.stageAmounts || []))], ['已付期 / 已付额', `${ppNum(o.paidStage)} / ${ppFen(o.paidAmount)}`]])}` });
    if (!c) return;
    const html = `<div class="box danger"><b>分阶段付款</b>：交易单 ${esc(id)} 第 ${esc(String(seq))} 期（真实动账）</div>
      <div class="pp-note">requestId 幂等；amount 留空则按该期计划额扣款。</div>`;
    const r = await sensitiveApi('/trade-plus/orders/' + encodeURIComponent(id) + '/stage-pay', 'POST',
      { stageSeq: seq, accountId: c.value, requestId: ppReqId('UI-spay') }, html);
    ppResult('cpOpBox', '分阶段付款已受理', r); toast('分阶段付款已受理'); await loadComboPay();
  } catch (e) { ppErrBox('cpOpBox', '分阶段付款', e); }
}
async function ppCpPrice(paid) {
  const id = ppVal('cpPriceOrder'), reason = ppVal('cpPriceReason');
  if (!id) { toast('请输入交易单 ID', false); return; }
  let newAmount;
  try { newAmount = toFen(ppVal('cpPriceAmount')); } catch (e) { toast('新价非法：' + e.message, false); return; }
  const o = (PPCashier.orders || []).find(x => x.id === id) || {};
  const eff = Number(o.amount || 0) - Number(o.refundedAmount || 0);
  const delta = newAmount - eff;
  const path = '/trade-plus/orders/' + encodeURIComponent(id) + (paid ? '/adjust-price' : '/price');
  const html = `<div class="box ${paid ? 'danger' : 'warn'}"><b>${paid ? '已支付改价 · 差额补退' : '未支付改价'}</b>${paid ? '（真实动账）' : '（不动账）'}</div>
    ${ddKv([['交易单', esc(id)], ['当前有效额（元）', ppFen(eff)], ['新价（元）', ppFen(newAmount)],
      ['差额（元）', `<b class="${delta < 0 ? 'neg' : ''}">${yuan(delta)}</b>`], ['原因', esc(reason || '—')]])}
    ${ppCons('改价后金额守恒（新价 = 有效额 + 差额）', eff + delta, newAmount, { leftLabel:'eff+delta', rightLabel:'newAmount' })}
    <div class="pp-note">${paid
      ? '下调差额 ≥ 大额阈值 → 双人复核 A0428（未复核绝不动账）；上调生成补差单。撤销 vs 退款：撤销走当日全额反向（CAS 守卫），退款走部分 / 全额退款单。'
      : '仅 CREATED 未支付订单可改价；改价留痕可在下方查询。'}</div>`;
  try {
    const r = await sensitiveApi(path, 'POST',
      { newAmount, reason: reason || '控制台运营改价', requestId: ppReqId('UI-price'), accountId: ppVal('cpPriceAccount') || undefined }, html);
    ppResult('cpOpBox', paid ? '已支付改价已受理' : '未支付改价完成', r);
    toast(paid ? '改价差额已受理' : '改价完成');
    const e = document.getElementById('cpLogOrder'); if (e) e.value = id;
    await loadComboPay(); await ppCpLogs();
  } catch (e) {
    if (isReviewPending(e)) { const b = document.getElementById('cpOpBox'); if (b) b.innerHTML = reviewPendingBox(e); return; }
    ppErrBox('cpOpBox', paid ? '已支付改价' : '未支付改价', e);
  }
}
async function ppCpCancel() {
  const id = ppVal('cpCancelPay');
  if (!id) { toast('请输入支付单 ID', false); return; }
  const p = await tryApi('/payments/' + encodeURIComponent(id));
  const d = p.ok ? p.data : {};
  const html = `<div class="box danger"><b>当日撤销（真实全额反向动账，不可逆）</b></div>
    ${ddKv([['支付单', esc(id)], ['交易单', ddLink('order', d.orderId, d.orderId)], ['金额（元）', ppFen(d.amount)],
      ['状态', ppBadge(d.status, d.status || '未知')], ['渠道单号', esc(d.channelBizNo || '—')]])}
    <div class="box warn"><b>撤销 vs 退款</b><br>撤销 = 当日全额反向（P-5 CAS 守卫，渠道未清算前可用）；
      退款 = 部分 / 全额退款单（可跨日，走 /api/payments/{id}/refund）。二者不可混用。</div>
    <div class="pp-note">SUCCESS 支付单全额反向若达大额阈值 → 双人复核 A0428，未复核绝不动账。</div>`;
  try {
    const r = await sensitiveApi('/trade-plus/payments/' + encodeURIComponent(id) + '/cancel', 'POST',
      { requestId: ppReqId('UI-cancel') }, html);
    ppResult('cpOpBox', '撤销已受理', r); toast('撤销已受理'); await loadComboPay();
  } catch (e) {
    if (isReviewPending(e)) { const b = document.getElementById('cpOpBox'); if (b) b.innerHTML = reviewPendingBox(e); return; }
    ppErrBox('cpOpBox', '当日撤销', e);
  }
}

/* 抽屉内合并单操作按钮（delegated，挂在 document 上一次性） */
function ppCashierDrawerWire() {
  document.addEventListener('click', ev => {
    const b = ev.target.closest && ev.target.closest('[data-pp-merge-pay],[data-pp-merge-dissolve]');
    if (!b) return;
    ev.preventDefault();
    if (b.dataset.ppMergePay) ppCpMergeOp('pay', b.dataset.ppMergePay);
    else ppCpMergeOp('dissolve', b.dataset.ppMergeDissolve);
  });
  /* 方法目录 → 页内锚点（不跳页，避免运营丢失上下文） */
  document.addEventListener('click', ev => {
    const b = ev.target.closest && ev.target.closest('[data-co-scroll]');
    if (!b) return;
    ev.preventDefault();
    const el = document.getElementById(b.dataset.coScroll === 'code' ? 'coCodeCard' : 'coMethodsCard');
    if (el) { el.scrollIntoView({ behavior:'smooth', block:'start' }); const i = el.querySelector('input'); if (i) i.focus(); }
  });
}

/* 显式暴露到 window：顶层 const 只进全局词法作用域，不挂 window 会让 if(window.PPCashier) 恒 false */
window.PPCashier = PPCashier;