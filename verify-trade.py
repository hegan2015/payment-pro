#!/usr/bin/env python3
# ============================================================================
# payment-pro P1 · G 泳道验证：交易产品工作流 T-3 / T-1 / T-2 / T-4
#
# 用法：docker 全栈在线（./run.sh --docker）后 python3 verify-trade.py
#   [1] ride 红线：openapi trade.create / trade.pay / trade.query 语义与返回逐字节不变
#       （merchant+outTradeNo 唯一幂等、BALANCE 同步受理→PROCESSING→SUCCESS 无 WAITING、
#         手续费 60bps→SYS_FEE、trade.query 订单+支付单供增量对账回查）
#   [2] T-3 多产品路由：显式 productType / bizType 属性 / 商户级覆盖 / 全局缺省；
#       产品开关与参数经 DynamicConfig 热更生效；INSTANT 默认产品行为不变
#   [3] T-1 担保：付款入平台担保户（卖家分文未入）→ 确认收货打款卖家；
#       确认前退款从担保户退买家、确认后禁止再退；超时自动确认（JobHandler+ShedLock）；
#       重复确认幂等；每步复式记账 + 资金守恒 + 担保户余额恒等
#   [4] T-2 预授权：A-3 PREAUTH 冻结（balance 不动、available=balance−frozen）→
#       部分完成差额解冻 → 全额完成 / 撤销全额解冻；超扣拒绝；requestId 幂等不重扣
#   [5] T-4 超时关闭：到期才关、不重不漏（4 片 CRC32 切片 + 并发 ShedLock 互斥）、
#       在途/已支付单不误关、关闭与支付受理竞态守卫（CAS + 防悬挂回滚）、关闭后不可支付、
#       策略动态生效、默认不自动跑批
#   [6] 资金护航：试算 D=C 且 Σbalance=0、六项对账 pass、担保户/预授权冻结恒等
#
# 数据保护：只新增业务行（真实支付/冻结/过账链路），绝不删除/重置任何既有数据；
#           配置文件只增删本脚本自己的 trade.* 键（读-改-写保留他人键），结束恢复原状。
# 断言口径：不放宽、不改库状态凑绿；失败如实记录。
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
import zlib
from concurrent.futures import ThreadPoolExecutor

BASE = "http://127.0.0.1:8080"
TRADE_ACT = "http://127.0.0.1:18084/actuator"
CONFIG_FILE = os.path.expanduser("~/.config/payment-pro/config.properties")
SUFFIX = uuid.uuid4().hex[:6]
TOKEN = None
STEP = None
PASSED = 0
FAILED = 0
FAILURES = []
CONFIG_ORIGINAL = None
CONFIG_EXISTED = False
ADMIN_PASSWORD = os.environ.get("PP_ADMIN_PASSWORD", "admin123")


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


def http(url, method="GET", body=None, bearer=False, step=False, timeout=180):
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


def api(method, path, body=None, step=False):
    st, resp = http(BASE + path, method, body, bearer=True, step=step)
    if st == 401:
        login()
        st, resp = http(BASE + path, method, body, bearer=True, step=step)
    return st, resp


def ok(method, path, body=None, step=False):
    st, resp = api(method, path, body, step=step)
    assert st == 200, f"{method} {path} 期望 200，实际 {st}: {resp}"
    return resp


def act(path="", body=None):
    st, resp = http(TRADE_ACT + ("/" + path if path else ""), "POST" if body is not None else "GET", body)
    assert st == 200, f"{TRADE_ACT}/{path} 期望 200，实际 {st}: {resp}"
    return resp


def ppjob(name, shard_index=0, shard_total=1, param=""):
    # actuator @WriteOperation：非 @Selector 参数须在 body 中出现且非 null
    return http(TRADE_ACT + "/ppjob/" + name, "POST",
                {"shardIndex": shard_index, "shardTotal": shard_total, "param": param})


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
    return poll(lambda: ok("GET", f"/api/payments/{pid}"), lambda p: p["status"] == status, timeout)


def wait_order(oid, status, timeout=60):
    return poll(lambda: ok("GET", f"/api/orders/{oid}"), lambda o: o["status"] == status, timeout)


def order(oid):
    return ok("GET", f"/api/orders/{oid}")


def net_of(payment):
    """担保户/商户净额口径：(已付−已退) − (手续费−已退手续费)。"""
    return (payment["amount"] - (payment.get("refundedAmount") or 0)) \
        - ((payment.get("fee") or 0) - (payment.get("refundedFee") or 0))


def freezes(account_id, status="ACTIVE"):
    return ok("GET", f"/api/accounts/{account_id}/freezes?status={status}")


def frozen_sum(account_id):
    return sum(f["amount"] for f in freezes(account_id) if f["reasonType"] == "PREAUTH")


def trial():
    return ok("GET", "/api/accounting/trial")


def trial_guard(tag):
    t = trial()
    good = check(f"[{tag}] 试算平衡 D=C", t["totalDebit"] == t["totalCredit"], str(t))
    check(f"[{tag}] Σbalance=0", t["balanceSum"] == 0, str(t))
    return good, t


def recon_guard(tag):
    rep = ok("POST", "/api/recon/run", {})
    checks = rep.get("checks", [])
    bad = [c["name"] for c in checks if not c.get("pass")]
    check(f"[{tag}] 六项对账全 pass", len(checks) == 6 and not bad, str(bad) + str(rep)[:200])
    return rep


# ---------------- DynamicConfig（只动本脚本自己的 trade.* 键） ----------------

def config_read():
    if not os.path.exists(CONFIG_FILE):
        return {}
    items = {}
    with open(CONFIG_FILE, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            items[k.strip()] = v.strip()
    return items


def config_write(items):
    current = config_read()
    for k, v in items.items():
        if v is None:
            current.pop(k, None)
        else:
            current[k] = str(v)
    os.makedirs(os.path.dirname(CONFIG_FILE), exist_ok=True)
    with open(CONFIG_FILE, "w", encoding="utf-8") as f:
        for k, v in current.items():
            f.write(f"{k}={v}\n")
    act("pptrade", {"action": "refresh", "shardIndex": 0, "shardTotal": 1})


def restore_config():
    current = config_read()
    for k in list(current):
        if k.startswith("trade."):
            current.pop(k, None)
    if CONFIG_ORIGINAL:
        for k, v in CONFIG_ORIGINAL.items():
            if k.startswith("trade."):
                current[k] = v
    if current:
        os.makedirs(os.path.dirname(CONFIG_FILE), exist_ok=True)
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            for k, v in current.items():
                f.write(f"{k}={v}\n")
    elif CONFIG_EXISTED and os.path.exists(CONFIG_FILE):
        os.remove(CONFIG_FILE)
    try:
        act("pptrade", {"action": "refresh", "shardIndex": 0, "shardTotal": 1})
    except Exception:
        pass


# ---------------- OpenAPI（ride 调用面，HMAC-SHA256） ----------------

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


def pay_raw(order_id, account_id, request_id, method="BALANCE"):
    """受理可能因风控/可用余额/TCC Try 失败而返回 4xx（支付单仍落库并收敛 FAILED）。"""
    return api("POST", "/api/cashier/pay",
               {"orderId": order_id, "method": method, "accountId": account_id, "requestId": request_id})


def payment_by_request(request_id):
    for p in ok("GET", "/api/payments"):
        if p.get("requestId") == request_id:
            return p
    return None


def wait_payment_by_request(request_id, status, timeout=60):
    return poll(lambda: payment_by_request(request_id),
                lambda p: p is not None and p["status"] == status, timeout)


def pay(order_id, account_id, request_id, method="BALANCE"):
    return ok("POST", "/api/cashier/pay",
              {"orderId": order_id, "method": method, "accountId": account_id, "requestId": request_id})


def main():
    global CONFIG_ORIGINAL, CONFIG_EXISTED
    print(f"== payment-pro 交易产品工作流验证 T-3/T-1/T-2/T-4（批次 {SUFFIX}）==")
    login()
    CONFIG_EXISTED = os.path.exists(CONFIG_FILE)
    CONFIG_ORIGINAL = config_read()

    health = poll(lambda: ok("GET", "/api/health"), lambda h: all(x["ok"] for x in h), timeout=90, interval=2)
    if not check("13 个进程在线", len(health) == 13 and all(x["ok"] for x in health), str(health)[:200]):
        print("!! 全栈不健康，停止（不做任何资金操作）")
        return
    base_trial = trial()
    if not (base_trial["totalDebit"] == base_trial["totalCredit"] and base_trial["balanceSum"] == 0):
        print(f"!! 前置试算不平，停止：{base_trial}")
        return

    # ---------- 0. 夹具：商户/买家/账户/充值 + T-4 到期候选（先建，后续节自然等待到期） ----------
    print("[0] 夹具：会员/账户/充值（只新增，不动既有数据）")
    seller = new_member(f"G卖家{SUFFIX}", "MERCHANT")
    seller_acct = new_account(seller["id"])
    buyer1 = new_member(f"G买家A{SUFFIX}", "USER")
    buyer2 = new_member(f"G买家B{SUFFIX}", "USER")
    buyer3 = new_member(f"G买家C{SUFFIX}", "USER")
    buyer4 = new_member(f"G买家D{SUFFIX}", "USER")
    buyer5 = new_member(f"G买家E{SUFFIX}", "USER")
    poor = new_member(f"G穷户{SUFFIX}", "USER")
    a1, a2, a3, a4 = (new_account(m["id"]) for m in (buyer1, buyer2, buyer3, buyer4))
    a5 = new_account(buyer5["id"])
    a_poor = new_account(poor["id"])
    for acct, amount in ((a1, 20000000), (a2, 20000000), (a3, 20000000), (a4, 20000000)):
        topup(acct["id"], amount, f"g-topup-{SUFFIX}-{acct['id']}")
    topup(a5["id"], 30000, f"g-topup-{SUFFIX}-{a5['id']}")
    check("夹具充值到账（余额=2000万分）", bal(a1["id"]) == 20000000, str(bal(a1["id"])))

    # T-4 到期候选：8 单 3 分钟到期（分片不重不漏）+ 1 单已支付 + 1 单风控失败 + 2 单未到期
    exp_orders = [ok("POST", "/api/orders", {"merchantId": seller["id"], "subject": f"超时单{i}-{SUFFIX}",
                                            "amount": 1000 + i, "expireMinutes": 3})["order"]
                  for i in range(8)]
    exp_paid = ok("POST", "/api/orders", {"merchantId": seller["id"], "subject": f"超时已付-{SUFFIX}",
                                         "amount": 1200, "expireMinutes": 3})["order"]
    exp_failed = ok("POST", "/api/orders", {"merchantId": seller["id"], "subject": f"超时风控失败-{SUFFIX}",
                                           "amount": 20000000, "expireMinutes": 3})["order"]
    exp_future = [ok("POST", "/api/orders", {"merchantId": seller["id"], "subject": f"未到期{i}-{SUFFIX}",
                                            "amount": 900, "expireMinutes": 60})["order"] for i in range(2)]
    check("T-4 候选单落 expire_at（3 分钟）",
          all(o.get("expireAt") and o["expireAt"] - o["createdAt"] == 180000 for o in exp_orders),
          str([(o["id"], o.get("expireAt"), o["createdAt"]) for o in exp_orders[:2]]))
    check("T-4 未到期单 expire_at=60 分钟",
          all(o["expireAt"] - o["createdAt"] == 3600000 for o in exp_future), str(exp_future[0]))

    # ---------- 1. ride 红线 ----------
    print("[1] ride 兼容红线：trade.create / trade.pay / trade.query 逐字节不变")
    app_id, secret = seller["appId"], seller["appSecret"]
    out_no = f"ride-{SUFFIX}"
    st, resp = openapi("trade.create", app_id, secret, subject="ride 行程", amount=3000, outTradeNo=out_no)
    check("trade.create 200 且返回 id/amount/merchantId", st == 200 and resp.get("id")
          and resp["amount"] == 3000 and resp["merchantId"] == seller["id"], str(resp))
    ride_order = resp
    check("trade.create 默认产品 INSTANT（不传 productType）", ride_order.get("productType") == "INSTANT",
          str(ride_order.get("productType")))
    check("trade.create 返回字段含 ride 依赖的全部既有键",
          all(k in ride_order for k in ("id", "merchantId", "merchantName", "outTradeNo", "subject",
                                        "amount", "status", "refundedAmount", "createdAt")),
          str(sorted(ride_order)))
    check("trade.create 初始 CREATED / refundedAmount=0 / outTradeNo 原样",
          ride_order["status"] == "CREATED" and ride_order["refundedAmount"] == 0
          and ride_order["outTradeNo"] == out_no, str(ride_order))
    st, again = openapi("trade.create", app_id, secret, subject="ride 行程", amount=3000, outTradeNo=out_no)
    check("merchant+outTradeNo 唯一幂等（重复下单返回同一 id）", st == 200 and again["id"] == ride_order["id"],
          str(again))
    st, conflict = openapi("trade.create", app_id, secret, subject="ride 行程", amount=9999, outTradeNo=out_no)
    check("同 outTradeNo 不同金额仍返回原单（幂等键不含金额）",
          st == 200 and conflict["id"] == ride_order["id"] and conflict["amount"] == 3000, str(conflict))

    u10, m0, fee0, cash0 = bal(a1["id"]), bal(seller_acct["id"]), bal("SYS_FEE"), bal("SYS_CASH")
    st, resp = openapi("trade.pay", app_id, secret, orderId=ride_order["id"], method="BALANCE",
                       accountId=a1["id"], outTradeNo=f"ride-pay-{SUFFIX}")
    check("trade.pay 同步受理 200 返回 payment.id", st == 200 and resp.get("payment", {}).get("id"), str(resp))
    ride_pay = resp["payment"]
    check("BALANCE 同步受理态 PROCESSING/SUCCESS（绝不 WAITING/异步回执）",
          ride_pay["status"] in ("PROCESSING", "SUCCESS"), str(ride_pay["status"]))
    check("受理快照手续费 60bps=18 分", ride_pay["fee"] == 18, str(ride_pay["fee"]))
    paid = wait_payment(ride_pay["id"], "SUCCESS")
    check("trade.pay BALANCE 收敛 SUCCESS", paid["status"] == "SUCCESS", str(paid))
    check("付款方扣 3000 / 商户净入 2982 / SYS_FEE +18",
          bal(a1["id"]) == u10 - 3000 and bal(seller_acct["id"]) == m0 + 2982 and bal("SYS_FEE") == fee0 + 18,
          f"{bal(a1['id'])}/{bal(seller_acct['id'])}/{bal('SYS_FEE')}")
    ro = wait_order(ride_order["id"], "PAID")
    check("订单 PAID 且关联支付单", ro["status"] == "PAID" and ro["paymentId"] == ride_pay["id"], str(ro))
    st, q = openapi("trade.query", app_id, secret, orderId=ride_order["id"])
    check("trade.query 返回 order+payment（供 ride 增量对账回查手续费）",
          st == 200 and q.get("order", {}).get("id") == ride_order["id"]
          and q.get("payment", {}).get("id") == ride_pay["id"] and q["payment"]["fee"] == 18, str(q)[:300])
    check("trade.query 订单口径不变（PAID/amount/paymentId/refundedAmount）",
          q["order"]["status"] == "PAID" and q["order"]["amount"] == 3000
          and q["order"]["paymentId"] == ride_pay["id"] and q["order"]["refundedAmount"] == 0, str(q["order"]))
    check("INSTANT 订单不携带产品子状态（escrow/preauth 字段为 null）",
          q["order"].get("escrowStatus") is None and q["order"].get("preauthAmount") is None
          and q["order"].get("payeeMerchantId") is None, str(q["order"]))
    tr = ok("GET", f"/api/accounting/transfers/{ride_pay['id']}")
    debit = sum(l["amount"] for l in tr["lines"] if l["dir"] == "D")
    credit_merchant = sum(l["amount"] for l in tr["lines"] if l["dir"] == "C" and l["accountId"] == seller_acct["id"])
    check("INSTANT 分录模板不变：D 买家 3000 / C 商户 2982 / C SYS_FEE 18",
          debit == 3000 and credit_merchant == 2982 and tr["refId"] == ride_order["id"], str(tr))
    st, resp = openapi("trade.pay", app_id, secret, orderId=ride_order["id"], method="BALANCE",
                       accountId=a1["id"], outTradeNo=f"ride-pay-{SUFFIX}")
    check("trade.pay requestId 幂等（重复受理返回同一支付单，不重复扣款）",
          st == 200 and resp["payment"]["id"] == ride_pay["id"] and bal(a1["id"]) == u10 - 3000, str(resp))

    # ---------- 2. T-3 产品路由 ----------
    print("[2] T-3 多交易产品路由（产品参数经 DynamicConfig 可配）")
    products = ok("GET", "/api/trade/products")
    by_code = {p["product"]: p for p in products}
    check("产品目录三产品齐备", set(by_code) == {"INSTANT", "ESCROW", "PREAUTH"}, str(sorted(by_code)))
    check("INSTANT 为缺省产品且恒启用",
          by_code["INSTANT"]["defaultProduct"] is True and by_code["INSTANT"]["enabled"] is True, str(by_code["INSTANT"]))
    check("三产品各自状态机（SPI 路由落点）",
          by_code["INSTANT"]["stateMachine"] == "InstantProduct"
          and by_code["ESCROW"]["stateMachine"] == "EscrowProduct"
          and by_code["PREAUTH"]["stateMachine"] == "PreAuthProduct", str(by_code))
    check("产品参数快照含超时策略与担保窗口",
          by_code["INSTANT"]["params"].get("trade.order.expire-minutes") == "30"
          and by_code["ESCROW"]["params"].get("trade.escrow.auto-confirm-days") == "7", str(by_code))
    check("默认不自动跑批（两个 auto 开关默认 false）",
          by_code["INSTANT"]["params"].get("trade.expire.auto.enabled") == "false"
          and by_code["ESCROW"]["params"].get("trade.escrow.auto.enabled") == "false", str(by_code))

    inst = ok("POST", "/api/orders", {"merchantId": seller["id"], "subject": f"即时{SUFFIX}", "amount": 2000})["order"]
    check("不传 productType → INSTANT（merchantId 即卖家）",
          inst["productType"] == "INSTANT" and inst["merchantId"] == seller["id"], str(inst))
    esc = ok("POST", "/api/orders", {"merchantId": seller["id"], "subject": f"担保{SUFFIX}",
                                    "amount": 10000, "productType": "ESCROW"})["order"]
    hold_merchant = esc["merchantId"]
    hold_account = ok("GET", f"/api/accounts?memberId={hold_merchant}")
    hold_account_id = hold_account[0]["id"] if isinstance(hold_account, list) and hold_account else None
    check("ESCROW 路由：merchantId=平台担保系统商户、payeeMerchantId=真实卖家",
          esc["productType"] == "ESCROW" and esc["payeeMerchantId"] == seller["id"]
          and hold_merchant != seller["id"], str(esc))
    pre = ok("POST", "/api/orders", {"merchantId": seller["id"], "subject": f"预授权{SUFFIX}",
                                    "amount": 20000, "productType": "PREAUTH"})["order"]
    check("PREAUTH 路由：merchantId 仍为卖家（完成扣款直接入卖家）",
          pre["productType"] == "PREAUTH" and pre["merchantId"] == seller["id"], str(pre))
    biz_esc = ok("POST", "/api/orders", {"merchantId": seller["id"], "subject": f"属性路由{SUFFIX}",
                                        "amount": 1000, "bizType": "ESCROW"})["order"]
    check("按订单属性 bizType=ESCROW 路由到担保", biz_esc["productType"] == "ESCROW", str(biz_esc))
    st, resp = api("POST", "/api/orders", {"merchantId": seller["id"], "subject": "未知产品",
                                          "amount": 100, "productType": "NOPE"})
    check("未知产品码拒绝 400（不静默落错状态机）", st == 400, str(resp))
    st, resp = api("POST", "/api/orders", {"merchantId": seller["id"], "subject": "非法超时",
                                          "amount": 100, "expireMinutes": 0})
    check("expireMinutes 非法拒绝 400", st == 400, str(resp))

    config_write({"trade.product.preauth.enabled": "false"})
    st, resp = api("POST", "/api/orders", {"merchantId": seller["id"], "subject": "关闭产品",
                                          "amount": 100, "productType": "PREAUTH"})
    check("产品开关热更生效：PREAUTH 关闭后显式下单被拒", st == 400 and "未启用" in str(resp), str(resp))
    inst2 = ok("POST", "/api/orders", {"merchantId": seller["id"], "subject": f"默认不受影响{SUFFIX}",
                                      "amount": 100})["order"]
    check("关闭新产品不影响默认 INSTANT 下单", inst2["productType"] == "INSTANT", str(inst2))
    config_write({"trade.product.preauth.enabled": None,
                  f"trade.product.merchant.{seller['id']}": "ESCROW"})
    routed = ok("POST", "/api/orders", {"merchantId": seller["id"], "subject": f"商户级路由{SUFFIX}",
                                       "amount": 100})["order"]
    check("商户级覆盖热更生效：不传 productType 也路由到 ESCROW", routed["productType"] == "ESCROW", str(routed))
    config_write({f"trade.product.merchant.{seller['id']}": None})
    back = ok("POST", "/api/orders", {"merchantId": seller["id"], "subject": f"恢复缺省{SUFFIX}",
                                     "amount": 100})["order"]
    check("删除覆盖后回落全局缺省 INSTANT", back["productType"] == "INSTANT", str(back))
    config_write({"trade.order.expire-minutes": 5})
    cfg_order = ok("POST", "/api/orders", {"merchantId": seller["id"], "subject": f"策略热更{SUFFIX}",
                                          "amount": 100})["order"]
    check("超时策略热更生效（新单 5 分钟，在途单用受理快照）",
          cfg_order["expireAt"] - cfg_order["createdAt"] == 300000
          and exp_orders[0]["expireAt"] - exp_orders[0]["createdAt"] == 180000, str(cfg_order))
    config_write({"trade.order.expire-minutes": None})
    # 该单只为断言策略热更，立即手动关闭，避免后续跑批把它算进增量（干扰不重不漏断言）
    ok("POST", f"/api/orders/{cfg_order['id']}/close", {})

    # ---------- 3. T-1 担保交易 ----------
    print("[3] T-1 担保：付款→担保户→确认收货→打款卖家（资金守恒 + 幂等）")
    if not hold_account_id:
        hold_account_id = act("pptrade")["holdAccountId"]
    check("平台担保户已登记（系统商户 + 余额账户）", bool(hold_account_id), str(hold_account_id))
    h0, s0, b20, f0 = bal(hold_account_id), bal(seller_acct["id"]), bal(a2["id"]), bal("SYS_FEE")
    e1 = ok("POST", "/api/orders", {"merchantId": seller["id"], "subject": f"担保全流程{SUFFIX}",
                                   "amount": 10000, "productType": "ESCROW"})["order"]
    r = pay(e1["id"], a2["id"], f"g-esc-pay1-{SUFFIX}")
    p1 = wait_payment(r["payment"]["id"], "SUCCESS")
    time.sleep(2.5)
    eo1 = wait_order(e1["id"], "PAID")
    check("担保支付后订单 PAID + escrowStatus=HOLD", eo1["escrowStatus"] == "HOLD", str(eo1))
    check("担保自动确认期限=7 天（受理快照）",
          eo1["expireAt"] and abs(eo1["expireAt"] - (eo1["paidAt"] + 7 * 86400000)) < 120000, str(eo1))
    check("买家扣全额 10000", bal(a2["id"]) == b20 - 10000, str(bal(a2["id"])))
    check("卖家确认前分文未入", bal(seller_acct["id"]) == s0, str(bal(seller_acct["id"])))
    check("资金入平台担保户净额 9940", bal(hold_account_id) == h0 + 9940, str(bal(hold_account_id)))
    check("手续费支付时即收 SYS_FEE +60", bal("SYS_FEE") == f0 + 60, str(bal("SYS_FEE")))
    tr1 = ok("GET", f"/api/accounting/transfers/{p1['id']}")
    check("担保支付分录：D 买家 / C 担保户（非卖家）+ C SYS_FEE",
          any(l["accountId"] == hold_account_id and l["dir"] == "C" and l["amount"] == 9940 for l in tr1["lines"])
          and not any(l["accountId"] == seller_acct["id"] for l in tr1["lines"]), str(tr1))
    st, resp = api("POST", f"/api/trade/orders/{e1['id']}/confirm", {})
    check("确认收货无二次认证拒绝 403", st == 403, str(resp))
    c1 = ok("POST", f"/api/trade/orders/{e1['id']}/confirm", {}, step=True)
    check("确认收货后 escrowStatus=CONFIRMED", c1["escrowStatus"] == "CONFIRMED", str(c1))
    check("确认收货打款卖家净额 9940", bal(seller_acct["id"]) == s0 + 9940, str(bal(seller_acct["id"])))
    check("担保户等额减少（回到基线）", bal(hold_account_id) == h0, str(bal(hold_account_id)))
    check("买家余额不因确认收货变化", bal(a2["id"]) == b20 - 10000, str(bal(a2["id"])))
    tc = ok("GET", f"/api/accounting/transfers/EC{e1['id']}")
    check("确认收货复式记账 D 担保户 / C 卖家 等额 9940",
          sum(l["amount"] for l in tc["lines"] if l["dir"] == "D") == 9940
          and sum(l["amount"] for l in tc["lines"] if l["dir"] == "C") == 9940
          and tc["refId"] == e1["id"], str(tc))
    c1b = ok("POST", f"/api/trade/orders/{e1['id']}/confirm", {}, step=True)
    check("重复确认收货幂等（状态不变、不重复打款）",
          c1b["escrowStatus"] == "CONFIRMED" and bal(seller_acct["id"]) == s0 + 9940
          and bal(hold_account_id) == h0, str(c1b))
    st, resp = api("POST", f"/api/payments/{p1['id']}/refund",
                   {"amount": 1000, "requestId": f"g-esc-refund-after-{SUFFIX}"})
    check("确认收货后禁止再从担保户退款（防挪用他单担保资金）", st in (400, 409), str(resp))
    check("拒绝退款后余额分文未动", bal(hold_account_id) == h0 and bal(a2["id"]) == b20 - 10000, "")

    # 担保 + 确认前部分退款 → 净额确认
    e2 = ok("POST", "/api/orders", {"merchantId": seller["id"], "subject": f"担保退款{SUFFIX}",
                                   "amount": 5000, "productType": "ESCROW"})["order"]
    r2 = pay(e2["id"], a2["id"], f"g-esc-pay2-{SUFFIX}")
    p2 = wait_payment(r2["payment"]["id"], "SUCCESS")
    time.sleep(2.5)
    wait_order(e2["id"], "PAID")
    h1, s1, b21 = bal(hold_account_id), bal(seller_acct["id"]), bal(a2["id"])
    rf = ok("POST", f"/api/payments/{p2['id']}/refund", {"amount": 2000, "requestId": f"g-esc-rf-{SUFFIX}"})
    poll(lambda: ok("GET", f"/api/payments/{p2['id']}"), lambda p: (p.get("refundedAmount") or 0) == 2000)
    p2r = ok("GET", f"/api/payments/{p2['id']}")
    check("确认前退款从担保户退买家（净额 1988 + 手续费退回 12）",
          bal(a2["id"]) == b21 + 2000 and bal(hold_account_id) == h1 - 1988, f"{bal(a2['id'])}/{bal(hold_account_id)}")
    c2 = ok("POST", f"/api/trade/orders/{e2['id']}/confirm", {}, step=True)
    check("部分退款后确认收货按净额 2982 打款卖家",
          c2["escrowStatus"] == "CONFIRMED" and bal(seller_acct["id"]) == s1 + 2982
          and bal(hold_account_id) == h1 - 1988 - 2982, str(c2))
    check("担保户该单资金清零（1988 退买家 + 2982 打卖家 = 4970）",
          bal(hold_account_id) == h1 - 4970, str(bal(hold_account_id)))

    # 超时自动确认（JobHandler + ShedLock，配置窗口 0 天 = 立即到期）
    config_write({"trade.escrow.auto-confirm-days": 0})
    auto_orders = []
    for i in range(4):
        eo = ok("POST", "/api/orders", {"merchantId": seller["id"], "subject": f"担保超时{i}-{SUFFIX}",
                                       "amount": 2000 + i, "productType": "ESCROW"})["order"]
        rr = pay(eo["id"], a2["id"], f"g-esc-auto-{i}-{SUFFIX}")
        wait_payment(rr["payment"]["id"], "SUCCESS")
        time.sleep(2.5)
        auto_orders.append((eo, rr["payment"]["id"]))
    held = [wait_order(eo["id"], "PAID") for eo, _ in auto_orders]
    check("窗口 0 天：支付完结即到期（expireAt<=now）",
          all(o["escrowStatus"] == "HOLD" and o["expireAt"] <= int(time.time() * 1000) + 1000 for o in held),
          str([(o["id"], o["escrowStatus"], o["expireAt"]) for o in held]))
    h2, s2 = bal(hold_account_id), bal(seller_acct["id"])
    expect_net = sum(net_of(ok("GET", f"/api/payments/{pid}")) for _, pid in auto_orders)
    st, job = ppjob("escrowAutoConfirm")
    check("ppjob 触发 escrowAutoConfirm 成功（JobHandler 注册）", st == 200 and job.get("success") is True
          and job.get("known") is True, str(job))
    auto_done = [wait_order(eo["id"], "PAID") for eo, _ in auto_orders]
    check("超时自动确认全部 AUTO_CONFIRMED",
          all(o["escrowStatus"] == "AUTO_CONFIRMED" for o in auto_done), str(auto_done))
    check("自动确认打款卖家净额合计正确", bal(seller_acct["id"]) == s2 + expect_net,
          f"{bal(seller_acct['id'])} vs {s2 + expect_net}")
    check("自动确认后担保户等额减少", bal(hold_account_id) == h2 - expect_net, str(bal(hold_account_id)))
    st, job2 = ppjob("escrowAutoConfirm")
    check("重复触发自动确认幂等（不重复打款）",
          st == 200 and bal(seller_acct["id"]) == s2 + expect_net and bal(hold_account_id) == h2 - expect_net,
          str(job2))
    # 分片不重不漏：新建 4 单到期担保，按 4 片分别触发
    shard_orders = []
    for i in range(4):
        eo = ok("POST", "/api/orders", {"merchantId": seller["id"], "subject": f"担保分片{i}-{SUFFIX}",
                                       "amount": 3000 + i, "productType": "ESCROW"})["order"]
        rr = pay(eo["id"], a2["id"], f"g-esc-shard-{i}-{SUFFIX}")
        wait_payment(rr["payment"]["id"], "SUCCESS")
        time.sleep(2.5)
        shard_orders.append((eo, rr["payment"]["id"]))
    for eo, _ in shard_orders:
        wait_order(eo["id"], "PAID")
    h3, s3 = bal(hold_account_id), bal(seller_acct["id"])
    shard_expect = sum(net_of(ok("GET", f"/api/payments/{pid}")) for _, pid in shard_orders)
    counts = []
    for idx in range(4):
        n = ok("POST", "/api/trade/escrow/auto-confirm", {"shardIndex": idx, "shardTotal": 4}, step=True)
        counts.append(n)
    buckets = {}
    for eo, _ in shard_orders:
        buckets.setdefault(zlib.crc32(eo["id"].encode()) % 4, 0)
        buckets[zlib.crc32(eo["id"].encode()) % 4] += 1
    check("担保自动确认 4 片不重不漏（各片笔数=CRC32 分桶，合计=到期单数）",
          sum(counts) == len(shard_orders) and all(counts[i] == buckets.get(i, 0) for i in range(4)),
          f"counts={counts} buckets={buckets}")
    check("分片确认后卖家入账合计正确、担保户等额减少",
          bal(seller_acct["id"]) == s3 + shard_expect and bal(hold_account_id) == h3 - shard_expect,
          f"{bal(seller_acct['id'])}/{bal(hold_account_id)}")
    n_full = ok("POST", "/api/trade/escrow/auto-confirm", {"shardIndex": 0, "shardTotal": 1}, step=True)
    check("全量再跑一次为 0（已确认单不重复处理）", n_full == 0, str(n_full))
    config_write({"trade.escrow.auto-confirm-days": None})
    trial_guard("担保后")

    # ---------- 4. T-2 预授权 ----------
    print("[4] T-2 预授权：冻结→完成（差额解冻）/撤销（全额解冻）")
    b30, s30 = bal(a3["id"]), bal(seller_acct["id"])
    pa1 = ok("POST", "/api/orders", {"merchantId": seller["id"], "subject": f"押金{SUFFIX}",
                                    "amount": 20000, "productType": "PREAUTH"})["order"]
    st, resp = api("POST", "/api/cashier/pay", {"orderId": pa1["id"], "method": "BALANCE",
                                              "accountId": a3["id"], "requestId": f"g-pa-direct-{SUFFIX}"})
    check("预授权订单不可直接支付（须先冻结）",
          st in (400, 409) and "预授权订单不可直接支付" in str(resp), str(resp))
    fr = ok("POST", f"/api/trade/orders/{pa1['id']}/preauth",
            {"accountId": a3["id"], "requestId": f"g-pa-fr-{SUFFIX}"})
    check("冻结后订单 AUTHORIZED + 预授权明细齐备",
          fr["status"] == "AUTHORIZED" and fr["preauthStatus"] == "AUTHORIZED"
          and fr["preauthAmount"] == 20000 and fr["preauthCompleted"] == 0
          and fr["preauthAccountId"] == a3["id"] and fr["preauthFreezeId"], str(fr))
    check("冻结不动余额（balance 不变）", bal(a3["id"]) == b30, str(bal(a3["id"])))
    check("A-3 PREAUTH 冻结单生效（frozen=20000）", frozen_sum(a3["id"]) == 20000, str(freezes(a3["id"])))
    fz = [f for f in freezes(a3["id"]) if f["reasonType"] == "PREAUTH"]
    check("冻结单原因=PREAUTH 且 ACTIVE（复用 A-3，不新造冻结语义）",
          len(fz) == 1 and fz[0]["status"] == "ACTIVE" and fz[0]["amount"] == 20000, str(fz))
    fr2 = ok("POST", f"/api/trade/orders/{pa1['id']}/preauth",
             {"accountId": a3["id"], "requestId": f"g-pa-fr-{SUFFIX}"})
    check("重复冻结幂等（同一冻结单、不重复冻结）",
          fr2["preauthFreezeId"] == fr["preauthFreezeId"] and frozen_sum(a3["id"]) == 20000, str(fr2))
    # available=balance−frozen 口径：余额足够但可用不足 → 账务 TCC Try 拒绝，支付 FAILED 不动账
    pa_avail = ok("POST", "/api/orders", {"merchantId": seller["id"], "subject": f"押金可用{SUFFIX}",
                                         "amount": 20000, "productType": "PREAUTH"})["order"]
    ok("POST", f"/api/trade/orders/{pa_avail['id']}/preauth",
       {"accountId": a5["id"], "requestId": f"g-pa5-fr-{SUFFIX}"})
    check("可用额口径夹具：余额 30000 / 冻结 20000", bal(a5["id"]) == 30000 and frozen_sum(a5["id"]) == 20000,
          f"{bal(a5['id'])}/{frozen_sum(a5['id'])}")
    big = ok("POST", "/api/orders", {"merchantId": seller["id"], "subject": f"可用不足{SUFFIX}",
                                    "amount": 15000})["order"]
    stb, respb = pay_raw(big["id"], a5["id"], f"g-pa-avail-{SUFFIX}")
    pb = wait_payment_by_request(f"g-pa-avail-{SUFFIX}", "FAILED")
    check("available=balance−frozen：余额 30000≥15000 但可用 10000<15000 → 支付 FAILED 不动账",
          stb in (400, 409) and pb is not None and pb["status"] == "FAILED" and bal(a5["id"]) == 30000,
          f"{stb} {respb} {pb}")
    ok("POST", f"/api/trade/orders/{pa_avail['id']}/preauth/cancel", {"requestId": f"g-pa5-x-{SUFFIX}"}, step=True)
    check("可用额测试后冻结全额释放", frozen_sum(a5["id"]) == 0 and bal(a5["id"]) == 30000, "")
    time.sleep(2.5)
    st, resp = api("POST", f"/api/trade/orders/{pa1['id']}/preauth/complete",
                   {"amount": 25000, "requestId": f"g-pa-over-{SUFFIX}"}, step=True)
    check("超扣拒绝（完成额 > 冻结额）", st == 400, str(resp))
    c = ok("POST", f"/api/trade/orders/{pa1['id']}/preauth/complete",
           {"amount": 8000, "requestId": f"g-pa-c1-{SUFFIX}"}, step=True)
    check("部分完成 8000：累计额/子状态正确",
          c["preauthCompleted"] == 8000 and c["preauthStatus"] == "PARTIAL_COMPLETED"
          and c["status"] == "AUTHORIZED", str(c))
    check("部分完成扣买家 8000 / 入卖家 8000（押金扣款不计手续费）",
          bal(a3["id"]) == b30 - 8000 and bal(seller_acct["id"]) == s30 + 8000,
          f"{bal(a3['id'])}/{bal(seller_acct['id'])}")
    check("差额解冻：frozen 降为 12000（新冻结单承接剩余额度）",
          frozen_sum(a3["id"]) == 12000 and c["preauthFreezeId"] != fr["preauthFreezeId"], str(freezes(a3["id"])))
    st, resp = api("POST", f"/api/trade/orders/{pa1['id']}/preauth/complete",
                   {"amount": 8000, "requestId": f"g-pa-c1-{SUFFIX}"}, step=True)
    check("同 requestId 重复完成幂等（不重扣）",
          st == 200 and bal(a3["id"]) == b30 - 8000 and frozen_sum(a3["id"]) == 12000, str(resp))
    st, resp = api("POST", f"/api/trade/orders/{pa1['id']}/preauth/complete",
                   {"amount": 12001, "requestId": f"g-pa-over2-{SUFFIX}"}, step=True)
    check("超出剩余冻结额拒绝（12001 > 12000）", st == 400, str(resp))
    c2 = ok("POST", f"/api/trade/orders/{pa1['id']}/preauth/complete",
            {"amount": 12000, "requestId": f"g-pa-c2-{SUFFIX}"}, step=True)
    check("全额完成：COMPLETED + 订单终态 + frozen 清零",
          c2["preauthStatus"] == "COMPLETED" and c2["preauthCompleted"] == 20000
          and c2["status"] == "COMPLETED" and frozen_sum(a3["id"]) == 0, str(c2))
    check("累计扣款=冻结额（买家 −20000 / 卖家 +20000）",
          bal(a3["id"]) == b30 - 20000 and bal(seller_acct["id"]) == s30 + 20000,
          f"{bal(a3['id'])}/{bal(seller_acct['id'])}")
    st, resp = api("POST", f"/api/trade/orders/{pa1['id']}/preauth/complete",
                   {"amount": 1, "requestId": f"g-pa-c3-{SUFFIX}"}, step=True)
    check("终态后不可再完成", st == 409, str(resp))
    tcp = ok("GET", f"/api/accounting/transfers/PAP:{pa1['id']}:0")
    check("完成扣款复式记账 D 买家 / C 卖家 等额（bizNo 确定性）",
          sum(l["amount"] for l in tcp["lines"] if l["dir"] == "D") == 8000
          and sum(l["amount"] for l in tcp["lines"] if l["dir"] == "C") == 8000
          and tcp["refId"] == pa1["id"], str(tcp))
    # 部分完成后撤销：只释放剩余额度
    pa2 = ok("POST", "/api/orders", {"merchantId": seller["id"], "subject": f"押金撤销{SUFFIX}",
                                    "amount": 15000, "productType": "PREAUTH"})["order"]
    ok("POST", f"/api/trade/orders/{pa2['id']}/preauth", {"accountId": a3["id"], "requestId": f"g-pa2-fr-{SUFFIX}"})
    ok("POST", f"/api/trade/orders/{pa2['id']}/preauth/complete",
       {"amount": 5000, "requestId": f"g-pa2-c1-{SUFFIX}"}, step=True)
    b31, s31 = bal(a3["id"]), bal(seller_acct["id"])
    check("撤销前 frozen=10000（15000−5000）", frozen_sum(a3["id"]) == 10000, str(frozen_sum(a3["id"])))
    cx = ok("POST", f"/api/trade/orders/{pa2['id']}/preauth/cancel", {"requestId": f"g-pa2-x-{SUFFIX}"}, step=True)
    check("撤销：剩余额度全额解冻 + 状态 CANCELLED",
          cx["preauthStatus"] == "CANCELLED" and cx["status"] == "CANCELLED" and frozen_sum(a3["id"]) == 0, str(cx))
    check("撤销不动已完成扣款（买家/卖家余额不变）",
          bal(a3["id"]) == b31 and bal(seller_acct["id"]) == s31, f"{bal(a3['id'])}/{bal(seller_acct['id'])}")
    cx2 = ok("POST", f"/api/trade/orders/{pa2['id']}/preauth/cancel", {"requestId": f"g-pa2-x-{SUFFIX}"}, step=True)
    check("重复撤销幂等", cx2["preauthStatus"] == "CANCELLED" and frozen_sum(a3["id"]) == 0, str(cx2))
    # 纯撤销（未完成任何扣款）
    pa3 = ok("POST", "/api/orders", {"merchantId": seller["id"], "subject": f"押金纯撤销{SUFFIX}",
                                    "amount": 9000, "productType": "PREAUTH"})["order"]
    ok("POST", f"/api/trade/orders/{pa3['id']}/preauth", {"accountId": a3["id"], "requestId": f"g-pa3-fr-{SUFFIX}"})
    b32, s32 = bal(a3["id"]), bal(seller_acct["id"])
    cx3 = ok("POST", f"/api/trade/orders/{pa3['id']}/preauth/cancel", {"requestId": f"g-pa3-x-{SUFFIX}"}, step=True)
    check("纯撤销全额解冻、余额分文未动",
          cx3["preauthStatus"] == "CANCELLED" and frozen_sum(a3["id"]) == 0
          and bal(a3["id"]) == b32 and bal(seller_acct["id"]) == s32, str(cx3))
    check("撤销后订单不可再冻结/完成",
          api("POST", f"/api/trade/orders/{pa3['id']}/preauth/complete",
              {"amount": 1, "requestId": f"g-pa3-c-{SUFFIX}"}, step=True)[0] == 409, "")
    trial_guard("预授权后")

    # ---------- 5. T-4 超时自动关闭 ----------
    print("[5] T-4 超时关闭：不重不漏 + 在途单不误关 + 竞态守卫")
    # 已支付单：到期也不得关闭
    rp = pay(exp_paid["id"], a4["id"], f"g-exp-paid-{SUFFIX}")
    pp = wait_payment(rp["payment"]["id"], "SUCCESS")
    wait_order(exp_paid["id"], "PAID")
    time.sleep(2.5)
    # 风控失败单：支付 FAILED 且预留已释放（订单回到 CREATED）→ 到期应被关闭
    topup(a4["id"], 10000000, f"g-topup-extra-{SUFFIX}-{a4['id']}")
    stf, respf = pay_raw(exp_failed["id"], a4["id"], f"g-exp-failed-{SUFFIX}")
    pf = wait_payment_by_request(f"g-exp-failed-{SUFFIX}", "FAILED")
    check("风控拒绝支付单 FAILED（超限 2000 万）",
          stf in (400, 409) and pf is not None and pf["status"] == "FAILED", f"{stf} {respf} {pf}")
    check("FAILED 单预留已释放（订单回到 CREATED，可被超时关闭）",
          order(exp_failed["id"])["status"] == "CREATED", str(order(exp_failed["id"])))
    time.sleep(2.5)
    # 未到期先跑一次：绝不提前关闭
    st, early = ppjob("tradeOrderExpire")
    check("未到期触发作业成功（JobHandler 注册 + ShedLock）",
          st == 200 and early.get("success") is True and early.get("known") is True, str(early))
    check("未到期单仍 CREATED（不提前关闭）",
          all(order(o["id"])["status"] == "CREATED" for o in exp_orders + exp_future), "")
    # 等到期（1 分钟策略；前面各节已耗时，通常已到期）
    due_deadline = max(o["expireAt"] for o in exp_orders) / 1000.0
    wait_s = max(0, due_deadline - time.time()) + 1
    if wait_s > 0:
        print(f"  … 等待到期 {wait_s:.0f}s")
        time.sleep(min(wait_s, 150))
    poll(lambda: act("pptrade", {"action": "preview", "shardIndex": 0, "shardTotal": 1})["due"],
         lambda n: n >= len(exp_orders) + 1, timeout=90, interval=2)
    # 分片不重不漏：9 单（8 普通 + 1 风控失败）按 orderId CRC32 分 4 片
    shard_set = exp_orders + [exp_failed]
    due_before = ok("GET", "/api/trade/expire/due?shardIndex=0&shardTotal=1")
    buckets = {}
    for o in due_before:
        k = zlib.crc32(o["id"].encode()) % 4
        buckets[k] = buckets.get(k, 0) + 1
    check("到期候选集覆盖本批全部候选（预览与作业同一口径）",
          {o["id"] for o in shard_set} <= {o["id"] for o in due_before},
          f"due={len(due_before)} mine={len(shard_set)}")
    closes0 = act("pptrade")["expireCloses"]
    counts = []
    for idx in range(4):
        counts.append(ok("POST", "/api/trade/expire/run", {"shardIndex": idx, "shardTotal": 4}, step=True))
    check("超时关闭 4 片不重不漏（各片笔数=CRC32 分桶，合计=到期候选数）",
          sum(counts) == len(due_before) and all(counts[i] == buckets.get(i, 0) for i in range(4)),
          f"counts={counts} buckets={buckets} due={len(due_before)}")
    check("到期单全部 CLOSED（不遗漏）", all(order(o["id"])["status"] == "CLOSED" for o in exp_orders),
          str([(o["id"], order(o["id"])["status"]) for o in exp_orders]))
    check("已支付到期单未被误关（PAID）", order(exp_paid["id"])["status"] == "PAID", str(order(exp_paid["id"])))
    check("风控失败到期单被关闭（无在途单）", order(exp_failed["id"])["status"] == "CLOSED",
          str(order(exp_failed["id"])))
    check("未到期单不被关闭（60 分钟策略）", all(order(o["id"])["status"] == "CREATED" for o in exp_future), "")
    closes1 = act("pptrade")["expireCloses"]
    check("关闭留痕一单一次（留痕增量=关闭单数，不重）", closes1 - closes0 == len(due_before),
          f"{closes0} -> {closes1} due={len(due_before)}")
    n_again = ok("POST", "/api/trade/expire/run", {"shardIndex": 0, "shardTotal": 1}, step=True)
    check("重复跑批 0 笔（幂等不重关）", n_again == 0, str(n_again))
    with ThreadPoolExecutor(max_workers=4) as pool:
        concurrent = list(pool.map(lambda _: ppjob("tradeOrderExpire", 0, 1), range(4)))
    check("并发同片触发全部成功返回（ShedLock 互斥，无异常）",
          all(st == 200 and r.get("success") is True for st, r in concurrent), str(concurrent)[:200])
    check("并发触发后无新增关闭（已关单不重关）", act("pptrade")["expireCloses"] == closes1, "")
    st, resp = api("POST", "/api/cashier/pay", {"orderId": exp_orders[0]["id"], "method": "BALANCE",
                                              "accountId": a4["id"], "requestId": f"g-exp-closed-{SUFFIX}"})
    check("已关闭订单不可支付 409", st == 409, str(resp))
    check("超时关闭跑批无二次认证拒绝 403", api("POST", "/api/trade/expire/run", {})[0] == 403, "")

    # 关闭与支付受理竞态：同一到期单，支付线程 + 5 个作业触发并发
    exp_race = ok("POST", "/api/orders", {"merchantId": seller["id"], "subject": f"超时竞态-{SUFFIX}",
                                         "amount": 1500, "expireMinutes": 1})["order"]
    time.sleep(62)
    b40 = bal(a4["id"])
    reopens0 = act("pptrade")["expireRaceReopens"]
    race_result = {}

    def race_pay():
        try:
            r = pay(exp_race["id"], a4["id"], f"g-exp-race-{SUFFIX}")
            race_result["payment"] = wait_payment(r["payment"]["id"], "SUCCESS", timeout=30)
        except Exception as e:
            race_result["error"] = str(e)

    with ThreadPoolExecutor(max_workers=6) as pool:
        futures = [pool.submit(race_pay)]
        for _ in range(5):
            futures.append(pool.submit(lambda: ppjob("tradeOrderExpire")))
        for f in futures:
            f.result()
    race_order = order(exp_race["id"])
    race_payments = [p for p in ok("GET", "/api/payments") if p["orderId"] == exp_race["id"]]
    race_success = [p for p in race_payments if p["status"] == "SUCCESS"]
    # 合法终态三选一：① 支付胜出 PAID+恰一 SUCCESS；② 关闭胜出 CLOSED+无 SUCCESS；
    # ③ 关闭与受理同一瞬间竞态 → 防悬挂守卫把刚关的单 CAS 重开为 CREATED，在途支付单 TCC 回滚 FAILED（分文未动）
    check("关闭与受理竞态：绝不「非 PAID 却已扣款」（SUCCESS 必伴随 PAID，否则无 SUCCESS）",
          (race_order["status"] == "PAID" and len(race_success) == 1)
          or (race_order["status"] in ("CLOSED", "CREATED") and not race_success),
          f"order={race_order['status']} success={len(race_success)} pay={race_result}")
    if race_order["status"] == "PAID":
        check("竞态支付成功侧：买家恰扣一次 1500（不重复扣款）", bal(a4["id"]) == b40 - 1500, str(bal(a4["id"])))
    else:
        check("竞态关闭/重开侧：买家分文未扣（在途单未被误关致死、未动账）", bal(a4["id"]) == b40, str(bal(a4["id"])))
    reopens1 = act("pptrade")["expireRaceReopens"]
    check("防悬挂守卫证据：单被重开必留 reopened 痕（CREATED+FAILED 支付单 ⇒ 重开计数增加）",
          race_order["status"] != "CREATED" or not race_payments or reopens1 > reopens0,
          f"status={race_order['status']} reopens {reopens0}->{reopens1} payments={len(race_payments)}")
    check("竞态后订单仍可收敛（PAID 或 可再支付的 CREATED/CLOSED 终态，无 PAYING 悬挂）",
          race_order["status"] in ("PAID", "CLOSED", "CREATED"), str(race_order["status"]))

    # 受理时过期校验开关（默认 false ⇒ 默认受理路径逐字节不变）
    rej_order = ok("POST", "/api/orders", {"merchantId": seller["id"], "subject": f"过期受理{SUFFIX}",
                                          "amount": 100, "expireMinutes": 1})["order"]
    time.sleep(62)
    config_write({"trade.expire.reject-pay": "true"})
    st, resp = api("POST", "/api/cashier/pay", {"orderId": rej_order["id"], "method": "BALANCE",
                                              "accountId": a4["id"], "requestId": f"g-exp-reject-{SUFFIX}"})
    check("开启 reject-pay 后过期单受理被拒（策略可配）", st in (400, 409), str(resp))
    config_write({"trade.expire.reject-pay": None})
    st, resp = api("POST", "/api/cashier/pay", {"orderId": rej_order["id"], "method": "BALANCE",
                                              "accountId": a4["id"], "requestId": f"g-exp-accept-{SUFFIX}"})
    check("关闭开关后默认受理路径不变（过期 CREATED 单仍可受理，由作业收敛）", st == 200, str(resp))
    if st == 200:
        wait_payment(resp["payment"]["id"], "SUCCESS")
        wait_order(rej_order["id"], "PAID")
    trial_guard("超时关闭后")

    # ---------- 6. 资金护航与恒等式 ----------
    print("[6] 资金护航：试算平衡 / 六项对账 / 担保户与预授权冻结恒等")
    good, t = trial_guard("收尾")
    check("账户数/分录数不少于基线（只增不删）",
          t["accounts"] >= base_trial["accounts"] and t["entries"] >= base_trial["entries"],
          f"{t['accounts']}/{t['entries']} vs {base_trial['accounts']}/{base_trial['entries']}")
    recon_guard("收尾")
    # 担保户余额 = 未确认担保净额之和
    all_orders = ok("GET", "/api/orders")
    holding = [o for o in all_orders if o.get("productType") == "ESCROW" and o.get("escrowStatus") == "HOLD"]
    hold_expect = 0
    for o in holding:
        if o.get("paymentId"):
            hold_expect += net_of(ok("GET", f"/api/payments/{o['paymentId']}"))
    check("担保户余额恒等：= Σ 未确认担保净额", bal(hold_account_id) == hold_expect,
          f"{bal(hold_account_id)} vs {hold_expect}（HOLD {len(holding)} 单）")
    # 预授权冻结恒等：Σ(auth−completed) = A-3 ACTIVE PREAUTH 冻结额
    # 恒等式按本批账户口径核对（a3/a5 为本批新开户，冻结单只属本批；绝不把历史批次残留算进来）
    batch_accounts = {a3["id"], a5["id"]}
    preauth_open = [o for o in all_orders if o.get("productType") == "PREAUTH"
                    and o.get("preauthAccountId") in batch_accounts
                    and o.get("preauthStatus") in ("AUTHORIZED", "PARTIAL_COMPLETED")]
    pre_expect = sum((o.get("preauthAmount") or 0) - (o.get("preauthCompleted") or 0) for o in preauth_open)
    pre_frozen = frozen_sum(a3["id"]) + frozen_sum(a5["id"])
    check("预授权冻结恒等：Σ(冻结额−已完成额) = A-3 ACTIVE PREAUTH 冻结额",
          pre_frozen == pre_expect, f"{pre_frozen} vs {pre_expect}")
    check("本批预授权全部收敛（无残留冻结）", pre_expect == 0 and pre_frozen == 0,
          f"open={len(preauth_open)} frozen={pre_frozen}")
    # 产品路由留痕：三产品订单均落库且状态机各自正确
    mine = [o for o in all_orders if o.get("subject", "").endswith(SUFFIX) or SUFFIX in o.get("subject", "")]
    codes = {o.get("productType") for o in mine}
    check("三产品订单均已落库（路由留痕）", {"INSTANT", "ESCROW", "PREAUTH"} <= codes, str(codes))
    check("担保单终态齐备（CONFIRMED/AUTO_CONFIRMED，无残留 HOLD）",
          all(o.get("escrowStatus") in ("CONFIRMED", "AUTO_CONFIRMED")
              for o in mine if o.get("productType") == "ESCROW" and o["status"] == "PAID"),
          str([(o["id"], o.get("escrowStatus")) for o in mine if o.get("productType") == "ESCROW"]))
    st_final = act("pptrade")
    jobs = http(TRADE_ACT + "/ppjob")[1]
    check("ppjob 注册两个交易作业", set(jobs.get("handlers", [])) >= {"tradeOrderExpire", "escrowAutoConfirm"},
          str(jobs.get("handlers")))
    check("ShedLock 锁表就绪（多实例互斥）", jobs.get("shedlock", {}).get("enabled") is True, str(jobs.get("shedlock")))
    check("全程无竞态悬挂残留（reopened 留痕与 CLOSED 单一致）",
          st_final["expireRaceReopens"] <= st_final["expireCloses"], str(st_final))


if __name__ == "__main__":
    try:
        main()
    finally:
        restore_config()
    print(f"\n结果：{PASSED} 通过，{FAILED} 失败")
    if FAILURES:
        print("失败项：" + "; ".join(FAILURES))
    sys.exit(1 if FAILED else 0)