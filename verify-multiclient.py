#!/usr/bin/env python3
"""verify-multiclient.py — C-5 多端收银台（口径：**多端对接能力**，非自建 C 端页面）+ 开放面收尾接线专项验证。

跑法（gateway 8080，栈需已启动；docker profile 继承 --docker）：
  python3 verify-multiclient.py

覆盖：
  1. **契约一致性自检（防漂移）**：Python/Java SDK 方法集 ↔ OpenApiCashierController(9)/OpenApiSignController(2)
     实际 @PostMapping 端点一一对应（双向集合相等 + 逐方法 call 字面量）；主扫 qrContent 的 code= 归一
     双 SDK 逐例对齐；canonical 布尔渲染一致；requestId 生成口径；**OpenApiController md5 未变（v1 冻结）**
  2. **scope/版本/沙箱登记生效**：/api/openplatform/scopes 含 11 新方法映射；/openapi/version 公告
     v1.additiveMethods/multiclient/sandbox 三块；v1 路径不被版本头改变行为
  3. **沙箱端到端**：Python + Java 收银台沙箱示例退出码 0；沙箱账本隔离（生产账户数/支付单数/试算平衡前后不变）；
     沙箱 appId 读生产账户被拒、生产 appId 读沙箱账户被拒
  4. **签约 2 方法实签通过（生产路径）**：channel.sign 签约/幂等/脱敏/错签/缺参 + channel.agent.pay 代扣
     SUCCESS/手续费口径不变/幂等重放不重复扣款/超限额受理前拒绝/解约后拒绝
  5. **限流登记 + 拒绝在受理前 + appM1 legacy 不误拦**：sentinel-rules.json（live + example）11 条新
     paramFlowRules 保守阈值、既有 11 条一字未改；注册应用突发触发 429 A0429 且不落悬挂单；
     **未注册 appId（ride appM1 同形态）顺序调用全通、免费不限配额**
  6. **多端受理一致**：同订单同金额同 requestId，H5/MINI_APP/APP/PC 四端标识（additive 透传）受理结果一致

数据保护：测试数据 MCTEST 前缀 + uuid，金额小；只新增行，不删/改既有数据；不放宽任何断言。
"""
import hashlib
import hmac
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("PP_GATEWAY", "http://127.0.0.1:8080")
ROOT = os.path.dirname(os.path.abspath(__file__))
SUFFIX = uuid.uuid4().hex[:8]
TAG = "MCTEST" + SUFFIX
ADMIN_PASS = os.environ.get("PP_ADMIN_PASSWORD", "admin123")
JAVA_HOME = os.environ.get("JAVA_HOME", "/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home")
SENTINEL_LIVE = os.path.expanduser("~/.config/payment-pro/sentinel-rules.json")
SENTINEL_EXAMPLE = os.path.join(ROOT, "config", "sentinel-rules.json.example")
# v1 冻结基线（OpenApiController 本体零编辑；任何改动都会让此断言失败）
OPENAPI_CONTROLLER_MD5 = "58870299aef5f32edc93593ac8b32f09"
CASHIER = ["cashier.methods", "cashier.code.create", "cashier.code.parse", "cashier.code.query",
           "cashier.scanPay", "cashier.verify", "cashier.payVerified", "cashier.payPassword.set",
           "cashier.sms.send"]
SIGN = ["channel.sign", "channel.agent.pay"]
EXPECTED_SCOPES = {
    "cashier.methods": "cashier:read", "cashier.code.create": "cashier:code",
    "cashier.code.parse": "cashier:read", "cashier.code.query": "cashier:read",
    "cashier.scanPay": "cashier:pay", "cashier.verify": "cashier:verify",
    "cashier.payVerified": "cashier:pay", "cashier.payPassword.set": "cashier:password",
    "cashier.sms.send": "cashier:sms", "channel.sign": "channel:sign",
    "channel.agent.pay": "channel:agentpay",
}
# 既有 11 条 paramFlowRules 基线（I-7 落地值，本次只可 additive 追加，不得改动）
LEGACY_PARAM_RULES = {
    "openapi:trade.create": 20, "openapi:trade.pay": 10, "openapi:fund.topup": 8,
    "openapi:fund.withdraw": 5, "openapi:account.topup": 8, "openapi:account.transfer": 8,
    "openapi:account.withdraw": 5, "openapi:trade.refund": 5, "openapi:account.open": 10,
    "openapi:account.balance": 50, "openapi:trade.query": 50,
}
PASSED = FAILED = 0


def check(name, cond, detail=""):
    global PASSED, FAILED
    if cond:
        PASSED += 1
        print(f"  [PASS] {name}")
    else:
        FAILED += 1
        print(f"  [FAIL] {name}  {detail}")


def req(method, path, body=None, token=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Accept", "application/json")
    if data is not None:
        r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    for key, value in (headers or {}).items():
        r.add_header(key, value)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            text = resp.read().decode()
            return resp.status, (json.loads(text) if text else None), dict(resp.headers)
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        try:
            return e.code, json.loads(text), dict(e.headers or {})
        except Exception:
            return e.code, {"error": text}, dict(e.headers or {})
    except Exception as e:
        return 0, {"error": str(e)}, {}


st, resp, _ = req("POST", "/api/auth/login", {"username": "admin", "password": ADMIN_PASS})
assert st == 200 and resp.get("token"), f"admin 登录失败: {st} {resp}"
ADMIN = resp["token"]


def ok(method, path, body=None):
    st, resp, _ = req(method, path, body, token=ADMIN)
    assert st == 200, f"{method} {path} 期望 200 实际 {st}: {resp}"
    return resp


def canonical(params):
    items = sorted((k, v) for k, v in params.items() if v is not None and k != "sign")
    return "&".join(f"{k}={'true' if v is True else 'false' if v is False else v}" for k, v in items)


def call(app_id, secret, endpoint, extra=None, sign_secret=None, headers=None, nonce=None):
    params = {"appId": app_id, "timestamp": int(time.time() * 1000)}
    params.update(extra or {})
    if nonce:
        params["nonce"] = nonce
    params["sign"] = hmac.new((sign_secret or secret).encode(), canonical(params).encode(),
                              hashlib.sha256).hexdigest()
    return req("POST", "/openapi/" + endpoint, params, headers=headers)


def call_ok(app_id, secret, endpoint, extra=None):
    st, resp, hdr = call(app_id, secret, endpoint, extra)
    assert st == 200, f"{endpoint} 期望 200 实际 {st}: {resp}"
    return resp


def new_merchant(tag):
    m = ok("POST", "/api/members", {"name": f"{TAG}-{tag}", "type": "MERCHANT"})
    return m["appId"], m["appSecret"], m["id"]


def new_user(tag):
    m = ok("POST", "/api/members", {"name": f"{TAG}-{tag}", "type": "USER"})
    return m["id"]


def account_of(member_id):
    return ok("POST", "/api/accounts", {"memberId": member_id})


def bal(account_id):
    return ok("GET", f"/api/accounting/accounts/{account_id}/balance")


def count_of(resp):
    if isinstance(resp, list):
        return len(resp)
    if isinstance(resp, dict):
        for key in ("total", "totalCount"):
            if isinstance(resp.get(key), int):
                return resp[key]
        for key in ("records", "items", "list", "content"):
            if isinstance(resp.get(key), list):
                return len(resp[key])
    return -1


def payments_count():
    return count_of(ok("GET", "/api/payments"))


def accounts_count():
    return count_of(ok("GET", "/api/accounts"))


def wait_terminal(pid, timeout=40):
    """CARD 异步渠道需轮询收敛（BALANCE 同步，首次即终态）。"""
    deadline = time.time() + timeout
    p = ok("GET", f"/api/payments/{pid}")
    while time.time() < deadline and p.get("status") == "PROCESSING":
        time.sleep(0.5)
        p = ok("GET", f"/api/payments/{pid}")
    return p


def fee_of(amount):
    """手续费口径（60bps 四舍五入，§0.7 红线不变）。"""
    return (amount * 60 + 5000) // 10000


def luhn_card():
    import random
    while True:
        body = "622202" + "".join(random.choice("0123456789") for _ in range(9))
        for d in "0123456789":
            cand = body + d
            total, alt = 0, False
            for ch in reversed(cand):
                x = int(ch)
                if alt:
                    x *= 2
                    if x > 9:
                        x -= 9
                total += x
                alt = not alt
            if total % 10 == 0 and not cand.endswith("0000"):
                return cand


def endpoints_of(java_file):
    with open(os.path.join(ROOT, java_file), encoding="utf-8") as f:
        return re.findall(r'@PostMapping\("/([^"]+)"\)', f.read())


def read_source(path):
    with open(os.path.join(ROOT, path), encoding="utf-8") as f:
        return f.read()


# ======================================================================
print("== 1. 契约一致性自检：SDK 方法集 ↔ 服务端实际端点（防漂移） ==")
cashier_endpoints = endpoints_of("payment-gateway/src/main/java/com/qoder/payment/gateway/web/OpenApiCashierController.java")
sign_endpoints = endpoints_of("payment-gateway/src/main/java/com/qoder/payment/gateway/web/OpenApiSignController.java")
check("OpenApiCashierController 实际端点恰为 9 个 cashier.*",
      sorted(cashier_endpoints) == sorted(CASHIER), str(cashier_endpoints))
check("OpenApiSignController 实际端点恰为 channel.sign/channel.agent.pay",
      sorted(sign_endpoints) == sorted(SIGN), str(sign_endpoints))

sys.path.insert(0, os.path.join(ROOT, "sdk", "python"))
import payment_openapi as sdk  # noqa: E402

check("Python SDK CASHIER_METHODS 与服务端端点一一对应",
      sorted(sdk.CASHIER_METHODS) == sorted(cashier_endpoints), str(sdk.CASHIER_METHODS))
check("Python SDK SIGN_METHODS 与服务端端点一一对应",
      sorted(sdk.SIGN_METHODS) == sorted(sign_endpoints), str(sdk.SIGN_METHODS))
py_src = read_source("sdk/python/payment_openapi.py")
for api in CASHIER + SIGN:
    check(f"Python SDK 实装 {api}", f'self.call("{api}"' in py_src, "缺 call 字面量")

java_src = read_source("sdk/java/src/com/qoder/payment/sdk/PaymentOpenApiClient.java")
for api in CASHIER + SIGN:
    check(f"Java SDK 实装 {api}", f'call("{api}"' in java_src, "缺 call 字面量")

build = subprocess.run(["bash", os.path.join(ROOT, "sdk", "java", "build.sh")],
                       capture_output=True, text=True, env=dict(os.environ, JAVA_HOME=JAVA_HOME), timeout=300)
check("Java SDK 编译通过（含收银台/签约方法 + 探针）", build.returncode == 0, (build.stdout + build.stderr)[-500:])
probe = subprocess.run([os.path.join(JAVA_HOME, "bin", "java"), "-cp", os.path.join(ROOT, "sdk", "java", "out"),
                        "com.qoder.payment.sdk.ContractProbe"], capture_output=True, text=True, timeout=120)
check("Java 契约探针运行成功", probe.returncode == 0 and "PROBE_OK" in probe.stdout,
      (probe.stdout + probe.stderr)[-500:])
probe_lines = dict()
norm_java = []
for line in probe.stdout.splitlines():
    if line.startswith("NORM\t"):
        parts = line.split("\t")
        norm_java.append((parts[1], parts[2] if len(parts) > 2 else ""))
    elif "=" in line:
        key, value = line.split("=", 1)
        probe_lines[key] = value
check("Java SDK 收银台方法目录 ↔ 服务端端点一一对应",
      probe_lines.get("CASHIER", "").split(",") == CASHIER, probe_lines.get("CASHIER"))
check("Java SDK 签约方法目录 ↔ 服务端端点一一对应",
      probe_lines.get("SIGN", "").split(",") == SIGN, probe_lines.get("SIGN"))
check("双 SDK 方法目录完全一致（Python == Java）",
      probe_lines.get("CASHIER", "").split(",") == list(sdk.CASHIER_METHODS)
      and probe_lines.get("SIGN", "").split(",") == list(sdk.SIGN_METHODS), str(probe_lines)[:200])
check("Java SDK 冻结八方法目录未变", probe_lines.get("FROZEN", "").split(",") == list(sdk.FROZEN_METHODS),
      probe_lines.get("FROZEN"))

QR_SAMPLES = [
    "https://cashier.payment-pro.example/scan?code=MPabc123&amount=100",
    "https://cashier.payment-pro.example/scan?amount=100&code=MPabc123",
    "https://cashier.payment-pro.example/scan?code=MP%61bc123&amount=100#frag",
    "http://127.0.0.1:8080/scan?code=MPxyz&amount=1&extra=9",
    "code=MPbare&amount=100",
    "123456789012345678",
    "MPabc123",
    "  MPabc123  ",
    "https://cashier.payment-pro.example/scan?amount=100",
    "",
]
for index, sample in enumerate(QR_SAMPLES):
    py_value = sdk.normalize_scan_code(sample)
    java_value = norm_java[index][1] if index < len(norm_java) else None
    check(f"主扫 code= 归一逐例对齐 #{index + 1}", py_value == java_value,
          f"py={py_value!r} java={java_value!r} input={sample!r}")
check("主扫归一取出 token（不是整串 URL）",
      sdk.normalize_scan_code(QR_SAMPLES[0]) == "MPabc123", sdk.normalize_scan_code(QR_SAMPLES[0]))
check("被扫码值归一为恒等（18 位数字原样）",
      sdk.normalize_scan_code("123456789012345678") == "123456789012345678")
check("URL 编码 token 归一解码", sdk.normalize_scan_code(QR_SAMPLES[2]) == "MPabc123",
      sdk.normalize_scan_code(QR_SAMPLES[2]))
check("无 code= 的 URL 原样返回（不误造 token）", sdk.normalize_scan_code(QR_SAMPLES[8]) == QR_SAMPLES[8])
check("None 归一不抛异常", sdk.normalize_scan_code(None) is None)
check("canonical 布尔渲染双 SDK 一致（小写 true）",
      sdk.canonical({"sandbox": True, "a": 1}) == "a=1&sandbox=true"
      and probe_lines.get("CANON_BOOL") == "a=1&sandbox=true", probe_lines.get("CANON_BOOL"))
check("Java requestId 生成：前缀 + ≤64 字符",
      probe_lines.get("RID_PREFIX") == "true" and probe_lines.get("RID_LEN_OK") == "true", str(probe_lines)[:200])
rid = sdk.new_request_id("PAY")
check("Python requestId 生成：前缀 + ≤64 字符 + 唯一",
      rid.startswith("PAY-") and len(rid) <= 64 and rid != sdk.new_request_id("PAY"), rid)
md5_now = hashlib.md5(read_source(
    "payment-gateway/src/main/java/com/qoder/payment/gateway/web/OpenApiController.java").encode()).hexdigest()
check("OpenApiController md5 未变（v1 本体零编辑）", md5_now == OPENAPI_CONTROLLER_MD5,
      f"{md5_now} != {OPENAPI_CONTROLLER_MD5}")
frozen_src = read_source("payment-gateway/src/main/java/com/qoder/payment/gateway/web/OpenApiController.java")
check("v1 冻结八方法端点仍在 OpenApiController 本体",
      all(f'"/{m}"' in frozen_src for m in sdk.FROZEN_METHODS), "冻结端点缺失")

# ======================================================================
print("== 2. scope / 版本 / 沙箱登记生效 ==")
scopes = ok("GET", "/api/openplatform/scopes")
for api, scope in EXPECTED_SCOPES.items():
    check(f"scope 登记 {api} → {scope}", scopes.get(api) == scope, str(scopes.get(api)))
check("既有 scope 映射未被覆盖", scopes.get("trade.pay") == "trade:pay"
      and scopes.get("account.balance") == "account:read" and scopes.get("notify.resend") == "notify:write",
      str(scopes)[:200])
st, announcement, _ = req("GET", "/openapi/version")
v1 = next((v for v in announcement.get("versions", []) if v["version"] == "v1"), {})
check("版本公告可达且 default=v1 current=v2", st == 200 and announcement.get("default") == "v1"
      and announcement.get("current") == "v2", f"{st}")
check("v1 冻结清单未变（frozen=true 且含八冻结方法）", v1.get("frozen") is True
      and set(sdk.FROZEN_METHODS).issubset(set(v1.get("methods", []))), str(v1)[:250])
check("v1.additiveMethods 登记 11 新方法（additive 不改冻结面）",
      v1.get("additiveMethods") == CASHIER + SIGN, str(v1.get("additiveMethods")))
check("v1.methods 未被新方法污染（仍 13 个既有方法）", len(v1.get("methods", [])) == 13,
      str(v1.get("methods")))
multiclient = announcement.get("multiclient", {})
check("公告 multiclient：四端 + 能力口径（不自建 C 端页面）",
      multiclient.get("clients") == ["H5", "MINI_APP", "APP", "PC"]
      and "自渲染" in str(multiclient.get("capability")), str(multiclient)[:250])
check("公告 multiclient：主扫 code= 归一口径", "code=" in str(multiclient.get("qrNormalization")),
      str(multiclient.get("qrNormalization"))[:200])
check("公告 multiclient：验证四型 + 幂等 + 轮询口径",
      multiclient.get("verifyTypes") == ["PASSWORD", "SMS", "BIO", "SMALL_AMOUNT_FREE"]
      and "repeated=true" in str(multiclient.get("requestId")) and bool(multiclient.get("polling")),
      str(multiclient)[:250])
check("公告 multiclient：双 SDK + 双文档指引",
      set(multiclient.get("sdk", {})) == {"python", "java"}
      and "docs/cashier-multiclient.md" in multiclient.get("docs", []), str(multiclient.get("docs")))
sandbox_block = announcement.get("sandbox", {})
check("公告 sandbox.methods 登记收银台 9 + 签约 2",
      set(CASHIER + SIGN).issubset(set(sandbox_block.get("methods", []))),
      str(sandbox_block.get("methods"))[:300])
check("公告 sandbox.cashierMethods/signMethods 分组正确",
      sandbox_block.get("cashierMethods") == CASHIER and sandbox_block.get("signMethods") == SIGN,
      str(sandbox_block)[:250])
check("公告 sandbox 隔离口径（openapi_sandbox_* / 生产零污染）",
      "openapi_sandbox" in str(sandbox_block.get("isolation")), str(sandbox_block.get("isolation"))[:200])
check("公告 scopes 含新方法（与 scope 目录同源）",
      announcement.get("scopes", {}).get("cashier.payVerified") == "cashier:pay"
      and announcement.get("scopes", {}).get("channel.agent.pay") == "channel:agentpay",
      str(announcement.get("scopes"))[:200])

# ======================================================================
print("== 3. 沙箱端到端（双 SDK 示例）+ 账本隔离 ==")
prod_accounts_before = accounts_count()
prod_payments_before = payments_count()
trial_before = ok("GET", "/api/accounting/trial")
sbx = ok("POST", "/api/openplatform/apps", {"name": f"{TAG}-SBX", "sandbox": True,
                                           "rateLimit": 0, "dailyQuota": 0, "unitPrice": 0})
SBX_ID, SBX_SECRET = sbx["appId"], sbx["appSecret"]
check("沙箱应用注册成功（独立 appId + 一次性明文密钥）",
      SBX_ID.startswith("sbx") and bool(SBX_SECRET), str(sbx)[:200])
env = dict(os.environ, PP_APP_ID=SBX_ID, PP_APP_SECRET=SBX_SECRET, PP_GATEWAY=BASE)
py = subprocess.run([sys.executable, os.path.join(ROOT, "sdk", "python", "example_cashier_sandbox.py")],
                    capture_output=True, text=True, env=env, timeout=300)
check("Python 收银台沙箱示例退出码 0", py.returncode == 0, (py.stdout + py.stderr)[-800:])
check("Python 示例断言全通过（渲染/验证/受理/轮询/幂等/被扫/主扫归一/SMS/BIO/签约/代扣/解约）",
      "Python SDK 收银台沙箱示例全部断言通过" in py.stdout, py.stdout[-500:])
java = subprocess.run([os.path.join(JAVA_HOME, "bin", "java"), "-cp", os.path.join(ROOT, "sdk", "java", "out"),
                       "com.qoder.payment.sdk.CashierSandboxExample"],
                      capture_output=True, text=True, env=env, timeout=300)
check("Java 收银台沙箱示例退出码 0", java.returncode == 0, (java.stdout + java.stderr)[-800:])
check("Java 示例断言全通过（同一序列，双 SDK 行为一致）",
      "Java SDK 收银台沙箱示例全部断言通过" in java.stdout, java.stdout[-500:])
st, sbx_view, hdr = call(SBX_ID, SBX_SECRET, "cashier.methods", {"orderId": "SBXO-NOT-EXIST"})
check("沙箱截流生效（X-Api-Sandbox: true，未知订单 404 而非进生产域）",
      hdr.get("X-Api-Sandbox") == "true" and st == 404, f"{st} {hdr.get('X-Api-Sandbox')} {sbx_view}")
sbx_accounts = ok("GET", f"/api/openplatform/sandbox/{SBX_ID}/accounts")
check("沙箱账本独立（SBXA 前缀，含付款户与商户收款户）",
      len(sbx_accounts) >= 2 and all(a["accountId"].startswith("SBXA") for a in sbx_accounts),
      str(sbx_accounts)[:250])
check("沙箱资金已流转（示例扣款后付款户余额 17700）",
      any(a.get("balance") == 17700 for a in sbx_accounts), str([a.get("balance") for a in sbx_accounts]))
st, isolated, _ = call(SBX_ID, SBX_SECRET, "account.balance", {"accountId": "SYS_FEE"})
check("沙箱 appId 读生产系统户被拒（账本隔离，不串生产账）", st in (403, 404), f"{st} {isolated}")
prod_accounts_after = accounts_count()
prod_payments_after = payments_count()
trial_after = ok("GET", "/api/accounting/trial")
check("沙箱不污染生产：账户数不变", prod_accounts_after == prod_accounts_before,
      f"{prod_accounts_before} → {prod_accounts_after}")
check("沙箱不污染生产：支付单数不变", prod_payments_after == prod_payments_before,
      f"{prod_payments_before} → {prod_payments_after}")
check("沙箱不污染生产：试算平衡仍 D=C 且 Σbalance=0",
      trial_after.get("balanced") is True and trial_after.get("balanceSum") == 0, str(trial_after)[:200])

# ======================================================================
print("== 4. 签约 2 方法实签通过（生产路径，未注册 appId → legacy） ==")
legacy_id, legacy_secret, legacy_merchant = new_merchant("LEGACY")
payer_member = new_user("PAYER")
payer = account_of(payer_member)
merchant_account = account_of(legacy_merchant)
PAYER_ID, MERCHANT_ACC = payer["id"], merchant_account["id"]
sbx_probe = sbx_accounts[0]["accountId"]
sbx_probe_balance = ok("GET", f"/api/openplatform/sandbox/{SBX_ID}/accounts")
sbx_probe_value = next(a["balance"] for a in sbx_probe_balance if a["accountId"] == sbx_probe)
st, isolated2, _ = call(legacy_id, legacy_secret, "account.balance", {"accountId": sbx_probe})
check("生产 appId 读沙箱账户无资金可见性（反向隔离：0/404，绝不等于沙箱余额）",
      (st in (403, 404)) or (st == 200 and isolated2 == 0 and isolated2 != sbx_probe_value),
      f"{st} {isolated2} vs sandbox={sbx_probe_value}")
call_ok(legacy_id, legacy_secret, "fund.topup", {"accountId": PAYER_ID, "amount": 20000,
                                                 "bizNo": f"{TAG}-TOPUP"})
check("legacy 商户充值成功（未注册 appId 不受新闸门影响）", bal(PAYER_ID) == 20000, bal(PAYER_ID))
card = luhn_card()
sign_rid = f"{TAG}-SIGN-1"
st, contract, _ = call(legacy_id, legacy_secret, "channel.sign",
                       {"accountId": PAYER_ID, "merchantId": legacy_merchant, "cardNo": card,
                        "requestId": sign_rid, "singleLimit": 5000, "periodLimit": 20000,
                        "periodType": "DAY", "scopeNote": "MCTEST 代扣授权"})
check("channel.sign 实签通过（HMAC 验签 + SIGNED）", st == 200 and contract.get("status") == "SIGNED",
      f"{st} {contract}")
sign_no = contract.get("signNo") if isinstance(contract, dict) else None
check("签约出参含协议号/渠道协议号/额度/有效期", bool(sign_no) and bool(contract.get("channelSignNo"))
      and contract.get("singleLimit") == 5000 and contract.get("periodLimit") == 20000, str(contract)[:250])
check("签约卡号脱敏（掩码留后四位 + SHA-256 哈希）",
      str(contract.get("cardNoMask", "")).endswith(card[-4:]) and len(contract.get("cardNoHash") or "") == 64,
      str(contract)[:250])
check("签约响应绝不含明文卡号/密文/appSecret",
      card not in json.dumps(contract) and "cardNoCipher" not in contract and "appSecret" not in contract,
      str(contract)[:250])
st, replay, _ = call(legacy_id, legacy_secret, "channel.sign",
                     {"accountId": PAYER_ID, "merchantId": legacy_merchant, "cardNo": card,
                      "requestId": sign_rid})
check("签约 requestId 幂等（同协议号 + repeated=true）",
      st == 200 and replay.get("signNo") == sign_no and replay.get("repeated") is True, f"{st} {replay}")
st, bad, _ = call(legacy_id, legacy_secret, "channel.sign",
                  {"accountId": PAYER_ID, "merchantId": legacy_merchant, "cardNo": card,
                   "requestId": f"{TAG}-SIGN-BAD"}, sign_secret="MCTEST-BAD-SECRET")
check("channel.sign 错签拒绝 400 A0400（受理前）", st == 400 and bad.get("code") == "A0400", f"{st} {bad}")
st, missing, _ = call(legacy_id, legacy_secret, "channel.sign",
                      {"accountId": PAYER_ID, "merchantId": legacy_merchant, "cardNo": card})
check("channel.sign 缺 requestId 拒绝 400", st == 400 and missing.get("code") == "A0400", f"{st} {missing}")
st, badcard, _ = call(legacy_id, legacy_secret, "channel.sign",
                      {"accountId": PAYER_ID, "merchantId": legacy_merchant, "cardNo": "6222020202020201",
                       "requestId": f"{TAG}-SIGN-LUHN"})
check("channel.sign Luhn 非法卡号受理前拒绝", st == 400, f"{st} {badcard}")

deduct_rid = f"{TAG}-DEDUCT-1"
amount = 300
balance_before = bal(PAYER_ID)
merchant_balance_before = bal(MERCHANT_ACC)
payments_before_deduct = payments_count()
st, deduct, _ = call(legacy_id, legacy_secret, "channel.agent.pay",
                     {"signNo": sign_no, "amount": amount, "requestId": deduct_rid,
                      "merchantId": legacy_merchant, "subject": "MCTEST 代扣"})
check("channel.agent.pay 实签通过（代扣 SUCCESS）", st == 200 and deduct.get("status") == "SUCCESS",
      f"{st} {deduct}")
deduct_no = deduct.get("deductNo") if isinstance(deduct, dict) else None
check("代扣出参含扣款单号/支付单/交易单/尝试幂等键",
      bool(deduct_no) and bool(deduct.get("paymentId")) and bool(deduct.get("orderId"))
      and deduct.get("attemptRequestId") == f"{deduct_no}:A1", str(deduct)[:250])
check("代扣金额与周期归属键正确", deduct.get("amount") == amount and bool(deduct.get("periodKey")),
      str(deduct)[:200])
payment = wait_terminal(deduct["paymentId"])
check("代扣走既有支付路径（手续费口径不变 60bps）", payment.get("fee") == fee_of(amount),
      f"{payment.get('fee')} vs {fee_of(amount)}")
check("代扣支付单收敛终态 SUCCESS（绝不悬挂 PROCESSING）", payment.get("status") == "SUCCESS",
      str(payment.get("status")))
check("代扣支付方式 CARD（走既有渠道，非新造资金路径）", payment.get("method") == "CARD",
      str(payment.get("method")))
check("代扣支付单 requestId = 确定性尝试幂等键", payment.get("requestId") == deduct.get("attemptRequestId"),
      str(payment.get("requestId")))
check("代扣落 TCC xid + 渠道路由留痕", bool(payment.get("xid")) and bool(payment.get("channelCode")),
      str(payment)[:200])
channel_trade = ok("GET", f"/api/channel/trades/{deduct['paymentId']}")
check("渠道流水 CAPTURED 且金额=代扣金额（一单一行）",
      channel_trade.get("status") == "CAPTURED" and channel_trade.get("amount") == amount,
      str(channel_trade)[:200])
deduct_order = ok("GET", f"/api/orders/{deduct['orderId']}")
check("交易单 PAID 且 outTradeNo=deductNo（商户侧幂等键）",
      deduct_order.get("status") == "PAID" and deduct_order.get("outTradeNo") == deduct_no,
      str(deduct_order)[:200])
transfer = ok("GET", f"/api/accounting/transfers/{deduct['paymentId']}")
lines = (transfer or {}).get("lines") or []
debit_sum = sum(x["amount"] for x in lines if x.get("dir") == "D")
credit_sum = sum(x["amount"] for x in lines if x.get("dir") == "C")
check("复式记账凭证存在且 ΣD=ΣC 非零（bizNo=支付单id）",
      bool(lines) and debit_sum == credit_sum and debit_sum > 0, f"D={debit_sum} C={credit_sum}")
check("CARD 代扣不动付款户 BALANCE 余额（资金来自银行卡渠道，口径与 verify-sign 一致）",
      bal(PAYER_ID) == balance_before, f"{bal(PAYER_ID)} vs {balance_before}")
check("商户入账 = 金额 − 手续费（口径不变）",
      bal(MERCHANT_ACC) == merchant_balance_before + amount - fee_of(amount),
      f"{bal(MERCHANT_ACC)} vs {merchant_balance_before + amount - fee_of(amount)}")
check("手续费入 SYS_FEE（口径不变）", bal("SYS_FEE") >= fee_of(amount), str(bal("SYS_FEE")))
check("代扣新增恰一笔支付单", payments_count() == payments_before_deduct + 1,
      f"{payments_before_deduct} → {payments_count()}")
balance_after = bal(PAYER_ID)
st, deduct_replay, _ = call(legacy_id, legacy_secret, "channel.agent.pay",
                            {"signNo": sign_no, "amount": amount, "requestId": deduct_rid,
                             "merchantId": legacy_merchant})
check("代扣 requestId 幂等重放（同扣款单 + repeated=true）",
      st == 200 and deduct_replay.get("deductNo") == deduct_no and deduct_replay.get("repeated") is True,
      f"{st} {deduct_replay}")
check("代扣幂等重放不重复扣款/不新增支付单",
      bal(PAYER_ID) == balance_after and payments_count() == payments_before_deduct + 1,
      f"{bal(PAYER_ID)} vs {balance_after}")
st, over, _ = call(legacy_id, legacy_secret, "channel.agent.pay",
                   {"signNo": sign_no, "amount": 99999, "requestId": f"{TAG}-DEDUCT-OVER",
                    "merchantId": legacy_merchant})
check("超单笔限额受理前拒绝（B0001/A0403，不落悬挂单）",
      st in (400, 403) and over.get("code") in ("B0001", "A0403"), f"{st} {over}")
check("拒绝后余额/支付单数不变（绝不落悬挂单）",
      bal(PAYER_ID) == balance_after and payments_count() == payments_before_deduct + 1,
      f"{bal(PAYER_ID)} / {payments_count()}")
st, wrong_merchant, _ = call(legacy_id, legacy_secret, "channel.agent.pay",
                             {"signNo": sign_no, "amount": 100, "requestId": f"{TAG}-DEDUCT-MCH",
                              "merchantId": "M-NOT-AUTHORIZED"})
check("非授权商户代扣受理前拒绝", st == 403, f"{st} {wrong_merchant}")
st, cancelled, _ = call(legacy_id, legacy_secret, "channel.sign",
                        {"action": "UNSIGN", "signNo": sign_no, "requestId": f"{TAG}-UNSIGN",
                         "reason": "MCTEST 验证解约"})
check("channel.sign action=UNSIGN 解约成功（CANCELLED）",
      st == 200 and cancelled.get("status") == "CANCELLED", f"{st} {cancelled}")
st, after_cancel, _ = call(legacy_id, legacy_secret, "channel.agent.pay",
                           {"signNo": sign_no, "amount": 100, "requestId": f"{TAG}-DEDUCT-CANCELLED",
                            "merchantId": legacy_merchant})
check("解约后代扣拒绝（受理前，余额不变）",
      st in (403, 409) and bal(PAYER_ID) == balance_after, f"{st} {after_cancel} bal={bal(PAYER_ID)}")
st, unknown, _ = call(legacy_id, legacy_secret, "channel.agent.pay",
                      {"signNo": "SIGN-NOT-EXIST", "amount": 100, "requestId": f"{TAG}-DEDUCT-404"})
check("未知协议号代扣 404", st == 404, f"{st} {unknown}")

# ======================================================================
print("== 5. 限流登记 + 拒绝在受理前 + appM1 legacy 不误拦 ==")
for label, path in (("live", SENTINEL_LIVE), ("example", SENTINEL_EXAMPLE)):
    with open(path, encoding="utf-8") as f:
        rules = json.load(f)
    param = {r["resource"]: r for r in rules.get("paramFlowRules", [])}
    missing = [f"openapi:{a}" for a in CASHIER + SIGN if f"openapi:{a}" not in param]
    check(f"sentinel {label}：11 条新 paramFlowRules 已登记", not missing, str(missing))
    check(f"sentinel {label}：既有 11 条阈值一字未改",
          all(param.get(k, {}).get("count") == v for k, v in LEGACY_PARAM_RULES.items()),
          str({k: param.get(k, {}).get("count") for k in LEGACY_PARAM_RULES}))
    check(f"sentinel {label}：新规则 paramIdx=0（appId 维度）+ grade=1（QPS）",
          all(param[f"openapi:{a}"].get("paramIdx") == 0 and param[f"openapi:{a}"].get("grade") == 1
              for a in CASHIER + SIGN if f"openapi:{a}" in param), str(param)[:200])
    write_ok = all(param[f"openapi:{a}"]["count"] <= 10 for a in
                   ("cashier.scanPay", "cashier.payVerified", "channel.agent.pay", "channel.sign")
                   if f"openapi:{a}" in param)
    read_ok = all(param[f"openapi:{a}"]["count"] >= 30 for a in
                  ("cashier.methods", "cashier.code.parse", "cashier.code.query") if f"openapi:{a}" in param)
    check(f"sentinel {label}：保守阈值（资金写 ≤10 QPS 对齐 trade.pay，只读 ≥30 QPS）", write_ok and read_ok,
          str({a: param.get(f"openapi:{a}", {}).get("count") for a in CASHIER + SIGN}))

# 注册生产应用（独立 appId，避免影响 ride/legacy）→ 突发触发热点限流
burst_id, burst_secret, burst_merchant = new_merchant("BURST")
ok("POST", "/api/openplatform/apps", {"name": f"{TAG}-BURST", "appId": burst_id,
                                      "rateLimit": 0, "dailyQuota": 0, "unitPrice": 0})
payments_before_burst = payments_count()
codes = []
for index in range(30):
    st, resp, _ = call(burst_id, burst_secret, "cashier.scanPay",
                       {"code": "000000000000000000", "requestId": f"{TAG}-BURST-{index}"})
    codes.append((st, resp.get("code") if isinstance(resp, dict) else None))
limited = [c for c in codes if c[0] == 429]
check("注册应用突发触发 appId 热点限流 429 A0429（openapi:cashier.scanPay）",
      len(limited) >= 1 and all(c[1] == "A0429" for c in limited), str(codes))
check("限流拒绝在受理前：不落悬挂支付单", payments_count() == payments_before_burst,
      f"{payments_before_burst} → {payments_count()}")
check("未触发限流的请求按业务口径 404（码不存在，非 500）",
      all(c[0] in (429, 404) for c in codes), str(codes))

# legacy（未注册 appId，与 ride appM1 同形态）：顺序调用全通、免费、不限配额
legacy_payments_before = payments_count()
order = ok("POST", "/api/orders", {"merchantId": legacy_merchant, "subject": "MCTEST legacy 行程",
                                   "amount": 100})["order"]
legacy_ok = True
for index in range(6):
    st, resp, _ = call(legacy_id, legacy_secret, "cashier.methods",
                       {"orderId": order["id"], "accountId": PAYER_ID})
    legacy_ok = legacy_ok and st == 200 and isinstance(resp, dict) and resp.get("actualAmount") == 100
check("legacy 未注册 appId 顺序调 cashier.methods 全通（新规则不误拦）", legacy_ok, "见上")
st, resp, hdr = call(legacy_id, legacy_secret, "cashier.methods", {"orderId": order["id"]},
                   headers={"X-Api-Version": "v2"})
check("v1 路径不被版本头改变行为（cashier 仍裸数据 + X-Api-Version=v1）",
      st == 200 and isinstance(resp, dict) and "apiVersion" not in resp and hdr.get("X-Api-Version") == "v1",
      f"{st} {hdr.get('X-Api-Version')}")
st, paid, _ = call(legacy_id, legacy_secret, "cashier.payVerified",
                   {"orderId": order["id"], "accountId": PAYER_ID, "method": "BALANCE",
                    "requestId": f"{TAG}-LEGACY-PAY"})
check("legacy 收银台受理成功（BALANCE 同步终态，免密 100 ≤ 1000）",
      st == 200 and paid["payment"]["status"] == "SUCCESS", f"{st} {paid}")
legacy_payment_id = paid["payment"]["id"] if st == 200 else None
st, frozen_bal, _ = call(legacy_id, legacy_secret, "account.balance", {"accountId": PAYER_ID})
check("legacy account.balance 仍裸数字 long（红线）", st == 200 and isinstance(frozen_bal, int),
      f"{st} {frozen_bal}")
st, queried, _ = call(legacy_id, legacy_secret, "trade.query", {"orderId": order["id"]})
check("legacy trade.query 形状不变 {order,payment}",
      st == 200 and queried.get("payment", {}).get("id") == legacy_payment_id, f"{st} {queried}")
quota = ok("GET", f"/api/openplatform/quota?appId={legacy_id}")
check("legacy 配额不限（registered=false / remaining=None）",
      quota.get("registered") is False and quota.get("remaining") is None, str(quota)[:200])
bill = ok("GET", f"/api/openplatform/bill?appId={legacy_id}")
check("legacy 只免费计数（unitPrice=0，含 cashier/channel 新方法）",
      bool(bill) and all(r["billedAmount"] == 0 for r in bill)
      and any(r["api"].startswith("cashier.") for r in bill)
      and any(r["api"].startswith("channel.") for r in bill), str(bill)[:300])
st, nonce_resp, _ = call(legacy_id, legacy_secret, "cashier.methods", {"orderId": order["id"]},
                         nonce=f"{TAG}-nonce-1")
st2, nonce_replay, _ = call(legacy_id, legacy_secret, "cashier.methods", {"orderId": order["id"]},
                            nonce=f"{TAG}-nonce-1")
check("可选 nonce 首次通过 / 重放 409 A0409（口径不变）",
      st == 200 and st2 == 409 and nonce_replay.get("code") == "A0409", f"{st}/{st2} {nonce_replay}")

# ======================================================================
print("== 6. 多端受理一致（H5 / MINI_APP / APP / PC，clientType additive 透传） ==")
client_order = ok("POST", "/api/orders", {"merchantId": legacy_merchant,
                                          "subject": "MCTEST 多端一致行程", "amount": 200})["order"]
client_rid = f"{TAG}-MULTICLIENT-PAY"
balance_before_clients = bal(PAYER_ID)
results = {}
for client in ("H5", "MINI_APP", "APP", "PC"):
    st, resp, _ = call(legacy_id, legacy_secret, "cashier.payVerified",
                       {"orderId": client_order["id"], "accountId": PAYER_ID, "method": "BALANCE",
                        "requestId": client_rid, "clientType": client})
    results[client] = (st, resp)
    check(f"{client} 端受理成功（同订单同金额同 requestId）",
          st == 200 and resp["payment"]["status"] == "SUCCESS"
          and resp["payment"]["amount"] == 200, f"{st} {resp}")
ids = {resp["payment"]["id"] for st, resp in results.values() if st == 200}
check("四端受理结果一致（同一支付单，幂等吸收）", len(ids) == 1, str(ids))
check("四端只扣款一次（payer 余额 = 原余额 − 金额）",
      bal(PAYER_ID) == balance_before_clients - 200,
      f"{bal(PAYER_ID)} vs {balance_before_clients - 200}")
check("四端重放标记 repeated=true（首端之后）",
      all(resp.get("repeated") is True for client, (st, resp) in results.items() if client != "H5"),
      str({k: v[1].get("repeated") for k, v in results.items()}))
view = call_ok(legacy_id, legacy_secret, "cashier.methods", {"orderId": client_order["id"],
                                                            "accountId": PAYER_ID})
check("受理后渲染一致（订单已支付态 PAID/SUCCESS + 方式集稳定）",
      view["order"]["status"] in ("PAID", "SUCCESS") and len(view["methods"]) >= 9, str(view)[:200])

print()
print(f"verify-multiclient: {PASSED} passed, {FAILED} failed (共 {PASSED + FAILED} 项)")
sys.exit(1 if FAILED else 0)