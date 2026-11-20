/* =====================================================================
 * payment-pro 管理控制台 · 平台治理（IA 分组 12）· UI 泳道 B
 * ---------------------------------------------------------------------
 * 6 个实页（全局加载函数 = PPPlatform.loadXxx，文件末尾显式挂 window）：
 *   canary        灰度发布      GET /api/gray/status · /api/audit?path=/api/gray/*
 *                              POST /api/gray/config|rollback（step-up + GRAY 确认词）
 *   errcode       错误码表      GET /api/errors · /actuator/metrics/http.server.requests
 *   sentinel      限流熔断      GET /api/platform/sentinel（RuleManager 生效规则）
 *                              /actuator/metrics/payment.gateway.sentinel_block · /actuator/prometheus
 *   jobs          调度作业      GET /api/platform/domains · /api/platform/jobs?service=
 *                              POST /api/platform/jobs/{service}/{name}（step-up）
 *   shadow-diff   分片影子比对  GET /api/platform/shard · /api/platform/shard/compare
 *                              POST /api/platform/shard/toggle（step-up + SHARD 确认词 + 双确认）
 *   observability 可观测        GET /api/health · /api/stats · /actuator/health|metrics|prometheus|info
 * ---------------------------------------------------------------------
 * 【自挂载】index.html 里没有这 6 个 section（泳道 A 只负责 script 标签与 nav 布线），
 * 故每页首次加载时由 ppPfSec() 在 <main> 内建/填充 section#tab-<tab>，
 * 不依赖 init() 是否被调用 —— 加载函数自己保证 DOM + 列表注册 + 事件绑定就绪。
 * 顶层只声明；一切 DOM / 全局依赖都在函数体内（脚本先于 index.html 内联脚本加载）。
 * ===================================================================== */

/** 分片域（与 FundShardEndpointSupport 装配域一致）。 */
const PP_PF_SHARD_DOMAINS = ['accounting', 'settle', 'recon', 'trade', 'payment'];

/** Sentinel 部署基线（仅当 /api/platform/sentinel 不可用时兜底对照，明确标注非生效值）。 */
const PP_PF_SENTINEL_BASE = {
  flowRules: [
    { resource: '/openapi/trade.create', grade: 1, count: 100, limitApp: 'default', note: 'URL 级总闸：全 appId 合计 100 QPS' },
    { resource: '/openapi/trade.pay', grade: 1, count: 50, limitApp: 'default', note: 'URL 级总闸：50 QPS' },
    { resource: '/openapi/fund.topup', grade: 1, count: 30, limitApp: 'default', note: 'URL 级总闸：30 QPS' }
  ],
  paramFlowRules: [
    { resource: 'openapi:trade.create', paramIdx: 0, grade: 1, count: 20, durationInSec: 1, note: 'appId 热点参数限流 20 QPS' },
    { resource: 'openapi:trade.pay', paramIdx: 0, grade: 1, count: 10, durationInSec: 1, note: 'appId 热点参数限流 10 QPS' },
    { resource: 'openapi:fund.topup', paramIdx: 0, grade: 1, count: 8, durationInSec: 1, note: 'appId 热点参数限流 8 QPS' }
  ],
  degradeRules: [
    { resource: 'com.qoder.payment.facade.accounting.AccountingFacade', grade: 2, count: 5, timeWindow: 10, minRequestAmount: 5, statIntervalMs: 10000, note: '异常数熔断 → B0003 降级' },
    { resource: 'com.qoder.payment.facade.payment.PaymentFacade', grade: 2, count: 5, timeWindow: 10, minRequestAmount: 5, statIntervalMs: 10000, note: '异常数熔断 → B0003 降级' },
    { resource: 'com.qoder.payment.facade.trade.TradeFacade', grade: 2, count: 5, timeWindow: 10, minRequestAmount: 5, statIntervalMs: 10000, note: '异常数熔断 → B0003 降级' }
  ]
};

const PP_PF_SHARD_WARN = `<div class="box danger"><b>⚠⚠ 切流改变资金域读写路由，须用户明确拍板 ⚠⚠</b>
  <ul style="margin:8px 0 0 18px;line-height:1.9">
    <li><b>单库观察态</b>：双写开（mirrorLegacy=true）、影子比对 diff=0；读走单库，资金安全。</li>
    <li><b>切流</b> = shardingActive false→true、idMode SEQUENCE→SNOWFLAKE，账户余额 / 账务分录 / 结算记录读写路径全部改变。</li>
    <li><b>后端护栏</b>：toggle 前先做全量影子比对 + 唯一键冲突检查，<span class="pp-mono">diff≠0 或 conflicts≠0 直接拒绝</span>，不改动任何路由。</li>
    <li><b>回滚</b>：同一条 toggle（enabled=false），护栏一致。</li>
    <li><b>数据保护红线</b>：分片只 CREATE + INSERT 复制，原行保留，绝不下线单库、绝不删改既有数据。</li>
    <li>本页三道护栏：显著警示 → 输入 <span class="pp-mono">SHARD</span> → 用户拍板复确认 → step-up 二次认证。</li>
  </ul></div>`;

/* ---------- 页面骨架（自挂载用；只声明字符串，不触碰 DOM） ---------- */

const PP_PF_CANARY_HTML = `
  <div class="card"><h2>灰度发布<span class="section-desc">I-9 · 状态写共享 Redis，全网关实例即时生效；灰度不可达 fail-open 回稳定实例</span></h2>
    <div class="row" style="margin-bottom:10px">
      <button class="btn" id="btnPfCyRefresh">刷新状态</button>
      <button class="btn ghost" id="btnPfCyTrail">刷新发布留痕</button>
      <button class="btn ghost" id="btnPfCyCsv">导出留痕 CSV</button>
      <button class="btn ghost" data-pp-goto="observability">可观测</button>
      <button class="btn ghost" data-pp-goto="audit">审计日志</button>
    </div>
    <div id="pfCyBox"></div>
  </div>
  <div class="card"><h2>切流配置<span class="section-desc">敏感写：二次确认（输入 GRAY）+ X-Step-Up-Token</span></h2>
    <div class="row">
      <div class="field"><label>灰度开关</label><label style="font-weight:400"><input type="checkbox" id="pfCyEnabled"> 开启灰度</label></div>
      <div class="field"><label>权重（%）</label><input id="pfCyWeight" type="number" min="0" max="100" style="min-width:110px"></div>
      <div class="field"><label>灰度标签</label><input id="pfCyTag" placeholder="gray" style="min-width:120px"></div>
      <div class="field"><label>灰度实例基址</label><input id="pfCyTarget" placeholder="http://127.0.0.1:8081" style="min-width:240px"></div>
      <button class="btn danger" id="btnPfCyPublish" data-write="console:admin">下发切流</button>
      <button class="btn" id="btnPfCyRollback" data-write="console:admin">一键回滚</button>
    </div>
    <div class="hint">权重 0 或开关关闭 = 全部流量走稳定实例；开启必须填写灰度实例基址。回滚 = enabled=false + weight=0。</div>
    <div id="pfCyOpBox" style="margin-top:10px"></div>
  </div>
  <div class="card"><h2>发布留痕<span class="section-desc">来自审计日志 /api/audit（服务端分页，真实操作人与 traceId）</span></h2>
    <div class="row">
      <div class="field"><label>操作类型</label><select id="pfCyTrailPath">
        <option value="">全部灰度操作</option><option value="/api/gray/config">切流 config</option>
        <option value="/api/gray/rollback">回滚 rollback</option></select></div>
      <div class="field"><label>操作人</label><input id="pfCyTrailUser" placeholder="admin" style="min-width:140px"></div>
    </div>
    <div class="table-wrap"><table id="pfCyTrailTable"></table></div>
    <div class="row pager" id="pfCyTrailPager"></div>
  </div>`;

const PP_PF_ERRCODE_HTML = `
  <div class="card"><h2>错误码表<span class="section-desc">G-3 · 单一事实来源 /api/errors；纯只读，查询/过滤/导出不需二次认证</span></h2>
    <div class="row" style="margin-bottom:10px">
      <button class="btn" id="btnPfEcRefresh">刷新码表</button>
      <button class="btn ghost" id="btnPfEcTrend">刷新状态码趋势</button>
      <button class="btn ghost" id="btnPfEcCsv">导出 CSV</button>
      <button class="btn ghost" data-pp-goto="observability">可观测</button>
    </div>
    <div id="pfEcBox"></div>
    <div id="pfEcTrendBox" style="margin-top:10px"></div>
  </div>
  <div class="card"><h2>码表明细<span class="section-desc">点错误码打开详情抽屉（含 HTTP 实时计数与处置建议）</span></h2>
    <div class="table-wrap"><table id="pfEcTable"></table></div>
  </div>`;

const PP_PF_SENTINEL_HTML = `
  <div class="card"><h2>限流熔断<span class="section-desc">I-7 · 生效规则直读 RuleManager；拒绝发生在受理前，不落单、不留 PROCESSING 悬挂</span></h2>
    <div class="row" style="margin-bottom:10px">
      <button class="btn" id="btnPfSnRefresh">刷新规则与指标</button>
      <button class="btn ghost" id="btnPfSnCsv">导出规则 CSV</button>
      <button class="btn ghost" data-pp-goto="open-quota">appId 配额</button>
      <button class="btn ghost" data-pp-goto="observability">可观测</button>
    </div>
    <div id="pfSnBox"></div>
  </div>
  <div class="card"><h2>流控规则（URL 级 QPS 总闸）</h2>
    <div class="table-wrap"><table id="pfSnFlowTable"></table></div></div>
  <div class="card"><h2>热点参数限流（单 appId）</h2>
    <div class="table-wrap"><table id="pfSnParamTable"></table></div></div>
  <div class="card"><h2>熔断降级规则（Dubbo 接口级）</h2>
    <div class="table-wrap"><table id="pfSnDegradeTable"></table></div></div>`;

const PP_PF_JOBS_HTML = `
  <div class="card"><h2>调度作业<span class="section-desc">I-3 · XXL-Job 托管 + 本地 @Scheduled 兜底 + ShedLock 跨实例互斥</span></h2>
    <div class="row">
      <div class="field"><label>业务域</label><select id="pfJbDomain" style="min-width:280px"></select></div>
      <button class="btn" id="btnPfJbRefresh">读取作业态</button>
      <button class="btn ghost" id="btnPfJbCsv">导出作业 CSV</button>
      <a class="btn small ghost" id="pfJbXxlLink" href="http://127.0.0.1:8094/xxl-job-admin" target="_blank" rel="noopener">XXL-Job 控制台</a>
    </div>
    <div class="hint">执行器端口：accounting 9994 · trade 9995 · settle 9996 · recon 9997 · payment 9998 · claim 9999。
      域运维面经网关同源代理 <span class="pp-mono">/api/platform/jobs</span> 读取（不再需要粘贴导入）。</div>
    <div id="pfJbBox" style="margin-top:10px"></div>
  </div>
  <div class="card"><h2>手动触发<span class="section-desc">敏感写：二次确认 + X-Step-Up-Token；作业可能真实动账</span></h2>
    <div class="row">
      <div class="field"><label>分片序号 shardIndex</label><input id="pfJbShardIndex" type="number" min="0" value="0" style="min-width:110px"></div>
      <div class="field"><label>分片总数 shardTotal</label><input id="pfJbShardTotal" type="number" min="1" value="1" style="min-width:110px"></div>
      <div class="field"><label>param</label><input id="pfJbParam" placeholder="可空；如会计日 20250101" style="min-width:220px"></div>
    </div>
    <div id="pfJbOpBox" style="margin-top:10px"></div>
  </div>
  <div class="card"><h2>作业清单<span class="section-desc">点作业名打开详情抽屉（触发计数 / 最近结果 / 锁记录）</span></h2>
    <div class="table-wrap"><table id="pfJbJobTable"></table></div></div>
  <div class="card"><h2>执行日志<span class="section-desc">最近执行结果（来源 REMOTE / LOCAL / CONTROL）</span></h2>
    <div class="table-wrap"><table id="pfJbLogTable"></table></div></div>
  <div class="card"><h2>ShedLock 分布式锁<span class="section-desc">锁表实时行</span></h2>
    <div class="table-wrap"><table id="pfJbLockTable"></table></div></div>`;

const PP_PF_SHARD_HTML = `
  <div class="card"><h2>分片与影子比对<span class="section-desc">分片已上线（CREATE + INSERT 复制，原行保留）；影子双跑逐行逐值比对</span></h2>
    <div class="row">
      <div class="field"><label>资金域</label><select id="pfSdDomain" style="min-width:280px"></select></div>
      <button class="btn" id="btnPfSdRefresh">读取分片态</button>
      <button class="btn ghost" id="btnPfSdCompare">重新影子比对</button>
      <button class="btn ghost" id="btnPfSdCsv">导出比对 CSV</button>
      <button class="btn ghost" data-pp-goto="recon-shard">对账分片调度</button>
    </div>
    <div id="pfSdWarn" style="margin-top:10px"></div>
    <div id="pfSdBox" style="margin-top:10px"></div>
  </div>
  <div class="card"><h2>影子比对结果<span class="section-desc">diffTotal 必须为 0 才具备切流前置条件</span></h2>
    <div id="pfSdCmpBox"></div>
    <div class="table-wrap" style="margin-top:10px"><table id="pfSdTableTable"></table></div>
  </div>
  <div class="card"><h2>物理行数<span class="section-desc">单库表 vs 各分片表（只读核对）</span></h2>
    <div class="table-wrap"><table id="pfSdPhysTable"></table></div></div>
  <div class="card"><h2>切流 / 回滚<span class="section-desc">高危：三道护栏 + 后端前置比对护栏</span></h2>
    <div class="row">
      <div class="field"><label>目标路由</label><label style="font-weight:400"><input type="checkbox" id="pfSdEnabled"> 切流到分片表（取消勾选 = 回滚单库）</label></div>
      <button class="btn danger" id="btnPfSdToggle" data-write="console:admin">执行 toggle</button>
    </div>
    <div id="pfSdToggleTarget" class="hint"></div>
    <div id="pfSdOpBox" style="margin-top:10px"></div>
  </div>`;

const PP_PF_OBS_HTML = `
  <div class="card"><h2>可观测中心<span class="section-desc">全栈健康（Dubbo 探活）+ 网关依赖 + Micrometer/Prometheus 指标 + 告警判定</span></h2>
    <div class="row" style="margin-bottom:10px">
      <button class="btn" id="btnPfObRefresh">全量刷新</button>
      <button class="btn ghost" id="btnPfObProm">下载 Prometheus 快照</button>
      <button class="btn ghost" id="btnPfObCsv">导出健康 CSV</button>
      <a class="btn small ghost" href="/actuator/health" target="_blank" rel="noopener">actuator/health</a>
      <a class="btn small ghost" href="/actuator/prometheus" target="_blank" rel="noopener">Prometheus 端点</a>
      <button class="btn ghost" data-pp-goto="sentinel">限流熔断</button>
      <button class="btn ghost" data-pp-goto="jobs">调度作业</button>
    </div>
    <div id="pfObBox"></div>
  </div>
  <div class="card"><h2>告警判定<span class="section-desc">死信 / 失败 / 差错 / 限流计数器 + 服务探活</span></h2>
    <div id="pfObAlertBox"></div>
    <div class="table-wrap" style="margin-top:10px"><table id="pfObAlertTable"></table></div>
  </div>
  <div class="card"><h2>服务健康<span class="section-desc">13 进程；actuator/HTTP 端口 = Dubbo 端口 - 2800</span></h2>
    <div class="table-wrap"><table id="pfObHealthTable"></table></div></div>
  <div class="card"><h2>指标目录与下钻<span class="section-desc">点指标名打开测量值抽屉；下拉可直接下钻</span></h2>
    <div class="row">
      <div class="field"><label>指标下钻</label><select id="pfObMetricSel" style="min-width:340px"></select></div>
      <button class="btn small" id="btnPfObMetric">读取测量值</button>
    </div>
    <div id="pfObMetricBox" style="margin-top:10px"></div>
    <div class="table-wrap" style="margin-top:10px"><table id="pfObMetricTable"></table></div>
  </div>`;

/* ---------- 模块状态 ---------- */
const PPPlatform = {
  bound: {},
  gray: null, grayTrail: [], grayPage: 1, graySize: 20, grayTotal: 0,
  errors: [], errTrend: [], sentinel: null, sentinelLive: false, blockByType: {}, promRaw: '',
  health: [], domains: [], metrics: [],
  job: null, jobDomain: '', shard: null, shardCompare: null, shardDomain: '',

  /** 幂等初始化：注册抽屉 kind；DOM 与列表由每页 ensure 自挂载（不依赖调用时机）。 */
  init() {
    ppPfDdRegister();
  },

  /* ---------- 6 个全局加载入口（泳道 A 按此名布线） ---------- */
  loadCanary() { ppPfCanaryEnsure(); return ppPfCanaryLoad(); },
  loadErrcode() { ppPfErrEnsure(); return ppPfErrLoad(); },
  loadSentinel() { ppPfSentinelEnsure(); return ppPfSentinelLoad(); },
  loadJobs() { ppPfJobsEnsure(); return ppPfJobsLoad(); },
  loadShadowDiff() { ppPfShardEnsure(); return ppPfShardLoad(); },
  loadObservability() { ppPfObsEnsure(); return ppPfObsLoad(); }
};

/* ===================== 自挂载与通用工具 ===================== */

/** 建/填 section#tab-<tab>：已有真页（含 marker）则不动，否则注入本页骨架。 */
function ppPfSec(tab, marker, html) {
  ppPfDdRegister();
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
/** 抽屉 kind 注册（幂等；不依赖 init 调用时机）。 */
function ppPfDdRegister() {
  if (PPPlatform.bound.dd) return;
  PPPlatform.bound.dd = true;
  Object.assign(DD_KINDS, {
    pfGrayLog: '灰度发布留痕', pfErr: '错误码', pfRule: '限流熔断规则',
    pfJob: '调度作业', pfShardTable: '分片影子比对', pfMetric: '指标'
  });
  Object.assign(DD_RENDER, {
    pfGrayLog: ppPfDdGrayLog, pfErr: ppPfDdErr, pfRule: ppPfDdRule,
    pfJob: ppPfDdJob, pfShardTable: ppPfDdShardTable, pfMetric: ppPfDdMetric
  });
}
/** 只注册一次的客户端分页列表；若注册时表还不存在则补挂载过滤条与分页条。 */
function ppPfLp(key, spec) {
  if (!LP[key]) { if (document.getElementById(spec.table)) lpDef(key, spec); }
  else if (!LP[key].mounted) lpMount(key);
}
/** 事件绑定（按 id，存在即绑；每页只绑一次）。 */
function ppPfOn(map) {
  Object.entries(map).forEach(([id, fn]) => {
    const el = document.getElementById(id);
    if (el) el.onclick = fn;
  });
}
function ppPfOnce(key, fn) { if (PPPlatform.bound[key]) return; PPPlatform.bound[key] = true; fn(); }

/** 域运维面清单（端口由 /api/health 推导；优先用后端 /api/platform/domains）。 */
async function ppPfLoadDomains() {
  const r = await tryApi('/platform/domains');
  if (r.ok && Array.isArray(r.data) && r.data.length) {
    PPPlatform.domains = r.data.map(d => ({
      service: d.service, domain: d.domain, port: d.port, ok: d.ok,
      httpPort: d.httpPort, base: d.base || ('http://127.0.0.1:' + d.httpPort)
    }));
    return PPPlatform.domains;
  }
  const h = await tryApi('/health');
  if (h.ok) {
    PPPlatform.health = h.data || [];
    PPPlatform.domains = PPPlatform.health.filter(x => Number(x.port) >= 20000).map(x => ({
      service: x.service, domain: x.domain, port: Number(x.port), ok: x.ok,
      httpPort: Number(x.port) - 2800, base: 'http://127.0.0.1:' + (Number(x.port) - 2800)
    }));
  }
  return PPPlatform.domains;
}
function ppPfDomain(service) { return (PPPlatform.domains || []).find(d => d.service === service) || null; }
function ppPfFillDomains(selId, filter) {
  const sel = document.getElementById(selId);
  if (!sel) return;
  const list = (PPPlatform.domains || []).filter(filter || (() => true));
  const cur = sel.value;
  sel.innerHTML = list.map(d => `<option value="${esc(d.service)}">${esc(d.domain || d.service)} · ${esc(d.service)} :${d.httpPort}</option>`).join('');
  if (cur && list.some(d => d.service === cur)) sel.value = cur;
}
/** Prometheus 文本求和 / 按标签分组（无标签返回 null 表示未注册）。 */
function ppPfPromSum(txt, name) {
  if (!txt) return null;
  const re = new RegExp('^' + name.replace(/[.]/g, '\\.') + '(\\{[^}]*\\})?\\s+([0-9.eE+-]+)$', 'gm');
  let m, sum = null;
  while ((m = re.exec(txt))) sum = (sum || 0) + Number(m[2]);
  return sum;
}
function ppPfPromBy(txt, name, tag) {
  const out = {};
  if (!txt) return out;
  const re = new RegExp('^' + name.replace(/[.]/g, '\\.') + '\\{([^}]*)\\}\\s+([0-9.eE+-]+)$', 'gm');
  let m;
  while ((m = re.exec(txt))) {
    const t = new RegExp(tag + '="([^"]*)"').exec(m[1]);
    const k = t ? t[1] : '(无标签)';
    out[k] = (out[k] || 0) + Number(m[2]);
  }
  return out;
}
async function ppPfProm() {
  const r = await ppRawTry('/actuator/prometheus');
  const txt = r.ok ? (typeof r.data === 'string' ? r.data : (r.data && r.data.raw) || '') : '';
  PPPlatform.promRaw = txt;
  return txt;
}

/* ===================== 页 1 · 灰度发布 ===================== */

function ppPfCanaryEnsure() {
  ppPfSec('canary', 'pfCyBox', PP_PF_CANARY_HTML);
  ppPfOnce('canary', () => {
    ppPfOn({
      btnPfCyRefresh: () => ppPfCanaryLoad(),
      btnPfCyTrail: () => ppPfGrayTrail(1),
      btnPfCyCsv: () => ppPfGrayCsv(),
      btnPfCyPublish: () => ppPfGrayPublish(),
      btnPfCyRollback: () => ppPfGrayRollback()
    });
    const p = document.getElementById('pfCyTrailPath');
    if (p) p.onchange = () => ppPfGrayTrail(1);
    const u = document.getElementById('pfCyTrailUser');
    if (u) u.onkeydown = ev => { if (ev.key === 'Enter') ppPfGrayTrail(1); };
  });
}
async function ppPfCanaryLoad() {
  const box = document.getElementById('pfCyBox');
  ppLoadingBox(box, '加载灰度状态…');
  const r = await tryApi('/gray/status');
  if (!r.ok) { box.innerHTML = ddBox('danger', '灰度状态加载失败：' + esc(r.err.message)); return; }
  const g = r.data || {};
  PPPlatform.gray = g;
  const set = (k, v) => { const e = document.getElementById(k); if (e && document.activeElement !== e) e.value = v == null ? '' : v; };
  set('pfCyWeight', g.weight); set('pfCyTag', g.tag); set('pfCyTarget', g.target);
  const ck = document.getElementById('pfCyEnabled');
  if (ck) ck.checked = !!g.enabled;
  const weight = Number(g.weight || 0);
  box.innerHTML = `<div class="stat-cards">
      <div class="stat-card ${g.enabled ? 'warn' : 'ok'}"><div class="label">灰度开关</div><div class="value">${g.enabled ? '已开启' : '未启用'}</div></div>
      <div class="stat-card"><div class="label">灰度权重</div><div class="value">${weight} %</div></div>
      <div class="stat-card"><div class="label">稳定实例流量</div><div class="value">${100 - weight} %</div></div>
      <div class="stat-card accent"><div class="label">受理实例</div><div class="value" style="font-size:15px">${esc(g.instanceId || '—')}</div></div>
      <div class="stat-card"><div class="label">状态源</div><div class="value" style="font-size:15px">${esc(g.stateSource || '—')}</div></div>
    </div>
    ${g.enabled
      ? ddBox('warn', `<b>灰度已开启</b>：${weight}% 流量进入 <span class="pp-mono">${esc(g.target || '（未配置）')}</span>。
          回滚点「一键回滚」即 enabled=false + weight=0（同样需二次认证）。`)
      : ddBox('info', `<b>灰度未启用（如实展示，非占位数据）</b>：enabled=false / weight=0，
          <b>全部流量走稳定实例</b>。此前因数据保护未真启灰度，故当前没有真实灰度批次；
          下方「发布留痕」是历史 config/rollback 操作的真实审计记录。`)}
    ${ddKv([['本实例标识', esc(g.instanceId || '—')], ['灰度开关', g.enabled ? ppTag('bad', '已开启') : ppTag('on', '未启用')],
      ['灰度权重', weight + ' %'], ['灰度标签', esc(g.tag || 'gray')],
      ['灰度实例基址', esc(g.target || '—')], ['状态源', ppBadge('kind', g.stateSource || '—')]])}
    <div style="margin-top:6px"><b style="font-size:12px">流量分配（灰度 / 稳定）</b>${ppMeter(weight, 100)}</div>
    ${ddSec('分流规则（后端原文）', `<div class="pp-code light">${esc(g.rules || '—')}</div>`)}
    ${ddSec('落点核对响应头', `<div class="pp-code light">${esc(g.headers || '—')}</div>`)}
    <div class="pp-obs">状态写共享 Redis，<b>全实例即时生效</b>；灰度实例不可达时 fail-open 回稳定实例
      （<span class="pp-mono">X-Gray-Decision=stable-failopen</span>），不阻断支付。</div>`;
  await ppPfGrayTrail(PPPlatform.grayPage || 1, true);
}
async function ppPfGrayTrail(page, silent) {
  PPPlatform.grayPage = page || 1;
  const t = document.getElementById('pfCyTrailTable');
  if (!t) return;
  loadingRow(t, 8, '加载灰度发布留痕…');
  const p = new URLSearchParams({ action: 'POST', page: String(PPPlatform.grayPage), size: String(PPPlatform.graySize) });
  const path = ppVal('pfCyTrailPath');
  p.set('path', path || '/api/gray/');
  const user = ppVal('pfCyTrailUser');
  if (user) p.set('username', user);
  const r = await tryApi('/audit?' + p.toString());
  if (!r.ok) { errorRow(t, 8, r.err.message); return; }
  const d = r.data || {};
  let items = d.items || [];
  if (!path) items = items.filter(x => String(x.path || '').startsWith('/api/gray/'));
  PPPlatform.grayTrail = items;
  PPPlatform.grayTotal = d.total || items.length;
  t.innerHTML = `<tr><th>留痕 ID</th><th>操作</th><th>操作人</th><th>业务号</th><th>结果</th><th>来源 IP</th><th>traceId</th><th>时间</th></tr>` +
    (items.length ? items.map(x => `<tr>
      <td>${ddLink('pfGrayLog', x.id, x.id)}</td>
      <td class="pp-mono">${esc(String(x.path || '').replace('/api/gray/', ''))}</td>
      <td>${esc(x.username || '—')}</td><td class="pp-mono">${esc(x.bizNo || '—')}</td>
      <td>${ppBadge(String(x.result) === '200' ? 'SUCCESS' : 'FAILED', x.result)}</td>
      <td class="pp-mono">${esc(x.ip || '—')}</td>
      <td class="pp-mono" style="max-width:180px;word-break:break-all">${esc(x.traceId || '—')}</td>
      <td>${fmtTime(x.createdAt)}</td></tr>`).join('')
      : emptyRow(8, '暂无灰度操作留痕（从未下发过切流 / 回滚）'));
  renderPager(document.getElementById('pfCyTrailPager'), PPPlatform.grayPage, PPPlatform.graySize, PPPlatform.grayTotal,
    p2 => ppPfGrayTrail(p2), sz => { PPPlatform.graySize = sz; ppPfGrayTrail(1); });
  if (!silent) toast(`发布留痕：共 ${PPPlatform.grayTotal} 条`);
}
async function ppPfGrayPublish() {
  const enabled = ppChecked('pfCyEnabled'), weight = ppInt('pfCyWeight', 0);
  const tag = ppVal('pfCyTag') || 'gray', target = ppVal('pfCyTarget');
  if (weight < 0 || weight > 100) { toast('权重需在 0~100', false); return; }
  if (enabled && !target) { toast('开启灰度必须填写灰度实例基址', false); return; }
  const g = PPPlatform.gray || {};
  const html = `<div class="box danger"><b>⚠ 灰度切流：影响真实请求落点</b></div>
    ${ddKv([['本次权重', weight + ' %'], ['当前权重', String(g.weight == null ? '—' : g.weight) + ' %'],
      ['开关', enabled ? '开启' : '关闭'], ['标签', esc(tag)], ['目标', esc(target || '—')],
      ['生效范围', '共享 Redis → 全部网关实例即时生效']])}
    <div class="pp-note">灰度实例不可达时 fail-open 回稳定实例，不阻断支付；回滚 = 一键回滚（enabled=false + weight=0）。</div>`;
  const c = await askConfirm({ title: '灰度发布 / 调整', html, okText: '确认切流',
    input: { label: '请输入 GRAY 以确认切流', placeholder: 'GRAY', required: true } });
  if (!c) return;
  if (String(c.value).trim().toUpperCase() !== 'GRAY') { toast('确认词不正确，已取消（未发起请求）', false); return; }
  const box = document.getElementById('pfCyOpBox');
  try {
    const r = await sensitiveApi('/gray/config', 'POST', { enabled, weight, tag, target }, html);
    ppResult(box, '灰度配置已下发', r); toast('灰度配置已下发'); await ppPfCanaryLoad();
  } catch (e) { ppErrBox(box, '灰度发布', e); }
}
async function ppPfGrayRollback() {
  const g = PPPlatform.gray || {};
  const html = `<div class="box danger"><b>⚠ 一键回滚灰度：全部流量回稳定实例</b></div>
    ${ddKv([['当前状态', g.enabled ? '灰度已开启' : '灰度未启用'],
      ['当前权重', String(g.weight == null ? '—' : g.weight) + ' %'], ['灰度目标', esc(g.target || '—')],
      ['回滚结果', 'enabled=false + weight=0（全实例即时生效）']])}
    <div class="pp-note">已在灰度实例处理中的请求不会被打断。</div>`;
  const c = await askConfirm({ title: '一键回滚灰度', html, okText: '确认回滚' });
  if (!c) return;
  const box = document.getElementById('pfCyOpBox');
  try {
    const r = await sensitiveApi('/gray/rollback', 'POST', {}, html);
    ppResult(box, '灰度已回滚', r); toast('灰度已回滚'); await ppPfCanaryLoad();
  } catch (e) { ppErrBox(box, '灰度回滚', e); }
}
function ppPfGrayCsv() {
  if (!PPPlatform.grayTrail.length) { toast('当前页无留痕可导出', false); return; }
  ppCsvLocal('gray-trail-p' + PPPlatform.grayPage + '.csv',
    ['留痕ID', '路径', '操作人', '业务号', '结果', 'IP', 'traceId', '时间'],
    PPPlatform.grayTrail.map(x => [x.id, x.path, x.username, x.bizNo, x.result, x.ip, x.traceId, fmtTime(x.createdAt)]));
}
async function ppPfDdGrayLog(id) {
  const row = (PPPlatform.grayTrail || []).find(x => x.id === id) || {};
  const r = await tryApi('/gray/status');
  const g = r.ok ? (r.data || {}) : (PPPlatform.gray || {});
  return ddKv([['留痕 ID', esc(id)], ['操作路径', esc(row.path || '—')], ['操作人', esc(row.username || '—')],
      ['HTTP 结果', ppBadge(String(row.result) === '200' ? 'SUCCESS' : 'FAILED', row.result || '—')],
      ['业务号', esc(row.bizNo || '—')], ['来源 IP', esc(row.ip || '—')],
      ['traceId', esc(row.traceId || '—')], ['操作时间', fmtTime(row.createdAt)]]) +
    ddSec('当前灰度态（该操作之后的最新状态）', ddKv([['开关', g.enabled ? ppTag('bad', '已开启') : ppTag('on', '未启用')],
      ['权重', String(g.weight == null ? '—' : g.weight) + ' %'], ['目标', esc(g.target || '—')],
      ['受理实例', esc(g.instanceId || '—')], ['状态源', esc(g.stateSource || '—')]])) +
    ddBox('plain', '审计留痕不含请求体明文（脱敏口径），切流参数以当前灰度态与操作时间对照；' +
      '完整请求体排查请用 traceId 在网关日志检索。') +
    `<div class="pp-inline" style="margin-top:8px"><button class="btn small ghost" data-pp-goto="audit">审计日志</button>
      <button class="btn small ghost" data-pp-goto="observability">可观测</button></div>`;
}

/* ===================== 页 2 · 错误码表 ===================== */

function ppPfErrEnsure() {
  ppPfSec('errcode', 'pfEcBox', PP_PF_ERRCODE_HTML);
  ppPfLp('pfEc', { table: 'pfEcTable', render: ppPfErrRows, filters: [
    { k: 'kw', label: '关键字', ph: '错误码 / 文案 / 归因（如 A0403）', fields: ['code', 'message', 'description'] },
    { k: 'category', label: '类别', type: 'select', field: 'category',
      opts: [opt('', '全部'), opt('SUCCESS', 'SUCCESS 成功'), opt('A', 'A 用户端'), opt('B', 'B 业务/系统'), opt('C', 'C 第三方/下游')] },
    { k: 'httpStatus', label: 'HTTP', type: 'select', field: 'httpStatus',
      opts: [opt('', '全部')].concat([200, 400, 401, 403, 404, 405, 409, 413, 429, 500, 502, 503, 504].map(s => opt(String(s), String(s)))) },
    { k: 'face', label: '契约面', type: 'select', field: 'face', opts: [opt('', '全部'), opt('BASE', '基础码表'), opt('ADDITIVE', 'additive 追加')] } ] });
  ppPfOnce('errcode', () => ppPfOn({
    btnPfEcRefresh: () => ppPfErrLoad(),
    btnPfEcTrend: () => ppPfErrTrend(),
    btnPfEcCsv: () => ppPfErrCsv()
  }));
}
async function ppPfErrLoad() {
  lpLoading('pfEc', 6, '加载错误码表…');
  const r = await tryApi('/errors');
  if (!r.ok) { lpError('pfEc', 6, r.err.message); return; }
  const d = r.data || {};
  PPPlatform.errors = (d.codes || []).map(c => Object.assign({ face: 'BASE' }, c))
    .concat((d.additive || []).map(c => Object.assign({ face: 'ADDITIVE' }, c)));
  lpSet('pfEc', PPPlatform.errors);
  const box = document.getElementById('pfEcBox');
  if (box) {
    const cnt = k => PPPlatform.errors.filter(c => c.category === k).length;
    box.innerHTML = `<div class="stat-cards">
        <div class="stat-card accent"><div class="label">错误码总数</div><div class="value">${PPPlatform.errors.length}</div></div>
        <div class="stat-card"><div class="label">A 用户端</div><div class="value">${cnt('A')}</div></div>
        <div class="stat-card"><div class="label">B 业务/系统</div><div class="value">${cnt('B')}</div></div>
        <div class="stat-card"><div class="label">C 下游</div><div class="value">${cnt('C')}</div></div>
        <div class="stat-card ok"><div class="label">additive 追加</div><div class="value">${(d.additive || []).length}</div></div>
      </div>
      ${ddKv([['错误响应体字段', esc((d.errorBody || []).join(' / '))], ['成功响应口径', esc(d.successStyle || '—')]])}
      <div class="pp-obs">本页<b>纯只读</b>：查询 / 过滤 / 导出不做二次确认、不需 step-up。
        错误码实时分布见下方「状态码趋势」（Micrometer http.server.requests 按 status 标签下钻）。</div>`;
  }
  await ppPfErrTrend(true);
}
function ppPfErrRows(rows) {
  const t = document.getElementById('pfEcTable');
  if (!t) return;
  t.innerHTML = `<tr><th>错误码</th><th>对外文案</th><th>HTTP</th><th>类别</th><th>契约面</th><th>归因 / 触发场景</th></tr>` +
    (rows.length ? rows.map(c => `<tr>
      <td>${ddLink('pfErr', c.code, c.code)}</td><td>${esc(c.message || '—')}</td>
      <td>${ppBadge(Number(c.httpStatus) < 400 ? 'SUCCESS' : Number(c.httpStatus) >= 500 ? 'FAILED' : 'PENDING', c.httpStatus)}</td>
      <td>${ppBadge('kind', c.category || '—')}</td>
      <td>${c.face === 'ADDITIVE' ? ppTag('on', 'additive') : ppTag('off', '基础')}</td>
      <td>${esc(c.description || '—')}</td></tr>`).join('') : emptyRow(6, '无匹配错误码（可调整关键字 / 类别）'));
}
async function ppPfErrTrend(silent) {
  const box = document.getElementById('pfEcTrendBox');
  if (!silent) ppLoadingBox(box, '按 HTTP 状态标签下钻实时计数…');
  const base = await ppRawTry('/actuator/metrics/http.server.requests');
  if (!base.ok) { box.innerHTML = ddBox('warn', 'HTTP 指标不可用：' + esc(base.err.message)); return; }
  const tags = (base.data && base.data.availableTags) || [];
  const statusTag = tags.find(t => t.tag === 'status');
  const statuses = (statusTag && statusTag.values) || [];
  if (!statuses.length) {
    box.innerHTML = ddBox('plain', '自进程启动以来尚无 HTTP 请求样本（指标已注册但无 status 标签值）。');
    return;
  }
  const results = await Promise.all(statuses.map(s =>
    ppRawTry('/actuator/metrics/http.server.requests?tag=status:' + encodeURIComponent(s))));
  const rows = statuses.map((s, i) => {
    const r = results[i];
    const meas = r.ok ? ((r.data && r.data.measurements) || []) : [];
    const count = meas.find(m => m.statistic === 'COUNT');
    const total = meas.find(m => m.statistic === 'TOTAL_TIME');
    const max = meas.find(m => m.statistic === 'MAX');
    const n = count ? Number(count.value) : 0;
    const code = (PPPlatform.errors || []).find(c => Number(c.httpStatus) === Number(s));
    return { status: s, count: n, seconds: total ? Number(total.value) : 0,
      max: max ? Number(max.value) : 0, code: code ? code.code : '', message: code ? code.message : '' };
  }).sort((a, b) => b.count - a.count);
  PPPlatform.errTrend = rows;
  const sum = rows.reduce((s, x) => s + x.count, 0);
  const err = rows.filter(x => Number(x.status) >= 400).reduce((s, x) => s + x.count, 0);
  box.innerHTML = `<div class="stat-cards">
      <div class="stat-card accent"><div class="label">HTTP 请求累计</div><div class="value">${Math.round(sum)}</div></div>
      <div class="stat-card ${err ? 'warn' : 'ok'}"><div class="label">4xx/5xx 累计</div><div class="value">${Math.round(err)}</div></div>
      <div class="stat-card"><div class="label">错误率</div><div class="value">${sum ? (err / sum * 100).toFixed(2) : '0.00'} %</div></div>
      <div class="stat-card"><div class="label">状态种类</div><div class="value">${rows.length}</div></div>
    </div>` +
    ddTable(['HTTP 状态', '对应错误码', '对外文案', '请求数', '占比', '累计耗时(s)', '最大耗时(s)'],
      rows.map(x => `<tr><td>${ppBadge(Number(x.status) < 400 ? 'SUCCESS' : Number(x.status) >= 500 ? 'FAILED' : 'PENDING', x.status)}</td>
        <td>${x.code ? ddLink('pfErr', x.code, x.code) : '<span class="muted">—</span>'}</td>
        <td>${esc(x.message || '—')}</td><td class="num">${Math.round(x.count)}</td>
        <td class="num">${sum ? (x.count / sum * 100).toFixed(1) : '0.0'}%</td>
        <td class="num">${x.seconds.toFixed(3)}</td><td class="num">${x.max.toFixed(3)}</td></tr>`).join(''), '无请求样本');
  if (!silent) toast('状态码趋势已刷新');
}
function ppPfErrCsv() {
  const rows = lpVisible('pfEc');
  if (!rows.length) { toast('无数据可导出', false); return; }
  ppCsvLocal('error-codes.csv', ['错误码', '对外文案', 'HTTP', '类别', '契约面', '归因'],
    rows.map(c => [c.code, c.message, c.httpStatus, c.category, c.face, c.description]));
}
async function ppPfDdErr(code) {
  if (!PPPlatform.errors.length) {
    const r = await tryApi('/errors');
    if (r.ok) {
      const d = r.data || {};
      PPPlatform.errors = (d.codes || []).map(c => Object.assign({ face: 'BASE' }, c))
        .concat((d.additive || []).map(c => Object.assign({ face: 'ADDITIVE' }, c)));
    }
  }
  const c = (PPPlatform.errors || []).find(x => x.code === code);
  if (!c) return ddBox('warn', '码表无此错误码：' + esc(code));
  const live = await ppRawTry('/actuator/metrics/http.server.requests?tag=status:' + encodeURIComponent(String(c.httpStatus)));
  const meas = live.ok ? ((live.data && live.data.measurements) || []) : [];
  const count = meas.find(m => m.statistic === 'COUNT');
  const advice = {
    A0400: '核对入参与 JSON 合法性；OpenAPI 侧检查验签 canonical 与毫秒 timestamp（±300s）。',
    A0401: '控制台 Bearer 缺失/失效 → 重新登录；OpenAPI 侧检查 appId/appSecret 与 access_token。',
    A0403: 'RBAC 权限点不足，或敏感操作缺 X-Step-Up-Token（POST /api/security/step-up 用本人口令换取，300s）。',
    A0404: '单据/资源不存在：核对单号与环境（沙箱 SBX* 与生产命名空间不互通）。',
    A0409: '幂等冲突：同 requestId/nonce 重复提交，或唯一键冲突（重复 nonce → 防重放）。',
    A0428: '大额双人复核未通过（REVIEW_PENDING），未动账；需另一人在「大额复核」批准。',
    A0429: '限流/配额拒绝（受理前拒绝，不落单）：核对 Sentinel 规则与 appId 日配额。',
    B0001: '业务规则拒绝：核对账户状态/余额/限额与风控决策。',
    B0002: '系统内部错误：用 traceId 检索网关与域日志定位根因。',
    B0003: '下游熔断降级：核对域健康与熔断规则窗口，恢复后自动闭合。',
    C0001: '第三方/下游调用失败：核对渠道适配器与对端可用性。'
  }[c.code] || '按归因描述排查；用响应体 traceId 在网关日志检索完整链路。';
  return ddKv([['错误码', esc(c.code)], ['对外文案', esc(c.message || '—')],
      ['HTTP 状态', ppBadge(Number(c.httpStatus) >= 400 ? 'FAILED' : 'SUCCESS', c.httpStatus)],
      ['类别', ppBadge('kind', c.category || '—')],
      ['契约面', c.face === 'ADDITIVE' ? ppTag('on', 'additive 追加') : ppTag('off', '基础码表')],
      ['本进程该状态请求数', count ? String(Math.round(Number(count.value))) : '无样本']]) +
    ddSec('归因 / 触发场景', `<div class="pp-code light">${esc(c.description || '—')}</div>`) +
    ddSec('处置建议', ddBox('info', esc(advice))) +
    ddBox('plain', '错误体恒为 <span class="pp-mono">{error, code, traceId}</span>；成功响应为裸数据（不套 envelope），' +
      '<span class="pp-mono">account.balance</span> 永远返回裸数字 long（分）。') +
    `<div class="pp-inline" style="margin-top:8px">
      <button class="btn small ghost" data-pp-goto="observability">可观测</button>
      <button class="btn small ghost" data-pp-goto="sentinel">限流熔断</button>
      <button class="btn small ghost" data-pp-goto="audit">审计日志</button></div>`;
}

/* ===================== 页 3 · 限流熔断 ===================== */

function ppPfSentinelEnsure() {
  ppPfSec('sentinel', 'pfSnBox', PP_PF_SENTINEL_HTML);
  ppPfLp('pfSnFlow', { table: 'pfSnFlowTable', render: ppPfSnFlowRows, filters: [
    { k: 'kw', label: '关键字', ph: '资源 / 说明', fields: ['resource', 'note'] } ] });
  ppPfLp('pfSnParam', { table: 'pfSnParamTable', render: ppPfSnParamRows, filters: [
    { k: 'kw', label: '关键字', ph: '资源 / 说明', fields: ['resource', 'note'] } ] });
  ppPfLp('pfSnDegrade', { table: 'pfSnDegradeTable', render: ppPfSnDegradeRows, filters: [
    { k: 'kw', label: '关键字', ph: '接口 / 说明', fields: ['resource', 'note'] } ] });
  ppPfOnce('sentinel', () => ppPfOn({
    btnPfSnRefresh: () => ppPfSentinelLoad(),
    btnPfSnCsv: () => ppPfSnCsv()
  }));
}
async function ppPfSentinelLoad() {
  const box = document.getElementById('pfSnBox');
  ppLoadingBox(box, '加载生效规则与限流指标…');
  const [rules, blk, httpR, prom, health] = await Promise.all([
    tryApi('/platform/sentinel'),
    ppRawTry('/actuator/metrics/payment.gateway.sentinel_block'),
    ppRawTry('/actuator/metrics/http.server.requests'),
    ppPfProm(), tryApi('/health') ]);
  PPPlatform.sentinelLive = rules.ok;
  const s = rules.ok ? (rules.data || {}) : Object.assign({ live: false }, PP_PF_SENTINEL_BASE);
  PPPlatform.sentinel = s;
  if (health.ok) PPPlatform.health = health.data || [];
  const flow = (s.flowRules || []).map(r => Object.assign({ kind: 'flow', note: r.note || 'URL 级 QPS 总闸（全 appId 合计）' }, r));
  const param = (s.paramFlowRules || []).map(r => Object.assign({ kind: 'param', note: r.note || 'appId 热点参数限流（验签通过后计数）' }, r));
  const degrade = (s.degradeRules || []).map(r => Object.assign({ kind: 'degrade', note: r.note || '异常数熔断 → B0003 降级' }, r));
  lpSet('pfSnFlow', flow); lpSet('pfSnParam', param); lpSet('pfSnDegrade', degrade);
  const blocks = ppPfPromSum(prom, 'payment_gateway_sentinel_block_total');
  PPPlatform.blockByType = ppPfPromBy(prom, 'payment_gateway_sentinel_block_total', 'type');
  const meas = httpR.ok ? ((httpR.data && httpR.data.measurements) || []) : [];
  const cnt = meas.find(m => m.statistic === 'COUNT');
  const max = meas.find(m => m.statistic === 'MAX');
  const down = (PPPlatform.health || []).filter(h => !h.ok);
  const src = s.source || {};
  box.innerHTML = `<div class="stat-cards">
      <div class="stat-card ${blocks > 0 ? 'bad' : 'ok'}"><div class="label">Sentinel 拒绝累计</div><div class="value">${blocks == null ? '未注册' : blocks}</div></div>
      <div class="stat-card"><div class="label">HTTP 请求累计</div><div class="value">${cnt ? Math.round(cnt.value) : '—'}</div></div>
      <div class="stat-card"><div class="label">最大耗时(s)</div><div class="value">${max ? Number(max.value).toFixed(3) : '—'}</div></div>
      <div class="stat-card ${down.length ? 'bad' : 'ok'}"><div class="label">下游异常域</div><div class="value">${down.length}</div></div>
      <div class="stat-card accent"><div class="label">生效规则数</div><div class="value">${flow.length + param.length + degrade.length}</div></div>
    </div>
    ${rules.ok
      ? ddBox('info', `<b>规则来源：网关内存 RuleManager（当前生效值）</b>，非部署基线副本。
          流控 ${flow.length} · 热点参数 ${param.length} · 熔断 ${degrade.length}。`)
      : ddBox('warn', `<b>后端 /api/platform/sentinel 不可用</b>（${esc(rules.err.message)}）：
          下表回退为部署基线对照（<b>非生效值</b>），实际以规则文件 / Apollo 为准。`)}
    ${ddKv([['Sentinel 启用', s.enabled === false ? ppTag('off', '关闭（默认放行）') : ppTag('on', '启用')],
      ['规则文件', esc(src.file || '~/.config/payment-pro/sentinel-rules.json')],
      ['文件存在', src.exists == null ? '—' : ppYes(src.exists)],
      ['文件字节 / mtime', `${ppNum(src.size)} / ${src.lastModified ? fmtTime(src.lastModified) : '—'}`],
      ['Apollo 数据源', src.apolloEnabled ? ppTag('on', '启用 · ' + (src.apolloNamespace || '')) : ppTag('off', '未启用（文件源）')],
      ['热更新轮询', (src.pollSeconds || 5) + ' s']])}
    ${blk.ok ? ddSec('拒绝计数按类型（实时）', ddTable(['拒绝类型', '次数', '含义'],
        Object.entries(PPPlatform.blockByType).map(([k, v]) => `<tr><td class="pp-mono">${esc(k)}</td><td class="num">${v}</td>
          <td>${esc(k.includes('ParamFlow') ? 'appId 热点参数限流 → A0429' : k.includes('Flow') ? 'QPS 流控拒绝 → A0429'
            : k.includes('Degrade') ? '熔断降级 → B0003' : '其它 Sentinel 阻断')}</td></tr>`).join(''),
        '暂无拒绝（指标已注册，计数 0）'))
      : ddBox('plain', '<b>payment.gateway.sentinel_block 尚未注册</b>：Micrometer 懒注册，首次发生拒绝时才创建计数器 —— ' +
        '说明自进程启动以来<b>没有触发过限流 / 熔断</b>，不是采集故障。')}
    ${down.length ? ddBox('danger', '<b>下游异常域</b>：' + down.map(h => esc(h.domain || h.service)).join('、') +
        '（熔断窗口内异常数 ≥ 阈值即降级 B0003）') : ddBox('info', '全部下游域健康，熔断器处于闭合态。')}
    <div class="pp-obs">${esc(s.note || '')}<br><b>资金安全</b>：写操作（pay/topup/withdraw/transfer/refund）在<b>受理前</b>拒绝，
      不落订单、不留 PROCESSING 悬挂单；规则文件缺失/损坏 → 默认放行 + 内置保守熔断，不阻断支付。</div>`;
}
function ppPfSnFlowRows(rows) {
  const t = document.getElementById('pfSnFlowTable');
  if (!t) return;
  t.innerHTML = `<tr><th>资源（URL 模式）</th><th>grade</th><th class="num">阈值 QPS</th><th>limitApp</th><th>说明</th></tr>` +
    (rows.length ? rows.map(r => `<tr>
      <td>${ddLink('pfRule', 'flow:' + r.resource, r.resource)}</td>
      <td>${Number(r.grade) === 1 ? '1 = QPS' : esc(String(r.grade))}</td>
      <td class="num">${ppNum(r.count)}</td><td>${esc(r.limitApp || 'default')}</td>
      <td>${esc(r.note || '—')}</td></tr>`).join('') : emptyRow(5, '无流控规则（默认放行）'));
}
function ppPfSnParamRows(rows) {
  const t = document.getElementById('pfSnParamTable');
  if (!t) return;
  t.innerHTML = `<tr><th>资源</th><th>paramIdx</th><th class="num">阈值</th><th class="num">窗口(s)</th><th>说明</th></tr>` +
    (rows.length ? rows.map(r => `<tr>
      <td>${ddLink('pfRule', 'param:' + r.resource, r.resource)}</td>
      <td class="num">${ppNum(r.paramIdx)}（appId）</td><td class="num">${ppNum(r.count)}</td>
      <td class="num">${ppNum(r.durationInSec)}</td><td>${esc(r.note || '—')}</td></tr>`).join('')
      : emptyRow(5, '无热点参数规则'));
}
function ppPfSnDegradeRows(rows) {
  const t = document.getElementById('pfSnDegradeTable');
  if (!t) return;
  t.innerHTML = `<tr><th>资源（Dubbo 接口）</th><th>grade</th><th class="num">异常数阈值</th><th class="num">熔断时长(s)</th>
    <th class="num">最小请求数</th><th class="num">统计窗口(ms)</th><th>说明</th></tr>` +
    (rows.length ? rows.map(r => `<tr>
      <td style="word-break:break-all">${ddLink('pfRule', 'degrade:' + r.resource, r.resource)}</td>
      <td>${Number(r.grade) === 2 ? '2 = 异常数' : esc(String(r.grade))}</td>
      <td class="num">${ppNum(r.count)}</td><td class="num">${ppNum(r.timeWindow)}</td>
      <td class="num">${ppNum(r.minRequestAmount)}</td><td class="num">${ppNum(r.statIntervalMs)}</td>
      <td>${esc(r.note || '—')}</td></tr>`).join('') : emptyRow(7, '无熔断规则'));
}
function ppPfSnCsv() {
  const s = PPPlatform.sentinel || {};
  const rows = (s.flowRules || []).map(r => ['flow', r.resource, r.count + ' QPS', '1s', r.limitApp || 'default'])
    .concat((s.paramFlowRules || []).map(r => ['paramFlow', r.resource, r.count + ' /appId', (r.durationInSec || 1) + 's', 'paramIdx=' + r.paramIdx]))
    .concat((s.degradeRules || []).map(r => ['degrade', r.resource, r.count + ' 异常', (r.timeWindow || 0) + 's 熔断', 'minRequest=' + r.minRequestAmount]));
  if (!rows.length) { toast('无规则可导出', false); return; }
  ppCsvLocal('sentinel-rules-' + (PPPlatform.sentinelLive ? 'live' : 'baseline') + '.csv',
    ['类别', '资源', '阈值', '窗口', '备注'], rows);
}
async function ppPfDdRule(key) {
  const i = key.indexOf(':');
  const kind = key.slice(0, i), resource = key.slice(i + 1);
  const s = PPPlatform.sentinel || {};
  const list = kind === 'flow' ? s.flowRules : kind === 'param' ? s.paramFlowRules : s.degradeRules;
  const rule = (list || []).find(r => r.resource === resource) || {};
  const prom = PPPlatform.promRaw || await ppPfProm();
  const byType = ppPfPromBy(prom, 'payment_gateway_sentinel_block_total', 'type');
  const blocks = Object.entries(byType).filter(([k]) =>
    kind === 'flow' ? k.includes('Flow') && !k.includes('ParamFlow') : kind === 'param' ? k.includes('ParamFlow') : k.includes('Degrade'));
  return ddKv([['类别', ppBadge('kind', kind)], ['资源', esc(resource)],
      ['数据来源', PPPlatform.sentinelLive ? ppTag('on', 'RuleManager 生效值') : ppTag('off', '部署基线（非生效值）')],
      ['grade', ppNum(rule.grade)], ['阈值 count', ppNum(rule.count)],
      kind === 'flow' ? ['limitApp', esc(rule.limitApp || 'default')] : null,
      kind === 'param' ? ['paramIdx / 窗口', `${ppNum(rule.paramIdx)} / ${ppNum(rule.durationInSec)} s`] : null,
      kind === 'degrade' ? ['熔断时长 / 最小请求 / 统计窗口',
        `${ppNum(rule.timeWindow)} s / ${ppNum(rule.minRequestAmount)} / ${ppNum(rule.statIntervalMs)} ms`] : null].filter(Boolean)) +
    ddSec('该类阻断实时计数', ddTable(['拒绝类型', '次数'], blocks.map(([k, v]) =>
      `<tr><td class="pp-mono">${esc(k)}</td><td class="num">${v}</td></tr>`).join(''), '该类自启动以来无阻断')) +
    ddBox('info', kind === 'degrade'
      ? '熔断命中 → B0003（503），发生在 RPC 发起前，不启动下游 TCC，无在途悬挂单。'
      : '限流命中 → A0429（429），发生在受理前，不落订单、不留 PROCESSING 悬挂单。') +
    `<div class="pp-inline" style="margin-top:8px">
      <button class="btn small ghost" data-pp-goto="open-quota">appId 配额与计费</button>
      <button class="btn small ghost" data-pp-goto="errcode">错误码表</button></div>`;
}

/* ===================== 页 4 · 调度作业 ===================== */

function ppPfJobsEnsure() {
  ppPfSec('jobs', 'pfJbBox', PP_PF_JOBS_HTML);
  ppPfLp('pfJbJob', { table: 'pfJbJobTable', render: ppPfJbJobRows, filters: [
    { k: 'kw', label: '关键字', ph: '作业名', fields: ['name'] },
    { k: 'active', label: '本地调度', type: 'select', opts: [opt('', '全部'), opt('true', '本地生效'), opt('false', 'XXL 托管（本地抑制）')],
      test: (r, v) => String(!!r.localActive) === v },
    { k: 'fail', label: '最近结果', type: 'select', opts: [opt('', '全部'), opt('true', '有失败'), opt('false', '全成功/未运行')],
      test: (r, v) => String(Object.values(r.lastResults || {}).some(x => x && x.success === false)) === v } ] });
  ppPfLp('pfJbLog', { table: 'pfJbLogTable', render: ppPfJbLogRows, timeField: 'at', filters: [
    { k: 'kw', label: '关键字', ph: '作业 / 来源 / 错误', fields: ['name', 'source', 'error'] },
    { k: 'ok', label: '结果', type: 'select', opts: [opt('', '全部'), opt('true', '成功'), opt('false', '失败')],
      test: (r, v) => String(!!r.success) === v } ] });
  ppPfLp('pfJbLock', { table: 'pfJbLockTable', render: ppPfJbLockRows, filters: [
    { k: 'kw', label: '关键字', ph: '锁名 / 持有者', fields: ['name', 'locked_by'] },
    { k: 'held', label: '持有中', type: 'select', opts: [opt('', '全部'), opt('true', '本实例持有'), opt('false', '已释放')],
      test: (r, v) => String(!!r.held) === v } ] });
  ppPfOnce('jobs', () => {
    ppPfOn({
      btnPfJbRefresh: () => ppPfJbFetch(),
      btnPfJbCsv: () => ppPfJbCsv()
    });
    const sel = document.getElementById('pfJbDomain');
    if (sel) sel.onchange = () => ppPfJbFetch();
    const t = document.getElementById('pfJbJobTable');
    if (t) t.addEventListener('click', ev => {
      const b = ev.target.closest && ev.target.closest('[data-pf-jb-run]');
      if (b) { ev.preventDefault(); ppPfJbTrigger(b.dataset.pfJbRun); }
    });
  });
}
async function ppPfJobsLoad() {
  await ppPfLoadDomains();
  ppPfFillDomains('pfJbDomain');
  const sel = document.getElementById('pfJbDomain');
  if (sel && !PPPlatform.jobDomain) PPPlatform.jobDomain = sel.value;
  await ppPfJbFetch();
}
async function ppPfJbFetch() {
  const key = ppVal('pfJbDomain') || PPPlatform.jobDomain;
  const box = document.getElementById('pfJbBox');
  if (!key) { box.innerHTML = ddBox('warn', '未发现可用业务域（请先确认 /api/health 13/13）'); return; }
  PPPlatform.jobDomain = key;
  ppLoadingBox(box, '读取 ' + key + ' 作业态（网关同源代理）…');
  lpLoading('pfJbJob', 9, '加载作业…');
  lpLoading('pfJbLog', 7, '加载执行日志…');
  lpLoading('pfJbLock', 5, '加载分布式锁…');
  const r = await tryApi('/platform/jobs?service=' + encodeURIComponent(key));
  if (!r.ok) {
    lpSet('pfJbJob', []); lpSet('pfJbLog', []); lpSet('pfJbLock', []);
    const e = r.err || {};
    const noHandler = e.code === 'A0404' || /Not Found|404|资源不存在/.test(String(e.message || ''));
    if (noHandler) {
      // JobAutoConfiguration 是 @ConditionalOnBean(JobHandler.class)：该域只有裸 @Scheduled 兜底、
      // 没有 JobHandler bean 时整个 job 装配（含 /actuator/ppjob）被跳过，404 是真实语义而非故障。
      box.innerHTML = ddBox('warn', '域 <b>' + esc(key) + '</b> 未装配 JobHandler，无 XXL / 本地作业注册'
        + '（仅裸 <span class="pp-mono">@Scheduled</span> 兜底，故 <span class="pp-mono">/actuator/ppjob</span> 不存在）。'
        + '这是合法状态，不是读取故障；如需纳管请在该域提供 JobHandler bean。')
        + ppCode('curl -s http://127.0.0.1:' + ((ppPfDomain(key) || {}).httpPort || 18083) + '/actuator/ppjob', '终端直查（可复制，预期 404）');
      return;
    }
    box.innerHTML = ddBox('danger', '作业态读取失败：' + esc(e.message)) +
      ppCode('curl -s http://127.0.0.1:' + ((ppPfDomain(key) || {}).httpPort || 18083) + '/actuator/ppjob', '终端直查（可复制）');
    return;
  }
  PPPlatform.job = r.data || {};
  ppPfJbRender(PPPlatform.job, ppPfDomain(key) || { service: key });
}
function ppPfJbRender(j, d) {
  const box = document.getElementById('pfJbBox');
  const xxl = j.xxl || {}, loc = j.local || {}, sl = j.shedlock || {};
  const jobs = Object.entries(j.jobs || {}).map(([name, v]) => Object.assign({ name, service: d.service }, v));
  const fails = jobs.filter(x => Object.values(x.lastResults || {}).some(y => y && y.success === false)).length;
  if (box) box.innerHTML = `<div class="stat-cards">
      <div class="stat-card accent"><div class="label">作业数</div><div class="value">${jobs.length}</div></div>
      <div class="stat-card ${xxl.healthy ? 'ok' : 'bad'}"><div class="label">XXL 健康</div><div class="value">${xxl.healthy ? '健康' : '异常'}</div></div>
      <div class="stat-card"><div class="label">执行器端口</div><div class="value">${ppNum(xxl.port)}</div></div>
      <div class="stat-card ${fails ? 'bad' : 'ok'}"><div class="label">最近失败作业</div><div class="value">${fails}</div></div>
      <div class="stat-card"><div class="label">ShedLock 锁行</div><div class="value">${(sl.rows || []).length}</div></div>
    </div>
    ${ddKv([['应用', esc(j.application || '—')], ['域 / 运维端口', esc((d.domain || d.service) + ' :' + (d.httpPort || '—'))],
      ['锁持有者', esc(j.lockedBy || '—')], ['handler 数', String((j.handlers || []).length)],
      ['XXL 启用 / 注册 / 可达 / 健康', `${ppYes(xxl.enabled)} ${ppYes(xxl.executorRegistered)} ${ppYes(xxl.adminReachable)} ${ppYes(xxl.healthy)}`],
      ['XXL admin', esc(xxl.adminAddresses || '—')],
      ['执行器', esc((xxl.appname || '—') + ' :' + (xxl.port || '—') + ' @' + (xxl.executorIp || '—'))],
      ['最近探活', xxl.lastProbeAt ? fmtTime(xxl.lastProbeAt) : '—'],
      ['探活错误', esc(xxl.lastProbeError || '无')],
      ['本地兜底调度', loc.enabled ? ppTag('on', '启用') : ppTag('off', '关闭')],
      ['XXL 健康时本地待命', ppYes(loc.standbyWhenXxlHealthy)],
      ['停摆阈值(ms)', ppNum(loc.stallThresholdMs)],
      ['ShedLock', sl.enabled ? ppTag('on', '启用 · ' + (sl.table || '')) : ppTag('off', '关闭')]])}
    <div class="pp-obs"><b>双调度口径</b>：XXL-Job 健康时本地 <span class="pp-mono">@Scheduled</span> 待命（localSuppressed 累加）；
      XXL 不可达 / 停摆超阈值时本地接管；ShedLock 保证同名作业跨实例互斥（lockSkipped = 被其它实例持锁跳过）。
      手动触发走 <span class="pp-mono">POST /api/platform/jobs/{service}/{name}</span> → 域 <span class="pp-mono">/actuator/ppjob/{name}</span>，source=CONTROL。</div>`;
  const link = document.getElementById('pfJbXxlLink');
  if (link && xxl.adminAddresses) link.href = String(xxl.adminAddresses).split(',')[0];
  lpSet('pfJbJob', jobs);
  const logs = [];
  jobs.forEach(job => Object.entries(job.lastResults || {}).forEach(([source, v]) => {
    if (v) logs.push(Object.assign({ name: job.name, service: job.service, source }, v));
  }));
  logs.sort((a, b) => Number(b.at || 0) - Number(a.at || 0));
  lpSet('pfJbLog', logs);
  lpSet('pfJbLock', sl.rows || []);
}
function ppPfJbJobRows(rows) {
  const t = document.getElementById('pfJbJobTable');
  if (!t) return;
  const canW = hasPerm('console:admin');
  t.innerHTML = `<tr><th>作业</th><th>本地生效</th><th class="num">远程触发</th><th class="num">本地运行</th>
    <th class="num">本地抑制</th><th class="num">获锁</th><th class="num">跳过</th><th>最近结果</th><th>操作</th></tr>` +
    (rows.length ? rows.map(r => {
      const last = Object.values(r.lastResults || {})[0];
      return `<tr><td>${ddLink('pfJob', r.service + ':' + r.name, r.name)}</td>
        <td>${r.localActive ? ppTag('on', '本地生效') : ppTag('off', 'XXL 托管')}</td>
        <td class="num">${ppNum(r.remoteTriggers)}</td><td class="num">${ppNum(r.localRuns)}</td>
        <td class="num">${ppNum(r.localSuppressed)}</td><td class="num">${ppNum(r.lockAcquired)}</td>
        <td class="num ${Number(r.lockSkipped) > 0 ? 'neg' : ''}">${ppNum(r.lockSkipped)}</td>
        <td>${last ? `${ppBadge(last.success ? 'SUCCESS' : 'FAILED', last.success ? '成功' : '失败')}
          <span class="muted">${esc(last.source || '')} · ${ppNum(last.durationMs)}ms · ${last.at ? fmtTime(last.at) : '—'}</span>
          ${last.error ? `<br><span class="neg">${esc(last.error)}</span>` : ''}` : '<span class="muted">未运行</span>'}</td>
        <td>${canW ? `<button class="btn small" data-pf-jb-run="${esc(r.name)}">手动触发</button>` : '<span class="muted">只读</span>'}</td></tr>`;
    }).join('') : emptyRow(9, '该域暂无注册作业'));
}
function ppPfJbLogRows(rows) {
  const t = document.getElementById('pfJbLogTable');
  if (!t) return;
  t.innerHTML = `<tr><th>作业</th><th>来源</th><th>结果</th><th class="num">耗时(ms)</th><th class="num">分片</th><th>错误</th><th>执行时间</th></tr>` +
    (rows.length ? rows.map(r => `<tr>
      <td>${ddLink('pfJob', r.service + ':' + r.name, r.name)}</td>
      <td>${ppBadge('kind', r.source || '—')}</td>
      <td>${ppBadge(r.success ? 'SUCCESS' : 'FAILED', r.success ? '成功' : '失败')}</td>
      <td class="num">${ppNum(r.durationMs)}</td>
      <td class="num">${ppNum(r.shardIndex)} / ${ppNum(r.shardTotal)}</td>
      <td class="${r.error ? 'neg' : ''}">${esc(r.error || '—')}</td>
      <td>${r.at ? fmtTime(r.at) : '—'}</td></tr>`).join('')
      : emptyRow(7, '暂无执行记录（作业尚未运行；可手动触发一次）'));
}
function ppPfJbLockRows(rows) {
  const t = document.getElementById('pfJbLockTable');
  if (!t) return;
  t.innerHTML = `<tr><th>锁名</th><th>持有者</th><th>加锁时间</th><th>到期时间</th><th>持有中</th></tr>` +
    (rows.length ? rows.map(r => `<tr><td class="pp-mono">${esc(r.name)}</td><td>${esc(r.locked_by || '—')}</td>
      <td>${r.locked_at ? fmtTime(r.locked_at) : '—'}</td><td>${r.lock_until ? fmtTime(r.lock_until) : '—'}</td>
      <td>${r.held ? ppTag('on', '是') : ppTag('off', '否')}</td></tr>`).join('') : emptyRow(5, '无 ShedLock 记录'));
}
async function ppPfJbTrigger(name) {
  const service = PPPlatform.jobDomain;
  if (!service) { toast('未选定业务域', false); return; }
  const idx = ppInt('pfJbShardIndex', 0), total = ppInt('pfJbShardTotal', 1), param = ppVal('pfJbParam');
  const body = { shardIndex: idx, shardTotal: total, param: param || '' };
  const html = `<div class="box warn"><b>手动触发调度作业（敏感操作，需二次认证）</b></div>
    ${ddKv([['业务域', esc(service)], ['作业', esc(name)], ['分片', idx + ' / ' + total],
      ['param', esc(param || '（空串）')], ['来源标记', 'CONTROL']])}
    <div class="pp-note">作业可能真实动账（对账 / 结算 / 分账 / 退款补偿）；ShedLock 保证跨实例互斥，
      同名作业并发触发会被跳过（lockSkipped）。幂等由作业自身保证。</div>`;
  const box = document.getElementById('pfJbOpBox');
  try {
    const r = await ppGuarded('手动触发作业', html,
      '/platform/jobs/' + encodeURIComponent(service) + '/' + encodeURIComponent(name), 'POST', body, '确认触发');
    if (!r) return;
    ppResult(box, '作业触发完成', r, r && r.success !== false);
    toast('作业已触发'); await ppPfJbFetch();
  } catch (e) { ppErrBox(box, '作业触发', e); }
}
function ppPfJbCsv() {
  const rows = lpVisible('pfJbJob');
  if (!rows.length) { toast('无作业可导出', false); return; }
  ppCsvLocal('jobs-' + PPPlatform.jobDomain + '.csv',
    ['作业', '域', '本地生效', '远程触发', '本地运行', '本地抑制', '获锁', '跳过'],
    rows.map(r => [r.name, r.service, r.localActive ? 'Y' : 'N', r.remoteTriggers, r.localRuns,
      r.localSuppressed, r.lockAcquired, r.lockSkipped]));
}
async function ppPfDdJob(key) {
  const i = key.indexOf(':');
  const service = key.slice(0, i), name = key.slice(i + 1);
  const r = await tryApi('/platform/jobs?service=' + encodeURIComponent(service));
  const j = r.ok ? (r.data || {}) : (PPPlatform.job || {});
  const job = (j.jobs || {})[name];
  if (!job) return ddBox('warn', '未找到作业：' + esc(key));
  const sl = j.shedlock || {};
  const locks = (sl.rows || []).filter(x => String(x.name || '').startsWith(name));
  const results = Object.entries(job.lastResults || {});
  return ddKv([['作业', esc(name)], ['业务域', esc(service)], ['应用', esc(j.application || '—')],
      ['本地生效', job.localActive ? ppTag('on', '本地生效') : ppTag('off', 'XXL 托管')],
      ['远程触发 / 本地运行', `${ppNum(job.remoteTriggers)} / ${ppNum(job.localRuns)}`],
      ['本地抑制', ppNum(job.localSuppressed)],
      ['获锁 / 跳过', `${ppNum(job.lockAcquired)} / ${ppNum(job.lockSkipped)}`],
      ['最近远程触发', job.lastRemoteTriggerAt ? fmtTime(job.lastRemoteTriggerAt) : '—']]) +
    ddSec('最近执行结果（按来源）', ddTable(['来源', '结果', '耗时(ms)', '分片', '错误', '时间'],
      results.map(([source, v]) => `<tr><td>${ppBadge('kind', source)}</td>
        <td>${ppBadge(v.success ? 'SUCCESS' : 'FAILED', v.success ? '成功' : '失败')}</td>
        <td class="num">${ppNum(v.durationMs)}</td><td class="num">${ppNum(v.shardIndex)} / ${ppNum(v.shardTotal)}</td>
        <td class="${v.error ? 'neg' : ''}">${esc(v.error || '—')}</td><td>${v.at ? fmtTime(v.at) : '—'}</td></tr>`).join(''),
      '尚未运行'), results.length + ' 条') +
    ddSec('该作业 ShedLock 记录', ddTable(['锁名', '持有者', '加锁', '到期', '持有中'],
      locks.map(x => `<tr><td class="pp-mono">${esc(x.name)}</td><td>${esc(x.locked_by || '—')}</td>
        <td>${x.locked_at ? fmtTime(x.locked_at) : '—'}</td><td>${x.lock_until ? fmtTime(x.lock_until) : '—'}</td>
        <td>${x.held ? ppTag('on', '是') : ppTag('off', '否')}</td></tr>`).join(''), '无锁记录'), locks.length + ' 条') +
    ddBox('plain', 'XXL-Job 控制台可查完整调度日志（执行器 ' + esc((j.xxl || {}).appname || '—') +
      ' :' + ppNum((j.xxl || {}).port) + '）；本页展示的是进程内计数与最近一次结果快照。') +
    `<div class="pp-inline" style="margin-top:8px">
      <button class="btn small ghost" data-pp-goto="observability">可观测</button>
      <button class="btn small ghost" data-pp-goto="shadow-diff">分片与影子比对</button></div>`;
}

/* ===================== 页 5 · 分片与影子比对 ===================== */

function ppPfShardEnsure() {
  ppPfSec('shadow-diff', 'pfSdBox', PP_PF_SHARD_HTML);
  ppPfLp('pfSdTable', { table: 'pfSdTableTable', render: ppPfSdTableRows, filters: [
    { k: 'kw', label: '关键字', ph: '表名 / 分片列 / 主键', fields: ['table', 'shardColumn', 'pk'] },
    { k: 'diff', label: '差异', type: 'select', opts: [opt('', '全部'), opt('true', '有差异'), opt('false', 'diff=0')],
      test: (r, v) => String(Number(r.diffTotal || 0) !== 0) === v } ] });
  ppPfLp('pfSdPhys', { table: 'pfSdPhysTable', render: ppPfSdPhysRows, filters: [
    { k: 'kw', label: '关键字', ph: '表名', fields: ['table'] },
    { k: 'kind', label: '表类型', type: 'select', field: 'kind',
      opts: [opt('', '全部'), opt('LEGACY', '单库表'), opt('SHARD', '分片表')] } ] });
  ppPfOnce('shadow-diff', () => {
    ppPfOn({
      btnPfSdRefresh: () => ppPfSdFetch(),
      btnPfSdCompare: () => ppPfSdCompare(),
      btnPfSdCsv: () => ppPfSdCsv(),
      btnPfSdToggle: () => ppPfSdToggle()
    });
    const sel = document.getElementById('pfSdDomain');
    if (sel) sel.onchange = () => ppPfSdFetch();
    const w = document.getElementById('pfSdWarn');
    if (w) w.innerHTML = PP_PF_SHARD_WARN;
  });
}
async function ppPfShardLoad() {
  await ppPfLoadDomains();
  ppPfFillDomains('pfSdDomain', d => PP_PF_SHARD_DOMAINS.includes(d.service));
  const sel = document.getElementById('pfSdDomain');
  if (sel && !PPPlatform.shardDomain) PPPlatform.shardDomain = sel.value;
  const w = document.getElementById('pfSdWarn');
  if (w) w.innerHTML = PP_PF_SHARD_WARN;
  await ppPfSdFetch();
}
async function ppPfSdFetch() {
  const key = ppVal('pfSdDomain') || PPPlatform.shardDomain;
  const box = document.getElementById('pfSdBox');
  if (!key) { box.innerHTML = ddBox('warn', '未发现分片资金域'); return; }
  PPPlatform.shardDomain = key;
  ppLoadingBox(box, '读取 ' + key + ' 分片态（网关同源代理）…');
  lpLoading('pfSdTable', 10, '加载影子比对…');
  const r = await tryApi('/platform/shard?service=' + encodeURIComponent(key));
  if (!r.ok) {
    lpSet('pfSdTable', []); lpSet('pfSdPhys', []);
    box.innerHTML = ddBox('danger', '分片态读取失败：' + esc(r.err.message)) +
      ppCode('curl -s http://127.0.0.1:' + ((ppPfDomain(key) || {}).httpPort || 18083) + '/actuator/ppshard', '终端直查（可复制）');
    return;
  }
  PPPlatform.shard = r.data || {};
  const c = await tryApi('/platform/shard/compare?service=' + encodeURIComponent(key));
  PPPlatform.shardCompare = c.ok ? (c.data || null) : null;
  ppPfSdRender(PPPlatform.shard, PPPlatform.shardCompare, ppPfDomain(key) || { service: key },
    c.ok ? '' : c.err.message);
}
function ppPfSdRender(s, cmp, d, cmpErr) {
  const box = document.getElementById('pfSdBox');
  const tables = Object.entries(s.shardTables || {});
  const active = !!s.shardingActive;
  const phys = s.physicalRows || {};
  if (box) box.innerHTML = `<div class="stat-cards">
      <div class="stat-card ${active ? 'warn' : 'ok'}"><div class="label">路由态</div><div class="value" style="font-size:15px">${active ? '已切流分片' : '单库观察态'}</div></div>
      <div class="stat-card"><div class="label">分片数</div><div class="value">${ppNum(s.shardCount)}</div></div>
      <div class="stat-card"><div class="label">分片表</div><div class="value">${tables.length}</div></div>
      <div class="stat-card ${Number(s.mirrorFailures) > 0 ? 'bad' : 'ok'}"><div class="label">镜像失败</div><div class="value">${ppNum(s.mirrorFailures)}</div></div>
      <div class="stat-card"><div class="label">ID 模式</div><div class="value" style="font-size:15px">${esc(s.idMode || '—')}</div></div>
    </div>
    ${active ? ddBox('warn', `<b>该域已切流到分片表</b>（shardingActive=true，idMode=${esc(s.idMode || 'SNOWFLAKE')}）；
        回滚：toggle enabled=false（同样先比对再切）。`)
      : ddBox('info', `<b>单库观察态</b>：读走单库，双写开（mirrorLegacy=${s.mirrorLegacy ? 'true' : 'false'}），
        分片表与单库表并行维护 —— 影子双跑，资金安全。`)}
    ${ddKv([['域', esc(s.domain || d.service || '—')], ['运维端口', ppNum(d.httpPort)],
      ['分片已装配', ppYes(s.shardingConfigured)],
      ['分片已切流', active ? ppTag('bad', '已切流') : ppTag('on', '单库观察态')],
      ['读写分离', ppYes(s.readwriteSplitting)], ['双写单库（镜像）', ppYes(s.mirrorLegacy)],
      ['镜像行数', ppNum(s.mirrorRows)],
      ['镜像冲突', Number(s.mirrorConflicts) > 0 ? ppTag('bad', String(s.mirrorConflicts)) : ppTag('on', String(s.mirrorConflicts == null ? 0 : s.mirrorConflicts))],
      ['写路由读命中', ppNum(s.writeRouteHintReads)], ['已生成 ID', ppNum(s.idGenerated)],
      ['路由态持久化', ppYes(s.routeStatePersisted)]])}
    ${ddSec('分片键', ddTable(['逻辑表', '分片列'], tables.map(([t, c]) =>
      `<tr><td class="pp-mono">${esc(t)}</td><td class="pp-mono">${esc(c)}</td></tr>`).join(''), '未装配分片'), tables.length + ' 表')}
    ${ddSec('启动复制基线（只 CREATE + INSERT，原行保留）', ddTable(
      ['逻辑表', '分片列', '新增列', '复制前单库行', '复制后单库行', '→分片', '→单库'],
      Object.entries(s.bootstrap || {}).map(([t, v]) => `<tr><td class="pp-mono">${esc(t)}</td>
        <td class="pp-mono">${esc(v.shardColumn || '—')}</td><td class="num">${ppNum(v.addedColumns)}</td>
        <td class="num">${ppNum(v.legacyRowsBefore)}</td><td class="num">${ppNum(v.legacyRowsAfter)}</td>
        <td class="num">${ppNum(v.copiedToShards)}</td><td class="num">${ppNum(v.copiedToLegacy)}</td></tr>`).join(''),
      '无基线记录'))}`;
  const physRows = Object.entries(phys).map(([t, n]) => ({ table: t, rows: n,
    kind: /_\d+$/.test(t) ? 'SHARD' : 'LEGACY' }));
  lpSet('pfSdPhys', physRows);
  const cbox = document.getElementById('pfSdCmpBox');
  if (cmp) {
    const rows = Object.entries(cmp).filter(([, v]) => v && typeof v === 'object' && v.table)
      .map(([k, v]) => Object.assign({ key: k, service: d.service }, v));
    lpSet('pfSdTable', rows);
    const diff = Number(cmp.diffTotal || 0);
    if (cbox) cbox.innerHTML = ppCons('影子比对总差异（diffTotal 必须为 0 才允许切流）', diff, 0,
        { raw: true, leftLabel: 'diffTotal', rightLabel: '允许上限 0',
          note: '后端 toggle 前置护栏：diff≠0 或唯一键冲突≠0 一律拒绝切换，不改动任何路由。' }) +
      ddBox(diff === 0 ? 'info' : 'danger', diff === 0
        ? '<b>影子比对全表一致（diff = 0）</b>：单库与分片逐行逐值一致，具备切流前置条件（但仍须用户拍板）。'
        : '<b>存在差异，禁止切流</b>：先 resync 补齐并复核样本，差异清零后再评估。');
  } else {
    lpSet('pfSdTable', []);
    if (cbox) cbox.innerHTML = ddBox('warn', '影子比对未取到' + (cmpErr ? '：' + esc(cmpErr) : '') +
      '（该域可能无 shadowCompare 视图）。可点「重新影子比对」重试。');
  }
  const tg = document.getElementById('pfSdToggleTarget');
  if (tg) tg.innerHTML = active
    ? '当前 <b>已切流</b> → 本次操作将 <b>回滚到单库观察态</b>（enabled=false）'
    : '当前 <b>单库观察态</b> → 本次操作将 <b>切流到分片表</b>（enabled=true）';
  const ck = document.getElementById('pfSdEnabled');
  if (ck) ck.checked = !active;
}
function ppPfSdTableRows(rows) {
  const t = document.getElementById('pfSdTableTable');
  if (!t) return;
  t.innerHTML = `<tr><th>逻辑表</th><th>主键</th><th>分片列</th><th class="num">单库行数</th><th class="num">分片行数</th>
    <th class="num">分片缺失</th><th class="num">单库缺失</th><th class="num">值差异</th><th class="num">差异合计</th><th>样本</th></tr>` +
    (rows.length ? rows.map(r => {
      const diff = Number(r.diffTotal || 0);
      return `<tr><td>${ddLink('pfShardTable', (r.service || PPPlatform.shardDomain) + ':' + r.table, r.table)}</td>
        <td class="pp-mono">${esc(r.pk || '—')}</td><td class="pp-mono">${esc(r.shardColumn || '—')}</td>
        <td class="num">${ppNum(r.legacyRows)}</td><td class="num">${ppNum(r.shardRows)}</td>
        <td class="num">${ppNum(r.missingInShards)}</td><td class="num">${ppNum(r.missingInLegacy)}</td>
        <td class="num">${ppNum(r.valueDiffs)}</td>
        <td class="num">${diff === 0 ? ppTag('on', '0') : ppTag('bad', String(diff))}</td>
        <td>${(r.samples || []).length ? `<details><summary class="muted" style="cursor:pointer;font-size:12px">${r.samples.length} 条</summary>
          <div class="pp-code light">${esc(JSON.stringify(r.samples, null, 2))}</div></details>` : '<span class="muted">无</span>'}</td></tr>`;
    }).join('') : emptyRow(10, '暂无影子比对结果'));
}
function ppPfSdPhysRows(rows) {
  const t = document.getElementById('pfSdPhysTable');
  if (!t) return;
  const total = rows.filter(r => r.kind === 'LEGACY').reduce((s, r) => s + Number(r.rows || 0), 0);
  const shards = rows.filter(r => r.kind === 'SHARD').reduce((s, r) => s + Number(r.rows || 0), 0);
  t.innerHTML = `<tr><th>物理表</th><th>类型</th><th class="num">行数</th><th>占比</th></tr>` +
    (rows.length ? rows.map(r => `<tr><td class="pp-mono">${esc(r.table)}</td>
      <td>${r.kind === 'SHARD' ? ppTag('on', '分片表') : ppTag('off', '单库表')}</td>
      <td class="num">${ppNum(r.rows)}</td>
      <td>${ppMeter(Number(r.rows || 0), r.kind === 'SHARD' ? shards : total)}</td></tr>`).join('')
      : emptyRow(4, '无行数统计'));
}
async function ppPfSdCompare() {
  const key = PPPlatform.shardDomain;
  if (!key) { toast('未选定资金域', false); return; }
  const box = document.getElementById('pfSdCmpBox');
  ppLoadingBox(box, '执行全量影子比对（只读，不改路由、不动账）…');
  const r = await tryApi('/platform/shard/compare?service=' + encodeURIComponent(key));
  if (!r.ok) { box.innerHTML = ddBox('danger', '影子比对失败：' + esc(r.err.message)); return; }
  PPPlatform.shardCompare = r.data || null;
  ppPfSdRender(PPPlatform.shard || { shardingConfigured: true, shardingActive: false },
    PPPlatform.shardCompare, ppPfDomain(key) || { service: key }, '');
  toast('影子比对完成');
}
async function ppPfSdToggle() {
  const key = PPPlatform.shardDomain;
  if (!key) { toast('未选定资金域', false); return; }
  const enabled = ppChecked('pfSdEnabled');
  const s = PPPlatform.shard || {}, cmp = PPPlatform.shardCompare || {};
  const diff = cmp.diffTotal == null ? null : Number(cmp.diffTotal);
  const body = { enabled, confirm: 'SHARD' };
  const html = PP_PF_SHARD_WARN + ddKv([['域', esc(s.domain || key)],
      ['当前路由', s.shardingActive ? '分片表（已切流）' : '单库（观察态）'],
      ['本次目标', enabled ? '切流到分片表' : '回滚到单库'],
      ['影子比对 diffTotal', diff == null ? '未取到（后端仍会前置比对）' : String(diff)],
      ['双写单库', s.mirrorLegacy ? '开' : '关']]) +
    (diff != null && diff !== 0 ? ddBox('danger', '<b>当前 diff≠0，后端会拒绝切换</b>；请先补齐差异。') : '');
  const c = await askConfirm({ title: enabled ? '分片切流（高危 · 须用户拍板）' : '分片回滚到单库（高危）',
    html, okText: enabled ? '我已获用户拍板，确认切流' : '确认回滚到单库',
    input: { label: '请输入 SHARD 以确认', placeholder: 'SHARD', required: true } });
  if (!c) return;
  if (String(c.value).trim().toUpperCase() !== 'SHARD') { toast('确认词不正确，已取消（未发起请求）', false); return; }
  const c2 = await askConfirm({ title: '再次确认：用户是否已明确拍板？', okText: '是，用户已拍板',
    html: ddBox('danger', '<b>最后一道确认</b>：切流 / 回滚改变资金域读写路由，属用户决策事项。' +
      '请确认已获得用户明确许可；下一步要求二次认证（step-up，token 仅内存）。') });
  if (!c2) { toast('已取消（未发起任何请求）', false); return; }
  const box = document.getElementById('pfSdOpBox');
  try {
    const r = await sensitiveApi('/platform/shard/toggle?service=' + encodeURIComponent(key), 'POST', body, html);
    const rejected = r && (r.allowed === false || r.reason);
    ppResult(box, rejected ? '切换被后端护栏拒绝（路由未改动）' : 'toggle 已执行', r, !rejected);
    toast(rejected ? '后端护栏拒绝切换' : 'toggle 已执行', !rejected);
    await ppPfSdFetch();
  } catch (e) { ppErrBox(box, '分片 toggle', e); }
}
function ppPfSdCsv() {
  const rows = lpVisible('pfSdTable');
  if (!rows.length) { toast('无比对结果可导出', false); return; }
  ppCsvLocal('shadow-compare-' + PPPlatform.shardDomain + '.csv',
    ['逻辑表', '主键', '分片列', '单库行数', '分片行数', '分片缺失', '单库缺失', '值差异', '差异合计'],
    rows.map(r => [r.table, r.pk, r.shardColumn, r.legacyRows, r.shardRows,
      r.missingInShards, r.missingInLegacy, r.valueDiffs, r.diffTotal]));
}
async function ppPfDdShardTable(key) {
  const i = key.indexOf(':');
  const service = key.slice(0, i), table = key.slice(i + 1);
  const [sr, cr] = await Promise.all([
    tryApi('/platform/shard?service=' + encodeURIComponent(service)),
    tryApi('/platform/shard/compare?service=' + encodeURIComponent(service)) ]);
  const s = sr.ok ? (sr.data || {}) : (PPPlatform.shard || {});
  const cmp = cr.ok ? (cr.data || {}) : (PPPlatform.shardCompare || {});
  const row = cmp[table] || {};
  const phys = Object.entries(s.physicalRows || {}).filter(([t]) => t === table || t.startsWith(table + '_'));
  const boot = (s.bootstrap || {})[table] || {};
  const diff = Number(row.diffTotal || 0);
  return ddKv([['逻辑表', esc(table)], ['域', esc(service)], ['主键', esc(row.pk || '—')],
      ['分片列', esc(row.shardColumn || (s.shardTables || {})[table] || '—')],
      ['单库行数', ppNum(row.legacyRows)], ['分片行数', ppNum(row.shardRows)],
      ['分片缺失 / 单库缺失', `${ppNum(row.missingInShards)} / ${ppNum(row.missingInLegacy)}`],
      ['值差异', ppNum(row.valueDiffs)],
      ['差异合计', diff === 0 ? ppTag('on', '0（一致）') : ppTag('bad', String(diff))],
      ['当前路由', s.shardingActive ? ppTag('bad', '已切流分片') : ppTag('on', '单库观察态')]]) +
    ppCons('该表影子差异', diff, 0, { raw: true, leftLabel: 'diffTotal', rightLabel: '允许上限 0' }) +
    ddSec('物理分布', ddTable(['物理表', '行数'], phys.map(([t, n]) =>
      `<tr><td class="pp-mono">${esc(t)}</td><td class="num">${ppNum(n)}</td></tr>`).join(''), '无行数统计')) +
    ddSec('启动复制基线', ddKv([['新增列', ppNum(boot.addedColumns)],
      ['复制前单库行', ppNum(boot.legacyRowsBefore)], ['复制后单库行', ppNum(boot.legacyRowsAfter)],
      ['复制到分片', ppNum(boot.copiedToShards)], ['复制到单库', ppNum(boot.copiedToLegacy)]])) +
    ddSec('差异样本', (row.samples || []).length
      ? `<div class="pp-code light">${esc(JSON.stringify(row.samples, null, 2))}</div>`
      : ddBox('info', '无差异样本（逐行逐值一致）'), (row.samples || []).length + ' 条') +
    `<div class="pp-inline" style="margin-top:8px">
      <button class="btn small ghost" data-pp-goto="ledger">账务流水</button>
      <button class="btn small ghost" data-pp-goto="vouchers">凭证联查</button>
      <button class="btn small ghost" data-pp-goto="recon-shard">对账分片调度</button></div>`;
}

/* ===================== 页 6 · 可观测 ===================== */

function ppPfObsEnsure() {
  ppPfSec('observability', 'pfObBox', PP_PF_OBS_HTML);
  ppPfLp('pfObHealth', { table: 'pfObHealthTable', render: ppPfObHealthRows, filters: [
    { k: 'kw', label: '关键字', ph: '服务 / 业务域', fields: ['service', 'domain'] },
    { k: 'ok', label: '状态', type: 'select', opts: [opt('', '全部'), opt('true', '健康'), opt('false', '异常')],
      test: (r, v) => String(!!r.ok) === v } ] });
  ppPfLp('pfObMetric', { table: 'pfObMetricTable', render: ppPfObMetricRows, filters: [
    { k: 'kw', label: '关键字', ph: '指标名', fields: ['name'] },
    { k: 'kind', label: '类别', type: 'select', field: 'kind',
      opts: [opt('', '全部'), opt('biz', '业务计数器'), opt('jvm', 'JVM/进程'), opt('http', 'HTTP'), opt('db', '连接池/DB'), opt('other', '其它')] } ] });
  ppPfLp('pfObAlert', { table: 'pfObAlertTable', render: ppPfObAlertRows, filters: [
    { k: 'level', label: '级别', type: 'select', field: 'level',
      opts: [opt('', '全部'), opt('BAD', 'BAD 告警'), opt('OK', 'OK 正常')] } ] });
  ppPfOnce('observability', () => {
    ppPfOn({
      btnPfObRefresh: () => ppPfObsLoad(),
      btnPfObProm: () => ppDlText('gateway-prometheus.txt', PPPlatform.promRaw || '', 'text/plain'),
      btnPfObCsv: () => ppPfObCsv(),
      btnPfObMetric: () => ppPfObMetricDetail()
    });
    const sel = document.getElementById('pfObMetricSel');
    if (sel) sel.onchange = () => ppPfObMetricDetail();
    const t = document.getElementById('pfObMetricTable');
    if (t) t.addEventListener('click', ev => {
      const b = ev.target.closest && ev.target.closest('[data-pf-ob-metric]');
      if (b) {
        ev.preventDefault();
        const s = document.getElementById('pfObMetricSel');
        if (s) s.value = b.dataset.pfObMetric;
        ppPfObMetricDetail();
      }
    });
  });
}
async function ppPfObsLoad() {
  lpLoading('pfObHealth', 6, '加载服务健康…');
  lpLoading('pfObMetric', 4, '加载指标目录…');
  const [h, ah, names, prom, info, st] = await Promise.all([
    tryApi('/health'), ppRawTry('/actuator/health'), ppRawTry('/actuator/metrics'),
    ppPfProm(), ppRawTry('/actuator/info'), tryApi('/stats') ]);
  if (!h.ok) lpError('pfObHealth', 6, h.err.message);
  else {
    PPPlatform.health = h.data || [];
    PPPlatform.domains = PPPlatform.health.filter(x => Number(x.port) >= 20000).map(x => ({
      service: x.service, domain: x.domain, port: Number(x.port), ok: x.ok,
      httpPort: Number(x.port) - 2800, base: 'http://127.0.0.1:' + (Number(x.port) - 2800)
    }));
    lpSet('pfObHealth', PPPlatform.health);
  }
  const kinds = n => n.startsWith('payment.') ? 'biz'
    : n.startsWith('jvm.') || n.startsWith('process.') || n.startsWith('system.') ? 'jvm'
    : n.startsWith('http.') ? 'http'
    : n.startsWith('hikaricp.') || n.startsWith('jdbc.') || n.startsWith('executor.') ? 'db' : 'other';
  PPPlatform.metrics = names.ok ? ((names.data && names.data.names) || []).map(n => ({ name: n, kind: kinds(n) })) : [];
  lpSet('pfObMetric', PPPlatform.metrics);
  const sel = document.getElementById('pfObMetricSel');
  if (sel) {
    const biz = PPPlatform.metrics.filter(m => m.kind === 'biz');
    const rest = PPPlatform.metrics.filter(m => m.kind !== 'biz');
    sel.innerHTML = biz.concat(rest).map(m => `<option value="${esc(m.name)}">${esc(m.name)}</option>`).join('');
  }
  const box = document.getElementById('pfObBox');
  if (box) {
    const up = (PPPlatform.health || []).filter(x => x.ok).length, total = (PPPlatform.health || []).length;
    const comps = (ah.ok && ah.data && ah.data.components) || {};
    box.innerHTML = `<div class="stat-cards">
        <div class="stat-card ${up === total && total > 0 ? 'ok' : 'bad'}"><div class="label">全栈健康</div><div class="value">${up} / ${total}</div></div>
        <div class="stat-card ${ah.ok && ah.data && ah.data.status === 'UP' ? 'ok' : 'bad'}"><div class="label">网关 actuator</div><div class="value">${esc((ah.ok && ah.data && ah.data.status) || '不可用')}</div></div>
        <div class="stat-card"><div class="label">指标数</div><div class="value">${PPPlatform.metrics.length}</div></div>
        <div class="stat-card"><div class="label">业务计数器</div><div class="value">${PPPlatform.metrics.filter(m => m.kind === 'biz').length}</div></div>
        <div class="stat-card ok"><div class="label">支付总额（元）</div><div class="value">${st.ok ? yuan(st.data.totalVolume || 0) : '—'}</div></div>
        <div class="stat-card ${(st.ok && st.data.trial && st.data.trial.balanced) ? 'ok' : 'bad'}"><div class="label">试算平衡</div><div class="value">${st.ok ? ((st.data.trial && st.data.trial.balanced) ? '平衡' : '不平衡') : '—'}</div></div>
      </div>
      ${ppCons('健康服务数守恒（健康 + 异常 = 总数）', up + (total - up), total, { raw: true, leftLabel: 'up+down', rightLabel: 'total' })}
      ${ddSec('网关自身依赖', ddTable(['组件', '状态', '细节'], Object.entries(comps).map(([k, v]) =>
        `<tr><td class="pp-mono">${esc(k)}</td><td>${ppBadge(v.status === 'UP' ? 'SUCCESS' : 'FAILED', v.status)}</td>
          <td class="pp-mono" style="word-break:break-all">${esc(JSON.stringify(v.details || {}))}</td></tr>`).join(''), '无组件信息'))}
      ${info.ok && info.data ? ddSec('构建 / 版本信息', `<div class="pp-code light">${esc(JSON.stringify(info.data, null, 2))}</div>`) : ''}
      <div class="pp-obs"><b>口径</b>：全栈健康来自 <span class="pp-mono">/api/health</span>（Dubbo 探活，13 进程）；
        网关自身依赖来自 <span class="pp-mono">/actuator/health</span>（db / redis / diskSpace）；
        业务计数器来自 Micrometer → Prometheus。链路追踪与日志聚合未接入（后端缺口）：
        排障用响应头 <span class="pp-mono">X-Trace-Id</span> 在网关与各域日志检索。</div>`;
  }
  ppPfObAlerts(prom);
}
function ppPfObAlerts(txt) {
  const items = [
    ['通知死信', 'payment_gateway_notify_dead_total', 0, '通知投递进入死信 → 人工重发（通知 · 查询与重发）', 'notify-resend'],
    ['Outbox 死信', 'payment_gateway_outbox_dead_total', 0, '事件投递死信 → 检查 MQ / 回调地址', 'notify-resend'],
    ['TCC 死信', 'payment_gateway_tcc_dead_total', 0, 'TCC 悬挂 / 空回滚 → 资金修复（差错平台）', 'disputes'],
    ['支付失败', 'payment_gateway_pay_fail_total', 0, '支付失败计数 → 核对渠道与风控拒绝', 'payments'],
    ['对账差错', 'payment_gateway_recon_dispute_total', 0, '对账不平 → 差错平台处置', 'disputes'],
    ['Sentinel 拒绝', 'payment_gateway_sentinel_block_total', 0, '限流 / 熔断拒绝 → 核对规则与阈值', 'sentinel'],
    ['通知送达', 'payment_gateway_notify_delivered_total', null, '通知送达计数（正向指标）', 'notify-resend'],
    ['支付成功', 'payment_gateway_pay_success_total', null, '支付成功计数（正向指标）', 'payments'],
    ['退款', 'payment_gateway_refund_total', null, '退款计数（含成功 / 失败标签）', 'payments']
  ];
  const rows = items.map(([name, metric, thr, note, goto]) => {
    const v = ppPfPromSum(txt, metric);
    const level = v == null || thr == null ? 'OK' : v > thr ? 'BAD' : 'OK';
    return { name, metric, value: v, level, note, goto,
      detail: v == null ? '指标未注册（自启动以来未发生）' : '当前累计 ' + v };
  }).concat((PPPlatform.health || []).filter(h => !h.ok).map(h => ({
    name: '服务异常 · ' + (h.domain || h.service), metric: '/api/health', value: 0, level: 'BAD',
    note: 'Dubbo 探活失败 → 熔断降级 B0003', goto: 'arch', detail: '端口 ' + h.port })));
  lpSet('pfObAlert', rows);
  const box = document.getElementById('pfObAlertBox');
  if (box) {
    const bad = rows.filter(r => r.level === 'BAD');
    box.innerHTML = bad.length
      ? ddBox('danger', `<b>${bad.length} 项告警</b>：` + bad.map(r => esc(r.name) + '（' + esc(r.detail) + '）').join('；'))
      : ddBox('info', '<b>无告警</b>：死信 / 失败 / 差错 / 限流计数器均为 0 或未注册，全部服务健康。');
  }
}
function ppPfObHealthRows(rows) {
  const t = document.getElementById('pfObHealthTable');
  if (!t) return;
  t.innerHTML = `<tr><th>服务</th><th>业务域</th><th class="num">Dubbo 端口</th><th class="num">HTTP/actuator 端口</th><th>状态</th><th>入口</th></tr>` +
    (rows.length ? rows.map(h => `<tr><td class="pp-mono"><b>${esc(h.service)}</b></td><td>${esc(h.domain || '—')}</td>
      <td class="num">${ppNum(h.port)}</td><td class="num">${Number(h.port) >= 20000 ? Number(h.port) - 2800 : '—'}</td>
      <td>${h.ok ? ppTag('on', '健康') : ppTag('bad', '异常')}</td>
      <td>${h.service === 'gateway' ? '<button class="btn small ghost" data-pp-goto="arch">服务拓扑</button>'
        : Number(h.port) >= 20000 ? `<button class="btn small ghost" data-pp-goto="jobs">作业</button>
           <button class="btn small ghost" data-pp-goto="shadow-diff">分片</button>` : '<span class="muted">—</span>'}</td></tr>`).join('')
      : emptyRow(6, '无健康数据'));
}
function ppPfObMetricRows(rows) {
  const t = document.getElementById('pfObMetricTable');
  if (!t) return;
  t.innerHTML = `<tr><th>指标名</th><th>类别</th><th>Prometheus 名</th><th>操作</th></tr>` +
    (rows.length ? rows.map(m => `<tr><td>${ddLink('pfMetric', m.name, m.name)}</td>
      <td>${ppBadge('kind', m.kind)}</td><td class="pp-mono">${esc(m.name.replace(/\./g, '_'))}</td>
      <td><button class="btn small ghost" data-pf-ob-metric="${esc(m.name)}">下钻测量值</button></td></tr>`).join('')
      : emptyRow(4, '无指标'));
}
function ppPfObAlertRows(rows) {
  const t = document.getElementById('pfObAlertTable');
  if (!t) return;
  t.innerHTML = `<tr><th>告警项</th><th>指标 / 来源</th><th class="num">当前值</th><th>级别</th><th>判定</th><th>处置建议</th></tr>` +
    (rows.length ? rows.map(r => `<tr><td><b>${esc(r.name)}</b></td>
      <td class="pp-mono" style="word-break:break-all">${esc(r.metric)}</td>
      <td class="num">${r.value == null ? '<span class="muted">未注册</span>' : r.value}</td>
      <td>${r.level === 'BAD' ? ppTag('bad', 'BAD') : ppTag('on', 'OK')}</td>
      <td>${esc(r.detail)}</td>
      <td>${esc(r.note)}${r.goto ? ` <button class="btn small ghost" data-pp-goto="${esc(r.goto)}">前往</button>` : ''}</td></tr>`).join('')
      : emptyRow(6, '无告警项'));
}
async function ppPfObMetricDetail() {
  const name = ppVal('pfObMetricSel');
  const box = document.getElementById('pfObMetricBox');
  if (!name) { toast('请选择指标', false); return; }
  box.innerHTML = await ppPfDdMetric(name);
}
async function ppPfDdMetric(name) {
  const r = await ppRawTry('/actuator/metrics/' + encodeURIComponent(name));
  if (!r.ok) return ddBox('danger', '指标读取失败：' + esc(r.err.message));
  const d = r.data || {};
  return ddKv([['指标', esc(d.name || name)], ['说明', esc(d.description || '—')],
      ['基础单位', esc(d.baseUnit || '—')], ['Prometheus 名', esc(String(d.name || name).replace(/\./g, '_'))]]) +
    ddSec('测量值', ddTable(['统计量', '值'], (d.measurements || []).map(m =>
      `<tr><td class="pp-mono">${esc(m.statistic)}</td><td class="num">${esc(String(m.value))}</td></tr>`).join(''), '无测量值')) +
    ddSec('可用标签（可下钻）', ddTable(['标签', '取值'], (d.availableTags || []).map(t =>
      `<tr><td class="pp-mono">${esc(t.tag)}</td><td class="pp-mono" style="word-break:break-all">${esc((t.values || []).join(', '))}</td></tr>`).join(''), '无标签')) +
    ppCode('curl -s http://127.0.0.1:8080/actuator/metrics/' + name + '?tag=status:200', '按标签下钻示例');
}
function ppPfObCsv() {
  const rows = lpVisible('pfObHealth');
  if (!rows.length) { toast('无数据可导出', false); return; }
  ppCsvLocal('platform-health.csv', ['服务', '业务域', 'Dubbo端口', 'HTTP端口', '健康'],
    rows.map(h => [h.service, h.domain, h.port, Number(h.port) >= 20000 ? Number(h.port) - 2800 : '', h.ok ? 'UP' : 'DOWN']));
}

/* ===================== 显式挂 window（历史坑：const 不进 window） ===================== */
window.PPPlatform = PPPlatform;
/* 兼容两种 nav 布线：loader:'PPPlatform.loadCanary'（点号路径）与 window[loader] 直取。 */
['loadCanary', 'loadErrcode', 'loadSentinel', 'loadJobs', 'loadShadowDiff', 'loadObservability'].forEach(k => {
  window['PPPlatform.' + k] = function () { return PPPlatform[k](); };
});