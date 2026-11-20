#!/usr/bin/env python3
"""verify-sentinel.py — wave3 I-7 网关治理 Sentinel + 防重放验证。
跑法:
  python3 verify-sentinel.py           # 非破坏段（可重复跑）：限流/资金受理安全/nonce/ride冻结面/规则容错/指标
  python3 verify-sentinel.py --chaos   # 混沌段：停 accounting → C0001→B0003 熔断降级；跑完必须全栈重启恢复

断言口径（IMPLEMENTATION-PLAN §2.A I-7 + §0.7 ride 兼容红线）:
  ① 压测 /openapi/trade.create 超阈值 → 429 A0429 规范错误体，被拒请求不留订单/PROCESSING 悬挂单；
  ② 停下游 accounting → 先 C0001（RPC 失败）后 B0003（熔断打开，受理前快速拒绝），规范错误体不泄漏堆栈；
  ③ 同 appId+nonce 重放拒绝 409；不传 nonce 仍正常通过（ride-pro 不传 nonce，行为不变）；
  ④ 规则文件缺失/损坏 → 默认放行不阻断支付；恢复后规则重新生效（热更新）；
  ⑤ 正常阈值下 ride 8 个冻结方法顺序调用全过，smoke 口径不回归（smoke-test.py 另行 79/79）。
测试数据独立前缀 SENTINEL + uuid，只增不删。
"""
import concurrent.futures
import hashlib
import hmac
import json
import os
import re
import signal
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("PP_GATEWAY", "http://127.0.0.1:8080")
ROOT = os.path.dirname(os.path.abspath(__file__))
RULES_FILE = os.path.expanduser("~/.config/payment-pro/sentinel-rules.json")
SUFFIX = uuid.uuid4().hex[:8]
ADMIN_PASS = os.environ.get("PP_ADMIN_PASSWORD", "admin123")
TOKEN = None
passed = failed = 0


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  [PASS] {name}")
    else:
        failed += 1
        print(f"  [FAIL] {name}  {detail}")


def req(method, path, body=None, token=None, timeout=30, accept="application/json"):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    if accept:
        r.add_header("Accept", accept)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            text = resp.read().decode()
            try:
                return resp.status, json.loads(text) if text else None
            except Exception:
                return resp.status, text
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        try:
            return e.code, json.loads(text)
        except Exception:
            return e.code, {"error": text}
    except Exception as e:
        return 0, {"error": str(e)}


def api(method, path, body=None):
    """控制台 /api/**：带 Bearer，401 自动重登一次。"""
    st, resp = req(method, path, body, token=TOKEN)
    if st == 401:
        login()
        st, resp = req(method, path, body, token=TOKEN)
    return st, resp


def ok(method, path, body=None):
    st, resp = api(method, path, body)
    assert st == 200, f"{method} {path} 期望 200 实际 {st}: {resp}"
    return resp


def login():
    global TOKEN
    st, resp = req("POST", "/api/auth/login", {"username": "admin", "password": ADMIN_PASS})
    assert st == 200 and resp.get("token"), f"admin 登录失败: {st} {resp}"
    TOKEN = resp["token"]


def sign_params(params, secret):
    items = sorted((k, v) for k, v in params.items() if v is not None and k != "sign")
    canonical = "&".join(f"{k}={v}" for k, v in items)
    return hmac.new(secret.encode(), canonical.encode(), hashlib.sha256).hexdigest()


def openapi(endpoint, secret, params, nonce=None):
    """构造签名请求并调用（nonce 可选 additive）。"""
    body = dict(params, appId=APP_ID, timestamp=int(time.time() * 1000))
    if nonce is not None:
        body["nonce"] = nonce
    body["sign"] = sign_params(body, secret)
    return req("POST", "/openapi/" + endpoint, body)


def bal(account_id):
    return ok("GET", f"/api/accounting/accounts/{account_id}/balance")


def poll(fn, pred, timeout=25, interval=0.4):
    deadline = time.time() + timeout
    val = fn()
    while time.time() < deadline and not pred(val):
        time.sleep(interval)
        val = fn()
    return val


def wait_payment(pid, status="SUCCESS"):
    return poll(lambda: ok("GET", f"/api/payments/{pid}"), lambda p: p["status"] == status)


def burst(endpoint, secret, make_params, n, workers=24):
    """并发压测：返回 [(status, resp, tag)]。"""
    def one(i):
        params, tag = make_params(i)
        st, resp = openapi(endpoint, secret, params)
        return st, resp, tag
    with concurrent.futures.ThreadPoolExecutor(workers) as ex:
        return list(ex.map(one, range(n)))


def no_stack(resp):
    text = json.dumps(resp, ensure_ascii=False)
    return ("Exception" not in text and "\tat " not in text and "java." not in text
            and "org.apache.dubbo" not in text)


def norm_error(resp, code):
    return (isinstance(resp, dict) and resp.get("code") == code
            and isinstance(resp.get("error"), str) and resp["error"] and no_stack(resp))


# ======================================================================
def warmup_inter_domain():
    """重启后 Dubbo 消费端 invoker 可能短暂 validInvokers=0（重连竞态）：
    用跨域往返（account.open: gateway→member/account→member/accounting；
    fund.topup: gateway→payment→trade/account/accounting）探测至稳定，最长 90s。"""
    deadline = time.time() + 90
    last = None
    while time.time() < deadline:
        try:
            st, ro = openapi("account.open", SECRET,
                             {"name": f"SENT-WARMUP{SUFFIX}", "type": "USER"})
            if st == 200 and ro.get("accountId"):
                st2, rt = openapi("fund.topup", SECRET,
                                  {"accountId": ro["accountId"], "amount": 1,
                                   "bizNo": f"SENT-WARMUP-{SUFFIX}"})
                if st2 == 200 and rt.get("payment", {}).get("id"):
                    p = wait_payment(rt["payment"]["id"])
                    if p["status"] == "SUCCESS":
                        return
                last = f"fund.topup {st2} {str(rt)[:80]}"
            else:
                last = f"account.open {st} {str(ro)[:80]}"
        except Exception as e:
            last = str(e)
        time.sleep(3)
    raise AssertionError(f"跨域预热 90s 未稳定（Dubbo invoker 重连竞态）: {last}")


def main_default():
    login()
    print(f"== verify-sentinel（批次 {SUFFIX}）==")
    st, health = req("GET", "/api/health")
    assert st == 200 and all(h["ok"] for h in health) and len(health) == 13, f"前置：需 13/13 健康: {health}"

    merchant = ok("POST", "/api/members", {"name": f"SENTINEL商户{SUFFIX}", "type": "MERCHANT"})
    ok("POST", "/api/accounts", {"memberId": merchant["id"]})  # 商户账户（trade.pay 手续费结算必需）
    user = ok("POST", "/api/members", {"name": f"SENTINEL用户{SUFFIX}", "type": "USER"})
    ua = ok("POST", "/api/accounts", {"memberId": user["id"]})
    ok("POST", "/api/accounting/internal/topup",
       {"accountId": ua["id"], "amount": 10_000_000, "bizNo": f"SENT-TOPUP-{SUFFIX}"})
    global APP_ID, SECRET
    APP_ID, SECRET = merchant["appId"], merchant["appSecret"]
    warmup_inter_domain()

    # ---------- 1. 限流：trade.create 压测超阈值 ----------
    print("[1] 压测 /openapi/trade.create：超 appId 阈值(20 QPS) → 429 A0429，受理前拒绝")
    prefix = f"SENT-C-{SUFFIX}-"
    results = burst("trade.create", SECRET,
                    lambda i: ({"subject": "压测商品", "amount": 100, "outTradeNo": f"{prefix}{i}"}, f"{prefix}{i}"),
                    n=150)
    accepted = [r for r in results if r[0] == 200]
    rejected = [r for r in results if r[0] == 429]
    check("突发 150 笔出现限流 429", len(rejected) >= 1, f"200={len(accepted)} 429={len(rejected)}")
    check("阈值内仍有成功受理", len(accepted) >= 1, f"200={len(accepted)}")
    check("429 全部规范错误体 A0429（无堆栈）", all(norm_error(r[1], "A0429") for r in rejected),
          str([r[1] for r in rejected[:3]]))
    check("无其它异常状态码", all(r[0] in (200, 429) for r in results),
          str([(r[0], r[1]) for r in results if r[0] not in (200, 429)][:3]))
    orders = ok("GET", "/api/orders")
    mine = {o["outTradeNo"]: o for o in orders if o.get("merchantId") == merchant["id"]
            and (o.get("outTradeNo") or "").startswith(prefix)}
    check("被拒请求零订单残留（受理前拒绝）", len(mine) == len(accepted),
          f"库中 {len(mine)} vs 受理 {len(accepted)}")
    check("被拒 outTradeNo 均不存在", all(r[2] not in mine for r in rejected))

    # ---------- 2. 资金受理安全：trade.pay 压测 ----------
    print("[2] 压测 /openapi/trade.pay：被拒不落支付单、无 PROCESSING 悬挂、余额只扣受理笔")
    pay_orders = [ok("POST", "/api/orders", {"merchantId": merchant["id"], "subject": "压测支付", "amount": 100})["order"]
                  for _ in range(30)]
    bal0 = bal(ua["id"])

    def pay_params(i):
        o = pay_orders[i]
        return {"orderId": o["id"], "method": "BALANCE", "accountId": ua["id"],
                "outTradeNo": f"SENT-P-{SUFFIX}-{i}"}, o["id"]
    results = burst("trade.pay", SECRET, pay_params, n=30)
    accepted = [r for r in results if r[0] == 200]
    rejected = [r for r in results if r[0] == 429]
    check("支付突发出现限流 429（阈值 10 QPS 保守）", len(rejected) >= 1, f"200={len(accepted)} 429={len(rejected)}")
    check("支付突发有真实受理（非空断言）", len(accepted) >= 1,
          str([(r[0], r[1]) for r in results if r[0] not in (200, 429)][:3]))
    check("支付 429 规范错误体 A0429", all(norm_error(r[1], "A0429") for r in rejected),
          str([r[1] for r in rejected[:3]]))
    succ = 0
    for st, resp, oid in accepted:
        pid = resp["payment"]["id"]
        p = wait_payment(pid)
        if p["status"] == "SUCCESS":
            succ += 1
    check("受理支付全部收敛 SUCCESS（BALANCE 同步路径不变）", succ == len(accepted), f"{succ}/{len(accepted)}")
    hanging = []
    for st, resp, oid in rejected:
        o = ok("GET", f"/api/orders/{oid}")
        if o.get("paymentId"):
            hanging.append((oid, o["paymentId"]))
    check("被拒支付零支付单（不落 PROCESSING 悬挂单）", not hanging, str(hanging[:5]))
    pays = [p for p in ok("GET", "/api/payments") if p.get("payerAccountId") == ua["id"]]
    check("该账户无 PROCESSING 残留", all(p["status"] != "PROCESSING" for p in pays),
          str([(p["id"], p["status"]) for p in pays if p["status"] == "PROCESSING"][:5]))
    check("余额只扣受理笔（被拒零扣款）", bal(ua["id"]) == bal0 - 100 * len(accepted),
          f"{bal(ua['id'])} vs {bal0 - 100 * len(accepted)}")

    # ---------- 3. 防重放：nonce 可选 ----------
    print("[3] nonce 防重放：同 appId+nonce 拒绝；不传 nonce 行为不变（ride 兼容红线）")
    time.sleep(1.5)  # 脱离压测滑动窗口（热点参数按最近 durationInSec 秒计数）

    def seq3(endpoint, params, nonce=None):
        time.sleep(0.1)  # 顺序 ≤10 QPS，远低于 trade.create 20 / fund.topup 8 阈值
        return openapi(endpoint, SECRET, params, nonce=nonce)
    n1 = "nonce-" + uuid.uuid4().hex
    st, r1 = seq3("trade.create", {"subject": "nonce首单", "amount": 100,
                                   "outTradeNo": f"SENT-N1-{SUFFIX}"}, nonce=n1)
    check("携带 nonce 首次通过", st == 200, f"{st} {r1}")
    st, r2 = seq3("trade.create", {"subject": "nonce首单", "amount": 100,
                                   "outTradeNo": f"SENT-N1-{SUFFIX}"}, nonce=n1)
    check("同 nonce 原样重放拒绝 409 A0409", st == 409 and norm_error(r2, "A0409"), f"{st} {r2}")
    st, r3 = seq3("trade.create", {"subject": "nonce换单", "amount": 100,
                                   "outTradeNo": f"SENT-N2-{SUFFIX}"}, nonce=n1)
    check("同 nonce 换业务参数仍拒绝（appId 维度去重）", st == 409 and r3.get("code") == "A0409", f"{st} {r3}")
    st, r4 = seq3("trade.create", {"subject": "无nonce单A", "amount": 100,
                                   "outTradeNo": f"SENT-NN-A-{SUFFIX}"})
    check("不传 nonce 正常通过①", st == 200, f"{st} {r4}")
    st, r5 = seq3("trade.create", {"subject": "无nonce单B", "amount": 100,
                                   "outTradeNo": f"SENT-NN-B-{SUFFIX}"})
    check("不传 nonce 正常通过②", st == 200, f"{st} {r5}")
    st, r6 = seq3("trade.create", {"subject": "无nonce单A", "amount": 100,
                                   "outTradeNo": f"SENT-NN-A-{SUFFIX}"})
    check("不传 nonce 原样重放走既有幂等（200 同单，非 409）", st == 200 and r6.get("id") == r4.get("id"),
          f"{st} {r6}")
    st, r7 = seq3("fund.topup", {"accountId": ua["id"], "amount": 500,
                                 "bizNo": f"SENT-FN-{SUFFIX}"}, nonce="nonce-" + uuid.uuid4().hex)
    check("资金单携带 nonce 通过", st == 200 and r7.get("payment", {}).get("id"), f"{st} {r7}")
    if st == 200:
        wait_payment(r7["payment"]["id"])
    st, r8 = seq3("fund.topup", {"accountId": ua["id"], "amount": 500,
                                 "bizNo": f"SENT-FN-{SUFFIX}"})
    check("资金单不传 nonce 幂等回放仍 200（ride 语义）", st == 200, f"{st} {r8}")
    st, r9 = seq3("trade.create", {"subject": "超长nonce", "amount": 100,
                                   "outTradeNo": f"SENT-NL-{SUFFIX}"}, nonce="x" * 65)
    check("超长 nonce 400 A0400", st == 400 and r9.get("code") == "A0400", f"{st} {r9}")

    # ---------- 4. ride 冻结面：8 方法顺序无 nonce 全过 ----------
    print("[4] ride 兼容：8 个冻结方法顺序调用（无 nonce，正常阈值下零拒绝）")
    time.sleep(1.5)
    seq = []

    def s(endpoint, params):
        time.sleep(0.1)  # ≤10 QPS，远低于各端点阈值
        st, resp = openapi(endpoint, SECRET, params)
        seq.append((endpoint, st, resp))
        return st, resp
    st, ro = s("account.open", {"name": f"SENT-RIDE用户{SUFFIX}", "type": "USER"})
    check("account.open 200 返回 memberId/accountId", st == 200 and ro.get("memberId") and ro.get("accountId"), f"{st} {ro}")
    ride_acct = ro.get("accountId")
    st, ro2 = s("account.open", {"name": f"SENT-RIDE用户{SUFFIX}", "type": "USER"})
    check("account.open 幂等懒开户（同 id）", st == 200 and ro2.get("accountId") == ride_acct, f"{st} {ro2}")
    st, rt = s("fund.topup", {"accountId": ride_acct, "amount": 5000, "bizNo": f"SENT-R-TOPUP-{SUFFIX}"})
    check("fund.topup 200 返回 payment.id", st == 200 and rt.get("payment", {}).get("id"), f"{st} {rt}")
    if st == 200:
        check("fund.topup SUCCESS（SYS_FUND 同步路径）", wait_payment(rt["payment"]["id"])["status"] == "SUCCESS")
    st, rw = s("fund.withdraw", {"accountId": ride_acct, "amount": 1000, "bizNo": f"SENT-R-WD-{SUFFIX}"})
    check("fund.withdraw 200 返回 payment.id", st == 200 and rw.get("payment", {}).get("id"), f"{st} {rw}")
    if st == 200:
        check("fund.withdraw SUCCESS", wait_payment(rw["payment"]["id"])["status"] == "SUCCESS")
    st, _ = s("account.transfer", {"fromAccountId": ride_acct, "toAccountId": ua["id"],
                                   "amount": 500, "bizNo": f"SENT-R-TR-{SUFFIX}"})
    check("account.transfer 200（借贷腿划转）", st == 200, str(st))
    st, rb = s("account.balance", {"accountId": ride_acct})
    check("account.balance 裸数字且金额正确(5000-1000-500)", st == 200 and rb == 3500, f"{st} {rb}")
    st, rc = s("trade.create", {"subject": "ride商品", "amount": 800, "outTradeNo": f"SENT-R-OC-{SUFFIX}"})
    check("trade.create 200 返回 id", st == 200 and rc.get("id"), f"{st} {rc}")
    st, rp = s("trade.pay", {"orderId": rc.get("id"), "accountId": ride_acct, "method": "BALANCE",
                             "outTradeNo": f"SENT-R-OP-{SUFFIX}"})
    check("trade.pay 200 BALANCE 同步受理", st == 200 and rp.get("payment", {}).get("id"), f"{st} {rp}")
    if st == 200:
        check("trade.pay SUCCESS（无 WAITING/异步回执）", wait_payment(rp["payment"]["id"])["status"] == "SUCCESS")
    st, rq = s("trade.query", {"orderId": rc.get("id")})
    check("trade.query 200 订单+支付单（手续费可回查）", st == 200 and rq.get("order") and rq.get("payment")
          and rq["payment"].get("fee") is not None, f"{st} {str(rq)[:150]}")
    check("冻结面 8 方法零限流拒绝", all(st == 200 for _, st, _ in seq),
          str([(e, st) for e, st, _ in seq if st != 200]))

    # ---------- 5. 规则文件容错 + 热更新 ----------
    print("[5] 规则文件缺失/损坏 → 默认放行不阻断支付；恢复 → 规则热生效")
    original = open(RULES_FILE, encoding="utf-8").read()
    bak = RULES_FILE + f".verify-bak-{SUFFIX}"
    try:
        with open(RULES_FILE, "w", encoding="utf-8") as f:
            f.write("{{{ 这不是合法 JSON ")
        time.sleep(8)  # > 5s 轮询周期
        results = burst("trade.create", SECRET,
                        lambda i: ({"subject": "容错压测", "amount": 100,
                                    "outTradeNo": f"SENT-FT-C-{SUFFIX}-{i}"}, i), n=60)
        check("规则损坏 → 突发 60 笔默认放行（零 429）", all(r[0] == 200 for r in results),
              str([(r[0], r[1]) for r in results if r[0] != 200][:3]))
        os.replace(RULES_FILE, bak)  # 缺失场景
        time.sleep(8)
        results = burst("trade.create", SECRET,
                        lambda i: ({"subject": "缺失压测", "amount": 100,
                                    "outTradeNo": f"SENT-FT-M-{SUFFIX}-{i}"}, i), n=60)
        check("规则缺失 → 突发 60 笔默认放行（零 429）", all(r[0] == 200 for r in results),
              str([(r[0], r[1]) for r in results if r[0] != 200][:3]))
    finally:
        with open(RULES_FILE, "w", encoding="utf-8") as f:
            f.write(original)
        os.chmod(RULES_FILE, 0o600)
        if os.path.exists(bak):
            os.remove(bak)
    time.sleep(8)
    results = burst("trade.create", SECRET,
                    lambda i: ({"subject": "恢复压测", "amount": 100,
                                "outTradeNo": f"SENT-FT-R-{SUFFIX}-{i}"}, i), n=60)
    check("规则恢复 → 限流重新生效（出现 429）", any(r[0] == 429 for r in results),
          f"200={sum(1 for r in results if r[0] == 200)}")

    # ---------- 6. 治理指标 ----------
    print("[6] 治理指标（/actuator/prometheus）")
    st, text = req("GET", "/actuator/prometheus", accept="text/plain")
    text = text if isinstance(text, str) else json.dumps(text)
    check("prometheus 端点 200", st == 200, str(st))

    def metric_sum(name):
        vals = [float(m.group(1)) for m in re.finditer(r"^" + name + r"(?:\{[^}]*\})?\s+([0-9.eE+-]+)$", text, re.M)]
        return sum(vals) if vals else None
    check("openapi_rate_reject 计数 > 0", (metric_sum("payment_gateway_openapi_rate_reject_total") or 0) > 0,
          str(metric_sum("payment_gateway_openapi_rate_reject_total")))
    check("openapi_replay_reject 计数 > 0", (metric_sum("payment_gateway_openapi_replay_reject_total") or 0) > 0,
          str(metric_sum("payment_gateway_openapi_replay_reject_total")))
    check("sentinel_block 指标已注册", metric_sum("payment_gateway_sentinel_block_total") is not None,
          "missing payment_gateway_sentinel_block_total")

    summary()


# ======================================================================
def main_chaos():
    login()
    print(f"== verify-sentinel --chaos（批次 {SUFFIX}）：停 accounting → 熔断降级 ==")
    st, health = req("GET", "/api/health")
    assert st == 200 and all(h["ok"] for h in health), f"混沌前置：需 13/13 健康: {health}"
    merchant = ok("POST", "/api/members", {"name": f"SENTINEL混沌商户{SUFFIX}", "type": "MERCHANT"})
    user = ok("POST", "/api/members", {"name": f"SENTINEL混沌用户{SUFFIX}", "type": "USER"})
    ua = ok("POST", "/api/accounts", {"memberId": user["id"]})
    global APP_ID, SECRET
    APP_ID, SECRET = merchant["appId"], merchant["appSecret"]
    warmup_inter_domain()

    pidfile = os.path.join(ROOT, "run", "accounting.pid")
    with open(pidfile) as f:
        pid = int(f.read().strip())
    print(f"  SIGTERM accounting (pid {pid}) ...")
    os.kill(pid, signal.SIGTERM)
    deadline = time.time() + 20
    alive = True
    while time.time() < deadline:
        try:
            os.kill(pid, 0)
            time.sleep(0.5)
        except OSError:
            alive = False
            break
    if alive:
        os.kill(pid, signal.SIGKILL)
        time.sleep(1)
    os.remove(pidfile)
    time.sleep(3)  # 等 ZK 临时节点摘除 / invoker 失效

    print("  连续调用 /openapi/account.balance 观察 C0001 → B0003")
    seen = []
    for i in range(12):
        t0 = time.time()
        st, resp = openapi("account.balance", SECRET, {"accountId": ua["id"]})
        seen.append((st, resp.get("code") if isinstance(resp, dict) else None, time.time() - t0, resp))
        time.sleep(0.05)
    c0001 = [x for x in seen if x[1] == "C0001"]
    b0003 = [x for x in seen if x[1] == "B0003"]
    check("下游宕机先现 C0001（RPC 失败 503）", len(c0001) >= 1 and all(x[0] == 503 for x in c0001),
          str([(x[0], x[1]) for x in seen]))
    check("异常数熔断打开后 B0003（503 降级）", len(b0003) >= 1 and all(x[0] == 503 for x in b0003),
          str([(x[0], x[1]) for x in seen]))
    check("B0003 为受理前快速拒绝（<2s，非 RPC 超时）", all(x[2] < 2.0 for x in b0003),
          str([round(x[2], 2) for x in b0003]))
    check("全部失败规范错误体（error+code，无堆栈/下游地址）",
          all(x[0] == 503 and norm_error(x[3], x[1]) for x in seen),
          str([(x[0], x[3]) for x in seen if not (x[0] == 503 and norm_error(x[3], x[1]))][:3]))

    print("\n!! 混沌段结束：accounting 已停。必须立即全栈重启恢复：")
    print("!!   ./stop.sh && ./run.sh --docker --no-build   （过稳定性门禁后再跑其余脚本）")
    summary()


def summary():
    print(f"\n== 结果: {passed} 通过 / {failed} 失败 ==")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    APP_ID = SECRET = None
    if "--chaos" in sys.argv:
        main_chaos()
    else:
        main_default()