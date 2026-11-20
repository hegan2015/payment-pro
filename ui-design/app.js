/* payment-pro · 支付宝对齐 UI 原型交互（零依赖） */
(function () {
  "use strict";

  /* ---- 轻量 toast ---- */
  const toastStyle = document.createElement("style");
  toastStyle.textContent = `
    .demo-toast{position:fixed;left:50%;bottom:40px;transform:translateX(-50%) translateY(20px);
      background:hsl(220 20% 14%/.92);color:#fff;padding:12px 22px;border-radius:999px;
      font-size:14px;font-family:var(--font);box-shadow:0 10px 30px -8px rgba(0,0,0,.4);
      opacity:0;transition:all .28s cubic-bezier(.4,0,.2,1);z-index:9999;pointer-events:none;
      display:flex;align-items:center;gap:8px;backdrop-filter:blur(8px)}
    .demo-toast.show{opacity:1;transform:translateX(-50%) translateY(0)}
    .demo-toast .t-dot{width:8px;height:8px;border-radius:50%;background:#00B578}`;
  document.head.appendChild(toastStyle);
  let toastTimer;
  function toast(msg) {
    let el = document.querySelector(".demo-toast");
    if (!el) { el = document.createElement("div"); el.className = "demo-toast"; document.body.appendChild(el); }
    el.innerHTML = '<span class="t-dot"></span>' + msg;
    requestAnimationFrame(() => el.classList.add("show"));
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove("show"), 2000);
  }

  /* ---- 收银台：支付方式选择 + 实付联动 ---- */
  const methods = document.querySelectorAll(".pay-method");
  const payBtn = document.getElementById("cashierPay");
  const totalEl = document.querySelector(".cashier-summary .cs-total .amt");
  const discountRow = document.querySelector(".cashier-summary .cs-row.discount span:last-child");
  const base = 29900, redpacket = 500; // 分
  function fmt(cents) { return "¥" + (cents / 100).toFixed(2); }
  function recalc() {
    const sel = document.querySelector(".pay-method.selected");
    const m = sel ? sel.dataset.method : "balance";
    const cardCut = m === "card" ? 200 : 0; // 招行立减¥2
    const total = base - redpacket - cardCut;
    if (totalEl) totalEl.textContent = fmt(total);
    if (payBtn) payBtn.textContent = "确认支付 " + fmt(total);
    if (discountRow) discountRow.textContent = "-" + fmt(redpacket + cardCut);
  }
  methods.forEach((el) => {
    el.addEventListener("click", () => {
      methods.forEach((x) => x.classList.remove("selected"));
      el.classList.add("selected");
      recalc();
    });
  });
  if (payBtn) payBtn.addEventListener("click", () => toast("已发起支付 · 服务端 TCC 编排中（演示）"));
  recalc();

  /* ---- 支付密码键盘 ---- */
  const dots = Array.from(document.querySelectorAll("#pwdDots .pwd-dot"));
  let pwdLen = 0;
  function renderDots() { dots.forEach((d, i) => d.classList.toggle("filled", i < pwdLen)); }
  renderDots();
  const keypad = document.getElementById("keypad");
  if (keypad) {
    keypad.addEventListener("click", (e) => {
      const key = e.target.closest(".key");
      if (!key) return;
      const k = key.dataset.k;
      if (k === "del") { pwdLen = Math.max(0, pwdLen - 1); renderDots(); return; }
      if (k === "bio") { toast("指纹验证通过 · 支付成功（演示）"); pwdLen = 0; renderDots(); return; }
      if (pwdLen >= 6) return;
      pwdLen++; renderDots();
      if (pwdLen === 6) {
        setTimeout(() => { toast("密码校验通过 · 支付成功 ¥294.00（演示）"); pwdLen = 0; renderDots(); }, 350);
      }
    });
  }

  /* ---- 付款码：条形码 + 二维码 + 定时刷新 ---- */
  const barcode = document.getElementById("barcode");
  const qr = document.getElementById("qr");
  const codeNumber = document.getElementById("codeNumber");
  const countdownEl = document.getElementById("codeCountdown");
  const refreshBtn = document.getElementById("codeRefresh");
  function rand(n) { return Math.floor(Math.random() * n); }
  function drawBarcode() {
    if (!barcode) return;
    barcode.innerHTML = "";
    for (let i = 0; i < 60; i++) {
      const bar = document.createElement("span");
      bar.className = "bar";
      bar.style.width = (rand(3) + 1) + "px";
      bar.style.opacity = Math.random() > 0.15 ? "1" : "0";
      barcode.appendChild(bar);
    }
  }
  function drawQR() {
    if (!qr) return;
    const N = 25, cell = 100 / N;
    let svg = "";
    const finder = (x, y) => {
      svg += `<rect x="${x*cell}" y="${y*cell}" width="${7*cell}" height="${7*cell}" fill="#1A1D24"/>`;
      svg += `<rect x="${(x+1)*cell}" y="${(y+1)*cell}" width="${5*cell}" height="${5*cell}" fill="#fff"/>`;
      svg += `<rect x="${(x+2)*cell}" y="${(y+2)*cell}" width="${3*cell}" height="${3*cell}" fill="#1677FF"/>`;
    };
    for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
      const inFinder = (x < 8 && y < 8) || (x > N - 9 && y < 8) || (x < 8 && y > N - 9);
      if (!inFinder && Math.random() > 0.5) svg += `<rect x="${x*cell}" y="${y*cell}" width="${cell}" height="${cell}" fill="#1A1D24"/>`;
    }
    finder(0, 0); finder(N - 7, 0); finder(0, N - 7);
    qr.innerHTML = svg;
  }
  function newCode() {
    drawBarcode(); drawQR();
    if (codeNumber) {
      let s = "";
      for (let i = 0; i < 19; i++) { s += rand(10); if (i % 4 === 3 && i < 18) s += " "; }
      codeNumber.textContent = s;
    }
    countdown = 60;
  }
  let countdown = 60;
  if (refreshBtn) refreshBtn.addEventListener("click", () => { newCode(); toast("付款码已刷新"); });
  newCode();
  setInterval(() => {
    countdown--;
    if (countdown <= 0) newCode();
    if (countdownEl) countdownEl.textContent = countdown + "s";
  }, 1000);

  /* ---- 商户控制台：趋势图 + 侧栏切换 ---- */
  const chart = document.getElementById("chart");
  if (chart) {
    const data = [62, 78, 55, 90, 72, 96, 84];
    const days = ["周四", "周五", "周六", "周日", "周一", "周二", "今天"];
    chart.innerHTML = data.map((v, i) =>
      `<div class="bar-col"><div class="bar" style="height:${v}%"></div><span class="bar-label">${days[i]}</span></div>`
    ).join("");
  }
  const nav = document.getElementById("consoleNav");
  if (nav) {
    nav.addEventListener("click", (e) => {
      const item = e.target.closest(".console-nav-item");
      if (!item) return;
      nav.querySelectorAll(".console-nav-item").forEach((x) => x.classList.remove("active"));
      item.classList.add("active");
      const title = document.querySelector(".console-top .ct-title");
      if (title) title.textContent = item.textContent.trim();
    });
  }

  /* ---- 钱包快捷入口 ---- */
  document.querySelectorAll(".wallet-action, .wallet-grid-item").forEach((el) => {
    el.style.cursor = "pointer";
    el.addEventListener("click", () => toast(el.textContent.trim() + " · 演示入口"));
  });
})();