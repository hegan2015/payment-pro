/* payment-pro · C 端移动 H5 共享逻辑（零构建链 / 零外部 CDN / 原生 JS）
   把 ui-design 纯 mock 原型换成真实端点调用：fetch 相对路径 /api/*，页面由网关提供。
   绝不能用 file:// 打开（下方协议守卫）。视觉复用 design-system.css 令牌与组件。 */
(function () {
  "use strict";

  /* ---- file:// 协议守卫（与控制台同形态）---- */
  if (location.protocol === "file:") {
    document.addEventListener("DOMContentLoaded", function () {
      var g = document.createElement("div");
      g.className = "proto-guard";
      g.innerHTML = '<div class="pg-card"><h2>请通过网关访问</h2>'
        + '<p>当前以 <code>file://</code> 打开，页面里的 <code>/api/*</code> 请求会落到本地文件路径，登录与支付都不可用。<br><br>'
        + '请启动 payment-pro 网关后访问：<br><code>http://127.0.0.1:8080/h5/login.html</code><br>'
        + '（HTTPS 自签：<code>https://127.0.0.1:8443/h5/login.html</code>）</p></div>';
      document.body.appendChild(g);
    });
    return;
  }

  /* ---- SVG 图标库（注入 sprite，页面用 <use href="#i-*">）---- */
  var SPRITE = '<svg xmlns="http://www.w3.org/2000/svg" style="display:none">'
    + '<symbol id="i-scan" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7V5a2 2 0 0 1 2-2h2M17 3h2a2 2 0 0 1 2 2v2M21 17v2a2 2 0 0 1-2 2h-2M7 21H5a2 2 0 0 1-2-2v-2M3 12h18"/></symbol>'
    + '<symbol id="i-code" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><path d="M14 14h3v3h-3zM20 14v.01M14 20v.01M20 20v.01M17 20h.01"/></symbol>'
    + '<symbol id="i-card" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="5" width="20" height="14" rx="2"/><path d="M2 10h20M6 15h4"/></symbol>'
    + '<symbol id="i-bill" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 3h14v18l-2.5-1.5L14 21l-2-1.5L10 21l-2.5-1.5L5 21z"/><path d="M9 8h6M9 12h6"/></symbol>'
    + '<symbol id="i-balance" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 7v10M9 9.5c0-1 1.3-1.5 3-1.5s3 .5 3 1.5-1.3 1.5-3 1.5-3 .5-3 1.5 1.3 1.5 3 1.5 3-.5 3-1.5"/></symbol>'
    + '<symbol id="i-huabei" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3l8 4v5c0 4.5-3.2 7.8-8 9-4.8-1.2-8-4.5-8-9V7z"/><path d="M9 12l2 2 4-4"/></symbol>'
    + '<symbol id="i-bank" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 9l9-6 9 6M4 9v10M20 9v10M8 12v4M12 12v4M16 12v4M2 21h20"/></symbol>'
    + '<symbol id="i-transfer" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 8h13l-3-3M20 16H7l3 3"/></symbol>'
    + '<symbol id="i-topup" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 8v8M8 12h8"/></symbol>'
    + '<symbol id="i-withdraw" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M8 12h8M12 12l-3-3M12 12l-3 3"/></symbol>'
    + '<symbol id="i-redpacket" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="3" width="16" height="18" rx="2"/><path d="M4 8c4 2 12 2 16 0M12 11v3"/><circle cx="12" cy="11" r="1.5"/></symbol>'
    + '<symbol id="i-more" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="5" cy="12" r="1.5"/><circle cx="12" cy="12" r="1.5"/><circle cx="19" cy="12" r="1.5"/></symbol>'
    + '<symbol id="i-check" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M4 12l5 5L20 6"/></symbol>'
    + '<symbol id="i-shield" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3l8 3v6c0 4.5-3.2 7.8-8 9-4.8-1.2-8-4.5-8-9V6z"/><path d="M9 12l2 2 4-4"/></symbol>'
    + '<symbol id="i-refresh" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 12a8 8 0 1 1-2.34-5.66M20 4v4h-4"/></symbol>'
    + '<symbol id="i-back" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 6l-6 6 6 6"/></symbol>'
    + '<symbol id="i-close" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 6l12 12M18 6L6 18"/></symbol>'
    + '<symbol id="i-delete" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 5H8l-5 7 5 7h13a1 1 0 0 0 1-1V6a1 1 0 0 0-1-1zM17 9l-6 6M11 9l6 6"/></symbol>'
    + '<symbol id="i-fingerprint" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 4a8 8 0 0 1 8 8M4 12a8 8 0 0 1 4-6.9M7 12a5 5 0 0 1 10 0v3M12 12v5M9 15a3 3 0 0 0 3 3M16 16a4 4 0 0 1-1 3"/></symbol>'
    + '<symbol id="i-wallet" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><path d="M16 12h4M3 9h16"/></symbol>'
    + '<symbol id="i-clock" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></symbol>'
    + '<symbol id="i-lock" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="10" width="16" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/></symbol>'
    + '<symbol id="i-eye" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z"/><circle cx="12" cy="12" r="3"/></symbol>'
    + '<symbol id="i-search" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/></symbol>'
    + '<symbol id="i-down" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M6 13l6 6 6-6"/></symbol>'
    + '<symbol id="i-grid" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/></symbol>'
    + '<symbol id="i-txn" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 7h13l-3-3M20 17H7l3 3"/></symbol>'
    + '<symbol id="i-split" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="6" cy="6" r="2.5"/><circle cx="6" cy="18" r="2.5"/><circle cx="18" cy="12" r="2.5"/><path d="M8.5 6H12a3 3 0 0 1 3 3v.5M8.5 18H12a3 3 0 0 0 3-3v-.5"/></symbol>'
    + '<symbol id="i-signal" viewBox="0 0 24 24" fill="currentColor"><rect x="2" y="14" width="3" height="6" rx="1"/><rect x="7" y="10" width="3" height="10" rx="1"/><rect x="12" y="6" width="3" height="14" rx="1"/><rect x="17" y="3" width="3" height="17" rx="1"/></symbol>'
    + '<symbol id="i-wifi" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M2 8.5a15 15 0 0 1 20 0M5 12a10 10 0 0 1 14 0M8.5 15.5a5 5 0 0 1 7 0M12 19h.01"/></symbol>'
    + '<symbol id="i-battery" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="7" width="18" height="10" rx="2"/><rect x="4" y="9" width="12" height="6" rx="1" fill="currentColor" stroke="none"/><path d="M22 10v4" stroke-linecap="round"/></symbol>'
    + '</svg>';

  function injectSprite() {
    if (document.getElementById("pp-sprite")) return;
    var d = document.createElement("div");
    d.id = "pp-sprite";
    d.innerHTML = SPRITE;
    document.body.insertBefore(d, document.body.firstChild);
  }

  /* ---- 会话 token（sessionStorage，页签隔离，不污染用户其它页签）---- */
  var TOKEN_KEY = "pp_h5_token";
  function getToken() { try { return sessionStorage.getItem(TOKEN_KEY); } catch (e) { return null; } }
  function setToken(t) { try { sessionStorage.setItem(TOKEN_KEY, t); } catch (e) {} }
  function clearToken() { try { sessionStorage.removeItem(TOKEN_KEY); } catch (e) {} }

  /* ---- 真实端点调用：相对路径 /api/*，带 Bearer，no-store ---- */
  function api(method, path, body) {
    var headers = { "Accept": "application/json" };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    var t = getToken();
    if (t) headers["Authorization"] = "Bearer " + t;
    return fetch(path, {
      method: method, headers: headers, cache: "no-store",
      body: body !== undefined ? JSON.stringify(body) : undefined
    }).then(function (res) {
      if (res.status === 401) {
        clearToken();
        go("login.html", { next: location.pathname + location.search });
        throw new Error("未登录或会话已过期");
      }
      return res.text().then(function (text) {
        var data = null;
        try { data = text ? JSON.parse(text) : null; } catch (e) { data = { error: text }; }
        if (!res.ok) {
          var err = new Error((data && data.error) || ("HTTP " + res.status));
          err.status = res.status; err.data = data;
          throw err;
        }
        return data;
      });
    });
  }

  function login(username, password) {
    return api("POST", "/api/auth/login", { username: username, password: password })
      .then(function (d) { setToken(d.token); return d; });
  }
  function logout() {
    return api("POST", "/api/auth/logout", {}).catch(function () {}).then(function () {
      clearToken(); go("login.html");
    });
  }
  function requireAuth() {
    if (!getToken()) { go("login.html", { next: location.pathname + location.search }); return false; }
    return true;
  }

  /* ---- 金额：分 → 元（千分位 + 两位小数），杜绝浮点存储 ---- */
  function yuan(cents) { return (Number(cents || 0) / 100).toFixed(2); }
  function fmt(cents) {
    var v = yuan(cents), p = v.split(".");
    return "¥" + p[0].replace(/\B(?=(\d{3})+(?!\d))/g, ",") + "." + p[1];
  }
  function fmtPlain(cents) {
    var v = yuan(cents), p = v.split(".");
    return p[0].replace(/\B(?=(\d{3})+(?!\d))/g, ",") + "." + p[1];
  }

  /* ---- 导航 / 查询参数 ---- */
  function qs() {
    var o = {};
    location.search.replace(/^\?/, "").split("&").forEach(function (kv) {
      if (!kv) return;
      var i = kv.indexOf("=");
      var k = i < 0 ? kv : kv.slice(0, i);
      var v = i < 0 ? "" : decodeURIComponent(kv.slice(i + 1).replace(/\+/g, " "));
      o[decodeURIComponent(k)] = v;
    });
    return o;
  }
  function go(page, params) {
    var q = "";
    if (params) {
      q = "?" + Object.keys(params).filter(function (k) { return params[k] !== undefined && params[k] !== null; })
        .map(function (k) { return encodeURIComponent(k) + "=" + encodeURIComponent(params[k]); }).join("&");
    }
    location.href = page + q;
  }

  /* ---- toast ---- */
  var toastTimer;
  function toast(msg, isErr) {
    var el = document.querySelector(".h5-toast");
    if (!el) { el = document.createElement("div"); el.className = "h5-toast"; document.body.appendChild(el); }
    el.className = "h5-toast" + (isErr ? " err" : "");
    el.innerHTML = '<span class="t-dot"></span><span></span>';
    el.lastChild.textContent = msg;
    requestAnimationFrame(function () { el.classList.add("show"); });
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.classList.remove("show"); }, 2400);
  }

  /* ---- 支付上下文（cashier → verify → result 跨页传递）---- */
  function setPayCtx(ctx) { try { sessionStorage.setItem("pp_h5_pay", JSON.stringify(ctx)); } catch (e) {} }
  function getPayCtx() { try { return JSON.parse(sessionStorage.getItem("pp_h5_pay") || "null"); } catch (e) { return null; } }

  /* ---- 沙箱示例数据 provisioning（CSHTEST 前缀 + 随机；一次会话复用）---- */
  function provisionDemo(orderAmount) {
    var cached = null;
    try { cached = JSON.parse(sessionStorage.getItem("pp_h5_demo") || "null"); } catch (e) {}
    if (cached && cached.orderId) return Promise.resolve(cached);
    var tag = "CSHTEST-H5-" + Math.random().toString(36).slice(2, 8);
    var demo = { tag: tag };
    return api("POST", "/api/members", { name: "星巴克咖啡(示例)" + tag, type: "MERCHANT" })
      .then(function (m) { demo.merchantId = m.id; return api("POST", "/api/members", { name: "示例用户" + tag, type: "USER" }); })
      .then(function (u) { demo.userId = u.id; return api("POST", "/api/accounts", { memberId: m_id(demo) }); })
      .then(function (ma) { demo.merchantAccountId = ma.id; return api("POST", "/api/accounts", { memberId: demo.userId }); })
      .then(function (ua) {
        demo.accountId = ua.id;
        return api("POST", "/api/accounting/internal/topup", { accountId: ua.id, amount: 128050, bizNo: "topup-" + tag });
      })
      .then(function () {
        return api("POST", "/api/orders", { merchantId: demo.merchantId, subject: "星巴克拿铁套餐", amount: orderAmount || 29900 });
      })
      .then(function (r) {
        demo.orderId = r.order.id; demo.amount = r.order.amount; demo.balance = 128050;
        try { sessionStorage.setItem("pp_h5_demo", JSON.stringify(demo)); } catch (e) {}
        return demo;
      });
  }
  function m_id(d) { return d.merchantId; }

  /* ---- 付款码视觉：条形码 + 二维码（沙箱示意，非真实可扫矩阵；码值以文本展示供解析）---- */
  function drawBarcode(el) {
    if (!el) return;
    el.innerHTML = "";
    for (var i = 0; i < 60; i++) {
      var bar = document.createElement("span");
      bar.className = "bar";
      bar.style.width = (Math.floor(Math.random() * 3) + 1) + "px";
      bar.style.opacity = Math.random() > 0.15 ? "1" : "0";
      el.appendChild(bar);
    }
  }
  function drawQR(el, seed) {
    if (!el) return;
    var N = 25, cell = 100 / N, svg = "";
    var rnd = seededRandom(seed || "pp");
    function finder(x, y) {
      svg += '<rect x="' + (x * cell) + '" y="' + (y * cell) + '" width="' + (7 * cell) + '" height="' + (7 * cell) + '" fill="#1A1D24"/>';
      svg += '<rect x="' + ((x + 1) * cell) + '" y="' + ((y + 1) * cell) + '" width="' + (5 * cell) + '" height="' + (5 * cell) + '" fill="#fff"/>';
      svg += '<rect x="' + ((x + 2) * cell) + '" y="' + ((y + 2) * cell) + '" width="' + (3 * cell) + '" height="' + (3 * cell) + '" fill="#1677FF"/>';
    }
    for (var y = 0; y < N; y++) for (var x = 0; x < N; x++) {
      var inFinder = (x < 8 && y < 8) || (x > N - 9 && y < 8) || (x < 8 && y > N - 9);
      if (!inFinder && rnd() > 0.5) svg += '<rect x="' + (x * cell) + '" y="' + (y * cell) + '" width="' + cell + '" height="' + cell + '" fill="#1A1D24"/>';
    }
    finder(0, 0); finder(N - 7, 0); finder(0, N - 7);
    el.innerHTML = svg;
  }
  function seededRandom(seed) {
    var h = 2166136261;
    for (var i = 0; i < seed.length; i++) { h ^= seed.charCodeAt(i); h = Math.imul(h, 16777619); }
    return function () { h = Math.imul(h ^ (h >>> 15), 2246822507); h = Math.imul(h ^ (h >>> 13), 3266489909); return ((h ^= h >>> 16) >>> 0) / 4294967296; };
  }

  /* ---- 支付单终态轮询（PROCESSING → SUCCESS/FAILED）---- */
  function pollPayment(pid, onUpdate, timeoutMs) {
    var deadline = Date.now() + (timeoutMs || 30000);
    function step() {
      return api("GET", "/api/payments/" + pid).then(function (p) {
        if (onUpdate) onUpdate(p);
        if (p.status !== "PROCESSING" || Date.now() > deadline) return p;
        return new Promise(function (r) { setTimeout(r, 800); }).then(step);
      });
    }
    return step();
  }

  /* ---- 状态栏 / 手机框骨架（页面复用）---- */
  function statusbar(onBlue) {
    return '<div class="statusbar' + (onBlue ? " on-blue" : "") + '"><span class="num">9:41</span>'
      + '<span class="sb-right"><svg><use href="#i-signal"/></svg><svg><use href="#i-wifi"/></svg><svg><use href="#i-battery"/></svg></span></div>';
  }

  window.PP = {
    api: api, login: login, logout: logout, requireAuth: requireAuth, getToken: getToken,
    yuan: yuan, fmt: fmt, fmtPlain: fmtPlain, qs: qs, go: go, toast: toast,
    setPayCtx: setPayCtx, getPayCtx: getPayCtx, provisionDemo: provisionDemo,
    drawBarcode: drawBarcode, drawQR: drawQR, pollPayment: pollPayment, statusbar: statusbar
  };

  document.addEventListener("DOMContentLoaded", function () {
    injectSprite();
    var page = document.body.getAttribute("data-page");
    if (page && page !== "login") requireAuth();
  });
})();