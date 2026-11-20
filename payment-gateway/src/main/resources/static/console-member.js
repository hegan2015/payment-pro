/* =====================================================================
 * payment-pro 管理控制台 · 会员与商户运营（IA 分组 9）· UI 第 3 棒
 * ---------------------------------------------------------------------
 * 实探端点（零后端改动）：
 *  /api/members/{id}/kyc         GET/POST（member:read / member:write）
 *  /api/merchant                 applications(GET/POST) · applications/{id}(GET) ·
 *                                applications/{id}/resubmit(POST) · review(GET) ·
 *                                review/{id}/approve|reject|supplement(POST) ·
 *                                contracts(GET 列表 / {id} GET 详情，UI additive) · contracts(POST) ·
 *                                settle-cards(GET/POST)
 *                                路径权限 merchant:read/write/review + 控制器内 member:read/write
 *  /api/accounts/{id}/cards      GET/POST · /api/cards/{cardId}/unbind(POST) ·
 *  /api/accounts/{id}/cards/{cardId}/default(POST)          （account:read/write）
 *  /api/accounts/{id}/level      GET · level/upgrade(POST) · limit-check(GET,只读试算) ·
 *                                freeze|unfreeze(POST) · freezes(GET) · status-ex(POST) ·
 *                                /api/accounts/typed(POST)
 *  /api/member/*                 C 端认证（permitAll）——本页仅「治理侧观测」，不替代 C 端登录
 * 顶层只声明；DOM / 全局依赖全部在 PPMember.init()。
 * ===================================================================== */

const PP_KYC_ST = { PASS:'已实名', FAIL:'未通过', PENDING:'待核验', VERIFIED:'已核验', REJECTED:'已拒绝' };
const PP_APP_ST = { SUBMITTED:'已提交', REVIEWING:'审核中', SUPPLEMENT:'待补件', APPROVED:'已通过', REJECTED:'已驳回' };
const PP_LEVELS = [['I','I 级（未实名）'], ['II','II 级（基础实名）'], ['III','III 级（强实名）'], ['IV','IV 级（商户 / 高级）']];
const PP_FREEZE_REASONS = [['RISK','RISK 风控冻结'], ['JUDICIAL','JUDICIAL 司法冻结（解冻需双人）'],
  ['PREAUTH','PREAUTH 预授权'], ['DISPUTE','DISPUTE 争议冻结']];
const PP_ACCT_TYPES = [['BALANCE','BALANCE 余额户'], ['ESCROW','ESCROW 担保户'], ['PREAUTH','PREAUTH 预授权户'],
  ['SETTLE','SETTLE 结算户'], ['SUSPENSE','SUSPENSE 挂账户']];

const PPMember = {
  members: [], accounts: [], apps: [], reviews: [], cards: [], freezes: [], settleCards: [],
  kyc: {}, levels: {}, audit: [], contracts: [],

  /* 全局加载函数约定（console-nav.js loader 指向这里） */
  loadKyc() { return loadKyc(); },
  loadOnboarding() { return loadOnboarding(); },
  loadContract() { return loadContract(); },
  loadBindcard() { return loadBindCard(); },
  loadTier() { return loadMemberTier(); },
  loadAuth() { return loadMemberAuth(); },

  init() {
    lpDef('kycMember', { table:'kycMemberTable', render:ppKycMemberRows, filters:[
      { k:'kw', label:'关键字', ph:'会员 ID / 名称 / appId', fields:['id','name','appId'] },
      { k:'type', label:'类型', type:'select', field:'type', opts:[opt('','全部'),opt('USER','USER 个人'),opt('MERCHANT','MERCHANT 商户')] },
      { k:'status', label:'状态', type:'select', field:'status', opts:[opt('','全部'),opt('ACTIVE','ACTIVE 正常'),opt('FROZEN','FROZEN 冻结'),opt('CLOSED','CLOSED 注销')] },
      ...DATE_FILTERS ] });
    lpDef('obApp', { table:'obAppTable', render:ppObAppRows, filters:[
      { k:'kw', label:'关键字', ph:'进件单 / 商户名 / 会员 / appId / 驳回原因', fields:['id','merchantName','memberId','appId','rejectReason'] },
      { k:'status', label:'状态', type:'select', field:'status',
        opts:[opt('','全部')].concat(Object.keys(PP_APP_ST).map(k => opt(k, k + ' ' + PP_APP_ST[k]))) },
      ...DATE_FILTERS ] });
    lpDef('obReview', { table:'obReviewTable', render:ppObReviewRows, filters:[
      { k:'kw', label:'关键字', ph:'任务 ID / 进件单 / 审核人 / 意见', fields:['id','applicationId','reviewer','opinion'] },
      { k:'status', label:'结论', type:'select', field:'status',
        opts:[opt('','全部'),opt('APPROVED','APPROVED 通过'),opt('REJECTED','REJECTED 驳回'),opt('SUPPLEMENT','SUPPLEMENT 补件')] },
      ...DATE_FILTERS ] });
    lpDef('ctCard', { table:'ctCardTable', render:ppCtCardRows, filters:[
      { k:'kw', label:'关键字', ph:'卡 ID / 掩码 / 银行', fields:['id','cardNoMask','bankName'] },
      { k:'status', label:'状态', type:'select', field:'status', opts:[opt('','全部'),opt('BOUND','BOUND 已绑'),opt('UNBOUND','UNBOUND 已解绑')] } ] });
    lpDef('ctContract', { table:'ctContractTable', render:ppCtContractRows, timeField:'signedAt', filters:[
      { k:'kw', label:'关键字', ph:'合同 / 进件单 / 商户 / 产品 / 操作人', fields:['id','applicationId','merchantId','products','operator'] },
      { k:'status', label:'状态', type:'select', field:'status', opts:[opt('','全部'),opt('SIGNED','SIGNED 已签约')] },
      { k:'fee', label:'费率', type:'select', opts:[opt('','全部'),opt('custom','已约定费率'),opt('default','回落缺省费率')],
        test:(r,v) => v === 'custom' ? r.feeBps != null : r.feeBps == null },
      ...DATE_FILTERS ] });
    lpDef('bcCard', { table:'bcCardTable', render:ppBcCardRows, filters:[
      { k:'kw', label:'关键字', ph:'卡 ID / 掩码 / 银行 / 签约号', fields:['id','cardNoMask','bankName','signNo'] },
      { k:'status', label:'状态', type:'select', field:'status', opts:[opt('','全部'),opt('BOUND','BOUND 已绑'),opt('UNBOUND','UNBOUND 已解绑')] },
      { k:'isDefault', label:'默认卡', type:'select', opts:[opt('','全部'),opt('true','仅默认卡'),opt('false','非默认')],
        test:(r,v) => String(!!r.isDefault) === v } ] });
    lpDef('bcAccount', { table:'bcAccountTable', render:ppBcAccountRows, filters:[
      { k:'kw', label:'关键字', ph:'账户 / 会员 / 名称', fields:['id','memberId','memberName'] },
      { k:'type', label:'类型', type:'select', field:'type', opts:[opt('','全部'),opt('USER','USER'),opt('MERCHANT','MERCHANT')] },
      { k:'accountType', label:'账户类型', type:'select', field:'accountType',
        opts:[opt('','全部')].concat(PP_ACCT_TYPES.map(t => opt(t[0], t[1]))) } ] });
    lpDef('mtFreeze', { table:'mtFreezeTable', render:ppMtFreezeRows, filters:[
      { k:'kw', label:'关键字', ph:'冻结单 / 业务号 / 原因 / 操作人', fields:['id','bizNo','reason','operator','authorizedBy'] },
      { k:'reasonType', label:'原因类型', type:'select', field:'reasonType', opts:[opt('','全部')].concat(PP_FREEZE_REASONS) },
      { k:'status', label:'状态', type:'select', field:'status', opts:[opt('','全部'),opt('ACTIVE','ACTIVE 生效'),opt('RELEASED','RELEASED 已解')] } ] });
    lpDef('maAudit', { table:'maAuditTable', render:ppMaAuditRows, filters:[
      { k:'kw', label:'关键字', ph:'路径 / 操作人 / traceId / IP', fields:['path','username','traceId','ip'] },
      { k:'op', label:'认证动作', type:'select', opts:[opt('','全部'),opt('register','注册'),opt('sms-code','短信码'),
          opt('login','登录'),opt('logout','登出'),opt('session','会话校验'),opt('password','改密')],
        test:(r,v) => String(r.path || '').includes('/api/member/' + v) },
      { k:'result', label:'HTTP 状态', type:'select', field:'result',
        opts:[opt('','全部'),opt('200','200 成功'),opt('400','400 参数/业务'),opt('401','401 未认证'),opt('403','403 拒绝'),opt('429','429 限流')] },
      ...DATE_FILTERS ] });

    DD_KINDS.kyc = 'KYC 认证'; DD_RENDER.kyc = ddKyc;
    DD_KINDS.mapp = '商户进件单'; DD_RENDER.mapp = ddMapp;
    DD_KINDS.mcontract = '商户合同'; DD_RENDER.mcontract = ddMcontract;
    DD_KINDS.settlecard = '结算卡'; DD_RENDER.settlecard = ddSettleCard;
    DD_KINDS.bankcard = '绑定银行卡'; DD_RENDER.bankcard = ddBankCard;
    DD_KINDS.freeze = '账户冻结单'; DD_RENDER.freeze = ddFreeze;
    DD_KINDS.aclevel = '账户分级限额'; DD_RENDER.aclevel = ddAcLevel;

    const on = (id, fn) => { const el = document.getElementById(id); if (el) el.addEventListener('click', fn); };
    on('btnKycQuery', () => ppKycQuery());
    on('btnKycSubmit', () => ppKycSubmit());
    on('btnKycRefresh', () => loadKyc());
    on('btnObSubmit', () => ppObSubmit(false));
    on('btnObResubmit', () => ppObSubmit(true));
    on('btnObApprove', () => ppObReview('approve'));
    on('btnObReject', () => ppObReview('reject'));
    on('btnObSupplement', () => ppObReview('supplement'));
    on('btnObRefresh', () => loadOnboarding());
    on('btnCtSign', () => ppCtSign());
    on('btnCtBind', () => ppCtBind());
    on('btnCtCards', () => ppCtCards());
    on('btnCtRefresh', () => loadContract());
    on('btnBcQuery', () => ppBcQuery());
    on('btnBcBind', () => ppBcBind());
    on('btnBcRefresh', () => loadBindCard());
    on('btnMtLevel', () => ppMtLevel());
    on('btnMtUpgrade', () => ppMtUpgrade());
    on('btnMtCheck', () => ppMtCheck());
    on('btnMtFreeze', () => ppMtFreeze());
    on('btnMtUnfreeze', () => ppMtUnfreeze());
    on('btnMtStatusEx', () => ppMtStatusEx());
    on('btnMtTyped', () => ppMtTyped());
    on('btnMtRefresh', () => loadMemberTier());
    on('btnMaSession', () => ppMaSession());
    on('btnMaRefresh', () => loadMemberAuth());
    ['#kycMember'].forEach(s => { const e = document.querySelector(s);
      if (e) e.addEventListener('keydown', ev => { if (ev.key === 'Enter') ppKycQuery(); }); });
    ['#bcAccount','#mtAccount'].forEach(s => { const e = document.querySelector(s);
      if (e) e.addEventListener('keydown', ev => { if (ev.key === 'Enter') s === '#bcAccount' ? ppBcQuery() : ppMtLevel(); }); });

    const bcSec = document.getElementById('tab-bindcard');
    if (bcSec) bcSec.addEventListener('click', ev => {
      const b = ev.target.closest && ev.target.closest('[data-bc-unbind],[data-bc-default]');
      if (!b) return;
      ev.preventDefault();
      ppBcOp(b.dataset.bcUnbind ? 'unbind' : 'default', b.dataset.bcUnbind || b.dataset.bcDefault);
    });
    document.addEventListener('click', ev => {
      const b = ev.target.closest && ev.target.closest('[data-mm-kyc],[data-mm-card],[data-mm-level],[data-mm-app],[data-mm-fill]');
      if (!b) return;
      ev.preventDefault();
      if (b.dataset.mmKyc) { const e = document.getElementById('kycMember'); if (e) e.value = b.dataset.mmKyc; ppKycQuery(); }
      else if (b.dataset.mmCard) { const e = document.getElementById('bcAccount'); if (e) e.value = b.dataset.mmCard; ppBcQuery(); }
      else if (b.dataset.mmLevel) { const e = document.getElementById('mtAccount'); if (e) e.value = b.dataset.mmLevel; ppMtLevel(); }
      else if (b.dataset.mmApp) ppObFill(b.dataset.mmApp);
      else if (b.dataset.mmFill) {
        const map = { ctApp:'ctAppId', ctCardApp:'ctCardApp', ctMerchant:'ctMerchant', obReview:'obReviewId',
          unfreeze:'mtUnfreezeId' };
        const e = document.getElementById(map[b.dataset.mmFill]);
        if (e) { e.value = b.dataset.mmId; e.focus(); toast('已填入 ' + b.dataset.mmId); }
        if (b.dataset.mmFill === 'ctMerchant') ppCtCards();
      }
    });
  }
};

/* ===================== 页 1 · KYC 认证 ===================== */
async function loadKyc() {
  lpLoading('kycMember', 7, '加载会员…');
  const r = await tryApi('/members');
  if (!r.ok) lpError('kycMember', 7, r.err.message);
  else { PPMember.members = r.data || []; lpSet('kycMember', PPMember.members); }
  const box = document.getElementById('kycKnown');
  if (box) {
    const a = await ppAuditIds('/api/members/', /\/api\/members\/([^/]+)\/kyc$/, 200);
    box.innerHTML = ddBox(a.ids.length ? 'info' : 'plain',
      `${esc(a.note)}${a.ids.length ? '<br>已提交过 KYC 的会员：' + a.ids.slice(0, 30).map(m => ddLink('kyc', m, m)).join(' ') : ''}`);
  }
}
function ppKycMemberRows(rows) {
  const t = document.getElementById('kycMemberTable'); if (!t) return;
  t.innerHTML = `<tr><th>会员</th><th>名称</th><th>类型</th><th>状态</th><th>appId</th><th>创建时间</th><th>操作</th></tr>` +
    (rows.length ? rows.map(m => `<tr><td>${ddLink('member', m.id, m.id)}</td><td>${esc(m.name || '—')}</td>
      <td>${ppBadge('kind', m.type || '—')}</td><td>${ppBadge(m.status, m.status)}</td>
      <td class="pp-mono">${esc(m.appId || '—')}</td><td>${fmtTime(m.createdAt)}</td>
      <td><button class="btn small ghost" data-mm-kyc="${esc(m.id)}">查 KYC</button>
        ${ddLink('kyc', m.id, '认证详情')}</td></tr>`).join('') : emptyRow(7, '无匹配会员'));
}
function ppKycHtml(k) {
  return ddKv([['会员', ddLink('member', k.memberId, k.memberId)],
      ['核验结论', k.pass ? ppTag('on', '通过') : ppTag('bad', '未通过')],
      ['核验状态', ppBadge(k.pass ? 'APPROVED' : 'REJECTED', PP_KYC_ST[k.verifyStatus] || k.verifyStatus || '—')],
      ['联动等级', k.level ? ppBadge(k.level, k.level + ' 级') : '—'],
      ['姓名（脱敏）', esc(k.nameMask || '—')], ['证件（脱敏）', esc(k.idCardMask || '—')],
      ['银行卡（脱敏）', esc(k.bankCardMask || '—')], ['手机（脱敏）', esc(k.mobileMask || '—')],
      ['未通过原因', esc(k.reason || '—')], ['核验 / 提交时间', `${k.verifiedAt ? fmtTime(k.verifiedAt) : '—'} / ${fmtTime(k.createdAt)}`]]) +
    `<div class="pp-obs">敏感字段后端已脱敏返回（姓名 / 证件 / 卡号 / 手机仅掩码），控制台不落明文、不写日志。
      KYC 通过后账户等级与限额联动，详见「分级限额」页。</div>`;
}
async function ddKyc(id) {
  const r = await tryApi('/members/' + encodeURIComponent(id) + '/kyc');
  if (!r.ok) return ddBox('warn', '该会员尚未提交 KYC 或查询失败：' + esc(r.err.message)) +
    ddBox('plain', '可在「KYC 认证」页填写姓名 / 证件 / 银行卡 / 手机后提交（敏感操作，需二次认证）。');
  PPMember.kyc[id] = r.data;
  return ppKycHtml(r.data || {});
}
async function ppKycQuery() {
  const id = ppVal('kycMember');
  const box = document.getElementById('kycBox');
  if (!id) { toast('请输入会员 ID', false); return; }
  ppLoadingBox(box, '查询 KYC…');
  const r = await tryApi('/members/' + encodeURIComponent(id) + '/kyc');
  if (!r.ok) { box.innerHTML = ddBox('warn', esc(r.err.message) + '（该会员尚未提交 KYC，可在上方填写后提交）'); return; }
  PPMember.kyc[id] = r.data;
  box.innerHTML = ppKycHtml(r.data || {});
}
async function ppKycSubmit() {
  const id = ppVal('kycMember');
  if (!id) { toast('请输入会员 ID', false); return; }
  const body = { name: ppVal('kycName'), idCardNo: ppVal('kycIdCard'),
    bankCardNo: ppVal('kycBankCard'), mobile: ppVal('kycMobile') };
  if (!body.name || !body.idCardNo) { toast('姓名与证件号必填', false); return; }
  const html = `<div class="box warn"><b>提交实名认证（敏感操作）</b>：核验通过后联动账户等级与限额。</div>
    ${ddKv([['会员', esc(id)], ['姓名', esc(body.name)], ['证件号', esc(maskMid(body.idCardNo))],
      ['银行卡', esc(body.bankCardNo ? maskMid(body.bankCardNo) : '（未填）')],
      ['手机', esc(body.mobile ? maskMid(body.mobile) : '（未填）')]])}
    <div class="pp-note">明文仅用于本次核验请求，控制台不缓存、不写 localStorage；返回结果为脱敏视图。</div>`;
  try {
    const r = await ppSensitive('提交 KYC 实名认证', html, '/members/' + encodeURIComponent(id) + '/kyc', 'POST', body, html);
    if (!r) return;
    const box = document.getElementById('kycBox');
    if (box) box.innerHTML = ppKycHtml(r || {});
    toast(r && r.pass ? 'KYC 核验通过' : 'KYC 已提交（未通过，见原因）', !!(r && r.pass));
    ['#kycIdCard','#kycBankCard'].forEach(s => { const e = document.querySelector(s); if (e) e.value = ''; });
  } catch (e) { ppErrBox('kycBox', '提交 KYC', e); }
}
const maskMid = s => { const v = String(s || ''); return v.length <= 6 ? v.replace(/.(?=.{2})/g, '*')
  : v.slice(0, 3) + '*'.repeat(Math.max(2, v.length - 7)) + v.slice(-4); };

/* ===================== 页 2 · 进件与审核 ===================== */
async function loadOnboarding() {
  lpLoading('obApp', 10, '加载进件单…'); lpLoading('obReview', 7, '加载审核任务…');
  const [a, t] = await Promise.all([tryApi('/merchant/applications'), tryApi('/merchant/review')]);
  if (!a.ok) lpError('obApp', 10, a.err.message);
  else { PPMember.apps = a.data || []; lpSet('obApp', PPMember.apps); ppObSla(); }
  if (!t.ok) lpError('obReview', 7, t.err.message);
  else { PPMember.reviews = t.data || []; lpSet('obReview', PPMember.reviews); }
}
function ppObSla() {
  const box = document.getElementById('obSla'); if (!box) return;
  const apps = PPMember.apps || [];
  const done = apps.filter(a => a.reviewedAt && a.createdAt);
  const avg = done.length ? done.reduce((s, a) => s + (a.reviewedAt - a.createdAt), 0) / done.length : 0;
  const pending = apps.filter(a => a.status === 'SUBMITTED' || a.status === 'REVIEWING' || a.status === 'SUPPLEMENT');
  const slow = pending.filter(a => Date.now() - a.createdAt > 3600000);
  const cnt = st => apps.filter(a => a.status === st).length;
  box.innerHTML = `<div class="stat-cards">
    <div class="stat-card accent"><div class="label">进件单总数</div><div class="value">${apps.length}</div></div>
    <div class="stat-card"><div class="label">待审 / 待补件</div><div class="value">${pending.length}</div></div>
    <div class="stat-card ok"><div class="label">已通过</div><div class="value">${cnt('APPROVED')}</div></div>
    <div class="stat-card bad"><div class="label">已驳回</div><div class="value">${cnt('REJECTED')}</div></div>
    <div class="stat-card ${slow.length ? 'bad' : ''}"><div class="label">超 1 小时未闭环</div><div class="value">${slow.length}</div></div>
    <div class="stat-card"><div class="label">平均审核时长</div><div class="value">${avg ? (avg / 1000).toFixed(1) + ' s' : '—'}</div></div>
  </div>${slow.length ? `<div class="box warn">超时未闭环进件单：${slow.slice(0, 10).map(a => ddLink('mapp', a.id, a.id)).join(' ')}</div>` : ''}`;
}
function ppObAppRows(rows) {
  const t = document.getElementById('obAppTable'); if (!t) return;
  const canR = hasPerm('merchant:review') && hasPerm('member:write');
  t.innerHTML = `<tr><th>进件单</th><th>商户名</th><th>执照（脱敏）</th><th>法人</th><th>联系方式</th>
    <th>状态</th><th>会员 / appId</th><th>提交人</th><th>提交 / 审核时间</th><th>操作</th></tr>` +
    (rows.length ? rows.map(a => `<tr><td>${ddLink('mapp', a.id, a.id)}</td><td>${esc(a.merchantName || '—')}</td>
      <td class="pp-mono">${esc(a.licenseNoMask || '—')}</td><td>${esc(a.legalPersonMask || '—')}</td>
      <td class="pp-mono">${esc(a.contactMask || '—')}</td>
      <td>${ppBadge(a.status, PP_APP_ST[a.status] || a.status)}</td>
      <td>${ddLink('member', a.memberId, a.memberId || '—')}<br><span class="pp-mono muted">${esc(a.appId || '—')}</span></td>
      <td>${esc(a.submitter || '—')}</td>
      <td>${fmtTime(a.createdAt)}<br><span class="muted">${a.reviewedAt ? fmtTime(a.reviewedAt) : '未审核'}</span></td>
      <td>${canR && (a.status === 'SUBMITTED' || a.status === 'REVIEWING' || a.status === 'SUPPLEMENT')
        ? `<button class="btn small ghost" data-mm-app="${esc(a.id)}">审核</button>` : ''}
        ${a.status === 'REJECTED' ? `<button class="btn small ghost" data-mm-app="${esc(a.id)}">重提</button>` : ''}
        ${a.status === 'APPROVED' ? `<button class="btn small ghost" data-mm-fill="ctApp" data-mm-id="${esc(a.id)}">签约</button>` : ''}
        ${a.memberId ? `<button class="btn small ghost" data-mm-fill="ctMerchant" data-mm-id="${esc(a.memberId)}">结算卡</button>` : ''}</td></tr>`).join('')
      : emptyRow(10, '无匹配进件单'));
}
function ppObReviewRows(rows) {
  const t = document.getElementById('obReviewTable'); if (!t) return;
  t.innerHTML = `<tr><th>任务</th><th>进件单</th><th>审核人</th><th>结论</th><th>意见 / 驳回原因</th><th>创建</th><th>审结</th><th>耗时</th></tr>` +
    (rows.length ? rows.map(r => `<tr><td class="pp-mono">${esc(r.id)}</td><td>${ddLink('mapp', r.applicationId, r.applicationId)}</td>
      <td>${esc(r.reviewer || '—')}</td>
      <td>${ppBadge(r.status === 'APPROVED' ? 'APPROVED' : r.status === 'REJECTED' ? 'REJECTED' : 'SUPPLEMENT', r.status)}</td>
      <td>${esc(r.opinion || '—')}</td><td>${fmtTime(r.createdAt)}</td>
      <td>${r.reviewedAt ? fmtTime(r.reviewedAt) : '—'}</td>
      <td>${r.reviewedAt && r.createdAt ? ((r.reviewedAt - r.createdAt) / 1000).toFixed(1) + ' s' : '—'}</td></tr>`).join('')
      : emptyRow(8, '暂无审核任务'));
}
function ppObFill(id) {
  const a = (PPMember.apps || []).find(x => x.id === id) || {};
  const set = (k, v) => { const e = document.getElementById(k); if (e) e.value = v == null ? '' : v; };
  set('obReviewId', id); set('obResubId', id);
  set('obName', a.merchantName || ''); set('obQual', a.qualification || '');
  const box = document.getElementById('obBox');
  if (box) box.innerHTML = ddBox('info', `已选中进件单 <b>${esc(id)}</b>（${esc(PP_APP_ST[a.status] || a.status || '')}）。
    审核请在「审核处置」区选择通过 / 驳回 / 补件并填写意见；重提请补全脱敏前的真实资料后点「重新提交」。`);
  toast('已选中 ' + id);
}
async function ddMapp(id) {
  const [a, t] = await Promise.all([
    tryApi('/merchant/applications/' + encodeURIComponent(id)), tryApi('/merchant/review') ]);
  if (!a.ok) return ddBox('danger', '进件单加载失败：' + esc(a.err.message));
  const d = a.data || {};
  const tasks = (t.ok ? (t.data || []) : []).filter(x => x.applicationId === id);
  let html = ddKv([['进件单', esc(d.id)], ['商户名', esc(d.merchantName || '—')],
    ['状态', ppBadge(d.status, PP_APP_ST[d.status] || d.status)],
    ['执照（脱敏）', esc(d.licenseNoMask || '—')], ['法人（脱敏）', esc(d.legalPersonMask || '—')],
    ['联系方式（脱敏）', esc(d.contactMask || '—')], ['驳回原因', esc(d.rejectReason || '—')],
    ['会员', ddLink('member', d.memberId, d.memberId)], ['appId', esc(d.appId || '—')],
    ['提交人', esc(d.submitter || '—')], ['提交 / 审结', `${fmtTime(d.createdAt)} / ${d.reviewedAt ? fmtTime(d.reviewedAt) : '—'}`]]);
  html += ddSec('资质材料', `<div class="pp-code light">${esc(d.qualification || '（未上传）')}</div>`);
  html += ddSec('状态流转', ppFlow(['SUBMITTED','REVIEWING','SUPPLEMENT','APPROVED'], d.status, ['REJECTED']));
  html += ddSec('审核轨迹', ddTable(['任务','审核人','结论','意见','时间'], tasks.map(x =>
    `<tr><td class="pp-mono">${esc(x.id)}</td><td>${esc(x.reviewer || '—')}</td>
      <td>${ppBadge(x.status, x.status)}</td><td>${esc(x.opinion || '—')}</td><td>${fmtTime(x.reviewedAt || x.createdAt)}</td></tr>`).join(''),
    '暂无审核轨迹'), tasks.length + ' 条');
  if (d.appSecret) html += ddSec('一次性密钥（仅本次返回）',
    ddBox('warn', '明文 appSecret 仅在审核通过响应中出现一次，请立即转交商户并妥善保存：<span class="pp-mono">' + esc(d.appSecret) + '</span>'));
  if (hasPerm('merchant:review') && hasPerm('member:write'))
    html += ddSec('运维操作', `<button class="btn small ghost" data-mm-app="${esc(id)}">前往审核处置区</button>`);
  return html;
}
async function ppObSubmit(resub) {
  const id = ppVal('obResubId');
  if (resub && !id) { toast('重提需填写进件单 ID', false); return; }
  const body = { merchantName: ppVal('obName'), licenseNo: ppVal('obLicense'),
    legalPerson: ppVal('obLegal'), contact: ppVal('obContact'), qualification: ppVal('obQual') };
  if (!body.merchantName || !body.licenseNo) { toast('商户名与营业执照号必填', false); return; }
  const html = `<div class="box warn"><b>${resub ? '重新提交进件（敏感操作）' : '提交商户进件（敏感操作）'}</b></div>
    ${ddKv([resub ? ['进件单', esc(id)] : null, ['商户名', esc(body.merchantName)],
      ['执照号', esc(maskMid(body.licenseNo))], ['法人', esc(body.legalPerson || '—')],
      ['联系方式', esc(body.contact ? maskMid(body.contact) : '—')]])}
    <div class="pp-note">仅驳回 / 待补件状态可重提；提交后进入审核队列，审核动作另需 merchant:review 权限。</div>`;
  try {
    const r = await ppSensitive(resub ? '重新提交进件' : '提交商户进件', html,
      '/merchant/applications' + (resub ? '/' + encodeURIComponent(id) + '/resubmit' : ''), 'POST', body, html);
    if (!r) return;
    ppResult('obBox', resub ? '已重新提交' : '进件已提交', r);
    toast(resub ? '已重新提交' : '进件已提交'); await loadOnboarding();
  } catch (e) { ppErrBox('obBox', resub ? '重新提交进件' : '提交进件', e); }
}
async function ppObReview(op) {
  const id = ppVal('obReviewId'), opinion = ppVal('obOpinion');
  if (!id) { toast('请填写进件单 ID', false); return; }
  if (op !== 'approve' && !opinion) { toast(op === 'reject' ? '驳回必须填写原因' : '补件必须填写补件要求', false); return; }
  const a = (PPMember.apps || []).find(x => x.id === id) || {};
  const label = op === 'approve' ? '审核通过（开通商户 + 生成 appId/密钥）' : op === 'reject' ? '审核驳回' : '要求补件';
  const html = `<div class="box ${op === 'approve' ? 'warn' : 'info'}"><b>${esc(label)}</b>（敏感操作，需二次认证）</div>
    ${ddKv([['进件单', esc(id)], ['商户名', esc(a.merchantName || '—')], ['当前状态', esc(PP_APP_ST[a.status] || a.status || '—')],
      [op === 'reject' ? '驳回原因' : '审核意见', esc(opinion || '（无）')]])}
    <div class="pp-note">${op === 'approve'
      ? '通过后创建会员 / appId / appSecret（明文仅本次返回），并可继续签约与绑定结算卡。'
      : op === 'reject' ? '驳回后商户可修改资料重提（resubmit）。' : '补件后状态置 SUPPLEMENT，等待商户重提。'}</div>`;
  const body = op === 'reject' ? { reason: opinion } : { opinion };
  try {
    const r = await ppSensitive(label, html, '/merchant/review/' + encodeURIComponent(id) + '/' + op, 'POST', body, html);
    if (!r) return;
    ppResult('obBox', label + ' 完成', r);
    if (op === 'approve' && r && r.appSecret) {
      const b = document.getElementById('obBox');
      if (b) b.innerHTML += ddBox('warn', '<b>一次性 appSecret（请立即保存并转交商户）</b><br><span class="pp-mono">' + esc(r.appSecret) + '</span>');
    }
    toast(label + ' 已执行'); await loadOnboarding();
  } catch (e) { ppErrBox('obBox', label, e); }
}

/* ===================== 页 3 · 合同与结算卡 ===================== */
async function loadContract() {
  lpLoading('ctContract', 9, '加载签约合同…');
  const mid = ppVal('ctMerchant');
  const box = document.getElementById('ctKnown');
  if (box) ppLoadingBox(box, '统计签约与结算卡…');
  const [c, a] = await Promise.all([
    tryApi('/merchant/contracts' + (mid ? '?merchantId=' + encodeURIComponent(mid) : '')),
    tryApi('/merchant/applications') ]);
  if (a.ok) PPMember.apps = a.data || [];
  if (!c.ok) {
    lpError('ctContract', 9, c.err.message);
    if (box) box.innerHTML = ddBox('danger', '合同列表加载失败：' + esc(c.err.message));
  } else {
    PPMember.contracts = c.data || [];
    lpSet('ctContract', PPMember.contracts);
    if (box) {
      const list = PPMember.contracts;
      const custom = list.filter(x => x.feeBps != null);
      const bps = custom.map(x => Number(x.feeBps));
      const approved = (PPMember.apps || []).filter(x => x.status === 'APPROVED');
      const unsigned = approved.filter(x => !list.some(k => k.applicationId === x.id));
      box.innerHTML = `<div class="stat-cards">
        <div class="stat-card accent"><div class="label">签约合同${mid ? '（该商户）' : ''}</div><div class="value">${list.length}</div></div>
        <div class="stat-card ok"><div class="label">已约定费率</div><div class="value">${custom.length}</div></div>
        <div class="stat-card"><div class="label">回落缺省费率</div><div class="value">${list.length - custom.length}</div></div>
        <div class="stat-card"><div class="label">费率区间（bp）</div><div class="value">${bps.length ? Math.min(...bps) + ' ~ ' + Math.max(...bps) : '—'}</div></div>
        <div class="stat-card ${unsigned.length ? 'bad' : ''}"><div class="label">已通过未签约</div><div class="value">${unsigned.length}</div></div>
      </div>${unsigned.length ? `<div class="box warn">已通过进件但未签约：${unsigned.slice(0, 10).map(x =>
        `<button class="btn small ghost" data-mm-fill="ctApp" data-mm-id="${esc(x.id)}">${esc(x.id)} ${esc(x.merchantName || '')}</button>`).join(' ')}</div>` : ''}
      <div class="hint">数据源 GET /api/merchant/contracts（合同实体：products / feeBps / status / operator / signedAt）；
        费率影响清结算计费，签约与改费率均为敏感写（POST /api/merchant/contracts，一单一约幂等）。</div>`;
    }
  }
  if (mid) await ppCtCards(); else lpSet('ctCard', []);
}
function ppCtContractRows(rows) {
  const t = document.getElementById('ctContractTable'); if (!t) return;
  t.innerHTML = `<tr><th>合同</th><th>进件单</th><th>商户会员</th><th>签约产品</th><th class="num">费率 bp</th>
    <th>费率</th><th>状态</th><th>操作人</th><th>签约时间</th><th>操作</th></tr>` +
    (rows.length ? rows.map(c => `<tr><td>${ddLink('mcontract', c.id, c.id)}</td>
      <td>${ddLink('mapp', c.applicationId, c.applicationId)}</td>
      <td>${ddLink('member', c.merchantId, c.merchantId || '—')}</td>
      <td>${esc(c.products || '—')}</td><td class="num">${ppNum(c.feeBps)}</td>
      <td>${c.feeBps == null ? '<span class="muted">缺省</span>' : (Number(c.feeBps) / 100).toFixed(2) + '%'}</td>
      <td>${ppBadge(c.status === 'SIGNED' ? 'APPROVED' : 'kind', c.status || '—')}</td><td>${esc(c.operator || '—')}</td>
      <td>${c.signedAt ? fmtTime(c.signedAt) : '—'}</td>
      <td><button class="btn small ghost" data-mm-fill="ctApp" data-mm-id="${esc(c.applicationId)}">改费率</button>
        <button class="btn small ghost" data-mm-fill="ctCardApp" data-mm-id="${esc(c.applicationId)}">绑结算卡</button>
        ${c.merchantId ? `<button class="btn small ghost" data-mm-fill="ctMerchant" data-mm-id="${esc(c.merchantId)}">查结算卡</button>` : ''}</td></tr>`).join('')
      : emptyRow(10, '暂无签约合同（签约前置：进件单已 APPROVED）'));
}
function ppCtCardRows(rows) {
  const t = document.getElementById('ctCardTable'); if (!t) return;
  t.innerHTML = `<tr><th>结算卡</th><th>商户</th><th>卡号（脱敏）</th><th>开户行</th><th>状态</th><th>绑定时间</th><th>详情</th></tr>` +
    (rows.length ? rows.map(c => `<tr><td class="pp-mono">${esc(c.id)}</td>
      <td>${ddLink('member', c.merchantId, c.merchantId)}</td><td class="pp-mono">${esc(c.cardNoMask || '—')}</td>
      <td>${esc(c.bankName || '—')}</td><td>${ppBadge(c.status || 'BOUND', c.status || 'BOUND')}</td>
      <td>${fmtTime(c.createdAt)}</td><td>${ddLink('settlecard', c.id, '查看')}</td></tr>`).join('')
      : emptyRow(7, '该商户暂无结算卡（请在上方绑定）'));
}
async function ddSettleCard(id) {
  const c = (PPMember.settleCards || []).find(x => x.id === id);
  if (!c) return ddBox('warn', '结算卡快照不在当前查询结果中，请先在「合同与结算卡」页按商户 ID 查询。');
  return ddKv([['结算卡', esc(c.id)], ['商户', ddLink('member', c.merchantId, c.merchantId)],
    ['卡号（脱敏）', esc(c.cardNoMask || '—')], ['开户行', esc(c.bankName || '—')],
    ['状态', ppBadge(c.status || 'BOUND', c.status || 'BOUND')], ['绑定时间', fmtTime(c.createdAt)]]) +
    ddBox('plain', '结算卡用于清结算打款；卡号后端仅返回掩码，变更需重新绑定并留痕。');
}
async function ddMcontract(id) {
  const r = await tryApi('/merchant/contracts/' + encodeURIComponent(id));
  if (!r.ok) return ddBox('danger', '合同加载失败：' + esc(r.err.message));
  const c = r.data || {};
  const [a, sc] = await Promise.all([
    c.applicationId ? tryApi('/merchant/applications/' + encodeURIComponent(c.applicationId)) : Promise.resolve({ ok:false }),
    c.merchantId ? tryApi('/merchant/settle-cards?merchantId=' + encodeURIComponent(c.merchantId)) : Promise.resolve({ ok:false }) ]);
  const cards = sc.ok ? (sc.data || []) : [];
  ppMergeSettleCards(cards);
  const d = a.ok ? (a.data || {}) : {};
  let html = ddKv([['合同', `<b>${esc(c.id)}</b>`], ['状态', ppBadge(c.status === 'SIGNED' ? 'APPROVED' : 'kind', c.status || '—')],
    ['商户', ddLink('member', c.merchantId, c.merchantId)], ['进件单', ddLink('mapp', c.applicationId, c.applicationId)],
    ['商户名', esc(d.merchantName || '—')], ['appId', esc(d.appId || '—')],
    ['签约产品', esc(c.products || '—')],
    ['签约费率', c.feeBps == null ? '回落缺省（payment.fee-bps）' : c.feeBps + ' bp = ' + (Number(c.feeBps) / 100).toFixed(2) + '%'],
    ['操作人', esc(c.operator || '—')], ['签约时间', c.signedAt ? fmtTime(c.signedAt) : '—']]);
  html += ddSec('签约产品范围', (String(c.products || '').split(/[,，\s]+/).filter(Boolean).map(p =>
    `<span class="pp-tag on">${esc(p)}</span>`).join(' ') || ddBox('plain', '未记录产品范围')));
  html += ddSec('关联进件与审核', a.ok ? ddKv([['进件状态', ppBadge(d.status, PP_APP_ST[d.status] || d.status)],
    ['执照（脱敏）', esc(d.licenseNoMask || '—')], ['法人（脱敏）', esc(d.legalPersonMask || '—')],
    ['提交人', esc(d.submitter || '—')], ['提交 / 审结', `${fmtTime(d.createdAt)} / ${d.reviewedAt ? fmtTime(d.reviewedAt) : '—'}`]])
    : ddBox('warn', '关联进件单不可读（无权限或已删除）'));
  html += ddSec('结算卡（清结算打款目标）', ddTable(['结算卡','卡号（脱敏）','开户行','状态','绑定时间'],
    cards.map(x => `<tr><td>${ddLink('settlecard', x.id, x.id)}</td><td class="pp-mono">${esc(x.cardNoMask || '—')}</td>
      <td>${esc(x.bankName || '—')}</td><td>${ppBadge(x.status || 'BOUND', x.status || 'BOUND')}</td>
      <td>${fmtTime(x.createdAt)}</td></tr>`).join(''), '该商户未绑定结算卡'), cards.length + ' 张');
  if (hasPerm('merchant:write'))
    html += ddSec('运维操作', `<div class="pp-inline">
      <button class="btn small ghost" data-mm-fill="ctApp" data-mm-id="${esc(c.applicationId)}">改费率 / 重签</button>
      <button class="btn small ghost" data-mm-fill="ctCardApp" data-mm-id="${esc(c.applicationId)}">绑定新结算卡</button>
      <button class="btn small ghost" data-pp-goto="settle-fee">费率规则（清结算）</button></div>
      <div class="pp-note">一单一约幂等：同进件单重复签约返回既有合同；费率变更需重新签约并留痕。</div>`);
  return html;
}
/** 结算卡快照并集（抽屉按 id 查快照，跨页/跨商户查询后仍可读） */
function ppMergeSettleCards(list) {
  const map = new Map((PPMember.settleCards || []).map(x => [x.id, x]));
  (list || []).forEach(x => map.set(x.id, x));
  PPMember.settleCards = [...map.values()];
}
async function ppCtSign() {
  const id = ppVal('ctAppId'), products = ppVal('ctProducts'), bps = ppVal('ctFeeBps');
  if (!id || !products) { toast('进件单 ID 与签约产品必填', false); return; }
  const feeBps = bps === '' ? null : Number(bps);
  if (feeBps != null && (!isFinite(feeBps) || feeBps < 0 || feeBps > 10000)) { toast('费率 bp 需在 0~10000', false); return; }
  const html = `<div class="box warn"><b>商户签约（敏感操作）</b>：确立产品范围与结算费率。</div>
    ${ddKv([['进件单', esc(id)], ['签约产品', esc(products)],
      ['费率', feeBps == null ? '回落缺省费率' : feeBps + ' bp = ' + (feeBps / 100).toFixed(2) + '%']])}
    <div class="pp-note">要求进件单已 APPROVED；费率影响后续清结算计费，修改需留痕。</div>`;
  try {
    const r = await ppSensitive('商户签约', html, '/merchant/contracts', 'POST', { applicationId: id, products, feeBps }, html);
    if (!r) return;
    ppResult('ctBox', '签约完成', r); toast('签约完成'); await loadContract();
  } catch (e) { ppErrBox('ctBox', '商户签约', e); }
}
async function ppCtBind() {
  const id = ppVal('ctCardApp'), cardNo = ppVal('ctCardNo'), bankName = ppVal('ctBankName');
  if (!id || !cardNo) { toast('进件单 ID 与结算卡号必填', false); return; }
  const html = `<div class="box warn"><b>绑定结算卡（敏感操作）</b>：清结算打款目标账户。</div>
    ${ddKv([['进件单', esc(id)], ['卡号', esc(maskMid(cardNo))], ['开户行', esc(bankName || '—')]])}
    <div class="pp-note">卡号明文仅用于本次绑定请求；后端仅存掩码。绑定后结算打款指向该卡。</div>`;
  try {
    const r = await ppSensitive('绑定结算卡', html, '/merchant/settle-cards', 'POST', { applicationId: id, cardNo, bankName }, html);
    if (!r) return;
    ppResult('ctBox', '结算卡已绑定', r); toast('结算卡已绑定');
    const e = document.getElementById('ctCardNo'); if (e) e.value = '';
    if (r && r.merchantId) { const m = document.getElementById('ctMerchant'); if (m) m.value = r.merchantId; await ppCtCards(); }
  } catch (e) { ppErrBox('ctBox', '绑定结算卡', e); }
}
async function ppCtCards() {
  const mid = ppVal('ctMerchant');
  if (!mid) { toast('请输入商户会员 ID', false); return; }
  lpLoading('ctCard', 7, '加载结算卡…');
  const r = await tryApi('/merchant/settle-cards?merchantId=' + encodeURIComponent(mid));
  if (!r.ok) { lpError('ctCard', 7, r.err.message); return; }
  ppMergeSettleCards(r.data || []);
  lpSet('ctCard', r.data || []);
}

/* ===================== 页 4 · 绑卡管理 ===================== */
async function loadBindCard() {
  lpLoading('bcAccount', 8, '加载账户…');
  const r = await tryApi('/accounts');
  if (!r.ok) lpError('bcAccount', 8, r.err.message);
  else { PPMember.accounts = r.data || []; lpSet('bcAccount', PPMember.accounts); }
  const id = ppVal('bcAccount');
  if (id) await ppBcQuery(); else lpSet('bcCard', []);
}
function ppBcAccountRows(rows) {
  const t = document.getElementById('bcAccountTable'); if (!t) return;
  t.innerHTML = `<tr><th>账户</th><th>会员</th><th>账户类型</th><th>币种</th><th class="num">余额（元）</th>
    <th>等级</th><th>状态</th><th>操作</th></tr>` +
    (rows.length ? rows.map(a => `<tr><td>${ddLink('account', a.id, a.id)}</td>
      <td>${ddLink('member', a.memberId, a.memberId)}<br><span class="muted">${esc(a.memberName || '')}</span></td>
      <td>${ppBadge('kind', a.accountType || 'BALANCE')}</td><td>${esc(a.currency || 'CNY')}</td>
      <td class="num">${ppFen(a.balance)}</td><td>${a.level ? ppBadge(a.level, a.level) : '—'}</td>
      <td>${ppBadge(a.status, a.status)}</td>
      <td><button class="btn small ghost" data-mm-card="${esc(a.id)}">查绑卡</button></td></tr>`).join('')
      : emptyRow(8, '无匹配账户'));
}
function ppBcCardRows(rows) {
  const t = document.getElementById('bcCardTable'); if (!t) return;
  const canW = hasPerm('account:write');
  t.innerHTML = `<tr><th>卡 ID</th><th>账户</th><th>卡号（脱敏）</th><th>银行</th><th>手机（脱敏）</th>
    <th>状态</th><th>默认</th><th>签约号</th><th>绑定 / 解绑</th><th>操作</th></tr>` +
    (rows.length ? rows.map(c => `<tr><td class="pp-mono">${esc(c.id)}</td>
      <td>${ddLink('account', c.accountId, c.accountId)}</td><td class="pp-mono">${esc(c.cardNoMask || '—')}</td>
      <td>${esc(c.bankName || '—')}</td><td class="pp-mono">${esc(c.mobileMask || '—')}</td>
      <td>${ppBadge(c.status || 'BOUND', c.status || 'BOUND')}</td>
      <td>${c.isDefault ? ppTag('on', '默认') : '<span class="muted">—</span>'}</td>
      <td class="pp-mono">${esc(c.signNo || '—')}</td>
      <td>${fmtTime(c.createdAt)}<br><span class="muted">${c.unboundAt ? fmtTime(c.unboundAt) : '—'}</span></td>
      <td>${canW && (c.status || 'BOUND') === 'BOUND' ? `
        ${c.isDefault ? '' : `<button class="btn small ghost" data-bc-default="${esc(c.id)}">设为默认</button>`}
        <button class="btn small danger" data-bc-unbind="${esc(c.id)}">解绑</button>` : '<span class="muted">—</span>'}
        ${ddLink('bankcard', c.id, '详情')}</td></tr>`).join('')
      : emptyRow(10, '该账户暂无绑卡记录'));
}
async function ddBankCard(id) {
  const c = (PPMember.cards || []).find(x => x.id === id);
  if (!c) return ddBox('warn', '绑卡快照不在当前查询结果中，请先按账户 ID 查询绑卡列表。');
  return ddKv([['卡 ID', esc(c.id)], ['账户', ddLink('account', c.accountId, c.accountId)],
    ['卡号（脱敏）', esc(c.cardNoMask || '—')], ['银行', esc(c.bankName || '—')],
    ['手机（脱敏）', esc(c.mobileMask || '—')], ['状态', ppBadge(c.status || 'BOUND', c.status || 'BOUND')],
    ['默认卡', c.isDefault ? ppTag('on', '是') : ppTag('off', '否')], ['签约号', esc(c.signNo || '—')],
    ['绑定 / 解绑', `${fmtTime(c.createdAt)} / ${c.unboundAt ? fmtTime(c.unboundAt) : '—'}`]]) +
    ddBox('plain', '卡 BIN 与限额由渠道侧维护（见「渠道 · 限额与维护窗」）；风险卡处置走「风控 · 名单管理」黑名单。');
}
async function ppBcQuery() {
  const id = ppVal('bcAccount');
  if (!id) { toast('请输入账户 ID', false); return; }
  lpLoading('bcCard', 10, '加载绑卡…');
  const r = await tryApi('/accounts/' + encodeURIComponent(id) + '/cards');
  if (!r.ok) { lpError('bcCard', 10, r.err.message); return; }
  PPMember.cards = r.data || [];
  lpSet('bcCard', PPMember.cards);
  const box = document.getElementById('bcSum');
  if (box) {
    const bound = PPMember.cards.filter(c => (c.status || 'BOUND') === 'BOUND');
    box.innerHTML = ddBox('info', `账户 <b>${esc(id)}</b>：绑卡 ${PPMember.cards.length} 张，生效 ${bound.length} 张，默认卡 ${bound.filter(c => c.isDefault).map(c => esc(c.cardNoMask)).join(',') || '未设置'}。`);
  }
}
async function ppBcBind() {
  const id = ppVal('bcAccount');
  if (!id) { toast('请先填写账户 ID', false); return; }
  const body = { cardNo: ppVal('bcCardNo'), mobile: ppVal('bcMobile'), name: ppVal('bcName'),
    idCardNo: ppVal('bcIdCard'), bankName: ppVal('bcBank') };
  if (!body.cardNo) { toast('卡号必填', false); return; }
  const html = `<div class="box warn"><b>绑定银行卡（敏感操作）</b>：四要素核验后绑卡。</div>
    ${ddKv([['账户', esc(id)], ['卡号', esc(maskMid(body.cardNo))], ['持卡人', esc(body.name || '—')],
      ['证件', esc(body.idCardNo ? maskMid(body.idCardNo) : '—')], ['手机', esc(body.mobile ? maskMid(body.mobile) : '—')],
      ['银行', esc(body.bankName || '—')]])}
    <div class="pp-note">明文仅用于本次核验；后端仅存掩码。首张卡自动置默认。</div>`;
  try {
    const r = await ppSensitive('绑定银行卡', html, '/accounts/' + encodeURIComponent(id) + '/cards', 'POST', body, html);
    if (!r) return;
    ppResult('bcBox', '绑卡完成', r); toast('绑卡完成');
    ['#bcCardNo','#bcIdCard'].forEach(s => { const e = document.querySelector(s); if (e) e.value = ''; });
    await ppBcQuery();
  } catch (e) { ppErrBox('bcBox', '绑定银行卡', e); }
}
async function ppBcOp(op, cardId) {
  const c = (PPMember.cards || []).find(x => x.id === cardId) || {};
  const aid = ppVal('bcAccount') || c.accountId;
  try {
    if (op === 'unbind') {
      const html = `<div class="box danger"><b>解绑银行卡（敏感操作）</b>：解绑后该卡不可再用于支付 / 代扣。</div>
        ${ddKv([['卡 ID', esc(cardId)], ['账户', esc(aid || '—')], ['卡号', esc(c.cardNoMask || '—')],
          ['默认卡', c.isDefault ? '是（解绑后需另设默认）' : '否'], ['签约号', esc(c.signNo || '—')]])}
        <div class="pp-note">存在生效代扣协议时请先解约（渠道 · 配置与适配器）；解绑留痕不可撤销。</div>`;
      const r = await ppSensitive('解绑银行卡', html, '/cards/' + encodeURIComponent(cardId) + '/unbind', 'POST', {}, html);
      if (r) { ppResult('bcBox', '已解绑', r); toast('已解绑'); await ppBcQuery(); }
      return;
    }
    const html = `<div class="box warn"><b>设为默认卡（敏感操作）</b>：影响后续默认支付 / 提现目标。</div>
      ${ddKv([['账户', esc(aid || '—')], ['卡 ID', esc(cardId)], ['卡号', esc(c.cardNoMask || '—')]])}`;
    const r = await ppSensitive('设为默认卡', html,
      '/accounts/' + encodeURIComponent(aid) + '/cards/' + encodeURIComponent(cardId) + '/default', 'POST', {}, html);
    if (r) { ppResult('bcBox', '默认卡已更新', r); toast('默认卡已更新'); await ppBcQuery(); }
  } catch (e) { ppErrBox('bcBox', '绑卡操作', e); }
}

/* ===================== 页 5 · 分级限额 ===================== */
async function loadMemberTier() {
  const id = ppVal('mtAccount');
  const box = document.getElementById('mtMatrix');
  if (box) ppLoadingBox(box, '加载等级限额矩阵…');
  if (id) await ppMtLevel();
  else { lpSet('mtFreeze', []); if (box) box.innerHTML = ddBox('plain', '输入账户 ID 后查询该账户等级与限额；下方为平台等级口径说明。'); }
}
function ppMtLevelHtml(d) {
  return ddKv([['账户', ddLink('account', d.accountId, d.accountId)], ['当前等级', ppBadge(d.level, d.level + ' 级')],
      ['单笔限额（元）', ppFen(d.singleLimit)], ['日累计限额（元）', ppFen(d.dailyLimit)],
      ['余额上限（元）', ppFen(d.balanceLimit)], ['KYC 要求', esc(d.requireKyc || 'NONE')],
      ['已达 KYC 等级', d.kycLevel ? ppBadge(d.kycLevel, d.kycLevel) : '—']]) +
    `<div class="pp-grid kv2" style="margin-top:8px">
      <div>${ppMeter(0, d.singleLimit)}<div class="pp-note">单笔限额刻度</div></div>
      <div>${ppMeter(0, d.dailyLimit)}<div class="pp-note">日累计限额刻度</div></div>
    </div>
    <div class="pp-obs">升级需满足目标等级的 KYC 要求（requireKyc）；未实名账户升级会被拒绝。
      降级 / 销户走「账户」页生命周期护栏（五重校验 + fail-closed）。</div>`;
}
async function ddAcLevel(id) {
  const [l, f] = await Promise.all([
    tryApi('/accounts/' + encodeURIComponent(id) + '/level'),
    tryApi('/accounts/' + encodeURIComponent(id) + '/freezes') ]);
  if (!l.ok) return ddBox('danger', '等级加载失败：' + esc(l.err.message));
  let html = ppMtLevelHtml(l.data || {});
  const fs = f.ok ? (f.data || []) : [];
  html += ddSec('冻结单', ddTable(['冻结单','金额（元）','原因','状态','操作人','创建'], fs.map(x =>
    `<tr><td>${ddLink('freeze', x.id, x.id)}</td><td class="num">${ppFen(x.amount)}</td>
      <td>${esc(x.reasonType || '—')}</td><td>${ppBadge(x.status, x.status)}</td>
      <td>${esc(x.operator || '—')}</td><td>${fmtTime(x.createdAt)}</td></tr>`).join(''), '无冻结单'), fs.length + ' 条');
  return html;
}
async function ppMtLevel() {
  const id = ppVal('mtAccount');
  if (!id) { toast('请输入账户 ID', false); return; }
  const box = document.getElementById('mtMatrix');
  ppLoadingBox(box, '加载等级与限额…');
  const [l, f] = await Promise.all([
    tryApi('/accounts/' + encodeURIComponent(id) + '/level'),
    tryApi('/accounts/' + encodeURIComponent(id) + '/freezes') ]);
  if (!l.ok) { box.innerHTML = ddBox('danger', '等级加载失败：' + esc(l.err.message)); }
  else { PPMember.levels[id] = l.data; box.innerHTML = ppMtLevelHtml(l.data || {}); }
  if (!f.ok) lpError('mtFreeze', 9, f.err.message);
  else { PPMember.freezes = f.data || []; lpSet('mtFreeze', PPMember.freezes); }
}
async function ppMtUpgrade() {
  const id = ppVal('mtAccount'), target = ppVal('mtTarget');
  if (!id || !target) { toast('账户 ID 与目标等级必填', false); return; }
  const cur = PPMember.levels[id] || {};
  const html = `<div class="box warn"><b>账户等级升级（敏感操作）</b>：限额随等级放大，影响资金风险敞口。</div>
    ${ddKv([['账户', esc(id)], ['当前等级', esc(cur.level || '未知')], ['目标等级', esc(target)],
      ['目标 KYC 要求', esc(cur.requireKyc || '（查询后确认）')], ['已达 KYC 等级', esc(cur.kycLevel || '无')]])}
    <div class="pp-note">后端强校验 KYC 前置：未实名 / KYC 等级不足会被拒绝（B0001），不会放宽限额。</div>`;
  try {
    const r = await ppSensitive('账户等级升级', html, '/accounts/' + encodeURIComponent(id) + '/level/upgrade',
      'POST', { targetLevel: target }, html);
    if (!r) return;
    ppResult('mtBox', '等级升级完成', r); toast('等级已升级'); await ppMtLevel();
  } catch (e) { ppErrBox('mtBox', '等级升级', e); }
}
async function ppMtCheck() {
  const id = ppVal('mtAccount');
  if (!id) { toast('请输入账户 ID', false); return; }
  let amount;
  try { amount = toFen(ppVal('mtAmount')); } catch (e) { toast('金额非法：' + e.message, false); return; }
  const kind = ppVal('mtKind') || 'SINGLE';
  const box = document.getElementById('mtCheckBox');
  ppLoadingBox(box, '限额试算（只读，不动账、不需二次认证）…');
  const r = await tryApi('/accounts/' + encodeURIComponent(id) + '/limit-check?amount=' + amount + '&kind=' + encodeURIComponent(kind));
  if (!r.ok) { box.innerHTML = ddBox('danger', '试算失败：' + esc(r.err.message)); return; }
  const d = r.data || {};
  box.innerHTML = ddBox(d.pass ? 'info' : 'warn',
    `<b>${d.pass ? '限额校验通过' : '限额校验不通过'}</b>（只读试算，未动账）`) +
    ddKv([['账户', esc(id)], ['等级', ppBadge(d.level, d.level || '—')], ['维度', esc(d.kind || kind)],
      ['限额（元）', ppFen(d.limit)], ['试算金额（元）', ppFen(d.amount)], ['拒绝原因', esc(d.reason || '—')]]) +
    ppMeter(d.amount, d.limit);
}
function ppMtFreezeRows(rows) {
  const t = document.getElementById('mtFreezeTable'); if (!t) return;
  const canW = hasPerm('account:write');
  t.innerHTML = `<tr><th>冻结单</th><th>账户</th><th class="num">金额（元）</th><th>原因类型</th><th>说明</th>
    <th>状态</th><th>操作人 / 授权人</th><th>业务号</th><th>创建 / 解除</th><th>操作</th></tr>` +
    (rows.length ? rows.map(f => `<tr><td>${ddLink('freeze', f.id, f.id)}</td>
      <td>${ddLink('account', f.accountId, f.accountId)}</td><td class="num">${ppFen(f.amount)}</td>
      <td>${ppBadge(f.reasonType === 'JUDICIAL' ? 'FROZEN_JUDICIAL' : 'kind', f.reasonType || '—')}</td>
      <td>${esc(f.reason || '—')}</td><td>${ppBadge(f.status, f.status)}</td>
      <td>${esc(f.operator || '—')}<br><span class="muted">${esc(f.authorizedBy || '—')}</span></td>
      <td class="pp-mono">${esc(f.bizNo || '—')}</td>
      <td>${fmtTime(f.createdAt)}<br><span class="muted">${f.releasedAt ? fmtTime(f.releasedAt) : '—'}</span></td>
      <td>${canW && f.status === 'ACTIVE'
        ? `<button class="btn small ghost" data-mm-fill="unfreeze" data-mm-id="${esc(f.id)}">解冻</button>` : '<span class="muted">—</span>'}</td></tr>`).join('')
      : emptyRow(10, '该账户无冻结单'));
}
async function ddFreeze(id) {
  const f = (PPMember.freezes || []).find(x => x.id === id);
  if (!f) return ddBox('warn', '冻结单快照不在当前查询结果中，请先按账户 ID 查询冻结单列表。');
  return ddKv([['冻结单', esc(f.id)], ['账户', ddLink('account', f.accountId, f.accountId)],
    ['金额（元）', ppFen(f.amount)], ['原因类型', ppBadge('kind', f.reasonType || '—')],
    ['说明', esc(f.reason || '—')], ['状态', ppBadge(f.status, f.status)],
    ['操作人', esc(f.operator || '—')], ['授权人（司法解冻必填）', esc(f.authorizedBy || '—')],
    ['冻结业务号', esc(f.bizNo || '—')], ['解冻业务号', esc(f.releaseBizNo || '—')],
    ['创建 / 解除', `${fmtTime(f.createdAt)} / ${f.releasedAt ? fmtTime(f.releasedAt) : '—'}`]]) +
    ddBox('plain', '金额冻结经账务 TCC Try/Cancel 落地（frozen 列），不产生分录，试算平衡不受影响；' +
      '司法冻结（JUDICIAL）解冻必须 authorizedBy ≠ operator（双人）。');
}
async function ppMtFreeze() {
  const id = ppVal('mtAccount');
  if (!id) { toast('请输入账户 ID', false); return; }
  let amount;
  try { amount = toFen(ppVal('mtFreezeAmount')); } catch (e) { toast('冻结金额非法：' + e.message, false); return; }
  const reasonType = ppVal('mtFreezeReason'), reason = ppVal('mtFreezeNote');
  const bizNo = ppReqId('UI-frz');
  const html = `<div class="box warn"><b>金额冻结（敏感操作）</b>：可用余额减少，不影响试算平衡。</div>
    ${ddKv([['账户', esc(id)], ['冻结金额（元）', ppFen(amount)], ['原因类型', esc(reasonType)],
      ['说明', esc(reason || '—')], ['幂等业务号', esc(bizNo)]])}
    <div class="pp-note">TCC Try 落地 frozen 列；bizNo 幂等，重复提交不重复冻结。</div>`;
  try {
    const r = await ppSensitive('账户金额冻结', html, '/accounts/' + encodeURIComponent(id) + '/freeze', 'POST',
      { amount, reasonType, reason, bizNo }, html);
    if (!r) return;
    ppResult('mtBox', '冻结完成', r); toast('已冻结'); await ppMtLevel();
  } catch (e) { ppErrBox('mtBox', '金额冻结', e); }
}
async function ppMtUnfreeze() {
  const id = ppVal('mtAccount'), fid = ppVal('mtUnfreezeId');
  if (!id || !fid) { toast('账户 ID 与冻结单 ID 必填', false); return; }
  const f = (PPMember.freezes || []).find(x => x.id === fid) || {};
  const reasonType = ppVal('mtUnfreezeReason') || f.reasonType;
  const authBy = ppVal('mtAuthBy');
  if (reasonType === 'JUDICIAL' && !authBy) { toast('司法冻结解冻必须填写授权人（且 ≠ 操作人）', false); return; }
  const html = `<div class="box warn"><b>解冻（敏感操作）</b>：释放冻结额回可用余额。</div>
    ${ddKv([['账户', esc(id)], ['冻结单', esc(fid)], ['金额（元）', ppFen(f.amount)],
      ['原因类型', esc(reasonType || '—')], ['授权人', esc(authBy || '（非司法可空）')],
      ['操作人', esc(me())]])}
    <div class="pp-note">解冻原因必须与冻结原因对应；JUDICIAL 需 authorizedBy ≠ operator（双人护栏）。</div>`;
  try {
    const r = await ppSensitive('账户解冻', html, '/accounts/' + encodeURIComponent(id) + '/unfreeze', 'POST',
      { freezeId: fid, reasonType, authorizedBy: authBy || undefined, bizNo: ppReqId('UI-unfrz') }, html);
    if (!r) return;
    ppResult('mtBox', '解冻完成', r); toast('已解冻'); await ppMtLevel();
  } catch (e) { ppErrBox('mtBox', '解冻', e); }
}
async function ppMtStatusEx() {
  const id = ppVal('mtAccount');
  if (!id) { toast('请输入账户 ID', false); return; }
  const status = ppVal('mtStatus'), freezeType = ppVal('mtFreezeType'), reason = ppVal('mtStatusReason'), authBy = ppVal('mtStatusAuth');
  if (!status) { toast('请选择目标状态', false); return; }
  if (freezeType === 'JUDICIAL' && status === 'ACTIVE' && !authBy) { toast('司法冻结解除必须填写授权人', false); return; }
  const html = `<div class="box danger"><b>账户状态变更（敏感操作）</b>：影响该账户全部收付能力。</div>
    ${ddKv([['账户', esc(id)], ['目标状态', esc(status)], ['冻结类型', esc(freezeType || 'NONE')],
      ['原因', esc(reason || '—')], ['授权人', esc(authBy || '—')], ['操作人', esc(me())]])}
    <div class="pp-note">FULL 全面冻结 / JUDICIAL 司法冻结；JUDICIAL 解除需 authorizedBy ≠ operator。</div>`;
  try {
    const r = await ppSensitive('账户状态变更', html, '/accounts/' + encodeURIComponent(id) + '/status-ex', 'POST',
      { status, freezeType: freezeType || undefined, reason, authorizedBy: authBy || undefined }, html);
    if (!r) return;
    ppResult('mtBox', '状态变更完成', r); toast('状态已变更'); await ppMtLevel();
  } catch (e) { ppErrBox('mtBox', '账户状态变更', e); }
}
async function ppMtTyped() {
  const memberId = ppVal('mtTypedMember'), accountType = ppVal('mtTypedType');
  if (!memberId || !accountType) { toast('会员 ID 与账户类型必填', false); return; }
  const html = `<div class="box warn"><b>开立专用类型账户（敏感操作）</b></div>
    ${ddKv([['会员', esc(memberId)], ['账户类型', esc(accountType)]])}
    <div class="pp-note">担保户 / 预授权户 / 挂账户等专用账户，开立后不可随意销户（走生命周期五重护栏）。</div>`;
  try {
    const r = await ppSensitive('开立类型账户', html, '/accounts/typed', 'POST', { memberId, accountType }, html);
    if (!r) return;
    ppResult('mtBox', '类型账户已开立', r); toast('账户已开立');
  } catch (e) { ppErrBox('mtBox', '开立类型账户', e); }
}

/* ===================== 页 6 · C 端会员认证管理（治理侧观测） ===================== */
async function loadMemberAuth() {
  lpLoading('maAudit', 8, '加载认证留痕…');
  const r = await tryApi('/audit?size=200');
  const box = document.getElementById('maStats');
  if (!r.ok) {
    lpError('maAudit', 8, r.err.message);
    if (box) box.innerHTML = ddBox('warn', '审计留痕不可用（需 audit:read）：' + esc(r.err.message));
    return;
  }
  const all = (r.data && r.data.items) || [];
  PPMember.audit = all.filter(x => String(x.path || '').startsWith('/api/member/'));
  lpSet('maAudit', PPMember.audit);
  const cnt = k => PPMember.audit.filter(x => String(x.path).includes('/api/member/' + k)).length;
  const bad = PPMember.audit.filter(x => String(x.result) !== '200').length;
  if (box) box.innerHTML = `<div class="stat-cards">
    <div class="stat-card accent"><div class="label">认证调用（近 ${all.length} 条留痕）</div><div class="value">${PPMember.audit.length}</div></div>
    <div class="stat-card"><div class="label">注册</div><div class="value">${cnt('register')}</div></div>
    <div class="stat-card"><div class="label">短信码</div><div class="value">${cnt('sms-code')}</div></div>
    <div class="stat-card ok"><div class="label">登录</div><div class="value">${cnt('login')}</div></div>
    <div class="stat-card"><div class="label">改密 / 登出</div><div class="value">${cnt('password')} / ${cnt('logout')}</div></div>
    <div class="stat-card ${bad ? 'bad' : ''}"><div class="label">非 200</div><div class="value">${bad}</div></div>
  </div>`;
}
function ppMaAuditRows(rows) {
  const t = document.getElementById('maAuditTable'); if (!t) return;
  t.innerHTML = `<tr><th>时间</th><th>认证动作</th><th>完整路径</th><th>操作人 / 主体</th><th>HTTP</th><th>来源 IP</th><th>traceId</th><th>归因</th></tr>` +
    (rows.length ? rows.map(a => {
      const p = String(a.path || ''), act = p.replace('/api/member/', '');
      const st = String(a.result || '');
      const why = st === '200' ? '正常' : st === '429' ? '登录失败限流 / Sentinel 拒绝'
        : st === '401' ? '会话失效 / 旧口令错误' : st === '403' ? '权限或风控拒绝'
        : st === '400' ? '参数或业务校验失败' : '其它';
      return `<tr><td>${fmtTime(a.createdAt)}</td><td>${ppBadge('kind', act || '—')}</td>
        <td class="pp-mono">${esc(p)}</td><td>${esc(a.username || '（C 端匿名）')}</td>
        <td>${ppBadge(st === '200' ? 'SUCCESS' : 'FAILED', st || '—')}</td>
        <td class="pp-mono">${esc(a.ip || '—')}</td><td class="pp-mono">${esc(a.traceId || '—')}</td>
        <td>${esc(why)}</td></tr>`;
    }).join('') : emptyRow(8, '近期无 C 端认证调用留痕'));
}
async function ppMaSession() {
  const tk = ppVal('maToken');
  const box = document.getElementById('maSessionBox');
  if (!tk) { toast('请输入 C 端会话 token', false); return; }
  ppLoadingBox(box, '校验会话（只读）…');
  const r = await tryApi('/member/session?token=' + encodeURIComponent(tk));
  if (!r.ok) { box.innerHTML = ddBox('danger', '会话校验失败：' + esc(r.err.message)); return; }
  const d = r.data || {};
  box.innerHTML = ddBox(d.valid ? 'info' : 'warn', `<b>${d.valid ? '会话有效' : '会话无效 / 已过期'}</b>（只读观测，不创建也不销毁会话）`) +
    ddKv([['会员', d.memberId ? ddLink('member', d.memberId, d.memberId) : '—'], ['名称', esc(d.name || '—')],
      ['手机（脱敏）', esc(d.mobileMask || '—')], ['过期时间', d.expiresAt ? fmtTime(d.expiresAt) : '—'],
      ['剩余', d.expiresAt ? Math.max(0, Math.floor((d.expiresAt - Date.now()) / 1000)) + ' s' : '—']]);
  const e = document.getElementById('maToken'); if (e) e.value = '';
}
/* 显式暴露到 window：顶层 const 只进全局词法作用域，不挂 window 会让 if(window.PPMember) 恒 false */
window.PPMember = PPMember;
