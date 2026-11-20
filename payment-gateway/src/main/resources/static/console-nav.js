/* =====================================================================
 * payment-pro 管理控制台 · 信息架构（12 分组两级导航）+ hash 路由 + 快速跳转
 * ---------------------------------------------------------------------
 * 【挂载点约定 · 务必遵守】
 *  1) 新增页面：在下方 PP_IA 对应分组里加一条 { tab, name, perm, loader }。
 *     - tab   ：既是 nav button 的 data-tab，也是 section#tab-<tab> 的后缀（保持既有值不动）。
 *     - perm  ：沿用既有权限点，逗号分隔为「任一命中」；分组内全部子项不可见 → 整组自动隐藏。
 *     - loader：全局函数名（字符串），支持 'loadXxx' 与 'PPModule.loadXxx' 两种写法，
 *               由 bindLoaders() 解析后挂到既有 loaders[tab]，由既有 refresh(tab) 调用。
 *     - 页面容器：在 index.html 写 <section id="tab-<tab>" hidden>…</section>；
 *               未落地时本模块只生成一个提示容器（不再有待建占位页）。
 *  2) 列表分页：复用既有 lpDef/lpSet/lpPaint/lpLoading/lpError（客户端）或 renderPager（服务端）。
 *  3) 单据详情：复用既有 #detailDrawer —— 往 DD_KINDS / DD_RENDER 里加 kind 即可（见 console-risk.js）。
 *  4) 敏感操作：askConfirm(...) 二次确认 + sensitiveApi(path,method,body,reasonHtml) 走 step-up。
 *  5) 样式：只用 console-ui.css 的 .pp-* 与 index.html 既有令牌，禁止外部库 / CDN。
 *  6) 本文件与各业务模块均在 index.html 内联脚本「之前」加载，
 *     顶层只允许声明（数据 + 函数），一切 DOM/全局依赖都放进 init()，由内联脚本尾部调用；
 *     模块末尾必须显式 window.PPX = PPX（顶层 const 不会自动成为 window 属性）。
 * ===================================================================== */

const PP_IA = [
  { key:'overview', name:'总览', icon:'总', pages:[
    { tab:'arch',   name:'服务拓扑与健康', perm:'', loader:'loadArch' },
    { tab:'funds',  name:'资金看板', perm:'stats:read,accounting:read', loader:'loadFundsBoard' } ] },

  { key:'trade', name:'交易', icon:'交', pages:[
    { tab:'orders', name:'交易单', perm:'trade:read', loader:'loadOrders' },
    { tab:'trade-products', name:'交易产品', perm:'trade:read', loader:'PPTrade.loadProducts' },
    { tab:'cashier-ops', name:'收银台运营', perm:'trade:read', loader:'PPCashier.loadOps' },
    { tab:'combo-pay', name:'合并支付与分阶段', perm:'payment:read', loader:'PPCashier.loadCombo' } ] },

  { key:'payment', name:'支付', icon:'付', pages:[
    { tab:'payments', name:'支付单', perm:'payment:read', loader:'loadPayments' },
    { tab:'pay-orchestration', name:'支付编排', perm:'payment:read', loader:'loadPayOrchestration' },
    { tab:'marketing-pay', name:'营销抵扣编排', perm:'marketing:read,payment:read', loader:'loadMarketingPay' },
    { tab:'reviews', name:'大额复核', perm:'payment:refund,accounting:reverse,dispute:approve', loader:'loadReviews' },
    { tab:'close-reverse', name:'关闭与撤销', perm:'payment:read', loader:'loadCloseReverse' } ] },

  { key:'accounting', name:'资金账务', icon:'账', pages:[
    { tab:'accounts', name:'账户', perm:'accounting:read', loader:'loadAccountsPage' },
    { tab:'ledger',   name:'账务流水', perm:'accounting:read', loader:'loadLedgerPage' },
    { tab:'vouchers', name:'凭证联查', perm:'accounting:read', loader:'loadVouchersPage' },
    { tab:'subjects', name:'会计科目与余额', perm:'accounting:read', loader:'loadSubjects' },
    { tab:'gl-reports', name:'总账 / 明细账 / 资产负债表', perm:'accounting:read', loader:'loadGlReports' },
    { tab:'daycut', name:'日切与日终批', perm:'accounting:read', loader:'loadDaycut' },
    { tab:'reserve', name:'备付金核对', perm:'accounting:read', loader:'loadReserve' },
    { tab:'reserve-logs', name:'账实核对日志', perm:'accounting:read', loader:'loadReserveLogsPage' },
    { tab:'multicurrency', name:'多币种与汇率', perm:'accounting:read', loader:'loadMulticurrency' } ] },

  { key:'settle', name:'清结算', icon:'结', pages:[
    { tab:'settle', name:'结算记录与汇总', perm:'settle:read', loader:'loadSettle' },
    { tab:'merchant-bill', name:'商户账单', perm:'settle:read', loader:'loadSettleBills' },
    { tab:'settle-cycle', name:'周期与批次', perm:'settle:read', loader:'loadSettleCycle' },
    { tab:'settle-fee', name:'费率规则', perm:'settle:read', loader:'loadSettleFee' },
    { tab:'settle-split', name:'分账明细', perm:'settle:read', loader:'loadSettleSplit' },
    { tab:'withdraw', name:'提现单', perm:'settle:read', loader:'loadWithdraw' } ] },

  { key:'channel', name:'渠道', icon:'渠', pages:[
    { tab:'ch-config', name:'配置与适配器', perm:'channel:read', loader:'loadChConfig' },
    { tab:'ch-route',  name:'路由与评分', perm:'channel:read', loader:'loadChRoute' },
    { tab:'ch-limit',  name:'限额与维护窗', perm:'channel:read', loader:'loadChLimit' },
    { tab:'ch-trades', name:'渠道流水', perm:'channel:read', loader:'loadChannelTrades' },
    { tab:'ch-bills',  name:'对账文件与比对', perm:'channel:read', loader:'loadChBills' } ] },

  { key:'risk', name:'风控', icon:'风', pages:[
    { tab:'rk-rules',   name:'规则与版本', perm:'risk:read', loader:'loadRkRules' },
    { tab:'rk-engine',  name:'引擎与试算', perm:'risk:read', loader:'loadRkEngine' },
    { tab:'rk-quotas',  name:'多维限额', perm:'risk:read', loader:'loadRkQuotas' },
    { tab:'rk-lists',   name:'名单管理', perm:'risk:read', loader:'loadRkLists' },
    { tab:'rk-score',   name:'评分与处置', perm:'risk:read', loader:'loadRkScore' },
    { tab:'rk-profile', name:'设备指纹与IP画像', perm:'risk:read', loader:'loadRkProfile' },
    { tab:'rk-aml',     name:'AML 与可疑报告', perm:'risk:read', loader:'loadRkAml' } ] },

  { key:'recon', name:'对账与差错', icon:'对', pages:[
    { tab:'recon', name:'对账运行与报告', perm:'recon:read', loader:'loadRecon' },
    { tab:'recon-daycut', name:'日切批量对账', perm:'recon:read', loader:'loadReconDaycut' },
    { tab:'recon-cross', name:'渠道内外对账', perm:'recon:read', loader:'loadReconCross' },
    { tab:'recon-report', name:'对账报表', perm:'recon:read', loader:'loadReconReport' },
    { tab:'recon-shard', name:'分片调度', perm:'recon:read', loader:'loadReconShard' },
    { tab:'disputes', name:'差错平台', perm:'dispute:read', loader:'loadDisputes' } ] },

  { key:'member', name:'会员与商户', icon:'会', pages:[
    { tab:'members', name:'会员', perm:'member:read', loader:'loadMembers' },
    { tab:'member-certs', name:'密钥与证书', perm:'member:read', loader:'loadMemberCerts' },
    { tab:'kyc', name:'KYC 认证', perm:'member:read', loader:'PPMember.loadKyc' },
    { tab:'onboarding', name:'进件与审核', perm:'merchant:read', loader:'PPMember.loadOnboarding' },
    { tab:'contract', name:'合同与结算卡', perm:'merchant:read', loader:'PPMember.loadContract' },
    { tab:'bindcard', name:'绑卡管理', perm:'member:read', loader:'PPMember.loadBindcard' },
    { tab:'member-tier', name:'分级限额', perm:'member:read', loader:'PPMember.loadTier' },
    { tab:'member-auth', name:'C端会员认证管理', perm:'member:read', loader:'PPMember.loadAuth' } ] },

  { key:'notify', name:'通知', icon:'通', pages:[
    { tab:'notify', name:'通知记录', perm:'notify:read', loader:'loadNotify' },
    { tab:'notify-resend', name:'查询与重发', perm:'notify:read', loader:'PPNotify.loadResend' },
    { tab:'notify-tpl', name:'事件类型与模板', perm:'notify:read', loader:'PPNotify.loadTemplates' } ] },

  { key:'openapi', name:'开放平台', icon:'开', pages:[
    { tab:'open-apps', name:'应用与授权', perm:'openplatform:read', loader:'PPOpen.loadApps' },
    { tab:'open-sandbox', name:'沙箱', perm:'openplatform:read', loader:'PPOpen.loadSandbox' },
    { tab:'open-quota', name:'配额与计费', perm:'openplatform:read', loader:'PPOpen.loadQuota' },
    { tab:'open-version', name:'版本与 SDK', perm:'openplatform:read', loader:'PPOpen.loadVersion' },
    { tab:'open-docs', name:'API 文档', perm:'openplatform:read', loader:'PPOpen.loadDocs' } ] },

  { key:'platform', name:'平台治理', icon:'治', pages:[
    { tab:'console', name:'控制台用户与角色', perm:'console:admin', loader:'loadConsole' },
    { tab:'audit',   name:'审计日志', perm:'audit:read', loader:'loadAudit' },
    { tab:'config',  name:'配置中心', perm:'console:admin', loader:'loadConfig' },
    { tab:'canary', name:'灰度发布', perm:'console:admin', loader:'PPPlatform.loadCanary' },
    { tab:'errcode', name:'错误码表', perm:'console:admin', loader:'PPPlatform.loadErrcode' },
    { tab:'sentinel', name:'限流熔断', perm:'console:admin', loader:'PPPlatform.loadSentinel' },
    { tab:'jobs', name:'调度作业', perm:'console:admin', loader:'PPPlatform.loadJobs' },
    { tab:'shadow-diff', name:'分片与影子比对', perm:'console:admin', loader:'PPPlatform.loadShadowDiff' },
    { tab:'observability', name:'可观测', perm:'console:admin', loader:'PPPlatform.loadObservability' } ] }
];

const PPNav = {
  LS_KEY: 'pp.console.nav',
  state: { collapsed:{}, rail:false, recent:[], last:'' },
  byTab: {}, byGroup: {}, cur: null, ready: false,

  /* ---------- 生命周期 ---------- */
  init() {
    PP_IA.forEach(g => {
      this.byGroup[g.key] = g;
      g.pages.forEach(p => { p.group = g.key; this.byTab[p.tab] = p; });
    });
    this.load();
    this.build();
    this.ensureSections();
    this.bindLoaders();
    this.wire();
    this.applyRail();
    this.route(false);           /* 只定位不取数：取数由既有 boot()/doLogin() 的 refresh 负责 */
    this.ready = true;
  },
  load() {
    try {
      const raw = JSON.parse(localStorage.getItem(this.LS_KEY) || 'null');
      if (raw && typeof raw === 'object') {
        this.state.collapsed = raw.collapsed || {};
        this.state.rail = !!raw.rail;
        this.state.recent = Array.isArray(raw.recent) ? raw.recent.slice(0, 8) : [];
        this.state.last = raw.last || '';
      }
    } catch (e) { /* 脏数据不影响启动 */ }
  },
  save() {
    try { localStorage.setItem(this.LS_KEY, JSON.stringify(this.state)); } catch (e) {}
  },

  /* ---------- 渲染侧栏 ---------- */
  build() {
    const nav = document.getElementById('ppNav');
    if (!nav) return;
    const groups = PP_IA.map((g, i) => `
      <div class="pp-grp${this.state.collapsed[g.key] ? ' collapsed' : ''}" data-grp="${esc(g.key)}">
        <div class="pp-grp-head" role="button" tabindex="0" data-grp-head="${esc(g.key)}" title="${esc(g.name)}">
          <span class="pp-ico">${esc(g.icon)}</span><span class="pp-grp-name">${esc(g.name)}</span>
          <span class="pp-grp-idx">${i + 1}</span><span class="pp-caret">▾</span>
        </div>
        <div class="pp-grp-body">${g.pages.map(p => `
          <button data-tab="${esc(p.tab)}"${p.perm ? ` data-perm="${esc(p.perm)}"` : ''}
            title="${esc(g.name)} · ${esc(p.name)}${p.soon ? '（即将上线）' : ''}">
            <span class="pp-ico">${esc(p.name.slice(0, 1))}</span>
            <span class="pp-label">${esc(p.name)}</span>
            ${p.soon ? '<span class="pp-soon">待建</span>' : ''}
          </button>`).join('')}</div>
      </div>`).join('');
    nav.innerHTML = `
      <div class="pp-nav-top">
        <div class="pp-jump-wrap">
          <input id="ppJump" placeholder="快速跳转：输入页面名 / 回车直达" autocomplete="off" aria-label="快速跳转">
          <div class="pp-jump-list" id="ppJumpList" hidden></div>
        </div>
        <div class="pp-nav-tools">
          <div class="pp-tool" role="button" tabindex="0" id="ppNavExpand" title="展开全部">展开</div>
          <div class="pp-tool" role="button" tabindex="0" id="ppNavCollapse" title="折叠全部">折叠</div>
        </div>
        <div class="pp-tool pp-rail-only" role="button" tabindex="0" id="ppNavUnrail" title="展开侧栏">»</div>
      </div>
      ${groups}
      <div style="padding:10px 12px"><div class="pp-tool" role="button" tabindex="0" id="ppNavRail" title="收起为图标栏">« 收起为图标栏</div></div>`;
  },
  /** 为尚未落地的页面生成 section#tab-<tab> 容器（后续棒次写了真 section 就自动跳过） */
  ensureSections() {
    const main = document.querySelector('main');
    if (!main) return;
    PP_IA.forEach(g => g.pages.forEach(p => {
      if (document.getElementById('tab-' + p.tab)) return;
      const sec = document.createElement('section');
      sec.id = 'tab-' + p.tab;
      sec.hidden = true;
      sec.innerHTML = `<div class="card"><h3>${esc(p.name)}<span class="section-desc">分组 ${esc(g.name)} · 权限点 ${esc(p.perm || '（登录可见）')}</span></h3>
        <div class="box warn">页面容器 <span class="pp-mono">section#tab-${esc(p.tab)}</span> 尚未在 index.html 落地，
          加载函数 <span class="pp-mono">${esc(p.loader || '（未挂载）')}</span> 需要的表格 / 表单节点缺失。
          请在 index.html 补齐同名 section（表格 id 见对应 console-*.js 的 lpDef 声明）。</div>
      </div>`;
      main.appendChild(sec);
    }));
  },
  /** loader 支持 'globalFn' 与 'PPModule.method' 两种写法（后者按点号逐级解析） */
  resolveLoader(name) {
    if (!name) return null;
    let cur = window;
    for (const seg of String(name).split('.')) {
      if (cur == null) return null;
      cur = cur[seg];
    }
    return typeof cur === 'function' ? cur : null;
  },
  bindLoaders() {
    PP_IA.forEach(g => g.pages.forEach(p => {
      const fn = this.resolveLoader(p.loader);
      if (fn) loaders[p.tab] = fn;
    }));
  },

  /* ---------- 交互 ---------- */
  wire() {
    const nav = document.getElementById('ppNav');
    nav.addEventListener('click', ev => {
      const b = ev.target.closest('button[data-tab]');
      if (b) { this.go(b.dataset.tab); return; }
      const h = ev.target.closest('[data-grp-head]');
      if (h) this.toggleGroup(h.dataset.grpHead);
    });
    nav.addEventListener('keydown', ev => {
      const h = ev.target.closest && ev.target.closest('[data-grp-head]');
      if (h && (ev.key === 'Enter' || ev.key === ' ')) { ev.preventDefault(); this.toggleGroup(h.dataset.grpHead); }
    });
    const on = (id, fn) => { const el = document.getElementById(id); if (!el) return;
      el.addEventListener('click', fn);
      el.addEventListener('keydown', ev => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); fn(); } }); };
    on('ppNavExpand', () => { PP_IA.forEach(g => this.state.collapsed[g.key] = false); this.save(); this.paintGroups(); });
    on('ppNavCollapse', () => { PP_IA.forEach(g => this.state.collapsed[g.key] = true); this.save(); this.paintGroups(); });
    on('ppNavRail', () => this.setRail(true));
    on('ppNavUnrail', () => this.setRail(false));
    window.addEventListener('hashchange', () => this.route(true));
    const mq = window.matchMedia('(max-width:900px)');
    const sync = () => { this.mqNarrow = mq.matches; this.applyRail(); };
    if (mq.addEventListener) mq.addEventListener('change', sync); else if (mq.addListener) mq.addListener(sync);
    sync();
    this.wireJump();
    /* 通用页面跳转：任何元素加 data-pp-goto="tab" 即可 */
    document.addEventListener('click', ev => {
      const b = ev.target.closest && ev.target.closest('[data-pp-goto]');
      if (b) { ev.preventDefault(); this.go(b.dataset.ppGoto); }
    });
  },
  wireJump() {
    const inp = document.getElementById('ppJump'), list = document.getElementById('ppJumpList');
    if (!inp || !list) return;
    let hits = [], sel = 0;
    const paint = () => {
      const q = inp.value.trim().toLowerCase();
      if (!q) { list.hidden = true; list.innerHTML = ''; hits = []; return; }
      hits = [];
      PP_IA.forEach(g => g.pages.forEach(p => {
        const b = document.querySelector(`nav#ppNav button[data-tab="${p.tab}"]`);
        if (b && b.hidden) return;
        const hay = (p.name + ' ' + g.name + ' ' + p.tab).toLowerCase();
        if (hay.includes(q)) hits.push(p);
      }));
      hits = hits.slice(0, 10); sel = 0;
      list.hidden = !hits.length;
      list.innerHTML = hits.map((p, i) => `<div data-jump="${esc(p.tab)}" class="${i === 0 ? 'on' : ''}">
        <span class="g">${esc(this.byGroup[p.group].name)}</span><span>${esc(p.name)}</span>
        ${p.soon ? '<span class="pp-soon">待建</span>' : ''}</div>`).join('');
    };
    const move = d => {
      if (!hits.length) return;
      sel = (sel + d + hits.length) % hits.length;
      [...list.children].forEach((c, i) => c.classList.toggle('on', i === sel));
    };
    inp.addEventListener('input', paint);
    inp.addEventListener('focus', paint);
    inp.addEventListener('blur', () => setTimeout(() => { list.hidden = true; }, 160));
    inp.addEventListener('keydown', ev => {
      if (ev.key === 'Enter') { ev.preventDefault(); if (hits[sel]) { this.go(hits[sel].tab); inp.value = ''; list.hidden = true; inp.blur(); } }
      else if (ev.key === 'ArrowDown') { ev.preventDefault(); move(1); }
      else if (ev.key === 'ArrowUp') { ev.preventDefault(); move(-1); }
      else if (ev.key === 'Escape') { list.hidden = true; inp.blur(); }
    });
    list.addEventListener('mousedown', ev => {
      const d = ev.target.closest('[data-jump]');
      if (d) { ev.preventDefault(); this.go(d.dataset.jump); inp.value = ''; list.hidden = true; }
    });
  },
  toggleGroup(key) {
    this.state.collapsed[key] = !this.state.collapsed[key];
    this.save(); this.paintGroups();
  },
  paintGroups() {
    PP_IA.forEach(g => {
      const el = document.querySelector(`.pp-grp[data-grp="${g.key}"]`);
      if (el) el.classList.toggle('collapsed', !!this.state.collapsed[g.key]);
    });
  },
  setRail(v) { this.state.rail = !!v; this.save(); this.applyRail(); },
  applyRail() { document.body.classList.toggle('pp-rail', !!(this.state.rail || this.mqNarrow)); },

  /* ---------- 权限：分组无可见子项则整组隐藏（由既有 applyPerms 调用） ---------- */
  syncPerms() {
    PP_IA.forEach(g => {
      const el = document.querySelector(`.pp-grp[data-grp="${g.key}"]`);
      if (!el) return;
      const visible = g.pages.some(p => {
        const b = document.querySelector(`nav#ppNav button[data-tab="${p.tab}"]`);
        return b && !b.hidden;
      });
      el.hidden = !visible;
    });
    const cur = this.cur && document.querySelector(`nav#ppNav button[data-tab="${this.cur}"]`);
    if (this.ready && cur && cur.hidden) {
      const first = this.firstVisible();
      if (first) this.go(first.tab, true);
    }
    this.paintCrumb();
  },
  firstVisible() {
    for (const g of PP_IA) for (const p of g.pages) {
      const b = document.querySelector(`nav#ppNav button[data-tab="${p.tab}"]`);
      if (b && !b.hidden) return p;
    }
    return null;
  },

  /* ---------- 路由 ---------- */
  hashOf(tab) { const p = this.byTab[tab]; return p ? `#/${p.group}/${p.tab}` : ''; },
  parse() {
    const h = String(location.hash || '').replace(/^#\/?/, '');
    const parts = h.split('/').filter(Boolean);
    const tab = parts[parts.length - 1];
    return this.byTab[tab] ? this.byTab[tab] : null;
  },
  go(tab, replace) {
    const p = this.byTab[tab];
    if (!p) { toast('页面不存在：' + tab, false); return; }
    const b = document.querySelector(`nav#ppNav button[data-tab="${tab}"]`);
    if (b && b.hidden) { toast('当前角色无权访问该页面', false); return; }
    const target = this.hashOf(tab);
    if (location.hash === target) { this.apply(tab, true); return; }
    if (replace) { history.replaceState(null, '', target); this.apply(tab, true); }
    else location.hash = target;   /* hashchange → route(true) */
  },
  route(doRefresh) {
    let p = this.parse();
    /* 仅当 hash 为空（首次进入）才恢复上次访问；非法 hash 一律回落首个可见页 */
    if (!p && !String(location.hash || '').replace(/^#\/?/, '')) p = this.byTab[(this.state.last || '').split('/').pop()] || null;
    if (p) {
      const b = document.querySelector(`nav#ppNav button[data-tab="${p.tab}"]`);
      if (b && b.hidden) p = null;
    }
    if (!p) p = this.firstVisible() || this.byTab['arch'];
    if (!p) return;
    const target = this.hashOf(p.tab);
    if (location.hash !== target) history.replaceState(null, '', target);
    this.apply(p.tab, doRefresh);
  },
  apply(tab, doRefresh) {
    const p = this.byTab[tab];
    if (!p) return;
    const changed = this.cur !== tab;
    this.cur = tab;
    document.querySelectorAll('nav#ppNav button[data-tab]').forEach(x => x.classList.toggle('active', x.dataset.tab === tab));
    document.querySelectorAll('main > section').forEach(s => { s.hidden = s.id !== 'tab-' + tab; });
    const grp = document.querySelector(`.pp-grp[data-grp="${p.group}"]`);
    if (grp && grp.classList.contains('collapsed') && changed) { grp.classList.remove('collapsed'); this.state.collapsed[p.group] = false; }
    document.querySelectorAll('.pp-grp-head').forEach(h => h.classList.toggle('on', h.dataset.grpHead === p.group));
    this.state.last = this.hashOf(tab);
    if (changed) this.state.recent = [tab].concat((this.state.recent || []).filter(t => t !== tab)).slice(0, 8);
    this.save();
    this.paintCrumb();
    if (doRefresh) {
      const now = Date.now();
      if (this._lastRefresh && this._lastRefresh.tab === tab && now - this._lastRefresh.ts < 500) return;
      this._lastRefresh = { tab, ts: now };
      refresh(tab);
    }
  },
  paintCrumb() {
    const el = document.getElementById('ppCrumb');
    if (!el || !this.cur) return;
    const p = this.byTab[this.cur]; if (!p) return;
    const g = this.byGroup[p.group];
    const recent = (this.state.recent || []).filter(t => t !== this.cur && this.byTab[t])
      .filter(t => { const b = document.querySelector(`nav#ppNav button[data-tab="${t}"]`); return b && !b.hidden; })
      .slice(0, 4);
    el.innerHTML = `<span>${esc(g.name)}</span><span class="sep">›</span><b>${esc(p.name)}</b>
      ${p.soon ? '<span class="pp-soon">即将上线</span>' : ''}
      <span class="pp-mono" style="color:#94a3b8">${esc(this.hashOf(p.tab))}</span>
      ${recent.length ? `<span class="pp-recent"><span>最近</span>${recent.map(t =>
        `<button data-pp-goto="${esc(t)}">${esc(this.byTab[t].name)}</button>`).join('')}</span>` : ''}`;
  }
};

/* ---------- 共享展示 helper（风控 / 渠道 / 后续棒次运营页通用） ---------- */
const ppFen = v => (v === null || v === undefined || v === '') ? '—' : yuan(v);
const ppBizDate = v => {
  if (v === null || v === undefined || v === '') return '—';
  const s = String(v);
  if (/^\d{8}$/.test(s)) return s.slice(0,4) + '-' + s.slice(4,6) + '-' + s.slice(6,8);
  if (/^\d{6}$/.test(s)) return s.slice(0,4) + '-' + s.slice(4,6);
  return fmtTime(v).split(' ')[0];
};
const ppMs = v => (v && Number(v) > 0) ? fmtTime(Number(v)) : '—';
const ppPct = v => (v === null || v === undefined) ? '—' : (Number(v) * 100).toFixed(1) + '%';
function ppMeter(used, limit) {
  if (!limit || limit <= 0) return '<span class="muted">未设限</span>';
  const r = Math.min(1, (used || 0) / limit), cls = r >= 1 ? 'bad' : (r >= 0.8 ? 'warn' : '');
  return `<div class="pp-inline"><div class="pp-meter ${cls}" style="flex:1"><i style="width:${(r*100).toFixed(1)}%"></i></div>
    <span class="muted" style="font-size:11px">${(r*100).toFixed(0)}%</span></div>`;
}

/* ---------- 总览 · 资金看板（复用既有 /api/stats 的试算平衡与关键指标） ---------- */
async function loadFundsBoard() {
  const box = document.getElementById('fundsKpi');
  if (!box) return;
  let s = stats;
  try { if (!s || !s.members) s = await api('/stats'); } catch (e) { box.innerHTML = `<div class="box danger">指标加载失败：${esc(e.message)}</div>`; return; }
  const st = s.settle || {}, t = s.trial || {};
  box.innerHTML = `
    <div class="stat-card accent"><div class="label">会员 / 账户</div><div class="value">${s.members || 0} / ${s.accounts || 0}</div></div>
    <div class="stat-card"><div class="label">交易单</div><div class="value">${s.orders || 0}</div></div>
    <div class="stat-card ok"><div class="label">支付总额（元）</div><div class="value">${yuan(s.totalVolume || 0)}</div></div>
    <div class="stat-card"><div class="label">手续费（元）</div><div class="value">${yuan(st.totalFee || 0)}</div></div>
    <div class="stat-card ${t.balanced ? 'ok' : 'bad'}"><div class="label">试算平衡</div><div class="value">${t.balanced ? '平衡' : '不平衡'}</div></div>`;
  const tb = document.getElementById('fundsTrial');
  if (tb) tb.innerHTML = `<div class="kv">
    <div><span class="k">借方合计</span><span class="v">¥ ${yuan(t.totalDebit || 0)}</span></div>
    <div><span class="k">贷方合计</span><span class="v">¥ ${yuan(t.totalCredit || 0)}</span></div>
    <div><span class="k">分录条数</span><span class="v">${t.entries || 0}</span></div>
    <div><span class="k">差额</span><span class="v ${(t.totalDebit || 0) === (t.totalCredit || 0) ? '' : 'neg'}">¥ ${yuan((t.totalDebit || 0) - (t.totalCredit || 0))}</span></div>
    <div><span class="k">结算笔数</span><span class="v">${st.payCount != null ? st.payCount : '—'}</span></div>
    <div><span class="k">商户净结算</span><span class="v">¥ ${yuan(st.totalNet || 0)}</span></div></div>`;
}
/* 显式暴露到 window（顶层 const 不会自动成为 window 属性） */
window.PPNav = PPNav;
