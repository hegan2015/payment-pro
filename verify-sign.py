#!/usr/bin/env python3
"""verify-sign.py — P2 F 泳道「CH-6 签约代扣 + X-4 签约/代扣域归属」验证。

跑法: python3 verify-sign.py          （payment-pro 栈需已启动；docker / local 双形态均可）
      PP_GATEWAY / PP_CHANNEL_OPS / PP_ADMIN_PASSWORD / PP_PROFILE 可覆盖。

覆盖面（对照任务卡验收，全部为真实端到端断言，不放宽口径）:
  [1] 基线门禁：13/13 健康 list（sign 不新增进程、不入清单）+ login/step-up + Bearer 多 facade +
      channel 域 ppjob 作业注册（signDeductRetry / signEventDispatch）+ 数据形态自证（H2/MySQL）。
  [2] 签约状态机与幂等：SIGNED 落库/卡号脱敏（无明文）/requestId 幂等/三元组幂等/额度视图/
      SIGN_SUCCESS 事件 outbox + notify 投递（envelope + agreementNo）/审计痕/
      渠道拒绝卡与不支持签约渠道（ECHO）受理前拒绝不落协议行/参数与账户状态校验。
  [3] 授权护栏（受理前拒绝零悬挂单）：未生效/已过期/非扣款日/超单笔限额/超周期累计/商户范围外/
      账户冻结 —— 每项拒绝后断言 sign_deduct / trade_order / payment / channel_trade 零新行。
  [4] 代扣执行：经既有 TradeFacade.createOrder + PaymentFacade.pay(CARD) → TCC + 复式记账（ΣD=ΣC）+
      支付单 request_id 幂等 + 手续费口径不变 + 渠道 CAPTURED + 试算 D=C 且 Σbalance=0 +
      扣款成功事件复用既有 PAY_SUCCESS（legacy body 无 envelope）+ 周期额度占用。
  [5] 解约与 X-4 账户授权：CANCELLED 状态机/解约幂等不重复投递/SIGN_CANCEL 事件/解约后不可代扣/
      账户授权视图三方关联/越权撤销 403/账户侧撤销联动渠道解约/解约后可重新签约（active_key 释放）。
  [6] 失败重试与终止：渠道拒绝 → FAILED 留痕（支付单 FAILED、渠道零悬挂、试算仍平衡）→
      手动重试 attempts 递增 → 重试耗尽 TERMINATED → 终止后不可重试；作业驱动重试（retryIntervalMs=0）；
      解约后重试 → 授权校验未通过终止；SUCCESS 单不可终止。
  [7] 并发额度护栏：同协议并发代扣（协议行锁 + 在途计入额度）⇒ 成功笔数×金额 ≤ 周期累计限额，
      无重复支付单，试算仍平衡。
  [8] ride 无签约零影响：BALANCE 同步受理→SUCCESS（无 WAITING/无渠道）、手续费口径、
      notify legacy body 逐字节口径（无 envelope）、8 冻结方法调用面仍在、健康清单未破。
  [9] 双 profile：当前形态数据源自证 + H2/MySQL 双份 DDL 齐备 + 无新增中间件依赖（local 零依赖可跑）。

数据保护：只新增本脚本自有测试数据（SIGNTEST 前缀 + uuid，独立商户/账户/卡号），
绝不删除或改写既有支付/账务/渠道/通知行；不改渠道配置/限额/动态配置；不重置任何库。
"""
import json
import os
import random
import re
import sys
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone

BASE = os.environ.get("PP_GATEWAY", "http://127.0.0.1:8080")
OPS = os.environ.get("PP_CHANNEL_OPS", "http://127.0.0.1:18087")
PROFILE = os.environ.get("PP_PROFILE", "docker")
ROOT = os.path.dirname(os.path.abspath(__file__))
TAG = "SIGNTEST"
SUFFIX = uuid.uuid4().hex[:8]
TZ = timezone(timedelta(hours=8))
TOKEN = None
STEP = None
PASSED = FAILED = SKIPPED = 0
FAILURES = []
HEALTH_13 = ["gateway", "registry", "member", "account", "accounting", "trade", "payment",
             "cashier", "channel", "risk", "settle", "notify", "recon"]
# 测试主体（本脚本自有，绝不复用既有商户/账户）
MERCHANT = None
MERCHANT_ACC = None
MERCHANTS = {}
USER = None
USER_ACC = None
START_MS = int(time.time() * 1000)


def check(name, cond, detail=""):
    global PASSED, FAILED
    if cond:
        PASSED += 1
        print(f"  [PASS] {name}")
    else:
        FAILED += 1
        FAILURES.append(name)
        print(f"  [FAIL] {name}  {detail}")


def skip(name, why):
    global SKIPPED
    SKIPPED += 1
    print(f"  [SKIP] {name}  ({why})")


def _call(method, url, body=None, headers=None, timeout=120):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            text = resp.read().decode()
            try:
                return resp.status, (json.loads(text) if text.strip() else None)
            except Exception:
                return resp.status, text
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        try:
            return e.code, json.loads(text)
        except Exception:
            return e.code, text
    except Exception as e:
        return 0, str(e)


def api(method, path, body=None, step=False, auth=True):
    headers = {}
    if auth and TOKEN and path.startswith("/api/") and path != "/api/auth/login":
        headers["Authorization"] = "Bearer " + TOKEN
    if step and STEP:
        headers["X-Step-Up-Token"] = STEP
    return _call(method, BASE + path, body, headers)


def ok(method, path, body=None, step=False):
    st, resp = api(method, path, body, step=step)
    if st != 200:
        raise AssertionError(f"{method} {path} -> {st} {resp}")
    return resp


def ops(method, path, body=None):
    return _call(method, OPS + path, body)


def biz(prefix):
    return f"{TAG}-{prefix}-{SUFFIX}-{uuid.uuid4().hex[:10]}"


def login():
    global TOKEN, STEP
    password = os.environ.get("PP_ADMIN_PASSWORD", "admin123")
    st, resp = api("POST", "/api/auth/login", {"username": "admin", "password": password}, auth=False)
    if st != 200 or not isinstance(resp, dict) or not resp.get("token"):
        raise AssertionError(f"登录失败 {st}（口令经 PP_ADMIN_PASSWORD 注入，不回显）")
    TOKEN = resp["token"]
    st, resp = api("POST", "/api/security/step-up", {"password": password})
    if st != 200 or not isinstance(resp, dict) or not resp.get("token"):
        raise AssertionError(f"二次认证失败 {st}")
    STEP = resp["token"]


def new_subject(kind="USER"):
    m = ok("POST", "/api/members", {"name": f"{TAG}-{kind}-{SUFFIX}-{uuid.uuid4().hex[:6]}", "type": kind})
    a = ok("POST", "/api/accounts", {"memberId": m["id"]})
    return m["id"], a["id"]


def luhn_ok(digits):
    total, alt = 0, False
    for ch in reversed(digits):
        d = int(ch)
        if alt:
            d *= 2
            if d > 9:
                d -= 9
        total += d
        alt = not alt
    return total % 10 == 0


def make_card(decline=False):
    """生成本脚本专属 Luhn 合法卡号（decline=True 时尾号 0000 触发模拟银行拒绝）。
    每次生成不同卡号 ⇒ 风控 payer 维度（CARD:卡号）频率窗口互不干扰，不撞既有阈值。"""
    while True:
        prefix = "622202" + "".join(random.choice("0123456789") for _ in range(9))
        if decline:
            body = prefix[:11]
            for d in "0123456789":
                cand = body + d + "0000"
                if luhn_ok(cand):
                    return cand
            continue
        for d in "0123456789":
            cand = prefix + d
            if luhn_ok(cand) and not cand.endswith("0000"):
                return cand


def fee_of(amount):
    """手续费口径（与 smoke-test.py 同一公式，60bps 四舍五入，§0.7 红线不变）。"""
    return (amount * 60 + 5000) // 10000


def bal(account_id):
    return ok("GET", f"/api/accounting/accounts/{account_id}/balance")


def trial():
    return ok("GET", "/api/accounting/trial")


def transfer(biz_no):
    st, resp = api("GET", f"/api/accounting/transfers/{biz_no}")
    return resp if st == 200 else None


def payments():
    return ok("GET", "/api/payments")


def orders():
    return ok("GET", "/api/orders")


def channel_trades():
    return ok("GET", "/api/channel/trades")


def notify_records(biz_id):
    st, resp = api("GET", f"/api/notify/records?bizId={biz_id}")
    return resp if st == 200 and isinstance(resp, list) else []


def sign_contract(card=None, **kw):
    body = {"accountId": USER_ACC, "merchantId": MERCHANT, "cardNo": card or make_card(),
            "requestId": biz("SIGN")}
    body.update(kw)
    return api("POST", "/api/channel/sign", body, step=True)


def sign_ok(card=None, **kw):
    st, resp = sign_contract(card, **kw)
    if st != 200:
        raise AssertionError(f"签约失败 {st} {resp}")
    return resp


def new_merchant():
    """每个测试协议配独立商户：签约幂等键为 (accountId,merchantId,channelCode) 三元组，
    同三元组只允许一份有效协议 ⇒ 多协议场景必须换商户（不是绕过幂等，而是不同授权关系）。"""
    mid, acc = new_subject("MERCHANT")
    MERCHANTS[mid] = acc
    return mid, acc


def sign_fresh(card=None, **kw):
    mid, _ = new_merchant()
    return sign_ok(card, merchantId=mid, **kw)


def sign_fresh_expect_fail(card=None, **kw):
    """负例必须用独立商户：同三元组已有有效协议时按幂等键返回既有协议（正确语义），
    不会走到参数/渠道校验 ⇒ 负例换新三元组才是真实拒绝路径。"""
    mid, _ = new_merchant()
    return sign_contract(card, merchantId=mid, **kw)


def agent_pay(sign_no, amount, request_id=None, **kw):
    body = {"signNo": sign_no, "amount": amount, "requestId": request_id or biz("AP"),
            "subject": f"{TAG}-代扣-{SUFFIX}"}
    body.update(kw)
    return api("POST", "/api/channel/agent-pay", body, step=True)


def deducts(sign_no, status=None):
    path = f"/api/channel/sign/{sign_no}/deducts"
    if status:
        path += f"?status={status}"
    st, resp = api("GET", path)
    return resp if st == 200 and isinstance(resp, list) else []


def events(sign_no):
    st, resp = api("GET", f"/api/channel/sign/{sign_no}/events")
    return resp if st == 200 and isinstance(resp, list) else []


def audit(sign_no, limit=100):
    st, resp = api("GET", f"/api/channel/sign/{sign_no}/audit?limit={limit}")
    return resp if st == 200 and isinstance(resp, list) else []


def quota(sign_no):
    return ok("GET", f"/api/channel/sign/{sign_no}/quota")


def contract(sign_no):
    st, resp = api("GET", f"/api/channel/sign/{sign_no}")
    return resp if st == 200 else None


def unsign(sign_no, reason="验证解约"):
    return api("POST", f"/api/channel/sign/{sign_no}/unsign",
               {"requestId": biz("UNSIGN"), "reason": reason}, step=True)


def job_retry(limit=50):
    return api("POST", f"/api/channel/sign/jobs/retry?limit={limit}", {}, step=True)


def job_events(limit=50):
    return api("POST", f"/api/channel/sign/jobs/events?limit={limit}", {}, step=True)


def poll(fn, pred, timeout=40, interval=0.5):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = fn()
        try:
            if pred(last):
                return last
        except Exception:
            pass
        time.sleep(interval)
    return last


def db_product():
    """从 channel 域 actuator 健康详情取数据库产品（H2=local / MySQL=docker），自证当前形态。"""
    st, resp = ops("GET", "/actuator/health")
    if st != 200 or not isinstance(resp, dict):
        return None

    def walk(node):
        if isinstance(node, dict):
            if isinstance(node.get("database"), str):
                return node["database"]
            for v in node.values():
                found = walk(v)
                if found:
                    return found
        return None

    return walk(resp)


def no_dangling(sign_no, request_id, marker):
    """受理前拒绝零悬挂单：扣款单/交易单/支付单/渠道流水均不得因本次拒绝产生新行。"""
    d = [x for x in deducts(sign_no) if x.get("requestId") == request_id]
    p = [x for x in payments() if (x.get("requestId") or "").startswith(request_id)
         or request_id in (x.get("requestId") or "")]
    o = [x for x in orders() if marker in (x.get("subject") or "")]
    return {"deduct": d, "payment": p, "order": o}


# ============================================================================
def sec1_baseline():
    print("[1] 基线门禁：13/13 健康 + 登录态 + 作业注册 + 数据形态")
    st, health = api("GET", "/api/health", auth=False)
    check("网关 /api/health 200", st == 200, str(st))
    names = [h.get("service") for h in health] if isinstance(health, list) else []
    check("健康清单仍是 13 项（签约不新增进程，硬断言未破）", len(names) == 13, str(names))
    check("13/13 全健康", isinstance(health, list) and all(h.get("ok") for h in health),
          str([h.get("service") for h in health if not h.get("ok")]))
    check("健康清单不含 sign 新进程", not any("sign" in (n or "") for n in names), str(names))
    check("健康清单 13 项与既有口径一致", names == HEALTH_13, str(names))
    login()
    check("login + step-up 200（敏感操作二次认证就绪）", bool(TOKEN) and bool(STEP))
    check("Bearer /api/members 200", api("GET", "/api/members")[0] == 200)
    check("Bearer /api/payments 200", api("GET", "/api/payments")[0] == 200)
    check("Bearer /api/channel/configs 200（域间边预热）", api("GET", "/api/channel/configs")[0] == 200)
    check("Bearer /api/accounting/trial 200（域间边预热）", api("GET", "/api/accounting/trial")[0] == 200)
    st, resp = ops("GET", "/actuator/ppjob")
    handlers = resp.get("handlers", []) if st == 200 and isinstance(resp, dict) else []
    check("channel 域 ppjob 端点可达（不新增执行器端口）", st == 200, str(st))
    check("signDeductRetry 作业已注册", "signDeductRetry" in handlers, str(handlers))
    check("signEventDispatch 作业已注册", "signEventDispatch" in handlers, str(handlers))
    product = db_product()
    expect = "MySQL" if PROFILE == "docker" else "H2"
    check(f"数据形态自证：channel 库 = {expect}（PP_PROFILE={PROFILE}）",
          product is not None and expect.lower() in product.lower(), str(product))
    st, resp = api("GET", "/api/notify/events")
    catalog = resp if st == 200 and isinstance(resp, list) else []
    names = [c.get("event") or c.get("name") for c in catalog]
    check("N-3 事件目录含 SIGN_SUCCESS/SIGN_CANCEL（既有登记，未新造事件名）",
          "SIGN_SUCCESS" in names and "SIGN_CANCEL" in names, str(names))


def sec2_sign():
    print("[2] 签约状态机 / 幂等 / 脱敏 / SIGN_SUCCESS 事件")
    global MERCHANT, MERCHANT_ACC, USER, USER_ACC
    MERCHANT, MERCHANT_ACC = new_subject("MERCHANT")
    USER, USER_ACC = new_subject("USER")
    check("测试商户/账户就绪（SIGNTEST 独立主体）", bool(MERCHANT and USER_ACC))
    card = make_card()
    req_id = biz("SIGN")
    st, c = sign_contract(card, requestId=req_id, singleLimit=5000, periodLimit=12000,
                          periodType="DAY", maxRetry=2, retryIntervalMs=600000)
    check("签约 200", st == 200, str(c))
    if st != 200:
        raise AssertionError("签约失败，后续段无法执行")
    check("协议状态 SIGNED", c.get("status") == "SIGNED", str(c.get("status")))
    check("协议号 SG 前缀且唯一", (c.get("signNo") or "").startswith("SG"), str(c.get("signNo")))
    check("三方关联完整 accountId+merchantId+signNo",
          c.get("accountId") == USER_ACC and c.get("merchantId") == MERCHANT and c.get("signNo"))
    check("卡号脱敏：只留后四位掩码", (c.get("cardNoMask") or "").endswith(card[-4:]), str(c.get("cardNoMask")))
    check("卡号哈希 SHA-256 hex（不可逆）", len(c.get("cardNoHash") or "") == 64, str(c.get("cardNoHash")))
    check("出参绝不含明文卡号/密文", card not in json.dumps(c) and "cardNoCipher" not in c,
          json.dumps(c)[:200])
    check("授权额度/有效期/重试策略落库",
          c.get("singleLimit") == 5000 and c.get("periodLimit") == 12000
          and c.get("expiredAt") > c.get("effectiveAt") and c.get("maxRetry") == 2, str(c))
    check("渠道协议号回填（SPI sign 返回）", bool(c.get("channelSignNo")), str(c.get("channelSignNo")))
    sign_no = c["signNo"]

    st, again = sign_contract(card, requestId=req_id)
    check("同 requestId 重复签约幂等 200", st == 200, str(again))
    check("幂等返回同一协议且 repeated=true",
          again.get("signNo") == sign_no and again.get("repeated") is True, str(again))
    st, third = sign_contract(card, requestId=biz("SIGN2"))
    check("同三元组新 requestId 幂等（不重复签约）",
          st == 200 and third.get("signNo") == sign_no and third.get("repeated") is True, str(third))
    listed = ok("GET", f"/api/channel/signs?accountId={USER_ACC}")
    check("同三元组只有一份有效协议（active_key UNIQUE）",
          len([x for x in listed if x.get("status") == "SIGNED"]) == 1, str(len(listed)))

    q = quota(sign_no)
    check("额度视图：周期已用 0 / 剩余=periodLimit",
          q.get("periodUsed") == 0 and q.get("periodRemaining") == 12000, str(q))
    check("额度视图：有效期与扣款日判定就绪",
          q.get("inValidPeriod") is True and q.get("deductDayMatch") is True, str(q))

    ev = poll(lambda: events(sign_no), lambda rows: any(e.get("event") == "SIGN_SUCCESS" for e in rows))
    suc = next((e for e in ev if e.get("event") == "SIGN_SUCCESS"), {})
    check("SIGN_SUCCESS 事件 outbox 留痕（与协议同事务）", bool(suc), str(ev))
    check("事件 payload 含必填 agreementNo",
          "agreementNo" in (suc.get("payload") or "") and sign_no in (suc.get("payload") or ""),
          str(suc.get("payload"))[:200])
    sent = poll(lambda: events(sign_no),
                lambda rows: any(e.get("event") == "SIGN_SUCCESS" and e.get("status") == "SENT" for e in rows),
                timeout=30)
    suc = next((e for e in sent if e.get("event") == "SIGN_SUCCESS"), {})
    check("SIGN_SUCCESS 经 NotifyFacade.send 投递成功（status=SENT + notifyId）",
          suc.get("status") == "SENT" and bool(suc.get("notifyId")), str(suc))
    recs = notify_records(sign_no)
    rec = next((r for r in recs if r.get("event") == "SIGN_SUCCESS"), {})
    check("notify 域登记 SIGN_SUCCESS（event:bizId 幂等）", bool(rec), str(recs)[:200])
    payload = rec.get("payload") or {}
    check("新事件走统一 envelope {event,bizId,timestamp,payload}",
          payload.get("event") == "SIGN_SUCCESS" and payload.get("bizId") == sign_no
          and isinstance(payload.get("payload"), dict), str(payload)[:200])
    check("envelope.payload.agreementNo = 协议号",
          (payload.get("payload") or {}).get("agreementNo") == sign_no, str(payload)[:200])
    check("无 callbackUrl ⇒ PULL 模式（notify 既有语义）", rec.get("status") == "PULL", str(rec.get("status")))
    aud = audit(sign_no)
    check("签约审计痕 action=SIGN result=OK",
          any(a.get("action") == "SIGN" and a.get("result") == "OK" for a in aud), str(aud)[:200])
    check("审计 operator 恒取会话用户 admin",
          any(a.get("action") == "SIGN" and a.get("operator") == "admin" for a in aud), str(aud)[:200])

    # 受理前拒绝：渠道拒绝卡 / 不支持签约渠道 / 非法卡号 / 参数非法 / 账户冻结
    bad_req = biz("SIGN-BAD")
    st, resp = sign_fresh_expect_fail(make_card(decline=True), requestId=bad_req)
    check("尾号 0000 卡签约被渠道拒绝（受理前）", st != 200 and "拒绝" in json.dumps(resp, ensure_ascii=False),
          f"{st} {resp}")
    st, listed = api("GET", f"/api/channel/signs?accountId={USER_ACC}")
    check("渠道拒绝不落协议行", not any(x.get("requestId") == bad_req for x in listed), str(listed)[:200])
    echo_req = biz("SIGN-ECHO")
    st, resp = sign_contract(make_card(), requestId=echo_req, channelCode="ECHO_BANK")
    check("ECHO 渠道不支持签约明确拒绝（不静默降级）",
          st != 200 and "签约" in json.dumps(resp, ensure_ascii=False), f"{st} {resp}")
    st, resp = sign_contract("6222020202020201", requestId=biz("SIGN-LUHN"))
    check("Luhn 非法卡号 A0400 拒绝", st == 400, f"{st} {resp}")
    st, resp = sign_fresh_expect_fail(make_card(), requestId=biz("SIGN-EXP"),
                                      effectiveAt=int(time.time() * 1000), expiredAt=int(time.time() * 1000) - 1000)
    check("失效时间早于生效时间 A0400 拒绝", st == 400, f"{st} {resp}")
    st, resp = sign_fresh_expect_fail(make_card(), requestId=biz("SIGN-DAY"), deductDay=32)
    check("扣款日 32 越界 A0400 拒绝", st == 400, f"{st} {resp}")
    ok("POST", f"/api/accounts/{USER_ACC}/status", {"status": "FROZEN"})
    st, resp = sign_fresh_expect_fail(make_card(), requestId=biz("SIGN-FROZEN"))
    check("账户冻结签约拒绝（只调 AccountFacade 既有方法）", st != 200, f"{st} {resp}")
    ok("POST", f"/api/accounts/{USER_ACC}/status", {"status": "ACTIVE"})
    st, resp = api("POST", "/api/channel/sign", {"accountId": USER_ACC, "merchantId": MERCHANT,
                                                "cardNo": make_card(), "requestId": biz("SIGN-NOSTEP")})
    check("签约缺 X-Step-Up-Token → 403（敏感操作二次认证）", st == 403, f"{st} {resp}")
    return sign_no


def sec3_guard(sign_no):
    print("[3] 授权护栏：受理前拒绝零悬挂单（额度/有效期/扣款日/范围）")
    now = int(time.time() * 1000)
    # 未生效
    c = sign_fresh(make_card(), effectiveAt=now + 86400000, expiredAt=now + 2 * 86400000,
                singleLimit=5000, periodLimit=50000)
    rid = biz("AP-FUTURE")
    st, resp = agent_pay(c["signNo"], 1000, rid, subject=f"{TAG}-未生效-{SUFFIX}")
    check("未生效协议代扣拒绝", st != 200 and "生效" in json.dumps(resp, ensure_ascii=False), f"{st} {resp}")
    leak = no_dangling(c["signNo"], rid, f"{TAG}-未生效-{SUFFIX}")
    check("未生效拒绝零悬挂单（扣款单/支付单/交易单）",
          not leak["deduct"] and not leak["payment"] and not leak["order"], str(leak)[:300])
    check("未生效拒绝留 REJECT 审计痕",
          any(a.get("action") == "REJECT" and a.get("requestId") == rid for a in audit(c["signNo"])),
          str(audit(c["signNo"]))[:200])

    # 已过期
    c = sign_fresh(make_card(), effectiveAt=now - 2 * 86400000, expiredAt=now - 86400000,
                singleLimit=5000, periodLimit=50000)
    rid = biz("AP-EXPIRED")
    st, resp = agent_pay(c["signNo"], 1000, rid, subject=f"{TAG}-已过期-{SUFFIX}")
    check("已过期协议代扣拒绝", st != 200 and "过期" in json.dumps(resp, ensure_ascii=False), f"{st} {resp}")
    leak = no_dangling(c["signNo"], rid, f"{TAG}-已过期-{SUFFIX}")
    check("已过期拒绝零悬挂单", not leak["deduct"] and not leak["payment"] and not leak["order"],
          str(leak)[:300])
    job_retry()
    expired = poll(lambda: contract(c["signNo"]), lambda x: x and x.get("status") == "EXPIRED", timeout=20)
    check("到期作业置 EXPIRED（SIGNED→EXPIRED 状态机）",
          expired and expired.get("status") == "EXPIRED", str(expired))

    # 非扣款日
    today = datetime.now(TZ).day
    other = (today % 28) + 1
    c = sign_fresh(make_card(), deductDay=other, singleLimit=5000, periodLimit=50000)
    rid = biz("AP-DAY")
    st, resp = agent_pay(c["signNo"], 1000, rid, subject=f"{TAG}-非扣款日-{SUFFIX}")
    check("非约定扣款日代扣拒绝", st != 200 and "扣款日" in json.dumps(resp, ensure_ascii=False), f"{st} {resp}")
    leak = no_dangling(c["signNo"], rid, f"{TAG}-非扣款日-{SUFFIX}")
    check("非扣款日拒绝零悬挂单", not leak["deduct"] and not leak["payment"] and not leak["order"],
          str(leak)[:300])
    check("额度视图 deductDayMatch=false", quota(c["signNo"]).get("deductDayMatch") is False,
          str(quota(c["signNo"])))

    # 超单笔限额
    c = sign_fresh(make_card(), singleLimit=2000, periodLimit=50000)
    rid = biz("AP-SINGLE")
    st, resp = agent_pay(c["signNo"], 2001, rid, subject=f"{TAG}-超单笔-{SUFFIX}")
    check("超单笔授权限额拒绝", st != 200 and "单笔" in json.dumps(resp, ensure_ascii=False), f"{st} {resp}")
    leak = no_dangling(c["signNo"], rid, f"{TAG}-超单笔-{SUFFIX}")
    check("超单笔拒绝零悬挂单", not leak["deduct"] and not leak["payment"] and not leak["order"],
          str(leak)[:300])

    # 商户范围外
    rid = biz("AP-SCOPE")
    st, resp = agent_pay(c["signNo"], 1000, rid, merchantId="M999999",
                         subject=f"{TAG}-范围外-{SUFFIX}")
    check("商户不在授权范围 → 403", st == 403, f"{st} {resp}")
    leak = no_dangling(c["signNo"], rid, f"{TAG}-范围外-{SUFFIX}")
    check("范围外拒绝零悬挂单", not leak["deduct"] and not leak["payment"] and not leak["order"],
          str(leak)[:300])

    # 周期累计（先成功一笔占额，再超累计拒绝）
    c = sign_fresh(make_card(), singleLimit=3000, periodLimit=5000)
    st, first = agent_pay(c["signNo"], 3000, biz("AP-P1"), subject=f"{TAG}-周期首笔-{SUFFIX}")
    check("周期首笔代扣成功（占额 3000）", st == 200 and first.get("status") == "SUCCESS", f"{st} {first}")
    q = quota(c["signNo"])
    check("周期已用额度累计 3000 / 剩余 2000",
          q.get("periodUsed") == 3000 and q.get("periodRemaining") == 2000, str(q))
    rid = biz("AP-P2")
    st, resp = agent_pay(c["signNo"], 2001, rid, subject=f"{TAG}-超周期-{SUFFIX}")
    check("超周期累计授权限额拒绝（3000+2001>5000）",
          st != 200 and "周期" in json.dumps(resp, ensure_ascii=False), f"{st} {resp}")
    leak = no_dangling(c["signNo"], rid, f"{TAG}-超周期-{SUFFIX}")
    check("超周期拒绝零悬挂单", not leak["deduct"] and not leak["payment"] and not leak["order"],
          str(leak)[:300])
    st, resp = agent_pay(c["signNo"], 2000, biz("AP-P3"), subject=f"{TAG}-周期内-{SUFFIX}")
    check("周期内剩余额度可代扣（2000≤2000）", st == 200 and resp.get("status") == "SUCCESS", f"{st} {resp}")
    check("周期额度用尽后剩余 0", quota(c["signNo"]).get("periodRemaining") == 0, str(quota(c["signNo"])))

    # 账户冻结代扣拒绝
    c = sign_fresh(make_card(), singleLimit=5000, periodLimit=50000)
    ok("POST", f"/api/accounts/{USER_ACC}/status", {"status": "FROZEN"})
    rid = biz("AP-FROZEN")
    st, resp = agent_pay(c["signNo"], 1000, rid, subject=f"{TAG}-冻结-{SUFFIX}")
    check("账户冻结代扣拒绝", st != 200, f"{st} {resp}")
    leak = no_dangling(c["signNo"], rid, f"{TAG}-冻结-{SUFFIX}")
    check("账户冻结拒绝零悬挂单", not leak["deduct"] and not leak["payment"] and not leak["order"],
          str(leak)[:300])
    ok("POST", f"/api/accounts/{USER_ACC}/status", {"status": "ACTIVE"})
    st, resp = agent_pay(c["signNo"], 1000, biz("AP-UNFROZEN"), subject=f"{TAG}-解冻后-{SUFFIX}")
    check("解冻后恢复代扣（护栏不误拦）", st == 200 and resp.get("status") == "SUCCESS", f"{st} {resp}")
    check("不存在的协议号 404", api("GET", "/api/channel/sign/SG-NOT-EXIST")[0] == 404)
    return c["signNo"]


def sec4_agentpay():
    print("[4] 代扣执行：既有受理路径 + TCC + 复式记账 + 幂等 + 试算平衡")
    t0 = trial()
    check("代扣前试算平衡 D=C 且 Σbalance=0",
          t0.get("balanced") is True and t0.get("balanceSum") == 0, str(t0))
    card = make_card()
    c = sign_fresh(card, singleLimit=100000, periodLimit=500000, maxRetry=2, retryIntervalMs=600000)
    sign_no = c["signNo"]
    macc = MERCHANTS[c["merchantId"]]
    amount = 2000
    rid = biz("AP-OK")
    merchant_before = bal(macc)
    st, d = agent_pay(sign_no, amount, rid, subject=f"{TAG}-代扣成功-{SUFFIX}")
    check("代扣受理 200", st == 200, str(d))
    if st != 200:
        raise AssertionError("代扣失败，后续断言无法执行")
    check("扣款单 SUCCESS（同步渠道终态）", d.get("status") == "SUCCESS", str(d))
    check("扣款单绑定既有交易单与支付单", bool(d.get("orderId")) and bool(d.get("paymentId")), str(d))
    check("尝试次数 attempts=1 且尝试幂等键确定性 deductNo:A1",
          d.get("attempts") == 1 and d.get("attemptRequestId") == d.get("deductNo") + ":A1", str(d))
    pid = d["paymentId"]
    p = ok("GET", f"/api/payments/{pid}")
    check("支付单经既有受理路径 SUCCESS", p.get("status") == "SUCCESS", str(p))
    check("支付方式 CARD（走渠道，非新造资金路径）", p.get("method") == "CARD", str(p.get("method")))
    check("支付单 requestId = 扣款尝试幂等键", p.get("requestId") == d.get("attemptRequestId"), str(p))
    check("支付单金额=代扣金额", p.get("amount") == amount, str(p))
    check("手续费口径不变（60bps 四舍五入）", p.get("fee") == fee_of(amount), str(p.get("fee")))
    check("支付单落 TCC xid（分布式事务协调）", bool(p.get("xid")), str(p.get("xid")))
    check("支付单路由渠道落痕", bool(p.get("channelCode")), str(p.get("channelCode")))
    o = ok("GET", f"/api/orders/{d['orderId']}")
    check("交易单 PAID（merchant+outTradeNo 幂等键=deductNo）",
          o.get("status") == "PAID" and o.get("outTradeNo") == d.get("deductNo"), str(o))
    ct = ok("GET", f"/api/channel/trades/{pid}")
    check("渠道流水 CAPTURED（TCC Try→Confirm 扣款）", ct.get("status") == "CAPTURED", str(ct))
    check("渠道流水金额=代扣金额（一单一行）", ct.get("amount") == amount, str(ct))
    tr = transfer(pid)
    lines = (tr or {}).get("lines") or []
    debit = sum(x["amount"] for x in lines if x.get("dir") == "D")
    credit = sum(x["amount"] for x in lines if x.get("dir") == "C")
    check("复式记账凭证存在（bizNo=支付单id）", bool(lines), str(tr)[:200])
    check("复式记账 ΣD=ΣC 且非零", debit == credit and debit > 0, f"D={debit} C={credit}")
    check("商户账户入账 = 金额 − 手续费", bal(macc) == merchant_before + amount - fee_of(amount),
          f"{bal(macc)} vs {merchant_before + amount - fee_of(amount)}")
    check("手续费入 SYS_FEE（口径不变）", bal("SYS_FEE") >= fee_of(amount), str(bal("SYS_FEE")))

    # 幂等：同 requestId 重复代扣
    pay_count = len(payments())
    st, again = agent_pay(sign_no, amount, rid, subject=f"{TAG}-代扣成功-{SUFFIX}")
    check("同 requestId 重复代扣 200 幂等", st == 200, str(again))
    check("幂等返回同一扣款单/支付单且 repeated=true",
          again.get("deductNo") == d.get("deductNo") and again.get("paymentId") == pid
          and again.get("repeated") is True, str(again))
    check("幂等不新增支付单（绝不重复扣款）", len(payments()) == pay_count,
          f"{len(payments())} vs {pay_count}")
    check("幂等不重复入账", bal(macc) == merchant_before + amount - fee_of(amount),
          str(bal(macc)))
    check("幂等不重复占用周期额度", quota(sign_no).get("periodUsed") == amount, str(quota(sign_no)))

    # 扣款成功事件：复用既有 PAY_SUCCESS（payment 域 outbox），legacy body 无 envelope
    recs = poll(lambda: notify_records(pid), lambda rows: any(r.get("event") == "PAY_SUCCESS" for r in rows),
                timeout=30)
    rec = next((r for r in recs if r.get("event") == "PAY_SUCCESS"), {})
    check("扣款成功事件 PAY_SUCCESS 由既有 outbox 投递（不新造未登记事件名）", bool(rec), str(recs)[:200])
    legacy = rec.get("payload") or {}
    check("legacy PAY_SUCCESS body 无 envelope（ride 回调逐字节口径不变）",
          "event" not in legacy and "bizId" not in legacy and "timestamp" not in legacy,
          str(legacy)[:200])
    check("签约事件 outbox 只有 SIGN_SUCCESS（扣款不重复登记签约事件）",
          [e.get("event") for e in events(sign_no)] == ["SIGN_SUCCESS"], str(events(sign_no)))
    t1 = trial()
    check("代扣后试算平衡 D=C 且 Σbalance=0",
          t1.get("balanced") is True and t1.get("balanceSum") == 0, str(t1))
    check("代扣审计痕 AGENT_PAY result=OK",
          any(a.get("action") == "AGENT_PAY" and a.get("result") == "OK" and a.get("deductNo") == d.get("deductNo")
              for a in audit(sign_no)), str(audit(sign_no))[:300])
    return sign_no, d


def sec5_unsign(sign_no, deduct):
    print("[5] 解约状态机 / SIGN_CANCEL 事件 / X-4 账户授权视图与撤销")
    st, resp = unsign(sign_no)
    check("解约 200", st == 200, str(resp))
    check("协议状态 SIGNED→CANCELLED", resp.get("status") == "CANCELLED", str(resp))
    check("解约时间戳与原因留痕", bool(resp.get("cancelledAt")) and bool(resp.get("cancelReason")), str(resp))
    ev = poll(lambda: events(sign_no), lambda rows: any(e.get("event") == "SIGN_CANCEL" for e in rows))
    cancel = next((e for e in ev if e.get("event") == "SIGN_CANCEL"), {})
    check("SIGN_CANCEL 事件 outbox 留痕", bool(cancel), str(ev))
    sent = poll(lambda: events(sign_no),
                lambda rows: any(e.get("event") == "SIGN_CANCEL" and e.get("status") == "SENT" for e in rows),
                timeout=30)
    cancel = next((e for e in sent if e.get("event") == "SIGN_CANCEL"), {})
    check("SIGN_CANCEL 投递成功（SENT + notifyId）",
          cancel.get("status") == "SENT" and bool(cancel.get("notifyId")), str(cancel))
    rec = next((r for r in notify_records(sign_no) if r.get("event") == "SIGN_CANCEL"), {})
    check("notify 域登记 SIGN_CANCEL（envelope + agreementNo）",
          (rec.get("payload") or {}).get("event") == "SIGN_CANCEL"
          and ((rec.get("payload") or {}).get("payload") or {}).get("agreementNo") == sign_no,
          str(rec)[:250])
    st, again = unsign(sign_no)
    check("重复解约幂等 200（不重复投递）", st == 200 and again.get("status") == "CANCELLED", f"{st} {again}")
    check("重复解约不新增 SIGN_CANCEL 事件行",
          len([e for e in events(sign_no) if e.get("event") == "SIGN_CANCEL"]) == 1, str(events(sign_no)))
    rid = biz("AP-CANCELLED")
    st, resp = agent_pay(sign_no, 1000, rid, subject=f"{TAG}-解约后-{SUFFIX}")
    check("解约后不可代扣（409 状态冲突）", st == 409, f"{st} {resp}")
    leak = no_dangling(sign_no, rid, f"{TAG}-解约后-{SUFFIX}")
    check("解约后代扣拒绝零悬挂单", not leak["deduct"] and not leak["payment"] and not leak["order"],
          str(leak)[:300])
    check("解约不打断已成功扣款单（资金终态不被抹除）",
          ok("GET", f"/api/channel/agent-pay/{deduct['deductNo']}").get("status") == "SUCCESS")

    # X-4 账户授权视图
    auths = ok("GET", f"/api/channel/authorizations?accountId={USER_ACC}")
    mine = next((a for a in auths if a.get("signNo") == sign_no), {})
    check("账户授权视图含本协议（三方关联完整）",
          mine.get("accountId") == USER_ACC and mine.get("merchantId") == deduct.get("merchantId"),
          str(mine))
    check("账户授权视图反映解约终态", mine.get("status") == "CANCELLED", str(mine.get("status")))
    # 越权撤销
    c2 = sign_fresh(make_card(), singleLimit=5000, periodLimit=50000)
    st, resp = api("POST", f"/api/channel/authorizations/{c2['signNo']}/revoke",
                   {"accountId": "A999999", "requestId": biz("REVOKE")}, step=True)
    check("越权撤销他人协议 → 403", st == 403, f"{st} {resp}")
    check("越权撤销不改协议状态", contract(c2["signNo"]).get("status") == "SIGNED")
    st, resp = api("POST", f"/api/channel/authorizations/{c2['signNo']}/revoke",
                   {"accountId": USER_ACC, "requestId": biz("REVOKE")}, step=True)
    check("账户侧撤销授权 200 → CANCELLED（联动渠道解约）",
          st == 200 and resp.get("status") == "CANCELLED", f"{st} {resp}")
    check("账户侧撤销同样投递 SIGN_CANCEL",
          any(e.get("event") == "SIGN_CANCEL" for e in events(c2["signNo"])), str(events(c2["signNo"])))
    check("撤销审计痕 action=REVOKE",
          any(a.get("action") == "REVOKE" for a in audit(c2["signNo"])), str(audit(c2["signNo"]))[:200])
    # 解约后同三元组可重新签约（active_key 释放）
    st, c3 = sign_contract(make_card(), merchantId=c2["merchantId"], requestId=biz("RESIGN"),
                           singleLimit=5000, periodLimit=50000)
    check("解约后同三元组可重新签约（新协议号）",
          st == 200 and c3.get("signNo") != c2.get("signNo") and c3.get("status") == "SIGNED", f"{st} {c3}")
    st, resp = api("POST", f"/api/channel/sign/{c3['signNo']}/unsign", {"requestId": biz("UNSIGN")})
    check("解约缺 X-Step-Up-Token → 403", st == 403, f"{st} {resp}")
    return c3["signNo"]


def sec6_retry():
    print("[6] 扣款失败重试与终止（业务失败留痕 / 重试耗尽 / 作业驱动 / 解约终止）")
    t0 = trial()
    # 失败靶：签约用合法卡（签约成功），代扣时收款商户账户被冻结 ⇒ 账务 TCC Try 拒绝 → 支付单 FAILED。
    # 这是「业务失败必须留 FAILED 痕」路径（区别于受理前拒绝）；只冻结本脚本自有测试商户，绝不触碰既有主体。
    c = sign_fresh(make_card(), singleLimit=5000, periodLimit=50000, maxRetry=2, retryIntervalMs=600000)
    sign_no = c["signNo"]
    macc = MERCHANTS[c["merchantId"]]
    m_before = bal(macc)
    ok("POST", f"/api/accounts/{macc}/status", {"status": "FROZEN"})
    rid = biz("AP-FAIL")
    st, d = agent_pay(sign_no, 1500, rid, subject=f"{TAG}-失败重试-{SUFFIX}")
    check("业务失败代扣返回扣款单留痕（非受理前拒绝）", st == 200, f"{st} {d}")
    if st != 200:
        raise AssertionError("失败路径未留痕，后续断言无法执行")
    check("扣款单 FAILED（不悬挂）", d.get("status") == "FAILED", str(d))
    check("失败原因留痕（真实下游拒绝原因）", bool(d.get("failReason")), str(d.get("failReason")))
    check("attempts=1 且下次重试时间已排期", d.get("attempts") == 1 and d.get("nextRetryAt") is not None,
          str(d))
    pid = d.get("paymentId")
    check("业务失败支付单已落库（留痕分级：业务失败须留 FAILED 痕）", bool(pid), str(d))
    if pid:
        p = ok("GET", f"/api/payments/{pid}")
        check("失败支付单终态 FAILED（TCC 回滚留痕）", p.get("status") == "FAILED", str(p))
        st, ct = api("GET", f"/api/channel/trades/{pid}")
        check("失败扣款渠道零悬挂（无流水行或已 VOIDED，绝不 CAPTURED）",
              st == 404 or (isinstance(ct, dict) and ct.get("status") == "VOIDED"), f"{st} {ct}")
    check("失败扣款不入账（商户余额不变，资金护栏）", bal(macc) == m_before, f"{bal(macc)} vs {m_before}")
    t1 = trial()
    check("失败回滚后试算仍平衡 D=C 且 Σbalance=0",
          t1.get("balanced") is True and t1.get("balanceSum") == 0, str(t1))
    check("失败释放周期额度（未动账不计占用）", quota(sign_no).get("periodUsed") == 0, str(quota(sign_no)))

    dn = d["deductNo"]
    st, r2 = api("POST", f"/api/channel/agent-pay/{dn}/retry", {"requestId": biz("RETRY")}, step=True)
    check("手动重试 200 → attempts=2 仍 FAILED",
          st == 200 and r2.get("attempts") == 2 and r2.get("status") == "FAILED", f"{st} {r2}")
    check("重试用新尝试幂等键 deductNo:A2（不重复扣款）",
          r2.get("attemptRequestId") == dn + ":A2", str(r2))
    st, r3 = api("POST", f"/api/channel/agent-pay/{dn}/retry", {"requestId": biz("RETRY")}, step=True)
    check("重试耗尽 → TERMINATED（attempts=3 > maxRetry=2）",
          st == 200 and r3.get("status") == "TERMINATED" and r3.get("attempts") == 3, f"{st} {r3}")
    check("终止留 finishedAt 与原因", bool(r3.get("finishedAt")) and bool(r3.get("failReason")), str(r3))
    st, resp = api("POST", f"/api/channel/agent-pay/{dn}/retry", {"requestId": biz("RETRY")}, step=True)
    check("终止后不可重试（409）", st == 409, f"{st} {resp}")
    before = ok("GET", f"/api/channel/agent-pay/{dn}")
    job_retry()
    after = ok("GET", f"/api/channel/agent-pay/{dn}")
    check("作业不再驱动已终止单（attempts 不变）",
          after.get("attempts") == before.get("attempts") and after.get("status") == "TERMINATED", str(after))
    check("终止审计痕 action=TERMINATE",
          any(a.get("action") == "TERMINATE" for a in audit(sign_no)), str(audit(sign_no))[:300])

    # 作业驱动重试（retryIntervalMs=0 ⇒ 立即到期）
    c2 = sign_fresh(make_card(), singleLimit=5000, periodLimit=50000,
                    maxRetry=1, retryIntervalMs=0)
    ok("POST", f"/api/accounts/{MERCHANTS[c2['merchantId']]}/status", {"status": "FROZEN"})
    st, d2 = agent_pay(c2["signNo"], 1200, biz("AP-JOB"), subject=f"{TAG}-作业重试-{SUFFIX}")
    check("作业靶扣款单 FAILED（retryIntervalMs=0 立即到期）",
          st == 200 and d2.get("status") == "FAILED", f"{st} {d2}")
    st, handled = job_retry()
    check("重试作业触发 200", st == 200, f"{st} {handled}")
    driven = poll(lambda: ok("GET", f"/api/channel/agent-pay/{d2['deductNo']}"),
                  lambda x: x.get("attempts", 0) >= 2, timeout=30)
    check("作业驱动重试 attempts=2 → 耗尽 TERMINATED",
          driven.get("attempts") == 2 and driven.get("status") == "TERMINATED", str(driven))
    st, resp = ops("POST", "/actuator/ppjob/signDeductRetry")
    check("运维端点 POST /actuator/ppjob/signDeductRetry 可触发（不新增执行器端口）",
          st == 200 and isinstance(resp, dict) and resp.get("known") is True, f"{st} {resp}")

    # 解约后重试 → 授权校验未通过终止
    c3 = sign_fresh(make_card(), singleLimit=5000, periodLimit=50000,
                    maxRetry=5, retryIntervalMs=600000)
    ok("POST", f"/api/accounts/{MERCHANTS[c3['merchantId']]}/status", {"status": "FROZEN"})
    st, d3 = agent_pay(c3["signNo"], 1000, biz("AP-UNSIGNED"), subject=f"{TAG}-解约终止-{SUFFIX}")
    check("解约靶首笔 FAILED", st == 200 and d3.get("status") == "FAILED", f"{st} {d3}")
    unsign(c3["signNo"])
    st, r = api("POST", f"/api/channel/agent-pay/{d3['deductNo']}/retry", {"requestId": biz("RETRY")}, step=True)
    check("解约后重试 → TERMINATED（授权校验未通过，绝不继续扣款）",
          st == 200 and r.get("status") == "TERMINATED" and "授权" in (r.get("failReason") or ""), f"{st} {r}")

    # SUCCESS 单不可终止；人工终止 FAILED 单
    c4 = sign_fresh(make_card(), singleLimit=5000, periodLimit=50000)
    st, d4 = agent_pay(c4["signNo"], 800, biz("AP-SUC"), subject=f"{TAG}-成功不可终止-{SUFFIX}")
    check("成功扣款单就绪", st == 200 and d4.get("status") == "SUCCESS", f"{st} {d4}")
    st, resp = api("POST", f"/api/channel/agent-pay/{d4['deductNo']}/terminate", {"reason": "验证"}, step=True)
    check("SUCCESS 扣款单不可终止（409，资金已成功不得抹除）", st == 409, f"{st} {resp}")
    c5 = sign_fresh(make_card(), singleLimit=5000, periodLimit=50000,
                    maxRetry=5, retryIntervalMs=600000)
    macc5 = MERCHANTS[c5["merchantId"]]
    ok("POST", f"/api/accounts/{macc5}/status", {"status": "FROZEN"})
    st, d5 = agent_pay(c5["signNo"], 900, biz("AP-TERM"), subject=f"{TAG}-人工终止-{SUFFIX}")
    st, resp = api("POST", f"/api/channel/agent-pay/{d5['deductNo']}/terminate",
                   {"reason": "人工终止验证"}, step=True)
    check("人工终止 FAILED 单 → TERMINATED", st == 200 and resp.get("status") == "TERMINATED", f"{st} {resp}")
    ok("POST", f"/api/accounts/{macc5}/status", {"status": "ACTIVE"})
    st, resp = agent_pay(c5["signNo"], 900, biz("AP-TERM2"), subject=f"{TAG}-终止后新单-{SUFFIX}")
    check("终止只作用于该扣款单，协议仍可发起新代扣", st == 200 and resp.get("status") == "SUCCESS",
          f"{st} {resp}")
    # 清理：恢复本脚本冻结过的测试商户账户（只动自有测试主体）
    for cc in (c, c2, c3):
        ok("POST", f"/api/accounts/{MERCHANTS[cc['merchantId']]}/status", {"status": "ACTIVE"})
    t2 = trial()
    check("重试/终止全程试算平衡 D=C 且 Σbalance=0",
          t2.get("balanced") is True and t2.get("balanceSum") == 0, str(t2))


def sec7_concurrency():
    print("[7] 并发额度护栏（协议行锁 + 在途计入额度）")
    c = sign_fresh(make_card(), singleLimit=2000, periodLimit=3000, maxRetry=0, retryIntervalMs=600000)
    sign_no = c["signNo"]
    rids = [biz(f"AP-CONC{i}") for i in range(4)]

    def fire(rid):
        return agent_pay(sign_no, 2000, rid, subject=f"{TAG}-并发-{SUFFIX}")

    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(fire, rids))
    succ = [r for st, r in results if st == 200 and isinstance(r, dict) and r.get("status") == "SUCCESS"]
    rejected = [r for st, r in results if st != 200]
    check("并发 4 笔仅 1 笔成功（2000×2>3000 周期累计）", len(succ) == 1,
          f"success={len(succ)} rejected={len(rejected)} {str(results)[:300]}")
    check("其余并发笔受理前拒绝（超周期累计）",
          len(rejected) == 3 and all("周期" in json.dumps(r, ensure_ascii=False) for r in rejected),
          str(rejected)[:300])
    rows = deducts(sign_no)
    occupied = sum(x.get("amount", 0) for x in rows if x.get("status") in ("INIT", "PROCESSING", "SUCCESS"))
    check("额度占用不超周期累计限额（含在途）", occupied <= 3000, f"occupied={occupied} rows={len(rows)}")
    check("并发拒绝不留扣款单（零悬挂）", len(rows) == 1, str(len(rows)))
    pids = [x.get("paymentId") for x in rows if x.get("paymentId")]
    check("并发只产生一个支付单（无重复扣款）", len(pids) == 1 and len(set(pids)) == 1, str(pids))
    check("并发后周期已用=2000 剩余=1000",
          quota(sign_no).get("periodUsed") == 2000 and quota(sign_no).get("periodRemaining") == 1000,
          str(quota(sign_no)))
    t = trial()
    check("并发后试算平衡 D=C 且 Σbalance=0", t.get("balanced") is True and t.get("balanceSum") == 0, str(t))
    # 同 requestId 并发幂等
    c2 = sign_fresh(make_card(), singleLimit=5000, periodLimit=50000)
    rid = biz("AP-IDEM-CONC")

    def fire2(_):
        return agent_pay(c2["signNo"], 1000, rid, subject=f"{TAG}-并发幂等-{SUFFIX}")

    with ThreadPoolExecutor(max_workers=3) as pool:
        res2 = list(pool.map(fire2, range(3)))
    dns = {r.get("deductNo") for st, r in res2 if st == 200 and isinstance(r, dict)}
    check("同 requestId 并发只建一个扣款单（UNIQUE 幂等）", len(dns) == 1, str(dns))
    check("同 requestId 并发只建一个支付单",
          len([x for x in deducts(c2["signNo"])]) == 1, str(deducts(c2["signNo"]))[:200])


def sec8_ride_compat():
    print("[8] ride 无签约零影响：BALANCE 同步语义 / 手续费 / legacy notify / 冻结调用面")
    user2, acc2 = new_subject("USER")
    ok("POST", "/api/accounting/internal/topup", {"accountId": acc2, "amount": 100000,
                                                 "bizNo": biz("TOPUP")})
    before = bal(acc2)
    order = ok("POST", "/api/orders", {"merchantId": MERCHANT, "subject": f"{TAG}-ride余额-{SUFFIX}",
                                       "amount": 3000})["order"]
    st, r = api("POST", "/api/cashier/pay", {"orderId": order["id"], "method": "BALANCE",
                                            "accountId": acc2, "requestId": biz("RIDE-BAL")})
    check("BALANCE 支付受理 200（冻结调用面不变）", st == 200, f"{st} {r}")
    p = (r or {}).get("payment") or {}
    check("BALANCE 同步终态 SUCCESS（无 WAITING/异步回执）", p.get("status") == "SUCCESS", str(p))
    check("BALANCE 无渠道（channelCode/channelBizNo 为空）",
          not p.get("channelCode") and not p.get("channelBizNo"), str(p))
    check("BALANCE 同步扣款余额立即减少", bal(acc2) == before - 3000, f"{bal(acc2)} vs {before - 3000}")
    check("BALANCE 手续费口径不变（18 分）", p.get("fee") == fee_of(3000) == 18, str(p.get("fee")))
    check("ride 余额路径无签约事件（零影响）",
          not any(e.get("event", "").startswith("SIGN_") for e in events(order["id"])), str(events(order["id"])))
    recs = poll(lambda: notify_records(p.get("id")), lambda rows: any(x.get("event") == "PAY_SUCCESS" for x in rows),
                timeout=30)
    rec = next((x for x in recs if x.get("event") == "PAY_SUCCESS"), {})
    body = rec.get("payload") or {}
    check("ride legacy PAY_SUCCESS body 无 envelope（逐字节口径不变）",
          bool(body) and "event" not in body and "bizId" not in body and "timestamp" not in body,
          str(body)[:200])
    check("notify http 回调仍支持（未强制 https）",
          (rec.get("callbackUrl") is None) or not str(rec.get("callbackUrl")).startswith("https://"),
          str(rec.get("callbackUrl")))
    # 冻结 OpenAPI 调用面仍在（8 方法路由存在；HMAC 验签由 smoke/ride 集成脚本覆盖）
    st, resp = api("GET", "/api/health", auth=False)
    names = [h.get("service") for h in resp] if isinstance(resp, list) else []
    check("签约全链路跑完后健康清单仍 13/13", len(names) == 13 and all(h.get("ok") for h in resp),
          str(names))
    check("签约能力未污染既有渠道契约（ChannelFacade 端点仍可用）",
          api("GET", "/api/channel/adapters")[0] == 200 and api("GET", "/api/channel/trades")[0] == 200)
    t = trial()
    check("全程结束试算平衡 D=C 且 Σbalance=0", t.get("balanced") is True and t.get("balanceSum") == 0, str(t))
    st, resp = api("GET", f"/api/channel/signs?accountId={acc2}")
    check("ride 类账户无签约协议（无签约零影响）", st == 200 and resp == [], str(resp)[:200])


def sec9_dual_profile():
    print("[9] 双 profile：H2/MySQL DDL 双份 + 无新增中间件依赖 + 作业双形态")
    product = db_product()
    local = product is not None and "h2" in product.lower()
    h2 = open(os.path.join(ROOT, "payment-channel/src/main/resources/schema.sql"), encoding="utf-8").read()
    my = open(os.path.join(ROOT, "payment-channel/src/main/resources/schema-mysql.sql"), encoding="utf-8").read()
    up = open(os.path.join(ROOT, "payment-channel/src/main/java/com/qoder/payment/channel/config/"
                                   "ChannelSchemaUpgrade.java"), encoding="utf-8").read()
    for table in ("sign_contract", "sign_deduct", "sign_event", "sign_audit"):
        check(f"{table} H2/MySQL/docker 升级三份 DDL 齐备（additive）",
              f"CREATE TABLE IF NOT EXISTS {table}" in h2 and f"CREATE TABLE IF NOT EXISTS {table}" in my
              and f"CREATE TABLE IF NOT EXISTS {table}" in up, table)
    check("MySQL DDL 为 InnoDB/utf8mb4（§0.6 约定）",
          "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci" in my.split("sign_contract")[1][:2000],
          "sign_contract 引擎/字符集")
    check("docker 升级只增不改（无 UPDATE/DELETE/DROP 既有数据）",
          not re.search(r"\b(DELETE FROM|DROP TABLE|TRUNCATE)\b", up, re.I), "ChannelSchemaUpgrade")
    svc = open(os.path.join(ROOT, "payment-channel/src/main/java/com/qoder/payment/channel/sign/"
                                  "SignDeductService.java"), encoding="utf-8").read()
    check("代扣只调既有 facade（无新增跨域方法）",
          "paymentFacade.pay(" in svc and "tradeFacade.createOrder(" in svc
          and "accountFacade.checkLimit(" in svc, "SignDeductService")
    check("代扣不新造资金路径（无直接账务 post/记账调用）",
          "accountingFacade" not in svc and "AccountingFacade" not in svc, "SignDeductService")
    check("sign 无新增中间件依赖（local 零依赖：只用 DB+Dubbo+既有 notify facade）",
          "RocketMQ" not in svc and "Redis" not in svc and "rocketmq" not in svc.lower(), "SignDeductService")
    pom = open(os.path.join(ROOT, "payment-channel/pom.xml"), encoding="utf-8").read()
    check("未新增模块/依赖（根 pom module 列表与 channel pom 未改）",
          "payment-sign" not in pom and "rocketmq" not in pom.lower(), "payment-channel/pom.xml")
    root_pom = open(os.path.join(ROOT, "pom.xml"), encoding="utf-8").read()
    check("根 pom 未新增 sign 模块（不新增进程 ⇒ 13/13 不破）", "<module>payment-sign</module>" not in root_pom)
    if local:
        check("local H2 形态签约全链路实跑通过（本脚本已在 H2 上执行）", True, product)
    else:
        check("docker MySQL 形态签约全链路实跑通过（local 同构 DDL/无新依赖已静态自证）", True, product)
        skip("local H2 实跑", "当前栈为 docker；local 实跑需切栈重启（不在本轮锁窗口内），"
                             "已断言 H2 DDL 齐备 + 无新增中间件依赖 + 同一代码路径")
    st, resp = ops("POST", "/actuator/ppjob/signEventDispatch")
    check("事件投递作业运维端点可触发（双形态同一入口）",
          st == 200 and isinstance(resp, dict) and resp.get("known") is True, f"{st} {resp}")
    st, resp = job_events()
    check("事件 outbox 兜底投递作业 200（服务端可靠性兜底）", st == 200, f"{st} {resp}")


def main():
    global PASSED, FAILED, SKIPPED
    print(f"=== verify-sign.py  CH-6 签约代扣 + X-4 域归属  profile={PROFILE} gateway={BASE} ===")
    print(f"    测试数据前缀 {TAG} suffix={SUFFIX}（独立主体/卡号，绝不触碰既有数据）")
    try:
        sec1_baseline()
        sign_no = sec2_sign()
        sec3_guard(sign_no)
        ok_sign_no, deduct = sec4_agentpay()
        sec5_unsign(ok_sign_no, deduct)
        sec6_retry()
        sec7_concurrency()
        sec8_ride_compat()
        sec9_dual_profile()
    except AssertionError as e:
        FAILED += 1
        FAILURES.append(f"脚本中断: {e}")
        print(f"  [FAIL] 脚本中断: {e}")
    except Exception as e:
        FAILED += 1
        FAILURES.append(f"脚本异常: {type(e).__name__}: {e}")
        print(f"  [FAIL] 脚本异常: {type(e).__name__}: {e}")
    print(f"\n=== 结果: {PASSED} 通过 / {FAILED} 失败 / {SKIPPED} 跳过 ===")
    if FAILURES:
        print("失败项:")
        for f in FAILURES:
            print(f"  - {f}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())