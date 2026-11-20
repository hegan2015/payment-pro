#!/usr/bin/env python3
# ============================================================================
# payment-pro P2 · H 泳道验证：T-5 合并支付/分阶段 + T-6 撤销 vs 退款/改价
#
# 用法：全栈在线（./run.sh --docker 或 local ./run.sh）后 python3 verify-trade-plus.py
#   [1] ride 红线：openapi trade.create/pay/query 语义与返回逐字节不变（含新增字段缺省 null）
#   [2] T-5 合并支付：Σ子订单=合并额误差0、子订单状态联动、fee 分摊守恒、幂等、承载单口径
#   [3] 部分失败语义：风控拒绝整体回滚重开 + 拆分(dissolve)个别支付 + 关闭/受理竞态零悬挂
#   [4] 与 COMBO 叠加：Σ腿=合并额=Σ子订单额 两层守恒 + 合并退款子订单/腿分摊守恒
#   [5] 分阶段：Σ期额=订单额、顺序期 CAS、各期独立幂等、paid_amount 守恒、期撤销回退可重付
#   [6] T-6 撤销 vs 退款边界：当日未清算撤销(无退款单/CX 反向凭证) vs 已清算走退款(异步收敛)；
#       已退款/非最新期/CARD 拒绝撤销；撤销幂等；大额撤销双人复核
#   [7] T-6 改价：未支付改价 CAS + out_trade_no 幂等不破坏 + fee 按新额快照；
#       已支付差额补退(下调退款单/上调补差单) + 净价守恒 + 幂等 + 大额双人复核
#   [8] 敏感操作 step-up 闸门（缺失 403）
#   [9] 资金护航：试算 D=C 且 Σbalance=0、六项对账、分片/单库两态一致、零 PROCESSING 悬挂
#
# 数据保护：只新增业务行（TPTEST 前缀 + uuid），绝不删除/重置任何既有数据；不改库状态凑绿。
# 断言口径：不放宽；失败如实记录。
# ============================================================================
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor

BASE = "http://127.0.0.1:8080"
TRADE_ACT = "http://127.0.0.1:18084/actuator"
SUFFIX = uuid.uuid4().hex[:6]
TOKEN = None
STEP = None
PASSED = 0
FAILED = 0
FAILURES = []
ADMIN_PASSWORD = os.environ.get("PP_ADMIN_PASSWORD", "admin123")
CARD = "6222020202020202"
MY_PAYMENT_IDS = set()
MY_ORDER_IDS = set()


def check(name, cond, detail=""):
    global PASSED, FAILED
    if cond:
        PASSED += 1
        print(f"  ✓ {name}")
    else:
        FAILED += 1
        FAILURES.append(name)
        print(f"  ✗ {name}  {detail}")
    return bool(cond)


def http(url, method="GET", body=None, bearer=False, step=False, timeout=180, token=None, step_token=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, method=method,
                               headers={"Content-Type": "application/json", "Accept": "*/*"})
    if bearer and (token or TOKEN):
        r.add_header("Authorization", "Bearer " + (token or TOKEN))
    if step and (step_token or STEP):
        r.add_header("X-Step-Up-Token", step_token or STEP)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
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


def login():
    global TOKEN, STEP
    st, resp = http(BASE + "/api/auth/login", "POST", {"username": "admin", "password": ADMIN_PASSWORD})
    assert st == 200, f"登录失败 {st} {resp}"
    TOKEN = resp["token"]
    st, resp = http(BASE + "/api/security/step-up", "POST", {"password": ADMIN_PASSWORD}, bearer=True)
    assert st == 200, f"二次认证失败 {st} {resp}"
    STEP = resp["token"]


def api(method, path, body=None, step=False, token=None, step_token=None):
    st, resp = http(BASE + path, method, body, bearer=True, step=step, token=token, step_token=step_token)
    if st == 401 and token is None:
        login()
        st, resp = http(BASE + path, method, body, bearer=True, step=step)
    # step-up 令牌 300s TTL：长套件中途过期属夹具问题，自动续取一次（不放宽闸门）
    if st == 403 and step and token is None and isinstance(resp, dict) and resp.get("code") == "A0403":
        login()
        st, resp = http(BASE + path, method, body, bearer=True, step=True)
    return st, resp


def ok(method, path, body=None, step=False):
    st, resp = api(method, path, body, step=step)
    assert st == 200, f"{method} {path} 期望 200，实际 {st}: {resp}"
    return resp


def bal(account_id):
    return ok("GET", f"/api/accounting/accounts/{account_id}/balance")


def poll(fn, pred, timeout=60, interval=0.5):
    deadline = time.time() + timeout
    val = fn()
    while time.time() < deadline and not pred(val):
        time.sleep(interval)
        val = fn()
    return val


def wait_payment(pid, status, timeout=60):
    MY_PAYMENT_IDS.add(pid)
    p = poll(lambda: ok("GET", f"/api/payments/{pid}"), lambda x: x["status"] == status, timeout)
    # 严格断言：poll 超时只返回末值，若不校验会把 FAILED 单当成功传给下游，
    # 表现为后续「仅已成功支付单可撤销: FAILED」这类误导性失败，掩盖真实终态。
    assert p["status"] == status, f"支付单 {pid} 期望 {status}，实际 {p['status']}: {p.get('failReason')}"
    return p


def wait_order(oid, status, timeout=60):
    return poll(lambda: ok("GET", f"/api/orders/{oid}"), lambda o: o["status"] == status, timeout)


def order(oid):
    return ok("GET", f"/api/orders/{oid}")


def payment(pid):
    return ok("GET", f"/api/payments/{pid}")


def trial():
    return ok("GET", "/api/accounting/trial")


def trial_guard(tag):
    t = trial()
    check(f"[{tag}] 试算平衡 D=C", t["totalDebit"] == t["totalCredit"], str(t))
    check(f"[{tag}] Σbalance=0", t["balanceSum"] == 0, str(t))
    return t


def recon_guard(tag):
    rep = ok("POST", "/api/recon/run", {})
    checks = rep.get("checks", [])
    bad = [c["name"] for c in checks if not c.get("pass")]
    check(f"[{tag}] 六项对账全 pass", len(checks) == 6 and not bad, str(bad) + str(rep)[:200])


def sign_params(params, secret):
    items = sorted((k, v) for k, v in params.items() if v is not None and k != "sign")
    data = "&".join(f"{k}={v}" for k, v in items)
    return hmac.new(secret.encode(), data.encode(), hashlib.sha256).hexdigest()


def openapi(api_method, app_id, secret, **fields):
    params = {"appId": app_id, "timestamp": int(time.time() * 1000)}
    params.update({k: v for k, v in fields.items() if v is not None})
    params["sign"] = sign_params(params, secret)
    return http(BASE + "/openapi/" + api_method, "POST", params)


def new_member(name, kind):
    return ok("POST", "/api/members", {"name": name, "type": kind})


def new_account(member_id):
    return ok("POST", "/api/accounts", {"memberId": member_id})


def topup(account_id, amount, biz_no):
    return ok("POST", "/api/accounting/internal/topup",
              {"accountId": account_id, "amount": amount, "bizNo": biz_no})


def new_order(merchant_id, amount, subject, out_trade_no=None, expire_minutes=None):
    body = {"merchantId": merchant_id, "subject": subject, "amount": amount}
    if out_trade_no:
        body["outTradeNo"] = out_trade_no
    if expire_minutes:
        body["expireMinutes"] = expire_minutes
    o = ok("POST", "/api/orders", body)["order"]
    MY_ORDER_IDS.add(o["id"])
    return o


def pay(order_id, account_id, request_id, method="BALANCE", card_no=None):
    body = {"orderId": order_id, "method": method, "accountId": account_id, "requestId": request_id}
    if card_no:
        body["cardNo"] = card_no
    # 风控频率闸：每 payer 10s 内 5 笔。撞闸则等窗口滑出再试，绝不放宽阈值。
    for attempt in range(5):
        st, resp = api("POST", "/api/cashier/pay", body)
        if st == 200:
            return wait_payment(resp["payment"]["id"], "SUCCESS")
        freq = st == 400 and isinstance(resp, dict) and "过于频繁" in str(resp.get("error", ""))
        if not freq or attempt == 4:
            raise AssertionError(f"POST /api/cashier/pay 期望 200，实际 {st}: {resp}")
        time.sleep(11)
    raise AssertionError("POST /api/cashier/pay 频率闸退避耗尽")


def merge_create(order_ids, request_id):
    return ok("POST", "/api/trade-plus/orders/merge", {"orderIds": order_ids, "requestId": request_id})


def merge_pay(merge_id, account_id, request_id, legs=None, step=True):
    body = {"accountId": account_id, "requestId": request_id}
    if legs:
        body["legs"] = legs
    st, resp = api("POST", f"/api/trade-plus/merges/{merge_id}/pay", body, step=step)
    if st == 200:
        MY_PAYMENT_IDS.add(resp["payment"]["id"])
    return st, resp


def stage_pay(order_id, seq, account_id, request_id, amount=None, step=True):
    body = {"stageSeq": seq, "accountId": account_id, "requestId": request_id}
    if amount is not None:
        body["amount"] = amount
    st, resp = api("POST", f"/api/trade-plus/orders/{order_id}/stage-pay", body, step=step)
    if st == 200:
        MY_PAYMENT_IDS.add(resp["payment"]["id"])
    return st, resp


def today_start():
    import datetime
    tz = datetime.timezone(datetime.timedelta(hours=8))
    now = datetime.datetime.now(tz)
    return int(now.replace(hour=0, minute=0, second=0, microsecond=0).timestamp() * 1000)


def main():
    print(f"== payment-pro T-5/T-6 合并支付/分阶段 + 撤销/退款/改价验证（批次 TPTEST{SUFFIX}）==")
    login()
    health = poll(lambda: ok("GET", "/api/health"), lambda h: all(x["ok"] for x in h), timeout=90, interval=2)
    if not check("13 个进程在线", len(health) == 13 and all(x["ok"] for x in health), str(health)[:200]):
        print("!! 全栈不健康，停止（不做任何资金操作）")
        return
    base_trial = trial()
    if not (base_trial["totalDebit"] == base_trial["totalCredit"] and base_trial["balanceSum"] == 0):
        print(f"!! 前置试算不平，停止：{base_trial}")
        return

    # ---------- 0. 夹具 ----------
    print("[0] 夹具：TPTEST 商户/买家/账户/充值 + 第二审批人（只新增）")
    merchant = new_member(f"TPTEST-H商{SUFFIX}", "MERCHANT")
    ma = new_account(merchant["id"])
    m2 = new_member(f"TPTEST-H清{SUFFIX}", "MERCHANT")
    m2a = new_account(m2["id"])
    buyers = [new_member(f"TPTEST-H买{c}{SUFFIX}", "USER") for c in "ABCD"]
    accounts = [new_account(b["id"]) for b in buyers]
    a1, a2, a3, a4 = accounts
    for acct in accounts:
        topup(acct["id"], 30000000, f"TPTEST-topup-{SUFFIX}-{acct['id']}")
    check("夹具充值到账（3000 万分）", bal(a1["id"]) == 30000000, str(bal(a1["id"])))
    bob_user, bob_pw = f"tphbob{SUFFIX}", "TpHBob#2026x"
    st, resp = api("POST", "/api/console/users", {"username": bob_user, "password": bob_pw, "roles": ["OPERATOR"]})
    assert st == 200, f"创建第二审批人失败 {st} {resp}"
    st, resp = http(BASE + "/api/auth/login", "POST", {"username": bob_user, "password": bob_pw})
    assert st == 200 and resp.get("token"), f"第二审批人登录失败 {st} {resp}"
    bob_token = resp["token"]
    st, resp = http(BASE + "/api/security/step-up", "POST", {"password": bob_pw}, bearer=True, token=bob_token)
    assert st == 200, f"第二审批人二次认证失败 {st} {resp}"
    bob_step = resp["token"]

    def approve(review_id):
        st, resp = http(BASE + f"/api/reviews/{review_id}/approve", "POST", {},
                        bearer=True, step=True, token=bob_token, step_token=bob_step)
        assert st == 200 and resp.get("status") == "APPROVED", f"第二人审批失败 {st} {resp}"

    # ---------- 1. ride 红线 ----------
    print("[1] ride 兼容红线：trade.create / trade.pay / trade.query 逐字节不变")
    app_id, secret = merchant["appId"], merchant["appSecret"]
    out_no = f"ride-plus-{SUFFIX}"
    st, resp = openapi("trade.create", app_id, secret, subject="ride 行程", amount=3000, outTradeNo=out_no)
    check("trade.create 200 返回 id/amount/merchantId", st == 200 and resp.get("id")
          and resp["amount"] == 3000 and resp["merchantId"] == merchant["id"], str(resp))
    ride_order = resp
    MY_ORDER_IDS.add(ride_order["id"])
    check("trade.create 初始 CREATED/INSTANT/refundedAmount=0",
          ride_order["status"] == "CREATED" and ride_order.get("productType") == "INSTANT"
          and ride_order["refundedAmount"] == 0, str(ride_order))
    check("H 新增字段缺省 null/0（ride 口径不变）",
          ride_order.get("mergeId") is None and ride_order.get("stageCount") is None
          and ride_order.get("originalAmount") is None and (ride_order.get("priceChangeCount") or 0) == 0,
          str(ride_order))
    st, again = openapi("trade.create", app_id, secret, subject="ride 行程", amount=9999, outTradeNo=out_no)
    check("merchant+outTradeNo 唯一幂等（不同金额仍返回原单）",
          st == 200 and again["id"] == ride_order["id"] and again["amount"] == 3000, str(again))
    u0, m0, fee0 = bal(a1["id"]), bal(ma["id"]), bal("SYS_FEE")
    st, resp = openapi("trade.pay", app_id, secret, orderId=ride_order["id"], method="BALANCE",
                       accountId=a1["id"], outTradeNo=f"ride-plus-pay-{SUFFIX}")
    check("trade.pay 同步受理 PROCESSING/SUCCESS（无 WAITING）",
          st == 200 and resp.get("payment", {}).get("status") in ("PROCESSING", "SUCCESS"), str(resp))
    ride_pay = resp["payment"]
    check("受理快照手续费 60bps=18 分", ride_pay["fee"] == 18, str(ride_pay["fee"]))
    paid = wait_payment(ride_pay["id"], "SUCCESS")
    ro = wait_order(ride_order["id"], "PAID")
    check("BALANCE 收敛 SUCCESS + 订单 PAID", paid["status"] == "SUCCESS" and ro["paymentId"] == ride_pay["id"], str(ro))
    check("付款方 −3000 / 商户净入 +2982 / SYS_FEE +18",
          bal(a1["id"]) == u0 - 3000 and bal(ma["id"]) == m0 + 2982 and bal("SYS_FEE") == fee0 + 18,
          f"{bal(a1['id'])}/{bal(ma['id'])}/{bal('SYS_FEE')}")
    st, q = openapi("trade.query", app_id, secret, orderId=ride_order["id"])
    check("trade.query 返回 order+payment（fee 回查口径不变）",
          st == 200 and q.get("order", {}).get("id") == ride_order["id"]
          and q.get("payment", {}).get("id") == ride_pay["id"] and q["payment"]["fee"] == 18, str(q)[:300])
    check("trade.query 订单口径不变（PAID/amount/paymentId/refundedAmount）",
          q["order"]["status"] == "PAID" and q["order"]["amount"] == 3000
          and q["order"]["paymentId"] == ride_pay["id"] and q["order"]["refundedAmount"] == 0, str(q["order"]))
    tr = ok("GET", f"/api/accounting/transfers/{ride_pay['id']}")
    debit = sum(l["amount"] for l in tr["lines"] if l["dir"] == "D")
    check("INSTANT 分录模板不变（D 买家 3000 / refId=订单）", debit == 3000 and tr["refId"] == ride_order["id"], str(tr))
    st, resp = openapi("trade.pay", app_id, secret, orderId=ride_order["id"], method="BALANCE",
                       accountId=a1["id"], outTradeNo=f"ride-plus-pay-{SUFFIX}")
    check("trade.pay requestId 幂等（同一支付单不重复扣款）",
          st == 200 and resp["payment"]["id"] == ride_pay["id"] and bal(a1["id"]) == u0 - 3000, str(resp))

    # ---------- 2. T-5 合并支付 ----------
    print("[2] T-5 合并支付：Σ子订单=合并额误差0 + 子订单联动 + fee 分摊守恒 + 幂等")
    subs = [new_order(merchant["id"], amt, f"TPTEST合并子{i}-{SUFFIX}") for i, amt in enumerate((1000, 2000, 3000))]
    mg = merge_create([o["id"] for o in subs], f"TPTEST-mc-{SUFFIX}")
    check("合并单 total=Σ子订单额（6000，误差0）", mg["totalAmount"] == 6000
          and sum(r["amount"] for r in mg["rels"]) == 6000 and mg["status"] == "CREATED", str(mg))
    mg_id = mg["id"]
    st, again = api("POST", "/api/trade-plus/orders/merge",
                    {"orderIds": [o["id"] for o in subs], "requestId": f"TPTEST-mc-{SUFFIX}"})
    check("合并创建 requestId 幂等（同一合并单）", st == 200 and again["id"] == mg_id, str(again))
    check("子订单 merge_id 占位联动", all(order(o["id"]).get("mergeId") == mg_id for o in subs),
          str([order(o["id"]).get("mergeId") for o in subs]))
    st, resp = api("POST", "/api/cashier/pay", {"orderId": subs[0]["id"], "method": "BALANCE",
                                               "accountId": a1["id"], "requestId": f"TPTEST-solo-{SUFFIX}"})
    check("合并内子订单禁止个别支付（守恒守卫）", st == 409, f"{st} {resp}")
    st, resp = api("POST", f"/api/orders/{subs[0]['id']}/close")
    check("合并内子订单禁止单独关闭", st == 409, f"{st} {resp}")
    st, resp = api("POST", f"/api/trade-plus/orders/{subs[0]['id']}/price",
                   {"newAmount": 999, "reason": "x", "requestId": f"TPTEST-mp-{SUFFIX}"}, step=True)
    check("合并内子订单禁止改价（快照守恒）", st == 409, f"{st} {resp}")
    st, resp = merge_pay(mg_id, a1["id"], f"TPTEST-mpay-{SUFFIX}", step=False)
    check("合并支付缺 step-up → 403", st == 403, f"{st} {resp}")
    u1, m1, f1 = bal(a1["id"]), bal(ma["id"]), bal("SYS_FEE")
    st, resp = merge_pay(mg_id, a1["id"], f"TPTEST-mpay-{SUFFIX}")
    check("合并支付受理 200", st == 200, f"{st} {resp}")
    mp = wait_payment(resp["payment"]["id"], "SUCCESS")
    check("合并支付单 payMode=MERGE/amount=6000/fee=36（总额快照）",
          mp["payMode"] == "MERGE" and mp["amount"] == 6000 and mp["fee"] == 36 and mp["mergeId"] == mg_id, str(mp))
    mg_after = ok("GET", f"/api/trade-plus/merges/{mg_id}")
    check("合并单 PAID/paidCount=3", mg_after["status"] == "PAID" and mg_after["paidCount"] == 3, str(mg_after))
    check("子订单全部 PAID 且指向合并支付单（状态联动）",
          all(order(o["id"])["status"] == "PAID" and order(o["id"])["paymentId"] == mp["id"] for o in subs),
          str([(order(o["id"])["status"], order(o["id"])["paymentId"]) for o in subs]))
    check("Σrel.fee=支付单 fee（分摊守恒）", sum(r["fee"] for r in mg_after["rels"]) == mp["fee"],
          str(mg_after["rels"]))
    carrier = order(mp["orderId"])
    check("承载单 bizType=MERGE/PAID/amount=合并额（recon/settle 口径锚点）",
          carrier["bizType"] == "MERGE" and carrier["status"] == "PAID" and carrier["amount"] == 6000, str(carrier))
    check("付款方 −6000 / 商户 +5964 / SYS_FEE +36",
          bal(a1["id"]) == u1 - 6000 and bal(ma["id"]) == m1 + 5964 and bal("SYS_FEE") == f1 + 36,
          f"{bal(a1['id'])}/{bal(ma['id'])}/{bal('SYS_FEE')}")
    trm = ok("GET", f"/api/accounting/transfers/{mp['id']}")
    check("合并账务凭证 1:1（bizNo=支付单，D=6000）",
          sum(l["amount"] for l in trm["lines"] if l["dir"] == "D") == 6000 and trm["refId"] == carrier["id"], str(trm))
    st, resp = merge_pay(mg_id, a1["id"], f"TPTEST-mpay-{SUFFIX}")
    check("合并支付 requestId 幂等（同一支付单不重复扣款）",
          st == 200 and resp["payment"]["id"] == mp["id"] and resp.get("repeated") is True
          and bal(a1["id"]) == u1 - 6000, str(resp))
    st, resp = merge_pay(mg_id, a1["id"], f"TPTEST-mpay2-{SUFFIX}")
    check("已 PAID 合并单新 requestId 返回原支付单（不重复动账）",
          st == 200 and resp["payment"]["id"] == mp["id"] and bal(a1["id"]) == u1 - 6000, str(resp))
    trial_guard("合并支付后")

    # ---------- 3. 部分失败语义 ----------
    print("[3] 部分失败：风控拒绝整体回滚重开 + 拆分个别支付 + 关闭/受理竞态零悬挂")
    big_subs = [new_order(merchant["id"], 6000000, f"TPTEST大额子{i}-{SUFFIX}") for i in range(2)]
    mg2 = merge_create([o["id"] for o in big_subs], f"TPTEST-mc2-{SUFFIX}")
    u2 = bal(a2["id"])
    st, resp = merge_pay(mg2["id"], a2["id"], f"TPTEST-mpay-big-{SUFFIX}")
    check("超单笔限额合并受理 → 业务失败留 FAILED 痕", st in (400, 500) and resp.get("error"), f"{st} {resp}")
    failed_p = poll(lambda: next((p for p in ok("GET", "/api/payments")
                                  if p.get("requestId") == f"TPTEST-mpay-big-{SUFFIX}"), None),
                    lambda p: p is not None and p["status"] == "FAILED", timeout=30)
    check("失败支付单 FAILED（TCC 未动账）", failed_p is not None and failed_p["status"] == "FAILED", str(failed_p))
    if failed_p:
        MY_PAYMENT_IDS.add(failed_p["id"])
    mg2_after = ok("GET", f"/api/trade-plus/merges/{mg2['id']}")
    check("整体回滚：合并单重开 CREATED（可重付/可拆分）", mg2_after["status"] == "CREATED", str(mg2_after))
    check("子订单释放回 CREATED（未动账）",
          all(order(o["id"])["status"] == "CREATED" for o in big_subs) and bal(a2["id"]) == u2,
          str([order(o["id"])["status"] for o in big_subs]))
    ok("POST", f"/api/trade-plus/merges/{mg2['id']}/dissolve")
    check("拆分语义：dissolve 后子订单解绑可个别支付",
          all(order(o["id"]).get("mergeId") is None for o in big_subs),
          str([order(o["id"]).get("mergeId") for o in big_subs]))
    for i, o in enumerate(big_subs):
        p = pay(o["id"], a2["id"], f"TPTEST-split-{i}-{SUFFIX}")
        check(f"拆分后子订单{i}个别支付 SUCCESS（600 万分）", p["status"] == "SUCCESS" and p["amount"] == 6000000, str(p))
    # 关闭/受理竞态：子订单 1 分钟到期，payMerge 与超时关闭作业并发 → 合法终态三选一，零悬挂
    race_merges = []
    for i in range(3):
        exp_sub = new_order(merchant["id"], 500, f"TPTEST竞态到期{i}-{SUFFIX}", expire_minutes=1)
        fresh_sub = new_order(merchant["id"], 700, f"TPTEST竞态新鲜{i}-{SUFFIX}")
        m = merge_create([exp_sub["id"], fresh_sub["id"]], f"TPTEST-mcr-{i}-{SUFFIX}")
        race_merges.append((m, exp_sub, fresh_sub))
    print("  …等待竞态子订单到期（62s）")
    time.sleep(62)
    race_outcomes = []
    for i, (m, exp_sub, fresh_sub) in enumerate(race_merges):
        ub = bal(a3["id"])
        result = {}

        def do_pay():
            result["pay"] = merge_pay(m["id"], a3["id"], f"TPTEST-mpr-{i}-{SUFFIX}")

        def do_expire():
            result["expire"] = http(TRADE_ACT + "/ppjob/tradeOrderExpire", "POST",
                                    {"shardIndex": 0, "shardTotal": 1, "param": ""})

        with ThreadPoolExecutor(max_workers=2) as pool:
            f1 = pool.submit(do_pay)
            f2 = pool.submit(do_expire)
            f1.result()
            f2.result()
        st_pay, resp_pay = result["pay"]
        if st_pay == 200:
            poll(lambda: payment(resp_pay["payment"]["id"]),
                 lambda p: p["status"] in ("SUCCESS", "FAILED", "CLOSED", "CANCELLED"), timeout=30)
        time.sleep(1)
        m_now = ok("GET", f"/api/trade-plus/merges/{m['id']}")
        exp_now, fresh_now = order(exp_sub["id"]), order(fresh_sub["id"])
        pays = [p for p in ok("GET", "/api/payments") if p.get("mergeId") == m["id"]]
        succ = [p for p in pays if p["status"] == "SUCCESS"]
        for p in pays:
            MY_PAYMENT_IDS.add(p["id"])
        if st_pay == 200 and succ:
            ok_outcome = (m_now["status"] == "PAID" and exp_now["status"] == "PAID"
                          and fresh_now["status"] == "PAID" and bal(a3["id"]) == ub - 1200)
            race_outcomes.append("PAY_WIN")
        else:
            # 受理前拒绝（子订单已关）或 TCC 整体回滚：合并单 CREATED、无 SUCCESS、分文未动
            ok_outcome = (m_now["status"] == "CREATED" and not succ and bal(a3["id"]) == ub
                          and fresh_now["status"] == "CREATED" and fresh_now.get("paymentId") is None
                          and exp_now["status"] in ("CLOSED", "CREATED"))
            race_outcomes.append("REJECT_OR_ROLLBACK")
        check(f"竞态轮{i}合法终态（支付胜出全 PAID 恰扣一次 / 拒绝或整体回滚分文未动，零悬挂）",
              ok_outcome, f"pay={st_pay} merge={m_now['status']} exp={exp_now['status']} "
                          f"fresh={fresh_now['status']} succ={len(succ)} balΔ={bal(a3['id']) - ub}")
    check("竞态三轮均收敛（无 PROCESSING 悬挂支付单）",
          all(p["status"] in ("SUCCESS", "FAILED", "CLOSED", "CANCELLED")
              for p in ok("GET", "/api/payments") if p.get("mergeId") in {m["id"] for m, _, _ in race_merges}),
          race_outcomes)
    trial_guard("部分失败/竞态后")

    # ---------- 4. 与 COMBO 叠加 ----------
    print("[4] T-5 × COMBO 叠加：Σ腿=合并额=Σ子订单额 两层守恒 + 合并退款分摊")
    csubs = [new_order(merchant["id"], 1000, f"TPTEST组合子0-{SUFFIX}"),
             new_order(merchant["id"], 3000, f"TPTEST组合子1-{SUFFIX}")]
    mg3 = merge_create([o["id"] for o in csubs], f"TPTEST-mc3-{SUFFIX}")
    st, resp = merge_pay(mg3["id"], a1["id"], f"TPTEST-mcombo-bad-{SUFFIX}",
                         legs=[{"method": "BALANCE", "accountId": a1["id"], "amount": 2500},
                               {"method": "CARD", "cardNo": CARD, "amount": 1400}])
    check("叠加不守恒（Σ腿 3900≠4000）受理前拒绝", st == 400, f"{st} {resp}")
    u3, m3, f3, cash3 = bal(a1["id"]), bal(ma["id"]), bal("SYS_FEE"), bal("SYS_CASH")
    st, resp = merge_pay(mg3["id"], a1["id"], f"TPTEST-mcombo-{SUFFIX}",
                         legs=[{"method": "BALANCE", "accountId": a1["id"], "amount": 2500},
                               {"method": "CARD", "cardNo": CARD, "amount": 1500}])
    check("合并+组合叠加受理 200", st == 200, f"{st} {resp}")
    cp = wait_payment(resp["payment"]["id"], "SUCCESS")
    check("payMode=MERGE_COMBO/amount=4000/fee=24", cp["payMode"] == "MERGE_COMBO"
          and cp["amount"] == 4000 and cp["fee"] == 24, str(cp))
    legs = cp.get("legs") or []
    check("两腿独立落账 SUCCESS（Σ腿=4000=合并额，Σ腿fee=24）",
          len(legs) == 2 and all(l["status"] == "SUCCESS" for l in legs)
          and sum(l["amount"] for l in legs) == 4000 and sum(l["fee"] for l in legs) == 24, str(legs))
    mg3_after = ok("GET", f"/api/trade-plus/merges/{mg3['id']}")
    check("叠加守恒：Σ子订单=Σ腿=合并额（误差0）+ Σrel.fee=总fee",
          sum(r["amount"] for r in mg3_after["rels"]) == sum(l["amount"] for l in legs) == 4000
          and sum(r["fee"] for r in mg3_after["rels"]) == 24 and mg3_after["status"] == "PAID", str(mg3_after))
    check("叠加资金流：BALANCE 腿 −2500 / CARD 腿 SYS_CASH −1500 / 商户 +3976 / SYS_FEE +24",
          bal(a1["id"]) == u3 - 2500 and bal("SYS_CASH") == cash3 - 1500
          and bal(ma["id"]) == m3 + 3976 and bal("SYS_FEE") == f3 + 24,
          f"{bal(a1['id'])}/{bal('SYS_CASH')}/{bal(ma['id'])}/{bal('SYS_FEE')}")
    # 合并组合退款 1000：腿分摊 + 子订单分摊双守恒
    st, rr = api("POST", f"/api/payments/{cp['id']}/refund",
                 {"amount": 1000, "requestId": f"TPTEST-mcrefund-{SUFFIX}"}, step=True)
    check("合并组合退款受理 200", st == 200, f"{st} {rr}")
    refund_id = rr["refundId"]
    poll(lambda: ok("GET", f"/api/refunds/{refund_id}"), lambda r: r["status"] == "SUCCESS")
    cp2 = payment(cp["id"])
    check("退款收敛：支付单 refundedAmount=1000（P-4 异步不变）", cp2["refundedAmount"] == 1000, str(cp2))
    mg3_r = ok("GET", f"/api/trade-plus/merges/{mg3['id']}")
    check("子订单退款分摊守恒（Σ子订单已退=1000，各不超退）",
          sum(r["refundedAmount"] for r in mg3_r["rels"]) == 1000
          and all(r["refundedAmount"] <= r["amount"] for r in mg3_r["rels"]), str(mg3_r["rels"]))
    check("子订单状态联动 PARTIAL_REFUNDED",
          all(order(o["id"])["status"] == "PARTIAL_REFUNDED" for o in csubs),
          str([order(o["id"])["status"] for o in csubs]))
    legs2 = payment(cp["id"]).get("legs") or []
    check("腿级已退守恒（Σ腿已退=1000）", sum(l["refundedAmount"] for l in legs2) == 1000, str(legs2))
    trial_guard("合并组合退款后")

    # ---------- 5. 分阶段 ----------
    print("[5] T-5 分阶段：Σ期额=订单额 + 顺序期 CAS + 各期幂等 + paid_amount 守恒 + 期撤销")
    so = new_order(merchant["id"], 10000, f"TPTEST分期主{SUFFIX}")
    st, resp = api("POST", f"/api/trade-plus/orders/{so['id']}/stage-plan",
                   {"amounts": [3000, 6000], "requestId": f"TPTEST-sp-bad-{SUFFIX}"})
    check("Σ期额≠订单额 → 拒绝（守恒强校验）", st == 400, f"{st} {resp}")
    plan = ok("POST", f"/api/trade-plus/orders/{so['id']}/stage-plan",
              {"amounts": [3000, 7000], "requestId": f"TPTEST-sp-{SUFFIX}"})
    check("阶段计划落库（stageCount=2/Σ=10000）", plan["stageCount"] == 2
          and plan["stageAmounts"] == [3000, 7000] and sum(plan["stageAmounts"]) == plan["amount"], str(plan))
    st, resp = api("POST", f"/api/trade-plus/orders/{so['id']}/stage-plan",
                   {"amounts": [3000, 7000], "requestId": f"TPTEST-sp-{SUFFIX}"})
    check("阶段计划 requestId 幂等", st == 200 and resp["stageCount"] == 2, f"{st} {resp}")
    st, resp = api("POST", "/api/cashier/pay", {"orderId": so["id"], "method": "BALANCE",
                                               "accountId": a1["id"], "requestId": f"TPTEST-solo-stage-{SUFFIX}"})
    check("分阶段订单禁止整单普通支付", st == 409, f"{st} {resp}")
    st, resp = stage_pay(so["id"], 2, a1["id"], f"TPTEST-s2-early-{SUFFIX}")
    check("跳期支付拒绝（顺序 CAS）", st == 409, f"{st} {resp}")
    st, resp = stage_pay(so["id"], 1, a1["id"], f"TPTEST-s1-badamt-{SUFFIX}", amount=2500)
    check("期额≠计划额拒绝（守恒）", st == 400, f"{st} {resp}")
    st, resp = stage_pay(so["id"], 1, a1["id"], f"TPTEST-s1-{SUFFIX}", step=False)
    check("阶段支付缺 step-up → 403", st == 403, f"{st} {resp}")
    u4, m4, f4 = bal(a1["id"]), bal(ma["id"]), bal("SYS_FEE")
    st, resp = stage_pay(so["id"], 1, a1["id"], f"TPTEST-s1-{SUFFIX}")
    check("第 1 期（定金 3000）受理 200", st == 200, f"{st} {resp}")
    sp1 = wait_payment(resp["payment"]["id"], "SUCCESS")
    check("STAGE 单 stageSeq=1/fee=18（本期额快照）", sp1["payMode"] == "STAGE"
          and sp1["stageSeq"] == 1 and sp1["fee"] == 18, str(sp1))
    so1 = order(so["id"])
    check("首期后订单 PAID/paidStage=1/paidAmount=3000（recon 口径）",
          so1["status"] == "PAID" and so1["paidStage"] == 1 and so1["paidAmount"] == 3000, str(so1))
    st, resp = stage_pay(so["id"], 1, a1["id"], f"TPTEST-s1-{SUFFIX}")
    check("第 1 期 requestId 幂等（同一支付单）", st == 200 and resp["payment"]["id"] == sp1["id"]
          and resp.get("repeated") is True, str(resp))
    # 并发同期不同 requestId：恰一 SUCCESS，其余 FAILED/拒绝（期占位 UNIQUE 守卫）
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(lambda i: stage_pay(so["id"], 2, a1["id"], f"TPTEST-s2-race-{i}-{SUFFIX}"), range(4)))
    succ2 = [r for st, r in results if st == 200]
    for st, r in results:
        if st == 200:
            MY_PAYMENT_IDS.add(r["payment"]["id"])
    check("并发同期恰一受理成功（其余让位）", len(succ2) == 1, str([(st, str(r)[:80]) for st, r in results]))
    sp2 = wait_payment(succ2[0]["payment"]["id"], "SUCCESS")
    so2 = order(so["id"])
    check("全款后 paidStage=2/paidAmount=10000=订单额（总额守恒误差0）",
          so2["paidStage"] == 2 and so2["paidAmount"] == 10000 and so2["amount"] == 10000
          and so2["status"] == "PAID", str(so2))
    check("Σ期支付额=订单额（3000+7000）", sp1["amount"] + sp2["amount"] == 10000, f"{sp1['amount']}+{sp2['amount']}")
    check("分期资金流：买家 −10000 / 商户 +9940 / SYS_FEE +60（18+42）",
          bal(a1["id"]) == u4 - 10000 and bal(ma["id"]) == m4 + 9940 and bal("SYS_FEE") == f4 + 60,
          f"{bal(a1['id'])}/{bal(ma['id'])}/{bal('SYS_FEE')}")
    # 中途退款 → 后续期拒付（保守守卫，资金正确）
    so_ref = new_order(merchant["id"], 5000, f"TPTEST分期退{SUFFIX}")
    ok("POST", f"/api/trade-plus/orders/{so_ref['id']}/stage-plan",
       {"amounts": [2000, 3000], "requestId": f"TPTEST-spr-{SUFFIX}"})
    st, resp = stage_pay(so_ref["id"], 1, a2["id"], f"TPTEST-spr1-{SUFFIX}")
    rp1 = wait_payment(resp["payment"]["id"], "SUCCESS")
    st, rr = api("POST", f"/api/payments/{rp1['id']}/refund",
                 {"amount": 2000, "requestId": f"TPTEST-spr-refund-{SUFFIX}"}, step=True)
    check("首期退款受理（订单 PAID 可退）", st == 200, f"{st} {rr}")
    poll(lambda: ok("GET", f"/api/refunds/{rr['refundId']}"), lambda r: r["status"] == "SUCCESS")
    st, resp = stage_pay(so_ref["id"], 2, a2["id"], f"TPTEST-spr2-{SUFFIX}")
    check("已退款订单后续期拒付（保守守卫）", st == 409, f"{st} {resp}")
    # 期撤销：仅最新已付期可撤，回退一期可重付
    so_c = new_order(merchant["id"], 8000, f"TPTEST分期撤{SUFFIX}")
    ok("POST", f"/api/trade-plus/orders/{so_c['id']}/stage-plan",
       {"amounts": [3000, 5000], "requestId": f"TPTEST-spc-{SUFFIX}"})
    st, r1 = stage_pay(so_c["id"], 1, a3["id"], f"TPTEST-spc1-{SUFFIX}")
    sc1 = wait_payment(r1["payment"]["id"], "SUCCESS")
    st, r2 = stage_pay(so_c["id"], 2, a3["id"], f"TPTEST-spc2-{SUFFIX}")
    sc2 = wait_payment(r2["payment"]["id"], "SUCCESS")
    uc = bal(a3["id"])
    st, resp = api("POST", f"/api/trade-plus/payments/{sc1['id']}/cancel",
                   {"requestId": f"TPTEST-spc-cancel1-{SUFFIX}"}, step=True)
    check("非最新期撤销拒绝（仅最新已付期）", st == 409, f"{st} {resp}")
    st, resp = api("POST", f"/api/trade-plus/payments/{sc2['id']}/cancel",
                   {"requestId": f"TPTEST-spc-cancel2-{SUFFIX}"}, step=True)
    check("最新期当日撤销 200（无退款单）", st == 200 and resp["status"] == "CANCELLED"
          and resp["refundedAmount"] == 0, f"{st} {resp}")
    so_c1 = order(so_c["id"])
    check("期撤销回退：paidStage=1/paidAmount=3000（守恒回退）",
          so_c1["paidStage"] == 1 and so_c1["paidAmount"] == 3000 and so_c1["status"] == "PAID", str(so_c1))
    check("期撤销资金原路退回（+5000）", bal(a3["id"]) == uc + 5000, str(bal(a3["id"])))
    st, resp = stage_pay(so_c["id"], 2, a3["id"], f"TPTEST-spc2-repay-{SUFFIX}")
    check("撤销后本期可重付（新 requestId）", st == 200, f"{st} {resp}")
    sc2b = wait_payment(resp["payment"]["id"], "SUCCESS")
    so_c2 = order(so_c["id"])
    check("重付后全款守恒（paidStage=2/paidAmount=8000）",
          so_c2["paidStage"] == 2 and so_c2["paidAmount"] == 8000, str(so_c2))
    st, resp = api("POST", f"/api/trade-plus/payments/{sc2['id']}/cancel",
                   {"requestId": f"TPTEST-spc-cancel2-{SUFFIX}"}, step=True)
    check("期撤销幂等（重复返回 CANCELLED 不重复回退）",
          st == 200 and resp["status"] == "CANCELLED" and order(so_c["id"])["paidStage"] == 2, f"{st} {resp}")
    trial_guard("分阶段后")

    # ---------- 6. T-6 撤销 vs 退款边界 ----------
    print("[6] T-6 撤销(当日未清算/无退款单) vs 退款(已清算/退款单异步收敛)")
    co = new_order(merchant["id"], 2000, f"TPTEST撤销单{SUFFIX}")
    # 撞风控改测试主体：a1 在前面各段已密集支付，复用会撞每 payer 10s/5 笔频率闸。
    # 本段用独立新付款账户，绝不放宽阈值。
    cx_buyer = new_member(f"TPTEST-H撤{SUFFIX}", "USER")
    cx_acct = new_account(cx_buyer["id"])
    topup(cx_acct["id"], 30000000, f"TPTEST-topup-cx-{SUFFIX}")
    cp0 = pay(co["id"], cx_acct["id"], f"TPTEST-cpay-{SUFFIX}")
    u5, m5, f5 = bal(cx_acct["id"]), bal(ma["id"]), bal("SYS_FEE")
    st, resp = api("POST", f"/api/trade-plus/payments/{cp0['id']}/cancel",
                   {"requestId": f"TPTEST-cancel-{SUFFIX}"}, step=False)
    check("撤销缺 step-up → 403", st == 403, f"{st} {resp}")
    st, resp = api("POST", f"/api/trade-plus/payments/{cp0['id']}/cancel",
                   {"requestId": f"TPTEST-cancel-{SUFFIX}"}, step=True)
    check("当日未清算撤销 200 → CANCELLED/closeType=CANCEL", st == 200
          and resp["status"] == "CANCELLED" and resp["closeType"] == "CANCEL", f"{st} {resp}")
    check("撤销不留退款单（refundedAmount=0）且资金原路退回",
          resp["refundedAmount"] == 0 and bal(cx_acct["id"]) == u5 + 2000
          and bal(ma["id"]) == m5 - 1988 and bal("SYS_FEE") == f5 - 12,
          f"{bal(cx_acct['id'])}/{bal(ma['id'])}/{bal('SYS_FEE')}")
    cx = ok("GET", f"/api/accounting/transfers/CX:{cp0['id']}")
    cx_debit = sum(l["amount"] for l in cx.get("lines", []) if l["dir"] == "D")
    check("撤销反向凭证 CX:paymentId（D=2000 全额反向，区别于退款单凭证）", cx_debit == 2000, str(cx)[:200])
    st, resp = api("POST", f"/api/trade-plus/payments/{cp0['id']}/cancel",
                   {"requestId": f"TPTEST-cancel-{SUFFIX}"}, step=True)
    check("撤销幂等（重复返回 CANCELLED 不重复过账）", st == 200 and resp["status"] == "CANCELLED"
          and bal(cx_acct["id"]) == u5 + 2000, f"{st} {resp}")
    # 已退款 → 不可撤销（走退款语义）
    co2 = new_order(merchant["id"], 1000, f"TPTEST退后撤{SUFFIX}")
    cp1 = pay(co2["id"], cx_acct["id"], f"TPTEST-cpay2-{SUFFIX}")
    st, rr = api("POST", f"/api/payments/{cp1['id']}/refund",
                 {"amount": 300, "requestId": f"TPTEST-partref-{SUFFIX}"}, step=True)
    poll(lambda: ok("GET", f"/api/refunds/{rr['refundId']}"), lambda r: r["status"] == "SUCCESS")
    st, resp = api("POST", f"/api/trade-plus/payments/{cp1['id']}/cancel",
                   {"requestId": f"TPTEST-cancel-refunded-{SUFFIX}"}, step=True)
    check("已退款交易撤销拒绝（撤销须全额未退）", st == 409, f"{st} {resp}")
    check("退款语义：部分退走退款单 + 订单 PARTIAL_REFUNDED（可跨日/部分）",
          order(co2["id"])["status"] == "PARTIAL_REFUNDED" and payment(cp1["id"])["refundedAmount"] == 300,
          str(order(co2["id"])))
    # CARD → 渠道当日撤销未就绪，明确走退款
    co3 = new_order(merchant["id"], 1500, f"TPTEST卡撤{SUFFIX}")
    cp2 = pay(co3["id"], cx_acct["id"], f"TPTEST-cardpay-{SUFFIX}", method="CARD", card_no=CARD)
    st, resp = api("POST", f"/api/trade-plus/payments/{cp2['id']}/cancel",
                   {"requestId": f"TPTEST-cancel-card-{SUFFIX}"}, step=True)
    check("CARD 撤销拒绝（渠道当日撤销未就绪 → 走退款）", st in (400, 409), f"{st} {resp}")
    # 大额撤销双人复核
    co4 = new_order(merchant["id"], 1500000, f"TPTEST大额撤{SUFFIX}")
    cp3 = pay(co4["id"], a2["id"], f"TPTEST-bigpay-{SUFFIX}")
    st, resp = api("POST", f"/api/trade-plus/payments/{cp3['id']}/cancel",
                   {"requestId": f"TPTEST-cancel-big-{SUFFIX}"}, step=True)
    check("大额撤销未复核 → A0428（绝不动账）", st == 400 and resp.get("code") == "A0428"
          and resp.get("reviewId"), f"{st} {resp}")
    ub = bal(a2["id"])
    approve(resp["reviewId"])
    st, resp = api("POST", f"/api/trade-plus/payments/{cp3['id']}/cancel",
                   {"requestId": f"TPTEST-cancel-big-{SUFFIX}"}, step=True)
    check("复核通过后撤销成功（第二人审批）", st == 200 and resp["status"] == "CANCELLED"
          and bal(a2["id"]) == ub + 1500000, f"{st} {resp}")
    trial_guard("撤销/退款边界后")

    # ---------- 7. T-6 改价 ----------
    print("[7] T-6 改价：未支付 CAS + 幂等不破坏 + fee 新额快照 + 已支付差额补退")
    # 撞风控改测试主体：a1 前面各段已密集支付，本段起换独立新付款账户（不放宽阈值）
    pc_buyer = new_member(f"TPTEST-H改{SUFFIX}", "USER")
    pc_acct = new_account(pc_buyer["id"])
    topup(pc_acct["id"], 30000000, f"TPTEST-topup-pc-{SUFFIX}")
    po = new_order(merchant["id"], 1000, f"TPTEST改价单{SUFFIX}", out_trade_no=f"TPTEST-PC-{SUFFIX}")
    st, resp = api("POST", f"/api/trade-plus/orders/{po['id']}/price",
                   {"newAmount": 800, "reason": "测试改价", "requestId": f"TPTEST-price-{SUFFIX}"}, step=False)
    check("改价缺 step-up → 403", st == 403, f"{st} {resp}")
    changed = ok("POST", f"/api/trade-plus/orders/{po['id']}/price",
                 {"newAmount": 800, "reason": "测试改价", "requestId": f"TPTEST-price-{SUFFIX}"}, step=True)
    check("未支付改价 1000→800（originalAmount 留痕/计数1）",
          changed["amount"] == 800 and changed["originalAmount"] == 1000 and changed["priceChangeCount"] == 1,
          str(changed))
    st, resp = api("POST", f"/api/trade-plus/orders/{po['id']}/price",
                   {"newAmount": 800, "reason": "测试改价", "requestId": f"TPTEST-price-{SUFFIX}"}, step=True)
    check("改价 requestId 幂等（计数不累加）", st == 200 and resp["priceChangeCount"] == 1, f"{st} {resp}")
    st, again = openapi("trade.create", app_id, secret, subject="TPTEST改价单", amount=1000,
                        outTradeNo=f"TPTEST-PC-{SUFFIX}")
    check("改价不破坏 merchant+outTradeNo 幂等（同键返回原单新额）",
          st == 200 and again["id"] == po["id"] and again["amount"] == 800, str(again))
    logs = ok("GET", f"/api/trade-plus/orders/{po['id']}/price-logs")
    check("改价留痕审计（UNPAID/old=1000/new=800）",
          len(logs) == 1 and logs[0]["direction"] == "UNPAID" and logs[0]["oldAmount"] == 1000
          and logs[0]["newAmount"] == 800, str(logs))
    pp = pay(po["id"], pc_acct["id"], f"TPTEST-pricepay-{SUFFIX}")
    check("改价后支付按新额 800 + fee 新额快照 5（half-up 60bps）",
          pp["amount"] == 800 and pp["fee"] == 5, str(pp))
    st, resp = api("POST", f"/api/trade-plus/orders/{po['id']}/price",
                   {"newAmount": 700, "reason": "x", "requestId": f"TPTEST-price-paid-{SUFFIX}"}, step=True)
    check("已支付订单直接改价拒绝（引导差额补退）", st in (400, 409), f"{st} {resp}")
    # 改价 vs 受理竞态：零悬挂（SUCCESS 必伴随金额一致；改价胜出则受理 FAILED）
    race_o = new_order(merchant["id"], 1000, f"TPTEST改价竞态{SUFFIX}")
    race_res = {}

    def do_change():
        race_res["change"] = api("POST", f"/api/trade-plus/orders/{race_o['id']}/price",
                                 {"newAmount": 600, "reason": "race", "requestId": f"TPTEST-prace-{SUFFIX}"}, step=True)

    def do_pay():
        race_res["pay"] = api("POST", "/api/cashier/pay", {"orderId": race_o["id"], "method": "BALANCE",
                                                           "accountId": a3["id"], "requestId": f"TPTEST-prace-pay-{SUFFIX}"})

    with ThreadPoolExecutor(max_workers=2) as pool:
        fc, fp = pool.submit(do_change), pool.submit(do_pay)
        fc.result()
        fp.result()
    time.sleep(1)
    st_c, resp_c = race_res["change"]
    st_p, resp_p = race_res["pay"]
    race_final = order(race_o["id"])
    race_pays = [p for p in ok("GET", "/api/payments") if p["orderId"] == race_o["id"]]
    for p in race_pays:
        MY_PAYMENT_IDS.add(p["id"])
    race_succ = [p for p in race_pays if p["status"] == "SUCCESS"]
    if race_succ:
        # 支付胜出（改价让位）或改价先落、支付按新额受理（顺序无冲突）：SUCCESS 必伴随金额一致、恰一笔
        race_ok = (race_final["status"] == "PAID" and len(race_succ) == 1
                   and race_succ[0]["amount"] == race_final["amount"]
                   and race_final["amount"] in (1000, 600))
    else:
        race_ok = (st_c == 200 and race_final["amount"] == 600
                   and race_final["status"] in ("CREATED", "PAID")
                   and all(p["status"] in ("FAILED", "CLOSED") for p in race_pays))
    check("改价与受理竞态 CAS 守卫零悬挂（支付胜出金额一致 / 改价胜出受理 FAILED）",
          race_ok, f"change={st_c} pay={st_p} order={race_final['status']}/{race_final['amount']} "
                   f"pays={[(p['status'], p['amount']) for p in race_pays]}")
    if race_final["status"] == "CREATED":
        pay(race_o["id"], pc_acct["id"], f"TPTEST-prace-repay-{SUFFIX}")
    # 已支付下调：差额退款单
    do1 = new_order(merchant["id"], 5000, f"TPTEST补退下调{SUFFIX}")
    dp1 = pay(do1["id"], pc_acct["id"], f"TPTEST-downpay-{SUFFIX}")
    u6 = bal(pc_acct["id"])
    st, resp = api("POST", f"/api/trade-plus/orders/{do1['id']}/adjust-price",
                   {"newAmount": 4000, "reason": "下调", "requestId": f"TPTEST-down-{SUFFIX}"}, step=False)
    check("差额补退缺 step-up → 403", st == 403, f"{st} {resp}")
    st, down = api("POST", f"/api/trade-plus/orders/{do1['id']}/adjust-price",
                   {"newAmount": 4000, "reason": "下调", "requestId": f"TPTEST-down-{SUFFIX}"}, step=True)
    check("已支付下调 5000→4000（DOWN/delta=1000/退款单）", st == 200 and down["direction"] == "DOWN"
          and down["delta"] == 1000 and down["refundId"], f"{st} {down}")
    poll(lambda: ok("GET", f"/api/refunds/{down['refundId']}"), lambda r: r["status"] == "SUCCESS")
    do1_after = order(do1["id"])
    check("下调不改订单额/fee 快照（amount=5000 原支付 fee=30 不变）",
          do1_after["amount"] == 5000 and payment(dp1["id"])["fee"] == 30
          and do1_after["refundedAmount"] == 1000 and do1_after["status"] == "PARTIAL_REFUNDED", str(do1_after))
    check("下调净价守恒（5000−1000=4000）+ 差额原路退回",
          do1_after["amount"] - do1_after["refundedAmount"] == 4000 and bal(pc_acct["id"]) == u6 + 1000,
          f"{bal(pc_acct['id'])} vs {u6 + 1000}")
    st, down2 = api("POST", f"/api/trade-plus/orders/{do1['id']}/adjust-price",
                    {"newAmount": 4000, "reason": "下调", "requestId": f"TPTEST-down-{SUFFIX}"}, step=True)
    check("差额补退幂等（repeated=true 不重复退款）", st == 200 and down2["repeated"] is True
          and down2["refundId"] == down["refundId"] and bal(pc_acct["id"]) == u6 + 1000, f"{st} {down2}")
    # 已支付上调：补差订单
    do2 = new_order(merchant["id"], 3000, f"TPTEST补退上调{SUFFIX}")
    pay(do2["id"], pc_acct["id"], f"TPTEST-uppay-{SUFFIX}")
    u7 = bal(pc_acct["id"])
    st, up = api("POST", f"/api/trade-plus/orders/{do2['id']}/adjust-price",
                 {"newAmount": 3500, "reason": "上调", "requestId": f"TPTEST-up-{SUFFIX}", "accountId": pc_acct["id"]}, step=True)
    check("已支付上调 3000→3500（UP/delta=500/补差单+支付）", st == 200 and up["direction"] == "UP"
          and up["delta"] == 500 and up["supplementOrderId"] and up["supplementPaymentId"], f"{st} {up}")
    supp_pay = wait_payment(up["supplementPaymentId"], "SUCCESS")
    check("补差支付 500 收敛 SUCCESS + 净价守恒（3000+500=3500）",
          supp_pay["amount"] == 500 and bal(pc_acct["id"]) == u7 - 500
          and order(do2["id"])["amount"] + supp_pay["amount"] == 3500, str(supp_pay))
    # 大额下调双人复核
    do3 = new_order(merchant["id"], 2000000, f"TPTEST大额补退{SUFFIX}")
    dp3 = pay(do3["id"], a2["id"], f"TPTEST-bigdown-pay-{SUFFIX}")
    u8 = bal(a2["id"])
    st, resp = api("POST", f"/api/trade-plus/orders/{do3['id']}/adjust-price",
                   {"newAmount": 500000, "reason": "大额下调", "requestId": f"TPTEST-bigdown-{SUFFIX}"}, step=True)
    check("大额下调未复核 → A0428（分文未动）", st == 400 and resp.get("code") == "A0428"
          and bal(a2["id"]) == u8, f"{st} {resp}")
    approve(resp["reviewId"])
    st, big_down = api("POST", f"/api/trade-plus/orders/{do3['id']}/adjust-price",
                       {"newAmount": 500000, "reason": "大额下调", "requestId": f"TPTEST-bigdown-{SUFFIX}"}, step=True)
    check("复核通过后大额下调成功（delta=1500000）", st == 200 and big_down["direction"] == "DOWN"
          and big_down["delta"] == 1500000, f"{st} {big_down}")
    poll(lambda: ok("GET", f"/api/refunds/{big_down['refundId']}"), lambda r: r["status"] == "SUCCESS", timeout=90)
    check("大额下调净价守恒（2000000−1500000=500000）",
          order(do3["id"])["amount"] - order(do3["id"])["refundedAmount"] == 500000, str(order(do3["id"])))
    trial_guard("改价后")

    # ---------- 8. 已清算边界（跑批后撤销拒绝 → 退款仍可） ----------
    print("[8] 已清算边界：当日批次覆盖后撤销拒绝，退款异步收敛仍可（专用商户隔离）")
    so2 = new_order(m2["id"], 2500, f"TPTEST清算单{SUFFIX}")
    sp = pay(so2["id"], a4["id"], f"TPTEST-settlepay-{SUFFIX}")
    ts = today_start()
    st, resp = api("POST", "/api/settle/batch/run",
                   {"bizDate": ts, "shardIndex": 0, "shardTotal": 1, "force": True}, step=True)
    check("当日清算跑批受理（force）", st == 200, f"{st} {resp}")
    batches = ok("GET", f"/api/settle/batches?merchantId={m2['id']}")
    check("清算批次覆盖当日（bizDate>=todayStart）",
          any(b.get("bizDate") and b["bizDate"] >= ts for b in batches), str(batches)[:200])
    st, resp = api("POST", f"/api/trade-plus/payments/{sp['id']}/cancel",
                   {"requestId": f"TPTEST-cancel-settled-{SUFFIX}"}, step=True)
    check("已清算撤销拒绝（判定边界：请走退款）", st == 409, f"{st} {resp}")
    st, rr = api("POST", f"/api/payments/{sp['id']}/refund",
                 {"amount": 2500, "requestId": f"TPTEST-settled-refund-{SUFFIX}"}, step=True)
    check("已清算全额退款受理（退款单路径）", st == 200 and rr.get("refundId"), f"{st} {rr}")
    poll(lambda: ok("GET", f"/api/refunds/{rr['refundId']}"), lambda r: r["status"] == "SUCCESS")
    check("退款异步收敛 SUCCESS + 订单 REFUNDED（P-4 outbox 兜底不变）",
          ok("GET", f"/api/refunds/{rr['refundId']}")["status"] == "SUCCESS"
          and wait_order(so2["id"], "REFUNDED")["status"] == "REFUNDED", str(order(so2["id"])))

    # ---------- 9. 资金护航与两态一致 ----------
    print("[9] 资金护航：试算/对账/分片两态/零悬挂")
    t = trial_guard("收尾")
    check("账户数/分录数不少于基线（只增不删）",
          t["accounts"] >= base_trial["accounts"] and t["entries"] >= base_trial["entries"],
          f"{t['accounts']}/{t['entries']} vs {base_trial['accounts']}/{base_trial['entries']}")
    recon_guard("收尾")
    all_pays = ok("GET", "/api/payments")
    mine = [p for p in all_pays if p["id"] in MY_PAYMENT_IDS]
    TERMINAL_PAY = ("SUCCESS", "FAILED", "CLOSED", "CANCELLED", "REFUNDED", "PARTIAL_REFUNDED")
    check("本批支付单全部终态（零 PROCESSING 悬挂）",
          mine and all(p["status"] in TERMINAL_PAY for p in mine),
          str([(p["id"], p["status"]) for p in mine if p["status"] not in TERMINAL_PAY]))
    check("本批订单零 PAYING 悬挂",
          all(order(oid)["status"] != "PAYING" for oid in MY_ORDER_IDS),
          str([oid for oid in MY_ORDER_IDS if order(oid)["status"] == "PAYING"]))
    # 分片/单库两态一致：local 单库与 docker 分片态跑同一套功能断言（本脚本双 profile 通用）；
    # docker 分片态另核影子侧逐行一致（shadowCompare diffTotal=0）+ 本批商户路由落片正确
    st, shard_status = http(TRADE_ACT + "/ppshard")
    if st == 200 and shard_status.get("shardingConfigured"):
        cmp = http(TRADE_ACT + "/ppshard/shadowCompare")[1]
        check("分片态影子侧逐行一致（diffTotal=0，两态一致）",
              isinstance(cmp, dict) and cmp.get("diffTotal", 0) == 0, str(cmp)[:200])
        detail = http(TRADE_ACT + f"/ppshard/merchant/{merchant['id']}")[1]
        # routedCount 是分组计数列表 [{'c': n}]，不是标量；须先求和再与物理分布Σ比
        routed = detail.get("routedCount")
        routed_sum = sum(r.get("c", 0) for r in routed) if isinstance(routed, list) else (routed or 0)
        check("本批商户订单路由落片一致（routedCount=物理分布Σ）",
              routed_sum == sum((detail.get("physicalDistribution") or {}).values()), str(detail))
        expect_mode = "SNOWFLAKE" if shard_status.get("shardingActive") else "SEQUENCE"
        check(f"分片态发号模式与切流状态一致（{expect_mode}）",
              shard_status.get("idMode") == expect_mode, str(shard_status))
    else:
        check("单库态（local/未切流）功能断言与分片态同一套（两态一致）",
              st == 200 and shard_status.get("shardingActive") is False, str(shard_status))
    # 收尾清理（只动本批 TPTEST 数据）：未付 CREATED 单关闭，活跃合并单拆分后关闭子订单，
    # 避免给后续套件留未结算残留（已付/已退/终态单一律保留，绝不删除任何行）
    for oid in list(MY_ORDER_IDS):
        o = order(oid)
        if o["status"] != "CREATED":
            continue
        if o.get("mergeId"):
            mg_now = ok("GET", f"/api/trade-plus/merges/{o['mergeId']}")
            if mg_now["status"] == "CREATED":
                # 收尾清理非断言：拆分可能因子订单仍带支付引用被 409 合法拒绝，不中断套件
                api("POST", f"/api/trade-plus/merges/{o['mergeId']}/dissolve")
        api("POST", f"/api/orders/{oid}/close")
    print(f"\n本批新增支付单 {len(mine)} 笔 / 订单 {len(MY_ORDER_IDS)} 笔（TPTEST{SUFFIX}，只增不删）")


if __name__ == "__main__":
    try:
        main()
    finally:
        pass
    print(f"\n结果：{PASSED} 通过，{FAILED} 失败")
    if FAILURES:
        print("失败项：" + "; ".join(FAILURES))
    sys.exit(1 if FAILED else 0)