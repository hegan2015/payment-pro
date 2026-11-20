#!/usr/bin/env python3
"""verify-cashier.py — 收银台工作流 C-1/C-2/C-3 + **收银台开放 OpenAPI**（ride-pro 对接面）验证。

跑法（gateway 8080，栈需已启动；docker profile 继承 --docker）：
  python3 verify-cashier.py

覆盖：
  A1. payment 自带 C 端 H5 已摘除：/h5/* 全部 404；原型完整保留在 ui-design/h5-prototype/
      （DOM 锚点/样式令牌/JS 语法 node --check 逐项校验，可逆不硬删）；index.html 无 /h5 链接
  A2. 收银台 OpenAPI 9 方法（cashier.methods/code.create/code.parse/code.query/scanPay/verify/
      payVerified/payPassword.set/sms.send）：真实商户 HMAC-SHA256 验签通过 + 逐方法错签拒绝 +
      timestamp 毫秒窗（过期/未来/缺失/非法）+ 未知 appId + nonce 重放 409/超长 400 + 免 Bearer +
      幂等 requestId + 脱敏（卡号后四位/口令不回显/appSecret 不外泄/短信码默认掩码）+
      付款码一次性与过期 + 验证四型与锁定 + pay-verified 衔接 + 既有 11 商户方法不回归
  B. C-1 多支付方式动态渲染：BALANCE/CARD 可用+限额；RESERVED（QUICK_PAY/NET_BANK/CREDIT/COUPON/COMBO）
     不可用+原因；维护窗口标记；冻结账户/余额不足 → BALANCE 不可用；聚合渲染 smoke 兼容
  C. C-2 付款码/扫码沙箱：被扫 18 位码 + 主扫二维码令牌生成/解析；扫码受理成功（BALANCE 同步）；
     一次性核销（防重放）；核销幂等（同 requestId 不重复扣款）；并发核销只一次；过期拒绝
  D. C-3 支付验证：口令错误拦截+不受理+锁定；正确→verifyToken→受理；验证+受理同 requestId 幂等；
     小额免密；超免密需验证；令牌一次性+绑定校验；短信沙箱；指纹模拟
  E. ride 红线：/api/cashier/pay BALANCE 同步语义不回归（受理→PROCESSING→SUCCESS，手续费口径不变，
     requestId 幂等 repeated，订单级重复 409，缺 requestId 400，绝不出现 WAITING）

数据保护：测试数据 CSHTEST/CSOPTEST 前缀 + uuid，金额小；只新增，不删/改既有行；冻结测试账户用后即恢复。
"""
import glob
import hashlib
import hmac
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("PP_GATEWAY", "http://127.0.0.1:8080")
ROOT = os.path.dirname(os.path.abspath(__file__))
# H5 已从服务路径摘除，原型保留在此（可逆，不硬删）
PROTO_DIR = os.path.join(ROOT, "ui-design", "h5-prototype")
SUFFIX = uuid.uuid4().hex[:6]
TAG = "CSHTEST" + SUFFIX
OPTAG = "CSOPTEST" + SUFFIX
PASSED = FAILED = SKIPPED = 0
TOKEN = None
# 开放平台调用凭据（main 中由真实商户 create 返回填充）
OP = {"app_id": None, "secret": None}
OP_TEXTS = []


def check(name, cond, detail=""):
    global PASSED, FAILED
    if cond:
        PASSED += 1
        print(f"  [PASS] {name}")
    else:
        FAILED += 1
        print(f"  [FAIL] {name}  {detail}")


def skip(name, why):
    global SKIPPED
    SKIPPED += 1
    print(f"  [SKIP] {name}  ({why})")


def _login():
    global TOKEN
    try:
        r = urllib.request.Request(BASE + "/api/auth/login",
                                   data=json.dumps({"username": "admin",
                                                    "password": os.environ.get("PP_ADMIN_PASSWORD", "admin123")}).encode(),
                                   method="POST", headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(r, timeout=10) as resp:
            TOKEN = json.loads(resp.read().decode()).get("token")
    except Exception:
        TOKEN = None


def _req_impl(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Accept", "application/json")
    if data is not None:
        r.add_header("Content-Type", "application/json")
    if TOKEN and path.startswith("/api/") and path != "/api/auth/login":
        r.add_header("Authorization", "Bearer " + TOKEN)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            text = resp.read().decode()
            return resp.status, json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        try:
            return e.code, json.loads(text)
        except Exception:
            return e.code, {"error": text}
    except Exception as e:
        return 0, {"error": str(e)}


def req(method, path, body=None):
    st, resp = _req_impl(method, path, body)
    if st == 401 and path.startswith("/api/") and path != "/api/auth/login":
        _login()
        if TOKEN:
            st, resp = _req_impl(method, path, body)
    # 风控滑动窗口限频（默认 5 笔/10s，同一付款账户）：本脚本多场景复用同一账户必然触窗。
    # 尊重风控——等窗口重置后重试，绝不放宽阈值或删断言。
    tries = 0
    while (st == 400 and isinstance(resp, dict) and resp.get("code") == "B0001"
           and "频繁" in str(resp.get("error", "")) and tries < 4):
        tries += 1
        time.sleep(11)
        st, resp = _req_impl(method, path, body)
    return st, resp


def ok(method, path, body=None):
    st, resp = req(method, path, body)
    assert st == 200, f"{method} {path} 期望 200，实际 {st}: {resp}"
    return resp


def raw_get(path):
    """静态资源（HTML/CSS/JS）原始 GET，返回 (status, text)，不带 Bearer（permitAll）。"""
    r = urllib.request.Request(BASE + path, method="GET")
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except Exception as e:
        return 0, str(e)


def poll(fn, pred, timeout=30, interval=0.5):
    deadline = time.time() + timeout
    val = fn()
    while time.time() < deadline and not pred(val):
        time.sleep(interval)
        val = fn()
    return val


def bal(aid):
    return ok("GET", f"/api/accounting/accounts/{aid}/balance")


def payment(pid):
    return ok("GET", f"/api/payments/{pid}")


def wait_terminal(pid, timeout=30):
    return poll(lambda: payment(pid), lambda p: p["status"] != "PROCESSING", timeout=timeout)


def fee_of(amount):
    return (amount * 60 + 5000) // 10000


def new_order(merchant_id, amount, subject):
    return ok("POST", "/api/orders", {"merchantId": merchant_id, "subject": subject, "amount": amount})["order"]


def find_node():
    for c in ("node", "nodejs"):
        p = shutil.which(c)
        if p:
            return p
    cands = ["/opt/homebrew/bin/node", "/usr/local/bin/node"]
    cands += glob.glob(os.path.expanduser("~/Library/Application Support/Doubao/sandbox_runtime/bases/*/bin/node"))
    cands += glob.glob(os.path.expanduser("~/.nvm/versions/node/*/bin/node"))
    cands += glob.glob(os.path.expanduser("~/.volta/bin/node"))
    for c in cands:
        if os.path.isfile(c) and os.access(c, os.X_OK):
            return c
    return None


# ============================================================================
# A1. payment 自带 C 端 H5 已摘除（原型完整保留，可逆）
# ============================================================================
PAGES = {
    "login.html": ["loginBtn", 'id="u"', 'id="p"', "data-page=\"login\""],
    "wallet.html": ["wallet-balance", 'id="walletBal"', "wallet-actions", 'data-nav="scan.html"', 'data-nav="paycode.html"', 'id="toCashier"'],
    "cashier.html": ["cashier-amount", 'id="orderAmount"', 'id="methodList"', "cashier-summary", 'id="sumActual"', 'id="cashierPay"', 'id="merchantName"'],
    "verify.html": ['id="pwdDots"', 'id="keypad"', "pwd-amount", 'id="smsSend"', 'id="bioBtn"', "verify-tabs", 'id="setPwd"'],
    "result.html": ['id="resultIcon"', 'id="resultTitle"', 'id="resultMeta"', 'id="procWrap"', 'id="retryBtn"', "spinner"],
    "paycode.html": ['id="barcode"', 'id="qr"', 'id="codeNumber"', 'id="codeCountdown"', 'id="codeRefresh"', "code-safe", 'id="simScan"'],
    "scan.html": ["scan-frame", 'id="codeInput"', 'id="parseBtn"', 'id="genMerchant"', 'id="confirmScan"', 'id="merchantQr"'],
    "bill.html": ['id="billList"', 'id="billOut"', 'id="billIn"', "bill-list", "bill-summary"],
}


def section_h5_removed():
    print("[A1] C 端 H5 摘除：/h5/* 全 404 + ui-design/h5-prototype 原型完整保留")
    for page, anchors in PAGES.items():
        st, _ = raw_get("/h5/" + page)
        check(f"/h5/{page} 已摘除 404", st == 404, str(st))
        path = os.path.join(PROTO_DIR, page)
        text = open(path, encoding="utf-8").read() if os.path.isfile(path) else ""
        missing = [a for a in anchors if a not in text]
        check(f"原型 {page} 保留 + DOM 锚点齐全", bool(text) and not missing, "缺:" + ",".join(missing))
        check(f"原型 {page} 引用 design-system.css + app.js",
              "design-system.css" in text and "app.js" in text)
    for asset in ("design-system.css", "app.css", "app.js"):
        st, _ = raw_get("/h5/" + asset)
        check(f"/h5/{asset} 已摘除 404", st == 404, str(st))
    st, _ = raw_get("/h5/")
    check("/h5/ 目录入口 404", st == 404, str(st))

    css = open(os.path.join(PROTO_DIR, "design-system.css"), encoding="utf-8").read()
    check("原型 design-system.css 品牌令牌 --primary 保留", "--primary" in css and "215 100% 54%" in css)
    appcss = open(os.path.join(PROTO_DIR, "app.css"), encoding="utf-8").read()
    check("原型 app.css 真机适配 .h5-stage 保留", ".h5-stage" in appcss)
    appjs = open(os.path.join(PROTO_DIR, "app.js"), encoding="utf-8").read()
    check("原型 app.js file:// 协议守卫保留",
          "location.protocol" in appjs and '"file:"' in appjs and "proto-guard" in appjs)
    check("原型 app.js fetch 相对路径 /api/*（无绝对 host）",
          'fetch(path' in appjs and "/api/auth/login" in appjs and 'fetch("http' not in appjs)
    check("原型 app.js 金额分→元（/100 + toFixed(2)）", "/ 100" in appjs and "toFixed(2)" in appjs)
    check("原型 app.js 零外部 CDN", "cdn." not in appjs and "unpkg" not in appjs and "jsdelivr" not in appjs)
    st, index = raw_get("/")
    check("控制台首页仍 200（未受影响）", st == 200, str(st))
    check("控制台首页无 /h5/ 链接", "/h5/" not in index, "index 仍指向 /h5/")

    node = find_node()
    if not node:
        skip("原型 JS 语法 node --check", "未找到 node 可执行文件")
    else:
        r = subprocess.run([node, "--check", os.path.join(PROTO_DIR, "app.js")], capture_output=True, text=True)
        check("原型 app.js node --check 语法通过", r.returncode == 0, r.stderr[:300])
        inline_fail = []
        for page in PAGES:
            html = open(os.path.join(PROTO_DIR, page), encoding="utf-8").read()
            for i, m in enumerate(re.finditer(r'<script(?![^>]*\bsrc=)[^>]*>(.*?)</script>', html, re.S)):
                code = m.group(1)
                if not code.strip():
                    continue
                with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False, encoding="utf-8") as t:
                    t.write(code)
                    tp = t.name
                rr = subprocess.run([node, "--check", tp], capture_output=True, text=True)
                os.unlink(tp)
                if rr.returncode != 0:
                    inline_fail.append(f"{page}#{i}:{rr.stderr[:120]}")
        check("原型各页内联脚本 node --check 语法通过", not inline_fail, ";".join(inline_fail))


# ============================================================================
# A2. 收银台开放 OpenAPI（ride-pro 对接面）：HMAC 签名工具 + 9 方法
# ============================================================================
OPEN_METHODS = ["cashier.methods", "cashier.code.create", "cashier.code.parse", "cashier.code.query",
                "cashier.scanPay", "cashier.verify", "cashier.payVerified",
                "cashier.payPassword.set", "cashier.sms.send"]


def sign_params(params, secret):
    """与网关 canonical 逐字节一致：TreeMap 排序 k=v&（剔除 sign、跳过 null）→ HMAC-SHA256 hex。"""
    items = sorted((k, v) for k, v in params.items() if v is not None and k != "sign")
    data = "&".join(f"{k}={v}" for k, v in items)
    return hmac.new(secret.encode(), data.encode(), hashlib.sha256).hexdigest()


def op_body(params=None, **kw):
    body = {}
    for k, v in (params or {}).items():
        # canonical 用 Java String.valueOf 渲染：布尔必须是小写 true/false（Python str(True)="True" 会错签）
        body[k] = ("true" if v else "false") if isinstance(v, bool) else v
    body["appId"] = kw.get("app_id", OP["app_id"])
    body["timestamp"] = kw.get("timestamp", int(time.time() * 1000))
    if kw.get("nonce") is not None:
        body["nonce"] = kw["nonce"]
    body["sign"] = kw.get("sign") or sign_params(body, kw.get("secret", OP["secret"]))
    return body


def op(method, params=None, **kw):
    """OpenAPI 调用：req() 对 /openapi/* 不加 Bearer（HMAC 独立链，免登录态）。"""
    st, resp = req("POST", "/openapi/" + method, op_body(params, **kw))
    OP_TEXTS.append(json.dumps(resp, ensure_ascii=False))
    return st, resp


def op_ok(method, params=None, **kw):
    st, resp = op(method, params, **kw)
    assert st == 200, f"openapi {method} 期望 200，实际 {st}: {resp}"
    return resp


def new_payer(name, topup=100000):
    m = ok("POST", "/api/members", {"name": name, "type": "USER"})
    a = ok("POST", "/api/accounts", {"memberId": m["id"]})
    if topup:
        ok("POST", "/api/accounting/internal/topup",
           {"accountId": a["id"], "amount": topup, "bizNo": f"{OPTAG}-{name[-4:]}-top"})
    return a


def section_openapi(ctx):
    print("[A2] 收银台 OpenAPI 9 方法：验签/错签/时间窗/nonce/幂等/脱敏/码一次性过期/验证四型/衔接")
    merchant, ma = ctx["merchant"], ctx["ma"]
    # 独立付款人：与 B/C/D 段隔离，避免风控滑动窗口（5 笔/10s）污染断言（不放宽任何阈值）
    u_scan = new_payer(f"{OPTAG}开放扫码用户")
    u_ver = new_payer(f"{OPTAG}开放验证用户")
    u_lock = new_payer(f"{OPTAG}开放锁定用户", topup=0)
    o1 = new_order(merchant["id"], 100, "开放被扫商品")

    # ---- 验签通过 + 渲染结构（免 Bearer） ----
    dto = op_ok("cashier.methods", {"orderId": o1["id"], "accountId": u_scan["id"]})
    check("cashier.methods 真实商户 HMAC 验签通过（免 Bearer）", isinstance(dto, dict) and "methods" in dto)
    om = {x["method"]: x for x in dto["methods"]}
    check("cashier.methods 返回结构与控制台面一致（9 方式 + 汇总）",
          all(k in om for k in ["BALANCE", "CARD", "PAY_CODE", "SCAN", "QUICK_PAY", "NET_BANK", "CREDIT", "COUPON", "COMBO"])
          and dto["actualAmount"] == 100 and isinstance(dto.get("freeVerifyLimit"), int), str(list(om)))
    check("cashier.methods 账户级可用性正确（BALANCE 可用）", om["BALANCE"]["available"] is True)
    agg = op_ok("cashier.methods", {"orderId": o1["id"]})
    check("cashier.methods 缺省 accountId 回退聚合渲染", any(a["id"] == u_scan["id"] for a in
                                                    {x["method"]: x for x in agg["methods"]}["BALANCE"]["accounts"]))

    # ---- 错签/时间窗/appId/nonce 治理（全部受理前拒绝） ----
    st, resp = op("cashier.methods", {"orderId": o1["id"]}, sign="0" * 64)
    check("cashier.methods 错签拒绝 400 A0400", st == 400 and resp.get("code") == "A0400" and "签名" in resp.get("error", ""), str(resp))
    st, resp = op("cashier.methods", {"orderId": o1["id"]}, timestamp=int(time.time() * 1000) - 600000)
    check("过期 timestamp（毫秒窗 ±300s）拒绝 400", st == 400 and "timestamp" in resp.get("error", ""), str(resp))
    st, resp = op("cashier.methods", {"orderId": o1["id"]}, timestamp=int(time.time() * 1000) + 600000)
    check("未来 timestamp 超窗拒绝 400", st == 400, str(resp))
    bad = op_body({"orderId": o1["id"]})
    bad.pop("timestamp")
    st, resp = req("POST", "/openapi/cashier.methods", bad)
    check("缺 timestamp 拒绝 400", st == 400, str(resp))
    bad = op_body({"orderId": o1["id"]})
    bad["timestamp"] = "not-a-number"
    st, resp = req("POST", "/openapi/cashier.methods", bad)
    check("非法 timestamp 拒绝 400", st == 400, str(resp))
    bad = op_body({"orderId": o1["id"]})
    bad.pop("appId")
    st, resp = req("POST", "/openapi/cashier.methods", bad)
    check("缺 appId 拒绝 400", st == 400, str(resp))
    st, resp = op("cashier.methods", {"orderId": o1["id"]}, app_id=OPTAG + "NOEXIST")
    check("未知 appId 拒绝 404", st == 404, str(resp))
    nonce = OPTAG + "-nonce"
    st1, _ = op("cashier.methods", {"orderId": o1["id"]}, nonce=nonce)
    st2, resp2 = op("cashier.methods", {"orderId": o1["id"]}, nonce=nonce)
    check("nonce 首次使用通过", st1 == 200, str(st1))
    check("nonce 重放拒绝 409 A0409", st2 == 409 and resp2.get("code") == "A0409", str(resp2))
    st, resp = op("cashier.methods", {"orderId": o1["id"]}, nonce="x" * 65)
    check("nonce 超长拒绝 400", st == 400, str(resp))
    st, _ = op("cashier.methods", {"orderId": o1["id"]})
    check("nonce 可选：不传时正常受理（ride 兼容红线）", st == 200, str(st))

    # ---- 9 方法逐个错签拒绝 + 拒绝不落悬挂单 ----
    b_before = bal(u_scan["id"])
    for m in OPEN_METHODS:
        st, resp = op(m, {"orderId": o1["id"], "accountId": u_scan["id"], "code": "000000000000000000",
                          "requestId": OPTAG + "-badsign", "password": "123456", "type": "USER_PRESENTED"},
                      sign="0" * 64)
        check(f"{m} 错签拒绝 400 A0400", st == 400 and resp.get("code") == "A0400", str(resp))
    check("验签失败发生在受理前：余额未动", bal(u_scan["id"]) == b_before)
    check("验签失败不落悬挂支付单", ok("GET", f"/api/orders/{o1['id']}").get("paymentId") is None)

    # ---- cashier.code.create / parse / query ----
    c1 = op_ok("cashier.code.create", {"type": "USER_PRESENTED", "accountId": u_scan["id"], "orderId": o1["id"]})
    check("cashier.code.create 被扫 18 位数字码 + ACTIVE",
          c1["type"] == "USER_PRESENTED" and re.fullmatch(r"\d{18}", c1["code"]) and c1["status"] == "ACTIVE", str(c1))
    check("cashier.code.create 有效期/倒计时/展示码下发",
          c1["expireAt"] > time.time() * 1000 and c1.get("ttlSeconds", 0) > 0 and " " in (c1.get("displayCode") or ""))
    p1 = op_ok("cashier.code.parse", {"code": c1["code"]})
    check("cashier.code.parse 金额/订单绑定正确", p1["amount"] == 100 and p1["orderId"] == o1["id"] and p1["status"] == "ACTIVE")
    q1 = op_ok("cashier.code.query", {"code": c1["code"]})
    check("cashier.code.query 状态 ACTIVE", q1["status"] == "ACTIVE")
    st, resp = op("cashier.code.parse", {"code": "000000000000000000"})
    check("cashier.code.parse 不存在码 404", st == 404, str(resp))
    st, resp = op("cashier.code.query", {"code": "000000000000000000"})
    check("cashier.code.query 不存在码 404", st == 404, str(resp))
    st, resp = op("cashier.code.parse", {})
    check("cashier.code.parse 缺 code 400", st == 400, str(resp))
    st, resp = op("cashier.code.create", {"accountId": u_scan["id"], "orderId": o1["id"]})
    check("cashier.code.create 缺 type 400", st == 400, str(resp))
    st, resp = op("cashier.code.create", {"type": "BAD_TYPE", "accountId": u_scan["id"], "orderId": o1["id"]})
    check("cashier.code.create 非法 type 400", st == 400, str(resp))
    o2 = new_order(merchant["id"], 100, "开放主扫商品")
    c2 = op_ok("cashier.code.create", {"type": "MERCHANT_PRESENTED", "accountId": ma["id"], "orderId": o2["id"]})
    check("cashier.code.create 主扫二维码令牌 + qrContent",
          c2["type"] == "MERCHANT_PRESENTED" and c2["code"].startswith("MP") and bool(c2.get("qrContent")), str(c2))
    # 过期码提前生成，末尾统一判定（避免空等）
    o_exp = new_order(merchant["id"], 100, "开放过期商品")
    ctx["op_expire_code"] = op_ok("cashier.code.create",
                                  {"type": "USER_PRESENTED", "accountId": u_scan["id"], "orderId": o_exp["id"]})

    # ---- cashier.scanPay：受理/一次性/幂等 ----
    b0 = bal(u_scan["id"])
    r1 = op_ok("cashier.scanPay", {"code": c1["code"], "requestId": OPTAG + "-scan1", "method": "BALANCE"})
    pid1 = r1["payment"]["id"]
    pa = wait_terminal(pid1)
    check("cashier.scanPay BALANCE 同步终态 SUCCESS（无 WAITING）", pa["status"] == "SUCCESS", str(pa["status"]))
    check("cashier.scanPay 手续费口径不变", pa["fee"] == fee_of(100), str(pa["fee"]))
    check("cashier.scanPay 扣款正确（100 分）", bal(u_scan["id"]) == b0 - 100)
    check("cashier.scanPay 核销后码 CONSUMED", op_ok("cashier.code.query", {"code": c1["code"]})["status"] == "CONSUMED")
    st, resp = op("cashier.scanPay", {"code": c1["code"], "requestId": OPTAG + "-replay", "method": "BALANCE"})
    check("cashier.scanPay 防重放：已核销码异 requestId 409", st == 409, str(resp))
    b1 = bal(u_scan["id"])
    r2 = op_ok("cashier.scanPay", {"code": c1["code"], "requestId": OPTAG + "-scan1", "method": "BALANCE"})
    check("cashier.scanPay 幂等：同 requestId 同一支付单 repeated",
          r2["payment"]["id"] == pid1 and r2.get("repeated") is True, str(r2))
    check("cashier.scanPay 幂等不重复扣款", bal(u_scan["id"]) == b1)
    st, resp = op("cashier.scanPay", {"code": c1["code"]})
    check("cashier.scanPay 缺 requestId 400", st == 400, str(resp))
    o3 = new_order(merchant["id"], 100, "开放主扫受理")
    c3 = op_ok("cashier.code.create", {"type": "MERCHANT_PRESENTED", "accountId": ma["id"], "orderId": o3["id"]})
    st, resp = op("cashier.scanPay", {"code": c3["code"], "requestId": OPTAG + "-mscan-nopayer", "method": "BALANCE"})
    check("cashier.scanPay 主扫缺 payerAccountId 400", st == 400, str(resp))
    b2 = bal(u_scan["id"])
    r3 = op_ok("cashier.scanPay", {"code": c3["code"], "requestId": OPTAG + "-mscan",
                                   "payerAccountId": u_scan["id"], "method": "BALANCE"})
    p3 = wait_terminal(r3["payment"]["id"])
    check("cashier.scanPay 主扫受理成功（付款方=请求指定账户）",
          p3["status"] == "SUCCESS" and p3["payerAccountId"] == u_scan["id"], str(p3))
    check("cashier.scanPay 主扫扣款正确", bal(u_scan["id"]) == b2 - 100)

    # ---- cashier.payPassword.set + cashier.verify 四型 + 锁定 ----
    ov = new_order(merchant["id"], 3000, "开放验证商品")
    v0 = op_ok("cashier.verify", {"accountId": u_ver["id"], "orderId": ov["id"], "amount": 3000,
                                  "verifyType": "PASSWORD", "payPassword": "123456"})
    check("cashier.verify 未设口令 → NEED_SET_PASSWORD",
          v0["pass"] is False and v0["challengeType"] == "NEED_SET_PASSWORD", str(v0))
    sp = op_ok("cashier.payPassword.set", {"accountId": u_ver["id"], "password": "123456"})
    check("cashier.payPassword.set 返回裸 boolean true（口令不回显）", sp is True, str(sp))
    st, resp = op("cashier.payPassword.set", {"accountId": u_ver["id"], "password": "12"})
    check("cashier.payPassword.set 非 6 位数字 400", st == 400, str(resp))
    v1 = op_ok("cashier.verify", {"accountId": u_ver["id"], "orderId": ov["id"], "amount": 3000,
                                  "verifyType": "PASSWORD", "payPassword": "000000"})
    check("cashier.verify 错误口令 → pass=false + 原因 + 剩余次数",
          v1["pass"] is False and v1.get("reason") and v1.get("remainAttempts") is not None, str(v1))
    check("cashier.verify 响应不回显口令明文",
          "payPassword" not in json.dumps(v1) and "123456" not in json.dumps(v1), str(v1))
    bv0 = bal(u_ver["id"])
    st, resp = op("cashier.payVerified", {"orderId": ov["id"], "method": "BALANCE", "accountId": u_ver["id"],
                                          "requestId": OPTAG + "-bad", "payPassword": "000000"})
    check("cashier.payVerified 错误口令拒绝 403", st == 403, str(resp))
    st, resp = op("cashier.payVerified", {"orderId": ov["id"], "method": "BALANCE", "accountId": u_ver["id"],
                                          "requestId": OPTAG + "-noverify"})
    check("cashier.payVerified 超免密无验证拒绝 403", st == 403, str(resp))
    check("cashier.payVerified 验证失败不扣款（不落单）", bal(u_ver["id"]) == bv0)
    st, resp = op("cashier.payVerified", {"orderId": ov["id"], "method": "BALANCE", "accountId": u_ver["id"]})
    check("cashier.payVerified 缺 requestId 400", st == 400, str(resp))

    v2 = op_ok("cashier.verify", {"accountId": u_ver["id"], "orderId": ov["id"], "amount": 3000,
                                  "verifyType": "PASSWORD", "payPassword": "123456", "requestId": OPTAG + "-v1"})
    check("cashier.verify 正确口令 → pass=true + verifyToken", v2["pass"] is True and bool(v2.get("verifyToken")), str(v2))
    v2b = op_ok("cashier.verify", {"accountId": u_ver["id"], "orderId": ov["id"], "amount": 3000,
                                   "verifyType": "PASSWORD", "payPassword": "123456", "requestId": OPTAG + "-v1"})
    check("cashier.verify 同 requestId 幂等返回同一会话令牌", v2b["verifyToken"] == v2["verifyToken"], str(v2b))
    bv1 = bal(u_ver["id"])
    rv = op_ok("cashier.payVerified", {"orderId": ov["id"], "method": "BALANCE", "accountId": u_ver["id"],
                                       "requestId": OPTAG + "-pay1", "verifyToken": v2["verifyToken"]})
    pv = wait_terminal(rv["payment"]["id"])
    check("cashier.payVerified 验证衔接 BALANCE 同步 SUCCESS + 手续费口径",
          pv["status"] == "SUCCESS" and pv["fee"] == fee_of(3000), str(pv))
    check("cashier.payVerified 扣款 3000", bal(u_ver["id"]) == bv1 - 3000)
    o_reuse = new_order(merchant["id"], 3000, "开放令牌复用")
    st, resp = op("cashier.payVerified", {"orderId": o_reuse["id"], "method": "BALANCE", "accountId": u_ver["id"],
                                          "requestId": OPTAG + "-reuse", "verifyToken": v2["verifyToken"]})
    check("cashier.payVerified 令牌一次性：复用拒绝 403", st == 403, str(resp))
    o_bind = new_order(merchant["id"], 3000, "开放绑定校验")
    vb = op_ok("cashier.verify", {"accountId": u_ver["id"], "orderId": ov["id"], "amount": 3000,
                                  "verifyType": "PASSWORD", "payPassword": "123456"})
    st, resp = op("cashier.payVerified", {"orderId": o_bind["id"], "method": "BALANCE", "accountId": u_ver["id"],
                                          "requestId": OPTAG + "-bind", "verifyToken": vb["verifyToken"]})
    check("cashier.payVerified 令牌绑定订单：跨单拒绝 403", st == 403, str(resp))
    bv2 = bal(u_ver["id"])
    v3 = op_ok("cashier.verify", {"accountId": u_ver["id"], "orderId": ov["id"], "amount": 3000,
                                  "verifyType": "PASSWORD", "payPassword": "123456", "requestId": OPTAG + "-v2"})
    rv2 = op_ok("cashier.payVerified", {"orderId": ov["id"], "method": "BALANCE", "accountId": u_ver["id"],
                                        "requestId": OPTAG + "-pay1", "verifyToken": v3["verifyToken"]})
    check("cashier.payVerified 同 requestId 幂等：同一支付单 repeated",
          rv2["payment"]["id"] == rv["payment"]["id"] and rv2.get("repeated") is True, str(rv2))
    check("cashier.payVerified 幂等不重复扣款", bal(u_ver["id"]) == bv2)

    o_free = new_order(merchant["id"], 100, "开放免密商品")
    bv3 = bal(u_ver["id"])
    rf = op_ok("cashier.payVerified", {"orderId": o_free["id"], "method": "BALANCE", "accountId": u_ver["id"],
                                       "requestId": OPTAG + "-free"})
    pf = wait_terminal(rf["payment"]["id"])
    check("cashier.payVerified 小额免密受理成功", pf["status"] == "SUCCESS" and bal(u_ver["id"]) == bv3 - 100)
    vf = op_ok("cashier.verify", {"accountId": u_ver["id"], "orderId": o_free["id"], "amount": 100,
                                  "verifyType": "SMALL_AMOUNT_FREE"})
    check("cashier.verify 小额免密 pass=true", vf["pass"] is True and vf["challengeType"] == "SMALL_AMOUNT_FREE", str(vf))

    sms_masked = op_ok("cashier.sms.send", {"accountId": u_ver["id"], "orderId": ov["id"]})
    check("cashier.sms.send 默认掩码验证码（不泄明文）",
          sms_masked.get("sandbox") is True and sms_masked.get("smsCode") == "******"
          and sms_masked.get("smsCodeMasked") is True, str(sms_masked))
    sms = op_ok("cashier.sms.send", {"accountId": u_ver["id"], "orderId": ov["id"], "sandbox": True})
    check("cashier.sms.send sandbox=true 返回沙箱 6 位码（结构同控制台面）",
          sms.get("sandbox") is True and re.fullmatch(r"\d{6}", str(sms.get("smsCode"))), str(sms))
    vs_bad = op_ok("cashier.verify", {"accountId": u_ver["id"], "orderId": ov["id"], "amount": 3000,
                                      "verifyType": "SMS", "smsCode": "999999"})
    check("cashier.verify 短信错误码 pass=false", vs_bad["pass"] is False, str(vs_bad))
    vs_ok = op_ok("cashier.verify", {"accountId": u_ver["id"], "orderId": ov["id"], "amount": 3000,
                                     "verifyType": "SMS", "smsCode": sms["smsCode"]})
    check("cashier.verify 短信正确码 pass=true + 令牌", vs_ok["pass"] is True and bool(vs_ok.get("verifyToken")), str(vs_ok))
    vbio0 = op_ok("cashier.verify", {"accountId": u_ver["id"], "orderId": ov["id"], "amount": 3000, "verifyType": "BIO"})
    check("cashier.verify 指纹无令牌 pass=false", vbio0["pass"] is False, str(vbio0))
    vbio = op_ok("cashier.verify", {"accountId": u_ver["id"], "orderId": ov["id"], "amount": 3000,
                                    "verifyType": "BIO", "bioToken": "sandbox-bio-xxx"})
    check("cashier.verify 指纹模拟 pass=true", vbio["pass"] is True and vbio["challengeType"] == "BIO", str(vbio))

    ok("POST", "/api/cashier/pay-password", {"accountId": u_lock["id"], "password": "654321"})
    last = None
    for _ in range(5):
        last = op_ok("cashier.verify", {"accountId": u_lock["id"], "amount": 3000,
                                        "verifyType": "PASSWORD", "payPassword": "000000"})
    check("cashier.verify 连续错误口令 → LOCKED",
          last["pass"] is False and last["challengeType"] == "LOCKED" and last.get("lockedUntil"), str(last))
    v_locked = op_ok("cashier.verify", {"accountId": u_lock["id"], "amount": 3000,
                                        "verifyType": "PASSWORD", "payPassword": "654321"})
    check("cashier.verify 锁定期间正确口令也拦截",
          v_locked["pass"] is False and v_locked["challengeType"] == "LOCKED", str(v_locked))

    # ---- 脱敏：卡号仅后四位 / appSecret 与口令不外泄 ----
    o_card = new_order(merchant["id"], 100, "开放卡号脱敏")
    rc = op_ok("cashier.payVerified", {"orderId": o_card["id"], "method": "BANKCARD", "accountId": u_ver["id"],
                                       "cardNo": "6222020202020202", "requestId": OPTAG + "-card"})
    check("cashier.payVerified BANKCARD 归一为 CARD", rc["payment"]["method"] == "CARD", str(rc["payment"]["method"]))
    cn = rc["payment"]["cardNo"] or ""
    check("卡号脱敏：仅后四位可见（BIN 亦抹除）",
          cn.endswith("0202") and len(cn) > 4 and set(cn[:-4]) == {"*"} and "622202" not in cn, str(cn))
    check("响应不含完整卡号", "6222020202020202" not in json.dumps(rc))
    joined = "\n".join(OP_TEXTS)
    check("全部 OpenAPI 响应不外泄 appSecret", OP["secret"] not in joined and "appSecret" not in joined)
    check("全部 OpenAPI 响应不外泄商户证书公钥", "certPublicKey" not in joined)
    check("全部 OpenAPI 响应不回显支付口令字段", '"payPassword"' not in joined and '"password"' not in joined)

    # ---- 既有 11 商户方法不回归（抽 trade.create / trade.pay / account.balance） ----
    oo = op_ok("trade.create", {"subject": "开放红线商品", "amount": 800})
    check("既有 trade.create 验签通过且返回不变", oo["amount"] == 800 and bool(oo["id"]), str(oo))
    bt0 = bal(u_scan["id"])
    rp = op_ok("trade.pay", {"orderId": oo["id"], "method": "BALANCE", "accountId": u_scan["id"],
                             "outTradeNo": OPTAG + "-tp"})
    pp = wait_terminal(rp["payment"]["id"])
    check("既有 trade.pay BALANCE 同步 SUCCESS（语义不变）", pp["status"] == "SUCCESS", str(pp["status"]))
    check("既有 trade.pay 扣款 800", bal(u_scan["id"]) == bt0 - 800)
    bb = op_ok("account.balance", {"accountId": u_scan["id"]})
    check("既有 account.balance 仍是裸数字 long", isinstance(bb, int) and bb == bal(u_scan["id"]), str(bb))
    st, resp = op("trade.create", {"subject": "错签", "amount": 1}, sign="0" * 64)
    check("既有 trade.create 错签拒绝 400（canonical 不变）", st == 400, str(resp))


def section_openapi_expire(ctx):
    print("[A2-过期] OpenAPI 付款码自然过期拒绝（默认 60s）")
    c_exp = ctx.get("op_expire_code")
    if not c_exp:
        skip("OpenAPI 过期码拒绝", "无过期码上下文")
        return
    code = c_exp["code"]
    pc = poll(lambda: op_ok("cashier.code.query", {"code": code}), lambda x: x["status"] == "EXPIRED", timeout=80, interval=2)
    check("OpenAPI 付款码到期 → EXPIRED", pc["status"] == "EXPIRED", str(pc["status"]))
    st, resp = op("cashier.scanPay", {"code": code, "requestId": OPTAG + "-exp", "method": "BALANCE"})
    check("OpenAPI 过期码核销拒绝 409", st == 409, str(resp))


# ============================================================================
# B. C-1 多支付方式动态渲染
# ============================================================================
def section_c1(ctx):
    print("[B] C-1 多支付方式动态渲染与可用性/限额/维护窗口提示")
    ua, ma, merchant = ctx["ua"], ctx["ma"], ctx["merchant"]
    order = new_order(merchant["id"], 3000, "C-1渲染商品")
    # 聚合渲染（smoke 兼容）
    agg = ok("GET", f"/api/cashier?orderId={order['id']}")
    am = {m["method"]: m for m in agg["methods"]}
    check("聚合渲染含 BALANCE/CARD（smoke 兼容）", "BALANCE" in am and "CARD" in am)
    check("聚合 BALANCE.accounts 列可用余额账户", any(a["id"] == ua["id"] for a in am["BALANCE"]["accounts"]))
    # 账户级渲染
    dto = ok("GET", f"/api/cashier/methods?orderId={order['id']}&accountId={ua['id']}")
    m = {x["method"]: x for x in dto["methods"]}
    check("账户级渲染含全部 9 方式（4 REAL + 5 预留）",
          all(k in m for k in ["BALANCE", "CARD", "PAY_CODE", "SCAN", "QUICK_PAY", "NET_BANK", "CREDIT", "COUPON", "COMBO"]),
          str(list(m)))
    check("BALANCE 可用（余额足额）", m["BALANCE"]["available"] is True, str(m["BALANCE"]))
    check("BALANCE 限额提示（分）", isinstance(m["BALANCE"].get("limit"), int) and m["BALANCE"]["limit"] > 0)
    check("CARD 可用 + 限额", m["CARD"]["available"] is True and m["CARD"].get("limit"))
    check("PAY_CODE/SCAN 沙箱可用", m["PAY_CODE"]["available"] is True and m["SCAN"]["available"] is True)
    check("预留方式 COMBO/COUPON/CREDIT 不可用 + 原因",
          all(m[k]["available"] is False and m[k].get("unavailableReason") for k in ["COMBO", "COUPON", "CREDIT"]))
    check("QUICK_PAY 未签约不可用 + 原因", m["QUICK_PAY"]["available"] is False and m["QUICK_PAY"].get("unavailableReason"))
    check("NET_BANK 维护窗口标记存在（bool）", isinstance(m["NET_BANK"].get("maintenance"), bool))
    check("RESERVED 方式 category 标记", all(m[k].get("category") == "RESERVED" for k in ["COMBO", "COUPON", "CREDIT", "NET_BANK"]))
    check("汇总：实付=订单金额-优惠（分）", dto["actualAmount"] == order["amount"] - (dto["discount"] or 0))
    check("汇总：免密限额下发（分）", isinstance(dto.get("freeVerifyLimit"), int) and dto["freeVerifyLimit"] > 0)
    check("收款商户名下发", bool(dto.get("merchantName")))

    # 余额不足 → BALANCE 不可用
    big = new_order(merchant["id"], 200000, "C-1余额不足")
    dto2 = ok("GET", f"/api/cashier/methods?orderId={big['id']}&accountId={ua['id']}")
    b2 = {x["method"]: x for x in dto2["methods"]}["BALANCE"]
    check("余额不足 → BALANCE 不可用 + 原因", b2["available"] is False and "余额不足" in (b2.get("unavailableReason") or ""), str(b2))

    # 冻结账户 → BALANCE 不可用（用后即恢复）
    ok("POST", f"/api/accounts/{ua['id']}/status", {"status": "FROZEN"})
    try:
        dto3 = ok("GET", f"/api/cashier/methods?orderId={order['id']}&accountId={ua['id']}")
        b3 = {x["method"]: x for x in dto3["methods"]}["BALANCE"]
        check("冻结账户 → BALANCE 不可用", b3["available"] is False, str(b3))
    finally:
        ok("POST", f"/api/accounts/{ua['id']}/status", {"status": "ACTIVE"})
    dto4 = ok("GET", f"/api/cashier/methods?orderId={order['id']}&accountId={ua['id']}")
    b4 = {x["method"]: x for x in dto4["methods"]}["BALANCE"]
    check("恢复 ACTIVE → BALANCE 重新可用", b4["available"] is True)
    ctx["c1_order"] = order


# ============================================================================
# C. C-2 付款码/扫码沙箱
# ============================================================================
def section_c2(ctx):
    print("[C] C-2 付款码/扫码沙箱：生成→解析→一次性→过期→防重放→核销幂等→并发")
    ua, ma, merchant = ctx["ua"], ctx["ma"], ctx["merchant"]

    # 被扫：用户出示 18 位数字码
    o1 = new_order(merchant["id"], 100, "C-2被扫商品")
    c1 = ok("POST", "/api/cashier/code", {"type": "USER_PRESENTED", "accountId": ua["id"], "orderId": o1["id"]})
    check("被扫码生成：18 位数字 + ACTIVE", c1["type"] == "USER_PRESENTED" and re.fullmatch(r"\d{18}", c1["code"]) and c1["status"] == "ACTIVE", str(c1))
    check("被扫码有效期/倒计时下发", c1["expireAt"] > time.time() * 1000 and c1.get("ttlSeconds", 0) > 0)
    check("被扫码展示分组码值（不泄露敏感信息）", bool(c1.get("displayCode")) and " " in c1["displayCode"])
    p1 = ok("POST", "/api/cashier/code/parse", {"code": c1["code"]})
    check("被扫码解析：金额/订单绑定正确", p1["amount"] == 100 and p1["orderId"] == o1["id"] and p1["status"] == "ACTIVE")
    check("码值不含账户口令等敏感信息", "password" not in json.dumps(c1).lower() and "salt" not in json.dumps(c1).lower())

    # 主扫：商户出二维码令牌
    o2 = new_order(merchant["id"], 100, "C-2主扫商品")
    c2 = ok("POST", "/api/cashier/code", {"type": "MERCHANT_PRESENTED", "accountId": ma["id"], "orderId": o2["id"]})
    check("主扫码生成：令牌 + 二维码内容", c2["type"] == "MERCHANT_PRESENTED" and c2["code"].startswith("MP") and bool(c2.get("qrContent")), str(c2))
    p2 = ok("GET", "/api/cashier/code/" + c2["code"])
    check("主扫码查询状态 ACTIVE", p2["status"] == "ACTIVE")

    # 过期码（提前生成，末尾统一判定，避免空等）
    o_exp = new_order(merchant["id"], 100, "C-2过期商品")
    c_exp = ok("POST", "/api/cashier/code", {"type": "USER_PRESENTED", "accountId": ua["id"], "orderId": o_exp["id"]})
    ctx["expire_code"] = c_exp

    # 扫码受理（被扫，小额免密）→ BALANCE 同步成功
    b0 = bal(ua["id"])
    r1 = ok("POST", "/api/cashier/scan-pay", {"code": c1["code"], "requestId": f"{TAG}-scan1", "method": "BALANCE"})
    pid1 = r1["payment"]["id"]
    check("扫码受理返回支付单", bool(pid1))
    pa = wait_terminal(pid1)
    check("扫码支付 BALANCE 同步终态 SUCCESS（无 WAITING）", pa["status"] == "SUCCESS", str(pa["status"]))
    check("扫码支付手续费口径不变", pa["fee"] == fee_of(100), str(pa["fee"]))
    check("扫码扣款正确（100 分）", bal(ua["id"]) == b0 - 100, f"{bal(ua['id'])} vs {b0-100}")
    pc = ok("GET", "/api/cashier/code/" + c1["code"])
    check("核销后码状态 CONSUMED", pc["status"] == "CONSUMED")

    # 一次性 / 防重放：同码不同 requestId 再核销 → 409
    st, resp = req("POST", "/api/cashier/scan-pay", {"code": c1["code"], "requestId": f"{TAG}-scan-replay", "method": "BALANCE"})
    check("防重放：已核销码异 requestId 拒绝 409", st == 409, str(resp))
    # 核销幂等：同 requestId 再受理 → 同一支付单，不重复扣款
    b1 = bal(ua["id"])
    r2 = ok("POST", "/api/cashier/scan-pay", {"code": c1["code"], "requestId": f"{TAG}-scan1", "method": "BALANCE"})
    check("核销幂等：同 requestId 返回同一支付单 repeated", r2["payment"]["id"] == pid1 and r2.get("repeated") is True, str(r2))
    check("核销幂等：余额不重复扣减", bal(ua["id"]) == b1)

    # 主扫受理（用户扫商户码，指定付款账户）
    o3 = new_order(merchant["id"], 100, "C-2主扫受理")
    c3 = ok("POST", "/api/cashier/code", {"type": "MERCHANT_PRESENTED", "accountId": ma["id"], "orderId": o3["id"]})
    b2 = bal(ua["id"])
    r3 = ok("POST", "/api/cashier/scan-pay", {"code": c3["code"], "requestId": f"{TAG}-mscan", "payerAccountId": ua["id"], "method": "BALANCE"})
    p3 = wait_terminal(r3["payment"]["id"])
    check("主扫受理成功（付款方=请求指定账户）", p3["status"] == "SUCCESS" and p3["payerAccountId"] == ua["id"])
    check("主扫扣款正确", bal(ua["id"]) == b2 - 100)

    # 并发核销只一次（同码两 requestId 并发）
    o4 = new_order(merchant["id"], 100, "C-2并发商品")
    c4 = ok("POST", "/api/cashier/code", {"type": "USER_PRESENTED", "accountId": ua["id"], "orderId": o4["id"]})
    b3 = bal(ua["id"])
    results = []

    def worker(rid):
        results.append(req("POST", "/api/cashier/scan-pay", {"code": c4["code"], "requestId": rid, "method": "BALANCE"}))

    t1 = threading.Thread(target=worker, args=(f"{TAG}-cc1",))
    t2 = threading.Thread(target=worker, args=(f"{TAG}-cc2",))
    t1.start(); t2.start(); t1.join(); t2.join()
    oks = [r for r in results if r[0] == 200]
    conflicts = [r for r in results if r[0] == 409]
    check("并发核销：恰一成功一拒绝", len(oks) == 1 and len(conflicts) == 1, str([r[0] for r in results]))
    if oks:
        wait_terminal(oks[0][1]["payment"]["id"])
    check("并发核销：只扣款一次", bal(ua["id"]) == b3 - 100, f"{bal(ua['id'])} vs {b3-100}")

    # 不存在码
    st, _ = req("POST", "/api/cashier/code/parse", {"code": "000000000000000000"})
    check("解析不存在码 → 404", st == 404, str(st))


def section_c2_expire(ctx):
    print("[C2-过期] 付款码自然过期拒绝（默认 60s，轮询至 EXPIRED）")
    c_exp = ctx.get("expire_code")
    if not c_exp:
        skip("过期码拒绝", "无过期码上下文")
        return
    code = c_exp["code"]
    pc = poll(lambda: ok("GET", "/api/cashier/code/" + code), lambda x: x["status"] == "EXPIRED", timeout=80, interval=2)
    check("付款码到期 → 状态 EXPIRED", pc["status"] == "EXPIRED", str(pc["status"]))
    st, resp = req("POST", "/api/cashier/scan-pay", {"code": code, "requestId": f"{TAG}-exp", "method": "BALANCE"})
    check("过期码核销拒绝 409", st == 409, str(resp))


# ============================================================================
# D. C-3 支付验证
# ============================================================================
def section_c3(ctx):
    print("[D] C-3 支付验证：失败拦截/不受理/锁定/免密/令牌一次性+绑定/短信/指纹/幂等")
    ua, merchant = ctx["ua"], ctx["merchant"]
    order = new_order(merchant["id"], 3000, "C-3验证商品")

    # 未设密码 → 挑战设置
    v0 = ok("POST", "/api/cashier/verify", {"accountId": ua["id"], "orderId": order["id"], "amount": 3000, "verifyType": "PASSWORD", "payPassword": "123456"})
    check("未设支付密码 → 挑战 NEED_SET_PASSWORD", v0["pass"] is False and v0["challengeType"] == "NEED_SET_PASSWORD", str(v0))
    # 设置密码
    ok("POST", "/api/cashier/pay-password", {"accountId": ua["id"], "password": "123456"})
    # 错误密码 → 拦截
    v1 = ok("POST", "/api/cashier/verify", {"accountId": ua["id"], "orderId": order["id"], "amount": 3000, "verifyType": "PASSWORD", "payPassword": "000000"})
    check("错误口令 → pass=false + 原因 + 剩余次数", v1["pass"] is False and v1.get("reason") and v1.get("remainAttempts") is not None, str(v1))
    # 验证失败不得受理（错误密码内联）
    b0 = bal(ua["id"])
    st, resp = req("POST", "/api/cashier/pay-verified", {"orderId": order["id"], "method": "BALANCE", "accountId": ua["id"], "requestId": f"{TAG}-bad", "payPassword": "000000"})
    check("错误口令受理拒绝 403", st == 403, str(resp))
    check("验证失败不扣款", bal(ua["id"]) == b0)
    # 超免密无验证 → 拒绝
    st, resp = req("POST", "/api/cashier/pay-verified", {"orderId": order["id"], "method": "BALANCE", "accountId": ua["id"], "requestId": f"{TAG}-noverify"})
    check("超免密限额无验证 → 拒绝 403", st == 403, str(resp))
    check("无验证不扣款", bal(ua["id"]) == b0)

    # 正确口令 → verifyToken → 受理
    v2 = ok("POST", "/api/cashier/verify", {"accountId": ua["id"], "orderId": order["id"], "amount": 3000, "verifyType": "PASSWORD", "payPassword": "123456", "requestId": f"{TAG}-v-ok"})
    check("正确口令 → pass=true + verifyToken", v2["pass"] is True and bool(v2.get("verifyToken")), str(v2))
    tok = v2["verifyToken"]
    b1 = bal(ua["id"])
    r = ok("POST", "/api/cashier/pay-verified", {"orderId": order["id"], "method": "BALANCE", "accountId": ua["id"], "requestId": f"{TAG}-pay1", "verifyToken": tok})
    pid = r["payment"]["id"]
    pa = wait_terminal(pid)
    check("验证受理 BALANCE 同步 SUCCESS + 手续费口径", pa["status"] == "SUCCESS" and pa["fee"] == fee_of(3000), str(pa))
    check("验证受理扣款 3000", bal(ua["id"]) == b1 - 3000)

    # 令牌一次性：同令牌再受理（异 requestId）→ 拒绝
    o_second = new_order(merchant["id"], 3000, "C-3令牌复用")
    st, resp = req("POST", "/api/cashier/pay-verified", {"orderId": o_second["id"], "method": "BALANCE", "accountId": ua["id"], "requestId": f"{TAG}-reuse", "verifyToken": tok})
    check("验证令牌一次性：复用拒绝 403", st == 403, str(resp))

    # 验证+受理幂等：同 requestId 重新验证→受理，不重复扣款
    b2 = bal(ua["id"])
    v3 = ok("POST", "/api/cashier/verify", {"accountId": ua["id"], "orderId": order["id"], "amount": 3000, "verifyType": "PASSWORD", "payPassword": "123456", "requestId": f"{TAG}-v-ok2"})
    r2 = ok("POST", "/api/cashier/pay-verified", {"orderId": order["id"], "method": "BALANCE", "accountId": ua["id"], "requestId": f"{TAG}-pay1", "verifyToken": v3["verifyToken"]})
    check("验证+受理同 requestId 幂等：同一支付单 repeated", r2["payment"]["id"] == pid and r2.get("repeated") is True, str(r2))
    check("幂等不重复扣款", bal(ua["id"]) == b2)

    # 令牌绑定校验：为 order A 签发的令牌用于 order B → 拒绝
    o_bind = new_order(merchant["id"], 3000, "C-3绑定校验")
    vb = ok("POST", "/api/cashier/verify", {"accountId": ua["id"], "orderId": order["id"], "amount": 3000, "verifyType": "PASSWORD", "payPassword": "123456"})
    st, resp = req("POST", "/api/cashier/pay-verified", {"orderId": o_bind["id"], "method": "BALANCE", "accountId": ua["id"], "requestId": f"{TAG}-bind", "verifyToken": vb["verifyToken"]})
    check("令牌绑定订单校验：跨单拒绝 403", st == 403, str(resp))

    # 小额免密：amount ≤ freeLimit 无需验证
    o_free = new_order(merchant["id"], 100, "C-3免密商品")
    b3 = bal(ua["id"])
    rf = ok("POST", "/api/cashier/pay-verified", {"orderId": o_free["id"], "method": "BALANCE", "accountId": ua["id"], "requestId": f"{TAG}-free"})
    pf = wait_terminal(rf["payment"]["id"])
    check("小额免密受理成功（≤免密限额）", pf["status"] == "SUCCESS" and bal(ua["id"]) == b3 - 100)
    vf = ok("POST", "/api/cashier/verify", {"accountId": ua["id"], "orderId": o_free["id"], "amount": 100, "verifyType": "SMALL_AMOUNT_FREE"})
    check("小额免密验证 pass=true", vf["pass"] is True and vf["challengeType"] == "SMALL_AMOUNT_FREE")

    # 短信沙箱
    sms = ok("POST", "/api/cashier/sms/send", {"accountId": ua["id"], "orderId": order["id"]})
    check("短信沙箱：返回码值 + sandbox 标记", sms.get("sandbox") is True and re.fullmatch(r"\d{6}", str(sms.get("smsCode"))), str(sms))
    vs_wrong = ok("POST", "/api/cashier/verify", {"accountId": ua["id"], "orderId": order["id"], "amount": 3000, "verifyType": "SMS", "smsCode": "999999"})
    check("短信错误码 → pass=false", vs_wrong["pass"] is False)
    vs_ok = ok("POST", "/api/cashier/verify", {"accountId": ua["id"], "orderId": order["id"], "amount": 3000, "verifyType": "SMS", "smsCode": sms["smsCode"]})
    check("短信正确码 → pass=true + 令牌", vs_ok["pass"] is True and bool(vs_ok.get("verifyToken")))

    # 指纹模拟
    vbio_empty = ok("POST", "/api/cashier/verify", {"accountId": ua["id"], "orderId": order["id"], "amount": 3000, "verifyType": "BIO"})
    check("指纹无令牌 → pass=false", vbio_empty["pass"] is False)
    vbio = ok("POST", "/api/cashier/verify", {"accountId": ua["id"], "orderId": order["id"], "amount": 3000, "verifyType": "BIO", "bioToken": "sandbox-bio-xxx"})
    check("指纹模拟令牌 → pass=true", vbio["pass"] is True and vbio["challengeType"] == "BIO")

    # 口令暴力锁定（独立账户，避免污染主账户）
    u2 = ok("POST", "/api/members", {"name": f"{TAG}锁定用户", "type": "USER"})
    ua2 = ok("POST", "/api/accounts", {"memberId": u2["id"]})
    ok("POST", "/api/cashier/pay-password", {"accountId": ua2["id"], "password": "654321"})
    last = None
    for i in range(5):
        last = ok("POST", "/api/cashier/verify", {"accountId": ua2["id"], "amount": 3000, "verifyType": "PASSWORD", "payPassword": "000000"})
    check("连续错误口令 → 锁定 LOCKED", last["pass"] is False and last["challengeType"] == "LOCKED" and last.get("lockedUntil"), str(last))
    v_locked = ok("POST", "/api/cashier/verify", {"accountId": ua2["id"], "amount": 3000, "verifyType": "PASSWORD", "payPassword": "654321"})
    check("锁定期间正确口令也拦截", v_locked["pass"] is False and v_locked["challengeType"] == "LOCKED")


# ============================================================================
# E. ride 红线：BALANCE 同步语义不回归（冻结路径 /api/cashier/pay）
# ============================================================================
def section_redline(ctx):
    print("[E] ride 红线：/api/cashier/pay BALANCE 同步语义逐字节不回归")
    # 独立付款人/商户：前面各段已对 ctx 账户付近百笔，复用必触风控滑动窗口（5 笔/10s）
    # 与多维限额，风控在 TCC Try 内拒绝 → 支付单 FAILED，污染红线断言。
    # 隔离测试主体，不放宽任何风控阈值/断言。
    ru = ok("POST", "/api/members", {"name": f"{TAG}红线用户", "type": "USER"})
    ua = ok("POST", "/api/accounts", {"memberId": ru["id"]})
    merchant = ok("POST", "/api/members", {"name": f"{TAG}红线商户", "type": "MERCHANT"})
    ma = ok("POST", "/api/accounts", {"memberId": merchant["id"]})
    ok("POST", "/api/accounting/internal/topup",
       {"accountId": ua["id"], "amount": 100000, "bizNo": f"{TAG}-rltop"})
    order = new_order(merchant["id"], 3000, "红线同步商品")
    u0, m0, fee0 = bal(ua["id"]), bal(ma["id"]), bal("SYS_FEE")
    rid = f"{TAG}-redline"
    r = ok("POST", "/api/cashier/pay", {"orderId": order["id"], "method": "BALANCE", "accountId": ua["id"], "requestId": rid})
    pid = r["payment"]["id"]
    seen = set()
    pa = poll(lambda: payment(pid), lambda p: (seen.add(p["status"]), p["status"] != "PROCESSING")[1], timeout=30)
    check("受理→PROCESSING→SUCCESS 轮询终态", pa["status"] == "SUCCESS", str(pa["status"]))
    check("绝不引入 WAITING/异步回执", "WAITING" not in seen, str(seen))
    check("手续费口径不变（3000→18 分）", pa["fee"] == 18, str(pa["fee"]))
    check("付款方扣 3000", bal(ua["id"]) == u0 - 3000)
    check("商户净额 +2982", bal(ma["id"]) == m0 + 2982)
    check("手续费户 +18", bal("SYS_FEE") == fee0 + 18)
    oa = poll(lambda: ok("GET", f"/api/orders/{order['id']}"), lambda o: o["status"] == "PAID")
    check("交易单 PAID 关联支付单", oa["status"] == "PAID" and oa["paymentId"] == pid)
    # requestId 幂等
    r2 = ok("POST", "/api/cashier/pay", {"orderId": order["id"], "method": "BALANCE", "accountId": ua["id"], "requestId": rid})
    check("requestId 幂等返回同一支付单 repeated", r2["payment"]["id"] == pid and r2.get("repeated") is True and bal(ua["id"]) == u0 - 3000)
    # 订单级重复（异 requestId）→ 409
    st, resp = req("POST", "/api/cashier/pay", {"orderId": order["id"], "method": "BALANCE", "accountId": ua["id"], "requestId": f"{TAG}-redline2"})
    check("订单级重复支付拒绝 409", st == 409, str(resp))
    # 缺 requestId → 400
    st, resp = req("POST", "/api/cashier/pay", {"orderId": order["id"], "method": "BALANCE", "accountId": ua["id"]})
    check("缺 requestId 拒绝 400", st == 400, str(resp))
    # BANKCARD 归一仍走 CARD（受理路径不变；同步/异步均返回 method=CARD）
    oc = new_order(merchant["id"], 3000, "红线银行卡")
    stc, respc = req("POST", "/api/cashier/pay", {"orderId": oc["id"], "method": "BANKCARD", "cardNo": "6222020202020202", "requestId": f"{TAG}-cardnorm"})
    check("BANKCARD 归一为 CARD（受理路径不变）", stc == 200 and respc["payment"]["method"] == "CARD", str(stc) + str(respc))


def main():
    print(f"== verify-cashier · 收银台工作流 + 开放 OpenAPI（批次 {TAG} / {OPTAG}）==")
    _login()
    if not TOKEN:
        print("!! 登录失败，无法继续（需网关在线 + admin 口令）")
        sys.exit(2)
    # 端点连通预检
    st, _ = req("GET", "/api/health")
    check("网关健康端点连通", st == 200, str(st))

    section_h5_removed()

    merchant = ok("POST", "/api/members", {"name": f"{TAG}商户", "type": "MERCHANT"})
    user = ok("POST", "/api/members", {"name": f"{TAG}用户", "type": "USER"})
    ma = ok("POST", "/api/accounts", {"memberId": merchant["id"]})
    ua = ok("POST", "/api/accounts", {"memberId": user["id"]})
    ok("POST", "/api/accounting/internal/topup", {"accountId": ua["id"], "amount": 100000, "bizNo": f"{TAG}-topup"})
    ctx = {"merchant": merchant, "user": user, "ma": ma, "ua": ua}
    # 开放平台调用凭据：真实商户 create 一次性返回的 appId/appSecret（E-1 后 list/get 已脱敏）
    OP["app_id"], OP["secret"] = merchant.get("appId"), merchant.get("appSecret")
    check("商户 create 一次性下发 appId/appSecret（供 OpenAPI 签名）",
          bool(OP["app_id"]) and bool(OP["secret"]), "create 未返回密钥")

    section_openapi(ctx)
    section_c1(ctx)
    section_c2(ctx)
    section_c3(ctx)
    section_redline(ctx)
    section_c2_expire(ctx)
    section_openapi_expire(ctx)

    print(f"\n== verify-cashier 结果：PASS={PASSED} FAIL={FAILED} SKIP={SKIPPED} ==")
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()