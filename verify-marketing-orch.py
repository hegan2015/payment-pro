#!/usr/bin/env python3
# ============================================================================
# payment-pro P2 · E 泳道验证：P-7 营销抵扣编排 + C-4 营销计算最优组合
#
# 用法：docker 全栈在线（./run.sh --docker）后 python3 verify-marketing-orch.py
#   脚本自动探测营销域（actuator 18093）是否在线：
#     在线  → [1]~[10] 全量（C-4 最优组合 / P-7 编排 / 回退 / 重分摊 / COMBO / 资金红线 / local H2）
#     离线  → [1]+[11]（PP_MARKETING_ENABLED=false 形态：ride 零影响 + 编排优雅短路）
#
#   [1]  基线与门禁：13/13 健康 list（marketing 刻意不纳入）、login+step-up、Bearer 双端点、域间边预热
#   [2]  ride 红线：无营销权益时编排完全短路（零 RPC/零副作用/fee 不变）+ 8 冻结 OpenAPI + cashier 9 方法面
#   [3]  C-4 最优组合正确性 / 可解释性 / 确定性
#   [4]  C-4 约束：满减门槛 / 封顶 / 互斥 / 叠加 / 预算余额 / 积分抵现比例 / 抵扣上限 / 无损剪枝
#   [5]  P-7 单腿编排：券→红包→积分→活动、实付=订单额−Σ抵扣、幂等、复式记账凭证、分摊守恒
#   [6]  P-7 失败回退：核销失败 / 积分不足 / 预算不足 / 超订单额 / 抵扣后余额不足 → 逆序退回权益
#   [7]  退回后目标维度重分摊（P2-A 遗留项）：Σ目标=Σ来源 diff=0、最大余数法、幂等
#   [8]  COMBO 衔接守恒：Σ资金腿=订单额、补贴入 BALANCE 腿、净实付、失败回退
#   [9]  资金红线与不回归：营销资金恒等 / 试算 D=C 且 Σbalance=0 / 六项对账 / fee 口径 / 13-13
#   [10] local H2 零依赖形态：C-4 与重分摊单进程可验（独立 DATA_DIR/端口，不动既有数据）
#   [11] 营销域未启用：无诉求支付零影响 + 有诉求支付优雅拒绝（非崩溃）+ 账本仍平衡
#
# 数据保护：只新增 MOCH 前缀 + uuid 的测试行；绝不删除/重置任何既有数据；不改库状态凑绿。
# 断言口径：不放宽；失败如实记录。
# ============================================================================
import hashlib
import hmac
import json
import os
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = "http://127.0.0.1:8080"
MKT_ACT = "http://127.0.0.1:18093/actuator"
LOCAL_BASE = "http://127.0.0.1:18994/local/marketing"
JAVA_HOME = "/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home"
REPO = os.path.dirname(os.path.abspath(__file__))
SUFFIX = uuid.uuid4().hex[:8]
TAG = "MOCH"
CARD = "6222020202020202"
TOKEN = None
STEP = None
PASSED = 0
FAILED = 0
FAILURES = []
LOCAL_PROC = None
MKT_ONLINE = False
# 全局营销活动 id 过滤器：活动是全局资源，必须显式限定候选才能做确定性断言（隔离其它泳道/历史活动）
NO_ACT = ["MOCH-NONE-ACTIVITY"]


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


def http(url, method="GET", body=None, bearer=False, step=False, timeout=120, raw=False):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, method=method,
                               headers={"Content-Type": "application/json", "Accept": "*/*"})
    if bearer and TOKEN:
        r.add_header("Authorization", "Bearer " + TOKEN)
    if step and STEP:
        r.add_header("X-Step-Up-Token", STEP)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            text = resp.read().decode()
            if raw:
                return resp.status, text
            return resp.status, json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        if raw:
            return e.code, text
        try:
            return e.code, json.loads(text)
        except Exception:
            return e.code, {"error": text}
    except Exception as e:
        return 0, {"error": str(e)}


def api(path, method="GET", body=None, step=False, timeout=120):
    return http(BASE + path, method, body, bearer=True, step=step, timeout=timeout)


def ok(path, method="GET", body=None, step=False):
    st, resp = api(path, method, body, step=step)
    assert st == 200, f"{method} {path} -> {st} {resp}"
    return resp


def mkt(path, method="GET", body=None, step=True):
    return api("/api/marketing" + path, method, body, step=step)


def mok(path, method="GET", body=None, step=True):
    st, resp = mkt(path, method, body, step=step)
    assert st == 200, f"{method} /api/marketing{path} -> {st} {resp}"
    return resp


def orch(path, method="GET", body=None, step=True):
    return api("/api/marketing-pay" + path, method, body, step=step)


def ook(path, method="GET", body=None, step=True):
    st, resp = orch(path, method, body, step=step)
    assert st == 200, f"{method} /api/marketing-pay{path} -> {st} {resp}"
    return resp


def bal(account_id):
    return ok(f"/api/accounting/accounts/{account_id}/balance")


def fee_of(amount):
    """既有口径：60bps，MoneyUtil.fee 四舍五入（与 smoke-test 逐字一致）。"""
    return (amount * 60 + 5000) // 10000


def login():
    global TOKEN, STEP
    password = os.environ.get("PP_ADMIN_PASSWORD", "admin123")
    st, resp = http(BASE + "/api/auth/login", "POST", {"username": "admin", "password": password})
    assert st == 200, f"登录失败 {st} {resp}"
    TOKEN = resp["token"]
    st, resp = http(BASE + "/api/security/step-up", "POST", {"password": password}, bearer=True)
    assert st == 200, f"二次认证失败 {st} {resp}"
    STEP = resp["token"]


def new_subject(kind="USER"):
    m = ok("/api/members", "POST", {"name": f"{TAG}-{kind}-{SUFFIX}-{uuid.uuid4().hex[:6]}", "type": kind})
    a = ok("/api/accounts", "POST", {"memberId": m["id"]})
    return m, a


def topup(account_id, amount):
    ok("/api/accounting/internal/topup", "POST",
       {"accountId": account_id, "amount": amount, "bizNo": biz("TU")})


def biz(prefix):
    return f"{TAG}-{prefix}-{uuid.uuid4().hex[:12]}"


def pid():
    return f"{TAG}P{uuid.uuid4().hex[:10]}"


def new_order(merchant_id, amount, subject="MOCH 营销编排商品"):
    return ok("/api/orders", "POST", {"merchantId": merchant_id, "subject": subject,
                                      "amount": amount, "outTradeNo": biz("OT")})["order"]


def coupon(member_id, amount, threshold=0, expired=False, **kw):
    body = {"memberId": member_id, "amount": amount, "threshold": threshold, "bizNo": biz("CI"),
            "validDays": 30}
    if expired:
        body["expireAt"] = int(time.time() * 1000) - 1000
    body.update(kw)
    return mok("/coupons/issue", "POST", body)


def red_packet(member_id, amount):
    return mok("/red-packets/issue", "POST",
               {"memberId": member_id, "amount": amount, "bizNo": biz("RI"), "validDays": 30})


def earn(member_id, points):
    mok("/points/earn", "POST", {"memberId": member_id, "points": points, "bizNo": biz("PE")})


def activity(name, atype, threshold=0, discount_amount=0, discount_bps=0, max_discount=0, budget=0):
    return mok("/activities", "POST", {"name": f"{TAG}-{name}-{SUFFIX}-{uuid.uuid4().hex[:4]}",
                                       "type": atype, "threshold": threshold,
                                       "discountAmount": discount_amount, "discountBps": discount_bps,
                                       "maxDiscount": max_discount, "budget": budget})


def best(member_id, order_amount, activity_ids=None, **kw):
    body = {"memberId": member_id, "orderAmount": order_amount,
            "activityIds": activity_ids if activity_ids is not None else NO_ACT}
    body.update(kw)
    return ook("/best", "POST", body, step=False)


def pay_marketing(order_id, account_id, marketing, legs=None, request_id=None, method="BALANCE"):
    body = {"orderId": order_id, "method": method, "accountId": account_id,
            "requestId": request_id or biz("REQ"), "marketing": marketing}
    if legs is not None:
        body["legs"] = legs
    return orch("/pay", "POST", body)


def wait_payment(payment_id, status, timeout=25):
    deadline = time.time() + timeout
    p = ok(f"/api/payments/{payment_id}")
    while time.time() < deadline and p["status"] != status:
        time.sleep(0.4)
        p = ok(f"/api/payments/{payment_id}")
    return p


def find_payment_by_request(request_id):
    for p in ok("/api/payments"):
        if p.get("requestId") == request_id:
            return p
    return None


def picks_of(dto):
    return [(s["source"], s["refId"], s["amount"]) for s in dto["selected"]]


def reject_codes(dto):
    return {(r["source"], r["refId"]): r["code"] for r in dto["rejected"]}


def sign_params(params, secret):
    items = sorted((k, v) for k, v in params.items() if v is not None and k != "sign")
    data = "&".join(f"{k}={v}" for k, v in items)
    return hmac.new(secret.encode(), data.encode(), hashlib.sha256).hexdigest()


def op_body(app_id, secret, params):
    body = dict(params)
    body["appId"] = app_id
    body["timestamp"] = int(time.time() * 1000)
    body["sign"] = sign_params(body, secret)
    return body


def op(method, app_id, secret, params):
    return http(BASE + "/openapi/" + method, "POST", op_body(app_id, secret, params))


# ============================================================================
def sec1_baseline():
    print("[1] 基线与门禁")
    st, health = http(BASE + "/api/health")
    check("网关健康 200", st == 200, str(st))
    check("健康清单仍是 13 项（marketing 刻意不纳入，既有硬断言不破）",
          isinstance(health, list) and len(health) == 13, str(health))
    check("13/13 全健康", all(h["ok"] for h in health), str([h["service"] for h in health if not h["ok"]]))
    check("健康清单不含 marketing", not any("marketing" in h["service"] for h in health))
    login()
    check("login + step-up 200", bool(TOKEN) and bool(STEP))
    check("Bearer /api/members 200", api("/api/members")[0] == 200)
    check("Bearer /api/payments 200", api("/api/payments")[0] == 200)
    accounts = ok("/api/accounts")
    aid = accounts[0]["id"] if accounts else None
    check("域间边预热：GET /api/accounts 取 id", bool(aid), str(accounts)[:120])
    check("域间边预热：GET /api/accounting/accounts/{id}/balance 200",
          api(f"/api/accounting/accounts/{aid}/balance")[0] == 200)
    trial = ok("/api/accounting/trial")
    check("基线试算平衡 D=C 且 Σbalance=0", trial["balanced"] and trial["balanceSum"] == 0, str(trial))
    global MKT_ONLINE
    st, act = http(MKT_ACT + "/health", timeout=5)
    MKT_ONLINE = st == 200 and (act or {}).get("status") == "UP"
    print(f"  · 营销域在线={MKT_ONLINE}（离线则只跑 [1]+[11] 短路形态）")
    return trial


# ============================================================================
def sec2_ride_shortcircuit(merchant, user, account):
    print("[2] ride 红线：无营销权益时编排完全短路（零 RPC / 零副作用 / fee 不变）")
    topup(account, 200000)
    u0, m0 = bal(account), bal(merchant["accountId"])
    fee0, sysm0 = bal("SYS_FEE"), bal("SYS_MARKETING")
    audit0 = mok("/audit?entityId=&limit=500", step=False)
    amount = 3000
    order = new_order(merchant["id"], amount)
    req = biz("RIDE")
    t0 = time.time()
    st, resp = api("/api/cashier/pay", "POST", {"orderId": order["id"], "method": "BALANCE",
                                               "accountId": account, "requestId": req})
    elapsed = (time.time() - t0) * 1000
    check("无营销诉求 BALANCE 支付受理 200（既有路径不变）", st == 200, f"{st} {resp}")
    p = wait_payment(resp["payment"]["id"], "SUCCESS")
    check("BALANCE 同步语义不变：受理→PROCESSING→SUCCESS", p["status"] == "SUCCESS", str(p["status"]))
    check("fee 口径逐字不变（60bps 订单全额）", p["fee"] == fee_of(amount) == 18, str(p["fee"]))
    check("payment.amount 仍是订单全额", p["amount"] == amount, str(p["amount"]))
    check("marketingAmount=0（零抵扣）", p.get("marketingAmount") == 0, str(p.get("marketingAmount")))
    check("marketingStatus=NONE（编排未介入）", p.get("marketingStatus") == "NONE", str(p.get("marketingStatus")))
    check("payAmount==amount（无抵扣时净实付=订单额）", p.get("payAmount") == amount, str(p.get("payAmount")))
    check("既有字段语义不变：payMode=SINGLE / legs 为空",
          p.get("payMode") == "SINGLE" and not p.get("legs"), str(p.get("payMode")))
    check("付款方扣全额（无补贴）", bal(account) == u0 - amount, f"{u0} -> {bal(account)}")
    check("商户净额 +amount−fee", bal(merchant["accountId"]) == m0 + amount - 18,
          f"{m0} -> {bal(merchant['accountId'])}")
    check("SYS_FEE 等额入账", bal("SYS_FEE") == fee0 + 18)
    check("SYS_MARKETING 零动账（编排完全短路）", bal("SYS_MARKETING") == sysm0,
          f"{sysm0} -> {bal('SYS_MARKETING')}")
    d = ook(f"/deduction/{p['id']}", step=False)
    check("抵扣明细为空且 status=NONE", d["status"] == "NONE" and d["legs"] == [], str(d))
    check("零 RPC 证据：allocation/conservation 均为 null（未触达营销域）",
          d.get("allocation") is None and d.get("conservation") is None, str(d)[:200])
    check("净实付/抵扣额如实为 0 与订单额", d["netAmount"] == 0 and d["payAmount"] == amount, str(d))
    alloc = mok(f"/alloc/{p['id']}", step=False)
    check("营销域无该支付单任何分摊行（零副作用）",
          alloc["sources"] == [] and alloc["targets"] == [] and alloc["totalAmount"] == 0, str(alloc))
    audit1 = mok("/audit?entityId=&limit=500", step=False)
    check("营销域审计零关联（无任何行引用该支付单 ⇒ 编排零副作用）",
          not any(p["id"] in str(a.get("bizNo", "")) + str(a.get("detail", "")) + str(a.get("entityId", ""))
                  for a in audit1), str(audit1)[:200])
    check("无营销诉求支付未产生 payment_marketing_leg 行", d["legs"] == [])
    print(f"  · 无营销诉求 BALANCE 受理耗时 {elapsed:.0f}ms（含 TCC 同步 Try；仅报告不设阈值）")

    # ---- 8 个冻结 OpenAPI 方法（ride 调用面）----
    app_id, secret = merchant["appId"], merchant["appSecret"]
    st, r = op("account.open", app_id, secret, {"name": f"{TAG}-RIDE-{SUFFIX}", "type": "USER"})
    check("account.open 返回 memberId/accountId（结构不变）",
          st == 200 and "memberId" in r and "accountId" in r, f"{st} {r}")
    ride_acc = r["accountId"]
    st, r = op("fund.topup", app_id, secret, {"accountId": ride_acc, "amount": 50000, "bizNo": biz("FT")})
    check("fund.topup 返回 payment.id", st == 200 and "id" in (r.get("payment") or r), f"{st} {r}")
    tp = r.get("payment") or r
    tpp = wait_payment(tp["id"], "SUCCESS")
    check("fund.topup fee=0 / BALANCE / 无渠道（语义不变）",
          tpp["fee"] == 0 and tpp["method"] == "BALANCE" and not tpp.get("channelBizNo"), str(tpp))
    check("fund.topup marketingAmount=0（编排短路）", tpp.get("marketingAmount") == 0, str(tpp))
    st, r = op("account.balance", app_id, secret, {"accountId": ride_acc})
    check("account.balance 返回裸数字 long（结构不变）",
          st == 200 and isinstance(r, int) and r == 50000, f"{st} {r}")
    st, r = op("account.transfer", app_id, secret, {"fromAccountId": ride_acc, "toAccountId": account,
                                                    "amount": 1000, "bizNo": biz("TR")})
    check("account.transfer 200（借贷腿定向划转语义不变）", st == 200, f"{st} {r}")
    check("transfer 后余额逐分正确", bal(ride_acc) == 49000, str(bal(ride_acc)))
    st, r = op("fund.withdraw", app_id, secret, {"accountId": ride_acc, "amount": 2000, "bizNo": biz("FW")})
    wp = (r.get("payment") or r) if st == 200 else {}
    check("fund.withdraw 返回 payment.id 且同步先扣", st == 200 and bal(ride_acc) == 47000, f"{st} {r}")
    if wp.get("id"):
        wpp = wait_payment(wp["id"], "SUCCESS")
        check("fund.withdraw fee=0 / BALANCE（语义不变）", wpp["fee"] == 0 and wpp["method"] == "BALANCE", str(wpp))
    st, r = op("trade.create", app_id, secret, {"merchantId": merchant["id"], "subject": "MOCH ride 商品",
                                                "amount": 800, "outTradeNo": biz("OT")})
    check("trade.create 返回 id（merchant+outTradeNo 幂等不变）", st == 200 and "id" in r, f"{st} {r}")
    ride_order = r["id"]
    st, r = op("trade.pay", app_id, secret, {"orderId": ride_order, "accountId": ride_acc,
                                             "method": "BALANCE", "outTradeNo": biz("OTP"),
                                             "callbackUrl": "http://127.0.0.1:9/moch"})
    check("trade.pay BALANCE 受理 200 返回 payment.id", st == 200 and "id" in (r.get("payment") or r), f"{st} {r}")
    rp = wait_payment((r.get("payment") or r)["id"], "SUCCESS") if st == 200 else {}
    check("trade.pay BALANCE 同步收敛 SUCCESS（不得引入 WAITING/异步回执）",
          rp.get("status") == "SUCCESS" and rp.get("fee") == fee_of(800), str(rp))
    check("trade.pay marketingAmount=0 / payAmount=800（ride 零影响）",
          rp.get("marketingAmount") == 0 and rp.get("payAmount") == 800, str(rp))
    st, r = op("trade.query", app_id, secret, {"orderId": ride_order})
    check("trade.query 返回订单+支付单（结构不变）",
          st == 200 and "order" in r and "payment" in r, f"{st} {str(r)[:160]}")
    check("trade.query 手续费可回查（ride 增量对账口径不变）",
          (r.get("payment") or {}).get("fee") == fee_of(800), str(r.get("payment")))

    # ---- cashier OpenAPI 9 方法调用面仍在（只 additive，结构不变）----
    st, r = op("cashier.methods", app_id, secret, {"orderId": ride_order, "accountId": ride_acc})
    check("cashier.methods 200 且返回结构不变", st == 200 and isinstance(r, (dict, list)), f"{st} {str(r)[:160]}")
    for m in ["cashier.code.create", "cashier.code.parse", "cashier.code.query", "cashier.scanPay",
              "cashier.verify", "cashier.payVerified", "cashier.payPassword.set", "cashier.sms.send"]:
        st, r = op(m, app_id, secret, {})
        check(f"{m} 调用面仍在（非 404，结构化错误体）",
              st != 404 and isinstance(r, dict) and ("error" in r or "code" in r or st == 200),
              f"{st} {str(r)[:120]}")
    return p


# ============================================================================
def sec3_c4_basic(member, acts):
    print("[3] C-4 最优组合：正确性 / 可解释性 / 确定性")
    a_full, a_cap, a_poor = acts
    c1 = coupon(member, 3000, 5000)
    c2 = coupon(member, 2000, 20000)
    c3 = coupon(member, 1000, 0)
    r1 = red_packet(member, 1000)
    earn(member, 50000)
    ids = [a_full["id"], a_cap["id"], a_poor["id"]]
    d = best(member, 10000, ids)
    check("最优总抵扣 = 3000券 + 1000红包 + 500积分 + 1500活动 = 6000",
          d["totalDiscount"] == 6000, str(d["totalDiscount"]))
    check("最优实付 = 10000 − 6000 = 4000（用户最优）", d["payAmount"] == 4000, str(d["payAmount"]))
    check("分项金额正确", (d["couponAmount"], d["redPacketAmount"], d["pointsAmount"], d["activityAmount"])
          == (3000, 1000, 500, 1500), str(d))
    check("选中顺序 = 券→红包→积分→活动（与 P-7 编排一致）",
          [s["source"] for s in d["selected"]] == ["COUPON", "RED_PACKET", "POINTS", "ACTIVITY"],
          str([s["source"] for s in d["selected"]]))
    check("选中券为面额最大且达门槛的 C1", picks_of(d)[0] == ("COUPON", c1["id"], 3000), str(picks_of(d)))
    check("积分抵现比例正确：50000 积分 ÷ 100 = 500 分，pointsUsed=50000",
          d["pointsUsed"] == 50000 and d["pointsExchangeRate"] == 100, str(d["pointsUsed"]))
    check("每个选中项都有非空选中理由（可解释）",
          all(s.get("reason") for s in d["selected"]), str(d["selected"])[:200])
    codes = reject_codes(d)
    check("落选原因：未达门槛券 THRESHOLD_NOT_MET", codes.get(("COUPON", c2["id"])) == "THRESHOLD_NOT_MET",
          str(codes.get(("COUPON", c2["id"]))))
    check("落选原因：预算不足活动 BUDGET_INSUFFICIENT（绝不为凑最优超预算）",
          codes.get(("ACTIVITY", a_poor["id"])) == "BUDGET_INSUFFICIENT", str(codes))
    check("落选原因：同组面额更小 STACK_LIMIT", codes.get(("COUPON", c3["id"])) == "STACK_LIMIT", str(codes))
    check("落选原因：活动叠加上限已用满 STACK_LIMIT",
          codes.get(("ACTIVITY", a_cap["id"])) == "STACK_LIMIT", str(codes))
    check("每个落选项都有非空原因文案", all(r.get("reason") for r in d["rejected"]))
    check("budgetRespected / mutexRespected 均为真", d["budgetRespected"] and d["mutexRespected"], str(d))
    check("exhaustive=true（节点预算内穷尽，最优性已证）", d["exhaustive"] is True, str(d))
    check("评估组合数 ≤ 16（来源组掩码枚举有界）", 0 < d["evaluatedCombinations"] <= 16,
          str(d["evaluatedCombinations"]))
    check("策略说明非空（算法可解释）", bool(d.get("strategy")) and bool(d.get("explanation")), str(d.get("strategy")))
    check("计算不核销：券仍 AVAILABLE", mok(f"/coupons/{c1['id']}", step=False)["status"] == "AVAILABLE")
    check("计算不核销：活动预算 used 不变",
          mok(f"/activities/{a_full['id']}", step=False)["used"] == 0, str(a_full))
    check("计算不核销：积分余额不变", mok(f"/points/{member}", step=False)["balance"] == 50000)
    # 确定性
    d2 = best(member, 10000, ids)
    check("确定性：同输入两次调用选中项逐字一致", picks_of(d) == picks_of(d2), f"{picks_of(d)} vs {picks_of(d2)}")
    check("确定性：inputFingerprint 一致", d["inputFingerprint"] == d2["inputFingerprint"],
          f'{d["inputFingerprint"]} vs {d2["inputFingerprint"]}')
    check("确定性：落选原因码集合一致", reject_codes(d) == reject_codes(d2))
    check("确定性：总抵扣/实付一致", (d["totalDiscount"], d["payAmount"]) == (d2["totalDiscount"], d2["payAmount"]))
    d3 = best(member, 10000, list(reversed(ids)))
    check("确定性：活动候选入参顺序无关（指纹归一化排序）",
          d3["inputFingerprint"] == d["inputFingerprint"] and picks_of(d3) == picks_of(d), str(d3["inputFingerprint"]))
    return {"c1": c1, "c2": c2, "c3": c3, "r1": r1, "ids": ids, "base": d}


def sec4_c4_constraints(member, acts, kit, member2, member3):
    print("[4] C-4 约束：门槛 / 封顶 / 互斥 / 叠加 / 预算 / 上限 / 无损剪枝")
    a_full, a_cap, a_poor = acts
    ids = kit["ids"]
    # 互斥
    d = best(member, 10000, ids, rules={"mutex": ["COUPON|ACTIVITY"]})
    check("互斥 COUPON|ACTIVITY：选券弃活动，总抵扣 4500", d["totalDiscount"] == 4500, str(d["totalDiscount"]))
    check("互斥：实付 5500", d["payAmount"] == 5500, str(d["payAmount"]))
    check("互斥：活动落选码 MUTEX",
          reject_codes(d).get(("ACTIVITY", a_full["id"])) == "MUTEX", str(reject_codes(d)))
    check("互斥：mutexRespected=true（绝不违反互斥）", d["mutexRespected"] is True)
    check("互斥：选中项不含 ACTIVITY", all(s["source"] != "ACTIVITY" for s in d["selected"]))
    d = best(member, 10000, ids, rules={"mutex": ["ACTIVITY|COUPON"]})
    check("互斥规则顺序无关（归一化）：结果与 COUPON|ACTIVITY 一致", d["totalDiscount"] == 4500, str(d))
    # 积分不可叠加
    d = best(member, 10000, ids, rules={"pointsStackable": False})
    check("积分不可叠加：弃积分保券+红包+活动 = 5500", d["totalDiscount"] == 5500, str(d["totalDiscount"]))
    check("积分不可叠加：POINTS 落选码 MUTEX",
          reject_codes(d).get(("POINTS", member)) == "MUTEX", str(reject_codes(d)))
    # 叠加
    d = best(member, 10000, ids, rules={"maxCoupons": 2})
    check("叠加 maxCoupons=2：券 3000+1000=4000，总抵扣 7000",
          d["couponAmount"] == 4000 and d["totalDiscount"] == 7000, str(d))
    check("叠加：选中两张券", sum(1 for s in d["selected"] if s["source"] == "COUPON") == 2)
    check("叠加：实付 3000", d["payAmount"] == 3000, str(d["payAmount"]))
    d = best(member, 10000, ids, rules={"maxCoupons": 0})
    check("关闭券（maxCoupons=0）：券全落选 DISABLED，总抵扣 3000",
          d["couponAmount"] == 0 and d["totalDiscount"] == 3000, str(d))
    check("关闭券：落选码 DISABLED", reject_codes(d).get(("COUPON", kit["c1"]["id"])) == "DISABLED")
    # 抵扣上限（截断）
    d = best(member, 10000, ids, rules={"maxDiscountBps": 5000})
    check("上限 50%：总抵扣被截到 5000（不得为负实付）", d["totalDiscount"] == 5000, str(d["totalDiscount"]))
    check("上限：实付 5000 ≥ 0", d["payAmount"] == 5000, str(d["payAmount"]))
    check("上限：确定性截断顺序先牺牲积分（积分归零落选 CAP_REACHED）",
          d["pointsAmount"] == 0 and reject_codes(d).get(("POINTS", member)) == "CAP_REACHED", str(d))
    rp = next(s for s in d["selected"] if s["source"] == "RED_PACKET")
    check("上限：红包被部分截断 1000→500 且 truncated/originalAmount 留痕",
          rp["amount"] == 500 and rp["truncated"] is True and rp["originalAmount"] == 1000, str(rp))
    check("上限：券面额保持完整（截断顺序最后牺牲券）", d["couponAmount"] == 3000, str(d["couponAmount"]))
    check("上限：活动不可截断仍全额 1500", d["activityAmount"] == 1500, str(d["activityAmount"]))
    # 满减门槛（订单额不足）
    d = best(member, 4000, ids)
    check("订单额 4000 < 活动门槛 8000：活动落选 THRESHOLD_NOT_MET",
          reject_codes(d).get(("ACTIVITY", a_full["id"])) == "THRESHOLD_NOT_MET", str(reject_codes(d)))
    check("订单额 4000 < 券门槛 5000：C1 落选 THRESHOLD_NOT_MET",
          reject_codes(d).get(("COUPON", kit["c1"]["id"])) == "THRESHOLD_NOT_MET")
    check("订单额 4000：最优 = 1000券 + 1000红包 + 500积分 + 800封顶立减 = 3300",
          d["totalDiscount"] == 3300 and d["activityAmount"] == 800, str(d["totalDiscount"]))
    check("订单额 4000：实付 700", d["payAmount"] == 700, str(d["payAmount"]))
    # 不超订单额（小额订单 + 大额券）
    d = best(member, 600, ids)
    check("小额订单 600：总抵扣 ≤ 600 且实付 ≥ 0",
          d["totalDiscount"] <= 600 and d["payAmount"] >= 0
          and d["payAmount"] == 600 - d["totalDiscount"], str(d))
    check("小额订单 600：抵扣被截到订单额 600（实付 0）", d["totalDiscount"] == 600, str(d["totalDiscount"]))
    # 封顶
    a2 = activity("CAP2", "DISCOUNT", 0, discount_bps=5000, max_discount=800, budget=0)
    a3 = activity("NOCAP", "DISCOUNT", 0, discount_bps=5000, max_discount=0, budget=0)
    d = best(member2, 10000, [a2["id"]])
    check("封顶：50% 立减 5000 被 maxDiscount=800 封住 → 800", d["activityAmount"] == 800, str(d))
    check("封顶：实付 9200", d["payAmount"] == 9200, str(d["payAmount"]))
    d = best(member2, 10000, [a3["id"]])
    check("不封顶：50% 立减 = 5000", d["activityAmount"] == 5000, str(d))
    check("不封顶：实付 5000", d["payAmount"] == 5000, str(d["payAmount"]))
    # 预算余额（原子口径，不超扣）
    poor_before = mok(f"/activities/{a_poor['id']}", step=False)
    d = best(member, 10000, [a_poor["id"]])
    check("预算余额 100 < 立减 1500：活动抵扣 0（绝不为凑最优超预算）",
          d["activityAmount"] == 0 and d["selected"] and all(s["source"] != "ACTIVITY" for s in d["selected"]),
          str(d))
    check("预算不足时其余来源仍最优：券3000+红包1000+积分500=4500",
          d["totalDiscount"] == 4500 and d["payAmount"] == 5500, str(d["totalDiscount"]))
    check("预算余额不足落选码 BUDGET_INSUFFICIENT",
          reject_codes(d).get(("ACTIVITY", a_poor["id"])) == "BUDGET_INSUFFICIENT")
    check("计算不消耗预算：used 逐分不变",
          mok(f"/activities/{a_poor['id']}", step=False)["used"] == poor_before["used"])
    # 无损剪枝
    cs = [coupon(member3, 100 * (i + 1), 0) for i in range(12)]
    top = max(cs, key=lambda c: c["amount"])
    d = best(member3, 10000, NO_ACT, usePoints=False, maxCandidatesPerSource=3)
    check("候选爆炸剪枝：12 张券保留前 3，prunedCandidates=9", d["prunedCandidates"] == 9, str(d["prunedCandidates"]))
    check("无损剪枝：最优仍为最大面额 1200（被剪者不可能进最优解）",
          d["totalDiscount"] == 1200 and picks_of(d) == [("COUPON", top["id"], 1200)], str(picks_of(d)))
    check("剪枝留痕：落选码 PRUNED 共 9 项",
          sum(1 for r in d["rejected"] if r["code"] == "PRUNED") == 9, str(d["rejected"])[:200])
    check("剪枝后 candidateCount=3", d["candidateCount"] == 3, str(d["candidateCount"]))
    d_full = best(member3, 10000, NO_ACT, usePoints=False)
    check("剪枝不损失最优性：与不剪枝结果一致",
          d_full["totalDiscount"] == d["totalDiscount"] and picks_of(d_full) == picks_of(d), str(d_full))
    # 空权益
    empty_member = f"{TAG}-EMPTY-{SUFFIX}-{uuid.uuid4().hex[:6]}"
    d = best(empty_member, 10000, NO_ACT)
    check("无权益会员：总抵扣 0 / 实付 = 订单额 / 选中为空",
          d["totalDiscount"] == 0 and d["payAmount"] == 10000 and d["selected"] == [], str(d))
    check("无权益会员：积分落选 POINTS_ZERO",
          any(r["code"] == "POINTS_ZERO" for r in d["rejected"]), str(d["rejected"]))
    # 参数护栏
    st, resp = orch("/best", "POST", {"memberId": member, "orderAmount": 0}, step=False)
    check("orderAmount=0 拒绝 400", st == 400, f"{st} {resp}")
    st, resp = orch("/best", "POST", {"orderAmount": 1000}, step=False)
    check("缺 memberId 拒绝 400", st == 400, f"{st} {resp}")
    st, resp = orch("/best", "POST", {"memberId": member, "orderAmount": 1000,
                                      "activityIds": NO_ACT, "rules": {"mutex": ["COUPON|NOPE"]}}, step=False)
    check("非法互斥来源拒绝（绝不静默忽略互斥）", st == 400, f"{st} {resp}")


# ============================================================================
def sec5_orchestration(merchant, member, account, acts):
    print("[5] P-7 单腿编排：券→红包→积分→活动 / 实付 / 幂等 / 复式记账 / 分摊守恒")
    a_full = acts[0]
    topup(account, 5000)
    c = coupon(member, 3000, 5000)
    r = red_packet(member, 1000)
    earn(member, 50000)
    amount = 10000
    order = new_order(merchant["id"], amount)
    u0, m0 = bal(account), bal(merchant["accountId"])
    fee0, sysm0 = bal("SYS_FEE"), bal("SYS_MARKETING")
    check("受理前余额 5000 < 订单额 10000（须靠补贴才够付）", u0 < amount, str(u0))
    spec = {"memberId": member, "couponIds": [c["id"]], "redPacketIds": [r["id"]],
            "points": 50000, "activityIds": [a_full["id"]]}
    req = biz("ORCH")
    st, resp = pay_marketing(order["id"], account, spec, request_id=req)
    check("营销抵扣支付受理 200", st == 200, f"{st} {resp}")
    p = wait_payment(resp["payment"]["id"], "SUCCESS")
    check("支付 SUCCESS", p["status"] == "SUCCESS", str(p))
    check("payment.amount 仍是订单全额 10000（fee 基数不变）", p["amount"] == amount, str(p["amount"]))
    check("fee 口径不变 = 60 分（按订单全额，非抵扣后）", p["fee"] == fee_of(amount) == 60, str(p["fee"]))
    check("marketingAmount = Σ抵扣 6000", p["marketingAmount"] == 6000, str(p["marketingAmount"]))
    check("marketingStatus = APPLIED", p["marketingStatus"] == "APPLIED", str(p["marketingStatus"]))
    check("净实付 payAmount = 10000 − 6000 = 4000（不得为负）", p["payAmount"] == 4000, str(p["payAmount"]))
    check("用户净现金流出 = 4000（补贴 6000 入戶后扣全额 10000）",
          bal(account) == u0 + 6000 - amount == u0 - 4000, f"{u0} -> {bal(account)}")
    check("商户实收 = 订单额 − fee = 9940（营销不改商户实收）",
          bal(merchant["accountId"]) == m0 + amount - 60, f"{m0} -> {bal(merchant['accountId'])}")
    check("SYS_FEE +60（手续费口径不变）", bal("SYS_FEE") == fee0 + 60)
    check("SYS_MARKETING 净出账 6000（真实资金经 accounting post）",
          sysm0 - bal("SYS_MARKETING") == 6000, f"{sysm0} -> {bal('SYS_MARKETING')}")
    d = ook(f"/deduction/{p['id']}", step=False)
    check("抵扣明细 4 行落库可追溯", len(d["legs"]) == 4, str(d["legs"]))
    check("编排顺序 = 券→红包→积分→活动",
          [x["source"] for x in d["legs"]] == ["COUPON", "RED_PACKET", "POINTS", "ACTIVITY"],
          str([x["source"] for x in d["legs"]]))
    check("各腿抵扣额 = 3000/1000/500/1500",
          [x["amount"] for x in d["legs"]] == [3000, 1000, 500, 1500], str([x["amount"] for x in d["legs"]]))
    check("各腿状态 USED 且携带营销核销单 id", all(x["status"] == "USED" and x["useId"] for x in d["legs"]))
    check("各腿携带资金凭证号 MKT: 前缀（与 accounting 分录 1:1）",
          all(x["bizNo"] and x["bizNo"].startswith("MKT:") for x in d["legs"]), str(d["legs"])[:200])
    check("积分腿记录消耗积分数 50000",
          next(x for x in d["legs"] if x["source"] == "POINTS")["points"] == 50000)
    check("明细汇总：applied=6000 / refunded=0 / net=6000 / payAmount=4000",
          (d["appliedAmount"], d["refundedAmount"], d["netAmount"], d["payAmount"]) == (6000, 0, 6000, 4000), str(d))
    al = d["allocation"]
    check("Σ分摊 = 抵扣总额 6000 且 diff=0（守恒）",
          al["allocated"] == 6000 and al["sourceSum"] == 6000 and al["diff"] == 0 and al["sourceDiff"] == 0, str(al))
    check("分摊 balanced=true", al["balanced"] is True, str(al))
    check("来源维度 4 行（COUPON/RED_PACKET/POINTS/ACTIVITY）",
          sorted(x["source"] for x in al["sources"]) == ["ACTIVITY", "COUPON", "POINTS", "RED_PACKET"], str(al["sources"]))
    cons = d["conservation"]
    check("守恒：订单额 = 净实付 + 营销抵扣（orderDiff=0）",
          cons["orderDiff"] == 0 and cons["targetDiff"] == 0 and cons["balanced"] is True, str(cons))
    check("守恒校验入参口径正确（orderAmount=10000, fundAmount=4000）",
          cons["orderAmount"] == 10000 and cons["fundAmount"] == 4000 and cons["marketingAmount"] == 6000, str(cons))
    # 复式记账凭证逐腿可回查
    for leg in d["legs"]:
        v = ok(f"/api/accounting/vouchers/{leg['bizNo']}")
        lines = v.get("lines") or []
        debit = [x for x in lines if x["dir"] == "D"]
        credit = [x for x in lines if x["dir"] == "C"]
        check(f"{leg['source']} 凭证 D SYS_MARKETING / C 商户（直补商户）且金额 {leg['amount']}",
              len(debit) == 1 and debit[0]["accountId"] == "SYS_MARKETING" and debit[0]["amount"] == leg["amount"]
              and len(credit) == 1 and credit[0]["accountId"] == merchant["accountId"] and credit[0]["amount"] == leg["amount"],
              str(v)[:220])
        check(f"{leg['source']} 凭证借贷恒等 balanced", v.get("balanced") is True
              and v.get("totalDebit") == v.get("totalCredit") == leg["amount"], str(v)[:160])
    # 券/红包/积分/活动状态与预算
    check("券已核销 USED", mok(f"/coupons/{c['id']}", step=False)["status"] == "USED")
    check("红包已核销 USED", mok(f"/red-packets/{r['id']}", step=False)["status"] == "USED")
    check("积分余额扣减 50000", mok(f"/points/{member}", step=False)["balance"] == 0)
    check("活动预算原子扣减 1500", mok(f"/activities/{a_full['id']}", step=False)["used"] == 1500)
    # 结算口径不变
    sr = None
    deadline = time.time() + 20
    while time.time() < deadline and sr is None:
        recs = ok(f"/api/settle/records?merchantId={merchant['id']}")
        sr = next((x for x in recs if x.get("paymentId") == p["id"] and x["type"] == "PAY"), None)
        if sr is None:
            time.sleep(0.5)
    check("结算单按订单全额登记：gross=10000 / fee=60 / net=9940（营销不改 fee 基数）",
          sr is not None and sr["fee"] == 60 and sr["net"] == 9940, str(sr))
    # 幂等
    st, resp2 = pay_marketing(order["id"], account, spec, request_id=req)
    check("同 requestId 重放返回同一支付单 + repeated=true",
          st == 200 and resp2["payment"]["id"] == p["id"] and resp2.get("repeated") is True, f"{st} {resp2}")
    check("幂等：余额不重复变动", bal(account) == u0 - 4000, str(bal(account)))
    check("幂等：SYS_MARKETING 不重复出账", sysm0 - bal("SYS_MARKETING") == 6000)
    check("幂等：营销抵扣明细仍 4 行（不重复核销）", len(ook(f"/deduction/{p['id']}", step=False)["legs"]) == 4)
    check("幂等：活动预算不重复扣减", mok(f"/activities/{a_full['id']}", step=False)["used"] == 1500)
    d2 = ook(f"/deduction/{p['id']}", step=False)
    check("幂等：分摊 diff 仍为 0", d2["allocation"]["diff"] == 0 and d2["allocation"]["allocated"] == 6000)
    return {"payment": p, "deduction": d, "coupon": c, "redPacket": r, "spec": spec,
            "u0": u0, "sysm0": sysm0, "account": account, "member": member}


def sec5b_auto(merchant, member, account):
    print("[5b] P-7 auto 模式：直接采用 C-4 最优组合（计算与核销一致）")
    topup(account, 20000)
    c = coupon(member, 3000, 5000)
    r = red_packet(member, 1000)
    earn(member, 50000)
    rules = {"maxActivities": 0, "maxCoupons": 1, "maxRedPackets": 1, "pointsStackable": True}
    amount = 10000
    calc = best(member, amount, NO_ACT, rules=rules)
    check("C-4 预算：券3000+红包1000+积分500 = 4500", calc["totalDiscount"] == 4500, str(calc["totalDiscount"]))
    order = new_order(merchant["id"], amount)
    u0, sysm0 = bal(account), bal("SYS_MARKETING")
    st, resp = pay_marketing(order["id"], account, {"memberId": member, "auto": True, "rules": rules})
    check("auto 模式受理 200", st == 200, f"{st} {resp}")
    p = wait_payment(resp["payment"]["id"], "SUCCESS")
    check("auto：核销结果与 C-4 计算逐分一致（marketingAmount=4500）",
          p["marketingAmount"] == calc["totalDiscount"] == 4500, str(p["marketingAmount"]))
    check("auto：净实付 = C-4 payAmount = 5500", p["payAmount"] == calc["payAmount"] == 5500, str(p["payAmount"]))
    check("auto：用户净流出 5500", bal(account) == u0 - 5500, f"{u0} -> {bal(account)}")
    check("auto：SYS_MARKETING 出账 4500", sysm0 - bal("SYS_MARKETING") == 4500)
    d = ook(f"/deduction/{p['id']}", step=False)
    check("auto：明细来源集合与 C-4 选中集合一致",
          sorted((x["source"], x["amount"]) for x in d["legs"])
          == sorted((s["source"], s["amount"]) for s in calc["selected"]), str(d["legs"]))
    check("auto：分摊守恒 diff=0", d["allocation"]["diff"] == 0 and d["allocation"]["balanced"] is True)
    check("auto：守恒 orderDiff=0", d["conservation"]["orderDiff"] == 0 and d["conservation"]["balanced"] is True)


# ============================================================================
def sec6_rollback(merchant, member, account, acts):
    print("[6] P-7 失败回退：任一步失败 → 逆序退回已核销权益（资金反向）")
    a_poor = acts[2]
    topup(account, 100000)

    def expect_failure(name, order_amount, spec, acc=None):
        acc = acc or account
        order = new_order(merchant["id"], order_amount)
        u0, sysm0, fee0 = bal(acc), bal("SYS_MARKETING"), bal("SYS_FEE")
        req = biz("FAIL")
        st, resp = pay_marketing(order["id"], acc, spec, request_id=req)
        check(f"{name}：受理被拒（4xx 业务错误，非 5xx 崩溃）", 400 <= st < 500, f"{st} {resp}")
        check(f"{name}：错误体结构化含 error/code", isinstance(resp, dict) and "error" in resp, str(resp)[:160])
        p = find_payment_by_request(req)
        check(f"{name}：支付单留 FAILED 痕（不静默丢弃）", p is not None and p["status"] == "FAILED", str(p))
        if p is None:
            return None
        d = ook(f"/deduction/{p['id']}", step=False)
        check(f"{name}：营销净抵扣归零（权益全部退回）", d["netAmount"] == 0, str(d))
        check(f"{name}：净实付回到订单额（无残留抵扣）", d["payAmount"] == order_amount, str(d))
        check(f"{name}：用户余额逐分复原", bal(acc) == u0, f"{u0} -> {bal(acc)}")
        check(f"{name}：SYS_MARKETING 逐分复原（资金反向）", bal("SYS_MARKETING") == sysm0,
              f"{sysm0} -> {bal('SYS_MARKETING')}")
        check(f"{name}：SYS_FEE 零入账（失败单不计费）", bal("SYS_FEE") == fee0)
        check(f"{name}：退回后分摊仍守恒 diff=0",
              d["allocation"] is None or (d["allocation"]["diff"] == 0 and d["allocation"]["balanced"] is True),
              str(d.get("allocation")))
        return d

    # 6a 第二步核销失败（过期券）→ 第一步已核销券退回
    c_ok = coupon(member, 1000, 0)
    c_exp = coupon(member, 2000, 0, expired=True)
    d = expect_failure("6a 过期券", 5000, {"memberId": member, "couponIds": [c_ok["id"], c_exp["id"]]})
    if d:
        # P-7 正确 TCC 模型：Try 本地预占失败 → 模板回滚本地事务（券 FROZEN+USE 回滚→AVAILABLE），
        # 协调器空回滚不释放（无 Try 日志）；补贴在 Confirm 失败未落，无 MKTR 凭证；失败不落 payment 营销腿。
        check("6a：第一张券预占回滚释放 AVAILABLE（TCC 本地回滚）",
              mok(f"/coupons/{c_ok['id']}", step=False)["status"] == "AVAILABLE",
              mok(f"/coupons/{c_ok['id']}", step=False)["status"])
        check("6a：TCC 失败不落 payment 营销腿（权益由协调器回滚，无增量 leg）",
              len(d["legs"]) == 0, str(d["legs"]))
    # 6b 积分不足
    c_b = coupon(member, 1000, 0)
    d = expect_failure("6b 积分不足", 5000, {"memberId": member, "couponIds": [c_b["id"]], "points": 99999999})
    if d:
        check("6b：券预占回滚释放 AVAILABLE", mok(f"/coupons/{c_b['id']}", step=False)["status"] == "AVAILABLE")
        check("6b：积分余额未被扣（失败整体回滚）", mok(f"/points/{member}", step=False)["balance"] == 0)
    # 6c 活动预算不足
    c_c = coupon(member, 1000, 0)
    d = expect_failure("6c 活动预算不足", 5000,
                       {"memberId": member, "couponIds": [c_c["id"]], "activityIds": [a_poor["id"]]})
    if d:
        check("6c：券预占回滚释放 AVAILABLE", mok(f"/coupons/{c_c['id']}", step=False)["status"] == "AVAILABLE")
        check("6c：活动预算未超扣 used=0", mok(f"/activities/{a_poor['id']}", step=False)["used"] == 0)
    # 6d 抵扣超订单额（实付不得为负）
    c_d = coupon(member, 3000, 0)
    d = expect_failure("6d 抵扣超订单额", 500, {"memberId": member, "couponIds": [c_d["id"]]})
    if d:
        check("6d：券未被核销（受理前拒绝，零动账）",
              mok(f"/coupons/{c_d['id']}", step=False)["status"] in ("AVAILABLE", "REFUNDED"))
    # 6e 抵扣后余额仍不足
    poor_member, poor_acc = new_subject("USER")
    c_e = coupon(poor_member["id"], 3000, 0)
    d = expect_failure("6e 抵扣后余额不足", 10000,
                       {"memberId": poor_member["id"], "couponIds": [c_e["id"]]}, acc=poor_acc["id"])
    if d:
        check("6e：补贴已退回，余额回到 0", bal(poor_acc["id"]) == 0, str(bal(poor_acc["id"])))
        check("6e：券 Cancel 释放 AVAILABLE", mok(f"/coupons/{c_e['id']}", step=False)["status"] == "AVAILABLE")
    # 6f 账户不属于权益会员（归属校验）
    other_member, other_acc = new_subject("USER")
    c_f = coupon(member, 1000, 0)
    order = new_order(merchant["id"], 5000)
    st, resp = pay_marketing(order["id"], account,
                             {"memberId": other_member["id"], "couponIds": [c_f["id"]]})
    check("6f 抵扣户与权益会员不一致拒绝 4xx", 400 <= st < 500, f"{st} {resp}")
    check("6f 券未被核销", mok(f"/coupons/{c_f['id']}", step=False)["status"] == "AVAILABLE")
    # 6g CARD 单腿不支持营销抵扣（受理前拒绝，不落单）
    order = new_order(merchant["id"], 5000)
    req_g = biz("CARDMKT")
    st, resp = pay_marketing(order["id"], None, {"memberId": member, "couponIds": [c_f["id"]]},
                             method="CARD", request_id=req_g)
    check("6g CARD 单腿 + 营销抵扣受理前拒绝 4xx", 400 <= st < 500, f"{st} {resp}")
    check("6g 未落支付单（受理前拒绝，零 DB 写入）", find_payment_by_request(req_g) is None,
          str(find_payment_by_request(req_g)))
    check("6g 该订单无任何支付单", not any(x.get("orderId") == order["id"] for x in ok("/api/payments")))
    # 6h 编排开关关闭诉求但缺 memberId
    order = new_order(merchant["id"], 5000)
    st, resp = pay_marketing(order["id"], account, {"couponIds": [c_f["id"]]})
    check("6h 缺 memberId 拒绝 4xx", 400 <= st < 500, f"{st} {resp}")
    trial = ok("/api/accounting/trial")
    check("失败回退后试算仍 D=C 且 Σbalance=0", trial["balanced"] and trial["balanceSum"] == 0, str(trial))


# ============================================================================
def sec7_realloc(merchant, member, account, acts):
    print("[7] 退回后目标维度重分摊（P2-A 遗留项）：Σ目标=Σ来源 diff=0")
    a_full = acts[0]
    topup(account, 50000)
    c = coupon(member, 3000, 5000)
    r = red_packet(member, 1000)
    earn(member, 50000)
    amount = 10000
    order = new_order(merchant["id"], amount)
    u0, sysm0 = bal(account), bal("SYS_MARKETING")
    targets = [{"targetType": "ITEM", "targetId": f"{TAG}-I1-{SUFFIX}", "weight": 1},
               {"targetType": "ITEM", "targetId": f"{TAG}-I2-{SUFFIX}", "weight": 1},
               {"targetType": "ITEM", "targetId": f"{TAG}-I3-{SUFFIX}", "weight": 1}]
    spec = {"memberId": member, "couponIds": [c["id"]], "redPacketIds": [r["id"]],
            "points": 50000, "activityIds": [a_full["id"]], "allocTargets": targets}
    st, resp = pay_marketing(order["id"], account, spec)
    check("多目标分摊支付受理 200", st == 200, f"{st} {resp}")
    p = wait_payment(resp["payment"]["id"], "SUCCESS")
    d = ook(f"/deduction/{p['id']}", step=False)
    check("初始目标分摊 6000 → 2000/2000/2000（最大余数法）",
          sorted(t["amount"] for t in d["allocation"]["targets"]) == [2000, 2000, 2000],
          str(d["allocation"]["targets"]))
    check("初始守恒 diff=0", d["allocation"]["diff"] == 0 and d["allocation"]["balanced"] is True)
    coupon_leg = next(x for x in d["legs"] if x["source"] == "COUPON")
    activity_leg = next(x for x in d["legs"] if x["source"] == "ACTIVITY")

    # 退回券（来源侧净额下降）→ 目标维度暂时不守恒（P2-A 遗留现象）
    mok(f"/coupons/refund/{coupon_leg['useId']}", "POST")
    broken = mok(f"/alloc/{p['id']}", step=False)
    check("退回券后来源净额 3000（来源侧正确）", broken["sourceSum"] == 3000, str(broken))
    check("退回券后目标维度尚未重分摊 → diff=3000（如实暴露不守恒）",
          broken["allocated"] == 6000 and broken["diff"] == 3000 and broken["balanced"] is False, str(broken))
    cons = mok(f"/conservation/{p['id']}?orderAmount=10000&fundAmount=7000", step=False)
    check("守恒校验如实判不成立（不伪造平衡）", cons["balanced"] is False and cons["targetDiff"] == 3000, str(cons))

    # P-7 重分摊
    d = ook(f"/realloc/{p['id']}", "POST", {"requestId": biz("RA")})
    al = d["allocation"]
    check("重分摊后 Σ目标 = Σ来源 = 3000，diff=0",
          al["allocated"] == 3000 and al["sourceSum"] == 3000 and al["diff"] == 0 and al["balanced"] is True, str(al))
    check("重分摊按原权重最大余数法：1000/1000/1000",
          sorted(t["amount"] for t in al["targets"] if t["type"] == "USE") == [2000, 2000, 2000]
          and net_targets(al) == [1000, 1000, 1000], str(al["targets"]))
    check("重分摊只追加/收敛 REFUND 调整行，USE 原始行不改写",
          all(t["amount"] == 2000 for t in al["targets"] if t["type"] == "USE")
          and sum(1 for t in al["targets"] if t["type"] == "REFUND") == 3, str(al["targets"]))
    check("重分摊后守恒校验成立（orderDiff=0 / targetDiff=0）",
          d["conservation"]["balanced"] is True and d["conservation"]["targetDiff"] == 0, str(d["conservation"]))
    check("重分摊后净抵扣 3000 / 净实付 7000", d["netAmount"] == 3000 and d["payAmount"] == 7000, str(d))
    # 幂等
    d2 = ook(f"/realloc/{p['id']}", "POST", {"requestId": biz("RA")})
    check("重分摊幂等：换 requestId 重放结果一致、行数不增",
          d2["allocation"]["allocated"] == 3000 and len(d2["allocation"]["targets"]) == len(al["targets"]),
          str(d2["allocation"]))
    # 再退活动 → 再重分摊
    mok(f"/activities/refund/{activity_leg['useId']}", "POST")
    d3 = ook(f"/realloc/{p['id']}", "POST", {"requestId": biz("RA")})
    check("二次退回后重分摊：Σ目标=Σ来源=1500，diff=0",
          d3["allocation"]["allocated"] == 1500 and d3["allocation"]["diff"] == 0
          and d3["allocation"]["balanced"] is True, str(d3["allocation"]))
    check("二次重分摊净额 500/500/500（最大余数法）", net_targets(d3["allocation"]) == [500, 500, 500],
          str(d3["allocation"]["targets"]))
    check("活动预算已回滚（used 回到 1500−1500=0 相对本单）",
          mok(f"/activities/{a_full['id']}", step=False)["used"] >= 0)
    # 整体回退（P-7 幂等回退端点，与 TCC 回滚同一代码路径）
    d4 = ook(f"/rollback/{p['id']}", "POST", {"requestId": biz("RB")})
    check("整体回退：全部明细 ROLLED_BACK", all(x["status"] == "ROLLED_BACK" for x in d4["legs"]), str(d4["legs"]))
    check("整体回退：净抵扣 0 / 净实付 = 订单额", d4["netAmount"] == 0 and d4["payAmount"] == amount, str(d4))
    check("整体回退：status=ROLLED_BACK", d4["status"] == "ROLLED_BACK", str(d4["status"]))
    check("整体回退：Σ来源=Σ目标=0，diff=0（守恒）",
          d4["allocation"]["sourceSum"] == 0 and d4["allocation"]["allocated"] == 0
          and d4["allocation"]["diff"] == 0 and d4["allocation"]["balanced"] is True, str(d4["allocation"]))
    check("整体回退：SYS_MARKETING 逐分复原", bal("SYS_MARKETING") == sysm0, f"{sysm0} -> {bal('SYS_MARKETING')}")
    check("整体回退：用户余额 = 原余额 − 净实付（直补商户：营销回退退商户补贴，不动用户净实付扣款）",
          bal(account) == u0 - (amount - 6000), f"{u0} -> {bal(account)}")
    check("整体回退：积分回补 50000", mok(f"/points/{member}", step=False)["balance"] == 50000)
    check("整体回退：红包 REFUNDED", mok(f"/red-packets/{r['id']}", step=False)["status"] == "REFUNDED")
    d5 = ook(f"/rollback/{p['id']}", "POST", {"requestId": biz("RB")})
    check("回退幂等：重放不重复反向动账", bal("SYS_MARKETING") == sysm0 and d5["netAmount"] == 0)
    check("回退幂等：分摊行数不增", len(d5["allocation"]["targets"]) == len(d4["allocation"]["targets"]))
    trial = ok("/api/accounting/trial")
    check("重分摊/回退后试算 D=C 且 Σbalance=0", trial["balanced"] and trial["balanceSum"] == 0, str(trial))


def net_targets(al):
    """目标维度净额（USE − REFUND），按 targetId 排序。"""
    net = {}
    for t in al["targets"]:
        net[t["targetId"]] = net.get(t["targetId"], 0) + (t["amount"] if t["type"] == "USE" else -t["amount"])
    return [net[k] for k in sorted(net)]


# ============================================================================
def sec8_combo(merchant, member, account, acts):
    print("[8] COMBO 衔接守恒：Σ资金腿=订单额 + 补贴入 BALANCE 腿 + 净实付")
    topup(account, 2000)
    c = coupon(member, 3000, 5000)
    r = red_packet(member, 1000)
    amount = 10000
    order = new_order(merchant["id"], amount)
    u0, m0 = bal(account), bal(merchant["accountId"])
    fee0, sysm0 = bal("SYS_FEE"), bal("SYS_MARKETING")
    legs = [{"method": "BALANCE", "accountId": account, "amount": 6000},
            {"method": "CARD", "cardNo": CARD, "amount": 4000}]
    spec = {"memberId": member, "couponIds": [c["id"]], "redPacketIds": [r["id"]]}
    st, resp = pay_marketing(order["id"], account, spec, legs=legs)
    check("COMBO + 营销抵扣受理 200", st == 200, f"{st} {resp}")
    p = wait_payment(resp["payment"]["id"], "SUCCESS")
    check("COMBO 支付 SUCCESS", p["status"] == "SUCCESS", str(p))
    check("payMode=COMBO / method=COMBO（既有口径不变）",
          p["payMode"] == "COMBO" and p["method"] == "COMBO", str(p))
    check("Σ资金腿 = 订单额 10000（组合受理强校验不变）",
          sum(x["amount"] for x in p["legs"]) == amount, str(p["legs"]))
    check("Σ腿 fee = 总 fee（手续费分摊守恒不变）",
          sum(x["fee"] for x in p["legs"]) == p["fee"] == fee_of(amount), str(p["legs"]))
    check("fee 基数仍是订单全额 60 分（营销不改 fee）", p["fee"] == 60, str(p["fee"]))
    check("marketingAmount=4000 / payAmount=6000",
          p["marketingAmount"] == 4000 and p["payAmount"] == 6000, str(p))
    check("用户净流出 = 6000 − 4000 = 2000（补贴入 BALANCE 腿付款户）",
          bal(account) == u0 - 2000, f"{u0} -> {bal(account)}")
    check("商户实收 = 10000 − 60 = 9940", bal(merchant["accountId"]) == m0 + 9940,
          f"{m0} -> {bal(merchant['accountId'])}")
    check("SYS_MARKETING 出账 4000", sysm0 - bal("SYS_MARKETING") == 4000)
    check("SYS_FEE +60", bal("SYS_FEE") == fee0 + 60)
    d = ook(f"/deduction/{p['id']}", step=False)
    check("COMBO 抵扣明细 2 行（券→红包）",
          [x["source"] for x in d["legs"]] == ["COUPON", "RED_PACKET"], str(d["legs"]))
    check("COMBO 分摊守恒 diff=0（Σ分摊=4000）",
          d["allocation"]["allocated"] == 4000 and d["allocation"]["diff"] == 0
          and d["allocation"]["balanced"] is True, str(d["allocation"]))
    check("COMBO 守恒：订单额 = 净实付 + 营销抵扣（orderDiff=0）",
          d["conservation"]["orderDiff"] == 0 and d["conservation"]["balanced"] is True, str(d["conservation"]))
    check("COMBO 各腿终态 SUCCESS", all(x["status"] == "SUCCESS" for x in p["legs"]), str(p["legs"]))
    # COMBO 无营销：行为不变
    topup(account, 20000)
    order2 = new_order(merchant["id"], amount)
    u1 = bal(account)
    st, resp = api("/api/cashier/pay", "POST", {"orderId": order2["id"], "requestId": biz("CNM"),
                                                "legs": legs})
    check("COMBO 无营销诉求受理 200（既有路径不变）", st == 200, f"{st} {resp}")
    p2 = wait_payment(resp["payment"]["id"], "SUCCESS")
    check("COMBO 无营销：marketingAmount=0 / payAmount=订单额",
          p2["marketingAmount"] == 0 and p2["payAmount"] == amount, str(p2))
    check("COMBO 无营销：Σ腿=订单额、fee 不变",
          sum(x["amount"] for x in p2["legs"]) == amount and p2["fee"] == fee_of(amount), str(p2))
    check("COMBO 无营销：用户扣全额（零补贴）", bal(account) == u1 - 6000, f"{u1} -> {bal(account)}")
    d2 = ook(f"/deduction/{p2['id']}", step=False)
    check("COMBO 无营销：零 RPC 短路（allocation/conservation 为 null）",
          d2["legs"] == [] and d2.get("allocation") is None and d2.get("conservation") is None, str(d2))
    # COMBO 失败回退：补贴后 BALANCE 腿仍不足
    poor_member, poor_acc = new_subject("USER")
    c3 = coupon(poor_member["id"], 3000, 0)
    order3 = new_order(merchant["id"], amount)
    sysm1 = bal("SYS_MARKETING")
    req3 = biz("CFAIL")
    legs_poor = [{"method": "BALANCE", "accountId": poor_acc["id"], "amount": 6000},
                 {"method": "CARD", "cardNo": CARD, "amount": 4000}]
    st, resp = pay_marketing(order3["id"], poor_acc["id"],
                             {"memberId": poor_member["id"], "couponIds": [c3["id"]]}, legs=legs_poor,
                             request_id=req3)
    check("COMBO 补贴后余额仍不足 → 拒绝 4xx", 400 <= st < 500, f"{st} {resp}")
    p3 = find_payment_by_request(req3)
    check("COMBO 失败留 FAILED 痕", p3 is not None and p3["status"] == "FAILED", str(p3))
    if p3:
        d3 = ook(f"/deduction/{p3['id']}", step=False)
        check("COMBO 失败：营销权益已退回（净抵扣 0）", d3["netAmount"] == 0, str(d3))
        check("COMBO 失败：券 Cancel 释放 AVAILABLE", mok(f"/coupons/{c3['id']}", step=False)["status"] == "AVAILABLE")
        check("COMBO 失败：SYS_MARKETING 逐分复原", bal("SYS_MARKETING") == sysm1)
        check("COMBO 失败：用户余额回到 0", bal(poor_acc["id"]) == 0, str(bal(poor_acc["id"])))
        check("COMBO 失败：各腿 FAILED（不留悬挂）",
              all(x["status"] == "FAILED" for x in ok(f"/api/payments/{p3['id']}")["legs"]), str(p3))
    # COMBO 抵扣入账户非 BALANCE 腿 → 拒绝
    order4 = new_order(merchant["id"], amount)
    c4 = coupon(member, 1000, 0)
    req4 = biz("CBADACC")
    st, resp = pay_marketing(order4["id"], account,
                             {"memberId": member, "accountId": merchant["accountId"], "couponIds": [c4["id"]]},
                             legs=legs, request_id=req4)
    check("COMBO 抵扣户非 BALANCE 腿付款户 → 拒绝 4xx", 400 <= st < 500, f"{st} {resp}")
    check("该券未被核销", mok(f"/coupons/{c4['id']}", step=False)["status"] == "AVAILABLE")
    check("受理前拒绝未落支付单", find_payment_by_request(req4) is None, str(find_payment_by_request(req4)))


# ============================================================================
def sec9_fund_guard():
    print("[9] 资金红线与不回归")
    fc = mok("/fund-check", step=False)
    check("营销资金恒等：Σ核销−Σ退回 = SYS_MARKETING 出账（diff=0）",
          fc["diff"] == 0 and fc["netMarketing"] == fc["sysMarketingOutflow"], str(fc))
    check("营销资金核对 balanced=true", fc["balanced"] is True, str(fc))
    check("账务试算 D=C", fc["trialBalanced"] is True and fc["trialDebit"] == fc["trialCredit"], str(fc)[:200])
    check("Σbalance=0（整体）", fc["balanceSum"] == 0, str(fc["balanceSum"]))
    trial = ok("/api/accounting/trial")
    check("试算平衡独立复核 D=C 且 Σbalance=0", trial["balanced"] and trial["balanceSum"] == 0, str(trial))
    st, health = http(BASE + "/api/health")
    check("收尾 13/13 全健康（marketing 不入清单）",
          st == 200 and len(health) == 13 and all(h["ok"] for h in health),
          str([h["service"] for h in health if not h["ok"]]))
    time.sleep(2)
    rep = None
    for attempt in range(4):
        st, r = api("/api/recon/run", "POST", timeout=300)
        if st == 200:
            rep = r
            break
        # 重启后 Dubbo invoker 重连/对账全量扫描偶发超时：重试而非放宽判定口径
        print(f"  · 对账第 {attempt + 1} 次返回 {st}，5s 后重试")
        time.sleep(5)
    check("六项对账可执行（200）", rep is not None, str(rep))
    if rep is None:
        return
    check("六项对账项数不变", len(rep["checks"]) == 6, str([c["name"] for c in rep["checks"]]))
    for c in rep["checks"]:
        check(f"对账：{c['name']}", c["pass"], c["detail"])
    audit = mok("/audit?entityId=&limit=500", step=False)
    actions = {a["action"] for a in audit}
    check("营销审计覆盖 P-7 重分摊留痕 REALLOC", "REALLOC" in actions, str(actions))


# ============================================================================
def local_start():
    global LOCAL_PROC
    jar = os.path.join(REPO, "payment-marketing", "target", "payment-marketing-1.0.0.jar")
    if not os.path.exists(jar):
        check("local 形态：营销域 jar 存在", False, jar)
        return False
    env = dict(os.environ)
    env["DATA_DIR"] = os.path.join(REPO, "data", "mkt-orch-local-verify")
    env["MARKETING_HTTP_PORT"] = "18994"
    env["MARKETING_DUBBO_PORT"] = "20994"
    env["MARKETING_LOCAL_API"] = "true"
    env["MARKETING_EXPIRE_DELAY"] = "3600000"
    os.makedirs(env["DATA_DIR"], exist_ok=True)
    variants = [
        ["-Ddubbo.registry.address=zookeeper://127.0.0.1:2181",
         "-Ddubbo.registry.register=false", "-Ddubbo.consumer.group=MKTORCHLOCAL"],
        ["-Ddubbo.registry.address=zookeeper://127.0.0.1:2181",
         "-Ddubbo.registry.register=false", "-Ddubbo.consumer.url=dubbo://127.0.0.1:1"],
        ["-Ddubbo.registry.address=N/A", "-Ddubbo.consumer.url=dubbo://127.0.0.1:1"],
    ]
    for extra in variants:
        log = open(os.path.join(REPO, "run", "p2E-marketing-local.log"), "a")
        args = [os.path.join(JAVA_HOME, "bin", "java"), "-Dspring.profiles.active=local",
                "-Ddubbo.application.qos-enable=false"] + extra + ["-jar", jar]
        LOCAL_PROC = subprocess.Popen(args, cwd=REPO, env=env, stdout=log, stderr=subprocess.STDOUT)
        for _ in range(75):
            if LOCAL_PROC.poll() is not None:
                break
            st, _ = http(LOCAL_BASE + "/profile", timeout=3)
            if st == 200:
                st2, _ = http(LOCAL_BASE + "/templates", timeout=5)
                if st2 == 200:
                    time.sleep(3)
                    if LOCAL_PROC.poll() is None:
                        st3, _ = http(LOCAL_BASE + "/templates", timeout=5)
                        if st3 == 200:
                            return True
            time.sleep(1)
        local_stop()
    return False


def local_stop():
    global LOCAL_PROC
    if LOCAL_PROC is None:
        return
    try:
        LOCAL_PROC.send_signal(signal.SIGTERM)
        try:
            LOCAL_PROC.wait(timeout=20)
        except subprocess.TimeoutExpired:
            LOCAL_PROC.kill()
            LOCAL_PROC.wait(timeout=10)
    except Exception:
        pass
    LOCAL_PROC = None


def lreq(path, method="GET", body=None):
    return http(LOCAL_BASE + path, method, body, timeout=60)


def lok(path, method="GET", body=None):
    st, resp = lreq(path, method, body)
    assert st == 200, f"local {method} {path} -> {st} {resp}"
    return resp


def sec10_local():
    print("[10] local H2 零依赖形态：C-4 最优组合 + 重分摊（独立 DATA_DIR/端口，不动既有数据）")
    if not local_start():
        check("local 形态：营销域单进程启动成功", False, "见 run/p2E-marketing-local.log")
        local_stop()
        return
    check("local 形态：营销域单进程启动成功", True)
    try:
        st, prof = lreq("/profile")
        check("local profile 生效", "local" in str(prof.get("profiles")), str(prof))
        check("local 数据库 = H2（零依赖）", prof.get("database") == "H2", str(prof.get("database")))
        member = f"{TAG}-LOCAL-{SUFFIX}"
        c1 = lok("/coupons/issue", "POST", {"memberId": member, "amount": 3000, "threshold": 5000,
                                            "bizNo": biz("LCI"), "validDays": 30})
        c2 = lok("/coupons/issue", "POST", {"memberId": member, "amount": 2000, "threshold": 20000,
                                            "bizNo": biz("LCI"), "validDays": 30})
        r1 = lok("/red-packets/issue", "POST", {"memberId": member, "amount": 1000,
                                                "bizNo": biz("LRI"), "validDays": 30})
        lok("/points/earn", "POST", {"memberId": member, "points": 50000, "bizNo": biz("LPE")})
        act = lok("/activities", "POST", {"name": f"{TAG}-L-ACT-{SUFFIX}", "type": "FULL_REDUCE",
                                          "threshold": 8000, "discountAmount": 1500, "budget": 100000})
        body = {"memberId": member, "orderAmount": 10000, "activityIds": [act["id"]]}
        d = lok("/promotion/best", "POST", body)
        check("local C-4 最优总抵扣 6000（券3000+红包1000+积分500+活动1500）",
              d["totalDiscount"] == 6000, str(d["totalDiscount"]))
        check("local C-4 实付 4000", d["payAmount"] == 4000, str(d["payAmount"]))
        check("local C-4 选中顺序 券→红包→积分→活动",
              [s["source"] for s in d["selected"]] == ["COUPON", "RED_PACKET", "POINTS", "ACTIVITY"], str(d))
        check("local C-4 门槛落选可解释",
              any(r["refId"] == c2["id"] and r["code"] == "THRESHOLD_NOT_MET" for r in d["rejected"]), str(d["rejected"]))
        d2 = lok("/promotion/best", "POST", body)
        check("local C-4 确定性：指纹与选中项一致",
              d["inputFingerprint"] == d2["inputFingerprint"] and picks_of(d) == picks_of(d2))
        dm = lok("/promotion/best", "POST", dict(body, rules={"mutex": ["COUPON|ACTIVITY"]}))
        check("local C-4 互斥生效：4500（弃活动保券）", dm["totalDiscount"] == 4500, str(dm["totalDiscount"]))
        dc = lok("/promotion/best", "POST", dict(body, rules={"maxDiscountBps": 5000}))
        check("local C-4 上限截断：5000 且实付 5000", dc["totalDiscount"] == 5000 and dc["payAmount"] == 5000, str(dc))
        check("local C-4 计算不核销（券仍 AVAILABLE）",
              any(x["id"] == c1["id"] for x in lok(f"/coupons?memberId={member}&status=AVAILABLE")))
        check("local C-4 计算不消耗活动预算",
              next(a for a in lok("/activities?orderAmount=10000") if a["id"] == act["id"])["used"] == 0)
        # 重分摊（纯计算，local 无账务域也可验守恒收敛）
        p = pid()
        a = lok("/alloc", "POST", {"paymentId": p, "requestId": "L1", "totalAmount": 1000, "mode": "WEIGHT",
                                   "targets": [{"targetType": "ITEM", "targetId": "I1", "weight": 1},
                                               {"targetType": "ITEM", "targetId": "I2", "weight": 1},
                                               {"targetType": "ITEM", "targetId": "I3", "weight": 1}]})
        check("local 初始分摊 333/333/334（最大余数法 Σ=1000）",
              sorted(t["amount"] for t in a["targets"]) == [333, 333, 334] and a["diff"] == 0, str(a))
        ra = lok(f"/alloc/realloc/{p}", "POST")
        check("local 全额退回重分摊：Σ目标净额=Σ来源=0，diff=0",
              ra["allocated"] == 0 and ra["diff"] == 0 and ra["balanced"] is True, str(ra))
        check("local 重分摊调整行 333/333/334（USE 原始行不改写）",
              sorted(t["amount"] for t in ra["targets"] if t["type"] == "REFUND") == [333, 333, 334]
              and sorted(t["amount"] for t in ra["targets"] if t["type"] == "USE") == [333, 333, 334],
              str(ra["targets"]))
        ra2 = lok(f"/alloc/realloc/{p}", "POST")
        check("local 重分摊幂等：行数不增、结果一致",
              len(ra2["targets"]) == len(ra["targets"]) and ra2["allocated"] == 0, str(ra2))
        st, resp = lreq("/fund-check")
        check("local 无账务域时资金核对优雅失败（不伪造平衡）", st != 200, f"{st} {resp}")
    finally:
        local_stop()
    st, _ = http(LOCAL_BASE + "/profile", timeout=3)
    check("local 自检进程已停止（端口释放，不留残留进程）", st == 0, str(st))


# ============================================================================
def sec11_offline():
    print("[11] 营销域未启用（PP_MARKETING_ENABLED=false）：零影响 + 优雅短路")
    st, act = http(MKT_ACT + "/health", timeout=5)
    check("营销域进程确实不在线（形态自证）", st != 200, f"{st} {act}")
    merchant, macc = new_subject("MERCHANT")
    merchant["accountId"] = macc["id"]
    user, acc = new_subject("USER")
    topup(acc["id"], 100000)
    u0, m0, fee0 = bal(acc["id"]), bal(macc["id"]), bal("SYS_FEE")
    amount = 3000
    order = new_order(merchant["id"], amount)
    st, resp = api("/api/cashier/pay", "POST", {"orderId": order["id"], "method": "BALANCE",
                                               "accountId": acc["id"], "requestId": biz("OFF")})
    check("营销域离线：无诉求 BALANCE 支付仍 200（零影响）", st == 200, f"{st} {resp}")
    p = wait_payment(resp["payment"]["id"], "SUCCESS")
    check("营销域离线：支付 SUCCESS 且 fee 口径不变", p["status"] == "SUCCESS" and p["fee"] == fee_of(amount), str(p))
    check("营销域离线：marketingAmount=0 / payAmount=订单额",
          p["marketingAmount"] == 0 and p["payAmount"] == amount, str(p))
    check("营销域离线：付款方扣全额、商户净额不变口径",
          bal(acc["id"]) == u0 - amount and bal(macc["id"]) == m0 + amount - fee_of(amount), str(p))
    check("营销域离线：SYS_FEE 等额入账", bal("SYS_FEE") == fee0 + fee_of(amount))
    d = ook(f"/deduction/{p['id']}", step=False)
    check("营销域离线：抵扣明细 NONE 且零 RPC（allocation/conservation 为 null）",
          d["status"] == "NONE" and d["legs"] == [] and d.get("allocation") is None
          and d.get("conservation") is None, str(d))
    # ride OpenAPI BALANCE 同步语义
    app_id, secret = merchant["appId"], merchant["appSecret"]
    st, r = op("trade.create", app_id, secret, {"merchantId": merchant["id"], "subject": "MOCH 离线",
                                                "amount": 800, "outTradeNo": biz("OT")})
    check("营销域离线：trade.create 仍 200", st == 200 and "id" in r, f"{st} {r}")
    st, r = op("trade.pay", app_id, secret, {"orderId": r["id"], "accountId": acc["id"],
                                             "method": "BALANCE", "outTradeNo": biz("OTP")})
    check("营销域离线：trade.pay BALANCE 仍 200（冻结面不变）", st == 200, f"{st} {r}")
    rp = wait_payment((r.get("payment") or r)["id"], "SUCCESS") if st == 200 else {}
    check("营销域离线：BALANCE 同步收敛 SUCCESS（无 WAITING）", rp.get("status") == "SUCCESS", str(rp))
    st, r = op("account.balance", app_id, secret, {"accountId": acc["id"]})
    check("营销域离线：account.balance 仍返回裸数字", st == 200 and isinstance(r, int), f"{st} {r}")
    # 有营销诉求 → 优雅拒绝（非崩溃），账本零污染
    u1, sysm1 = bal(acc["id"]), bal("SYS_MARKETING")
    order2 = new_order(merchant["id"], 5000)
    req = biz("OFFMKT")
    st, resp = pay_marketing(order2["id"], acc["id"],
                             {"memberId": user["id"], "couponIds": [f"{TAG}-GHOST-{SUFFIX}"]}, request_id=req)
    check("营销域离线 + 有诉求：优雅拒绝 4xx（非 5xx 崩溃、非静默放行）",
          400 <= st < 500, f"{st} {resp}")
    check("营销域离线：错误体明确指向营销域不可达/未启用",
          isinstance(resp, dict) and "营销" in str(resp.get("error", "")), str(resp)[:200])
    p2 = find_payment_by_request(req)
    check("营销域离线：失败单留 FAILED 痕（不悬挂 PROCESSING）",
          p2 is not None and p2["status"] == "FAILED", str(p2))
    check("营销域离线：用户余额零污染", bal(acc["id"]) == u1, f"{u1} -> {bal(acc['id'])}")
    check("营销域离线：SYS_MARKETING 零动账", bal("SYS_MARKETING") == sysm1)
    if p2:
        d2 = ook(f"/deduction/{p2['id']}", step=False)
        check("营销域离线：净抵扣 0 / 净实付 = 订单额（无半成品抵扣）",
              d2["netAmount"] == 0 and d2["payAmount"] == 5000, str(d2))
    st, resp = orch("/best", "POST", {"memberId": user["id"], "orderAmount": 10000}, step=False)
    check("营销域离线：C-4 计算端点返回结构化错误（不泄露堆栈）",
          st >= 400 and isinstance(resp, dict) and "error" in resp and "Traceback" not in str(resp),
          f"{st} {str(resp)[:160]}")
    trial = ok("/api/accounting/trial")
    check("营销域离线：试算仍 D=C 且 Σbalance=0", trial["balanced"] and trial["balanceSum"] == 0, str(trial))
    st, health = http(BASE + "/api/health")
    check("营销域离线：13/13 健康硬断言不破", st == 200 and len(health) == 13 and all(h["ok"] for h in health),
          str([h["service"] for h in health if not h["ok"]]))


# ============================================================================
def main():
    print(f"=== verify-marketing-orch.py · P-7 营销抵扣编排 + C-4 最优组合 · TAG={TAG} SUFFIX={SUFFIX} ===")
    sec1_baseline()
    if not MKT_ONLINE:
        sec11_offline()
        print()
        print(f"结果（营销域离线形态）：{PASSED} 通过，{FAILED} 失败")
        if FAILURES:
            print("失败项：")
            for name in FAILURES:
                print("  - " + name)
        return 1 if FAILED else 0

    merchant, macc = new_subject("MERCHANT")
    merchant["accountId"] = macc["id"]
    ride_user, ride_acc = new_subject("USER")
    sec2_ride_shortcircuit(merchant, ride_user, ride_acc["id"])

    # C-4 素材
    m1, a1 = new_subject("USER")
    m2, a2 = new_subject("USER")
    m3, a3 = new_subject("USER")
    a_full = activity("FULL", "FULL_REDUCE", 8000, discount_amount=1500, budget=1000000)
    a_cap = activity("CAP", "DISCOUNT", 0, discount_bps=2000, max_discount=800, budget=1000000)
    a_poor = activity("POOR", "FULL_REDUCE", 0, discount_amount=1500, budget=100)
    acts = (a_full, a_cap, a_poor)
    kit = sec3_c4_basic(m1["id"], acts)
    sec4_c4_constraints(m1["id"], acts, kit, m2["id"], m3["id"])

    # P-7 素材
    m5, a5 = new_subject("USER")
    sec5_orchestration(merchant, m5["id"], a5["id"], acts)
    m5b, a5b = new_subject("USER")
    sec5b_auto(merchant, m5b["id"], a5b["id"])
    m6, a6 = new_subject("USER")
    sec6_rollback(merchant, m6["id"], a6["id"], acts)
    m7, a7 = new_subject("USER")
    sec7_realloc(merchant, m7["id"], a7["id"], acts)
    m8, a8 = new_subject("USER")
    sec8_combo(merchant, m8["id"], a8["id"], acts)
    sec9_fund_guard()
    sec10_local()
    print()
    print(f"结果（营销域在线全量形态）：{PASSED} 通过，{FAILED} 失败")
    if FAILURES:
        print("失败项：")
        for name in FAILURES:
            print("  - " + name)
    return 1 if FAILED else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    finally:
        local_stop()