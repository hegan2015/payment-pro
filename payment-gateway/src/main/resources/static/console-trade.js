/* =====================================================================
 * payment-pro 管理控制台 · 交易产品（IA 分组 2）
 * ---------------------------------------------------------------------
 * 实探端点（零后端改动）：
 *  /api/trade      ：products(GET) · expire/due(GET,只读预览) · expire/run(POST,step-up)
 *                    escrow/auto-confirm(POST,step-up) · orders/{id}/confirm(POST,step-up)
 *                    orders/{id}/preauth(POST) · preauth/complete(POST,step-up) · preauth/cancel(POST,step-up)
 *  /api/orders     ：GET 交易单全量（客户端分页 + 产品/状态/担保态/日期过滤）
 * 收银台运营 / 合并支付与分阶段两页已拆分到 console-cashier.js（PPCashier）。
 * 顶层只声明；DOM / 全局依赖全部在 PPTrade.init()（index.html 尾部显式 window 暴露后调用）。
 * ===================================================================== */

const PP_PRODUCT = { INSTANT:'即时到账', ESCROW:'担保交易', PREAUTH:'预授权' };
const PP_ESCROW_ST = { HOLD:'担保中', CONFIRMED:'已确认收货', AUTO_CONFIRMED:'超时自动确认' };
const PP_PREAUTH_ST = { AUTHORIZED:'已冻结', PARTIAL_COMPLETED:'部分完成', COMPLETED:'已完成', CANCELLED:'已撤销' };

const PPTrade = {
  products: [], orders: [], due: [],

  /* 全局加载函数约定（console-nav.js loader 指向这里） */
  loadProducts() { return loadTradeProducts(); },

  init() {
    lpDef('tpDue', { table:'tpDueTable', render:ppTpDueRows, filters:[
      { k:'kw', label:'关键字', ph:'交易单 / 商户 / 主题', fields:['id','merchantId','merchantName','subject'] },
      { k:'productType', label:'产品', type:'select', field:'productType',
        opts:[opt('','全部'),opt('INSTANT','INSTANT 即时到账'),opt('ESCROW','ESCROW 担保'),opt('PREAUTH','PREAUTH 预授权')] } ] });
    lpDef('tpOrder', { table:'tpOrderTable', render:ppTpOrderRows, filters:[
      { k:'kw', label:'关键字', ph:'交易单 / 商户 / 主题 / 支付单', fields:['id','merchantId','merchantName','subject','paymentId'] },
      { k:'productType', label:'产品', type:'select', field:'productType',
        opts:[opt('','全部'),opt('INSTANT','INSTANT 即时到账'),opt('ESCROW','ESCROW 担保'),opt('PREAUTH','PREAUTH 预授权')] },
      { k:'status', label:'交易状态', type:'select', field:'status',
        opts:[opt('','全部'),opt('CREATED','CREATED 待付'),opt('PAYING','PAYING 支付中'),opt('PAID','PAID 已付'),
          opt('PARTIAL_REFUNDED','PARTIAL_REFUNDED 部分退'),opt('REFUNDED','REFUNDED 已退'),opt('CLOSED','CLOSED 已关闭')] },
      { k:'escrowStatus', label:'担保态', type:'select', field:'escrowStatus',
        opts:[opt('','全部'),opt('HOLD','HOLD 担保中'),opt('CONFIRMED','CONFIRMED 已确认'),opt('AUTO_CONFIRMED','AUTO_CONFIRMED 自动确认')] },
      ...DATE_FILTERS ] });

    const on = (id, fn) => { const el = document.getElementById(id); if (el) el.addEventListener('click', fn); };
    on('btnTpRefresh', () => loadTradeProducts());
    on('btnTpDue', () => ppTpDue());
    on('btnTpExpireRun', () => ppTpBatch('expire'));
    on('btnTpEscrowAuto', () => ppTpBatch('escrow'));

    /* 行内操作 delegated */
    const sec = document.getElementById('tab-trade-products');
    if (sec) sec.addEventListener('click', ev => {
      const b = ev.target.closest && ev.target.closest('[data-tp-op]');
      if (b) { ev.preventDefault(); ppTpOrderOp(b.dataset.tpOp, b.dataset.tpId); }
    });
  }
};

/* ===================== 交易产品 ===================== */
async function loadTradeProducts() {
  const box = document.getElementById('tpProducts');
  if (box) ppLoadingBox(box, '加载产品目录…');
  lpLoading('tpOrder', 10, '加载交易单…');
  const [p, o] = await Promise.all([tryApi('/trade/products'), tryApi('/orders')]);
  if (!p.ok) { if (box) box.innerHTML = ddBox('danger', '产品目录加载失败：' + esc(p.err.message)); }
  else { PPTrade.products = p.data || []; ppTpProducts(); }
  if (!o.ok) lpError('tpOrder', 10, o.err.message);
  else { PPTrade.orders = o.data || []; lpSet('tpOrder', PPTrade.orders); ppTpKpi(); }
  await ppTpDue(true);
}
/** 产品维度分布（真实交易单快照聚合，点产品名过滤下方列表） */
function ppTpKpi() {
  const box = document.getElementById('tpKpi');
  if (!box) return;
  const os = PPTrade.orders || [];
  const by = k => os.filter(o => (o.productType || 'INSTANT') === k);
  const amt = list => list.reduce((s, o) => s + Number(o.amount || 0), 0);
  const hold = os.filter(o => o.escrowStatus === 'HOLD');
  const auth = os.filter(o => o.preauthStatus === 'AUTHORIZED' || o.preauthStatus === 'PARTIAL_COMPLETED');
  box.innerHTML = `<div class="stat-cards">
    <div class="stat-card accent"><div class="label">交易单总数</div><div class="value">${os.length}</div></div>
    ${['INSTANT','ESCROW','PREAUTH'].map(k => `<div class="stat-card"><div class="label">${PP_PRODUCT[k]} ${k}</div>
      <div class="value">${by(k).length}</div><div class="label">¥ ${yuan(amt(by(k)))}</div></div>`).join('')}
    <div class="stat-card ${hold.length ? 'ok' : ''}"><div class="label">担保中（HOLD）</div><div class="value">${hold.length}</div></div>
    <div class="stat-card ${auth.length ? 'bad' : ''}"><div class="label">预授权冻结中</div><div class="value">${auth.length}</div></div>
  </div>`;
}
function ppTpProducts() {
  const box = document.getElementById('tpProducts');
  if (!box) return;
  const list = PPTrade.products || [];
  if (!list.length) { box.innerHTML = ddBox('plain', '暂无产品目录'); return; }
  box.innerHTML = list.map(p => {
    const params = Object.entries(p.params || {});
    const used = (PPTrade.orders || []).filter(o => (o.productType || 'INSTANT') === p.product).length;
    return `<div class="pp-prod">
      <div class="pp-inline" style="justify-content:space-between">
        <b>${esc(PP_PRODUCT[p.product] || p.product)} <span class="pp-mono muted">${esc(p.product)}</span></b>
        <span>${p.defaultProduct ? ppTag('on', '默认产品') : ''} ${ppYes(p.enabled, '已启用', '已停用')}</span>
      </div>
      <div class="pp-note">${esc(p.description || '')}</div>
      ${ppFlow(p.states || [], null)}
      <div class="pp-note">状态机 <span class="pp-mono">${esc(p.stateMachine || '—')}</span> · 状态 ${(p.states || []).length} 个
        · 存量交易单 <b>${used}</b> 笔</div>
      <div class="table-wrap" style="margin-top:8px"><table>
        <tr><th>DynamicConfig 参数</th><th>生效值</th></tr>
        ${params.length ? params.map(([k, v]) => `<tr><td class="pp-mono">${esc(k)}</td><td class="pp-mono">${esc(v)}</td></tr>`).join('')
          : emptyRow(2, '无参数')}
      </table></div></div>`;
  }).join('');
}
function ppTpDueRows(rows) {
  const t = document.getElementById('tpDueTable'); if (!t) return;
  t.innerHTML = `<tr><th>交易单</th><th>商户</th><th>主题</th><th>产品</th><th class="num">金额（元）</th>
    <th>创建时间</th><th>到期时间</th><th>超时时长</th></tr>` +
    (rows.length ? rows.map(o => {
      const over = o.expireAt ? Date.now() - o.expireAt : 0;
      return `<tr><td>${ddLink('order', o.id, o.id)}</td>
        <td>${esc(o.merchantName || o.merchantId || '—')}</td><td>${esc(o.subject || '—')}</td>
        <td>${ppBadge('kind', PP_PRODUCT[o.productType] || o.productType || '—')}</td>
        <td class="num">${ppFen(o.amount)}</td><td>${fmtTime(o.createdAt)}</td><td>${fmtTime(o.expireAt)}</td>
        <td>${over > 0 ? ppTag('bad', '已超时 ' + Math.floor(over / 60000) + ' 分') : ppTag('off', '未到期')}</td></tr>`;
    }).join('') : emptyRow(8, '该分片无到期候选（只读预览，不关闭任何订单）'));
}
async function ppTpDue(silent) {
  const idx = ppInt('tpDueIdx', 0), total = ppInt('tpDueTotal', 1);
  lpLoading('tpDue', 8, '预览到期候选…');
  const r = await tryApi('/trade/expire/due?shardIndex=' + idx + '&shardTotal=' + total);
  if (!r.ok) { lpError('tpDue', 8, r.err.message); return; }
  PPTrade.due = r.data || [];
  lpSet('tpDue', PPTrade.due);
  const box = document.getElementById('tpDueBox');
  if (box) box.innerHTML = ddBox('info', `分片 <b>${idx}/${total}</b> 到期候选 <b>${PPTrade.due.length}</b> 笔（只读预览，与作业同一 CRC32 分片算法，不重不漏）。`);
  if (!silent) toast('到期候选已刷新');
}
async function ppTpBatch(kind) {
  const expire = kind === 'expire';
  const idx = ppInt(expire ? 'tpExpIdx' : 'tpEscIdx', 0), total = ppInt(expire ? 'tpExpTotal' : 'tpEscTotal', 1);
  const path = expire ? '/trade/expire/run' : '/trade/escrow/auto-confirm';
  const title = expire ? '超时关闭跑批' : '担保超时自动确认跑批';
  const html = expire
    ? `<div class="box warn"><b>批量关闭未支付交易单</b>（真实状态变更）</div>
       <div class="kv"><div><span class="k">分片</span><span class="v">${idx} / ${total}</span></div>
       <div><span class="k">候选预览</span><span class="v">${PPTrade.due.length} 笔（当前预览分片）</span></div></div>
       <div class="pp-note">幂等：已关闭 / 已支付订单不会重复处理；仅 CREATED 且 expireAt ≤ now 的订单被关闭。</div>`
    : `<div class="box warn"><b>担保超时自动确认 → 打款卖家</b>（真实动账）</div>
       <div class="kv"><div><span class="k">分片</span><span class="v">${idx} / ${total}</span></div></div>
       <div class="pp-note">仅 ESCROW 且 escrow_status=HOLD 且超过 trade.escrow.auto-confirm-days 的订单被确认；
       幂等键 (ESCROW_CONFIRM,orderId) + CAS，重复触发不重复打款。</div>`;
  try {
    const r = await ppSensitive(title, html, path, 'POST', { shardIndex: idx, shardTotal: total }, html, '确认执行跑批');
    if (!r) return;
    ppResult('tpBatchBox', title + '完成', r);
    toast(title + '已执行');
    await loadTradeProducts();
  } catch (e) { ppErrBox('tpBatchBox', title, e); }
}
function ppTpOrderRows(rows) {
  const t = document.getElementById('tpOrderTable'); if (!t) return;
  const canW = hasPerm('trade:write');
  t.innerHTML = `<tr><th>交易单</th><th>商户</th><th>产品</th><th class="num">金额（元）</th><th>状态</th>
    <th>担保 / 预授权</th><th class="num">预授权额（元）</th><th class="num">已完成（元）</th><th>到期</th><th>操作</th></tr>` +
    (rows.length ? rows.map(o => {
      const ops = [];
      if (canW && o.productType === 'ESCROW' && o.escrowStatus === 'HOLD')
        ops.push(`<button class="btn small" data-tp-op="confirm" data-tp-id="${esc(o.id)}">确认收货</button>`);
      if (canW && o.productType === 'PREAUTH') {
        if (!o.preauthStatus) ops.push(`<button class="btn small ghost" data-tp-op="preauth" data-tp-id="${esc(o.id)}">冻结</button>`);
        if (o.preauthStatus === 'AUTHORIZED' || o.preauthStatus === 'PARTIAL_COMPLETED') {
          ops.push(`<button class="btn small" data-tp-op="complete" data-tp-id="${esc(o.id)}">完成扣款</button>`);
          ops.push(`<button class="btn small danger" data-tp-op="cancel" data-tp-id="${esc(o.id)}">撤销</button>`);
        }
      }
      const sub = o.productType === 'ESCROW' ? (PP_ESCROW_ST[o.escrowStatus] || o.escrowStatus || '—')
        : o.productType === 'PREAUTH' ? (PP_PREAUTH_ST[o.preauthStatus] || o.preauthStatus || '未冻结') : '—';
      return `<tr><td>${ddLink('order', o.id, o.id)}</td><td>${esc(o.merchantName || o.merchantId || '—')}</td>
        <td>${ppBadge('kind', PP_PRODUCT[o.productType] || o.productType || '—')}</td>
        <td class="num">${ppFen(o.amount)}</td><td>${ppBadge(o.status, o.status)}</td>
        <td>${esc(sub)}</td><td class="num">${ppFen(o.preauthAmount)}</td><td class="num">${ppFen(o.preauthCompleted)}</td>
        <td>${o.expireAt ? fmtTime(o.expireAt) : '—'}</td>
        <td>${ops.length ? ops.join(' ') : '<span class="muted">—</span>'}</td></tr>`;
    }).join('') : emptyRow(10, '无匹配交易单'));
}
async function ppTpOrderOp(op, id) {
  const o = (PPTrade.orders || []).find(x => x.id === id) || {};
  try {
    if (op === 'confirm') {
      const html = `<div class="box warn"><b>确认收货 → 担保户打款卖家</b>（真实动账）</div>
        ${ddKv([['交易单', esc(id)], ['金额（元）', ppFen(o.amount)], ['卖家', esc(o.payeeMerchantId || o.merchantId || '—')],
          ['担保态', esc(o.escrowStatus || '—')]])}
        <div class="pp-note">幂等键 (ESCROW_CONFIRM,${esc(id)}) + escrow_status CAS；重复确认不重复打款。</div>`;
      const r = await ppSensitive('担保确认收货', html, '/trade/orders/' + encodeURIComponent(id) + '/confirm', 'POST', {}, html);
      if (r) { ppResult('tpBatchBox', '确认收货完成', r); toast('已确认收货'); await loadTradeProducts(); }
      return;
    }
    if (op === 'preauth') {
      const c = await askConfirm({ title:'预授权冻结', okText:'确认冻结', html:
        `<div class="box info">复用账户 A-3 PREAUTH 冻结（不产生分录，试算平衡不受影响）</div>
         ${ddKv([['交易单', esc(id)], ['冻结金额（元）', ppFen(o.amount)]])}` });
      if (!c) return;
      const acc = await askConfirm({ title:'预授权冻结 · 付款账户', input:{ label:'付款账户 ID', placeholder:'A2', required:true },
        html:'请输入用于冻结的付款账户 ID（账户余额需 ≥ 订单额）。' });
      if (!acc) return;
      const r = await guardedApi('/trade/orders/' + encodeURIComponent(id) + '/preauth', 'POST',
        { accountId: acc.value, requestId: ppReqId('UI-preauth') }, '预授权冻结');
      ppResult('tpBatchBox', '预授权冻结完成', r); toast('预授权已冻结'); await loadTradeProducts();
      return;
    }
    if (op === 'complete') {
      const left = Number(o.preauthAmount || 0) - Number(o.preauthCompleted || 0);
      const c = await askConfirm({ title:'预授权完成扣款', input:{ label:'扣款金额（元）', value:(left / 100).toFixed(2), required:true },
        html:`<div class="box warn"><b>真实扣款</b>：从冻结额中扣减，剩余可扣 <b>¥ ${yuan(left)}</b></div>
          ${ddKv([['交易单', esc(id)], ['已冻结（元）', ppFen(o.preauthAmount)], ['已完成（元）', ppFen(o.preauthCompleted)]])}` });
      if (!c) return;
      const amount = toFen(c.value);
      if (amount > left) { toast('扣款额不得超过剩余冻结额 ¥ ' + yuan(left), false); return; }
      const html = `<div class="box warn"><b>预授权完成扣款</b> ¥ ${yuan(amount)}（真实动账）</div>
        <div class="pp-note">amount ≤ 剩余冻结额；requestId 幂等。</div>`;
      const r = await sensitiveApi('/trade/orders/' + encodeURIComponent(id) + '/preauth/complete', 'POST',
        { amount, requestId: ppReqId('UI-pac') }, html);
      ppResult('tpBatchBox', '预授权完成扣款', r); toast('预授权扣款完成'); await loadTradeProducts();
      return;
    }
    if (op === 'cancel') {
      const left = Number(o.preauthAmount || 0) - Number(o.preauthCompleted || 0);
      const html = `<div class="box warn"><b>预授权撤销</b>：剩余冻结额 <b>¥ ${yuan(left)}</b> 全额解冻</div>
        ${ddKv([['交易单', esc(id)], ['预授权态', esc(o.preauthStatus || '—')]])}
        <div class="pp-note">撤销后不可再完成扣款；requestId 幂等。</div>`;
      const r = await ppSensitive('预授权撤销', html, '/trade/orders/' + encodeURIComponent(id) + '/preauth/cancel',
        'POST', { requestId: ppReqId('UI-pax') }, html);
      if (r) { ppResult('tpBatchBox', '预授权撤销', r); toast('预授权已撤销'); await loadTradeProducts(); }
    }
  } catch (e) { ppErrBox('tpBatchBox', '交易产品操作', e); }
}

/* 显式暴露到 window：顶层 const 只进全局词法作用域，不挂 window 会让 if(window.PPTrade) 恒 false */
window.PPTrade = PPTrade;