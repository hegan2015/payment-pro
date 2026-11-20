/* =====================================================================
 * payment-pro 管理控制台 · 开放平台运营（IA 分组 11）· UI 泳道 B
 * ---------------------------------------------------------------------
 * 5 个实页（全局加载函数 = PPOpen.loadApps / loadSandbox / loadQuota / loadVersion / loadDocs）：
 *  open-apps    GET /api/openplatform/apps|scopes|gate · apps/{appId} · /api/members/{id}/secrets
 *               POST apps（注册）· PUT apps/{appId}（授权/配额）· POST revoke|activate
 *               POST /api/members/{merchantId}/secrets/rotate（生产应用密钥轮换，step-up + 宽限期）
 *  open-sandbox GET /api/platform/sandbox（姿态/隔离/故障注入）· /api/openplatform/sandbox/{appId}/accounts
 *               GET /api/platform/sandbox/{appId}/orders|txns · /sandbox/orders/{appId}/{orderId}
 *               POST /api/platform/faults[/reset]（沙箱混沌注入，step-up）
 *  open-quota   GET /api/openplatform/quota?appId · /bill?appId&bizDate · PUT apps/{appId}
 *  open-version GET /openapi/version · /api/platform/sdk（清单 + SHA-256）· /api/platform/sdk/file?path=
 *  open-docs    GET /v3/api-docs · /api/platform/docs[/{name}]（docs/*.md 原文渲染）
 * ---------------------------------------------------------------------
 * 【自挂载】index.html 无这 5 页 section（泳道 A 只做 script 标签 + nav 布线），
 * 故 ppOaEnsure/ppOsEnsure/ppOqEnsure/ppOvEnsure/ppOdEnsure 在 <main> 内建/填 section#tab-<tab>；
 * 加载函数自身保证 DOM/列表/事件就绪。
 * 顶层只声明；DOM 与全局依赖全部在函数体内。
 * ===================================================================== */

const PP_APP_STATUS = [['ACTIVE', 'ACTIVE 生效'], ['REVOKED', 'REVOKED 已撤销']];

/** 收银台/签约 additive 方法目录兜底（console-trade.js 未加载时本页仍可渲染）。 */
const PP_OPEN_CASHIER = [
  ['cashier.methods', 'cashier:read', '收银台方式编排与排序（业务方自渲染）'],
  ['cashier.code.create', 'cashier:write', '付款码创建（被扫，短时有效）'],
  ['cashier.code.parse', 'cashier:read', '付款码解析（主扫归一后取 token）'],
  ['cashier.code.query', 'cashier:read', '付款码状态回查（ACTIVE→CONSUMED/EXPIRED）'],
  ['cashier.scanPay', 'payment:pay', '主扫受理（幂等 requestId，回查至终态）'],
  ['cashier.verify', 'cashier:write', '验证会话创建（PASSWORD/SMS/BIO/SMALL_AMOUNT_FREE）'],
  ['cashier.payVerified', 'payment:pay', '验证通过后支付（会话绑定，防重放）'],
  ['cashier.payPassword.set', 'cashier:write', '支付口令设置（哈希存储，不回显）'],
  ['cashier.sms.send', 'cashier:write', '短信验证码下发（限频）'],
  ['channel.sign', 'sign:write', '签约代扣协议（协议号 agreementNo）'],
  ['channel.agent.pay', 'payment:pay', '代扣受理（同 requestId 重放返回同一单）']
];
const ppOpenCashier = () => (typeof PP_CASHIER_OPEN !== 'undefined' ? PP_CASHIER_OPEN : PP_OPEN_CASHIER);

const PP_SDK_SIGN = `签名口径（双 SDK 与服务端冻结实现逐字节一致）
1) TreeMap 排序 k=v& 拼接（剔除 sign、跳过 null）
2) HMAC-SHA256 → 小写 hex；sign_type=RSA2 时 SHA256withRSA + Base64
3) timestamp 为「毫秒」，服务端窗口 ±300s
4) nonce 可选，TTL 300s 防重放（重复 nonce → A0409）
5) 布尔值 canonical 渲染为小写 true / false（与 Java String.valueOf 一致）
6) 金额单位统一「分」(long)；account.balance 为裸数字 long（v1 冻结面）`;

/* ---------- 页面骨架 ---------- */

const PP_OA_HTML = `
  <div class="card"><h2>开放平台应用与授权<span class="section-desc">G-4a · appId / scope / 配额 / 计费；appSecret 明文仅注册响应返回一次</span></h2>
    <div class="row" style="margin-bottom:10px">
      <button class="btn" id="btnOaRefresh">刷新应用</button>
      <button class="btn ghost" id="btnOaCsv">导出 CSV</button>
      <button class="btn ghost" data-pp-goto="open-quota">配额与计费</button>
      <button class="btn ghost" data-pp-goto="open-sandbox">沙箱</button>
      <button class="btn ghost" data-pp-goto="member-certs">商户密钥与证书</button>
    </div>
    <div id="oaGate"></div>
    <div class="table-wrap" style="margin-top:10px"><table id="oaAppTable"></table></div>
  </div>
  <div class="card"><h2>注册应用<span class="section-desc">敏感写：二次确认 + X-Step-Up-Token；沙箱生成独立 appId/密钥与 SBXA 账本</span></h2>
    <div class="row">
      <div class="field"><label>应用名称</label><input id="oaName" placeholder="ride-pro 生产接入" style="min-width:200px"></div>
      <div class="field"><label>指定 appId（可空）</label><input id="oaAppId" placeholder="留空由后端生成" style="min-width:160px"></div>
      <div class="field"><label>scope（逗号分隔）</label><input id="oaScopes" placeholder="account:read,trade:write" style="min-width:220px"></div>
      <div class="field"><label>QPS 上限（0=不限）</label><input id="oaRate" type="number" min="0" value="0" style="min-width:120px"></div>
      <div class="field"><label>日配额（0=不限）</label><input id="oaQuota" type="number" min="0" value="0" style="min-width:120px"></div>
      <div class="field"><label>单价（分/次）</label><input id="oaPrice" type="number" min="0" value="0" style="min-width:110px"></div>
      <div class="field"><label>默认版本</label><select id="oaVersion"><option value="v1">v1</option><option value="v2">v2</option></select></div>
      <div class="field"><label>环境</label><label style="font-weight:400"><input type="checkbox" id="oaSandbox"> 沙箱</label></div>
      <button class="btn" id="btnOaRegister" data-write="merchant:write">注册应用</button>
    </div>
    <div id="oaBox" style="margin-top:10px"></div>
  </div>
  <div class="card"><h2>调整授权 / 配额 / 计费<span class="section-desc">留空 = 不改；即时生效（策略缓存 evict）</span></h2>
    <div class="row">
      <div class="field"><label>appId</label><input id="oaEditId" placeholder="appM1 / sbx…" style="min-width:170px"></div>
      <div class="field"><label>名称</label><input id="oaEditName" style="min-width:160px"></div>
      <div class="field"><label>scope</label><input id="oaEditScopes" placeholder="逗号分隔" style="min-width:200px"></div>
      <div class="field"><label>QPS 上限</label><input id="oaEditRate" type="number" min="0" style="min-width:110px"></div>
      <div class="field"><label>日配额</label><input id="oaEditQuota" type="number" min="0" style="min-width:110px"></div>
      <div class="field"><label>单价（分）</label><input id="oaEditPrice" type="number" min="0" style="min-width:110px"></div>
      <div class="field"><label>默认版本</label><select id="oaEditVersion"><option value="">不改</option><option value="v1">v1</option><option value="v2">v2</option></select></div>
      <button class="btn" id="btnOaUpdate" data-write="merchant:write">保存调整</button>
    </div>
    <div class="row" style="margin-top:8px">
      <div class="field"><label>启用 / 禁用目标 appId</label><input id="oaTargetId" placeholder="appM1 / sbx…" style="min-width:170px"></div>
      <button class="btn danger" id="btnOaRevoke" data-write="merchant:write">禁用（撤销授权）</button>
      <button class="btn" id="btnOaActivate" data-write="merchant:write">启用（恢复授权）</button>
    </div>
    <div class="hint">撤销即时生效：策略缓存 + 凭证缓存 + access_token 同步失效，业务方（如 ride-pro）会立即中断，请先与业务方确认。</div>
  </div>
  <div class="card"><h2>密钥轮换<span class="section-desc">生产应用验签用绑定商户的 appSecret；轮换走商户密钥版本机制（宽限期双密钥并存）</span></h2>
    <div class="row">
      <div class="field"><label>appId</label><input id="oaRotAppId" placeholder="appM1125" style="min-width:170px"></div>
      <div class="field"><label>宽限期（秒）</label><input id="oaRotGrace" type="number" min="0" value="86400" style="min-width:130px"></div>
      <button class="btn" id="btnOaRotInfo">查看密钥版本</button>
      <button class="btn danger" id="btnOaRotate" data-write="merchant:write">轮换密钥</button>
    </div>
    <div id="oaSecretBox" style="margin-top:10px"></div>
  </div>
  <div class="card"><h2>API → scope 目录<span class="section-desc">越权调用返回 A0403</span></h2>
    <div class="table-wrap"><table id="oaScopeTable"></table></div></div>`;

const PP_OS_HTML = `
  <div class="card"><h2>沙箱<span class="section-desc">G-4a · 沙箱请求在网关截流，只走 member 域 openapi_sandbox_* 账本，生产资金一行不动</span></h2>
    <div class="row" style="margin-bottom:10px">
      <button class="btn" id="btnOsRefresh">刷新沙箱姿态</button>
      <button class="btn ghost" data-pp-goto="open-apps">应用与授权</button>
      <button class="btn ghost" data-pp-goto="open-version">版本与 SDK</button>
      <button class="btn ghost" data-pp-goto="open-docs">API 文档</button>
    </div>
    <div id="osPosture"></div>
  </div>
  <div class="card"><h2>沙箱应用</h2>
    <div class="table-wrap"><table id="osAppTable"></table></div></div>
  <div class="card"><h2>沙箱账本联查<span class="section-desc">账户（SBXA/SBXM）· 交易单（SBXO）· 资金单/支付单（SBXP）</span></h2>
    <div class="row">
      <div class="field"><label>沙箱 appId</label><input id="osAppId" placeholder="sbx…" style="min-width:220px"></div>
      <div class="field"><label>条数上限</label><input id="osLimit" type="number" min="1" max="200" value="50" style="min-width:110px"></div>
      <button class="btn" id="btnOsAccounts">查账户</button>
      <button class="btn ghost" id="btnOsOrders">查交易单</button>
      <button class="btn ghost" id="btnOsTxns">查资金单</button>
      <button class="btn ghost" id="btnOsAll">三表全查</button>
    </div>
    <div id="osBox" style="margin-top:10px"></div>
    <div class="table-wrap" style="margin-top:10px"><table id="osAccountTable"></table></div>
    <h3 style="margin-top:14px">沙箱交易单</h3>
    <div class="table-wrap"><table id="osOrderTable"></table></div>
    <h3 style="margin-top:14px">沙箱资金单 / 支付单</h3>
    <div class="table-wrap"><table id="osTxnTable"></table></div>
  </div>
  <div class="card"><h2>沙箱故障注入<span class="section-desc">渠道进程内模拟银行靶：回执丢失 / 拒绝 / 延迟 / 金额不符 / 宕机 / 超时；不连真实银行、不改既有业务数据</span></h2>
    <div id="osFaultBox"></div>
    <div class="row" style="margin-top:10px">
      <div class="field"><label>回执丢失</label><label style="font-weight:400"><input type="checkbox" id="osFpReceiptLoss"> 只落终态不推回执</label></div>
      <div class="field"><label>异步拒绝</label><label style="font-weight:400"><input type="checkbox" id="osFpReject"> 终态 FAILED</label></div>
      <div class="field"><label>金额不符</label><label style="font-weight:400"><input type="checkbox" id="osFpMismatch"> 渠道金额 +Δ</label></div>
      <div class="field"><label>宕机 503</label><label style="font-weight:400"><input type="checkbox" id="osFpDown"> 确未受理</label></div>
      <div class="field"><label>延迟(ms)</label><input id="osFpDelay" type="number" min="0" style="min-width:110px"></div>
      <div class="field"><label>超时(ms)</label><input id="osFpTimeout" type="number" min="0" value="0" style="min-width:110px"></div>
      <div class="field"><label>金额差(分)</label><input id="osFpDelta" type="number" value="100" style="min-width:110px"></div>
      <div class="field"><label>作用 bizNo（空=全部）</label><input id="osFpTarget" style="min-width:160px"></div>
      <button class="btn danger" id="btnOsFaultSet" data-write="console:admin">下发注入</button>
      <button class="btn" id="btnOsFaultReset" data-write="console:admin">全部复位</button>
    </div>
    <div id="osFaultOpBox" style="margin-top:10px"></div>
  </div>
  <div class="card"><h2>注册沙箱应用<span class="section-desc">敏感写：生成独立 appId / appSecret 与沙箱账本命名空间</span></h2>
    <div class="row">
      <div class="field"><label>沙箱应用名称</label><input id="osName" placeholder="联调沙箱" style="min-width:200px"></div>
      <div class="field"><label>单价（分/次，0=免费）</label><input id="osPrice" type="number" min="0" value="0" style="min-width:130px"></div>
      <button class="btn" id="btnOsCreate" data-write="merchant:write">注册沙箱应用</button>
    </div>
    <div class="hint">沙箱 → 生产迁移：需在生产环境重新注册应用（sandbox=false）并重新开户，沙箱数据不迁移（后端无迁移端点）。</div>
  </div>`;

const PP_OQ_HTML = `
  <div class="card"><h2>配额与计费<span class="section-desc">G-4a · 计费账本独立于资金账本；超限在受理前拒绝（A0429），不落单、不留悬挂</span></h2>
    <div class="row">
      <div class="field"><label>appId</label><input id="oqAppId" placeholder="appM1 / appM1125 / sbx…" style="min-width:200px"></div>
      <button class="btn" id="btnOqQuery">查配额与实时用量</button>
      <button class="btn ghost" id="btnOqAppM1">appM1 兼容红线</button>
      <button class="btn ghost" data-pp-goto="open-apps">应用与授权</button>
      <button class="btn ghost" data-pp-goto="sentinel">限流熔断</button>
    </div>
    <div id="oqBox" style="margin-top:10px"></div>
  </div>
  <div class="card"><h2>调整配额与单价<span class="section-desc">留空 = 不改；敏感写，即时生效</span></h2>
    <div class="row">
      <div class="field"><label>QPS 上限（0=不限）</label><input id="oqRate" type="number" min="0" style="min-width:130px"></div>
      <div class="field"><label>日配额（0=不限）</label><input id="oqQuota" type="number" min="0" style="min-width:130px"></div>
      <div class="field"><label>单价（分/次，0=免费）</label><input id="oqPrice" type="number" min="0" style="min-width:130px"></div>
      <button class="btn" id="btnOqSave" data-write="merchant:write">保存</button>
    </div>
    <div id="oqOpBox" style="margin-top:10px"></div>
  </div>
  <div class="card"><h2>计费日账单<span class="section-desc">callCount × unitPrice；rejectCount 不计费</span></h2>
    <div class="row">
      <div class="field"><label>appId（可空=全部）</label><input id="oqBillApp" style="min-width:170px"></div>
      <div class="field"><label>会计日（可空=当日）</label><input id="oqBillDate" type="date" style="min-width:150px"></div>
      <button class="btn" id="btnOqBill">查账单</button>
      <button class="btn ghost" id="btnOqCsv">导出 CSV</button>
    </div>
    <div id="oqSum" style="margin-top:10px"></div>
    <div class="table-wrap" style="margin-top:10px"><table id="oqBillTable"></table></div>
  </div>`;

const PP_OV_HTML = `
  <div class="card"><h2>OpenAPI 版本与兼容策略<span class="section-desc">v1 冻结面逐字节不变；新增能力一律 additive；v2 为演进面</span></h2>
    <div class="row" style="margin-bottom:10px">
      <button class="btn" id="btnOvRefresh">刷新版本契约</button>
      <button class="btn ghost" id="btnOvContract">下载版本契约 JSON</button>
      <button class="btn ghost" id="btnOvSign">下载签名口径</button>
      <button class="btn ghost" data-pp-goto="open-docs">API 文档</button>
    </div>
    <div id="ovBox"></div>
    <div id="ovNotice" style="margin-top:10px"></div>
  </div>
  <div class="card"><h2>方法契约清单<span class="section-desc">冻结 / additive / v2 演进三面</span></h2>
    <div class="table-wrap"><table id="ovMethodTable"></table></div></div>
  <div class="card"><h2>双 SDK 与防漂移校验<span class="section-desc">源码清单 + SHA-256（跨环境逐字节核对）+ 可直接下载源码</span></h2>
    <div class="row" style="margin-bottom:10px">
      <button class="btn" id="btnOvSdk">刷新 SDK 清单</button>
      <button class="btn ghost" id="btnOvSdkCsv">导出校验值 CSV</button>
    </div>
    <div id="ovSdkBox"></div>
    <div id="ovSdk" style="margin-top:10px"></div>
  </div>`;

const PP_OD_HTML = `
  <div class="card"><h2>API 文档<span class="section-desc">G-2 · springdoc 契约（免鉴权，生成期脱敏）+ 仓库契约文档原文</span></h2>
    <div class="row" style="margin-bottom:10px">
      <button class="btn" id="btnOdRefresh">刷新契约</button>
      <button class="btn ghost" id="btnOdDownload">下载 OpenAPI JSON</button>
      <button class="btn ghost" id="btnOdCsv">导出端点 CSV</button>
      <a class="btn small ghost" href="/swagger-ui.html" target="_blank" rel="noopener">Swagger UI</a>
    </div>
    <div id="odBox"></div>
  </div>
  <div class="card"><h2>端点清单<span class="section-desc">控制台 /api 与开放面 /openapi 分面标注</span></h2>
    <div class="table-wrap"><table id="odPathTable"></table></div></div>
  <div class="card"><h2>收银台 OpenAPI（供业务方自渲染）<span class="section-desc">平台不自建 C 端页面，只提供 OpenAPI + 双 SDK + 契约</span></h2>
    <div id="odCashier"></div>
  </div>
  <div class="card"><h2>契约文档原文<span class="section-desc">docs/*.md（服务端读取，SHA-256 可核对）</span></h2>
    <div class="row">
      <div class="field"><label>文档</label><select id="odDocSel" style="min-width:320px"></select></div>
      <button class="btn" id="btnOdDoc">渲染文档</button>
      <button class="btn ghost" id="btnOdDocDl">下载 markdown</button>
    </div>
    <div id="odDocMeta" style="margin-top:8px"></div>
    <div id="odDocBox" style="margin-top:10px"></div>
  </div>`;

/* ---------- 模块状态 ---------- */
const PPOpen = {
  apps: [], scopes: {}, quota: null, bill: [], sandboxAccounts: [], sandboxOrders: [], sandboxTxns: [],
  version: null, docs: null, gate: null, posture: null, faults: null, sdk: null, docList: [], docText: {},
  bound: {},

  init() {
    ppOaEnsure(); ppOsEnsure(); ppOqEnsure(); ppOvEnsure(); ppOdEnsure();
  },

  /* ---------- 5 个全局加载入口（泳道 A 按此名布线） ---------- */
  loadApps() { ppOaEnsure(); return loadOpenApps(); },
  loadSandbox() { ppOsEnsure(); return loadOpenSandbox(); },
  loadQuota() { ppOqEnsure(); return loadOpenQuota(); },
  loadVersion() { ppOvEnsure(); return loadOpenVersion(); },
  loadDocs() { ppOdEnsure(); return loadOpenDocs(); }
};

/* ---------- 自挂载与列表注册 ---------- */
function ppOxSec(tab, marker, html) {
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
function ppOxLp(key, spec) {
  if (!LP[key]) { if (document.getElementById(spec.table)) lpDef(key, spec); }
  else if (!LP[key].mounted) lpMount(key);
}
function ppOxOn(map) {
  Object.entries(map).forEach(([id, fn]) => { const el = document.getElementById(id); if (el) el.onclick = fn; });
}
function ppOxOnce(key, fn) { if (PPOpen.bound[key]) return; PPOpen.bound[key] = true; fn(); }
/** 跨页联动：填 appId 后跳页并触发查询（全局委托，只绑一次）。 */
function ppOxWireLinks() {
  ppOxOnce('links', () => {
    document.addEventListener('click', ev => {
      const b = ev.target.closest && ev.target.closest('[data-oa-fill],[data-oa-quota],[data-oa-sbx],[data-oa-rot]');
      if (!b) return;
      ev.preventDefault();
      const set = (k, v) => { const e = document.getElementById(k); if (e) e.value = v == null ? '' : v; };
      if (b.dataset.oaFill) {
        set('oaEditId', b.dataset.oaFill); set('oaTargetId', b.dataset.oaFill); set('oaRotAppId', b.dataset.oaFill);
        const a = (PPOpen.apps || []).find(x => x.appId === b.dataset.oaFill) || {};
        set('oaEditScopes', (a.scopes || []).join(',')); set('oaEditRate', a.rateLimit);
        set('oaEditQuota', a.dailyQuota); set('oaEditPrice', a.unitPrice);
        toast('已填入 ' + b.dataset.oaFill);
      } else if (b.dataset.oaQuota) {
        if (window.PPNav) PPNav.go('open-quota');
        setTimeout(() => { set('oqAppId', b.dataset.oaQuota); ppOqQuery(); }, 150);
      } else if (b.dataset.oaSbx) {
        if (window.PPNav) PPNav.go('open-sandbox');
        setTimeout(() => { set('osAppId', b.dataset.oaSbx); ppOsAll(); }, 150);
      } else if (b.dataset.oaRot) {
        set('oaRotAppId', b.dataset.oaRot);
        ppOaSecretInfo();
      }
    });
  });
}

/* ===================== 页 1 · 应用与授权 ===================== */
function ppOaEnsure() {
  ppOxSec('open-apps', 'oaAppTable', PP_OA_HTML);
  DD_KINDS.openapp = '开放平台应用'; DD_RENDER.openapp = ddOpenApp;
  ppOxLp('oaApp', { table: 'oaAppTable', render: ppOaAppRows, filters: [
    { k: 'kw', label: '关键字', ph: 'appId / 名称 / 商户 / 操作人', fields: ['appId', 'name', 'merchantId', 'operator'] },
    { k: 'status', label: '状态', type: 'select', field: 'status', opts: [opt('', '全部')].concat(PP_APP_STATUS) },
    { k: 'sandbox', label: '环境', type: 'select', opts: [opt('', '全部'), opt('true', '沙箱'), opt('false', '生产')],
      test: (r, v) => String(!!r.sandbox) === v },
    { k: 'apiVersion', label: '默认版本', type: 'select', field: 'apiVersion', opts: [opt('', '全部'), opt('v1', 'v1'), opt('v2', 'v2')] },
    ...DATE_FILTERS ] });
  ppOxLp('oaScope', { table: 'oaScopeTable', render: ppOaScopeRows, filters: [
    { k: 'kw', label: '关键字', ph: 'API 方法 / scope', fields: ['0', '1'] } ] });
  ppOxOnce('apps', () => {
    ppOxOn({
      btnOaRefresh: () => loadOpenApps(),
      btnOaRegister: () => ppOaRegister(),
      btnOaUpdate: () => ppOaUpdate(),
      btnOaRevoke: () => ppOaToggle('revoke'),
      btnOaActivate: () => ppOaToggle('activate'),
      btnOaCsv: () => ppOaCsv(),
      btnOaRotInfo: () => ppOaSecretInfo(),
      btnOaRotate: () => ppOaRotate()
    });
    const r = document.getElementById('oaRotAppId');
    if (r) r.onkeydown = ev => { if (ev.key === 'Enter') ppOaSecretInfo(); };
  });
  ppOxWireLinks();
}
async function loadOpenApps() {
  ppOaEnsure();
  lpLoading('oaApp', 12, '加载开放平台应用…');
  const [a, s, g] = await Promise.all([
    tryApi('/openplatform/apps'), tryApi('/openplatform/scopes'), tryApi('/openplatform/gate') ]);
  if (!a.ok) lpError('oaApp', 12, a.err.message);
  else { PPOpen.apps = a.data || []; lpSet('oaApp', PPOpen.apps); }
  if (s.ok) { PPOpen.scopes = s.data || {}; lpSet('oaScope', Object.entries(PPOpen.scopes)); }
  else lpSet('oaScope', []);
  const box = document.getElementById('oaGate');
  if (box) {
    const sandbox = PPOpen.apps.filter(x => x.sandbox).length;
    const revoked = PPOpen.apps.filter(x => x.status === 'REVOKED').length;
    box.innerHTML = `<div class="stat-cards">
        <div class="stat-card accent"><div class="label">应用总数</div><div class="value">${PPOpen.apps.length}</div></div>
        <div class="stat-card"><div class="label">生产 / 沙箱</div><div class="value">${PPOpen.apps.length - sandbox} / ${sandbox}</div></div>
        <div class="stat-card ${revoked ? 'warn' : 'ok'}"><div class="label">已撤销</div><div class="value">${revoked}</div></div>
        <div class="stat-card"><div class="label">scope 目录</div><div class="value">${Object.keys(PPOpen.scopes).length}</div></div>
      </div>` + (g.ok ? ddKv([
        ['未注册 appId 策略', esc(g.data.unregisteredPolicy || '—')],
        ['免检端点', esc((g.data.exempt || []).join(', '))],
        ['计费是否动资金账本', g.data.billingTouchesFundLedger ? ppTag('bad', '是') : ppTag('on', '否（独立计费账本）')],
        ['拒绝时机', g.data.rejectBeforeAcceptance ? ppTag('on', '受理前拒绝（不留悬挂单）') : ppTag('bad', '受理后')],
        ['作用域', esc(g.data.scope || '—')]])
        : ddBox('warn', '闸门策略加载失败：' + esc(g.err.message)));
  }
}
function ppOaAppRows(rows) {
  const t = document.getElementById('oaAppTable'); if (!t) return;
  const canW = hasPerm('merchant:write');
  t.innerHTML = `<tr><th>appId</th><th>名称</th><th>商户</th><th>授权 scope</th><th class="num">QPS 上限</th>
    <th class="num">日配额</th><th class="num">单价（分/次）</th><th>环境</th><th>版本</th><th>状态</th>
    <th>密钥指纹</th><th>操作</th></tr>` +
    (rows.length ? rows.map(a => `<tr><td>${ddLink('openapp', a.appId, a.appId)}</td><td>${esc(a.name || '—')}</td>
      <td>${a.merchantId ? ddLink('member', a.merchantId, a.merchantId) : '—'}</td>
      <td class="pp-mono" style="max-width:230px;word-break:break-all">${esc((a.scopes || []).join(', ') || '—')}</td>
      <td class="num">${Number(a.rateLimit || 0) > 0 ? a.rateLimit : '<span class="muted">不限</span>'}</td>
      <td class="num">${Number(a.dailyQuota || 0) > 0 ? a.dailyQuota : '<span class="muted">不限</span>'}</td>
      <td class="num">${ppNum(a.unitPrice)}</td>
      <td>${a.sandbox ? ppBadge('SANDBOX', '沙箱') : ppBadge('PROD', '生产')}</td>
      <td>${ppBadge('kind', a.apiVersion || 'v1')}</td><td>${ppBadge(a.status, a.status)}</td>
      <td class="pp-mono">${esc(a.secretFingerprint || '—')}</td>
      <td>${canW ? `<button class="btn small ghost" data-oa-fill="${esc(a.appId)}">改授权</button>` : ''}
        <button class="btn small ghost" data-oa-quota="${esc(a.appId)}">用量</button>
        ${a.sandbox ? `<button class="btn small ghost" data-oa-sbx="${esc(a.appId)}">沙箱账本</button>`
          : `<button class="btn small ghost" data-oa-rot="${esc(a.appId)}">密钥</button>`}
        ${ddLink('openapp', a.appId, '详情')}</td></tr>`).join('') : emptyRow(12, '暂无开放平台应用'));
}
function ppOaScopeRows(rows) {
  const t = document.getElementById('oaScopeTable'); if (!t) return;
  t.innerHTML = `<tr><th>OpenAPI 方法</th><th>所需 scope</th><th class="num">已授权应用数</th></tr>` +
    (rows.length ? rows.map(([m, sc]) => {
      const n = (PPOpen.apps || []).filter(a => (a.scopes || []).includes(sc) || (a.scopes || []).includes('*')).length;
      return `<tr><td class="pp-mono">${esc(m)}</td><td class="pp-mono">${esc(sc)}</td><td class="num">${n}</td></tr>`;
    }).join('') : emptyRow(3, '暂无 scope 目录'));
}
function ppOaAppHtml(a, q, bill) {
  let html = ddKv([['appId', esc(a.appId)], ['名称', esc(a.name || '—')],
    ['商户', a.merchantId ? ddLink('member', a.merchantId, a.merchantId) : '—'], ['状态', ppBadge(a.status, a.status)],
    ['环境', a.sandbox ? ppBadge('SANDBOX', '沙箱（独立账本）') : ppBadge('PROD', '生产')],
    ['默认版本', ppBadge('kind', a.apiVersion || 'v1')],
    ['QPS 上限', Number(a.rateLimit || 0) > 0 ? a.rateLimit + ' /s' : '不限'],
    ['日配额', Number(a.dailyQuota || 0) > 0 ? a.dailyQuota + ' 次/日' : '不限'],
    ['单价（分/次）', ppNum(a.unitPrice)], ['密钥指纹', esc(a.secretFingerprint || '—')],
    ['操作人', esc(a.operator || '—')], ['撤销时间', a.revokedAt ? fmtTime(a.revokedAt) : '—'],
    ['创建 / 更新', `${fmtTime(a.createdAt)} / ${fmtTime(a.updatedAt)}`]]);
  html += ddSec('授权 scope', `<div class="pp-code light">${esc((a.scopes || []).join('\n') || '（无）')}</div>`,
    (a.scopes || []).length + ' 项');
  if (q) html += ddSec('当日用量', ddKv([['会计日', ppBizDate(q.bizDate)], ['已用', ppNum(q.usedToday)],
      ['被拒', ppNum(q.rejectedToday)], ['剩余', q.remaining == null ? '不限' : ppNum(q.remaining)],
      ['实时 QPS', ppNum(q.qpsUsed)]]) +
    ppMeter(q.usedToday, q.dailyQuota) + ppMeter(q.qpsUsed, q.rateLimit));
  const bs = (bill || []).filter(b => b.appId === a.appId);
  html += ddSec('计费日账单', ddTable(['API', '会计日', '调用数', '拒绝数', '单价（分）', '计费额（元）'],
    bs.map(b => `<tr><td class="pp-mono">${esc(b.api)}</td><td>${ppBizDate(b.bizDate)}</td>
      <td class="num">${ppNum(b.callCount)}</td><td class="num">${ppNum(b.rejectCount)}</td>
      <td class="num">${ppNum(b.unitPrice)}</td><td class="num">${ppFen(b.billedAmount)}</td></tr>`).join(''),
    '暂无计费记录'), bs.length + ' 条');
  html += ddBox('plain', 'appSecret 明文仅在注册 / 审核通过响应中返回一次，列表与详情只给指纹（不可逆）。' +
    '撤销即时生效：策略缓存 + 凭证缓存 + access_token 同步失效。' +
    (a.sandbox ? '沙箱应用密钥独立于商户密钥，后端暂无沙箱密钥轮换端点：需轮换请撤销后重新注册沙箱应用。'
      : '生产应用验签使用绑定商户的 appSecret，轮换见本页「密钥轮换」（宽限期双密钥并存）。'));
  html += `<div class="pp-inline" style="margin-top:8px">
    <button class="btn small ghost" data-oa-quota="${esc(a.appId)}">配额与计费</button>
    ${a.sandbox ? `<button class="btn small ghost" data-oa-sbx="${esc(a.appId)}">沙箱账本</button>`
      : `<button class="btn small ghost" data-oa-rot="${esc(a.appId)}">密钥版本</button>`}
    <button class="btn small ghost" data-pp-goto="open-docs">API 文档</button></div>`;
  return html;
}
async function ddOpenApp(appId) {
  const [a, q, b] = await Promise.all([
    tryApi('/openplatform/apps/' + encodeURIComponent(appId)),
    tryApi('/openplatform/quota?appId=' + encodeURIComponent(appId)),
    tryApi('/openplatform/bill?appId=' + encodeURIComponent(appId)) ]);
  if (!a.ok) return ddBox('danger', '应用加载失败：' + esc(a.err.message));
  return ppOaAppHtml(a.data || {}, q.ok ? q.data : null, b.ok ? b.data : []);
}
const ppScopeList = s => String(s || '').split(/[\s,，;；]+/).filter(Boolean);
async function ppOaRegister() {
  const name = ppVal('oaName');
  if (!name) { toast('应用名称必填', false); return; }
  const sandbox = ppChecked('oaSandbox');
  const body = { name, appId: ppVal('oaAppId') || undefined, scopes: ppScopeList(ppVal('oaScopes')),
    rateLimit: ppInt('oaRate', 0), dailyQuota: ppInt('oaQuota', 0), unitPrice: ppInt('oaPrice', 0),
    sandbox, apiVersion: ppVal('oaVersion') || 'v1' };
  if (!body.scopes.length) body.scopes = sandbox ? ['*'] : ['account:read'];
  const html = `<div class="box warn"><b>注册开放平台应用（敏感操作）</b></div>
    ${ddKv([['名称', esc(name)], ['环境', sandbox ? '沙箱（独立 appId / 密钥 / SBXA 账本）' : '生产'],
      ['指定 appId', esc(body.appId || '（后端生成）')], ['scope', esc(body.scopes.join(', '))],
      ['QPS 上限', body.rateLimit > 0 ? body.rateLimit + ' /s' : '不限'],
      ['日配额', body.dailyQuota > 0 ? body.dailyQuota + ' 次/日' : '不限'],
      ['单价（分/次）', String(body.unitPrice)], ['默认版本', esc(body.apiVersion)]])}
    <div class="pp-note">appSecret 明文仅本次响应返回，请立即保存；后续列表 / 详情只给指纹。</div>`;
  try {
    const r = await ppSensitive('注册开放平台应用', html, '/openplatform/apps', 'POST', body, html);
    if (!r) return;
    const box = document.getElementById('oaBox');
    ppResult(box, '应用已注册', r);
    if (r && r.appSecret && box) box.innerHTML += ddBox('warn',
      `<b>一次性 appSecret（请立即保存并转交商户，页面不留存）</b><br><span class="pp-mono">${esc(r.appSecret)}</span>
       <br>appId：<span class="pp-mono">${esc(r.appId || '')}</span>`);
    toast('应用已注册'); await loadOpenApps();
  } catch (e) { ppErrBox('oaBox', '注册应用', e); }
}
async function ppOaUpdate() {
  const id = ppVal('oaEditId');
  if (!id) { toast('请填写 appId', false); return; }
  const a = (PPOpen.apps || []).find(x => x.appId === id) || {};
  const body = { name: ppVal('oaEditName') || undefined,
    scopes: ppVal('oaEditScopes') ? ppScopeList(ppVal('oaEditScopes')) : undefined,
    rateLimit: ppVal('oaEditRate') === '' ? undefined : ppInt('oaEditRate', 0),
    dailyQuota: ppVal('oaEditQuota') === '' ? undefined : ppInt('oaEditQuota', 0),
    unitPrice: ppVal('oaEditPrice') === '' ? undefined : ppInt('oaEditPrice', 0),
    apiVersion: ppVal('oaEditVersion') || undefined };
  const changed = Object.entries(body).filter(([, v]) => v !== undefined);
  if (!changed.length) { toast('请至少修改一项（留空 = 不改）', false); return; }
  const html = `<div class="box warn"><b>调整应用授权 / 配额 / 计费（敏感操作）</b>：即时生效（策略缓存 evict）。</div>
    ${ddKv([['appId', esc(id)], ['名称', esc(a.name || '—')]].concat(changed.map(([k, v]) =>
      [k, esc(Array.isArray(v) ? v.join(', ') : String(v))])))}
    <div class="pp-note">null 字段不改；scope 收窄会立即拒绝越权调用（A0403）；配额下调可能立刻触发 A0429。</div>`;
  try {
    const r = await ppSensitive('调整应用授权与配额', html, '/openplatform/apps/' + encodeURIComponent(id), 'PUT', body, html);
    if (!r) return;
    ppResult('oaBox', '应用已更新', r); toast('应用已更新'); await loadOpenApps();
  } catch (e) { ppErrBox('oaBox', '调整应用', e); }
}
async function ppOaToggle(op) {
  const id = ppVal('oaTargetId');
  if (!id) { toast('请填写 appId', false); return; }
  const a = (PPOpen.apps || []).find(x => x.appId === id) || {};
  const revoke = op === 'revoke';
  const html = `<div class="box ${revoke ? 'danger' : 'warn'}"><b>${revoke ? '禁用应用（撤销授权，敏感操作，即时生效）' : '启用应用（恢复授权，敏感操作）'}</b></div>
    ${ddKv([['appId', esc(id)], ['名称', esc(a.name || '—')], ['商户', esc(a.merchantId || '—')],
      ['当前状态', ppBadge(a.status, a.status || '—')], ['环境', a.sandbox ? '沙箱' : '生产'],
      ['授权 scope 数', String((a.scopes || []).length)]])}
    <div class="pp-note">${revoke
      ? '撤销后该 appId 的全部 OpenAPI 调用立即被拒（策略缓存 + 凭证缓存 + access_token 同步失效）；业务方（如 ride-pro）会立即中断，请先与业务方确认。'
      : '恢复后授权与配额回到撤销前配置；access_token 需业务方重新获取。'}</div>`;
  try {
    const r = await ppSensitive(revoke ? '禁用应用（撤销授权）' : '启用应用（恢复授权）', html,
      '/openplatform/apps/' + encodeURIComponent(id) + '/' + op, 'POST', {}, html);
    if (!r) return;
    ppResult('oaBox', revoke ? '授权已撤销' : '授权已恢复', r);
    toast(revoke ? '授权已撤销' : '授权已恢复'); await loadOpenApps();
  } catch (e) { ppErrBox('oaBox', revoke ? '撤销授权' : '恢复授权', e); }
}
/** 密钥版本（生产应用 = 绑定商户的密钥版本链；沙箱应用无版本机制）。 */
async function ppOaSecretInfo() {
  const appId = ppVal('oaRotAppId');
  const box = document.getElementById('oaSecretBox');
  if (!appId) { toast('请填写 appId', false); return; }
  ppLoadingBox(box, '读取密钥状态…');
  let app = (PPOpen.apps || []).find(x => x.appId === appId);
  if (!app) {
    const r = await tryApi('/openplatform/apps/' + encodeURIComponent(appId));
    if (!r.ok) { box.innerHTML = ddBox('danger', '应用查询失败：' + esc(r.err.message)); return; }
    app = r.data || {};
  }
  if (app.sandbox) {
    box.innerHTML = ddKv([['appId', esc(appId)], ['环境', ppBadge('SANDBOX', '沙箱')],
        ['密钥指纹', esc(app.secretFingerprint || '—')], ['状态', ppBadge(app.status, app.status)]]) +
      ddBox('warn', '<b>沙箱密钥无版本/轮换机制</b>（后端未提供沙箱密钥轮换端点）：沙箱 appSecret 在注册时一次性下发并加密留存。' +
        '需更换请<b>撤销该沙箱应用后重新注册</b>（沙箱账本数据保留，appId 变更）。生产应用才有宽限期双密钥轮换。');
    return;
  }
  if (!app.merchantId) {
    box.innerHTML = ddBox('warn', '该生产应用未绑定商户（merchantId 为空），无法定位密钥版本链。');
    return;
  }
  const v = await tryApi('/members/' + encodeURIComponent(app.merchantId) + '/secrets');
  box.innerHTML = ddKv([['appId', esc(appId)], ['环境', ppBadge('PROD', '生产')],
      ['绑定商户', ddLink('member', app.merchantId, app.merchantId)],
      ['应用侧指纹', esc(app.secretFingerprint || '—')],
      ['验签密钥来源', '商户 appSecret（memberFacade.getByAppId）+ 宽限期 graceSecrets']]) +
    (v.ok ? ddSec('商户密钥版本链', ddTable(['版本', '状态', '指纹', '创建时间', '宽限到期', '退役时间'],
        (v.data || []).map(x => `<tr><td class="num">${ppNum(x.version)}</td>
          <td>${ppBadge(x.status, x.status)}</td><td class="pp-mono">${esc(x.fingerprint || '—')}</td>
          <td>${fmtTime(x.createdAt)}</td><td>${x.graceUntil ? fmtTime(x.graceUntil) : '—'}</td>
          <td>${x.retiredAt ? fmtTime(x.retiredAt) : '—'}</td></tr>`).join(''), '无版本记录'),
        (v.data || []).length + ' 个版本')
      : ddBox('warn', '密钥版本查询失败：' + esc(v.err.message))) +
    ddBox('info', '轮换后新密钥立即生效，旧密钥在宽限期内继续可用（graceSecrets），业务方可平滑切换；' +
      '网关凭证缓存同步 evict。');
}
async function ppOaRotate() {
  const appId = ppVal('oaRotAppId');
  if (!appId) { toast('请填写 appId', false); return; }
  let app = (PPOpen.apps || []).find(x => x.appId === appId);
  if (!app) {
    const r = await tryApi('/openplatform/apps/' + encodeURIComponent(appId));
    if (!r.ok) { ppErrBox('oaSecretBox', '应用查询', r.err); return; }
    app = r.data || {};
  }
  if (app.sandbox) { toast('沙箱应用无密钥轮换端点，请撤销后重新注册', false); await ppOaSecretInfo(); return; }
  if (!app.merchantId) { toast('该应用未绑定商户，无法轮换', false); return; }
  const grace = ppInt('oaRotGrace', 86400);
  const html = `<div class="box danger"><b>密钥轮换（敏感操作，需二次认证）</b></div>
    ${ddKv([['appId', esc(appId)], ['绑定商户', esc(app.merchantId)],
      ['宽限期', grace + ' 秒（' + (grace / 3600).toFixed(1) + ' 小时）'],
      ['影响', '新密钥立即生效；旧密钥宽限期内并存，到期后失效']])}
    <div class="pp-note">轮换会改变业务方验签凭据：请先与业务方约定切换窗口。宽限期结束后旧密钥签名的请求将被拒（A0400 签名验证失败）。
      新密钥明文仅本次响应返回。</div>`;
  const box = document.getElementById('oaSecretBox');
  try {
    const r = await ppSensitive('轮换商户密钥', html,
      '/members/' + encodeURIComponent(app.merchantId) + '/secrets/rotate', 'POST', { graceSeconds: grace }, html);
    if (!r) return;
    ppResult(box, '密钥已轮换', r);
    if (r && r.appSecret) box.innerHTML += ddBox('warn',
      `<b>一次性新 appSecret（请立即转交业务方，页面不留存）</b><br><span class="pp-mono">${esc(r.appSecret)}</span>`);
    toast('密钥已轮换'); await ppOaSecretInfo(); await loadOpenApps();
  } catch (e) { ppErrBox(box, '密钥轮换', e); }
}
function ppOaCsv() {
  if (!PPOpen.apps.length) { toast('无数据可导出', false); return; }
  ppCsvLocal('openplatform-apps.csv',
    ['appId', '名称', '商户', 'scope', 'QPS上限', '日配额', '单价(分)', '沙箱', '版本', '状态', '密钥指纹', '操作人', '创建时间'],
    PPOpen.apps.map(a => [a.appId, a.name, a.merchantId, (a.scopes || []).join('|'), a.rateLimit, a.dailyQuota,
      a.unitPrice, a.sandbox ? 'Y' : 'N', a.apiVersion, a.status, a.secretFingerprint, a.operator, fmtTime(a.createdAt)]));
}

/* ===================== 页 2 · 沙箱 ===================== */
function ppOsEnsure() {
  ppOxSec('open-sandbox', 'osAppTable', PP_OS_HTML);
  DD_KINDS.sbxOrder = '沙箱交易单'; DD_RENDER.sbxOrder = ppOsDdOrder;
  ppOxLp('osApp', { table: 'osAppTable', render: ppOsAppRows, filters: [
    { k: 'kw', label: '关键字', ph: 'appId / 名称 / 商户', fields: ['appId', 'name', 'merchantId'] },
    { k: 'status', label: '状态', type: 'select', field: 'status', opts: [opt('', '全部')].concat(PP_APP_STATUS) } ] });
  ppOxLp('osAccount', { table: 'osAccountTable', render: ppOsAccountRows, filters: [
    { k: 'kw', label: '关键字', ph: '账户 / 会员 / 名称', fields: ['accountId', 'memberId', 'name'] },
    { k: 'type', label: '类型', type: 'select', field: 'type', opts: [opt('', '全部'), opt('USER', 'USER'), opt('MERCHANT', 'MERCHANT')] } ] });
  ppOxLp('osOrder', { table: 'osOrderTable', render: ppOsOrderRows, filters: [
    { k: 'kw', label: '关键字', ph: '交易单 / outTradeNo / 标题', fields: ['id', 'outTradeNo', 'subject'] },
    { k: 'status', label: '状态', type: 'select', field: 'status',
      opts: [opt('', '全部'), opt('CREATED', 'CREATED 待支付'), opt('PAID', 'PAID 已支付'), opt('SUCCESS', 'SUCCESS'), opt('CLOSED', 'CLOSED')] } ] });
  ppOxLp('osTxn', { table: 'osTxnTable', render: ppOsTxnRows, filters: [
    { k: 'kw', label: '关键字', ph: '资金单 / 账户 / requestId', fields: ['id', 'payerAccountId', 'toAccountId', 'requestId'] },
    { k: 'bizType', label: '业务类型', type: 'select', field: 'bizType',
      opts: [opt('', '全部'), opt('TRADE', 'TRADE'), opt('TOPUP', 'TOPUP'), opt('WITHDRAW', 'WITHDRAW'), opt('TRANSFER', 'TRANSFER')] } ] });
  ppOxOnce('sandbox', () => {
    ppOxOn({
      btnOsRefresh: () => loadOpenSandbox(),
      btnOsAccounts: () => ppOsAccounts(),
      btnOsOrders: () => ppOsOrders(),
      btnOsTxns: () => ppOsTxns(),
      btnOsAll: () => ppOsAll(),
      btnOsCreate: () => ppOsCreate(),
      btnOsFaultSet: () => ppOsFaultSet(),
      btnOsFaultReset: () => ppOsFaultReset()
    });
    const e = document.getElementById('osAppId');
    if (e) e.addEventListener('keydown', ev => { if (ev.key === 'Enter') ppOsAll(); });
  });
  ppOxWireLinks();
}
async function loadOpenSandbox() {
  ppOsEnsure();
  lpLoading('osApp', 9, '加载沙箱应用…');
  const [apps, posture] = await Promise.all([tryApi('/openplatform/apps'), tryApi('/platform/sandbox')]);
  if (!apps.ok) lpError('osApp', 9, apps.err.message);
  else {
    PPOpen.apps = apps.data || [];
    lpSet('osApp', PPOpen.apps.filter(a => a.sandbox));
  }
  PPOpen.posture = posture.ok ? (posture.data || null) : null;
  PPOpen.faults = posture.ok ? (posture.data && posture.data.faults) : null;
  ppOsPosture(posture.ok ? '' : posture.err.message);
  ppOsFaultRender();
  const id = ppVal('osAppId');
  if (id) await ppOsAll();
  else { lpSet('osAccount', []); lpSet('osOrder', []); lpSet('osTxn', []); }
}
function ppOsPosture(err) {
  const box = document.getElementById('osPosture');
  if (!box) return;
  const p = PPOpen.posture;
  if (!p) {
    box.innerHTML = ddBox('warn', '沙箱姿态端点不可用（' + esc(err || '未知') + '）：仅展示应用列表，隔离与注入态无法核对。');
    return;
  }
  const iso = p.isolation || {}, guard = p.prodGuard || {};
  const methods = p.supportedMethods || [];
  const f = p.faults || {};
  const injecting = !!(f.reject || f.receiptLoss || f.amountMismatch || f.down || Number(f.timeoutMs) > 0 || Number(f.delayMs) > 0);
  box.innerHTML = `<div class="stat-cards">
      <div class="stat-card accent"><div class="label">沙箱应用</div><div class="value">${ppNum(p.sandboxAppCount)}</div></div>
      <div class="stat-card"><div class="label">生产应用</div><div class="value">${ppNum(p.prodAppCount)}</div></div>
      <div class="stat-card ok"><div class="label">沙箱支持方法</div><div class="value">${methods.length}</div></div>
      <div class="stat-card ${injecting ? 'warn' : 'ok'}"><div class="label">故障注入</div><div class="value">${injecting ? '进行中' : '全部关闭'}</div></div>
      <div class="stat-card ok"><div class="label">动生产资金账本</div><div class="value">${iso.touchesFundLedger ? '是' : '否'}</div></div>
    </div>
    ${ddBox('info', '<b>沙箱生产必关（当前态如实展示）</b>：沙箱截流<b>仅按 appId 的 sandbox 标志</b>判定 —— ' +
      '生产 appId 永远走生产链路，沙箱 appId 永远走沙箱账本；不存在「全局沙箱开关」把生产流量导入沙箱的可能。' +
      '生产环境不得下发沙箱 appId 给真实商户（本页沙箱应用清单即核对依据）。')}
    ${ddKv([['生产 appId 可用沙箱账本', guard.prodAppCanUseSandboxLedger ? ppTag('bad', '是') : ppTag('on', '否')],
      ['沙箱 appId 可用生产账本', guard.sandboxAppCanUseProdLedger ? ppTag('bad', '是') : ppTag('on', '否')],
      ['仅按应用标志路由', guard.sandboxRoutingByAppFlagOnly ? ppTag('on', '是') : ppTag('bad', '否')],
      ['账户前缀', esc((iso.accountPrefix || []).join(' / '))],
      ['交易单 / 资金单前缀', esc((iso.orderPrefix || '—') + ' / ' + (iso.txnPrefix || '—'))],
      ['沙箱表', esc((iso.tables || []).join(', '))]])}
    ${ddSec('沙箱支持方法（网关截流目录）', `<div class="pp-code light">${esc(methods.join('\n') || '—')}</div>`, methods.length + ' 个')}
    <div class="pp-obs">${esc(iso.note || '')}</div>`;
}
function ppOsAppRows(rows) {
  const t = document.getElementById('osAppTable'); if (!t) return;
  t.innerHTML = `<tr><th>沙箱 appId</th><th>名称</th><th>沙箱商户</th><th>scope</th><th class="num">单价（分）</th>
    <th>状态</th><th>密钥指纹</th><th>创建时间</th><th>操作</th></tr>` +
    (rows.length ? rows.map(a => `<tr><td>${ddLink('openapp', a.appId, a.appId)}</td><td>${esc(a.name || '—')}</td>
      <td class="pp-mono">${esc(a.merchantId || '—')}</td>
      <td class="pp-mono" style="max-width:200px;word-break:break-all">${esc((a.scopes || []).join(', '))}</td>
      <td class="num">${ppNum(a.unitPrice)}</td><td>${ppBadge(a.status, a.status)}</td>
      <td class="pp-mono">${esc(a.secretFingerprint || '—')}</td><td>${fmtTime(a.createdAt)}</td>
      <td><button class="btn small ghost" data-oa-sbx="${esc(a.appId)}">三表联查</button></td></tr>`).join('')
      : emptyRow(9, '暂无沙箱应用（可在下方一键注册）'));
}
function ppOsAccountRows(rows) {
  const t = document.getElementById('osAccountTable'); if (!t) return;
  t.innerHTML = `<tr><th>沙箱账户</th><th>沙箱会员</th><th>名称</th><th>类型</th><th class="num">余额（元）</th>
    <th>归属 appId</th><th>创建时间</th></tr>` +
    (rows.length ? rows.map(a => `<tr><td>${ddLink('account', a.accountId, a.accountId)}</td>
      <td class="pp-mono">${esc(a.memberId || '—')}</td><td>${esc(a.name || '—')}</td>
      <td>${ppBadge('kind', a.type || '—')}</td><td class="num">${ppFen(a.balance)}</td>
      <td class="pp-mono">${esc(a.appId || '—')}</td><td>${fmtTime(a.createdAt)}</td></tr>`).join('')
      : emptyRow(7, '该沙箱应用暂无账户（用 SDK 示例开户）'));
}
function ppOsOrderRows(rows) {
  const t = document.getElementById('osOrderTable'); if (!t) return;
  t.innerHTML = `<tr><th>沙箱交易单</th><th>outTradeNo</th><th>标题</th><th class="num">金额（元）</th><th>状态</th>
    <th>支付单</th><th>创建时间</th><th>支付时间</th></tr>` +
    (rows.length ? rows.map(o => `<tr><td>${ddLink('sbxOrder', (o.appId || ppVal('osAppId')) + ':' + o.id, o.id)}</td>
      <td class="pp-mono">${esc(o.outTradeNo || '—')}</td><td>${esc(o.subject || '—')}</td>
      <td class="num">${ppFen(o.amount)}</td><td>${ppBadge(o.status, o.status)}</td>
      <td class="pp-mono">${esc(o.paymentId || '—')}</td>
      <td>${fmtTime(o.createdAt)}</td><td>${o.paidAt ? fmtTime(o.paidAt) : '—'}</td></tr>`).join('')
      : emptyRow(8, '暂无沙箱交易单（用 SDK 示例下单）'));
}
function ppOsTxnRows(rows) {
  const t = document.getElementById('osTxnTable'); if (!t) return;
  t.innerHTML = `<tr><th>沙箱资金单</th><th>业务类型</th><th>方式</th><th>付款账户</th><th>收款账户</th>
    <th class="num">金额（元）</th><th class="num">手续费</th><th>状态</th><th>幂等键</th><th>创建时间</th></tr>` +
    (rows.length ? rows.map(x => `<tr><td class="pp-mono">${esc(x.id)}</td>
      <td>${ppBadge('kind', x.bizType || '—')}</td><td>${esc(x.method || '—')}</td>
      <td class="pp-mono">${esc(x.payerAccountId || '—')}</td><td class="pp-mono">${esc(x.toAccountId || '—')}</td>
      <td class="num">${ppFen(x.amount)}</td><td class="num">${ppFen(x.fee)}</td>
      <td>${ppBadge(x.status, x.status)}</td><td class="pp-mono">${esc(x.requestId || '—')}</td>
      <td>${fmtTime(x.createdAt)}</td></tr>`).join('') : emptyRow(10, '暂无沙箱资金单'));
}
async function ppOsAccounts() {
  const id = ppVal('osAppId');
  if (!id) { toast('请输入沙箱 appId', false); return; }
  lpLoading('osAccount', 7, '加载沙箱账本…');
  const r = await tryApi('/openplatform/sandbox/' + encodeURIComponent(id) + '/accounts');
  if (!r.ok) { lpError('osAccount', 7, r.err.message); return; }
  PPOpen.sandboxAccounts = r.data || [];
  lpSet('osAccount', PPOpen.sandboxAccounts);
  ppOsLedgerSummary();
}
async function ppOsOrders() {
  const id = ppVal('osAppId');
  if (!id) { toast('请输入沙箱 appId', false); return; }
  lpLoading('osOrder', 8, '加载沙箱交易单…');
  const r = await tryApi('/platform/sandbox/' + encodeURIComponent(id) + '/orders?limit=' + (ppInt('osLimit', 50) || 50));
  if (!r.ok) { lpError('osOrder', 8, r.err.message); return; }
  PPOpen.sandboxOrders = r.data || [];
  lpSet('osOrder', PPOpen.sandboxOrders);
  ppOsLedgerSummary();
}
async function ppOsTxns() {
  const id = ppVal('osAppId');
  if (!id) { toast('请输入沙箱 appId', false); return; }
  lpLoading('osTxn', 10, '加载沙箱资金单…');
  const r = await tryApi('/platform/sandbox/' + encodeURIComponent(id) + '/txns?limit=' + (ppInt('osLimit', 50) || 50));
  if (!r.ok) { lpError('osTxn', 10, r.err.message); return; }
  PPOpen.sandboxTxns = r.data || [];
  lpSet('osTxn', PPOpen.sandboxTxns);
  ppOsLedgerSummary();
}
async function ppOsAll() {
  const id = ppVal('osAppId');
  if (!id) { toast('请输入沙箱 appId', false); return; }
  await Promise.all([ppOsAccounts(), ppOsOrders(), ppOsTxns()]);
}
function ppOsLedgerSummary() {
  const box = document.getElementById('osBox');
  if (!box) return;
  const list = PPOpen.sandboxAccounts || [], orders = PPOpen.sandboxOrders || [], txns = PPOpen.sandboxTxns || [];
  const sum = list.reduce((s, a) => s + Number(a.balance || 0), 0);
  const bad = list.filter(a => !/^SBX[AM]/.test(String(a.accountId || '')));
  const badOrder = orders.filter(o => !String(o.id || '').startsWith('SBXO'));
  const badTxn = txns.filter(x => !String(x.id || '').startsWith('SBXP'));
  const feeSum = txns.reduce((s, x) => s + Number(x.fee || 0), 0);
  box.innerHTML = `<div class="stat-cards">
      <div class="stat-card accent"><div class="label">沙箱账户</div><div class="value">${list.length}</div></div>
      <div class="stat-card"><div class="label">交易单 / 资金单</div><div class="value">${orders.length} / ${txns.length}</div></div>
      <div class="stat-card ok"><div class="label">沙箱余额合计（元）</div><div class="value">${yuan(sum)}</div></div>
      <div class="stat-card ${(bad.length + badOrder.length + badTxn.length) ? 'bad' : 'ok'}"><div class="label">越界前缀</div><div class="value">${bad.length + badOrder.length + badTxn.length}</div></div>
      <div class="stat-card ${feeSum ? 'bad' : 'ok'}"><div class="label">沙箱手续费合计</div><div class="value">${yuan(feeSum)}</div></div>
    </div>
    ${ppCons('沙箱命名空间隔离（SBXA/SBXM + SBXO + SBXP 全覆盖）',
      (list.length - bad.length) + (orders.length - badOrder.length) + (txns.length - badTxn.length),
      list.length + orders.length + txns.length,
      { raw: true, leftLabel: '合规前缀', rightLabel: '记录总数',
        note: '沙箱资金使用独立 SBXA/SBXM/SBXO/SBXP 命名空间，与生产资金账本物理隔离，不影响试算平衡。' })}
    ${ppCons('沙箱 fee=0 语义（手续费合计必须为 0）', feeSum, 0,
      { leftLabel: 'Σfee', rightLabel: '期望 0', note: '沙箱同步 SUCCESS、fee=0、仅 BALANCE 支付，不产生渠道调用与真实资金变动。' })}`;
}
async function ppOsDdOrder(key) {
  const i = key.indexOf(':');
  const appId = key.slice(0, i), orderId = key.slice(i + 1);
  const r = await tryApi('/platform/sandbox/orders/' + encodeURIComponent(appId) + '/' + encodeURIComponent(orderId));
  if (!r.ok) return ddBox('danger', '沙箱交易单加载失败：' + esc(r.err.message));
  const d = r.data || {}, o = d.order || {}, p = d.payment || null;
  return ddKv([['沙箱交易单', esc(o.id || orderId)], ['归属 appId', ddLink('openapp', appId, appId)],
      ['沙箱商户', esc(o.merchantId || '—')], ['outTradeNo', esc(o.outTradeNo || '—')],
      ['标题', esc(o.subject || '—')], ['金额（元）', ppFen(o.amount)], ['状态', ppBadge(o.status, o.status)],
      ['支付单', esc(o.paymentId || '—')], ['创建 / 支付', `${fmtTime(o.createdAt)} / ${o.paidAt ? fmtTime(o.paidAt) : '—'}`]]) +
    (p ? ddSec('关联沙箱支付单（SBXP）', ddKv([['资金单', esc(p.id)], ['业务类型', esc(p.bizType || '—')],
        ['方式', esc(p.method || '—')], ['付款账户', esc(p.payerAccountId || '—')],
        ['收款账户', esc(p.toAccountId || '—')], ['金额（元）', ppFen(p.amount)], ['手续费（元）', ppFen(p.fee)],
        ['状态', ppBadge(p.status, p.status)], ['幂等键', esc(p.requestId || '—')],
        ['创建时间', fmtTime(p.createdAt)]]))
      : ddBox('plain', '该交易单尚未支付（无关联 SBXP 资金单）。')) +
    ddBox('info', '沙箱单据与生产单据命名空间不重叠：SBXO/SBXP 不会出现在生产交易单 / 支付单 / 账务流水中。');
}
function ppOsFaultRender() {
  const box = document.getElementById('osFaultBox');
  if (!box) return;
  const f = PPOpen.faults;
  if (!f || f.error) {
    box.innerHTML = ddBox('warn', '故障注入态不可用：' + esc((f && f.error) || '未取到') + '（渠道域运维面）');
    return;
  }
  const set = (id, v) => { const e = document.getElementById(id); if (e && document.activeElement !== e) e.value = v == null ? '' : v; };
  const ck = (id, v) => { const e = document.getElementById(id); if (e) e.checked = !!v; };
  ck('osFpReceiptLoss', f.receiptLoss); ck('osFpReject', f.reject);
  ck('osFpMismatch', f.amountMismatch); ck('osFpDown', f.down);
  set('osFpDelay', f.delayMs); set('osFpTimeout', f.timeoutMs); set('osFpDelta', f.amountDelta); set('osFpTarget', f.targetBizNo);
  const bill = Object.entries(f.billFaults || {});
  box.innerHTML = ddTable(['注入项', '当前态', '语义'], [
    ['回执丢失 receiptLoss', f.receiptLoss, '只落终态不推回执 → 平台必须查询补单'],
    ['异步拒绝 reject', f.reject, '终态 FAILED（业务失败须留痕）'],
    ['金额不符 amountMismatch', f.amountMismatch, '渠道金额 = 请求金额 + Δ（对账差异靶）'],
    ['宕机 down', f.down, 'submit/query/bill 一律 503 → 确未受理 → 故障切换'],
    ['延迟 delayMs', f.delayMs == null ? '默认' : f.delayMs + ' ms', '回执延迟'],
    ['超时 timeoutMs', Number(f.timeoutMs) > 0 ? f.timeoutMs + ' ms' : '不注入', '受理响应超时 → 状态未知 PROCESSING（不臆断失败）'],
    ['金额差 amountDelta', (f.amountDelta == null ? 100 : f.amountDelta) + ' 分', '金额不符的差额'],
    ['作用范围 targetBizNo', f.targetBizNo || '全部沙箱单', '限定单笔注入']
  ].map(([k, v, note]) => `<tr><td class="pp-mono">${esc(k)}</td>
    <td>${typeof v === 'boolean' ? (v ? ppTag('bad', '注入中') : ppTag('on', '关闭')) : esc(String(v))}</td>
    <td>${esc(note)}</td></tr>`).join(''), '无注入项') +
    (bill.length ? ddSec('账单差异注入', `<div class="pp-code light">${esc(JSON.stringify(f.billFaults, null, 2))}</div>`, bill.length + ' 个渠道') : '') +
    ddBox('plain', '注入器为渠道进程内内存态，<b>重启即恢复出厂</b>；只作用于模拟银行与其生成的账单文件，' +
      '不连接真实银行、不改写 channel_trade / 账务 / 支付单任何既有数据。');
}
async function ppOsFaultSet() {
  const body = {
    receiptLoss: ppChecked('osFpReceiptLoss'), reject: ppChecked('osFpReject'),
    amountMismatch: ppChecked('osFpMismatch'), down: ppChecked('osFpDown'),
    timeoutMs: ppInt('osFpTimeout', 0), amountDelta: ppInt('osFpDelta', 100),
    targetBizNo: ppVal('osFpTarget') || null
  };
  const delay = ppVal('osFpDelay');
  if (delay !== '') body.delayMs = Number(delay);
  const html = `<div class="box warn"><b>下发沙箱故障注入（敏感操作，需二次认证）</b></div>
    ${ddKv(Object.entries(body).map(([k, v]) => [k, esc(String(v == null ? '（清空）' : v))]))}
    <div class="pp-note">只作用于渠道进程内模拟银行与其账单文件；不连真实银行、不改既有业务数据。
      验收结束请点「全部复位」，避免污染后续用例。</div>`;
  const box = document.getElementById('osFaultOpBox');
  try {
    const r = await ppSensitive('下发沙箱故障注入', html, '/platform/faults', 'POST', body, html);
    if (!r) return;
    PPOpen.faults = r; ppResult(box, '注入已下发', r); ppOsFaultRender(); toast('注入已下发');
  } catch (e) { ppErrBox(box, '故障注入', e); }
}
async function ppOsFaultReset() {
  const html = `<div class="box warn"><b>复位全部沙箱故障注入（敏感操作）</b>：延迟/拒绝/回执丢失/金额不符/宕机/超时与账单注入全部恢复出厂。</div>`;
  const box = document.getElementById('osFaultOpBox');
  try {
    const r = await ppSensitive('复位沙箱故障注入', html, '/platform/faults/reset', 'POST', {}, html, '确认复位');
    if (!r) return;
    PPOpen.faults = r; ppResult(box, '注入已复位', r); ppOsFaultRender(); toast('注入已复位');
  } catch (e) { ppErrBox(box, '故障复位', e); }
}
async function ppOsCreate() {
  const name = ppVal('osName');
  if (!name) { toast('沙箱应用名称必填', false); return; }
  const body = { name, sandbox: true, scopes: ['*'], unitPrice: ppInt('osPrice', 0),
    rateLimit: 0, dailyQuota: 0, apiVersion: 'v1' };
  const html = `<div class="box warn"><b>注册沙箱应用（敏感操作）</b>：生成独立 appId / appSecret 与 SBXA 账本命名空间。</div>
    ${ddKv([['名称', esc(name)], ['scope', '*（沙箱全放行）'], ['单价（分/次）', String(body.unitPrice)],
      ['QPS / 日配额', '不限（沙箱）']])}
    <div class="pp-note">appSecret 明文仅本次返回；沙箱与生产资金账本物理隔离。</div>`;
  try {
    const r = await ppSensitive('注册沙箱应用', html, '/openplatform/apps', 'POST', body, html);
    if (!r) return;
    const box = document.getElementById('osBox');
    ppResult(box, '沙箱应用已注册', r);
    if (r && r.appSecret && box) box.innerHTML += ddBox('warn',
      `<b>一次性 appSecret</b><br><span class="pp-mono">${esc(r.appSecret)}</span><br>
       appId：<span class="pp-mono">${esc(r.appId || '')}</span>
       ${ppCode('PP_APP_ID=' + (r.appId || '') + ' PP_APP_SECRET=' + r.appSecret + ' python3 sdk/python/example_sandbox.py', '一键跑沙箱示例')}`);
    if (r && r.appId) { const e = document.getElementById('osAppId'); if (e) e.value = r.appId; }
    toast('沙箱应用已注册'); await loadOpenSandbox();
  } catch (e) { ppErrBox('osBox', '注册沙箱应用', e); }
}

/* ===================== 页 3 · 配额与计费 ===================== */
function ppOqEnsure() {
  ppOxSec('open-quota', 'oqBillTable', PP_OQ_HTML);
  ppOxLp('oqBill', { table: 'oqBillTable', render: ppOqBillRows, timeField: 'updatedAt', filters: [
    { k: 'kw', label: '关键字', ph: 'appId / API / 会计日', fields: ['appId', 'api', 'bizDate'] },
    { k: 'appId', label: 'appId', ph: 'appM1', field: 'appId' },
    { k: 'billed', label: '计费', type: 'select', opts: [opt('', '全部'), opt('true', '有计费金额'), opt('false', '免费（单价 0）')],
      test: (r, v) => String(Number(r.billedAmount || 0) > 0) === v },
    { k: 'rejected', label: '拒绝', type: 'select', opts: [opt('', '全部'), opt('true', '有拒绝计数'), opt('false', '无拒绝')],
      test: (r, v) => String(Number(r.rejectCount || 0) > 0) === v } ] });
  ppOxOnce('quota', () => {
    ppOxOn({
      btnOqQuery: () => ppOqQuery(),
      btnOqSave: () => ppOqSave(),
      btnOqBill: () => ppOqBill(),
      btnOqCsv: () => ppOqCsv(),
      btnOqAppM1: () => { const e = document.getElementById('oqAppId'); if (e) e.value = 'appM1'; ppOqQuery(); }
    });
    const e = document.getElementById('oqAppId');
    if (e) e.addEventListener('keydown', ev => { if (ev.key === 'Enter') ppOqQuery(); });
  });
  ppOxWireLinks();
}
async function loadOpenQuota() {
  ppOqEnsure();
  const id = ppVal('oqAppId');
  if (id) await ppOqQuery();
  else {
    const box = document.getElementById('oqBox');
    if (box) box.innerHTML = ddBox('plain', '输入 appId 后查询配额与实时用量（或点「appM1 兼容红线」）；下方账单默认展示全部应用当日计费。');
  }
  await ppOqBill();
}
async function ppOqQuery() {
  const id = ppVal('oqAppId');
  if (!id) { toast('请输入 appId', false); return; }
  const box = document.getElementById('oqBox');
  ppLoadingBox(box, '加载配额与用量…');
  const r = await tryApi('/openplatform/quota?appId=' + encodeURIComponent(id));
  if (!r.ok) { box.innerHTML = ddBox('danger', '配额加载失败：' + esc(r.err.message)); return; }
  const q = r.data || {}; PPOpen.quota = q;
  const over = Number(q.dailyQuota || 0) > 0 && Number(q.usedToday || 0) >= Number(q.dailyQuota);
  const near = Number(q.dailyQuota || 0) > 0 && Number(q.usedToday || 0) >= Number(q.dailyQuota) * 0.8;
  const free = Number(q.unitPrice || 0) === 0;
  box.innerHTML = ddBox(!q.registered ? 'warn' : over ? 'danger' : near ? 'warn' : 'info',
      !q.registered ? '<b>未注册 appId</b>：全放行 + 免费计数（ride-pro 兼容红线，不限流不限配额）'
      : over ? '<b>日配额已耗尽</b>：后续调用将被受理前拒绝（A0429）'
      : near ? '<b>日配额接近上限（≥80%）</b>：建议提前扩容' : '<b>配额正常</b>') +
    ddKv([['appId', ddLink('openapp', q.appId, q.appId)], ['已注册', q.registered ? ppTag('on', '是') : ppTag('off', '否')],
      ['状态', ppBadge(q.status, q.status || '—')], ['环境', q.sandbox ? ppBadge('SANDBOX', '沙箱') : ppBadge('PROD', '生产')],
      ['会计日', ppBizDate(q.bizDate)], ['QPS 上限', Number(q.rateLimit || 0) > 0 ? q.rateLimit + ' /s' : '不限'],
      ['实时 QPS', ppNum(q.qpsUsed)], ['日配额', Number(q.dailyQuota || 0) > 0 ? q.dailyQuota + ' 次' : '不限'],
      ['当日已用', ppNum(q.usedToday)], ['当日被拒', ppNum(q.rejectedToday)],
      ['剩余', q.remaining == null ? '不限' : ppNum(q.remaining)],
      ['单价（分/次）', ppNum(q.unitPrice)], ['计费口径', free ? ppTag('on', '免费（单价 0）') : ppTag('off', '按次计费')]]) +
    `<div class="pp-grid kv2" style="margin-top:8px">
      <div><b style="font-size:12px">日配额水位</b>${ppMeter(q.usedToday, q.dailyQuota)}</div>
      <div><b style="font-size:12px">QPS 水位</b>${ppMeter(q.qpsUsed, q.rateLimit)}</div>
    </div>
    ${id === 'appM1' ? ddBox('info', '<b>appM1 已拍板：免费不限</b> —— 未注册 appId 走 legacy 策略：' +
        '不限流（rateLimit=0）、不限日配额（dailyQuota=0）、单价 0（免费计数）。' +
        '这是 ride-pro 双账本镜像接入的兼容红线，<b>不得</b>为其配置限流/配额/单价。') : ''}
    <div class="pp-obs">计费账本独立于资金账本（billingTouchesFundLedger=false），超限在<b>受理前</b>拒绝，
      不落订单 / 不留 PROCESSING 悬挂单；被拒次数单独计数（rejectCount），不计费。</div>`;
}
async function ppOqSave() {
  const id = ppVal('oqAppId');
  if (!id) { toast('请先填写 appId', false); return; }
  const body = { rateLimit: ppVal('oqRate') === '' ? undefined : ppInt('oqRate', 0),
    dailyQuota: ppVal('oqQuota') === '' ? undefined : ppInt('oqQuota', 0),
    unitPrice: ppVal('oqPrice') === '' ? undefined : ppInt('oqPrice', 0) };
  const changed = Object.entries(body).filter(([, v]) => v !== undefined);
  if (!changed.length) { toast('请至少填写一项（留空 = 不改）', false); return; }
  const q = PPOpen.quota || {};
  const html = `<div class="box warn"><b>修改配额与计费单价（敏感操作）</b>：即时生效，影响业务方可用性与账单。</div>
    ${ddKv([['appId', esc(id)], ['当前 QPS / 日配额 / 单价',
        `${ppNum(q.rateLimit)} / ${ppNum(q.dailyQuota)} / ${ppNum(q.unitPrice)}`],
      ['当日已用 / 被拒', `${ppNum(q.usedToday)} / ${ppNum(q.rejectedToday)}`]]
      .concat(changed.map(([k, v]) => ['新 ' + k, String(v) + (v === 0 ? '（0 = 不限 / 免费）' : '')])))}
    <div class="pp-note">下调配额可能立即触发 A0429；上调单价影响后续计费金额。
      ${id === 'appM1' ? '<b>appM1 已拍板免费不限，请勿为其设置限流/配额/单价。</b>' : ''}</div>`;
  try {
    const r = await ppSensitive('修改配额与计费', html, '/openplatform/apps/' + encodeURIComponent(id), 'PUT', body, html);
    if (!r) return;
    ppResult('oqOpBox', '配额已更新', r); toast('配额已更新'); await ppOqQuery(); await ppOqBill();
  } catch (e) { ppErrBox('oqOpBox', '修改配额', e); }
}
async function ppOqBill() {
  lpLoading('oqBill', 9, '加载计费日账单…');
  const p = new URLSearchParams();
  const a = ppVal('oqBillApp'), d = ppVal('oqBillDate');
  if (a) p.set('appId', a);
  if (d) p.set('bizDate', d.replace(/-/g, ''));
  const r = await tryApi('/openplatform/bill' + (p.toString() ? '?' + p.toString() : ''));
  if (!r.ok) { lpError('oqBill', 9, r.err.message); return; }
  PPOpen.bill = r.data || [];
  lpSet('oqBill', PPOpen.bill);
  const box = document.getElementById('oqSum');
  if (box) {
    const calls = PPOpen.bill.reduce((s, b) => s + Number(b.callCount || 0), 0);
    const rej = PPOpen.bill.reduce((s, b) => s + Number(b.rejectCount || 0), 0);
    const amt = PPOpen.bill.reduce((s, b) => s + Number(b.billedAmount || 0), 0);
    const expect = PPOpen.bill.reduce((s, b) => s + Number(b.callCount || 0) * Number(b.unitPrice || 0), 0);
    const byApp = {};
    PPOpen.bill.forEach(b => { byApp[b.appId] = (byApp[b.appId] || 0) + Number(b.billedAmount || 0); });
    const top = Object.entries(byApp).sort((x, y) => y[1] - x[1]).slice(0, 5);
    const m1 = PPOpen.bill.filter(b => b.appId === 'appM1');
    box.innerHTML = `<div class="stat-cards">
        <div class="stat-card accent"><div class="label">账单行数</div><div class="value">${PPOpen.bill.length}</div></div>
        <div class="stat-card"><div class="label">调用总数</div><div class="value">${calls}</div></div>
        <div class="stat-card ${rej ? 'bad' : ''}"><div class="label">拒绝总数</div><div class="value">${rej}</div></div>
        <div class="stat-card ok"><div class="label">计费合计（元）</div><div class="value">${yuan(amt)}</div></div>
        <div class="stat-card"><div class="label">涉及应用</div><div class="value">${Object.keys(byApp).length}</div></div>
      </div>
      ${ppCons('计费守恒（Σ callCount×unitPrice = Σ billedAmount）', expect, amt,
        { leftLabel: 'Σ call×price', rightLabel: 'Σ billed', note: 'rejectCount 不计费；未注册 appId 单价 0（免费计数）。' })}
      ${m1.length ? ddBox('info', `<b>appM1 当日账单</b>：${m1.length} 行，调用 ${m1.reduce((s, b) => s + Number(b.callCount || 0), 0)} 次，
          计费 ${yuan(m1.reduce((s, b) => s + Number(b.billedAmount || 0), 0))} 元（应为 0.00 —— 免费不限已拍板）。`) : ''}
      ${top.length ? `<div class="pp-note">计费 TOP5：${top.map(([k, v]) =>
        `<span class="pp-mono">${esc(k)}</span> ¥ ${yuan(v)}`).join(' · ')}</div>` : ''}`;
  }
}
function ppOqBillRows(rows) {
  const t = document.getElementById('oqBillTable'); if (!t) return;
  t.innerHTML = `<tr><th>appId</th><th>API 方法</th><th>会计日</th><th class="num">调用数</th><th class="num">拒绝数</th>
    <th class="num">单价（分）</th><th class="num">计费额（元）</th><th>校验</th><th>更新时间</th></tr>` +
    (rows.length ? rows.map(b => {
      const expect = Number(b.callCount || 0) * Number(b.unitPrice || 0);
      const ok = expect === Number(b.billedAmount || 0);
      return `<tr><td>${ddLink('openapp', b.appId, b.appId)}</td><td class="pp-mono">${esc(b.api)}</td>
        <td>${ppBizDate(b.bizDate)}</td><td class="num">${ppNum(b.callCount)}</td>
        <td class="num ${Number(b.rejectCount) > 0 ? 'neg' : ''}">${ppNum(b.rejectCount)}</td>
        <td class="num">${ppNum(b.unitPrice)}</td><td class="num">${ppFen(b.billedAmount)}</td>
        <td>${ok ? ppTag('on', 'call×price 一致') : ppTag('bad', '差 ' + yuan(Number(b.billedAmount || 0) - expect))}</td>
        <td>${fmtTime(b.updatedAt)}</td></tr>`;
    }).join('') : emptyRow(9, '暂无计费记录'));
}
function ppOqCsv() {
  if (!PPOpen.bill.length) { toast('无账单可导出', false); return; }
  ppCsvLocal('openplatform-bill.csv', ['appId', 'API', '会计日', '调用数', '拒绝数', '单价(分)', '计费额(分)', '更新时间'],
    PPOpen.bill.map(b => [b.appId, b.api, b.bizDate, b.callCount, b.rejectCount, b.unitPrice, b.billedAmount, fmtTime(b.updatedAt)]));
}

/* ===================== 页 4 · 版本与 SDK ===================== */
function ppOvEnsure() {
  ppOxSec('open-version', 'ovMethodTable', PP_OV_HTML);
  ppOxLp('ovMethod', { table: 'ovMethodTable', render: ppOvMethodRows, filters: [
    { k: 'kw', label: '关键字', ph: '方法名 / scope', fields: ['method', 'scope', 'kind'] },
    { k: 'kind', label: '契约面', type: 'select', field: 'kind',
      opts: [opt('', '全部'), opt('FROZEN', 'v1 冻结面'), opt('ADDITIVE', 'additive 新增'), opt('V2', 'v2 演进')] } ] });
  ppOxLp('ovSdkFile', { table: 'ovSdkTable', render: ppOvSdkRows, filters: [
    { k: 'kw', label: '关键字', ph: '文件路径 / 名称', fields: ['path', 'name'] },
    { k: 'lang', label: '语言', type: 'select', field: 'lang', opts: [opt('', '全部'), opt('python', 'Python'), opt('java', 'Java')] } ] });
  ppOxOnce('version', () => {
    ppOxOn({
      btnOvRefresh: () => loadOpenVersion(),
      btnOvContract: () => ppOvContract(),
      btnOvSign: () => ppDlText('openapi-sign-spec.txt', PP_SDK_SIGN),
      btnOvSdk: () => ppOvSdk(),
      btnOvSdkCsv: () => ppOvSdkCsv()
    });
    const t = document.getElementById('ovSdkTable');
    if (t) t.addEventListener('click', ev => {
      const b = ev.target.closest && ev.target.closest('[data-ov-dl]');
      if (b) { ev.preventDefault(); ppOvSdkDownload(b.dataset.ovDl); }
    });
  });
}
async function loadOpenVersion() {
  ppOvEnsure();
  const box = document.getElementById('ovBox');
  if (box) ppLoadingBox(box, '加载版本契约…');
  const [r, sc] = await Promise.all([ppRawTry('/openapi/version'), ppRawTry('/openapi/scopes')]);
  if (sc.ok && sc.data) PPOpen.scopes = sc.data;
  if (!r.ok) {
    if (box) box.innerHTML = ddBox('danger', '版本契约加载失败：' + esc(r.err.message));
    lpSet('ovMethod', []); return;
  }
  const v = r.data || {}; PPOpen.version = v;
  const vs = v.versions || [];
  if (box) box.innerHTML = ddKv([['默认版本', ppBadge('kind', v.default || 'v1')], ['当前演进版本', ppBadge('kind', v.current || '—')],
      ['协商 header', esc((v.negotiation && v.negotiation.header) || 'X-Api-Version')],
      ['路径前缀', esc((v.negotiation && v.negotiation.pathPrefix) || '/openapi/v2/')],
      ['协商规则', esc((v.negotiation && v.negotiation.rule) || '—')],
      ['应用默认版本', esc((v.negotiation && v.negotiation.appDefault) || 'v1')]]) +
    vs.map(x => `<div class="pp-prod" style="margin-top:10px">
      <div class="pp-inline" style="justify-content:space-between">
        <b>${esc(x.version)} <span class="pp-mono muted">${esc(x.basePath || '')}</span></b>
        <span>${ppBadge(x.status, x.status)} ${x.frozen ? ppTag('bad', '冻结面') : ppTag('on', '可演进')}
          ${x.deprecated ? ppTag('bad', '已弃用') : ''}</span></div>
      <div class="pp-note">${esc(x.compatibility || '')}</div>
      ${x.sunset ? `<div class="box warn">日落时间：${esc(String(x.sunset))}</div>` : ''}
      ${ddTable(['契约面', '方法数', '说明'], [
        `<tr><td>${ppTag('bad', '冻结方法')}</td><td class="num">${(x.methods || []).length}</td>
          <td class="pp-mono" style="word-break:break-all">${esc((x.methods || []).join(', '))}</td></tr>`,
        (x.additiveMethods || []).length ? `<tr><td>${ppTag('on', 'additive 新增')}</td>
          <td class="num">${x.additiveMethods.length}</td>
          <td class="pp-mono" style="word-break:break-all">${esc(x.additiveMethods.join(', '))}</td></tr>` : '',
        (x.additive || []).length ? `<tr><td>${ppTag('on', 'v2 演进点')}</td><td class="num">${x.additive.length}</td>
          <td>${esc(x.additive.join('；'))}</td></tr>` : '' ].filter(Boolean).join(''), '无')}
      ${x.additiveNote ? `<div class="pp-obs">${esc(x.additiveNote)}</div>` : ''}
    </div>`).join('');
  const rows = [];
  vs.forEach(x => {
    (x.methods || []).forEach(m => rows.push({ method: m, version: x.version, kind: x.frozen ? 'FROZEN' : 'V2',
      scope: PPOpen.scopes[m] || '—', note: x.frozen ? '入参 / 返回 / 幂等键 / 语义冻结' : 'v2 演进面' }));
    (x.additiveMethods || []).forEach(m => rows.push({ method: m, version: x.version, kind: 'ADDITIVE',
      scope: PPOpen.scopes[m] || '—', note: 'additive 新端点，v1 冻结面不变' }));
  });
  lpSet('ovMethod', rows);
  const notice = document.getElementById('ovNotice');
  if (notice) {
    const dep = vs.filter(x => x.deprecated || x.sunset);
    const frozen = vs.find(x => x.frozen) || {};
    notice.innerHTML = (dep.length ? ddBox('warn', '<b>弃用公告</b><br>' + dep.map(x =>
        `${esc(x.version)} 已标记弃用${x.sunset ? '，日落 ' + esc(String(x.sunset)) : ''}`).join('<br>'))
      : ddBox('info', `<b>弃用公告：当前无弃用版本</b><br>v1 为冻结面（${(frozen.methods || []).length} 方法逐字节不变），`
        + `新增能力一律走 additive 端点（${(frozen.additiveMethods || []).length} 个：收银台 9 + 签约代扣 2）；`
        + 'v2 为演进面（envelope / attach 回显 / fee 明细），业务方可按 X-Api-Version 或 /openapi/v2/ 路径渐进迁移。')) +
      `<div class="pp-obs"><b>兼容红线</b>：未注册 appId（如 ride-pro <span class="pp-mono">appM1</span>）走 legacy
        不限流 / 不限配额 / 单价 0；<span class="pp-mono">account.balance</span> 永远返回裸数字 long（分）。</div>`;
  }
  await ppOvSdk(true);
}
function ppOvMethodRows(rows) {
  const t = document.getElementById('ovMethodTable'); if (!t) return;
  t.innerHTML = `<tr><th>方法</th><th>版本</th><th>契约面</th><th>所需 scope</th><th>兼容口径</th></tr>` +
    (rows.length ? rows.map(m => `<tr><td class="pp-mono"><b>${esc(m.method)}</b></td>
      <td>${ppBadge('kind', m.version)}</td>
      <td>${m.kind === 'FROZEN' ? ppTag('bad', 'v1 冻结') : m.kind === 'ADDITIVE' ? ppTag('on', 'additive') : ppTag('on', 'v2 演进')}</td>
      <td class="pp-mono">${esc(m.scope)}</td><td>${esc(m.note)}</td></tr>`).join('') : emptyRow(5, '暂无方法契约'));
}
async function ppOvSdk(silent) {
  const box = document.getElementById('ovSdkBox');
  if (!box) return;
  if (!silent) ppLoadingBox(box, '读取双 SDK 清单与校验值…');
  const r = await tryApi('/platform/sdk');
  const sdkBox = document.getElementById('ovSdk');
  if (!r.ok) {
    box.innerHTML = ddBox('warn', 'SDK 清单端点不可用：' + esc(r.err.message));
    lpSet('ovSdkFile', []);
    if (sdkBox) sdkBox.innerHTML = `<div class="pp-code light">${esc(PP_SDK_SIGN)}</div>`;
    return;
  }
  const d = r.data || {}; PPOpen.sdk = d;
  const files = (d.python || []).map(f => Object.assign({ lang: 'python' }, f))
    .concat((d.java || []).map(f => Object.assign({ lang: 'java' }, f)));
  /* SDK 清单表在骨架里按需创建（避免 index.html 无该表时 lpDef 落空） */
  if (!document.getElementById('ovSdkTable')) {
    box.innerHTML = '<div class="table-wrap"><table id="ovSdkTable"></table></div>';
    ppOxLp('ovSdkFile', { table: 'ovSdkTable', render: ppOvSdkRows, filters: [
      { k: 'kw', label: '关键字', ph: '文件路径 / 名称', fields: ['path', 'name'] },
      { k: 'lang', label: '语言', type: 'select', field: 'lang', opts: [opt('', '全部'), opt('python', 'Python'), opt('java', 'Java')] } ] });
    const t = document.getElementById('ovSdkTable');
    if (t) t.addEventListener('click', ev => {
      const b = ev.target.closest && ev.target.closest('[data-ov-dl]');
      if (b) { ev.preventDefault(); ppOvSdkDownload(b.dataset.ovDl); }
    });
  }
  lpSet('ovSdkFile', files);
  const meta = document.getElementById('ovSdkMeta');
  if (meta) meta.remove();
  box.insertAdjacentHTML('afterbegin', `<div id="ovSdkMeta">${ddKv([
      ['SDK 根目录', esc(d.root || '—')], ['Python 文件', String((d.python || []).length)],
      ['Java 文件', String((d.java || []).length)],
      ['编译脚本', d.buildScript ? ppTag('on', 'sdk/java/build.sh 存在') : ppTag('off', '缺失')],
      ['契约探针', esc(d.contractProbe || '—')]])}
    <div class="pp-obs">${esc(d.driftGuard || '')}</div></div>`);
  if (sdkBox) sdkBox.innerHTML = ddSec('快速开始（沙箱）', ppCode(
    '# Python（零第三方依赖）\nexport PP_APP_ID=sbx... PP_APP_SECRET=...\n'
    + 'python3 sdk/python/example_sandbox.py            # 资金全链路\n'
    + 'python3 sdk/python/example_cashier_sandbox.py    # 收银台 9 + 签约 2 端到端\n\n'
    + '# Java（JDK 11+，零第三方依赖）\nbash sdk/java/build.sh\n'
    + 'java -cp sdk/java/out com.qoder.payment.sdk.SandboxExample\n'
    + 'java -cp sdk/java/out com.qoder.payment.sdk.CashierSandboxExample\n'
    + 'java -cp sdk/java/out com.qoder.payment.sdk.ContractProbe   # 契约探针（无网络）', '可复制命令')) +
    ddSec('签名口径（防漂移基线）', `<div class="pp-code light">${esc(PP_SDK_SIGN)}</div>`);
  if (!silent) toast('SDK 清单已刷新');
}
function ppOvSdkRows(rows) {
  const t = document.getElementById('ovSdkTable'); if (!t) return;
  t.innerHTML = `<tr><th>语言</th><th>路径</th><th class="num">字节</th><th>SHA-256（防漂移）</th><th>操作</th></tr>` +
    (rows.length ? rows.map(f => `<tr><td>${ppBadge('kind', f.lang)}</td>
      <td class="pp-mono" style="word-break:break-all">${esc(f.path)}</td>
      <td class="num">${ppNum(f.bytes)}</td>
      <td class="pp-mono" style="word-break:break-all">${esc(f.sha256 || '—')}</td>
      <td><button class="btn small ghost" data-ov-dl="${esc(f.path)}">下载源码</button></td></tr>`).join('')
      : emptyRow(5, '无 SDK 文件'));
}
async function ppOvSdkDownload(path) {
  const rel = String(path || '').replace(/^sdk\//, '');
  const r = await ppRawTry('/api/platform/sdk/file?path=' + encodeURIComponent(rel));
  if (!r.ok) { toast('下载失败：' + r.err.message, false); return; }
  const text = typeof r.data === 'string' ? r.data : (r.data && r.data.raw) || JSON.stringify(r.data, null, 2);
  ppDlText(String(path).split('/').pop(), text, 'text/plain');
}
function ppOvSdkCsv() {
  const rows = LP['ovSdkFile'] ? lpVisible('ovSdkFile') : [];
  if (!rows.length) { toast('无 SDK 清单可导出', false); return; }
  ppCsvLocal('sdk-checksums.csv', ['语言', '路径', '字节', 'SHA-256'],
    rows.map(f => [f.lang, f.path, f.bytes, f.sha256]));
}
async function ppOvContract() {
  if (!PPOpen.version) { const r = await ppRawTry('/openapi/version'); if (r.ok) PPOpen.version = r.data; }
  if (!PPOpen.version) { toast('版本契约不可用', false); return; }
  ppDlText('openapi-version-contract.json', JSON.stringify(PPOpen.version, null, 2), 'application/json');
}

/* ===================== 页 5 · API 文档 ===================== */
function ppOdEnsure() {
  ppOxSec('open-docs', 'odPathTable', PP_OD_HTML);
  ppOxLp('odPath', { table: 'odPathTable', render: ppOdPathRows, filters: [
    { k: 'kw', label: '关键字', ph: '路径 / 方法 / 分组 / 摘要', fields: ['path', 'method', 'tag', 'summary'] },
    { k: 'tag', label: '分组', ph: '通知', field: 'tag' },
    { k: 'method', label: 'HTTP', type: 'select', field: 'method',
      opts: [opt('', '全部'), opt('GET', 'GET'), opt('POST', 'POST'), opt('PUT', 'PUT'), opt('DELETE', 'DELETE')] } ] });
  ppOxOnce('docs', () => {
    ppOxOn({
      btnOdRefresh: () => loadOpenDocs(),
      btnOdDownload: () => ppOdDownload(),
      btnOdCsv: () => ppOdCsv(),
      btnOdDoc: () => ppOdDoc(),
      btnOdDocDl: () => ppOdDocDownload()
    });
    const sel = document.getElementById('odDocSel');
    if (sel) sel.onchange = () => ppOdDoc();
  });
}
async function loadOpenDocs() {
  ppOdEnsure();
  const box = document.getElementById('odBox');
  if (box) ppLoadingBox(box, '加载 springdoc OpenAPI 契约…');
  const [r, sc, dl] = await Promise.all([ppRawTry('/v3/api-docs'), tryApi('/openplatform/scopes'), tryApi('/platform/docs')]);
  if (sc.ok) PPOpen.scopes = sc.data || {};
  if (!r.ok) {
    if (box) box.innerHTML = ddBox('danger', 'OpenAPI 契约加载失败：' + esc(r.err.message));
    lpSet('odPath', []);
  } else {
    const d = r.data || {}; PPOpen.docs = d;
    const paths = d.paths || {};
    const rows = [];
    Object.entries(paths).forEach(([p, ops]) => Object.entries(ops || {}).forEach(([m, op]) => {
      if (!['get', 'post', 'put', 'delete', 'patch'].includes(m)) return;
      rows.push({ path: p, method: m.toUpperCase(), tag: (op.tags || [])[0] || '（未分组）',
        summary: op.summary || op.operationId || '', deprecated: !!op.deprecated });
    }));
    lpSet('odPath', rows);
    const tags = d.tags || [];
    const openapi = rows.filter(x => x.path.startsWith('/openapi'));
    const consoleApi = rows.filter(x => x.path.startsWith('/api'));
    if (box) box.innerHTML = `<div class="stat-cards">
        <div class="stat-card accent"><div class="label">路径总数</div><div class="value">${Object.keys(paths).length}</div></div>
        <div class="stat-card"><div class="label">操作总数</div><div class="value">${rows.length}</div></div>
        <div class="stat-card"><div class="label">控制台 /api</div><div class="value">${consoleApi.length}</div></div>
        <div class="stat-card ok"><div class="label">开放面 /openapi</div><div class="value">${openapi.length}</div></div>
        <div class="stat-card"><div class="label">分组数</div><div class="value">${tags.length}</div></div>
      </div>
      ${ddKv([['契约标题', esc((d.info && d.info.title) || '—')],
        ['契约版本', esc((d.info && d.info.version) || '—')], ['OpenAPI 规范', esc(d.openapi || '—')],
        ['服务地址', esc(((d.servers || [])[0] || {}).url || '—')]])}
      <div class="pp-obs">${esc((d.info && d.info.description) || '')}<br>
        文档端点免鉴权（schema 已在生成期脱敏）；金额单位统一「分」(long)，错误码三段式 A/B/C。</div>
      ${ddSec('分组说明', ddTable(['分组', '说明'], tags.map(t =>
        `<tr><td><b>${esc(t.name)}</b></td><td>${esc(t.description || '—')}</td></tr>`).join(''), '无分组'), tags.length + ' 组')}`;
  }
  const c = document.getElementById('odCashier');
  if (c) {
    const list = ppOpenCashier();
    const v = PPOpen.version || {};
    const additive = ((v.versions || []).find(x => x.frozen) || {}).additiveMethods || [];
    c.innerHTML = ddBox('info', `<b>收银台 OpenAPI（供 ride-pro 等业务方自渲染）</b>：additive ${additive.length || list.length} 个方法` +
        '（收银台 9 + 签约代扣 2），v1 冻结面不变。') +
      ddTable(['OpenAPI 方法', '所需 scope', '能力'], list.map(x =>
        `<tr><td class="pp-mono">${esc(x[0])}</td><td class="pp-mono">${esc((PPOpen.scopes || {})[x[0]] || x[1])}</td>
          <td>${esc(x[2])}</td></tr>`).join(''), '无') +
      `<div class="pp-obs">平台<b>不自建 C 端页面</b>：收银台 UI 由业务方渲染，平台只提供 OpenAPI + 双 SDK + 契约。
        多端适配（H5 / 小程序 / APP / PC）与主扫归一、验证四型、幂等回查口径见下方契约文档原文。</div>`;
  }
  /* 契约文档清单 */
  PPOpen.docList = dl.ok ? ((dl.data && dl.data.docs) || []) : [];
  const sel = document.getElementById('odDocSel');
  if (sel) {
    sel.innerHTML = PPOpen.docList.map(x => `<option value="${esc(x.name)}">${esc(x.name)} · ${esc(x.title || '')}</option>`).join('');
    if (!PPOpen.docList.length) sel.innerHTML = '<option value="">（文档清单不可用）</option>';
  }
  if (PPOpen.docList.length) await ppOdDoc(true);
  else {
    const meta = document.getElementById('odDocMeta');
    if (meta) meta.innerHTML = ddBox('warn', '文档清单端点不可用' + (dl.ok ? '' : '：' + esc(dl.err.message)));
  }
}
function ppOdPathRows(rows) {
  const t = document.getElementById('odPathTable'); if (!t) return;
  t.innerHTML = `<tr><th>HTTP</th><th>路径</th><th>分组</th><th>摘要</th><th>面</th><th>弃用</th></tr>` +
    (rows.length ? rows.map(p => `<tr><td>${ppBadge('kind', p.method)}</td>
      <td class="pp-mono" style="word-break:break-all">${esc(p.path)}</td><td>${esc(p.tag)}</td>
      <td>${esc(p.summary || '—')}</td>
      <td>${p.path.startsWith('/openapi') ? ppTag('on', '开放面') : p.path.startsWith('/api') ? ppTag('off', '控制台') : ppTag('off', '其它')}</td>
      <td>${p.deprecated ? ppTag('bad', '已弃用') : '—'}</td></tr>`).join('') : emptyRow(6, '无匹配端点'));
}
async function ppOdDoc(silent) {
  const name = ppVal('odDocSel');
  const box = document.getElementById('odDocBox'), meta = document.getElementById('odDocMeta');
  if (!name) { if (!silent) toast('请选择文档', false); return; }
  if (!silent) ppLoadingBox(box, '读取文档原文…');
  let text = PPOpen.docText[name];
  if (text === undefined) {
    const r = await ppRawTry('/api/platform/docs/' + encodeURIComponent(name));
    if (!r.ok) { box.innerHTML = ddBox('danger', '文档读取失败：' + esc(r.err.message)); return; }
    text = typeof r.data === 'string' ? r.data : (r.data && r.data.raw) || '';
    PPOpen.docText[name] = text;
  }
  const info = (PPOpen.docList || []).find(x => x.name === name) || {};
  if (meta) meta.innerHTML = ddKv([['文件', esc(name)], ['标题', esc(info.title || '—')],
    ['字节', ppNum(info.bytes)], ['SHA-256', esc(info.sha256 || '—')]]);
  box.innerHTML = ppOdMarkdown(text);
  if (!silent) toast('文档已渲染');
}
function ppOdDocDownload() {
  const name = ppVal('odDocSel');
  const text = PPOpen.docText[name];
  if (!text) { toast('请先渲染文档', false); return; }
  ppDlText(name, text, 'text/markdown');
}
/** 极简 markdown 渲染（先整体转义，再做块级/行内替换；不引入任何外部库）。 */
function ppOdMarkdown(src) {
  const lines = String(src || '').replace(/\r\n/g, '\n').split('\n');
  const out = [];
  let inCode = false, codeBuf = [], listType = null, tableBuf = [];
  const inline = s => esc(s)
    .replace(/`([^`]+)`/g, '<span class="pp-mono">$1</span>')
    .replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>')
    .replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (m, t, u) =>
      /^(https?:|\/|\.)/.test(u) ? `<a href="${u}" target="_blank" rel="noopener">${t}</a>` : t);
  const closeList = () => { if (listType) { out.push(listType === 'ul' ? '</ul>' : '</ol>'); listType = null; } };
  const flushTable = () => {
    if (!tableBuf.length) return;
    const rows = tableBuf.filter(r => !/^\s*\|?[\s:|-]+\|?\s*$/.test(r));
    const cells = r => r.replace(/^\s*\|/, '').replace(/\|\s*$/, '').split('|').map(c => c.trim());
    const head = cells(rows[0] || '');
    out.push('<div class="table-wrap"><table><tr>' + head.map(h => `<th>${inline(h)}</th>`).join('') + '</tr>' +
      rows.slice(1).map(r => '<tr>' + cells(r).map(c => `<td>${inline(c)}</td>`).join('') + '</tr>').join('') +
      '</table></div>');
    tableBuf = [];
  };
  lines.forEach(line => {
    if (/^```/.test(line)) {
      if (inCode) { out.push(`<div class="pp-code light">${esc(codeBuf.join('\n'))}</div>`); codeBuf = []; inCode = false; }
      else { closeList(); flushTable(); inCode = true; }
      return;
    }
    if (inCode) { codeBuf.push(line); return; }
    if (/^\s*\|.*\|\s*$/.test(line)) { closeList(); tableBuf.push(line); return; }
    flushTable();
    const h = /^(#{1,6})\s+(.*)$/.exec(line);
    if (h) { closeList(); out.push(`<h${Math.min(6, h[1].length + 2)} style="margin:12px 0 6px">${inline(h[2])}</h${Math.min(6, h[1].length + 2)}>`); return; }
    if (/^\s*([-*+])\s+/.test(line)) {
      if (listType !== 'ul') { closeList(); out.push('<ul style="margin:6px 0 6px 20px;line-height:1.9">'); listType = 'ul'; }
      out.push(`<li>${inline(line.replace(/^\s*([-*+])\s+/, ''))}</li>`); return;
    }
    if (/^\s*\d+\.\s+/.test(line)) {
      if (listType !== 'ol') { closeList(); out.push('<ol style="margin:6px 0 6px 20px;line-height:1.9">'); listType = 'ol'; }
      out.push(`<li>${inline(line.replace(/^\s*\d+\.\s+/, ''))}</li>`); return;
    }
    closeList();
    if (/^\s*(---|===)\s*$/.test(line)) { out.push('<hr style="margin:10px 0;border:0;border-top:1px solid #e2e8f0">'); return; }
    if (!line.trim()) { out.push(''); return; }
    out.push(`<div style="line-height:1.9;margin:4px 0">${inline(line)}</div>`);
  });
  if (inCode && codeBuf.length) out.push(`<div class="pp-code light">${esc(codeBuf.join('\n'))}</div>`);
  closeList(); flushTable();
  return out.join('');
}
function ppOdDownload() {
  if (!PPOpen.docs) { toast('契约尚未加载', false); return; }
  ppDlText('payment-pro-openapi.json', JSON.stringify(PPOpen.docs, null, 2), 'application/json');
}
function ppOdCsv() {
  const rows = LP['odPath'] ? lpVisible('odPath') : [];
  if (!rows.length) { toast('无端点可导出', false); return; }
  ppCsvLocal('openapi-endpoints.csv', ['HTTP', '路径', '分组', '摘要', '弃用'],
    rows.map(p => [p.method, p.path, p.tag, p.summary, p.deprecated ? 'Y' : 'N']));
}

/* ===================== 显式挂 window（历史坑：const 不进 window） ===================== */
window.PPOpen = PPOpen;
/* 兼容两种 nav 布线：loader:'PPOpen.loadApps'（点号路径）与 window[loader] 直取。 */
['loadApps', 'loadSandbox', 'loadQuota', 'loadVersion', 'loadDocs'].forEach(k => {
  window['PPOpen.' + k] = function () { return PPOpen[k](); };
});