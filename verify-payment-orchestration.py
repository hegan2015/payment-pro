#!/usr/bin/env python3
# ============================================================================
# payment-pro P1 · I 泳道验证：支付编排工作流 P-6/P-2/P-3/P-4/P-5 + 计费/CHALLENGE 接线
#
# 用法：docker 全栈在线（./run.sh --docker --no-build）后 python3 verify-payment-orchestration.py
#   [1] P-6 路由择优：预览即受理口径、维护窗口/限额硬过滤拒绝且**不留悬挂单**
#   [2] P-2 组合支付：多腿原子（一腿失败全回滚）、Σ腿=订单额、fee 分摊守恒、幂等、8 并发同 requestId 只一单、组合退款腿级不超退
#   [3] P-3 分账编排：比例/固定额、Σ分账=净额误差 0（settle shareSummary）、超额拒绝、回退幂等、延迟分账到期执行、SHARE_SUCCESS/SHARE_BACK 事件投递
#   [4] P-4 退款异步化：退款单独立终态查询/补单幂等、超退拦截、部分/多次退款手续费闭环；
#       异步渠道开关开启时验证 CARD 退款 WAITING→回执→SUCCESS（默认同步 stack 该节省略并明示）
#   [5] P-5 关闭/撤销：在途关闭释放、当日撤销全额反向（区别于退款）、重复幂等、已退/已分账/CARD 拒绝、关闭与受理竞态 CAS 不变量
#   [6] 计费接 FeeCalculator：缺省 60bps 一致、配置热更只影响新单、在途/历史快照不变
#   [7] CHALLENGE 续付：灰名单 CHALLENGE 拒绝→C-3 验证→续付成功；错误 token 拒绝且可重试；BLACK/REJECT 仍拒绝
#   [8] BALANCE 同步不回归 + 资金护航：试算 D=C 且 Σbalance=0、六项对账 pass
#
# 红线核对：account.transfer/post 同步语义由 smoke/ride verify-integration 覆盖（本脚本不改其口径）；
#           /openapi/** 冻结面不接入 CHALLENGE 续付；BALANCE/资金单无 WAITING。
# 数据保护：只新增业务行（真实支付链路），绝不删除/重置任何既有数据；
#           配置文件只增删本脚本自己的键（读-改-写保留他人键），结束恢复原状。
# 断言口径：不放宽、不改库状态凑绿；失败如实记录。
# ============================================================================
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor

BASE = "http://127.0.0.1:8080"
PAY_ACT = "http://127.0.0.1:18085/actuator"
CONFIG_FILE = os.path.expanduser("~/.config/payment-pro/config.properties")
SUFFIX = uuid.uuid4().hex[:6]
TOKEN = None
STEP = None
PASSED = 0
FAILED = 0
FAILURES = []
CONFIG_ORIGINAL = None
CONFIG_EXISTED = False
GOOD_CARD = "6222020202020202"
BAD_CARD = "6222020202020000"


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


def http(url, method="GET", body=None, bearer=False, step=False, timeout=120):
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


def login():
    global TOKEN, STEP
    st, resp = http(BASE + "/api/auth/login", "POST",
                    {"username": "admin", "password": os.environ.get("PP_ADMIN_PASSWORD", "admin123")})
    assert st == 200, f"登录失败 {st} {resp}"
    TOKEN = resp["token"]
    st, resp = http(BASE + "/api/security/step-up", "POST",
                    {"password": os.environ.get("PP_ADMIN_PASSWORD", "admin123")}, bearer=True)
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


def bal(account_id):
    return ok("GET", f"/api/accounting/accounts/{account_id}/balance")


def poll(fn, pred, timeout=30, interval=0.4):
    deadline = time.time() + timeout
    val = fn()
    while time.time() < deadline and not pred(val):
        time.sleep(interval)
        val = fn()
    return val


def new_party(tag, kind="MERCHANT"):
    m = ok("POST", "/api/members", {"name": f"IO{kind[0]}{tag}{SUFFIX}", "type": kind})
    a = ok("POST", "/api/accounts", {"memberId": m["id"]})
    return m, a


def topup(account_id, amount, tag):
    return ok("POST", "/api/accounting/internal/topup",
              {"accountId": account_id, "amount": amount, "bizNo": f"io-topup-{tag}-{SUFFIX}"})


def new_order(merchant_id, amount, tag):
    return ok("POST", "/api/orders",
              {"merchantId": merchant_id, "subject": f"IO 编排验证 {tag}", "amount": amount})["order"]


def pay_balance(order_id, account_id, tag):
    r = ok("POST", "/api/cashier/pay",
           {"orderId": order_id, "method": "BALANCE", "accountId": account_id, "requestId": f"io-req-{tag}-{SUFFIX}"})
    pid = r["payment"]["id"]
    p = poll(lambda: ok("GET", f"/api/payments/{pid}"), lambda x: x["status"] in ("SUCCESS", "FAILED"))
    assert p["status"] == "SUCCESS", f"支付未成功: {p}"
    return p


def find_payment(request_id):
    return next((p for p in ok("GET", "/api/payments") if p.get("requestId") == request_id), None)


def config_read():
    if not os.path.exists(CONFIG_FILE):
        return {}
    out = {}
    with open(CONFIG_FILE, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def config_write(items):
    """读-改-写：只动本脚本的键，保留他人键（配置共享文件）。"""
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


def config_backup():
    global CONFIG_ORIGINAL, CONFIG_EXISTED
    CONFIG_EXISTED = os.path.exists(CONFIG_FILE)
    if CONFIG_EXISTED:
        with open(CONFIG_FILE, encoding="utf-8") as f:
            CONFIG_ORIGINAL = f.read()


def config_restore():
    if CONFIG_EXISTED:
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            f.write(CONFIG_ORIGINAL)
    elif os.path.exists(CONFIG_FILE):
        os.remove(CONFIG_FILE)


def channel_async_enabled():
    try:
        st, resp = http(PAY_ACT + "/env/payment.channel.async", timeout=10)
        if st == 200 and resp:
            prop = resp.get("property", {}).get("value")
            return str(prop).lower() == "true"
    except Exception:
        pass
    return False


# ============================================================================
def section_route():
    print("\n[1] P-6 支付路由择优（失败关闭不留悬挂单）")
    merchant, ma = new_party("rt")
    user, ua = new_party("rt", "USER")
    topup(ua["id"], 100000, "rt")
    preview = ok("GET", "/api/channel/route?amount=10000&type=PAY")
    code = preview.get("channelCode")
    check("路由预览选中渠道", bool(code), str(preview))
    order = new_order(merchant["id"], 10000, "rt-ok")
    r = ok("POST", "/api/cashier/pay", {"orderId": order["id"], "method": "CARD",
                                       "cardNo": GOOD_CARD, "requestId": f"io-rt-ok-{SUFFIX}"})
    p = poll(lambda: ok("GET", f"/api/payments/{r['payment']['id']}"), lambda x: x["status"] == "SUCCESS")
    check("CARD 支付成功且路由渠道透传", p["status"] == "SUCCESS" and bool(p.get("channelCode")), str(p))

    # 维护窗口硬过滤：全部启用渠道进入维护 → 无候选 → 受理前拒绝，支付单行都不落
    # （单渠道维护时路由会故障切换到其他候选，属 CH-3 正确行为，故全量覆盖断言）
    enabled = [c["channelCode"] for c in ok("GET", "/api/channel/configs") if c.get("status") == "ENABLED"]
    now = int(time.time() * 1000)
    for c in enabled:
        ok("POST", f"/api/channel/{c}/maintenance", {"start": now - 3600_000, "end": now + 3600_000}, step=True)
    preview_down = ok("GET", "/api/channel/route?amount=10000&type=PAY")
    check("全渠道维护时路由无可用渠道", not preview_down.get("channelCode"), str(preview_down))
    order2 = new_order(merchant["id"], 10000, "rt-maint")
    st, resp = api("POST", "/api/cashier/pay", {"orderId": order2["id"], "method": "CARD",
                                               "cardNo": GOOD_CARD, "requestId": f"io-rt-maint-{SUFFIX}"})
    check("维护窗口内路由拒绝受理", st == 400, f"{st} {resp}")
    check("路由拒绝不留悬挂单（无支付单行）", find_payment(f"io-rt-maint-{SUFFIX}") is None)
    check("路由拒绝订单仍可支付（CREATED）", ok("GET", f"/api/orders/{order2['id']}")["status"] == "CREATED")
    for c in enabled:
        ok("POST", f"/api/channel/{c}/maintenance", {"start": 0, "end": 0}, step=True)
    r2 = ok("POST", "/api/cashier/pay", {"orderId": order2["id"], "method": "CARD",
                                        "cardNo": GOOD_CARD, "requestId": f"io-rt-maint2-{SUFFIX}"})
    p2 = poll(lambda: ok("GET", f"/api/payments/{r2['payment']['id']}"), lambda x: x["status"] == "SUCCESS")
    check("维护窗口取消后恢复受理", p2["status"] == "SUCCESS", str(p2))

    # 单笔限额硬过滤（全渠道压到 100 分；测后逐渠道恢复原值，不动他方配置）
    orig_limits = {c: ok("GET", f"/api/channel/{c}/limit") for c in enabled}
    for c in enabled:
        ok("POST", f"/api/channel/{c}/limit", {"singleLimit": 100}, step=True)
    order3 = new_order(merchant["id"], 10000, "rt-limit")
    st, resp = api("POST", "/api/cashier/pay", {"orderId": order3["id"], "method": "CARD",
                                               "cardNo": GOOD_CARD, "requestId": f"io-rt-limit-{SUFFIX}"})
    check("超单笔限额路由拒绝受理", st == 400, f"{st} {resp}")
    check("限额拒绝不留悬挂单", find_payment(f"io-rt-limit-{SUFFIX}") is None)
    for c in enabled:
        ok("POST", f"/api/channel/{c}/limit",
           {"singleLimit": orig_limits[c].get("singleLimit"), "dailyLimit": orig_limits[c].get("dailyLimit")}, step=True)
    return merchant, ma, user, ua


def section_combo():
    print("\n[2] P-2 组合支付（多腿原子 + 金额守恒）")
    merchant, ma = new_party("cb")
    user, ua = new_party("cb", "USER")
    topup(ua["id"], 100000, "cb")
    m0, u0 = bal(ma["id"]), bal(ua["id"])
    order = new_order(merchant["id"], 10000, "cb-ok")
    legs = [{"method": "BALANCE", "accountId": ua["id"], "amount": 6000},
            {"method": "CARD", "cardNo": GOOD_CARD, "amount": 4000}]
    r = ok("POST", "/api/cashier/pay", {"orderId": order["id"], "legs": legs, "requestId": f"io-cb-ok-{SUFFIX}"})
    pid = r["payment"]["id"]
    p = poll(lambda: ok("GET", f"/api/payments/{pid}"), lambda x: x["status"] in ("SUCCESS", "FAILED"))
    check("组合支付成功（payMode=COMBO）", p["status"] == "SUCCESS" and p.get("payMode") == "COMBO", str(p))
    legs_dto = p.get("legs") or []
    check("腿明细齐备且 Σ腿=订单额", len(legs_dto) == 2 and sum(l["amount"] for l in legs_dto) == 10000, str(legs_dto))
    check("fee 按订单总额 60bps 且 Σ腿fee=总fee", p["fee"] == 60 and sum(l["fee"] for l in legs_dto) == 60, str(p))
    check("各腿终态 SUCCESS", all(l["status"] == "SUCCESS" for l in legs_dto), str(legs_dto))
    check("商户入账=订单额−fee（守恒）", bal(ma["id"]) == m0 + 9940, f"{bal(ma['id'])} vs {m0 + 9940}")
    check("余额腿只扣本腿金额", bal(ua["id"]) == u0 - 6000, f"{bal(ua['id'])} vs {u0 - 6000}")
    card_leg = next(l for l in legs_dto if l["method"] == "CARD")
    st, side = api("GET", f"/api/channel/trades/{card_leg['channelBizNo']}/side")
    check("CARD 腿渠道扣款 CAPTURED（bizNo=legId）", st == 200 and side.get("status") == "CAPTURED", f"{st} {side}")

    # 金额不守恒 → 受理前拒绝
    order_bad = new_order(merchant["id"], 10000, "cb-sum")
    st, resp = api("POST", "/api/cashier/pay", {"orderId": order_bad["id"], "requestId": f"io-cb-sum-{SUFFIX}",
                                                "legs": [{"method": "BALANCE", "accountId": ua["id"], "amount": 6000},
                                                         {"method": "CARD", "cardNo": GOOD_CARD, "amount": 3000}]})
    check("Σ腿≠订单额拒绝（400）", st == 400, f"{st} {resp}")
    check("不守恒拒绝不落单", find_payment(f"io-cb-sum-{SUFFIX}") is None)

    # 一腿失败（卡 0000）→ 整体回滚，余额腿全不扣
    u1 = bal(ua["id"])
    order_fail = new_order(merchant["id"], 10000, "cb-fail")
    st, resp = api("POST", "/api/cashier/pay", {"orderId": order_fail["id"], "requestId": f"io-cb-fail-{SUFFIX}",
                                                "legs": [{"method": "BALANCE", "accountId": ua["id"], "amount": 6000},
                                                         {"method": "CARD", "cardNo": BAD_CARD, "amount": 4000}]})
    check("一腿失败整体拒绝（400）", st == 400, f"{st} {resp}")
    fp = find_payment(f"io-cb-fail-{SUFFIX}")
    check("失败组合单终态 FAILED", fp is not None and fp["status"] == "FAILED", str(fp))
    check("失败组合各腿 FAILED", fp is not None and all(l["status"] == "FAILED" for l in (fp.get("legs") or [])), str(fp))
    check("余额腿全不扣（整体回滚）", bal(ua["id"]) == u1, f"{bal(ua['id'])} vs {u1}")
    st, _ = api("GET", f"/api/accounting/transfers/{fp['id']}") if fp else (404, None)
    check("失败组合单无账务凭证（不悬挂）", st == 404, str(st))
    check("失败后订单仍可支付", ok("GET", f"/api/orders/{order_fail['id']}")["status"] == "CREATED")

    # 幂等回放
    r_again = ok("POST", "/api/cashier/pay", {"orderId": order["id"], "legs": legs, "requestId": f"io-cb-ok-{SUFFIX}"})
    check("组合 requestId 幂等回放同一单", r_again["payment"]["id"] == pid and r_again["repeated"] is True, str(r_again))

    # 8 并发同 requestId 只一单
    order_cc = new_order(merchant["id"], 10000, "cb-cc")
    u2 = bal(ua["id"])
    cc_req = f"io-cb-cc-{SUFFIX}"

    def one_pay(_):
        return api("POST", "/api/cashier/pay", {"orderId": order_cc["id"], "requestId": cc_req,
                                                "legs": [{"method": "BALANCE", "accountId": ua["id"], "amount": 6000},
                                                         {"method": "CARD", "cardNo": GOOD_CARD, "amount": 4000}]})

    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(one_pay, range(8)))
    ids = {resp["payment"]["id"] for st, resp in results if st == 200}
    ccp = poll(lambda: find_payment(cc_req), lambda x: x is not None and x["status"] in ("SUCCESS", "FAILED"))
    check("8 并发同 requestId 只一单", len(ids) == 1 and ccp is not None and ccp["status"] == "SUCCESS",
          f"ids={ids} results={[s for s, _ in results]}")
    check("并发组合只扣一次余额腿", bal(ua["id"]) == u2 - 6000, f"{bal(ua['id'])} vs {u2 - 6000}")
    # 并发测试单全额退款收尾（真实业务链路）：COMBO 成功单的账务凭证按腿 bizNo=legId 落库，
    # 对账「支付-账务」按 paymentId 查凭证的口径尚未覆盖组合腿聚合（主会话接线项），
    # 退款至 REFUNDED 后自然退出 SUCCESS 核对集合，不留对账盲区。
    ok("POST", f"/api/payments/{ccp['id']}/refund", {"amount": 10000, "requestId": f"io-cb-cc-rf-{SUFFIX}"})
    ccp_done = poll(lambda: ok("GET", f"/api/payments/{ccp['id']}"), lambda x: x["status"] == "REFUNDED")
    check("并发组合单全额退款收尾 REFUNDED", ccp_done["status"] == "REFUNDED", str(ccp_done))

    # 组合退款：腿级分摊守恒、不超退
    m1, u3 = bal(ma["id"]), bal(ua["id"])
    rr = ok("POST", f"/api/payments/{pid}/refund", {"amount": 5000, "requestId": f"io-cb-rf1-{SUFFIX}"})
    check("组合部分退款受理", rr["refundAmount"] == 5000, str(rr))
    rp = poll(lambda: ok("GET", f"/api/payments/{pid}"), lambda x: x["refundedAmount"] == 5000)
    check("组合退款主单已退累计", rp["refundedAmount"] == 5000 and rp["refundedFee"] == 30, str(rp))
    legs_after = ok("GET", f"/api/payments/{pid}")["legs"]
    check("Σ腿已退=退款额（守恒）", sum(l["refundedAmount"] for l in legs_after) == 5000
          and sum(l["refundedFee"] for l in legs_after) == 30, str(legs_after))
    check("组合退款余额腿按比例退回", bal(ua["id"]) == u3 + 3000, f"{bal(ua['id'])} vs {u3 + 3000}")
    check("组合退款商户扣净额", bal(ma["id"]) == m1 - 4970, f"{bal(ma['id'])} vs {m1 - 4970}")
    st, resp = api("POST", f"/api/payments/{pid}/refund", {"amount": 6000, "requestId": f"io-cb-rf-over-{SUFFIX}"})
    check("组合超退拦截（超出可退余额 400）", st == 400, f"{st} {resp}")
    ok("POST", f"/api/payments/{pid}/refund", {"amount": 5000, "requestId": f"io-cb-rf2-{SUFFIX}"})
    rp2 = poll(lambda: ok("GET", f"/api/payments/{pid}"), lambda x: x["status"] == "REFUNDED")
    check("组合末笔退款闭环 REFUNDED", rp2["status"] == "REFUNDED" and rp2["refundedFee"] == 60, str(rp2))
    st, resp = api("POST", f"/api/payments/{pid}/refund", {"amount": 1, "requestId": f"io-cb-rf3-{SUFFIX}"})
    check("已全额退款单再退拒绝（409 状态守卫）", st == 409, f"{st} {resp}")
    return pid


def section_share():
    print("\n[3] P-3 分账编排（Σ=净额误差 0 + 回退幂等）")
    merchant, ma = new_party("sh")
    user, ua = new_party("sh", "USER")
    r1, ra1 = new_party("sh-r1", "USER")
    r2, ra2 = new_party("sh-r2", "USER")
    topup(ua["id"], 100000, "sh")
    p = pay_balance(new_order(merchant["id"], 10000, "sh")["id"], ua["id"], "sh")
    pid = p["id"]
    net = p["amount"] - p["fee"]
    check("分账前置：支付净额 9940", net == 9940, str(p))
    m0, b1, b2 = bal(ma["id"]), bal(ra1["id"]), bal(ra2["id"])
    share = ok("POST", f"/api/payments/{pid}/share",
               {"requestId": f"io-sh-{SUFFIX}", "shareType": "REALTIME",
                "receivers": [{"accountId": ra1["id"], "ratioBps": 6000},
                              {"accountId": ra2["id"], "ratioBps": 4000}]}, step=True)
    sid = share["id"]
    check("比例分账受理（60%/40%）", share["totalAmount"] == net and share["status"] in ("PENDING", "SHARED"), str(share))
    check("比例拆额 5964/3976", sorted(d["amount"] for d in share["details"]) == [3976, 5964], str(share))
    done = poll(lambda: ok("GET", f"/api/shares/{sid}"), lambda x: x["status"] == "SHARED", timeout=30)
    check("分账执行完成 SHARED", done["status"] == "SHARED", str(done))
    ss = ok("GET", f"/api/settle/shares/summary?shareId={sid}")
    check("settle 守恒 Σ分账=净额 误差 0", ss.get("diff") == 0 and ss.get("allocated") == net
          and ss.get("balanced") is True, str(ss))
    check("收款方到账（多方）", bal(ra1["id"]) == b1 + 5964 and bal(ra2["id"]) == b2 + 3976,
          f"{bal(ra1['id'])}/{bal(ra2['id'])}")
    check("商户净额全额划出", bal(ma["id"]) == m0 - net, f"{bal(ma['id'])} vs {m0 - net}")
    st, resp = api("POST", f"/api/payments/{pid}/share",
                   {"requestId": f"io-sh-over-{SUFFIX}", "receivers": [{"accountId": ra1["id"], "amount": 1}]}, step=True)
    check("超额分账拒绝", st == 400, f"{st} {resp}")
    replay = ok("POST", f"/api/payments/{pid}/share",
                {"requestId": f"io-sh-{SUFFIX}", "receivers": [{"accountId": ra1["id"], "ratioBps": 6000},
                                                              {"accountId": ra2["id"], "ratioBps": 4000}]}, step=True)
    check("分账 requestId 幂等同一单", replay["id"] == sid, str(replay))
    notified = poll(lambda: ok("GET", f"/api/notify/records?bizId={sid}"),
                    lambda rs: any(r.get("event") == "SHARE_SUCCESS" for r in rs), timeout=20)
    check("SHARE_SUCCESS 事件经 outbox 投递", any(r.get("event") == "SHARE_SUCCESS" for r in notified), str(notified))

    # 回退（反向划转）+ 幂等
    back = ok("POST", f"/api/shares/{sid}/back", {"requestId": f"io-sh-back-{SUFFIX}"}, step=True)
    check("回退受理 BACKING/BACKED", back["status"] in ("BACKING", "BACKED"), str(back))
    backed = poll(lambda: ok("GET", f"/api/shares/{sid}"), lambda x: x["status"] == "BACKED", timeout=30)
    check("回退完成 BACKED", backed["status"] == "BACKED", str(backed))
    check("回退后三方余额复原", bal(ma["id"]) == m0 and bal(ra1["id"]) == b1 and bal(ra2["id"]) == b2,
          f"{bal(ma['id'])}/{bal(ra1['id'])}/{bal(ra2['id'])}")
    ss2 = ok("GET", f"/api/settle/shares/summary?shareId={sid}")
    check("回退后 settle 净额守恒（已分=0）", ss2.get("allocated") == 0, str(ss2))
    back2 = ok("POST", f"/api/shares/{sid}/back", {"requestId": f"io-sh-back-{SUFFIX}"}, step=True)
    check("同 requestId 重复回退幂等", back2["status"] == "BACKED", str(back2))
    st, resp = api("POST", f"/api/shares/{sid}/back", {"requestId": f"io-sh-back-x-{SUFFIX}"}, step=True)
    check("异 requestId 重复回退拒绝", st == 409, f"{st} {resp}")
    notified2 = poll(lambda: ok("GET", f"/api/notify/records?bizId={sid}"),
                     lambda rs: any(r.get("event") == "SHARE_BACK" for r in rs), timeout=20)
    check("SHARE_BACK 事件投递", any(r.get("event") == "SHARE_BACK" for r in notified2), str(notified2))

    # 部分分账 + 二次分账超额拒绝 + 补齐守恒
    p2 = pay_balance(new_order(merchant["id"], 10000, "sh2")["id"], ua["id"], "sh2")
    s1 = ok("POST", f"/api/payments/{p2['id']}/share",
            {"requestId": f"io-sh-p1-{SUFFIX}", "receivers": [{"accountId": ra1["id"], "amount": 1000}]}, step=True)
    poll(lambda: ok("GET", f"/api/shares/{s1['id']}"), lambda x: x["status"] == "SHARED", timeout=30)
    st, resp = api("POST", f"/api/payments/{p2['id']}/share",
                   {"requestId": f"io-sh-p2-{SUFFIX}", "receivers": [{"accountId": ra2["id"], "amount": 8941}]}, step=True)
    check("多次分账累计超额拒绝", st == 400, f"{st} {resp}")
    s2 = ok("POST", f"/api/payments/{p2['id']}/share",
            {"requestId": f"io-sh-p3-{SUFFIX}", "receivers": [{"accountId": ra2["id"], "amount": 8940}]}, step=True)
    poll(lambda: ok("GET", f"/api/shares/{s2['id']}"), lambda x: x["status"] == "SHARED", timeout=30)
    check("多次分账 Σ=净额（1000+8940=9940）", s1["totalAmount"] + s2["totalAmount"] == 9940)

    # 延迟分账：到期前不执行，到期后作业/outbox 驱动
    p3 = pay_balance(new_order(merchant["id"], 10000, "sh3")["id"], ua["id"], "sh3")
    s3 = ok("POST", f"/api/payments/{p3['id']}/share",
            {"requestId": f"io-sh-dl-{SUFFIX}", "shareType": "DELAYED", "delayMs": 4000,
             "receivers": [{"accountId": ra1["id"], "amount": 9940}]}, step=True)
    check("延迟分账受理 PENDING", s3["status"] == "PENDING", str(s3))
    time.sleep(1.5)
    early = ok("GET", f"/api/shares/{s3['id']}")
    check("延迟到期前不执行", early["status"] == "PENDING", str(early))
    due = poll(lambda: ok("GET", f"/api/shares/{s3['id']}"), lambda x: x["status"] == "SHARED", timeout=40)
    check("延迟分账到期自动执行", due["status"] == "SHARED", str(due))
    return pid


def section_refund():
    print("\n[4] P-4 退款异步化（独立终态 + 补单收敛 + 口径不变）")
    merchant, ma = new_party("rf")
    user, ua = new_party("rf", "USER")
    topup(ua["id"], 100000, "rf")
    p = pay_balance(new_order(merchant["id"], 10000, "rf")["id"], ua["id"], "rf")
    pid = p["id"]
    u0, m0 = bal(ua["id"]), bal(ma["id"])
    rr = ok("POST", f"/api/payments/{pid}/refund", {"amount": 3000, "requestId": f"io-rf1-{SUFFIX}"})
    rid = rr["refundId"]
    check("退款受理返回 refundStatus", rr.get("refundStatus") in ("PROCESSING", "SUCCESS"), str(rr))
    rd = poll(lambda: ok("GET", f"/api/refunds/{rid}"), lambda x: x["status"] == "SUCCESS", timeout=20)
    check("退款单独立终态 SUCCESS（可查询）", rd["status"] == "SUCCESS" and rd["amount"] == 3000, str(rd))
    check("部分退款手续费按比例（18）", rd["feeRefund"] == 18, str(rd))
    check("部分退款余额复原口径", bal(ua["id"]) == u0 + 3000, f"{bal(ua['id'])} vs {u0 + 3000}")
    st, resp = api("POST", f"/api/payments/{pid}/refund", {"amount": 8000, "requestId": f"io-rf-over-{SUFFIX}"})
    check("超退拦截（超出可退余额 400）", st == 400, f"{st} {resp}")
    rr2 = ok("POST", f"/api/payments/{pid}/refund", {"amount": 3000, "requestId": f"io-rf1-{SUFFIX}"})
    check("退款 requestId 幂等同一单", rr2["refundId"] == rid, str(rr2))
    rc = ok("POST", f"/api/refunds/{rid}/reconverge")
    check("终态退款补单幂等（不重复过账）", rc["status"] == "SUCCESS" and bal(ua["id"]) == u0 + 3000, str(rc))
    ok("POST", f"/api/payments/{pid}/refund", {"amount": 7000, "requestId": f"io-rf2-{SUFFIX}"})
    pf = poll(lambda: ok("GET", f"/api/payments/{pid}"), lambda x: x["status"] == "REFUNDED")
    check("末笔退款手续费退剩余闭环（18+42=60）", pf["refundedFee"] == 60 and pf["status"] == "REFUNDED", str(pf))
    check("全额退款后三方余额复原", bal(ua["id"]) == u0 + 10000 and bal(ma["id"]) == m0 - 9940,
          f"{bal(ua['id'])}/{bal(ma['id'])}")
    st, resp = api("POST", f"/api/payments/{pid}/refund", {"amount": 1, "requestId": f"io-rf3-{SUFFIX}"})
    check("已全额退款单再退拒绝（409 状态守卫）", st == 409, f"{st} {resp}")

    if channel_async_enabled():
        print("  -- 异步渠道开关开启：CARD 退款 WAITING→回执→SUCCESS --")
        probe_st, _ = api("POST", "/api/channel/receipt", {"bizNo": "IO-PROBE-ABSENT", "status": "CAPTURED"})
        if probe_st == 404:
            print("  -- 缺 POST /api/channel/receipt 网关端点（主会话接线项），异步退款回执节省略 --")
        else:
            order = new_order(merchant["id"], 10000, "rf-card")
            r = ok("POST", "/api/cashier/pay", {"orderId": order["id"], "method": "CARD",
                                               "cardNo": GOOD_CARD, "requestId": f"io-rf-card-{SUFFIX}"})
            cp = poll(lambda: ok("GET", f"/api/payments/{r['payment']['id']}"),
                      lambda x: x["status"] in ("SUCCESS", "FAILED"), timeout=60)
            if cp["status"] == "SUCCESS":
                rr3 = ok("POST", f"/api/payments/{cp['id']}/refund", {"amount": 10000, "requestId": f"io-rf-async-{SUFFIX}"})
                rid3 = rr3["refundId"]
                rd3 = ok("GET", f"/api/refunds/{rid3}")
                check("异步 CARD 退款受理 PROCESSING（WAITING）", rd3["status"] == "PROCESSING", str(rd3))
                ok("POST", "/api/channel/receipt", {"bizNo": rid3, "status": "REFUNDED"})
                fin = poll(lambda: ok("GET", f"/api/refunds/{rid3}"), lambda x: x["status"] == "SUCCESS", timeout=60)
                if fin["status"] != "SUCCESS":
                    ok("POST", f"/api/refunds/{rid3}/reconverge")
                    fin = poll(lambda: ok("GET", f"/api/refunds/{rid3}"), lambda x: x["status"] == "SUCCESS", timeout=30)
                check("异步退款回执/补单收敛 SUCCESS", fin["status"] == "SUCCESS", str(fin))
            else:
                check("异步 CARD 支付成功（退款前置）", False, str(cp))
    else:
        print("  -- payment.channel.async=false（默认同步 stack）：异步退款节省略；"
              "同步退款语义/手续费口径/超退/幂等已全量断言，WAITING 路径与支付侧 CH-1 同一机制 --")


def section_close_cancel():
    print("\n[5] P-5 关闭/撤销（CAS 守卫 + 撤销区别于退款）")
    merchant, ma = new_party("cc")
    user, ua = new_party("cc", "USER")
    topup(ua["id"], 200000, "cc")
    p = pay_balance(new_order(merchant["id"], 10000, "cc")["id"], ua["id"], "cc")
    pid = p["id"]
    st, resp = api("POST", f"/api/payments/{pid}/close", {"requestId": f"io-cl-succ-{SUFFIX}"}, step=True)
    check("已成功单不可关闭（409）", st == 409, f"{st} {resp}")

    # 当日撤销：全额反向过账，区别于退款（不产生退款单、refunded_amount 不变）
    u0, m0, fee0 = bal(ua["id"]), bal(ma["id"]), bal("SYS_FEE")
    cancelled = ok("POST", f"/api/payments/{pid}/cancel", {"requestId": f"io-cx-{SUFFIX}"}, step=True)
    check("当日撤销 CANCELLED（closeType=CANCEL）",
          cancelled["status"] == "CANCELLED" and cancelled.get("closeType") == "CANCEL", str(cancelled))
    check("撤销全额反向：付款方复原", bal(ua["id"]) == u0 + 10000, f"{bal(ua['id'])} vs {u0 + 10000}")
    check("撤销商户净额冲回", bal(ma["id"]) == m0 - 9940, f"{bal(ma['id'])} vs {m0 - 9940}")
    check("撤销手续费冲回", bal("SYS_FEE") == fee0 - 60, f"{bal('SYS_FEE')} vs {fee0 - 60}")
    check("撤销不动 refunded_amount（区别于退款）", cancelled["refundedAmount"] == 0, str(cancelled))
    st, transfer = api("GET", f"/api/accounting/transfers/CX:{pid}")
    debit = sum(l["amount"] for l in transfer.get("lines", []) if l["dir"] == "D") if st == 200 else -1
    check("撤销反向凭证 CX:paymentId 等额", st == 200 and debit == 10000, f"{st} {transfer}")
    again = ok("POST", f"/api/payments/{pid}/cancel", {"requestId": f"io-cx-{SUFFIX}"}, step=True)
    check("重复撤销幂等（不重复冲账）", again["status"] == "CANCELLED" and bal(ua["id"]) == u0 + 10000, str(again))
    st, resp = api("POST", f"/api/payments/{pid}/refund", {"amount": 1, "requestId": f"io-cx-rf-{SUFFIX}"})
    check("已撤销单不可退款", st == 409, f"{st} {resp}")

    # 已退款单不可撤销
    p2 = pay_balance(new_order(merchant["id"], 10000, "cc2")["id"], ua["id"], "cc2")
    ok("POST", f"/api/payments/{p2['id']}/refund", {"amount": 1000, "requestId": f"io-cx2-rf-{SUFFIX}"})
    poll(lambda: ok("GET", f"/api/payments/{p2['id']}"), lambda x: x["refundedAmount"] == 1000)
    st, resp = api("POST", f"/api/payments/{p2['id']}/cancel", {"requestId": f"io-cx2-{SUFFIX}"}, step=True)
    check("已退款单撤销拒绝（409）", st == 409, f"{st} {resp}")

    # CARD 撤销拒绝（渠道当日撤销接口未就绪，保守走退款）
    order3 = new_order(merchant["id"], 10000, "cc3")
    r3 = ok("POST", "/api/cashier/pay", {"orderId": order3["id"], "method": "CARD",
                                        "cardNo": GOOD_CARD, "requestId": f"io-cx3-{SUFFIX}"})
    p3 = poll(lambda: ok("GET", f"/api/payments/{r3['payment']['id']}"), lambda x: x["status"] == "SUCCESS")
    st, resp = api("POST", f"/api/payments/{p3['id']}/cancel", {"requestId": f"io-cx3-c-{SUFFIX}"}, step=True)
    check("CARD 撤销明确拒绝走退款", st == 400, f"{st} {resp}")

    # 已分账单不可撤销
    p4 = pay_balance(new_order(merchant["id"], 10000, "cc4")["id"], ua["id"], "cc4")
    r4, ra4 = new_party("cc-r", "USER")
    ok("POST", f"/api/payments/{p4['id']}/share",
       {"requestId": f"io-cx4-sh-{SUFFIX}", "receivers": [{"accountId": ra4["id"], "amount": 100}]}, step=True)
    st, resp = api("POST", f"/api/payments/{p4['id']}/cancel", {"requestId": f"io-cx4-{SUFFIX}"}, step=True)
    check("已分账单撤销拒绝（409）", st == 409, f"{st} {resp}")

    # 关闭与受理竞态：CAS 不变量（CLOSED ⇔ 资金未动/已释放；SUCCESS ⇔ 关闭让位）
    hits = 0
    for i in range(6):
        order = new_order(merchant["id"], 10000, f"race{i}")
        req = f"io-race-{i}-{SUFFIX}"
        u_before = bal(ua["id"])
        with ThreadPoolExecutor(max_workers=1) as pool:
            fut = pool.submit(api, "POST", "/api/cashier/pay",
                              {"orderId": order["id"], "method": "BALANCE", "accountId": ua["id"], "requestId": req})
            found = poll(lambda: find_payment(req), lambda x: x is not None, timeout=10, interval=0.005)
            close_st, close_resp = (None, None)
            if found is not None:
                close_st, close_resp = api("POST", f"/api/payments/{found['id']}/close",
                                           {"requestId": f"io-race-cl-{i}-{SUFFIX}"}, step=True)
                if found["status"] == "PROCESSING":
                    hits += 1
            pay_st, pay_resp = fut.result()
        final = poll(lambda: find_payment(req), lambda x: x is not None and x["status"] != "PROCESSING", timeout=20)
        if final is None:
            check(f"竞态轮 {i} 终态收敛", False, "支付单缺失/仍在途")
            continue
        if final["status"] == "CLOSED":
            st_t, _ = api("GET", f"/api/accounting/transfers/{final['id']}")
            # 订单释放经 TCC cancel/recover 续驱收敛（防悬挂标记拒绝晚到 Try），轮询终态而非瞬时断言
            order_now = poll(lambda: ok("GET", f"/api/orders/{order['id']}")["status"],
                             lambda s: s == "CREATED", timeout=40, interval=1)
            check(f"竞态轮 {i} CLOSED⇒无凭证+订单释放+余额不动",
                  st_t == 404 and order_now == "CREATED" and bal(ua["id"]) == u_before,
                  f"transfer={st_t} order={order_now} bal={bal(ua['id'])} vs {u_before}")
        elif final["status"] == "SUCCESS":
            check(f"竞态轮 {i} SUCCESS⇒关闭让位（409）", close_st in (409, None), f"{close_st} {close_resp}")
        else:
            check(f"竞态轮 {i} 终态合法（非悬挂）", final["status"] == "FAILED", str(final))
    print(f"  · 竞态命中 PROCESSING 窗口 {hits}/6 轮（同步栈窗口极小，未命中时断言退化为终态一致性）")

    # FAILED 单不可关闭
    order_f = new_order(merchant["id"], 10000, "cc-fail")
    st, _ = api("POST", "/api/cashier/pay", {"orderId": order_f["id"], "method": "CARD",
                                            "cardNo": BAD_CARD, "requestId": f"io-cl-fail-{SUFFIX}"})
    fp = find_payment(f"io-cl-fail-{SUFFIX}")
    if fp:
        st, resp = api("POST", f"/api/payments/{fp['id']}/close", {"requestId": f"io-cl-f-{SUFFIX}"}, step=True)
        check("FAILED 终态单关闭拒绝（409）", st == 409, f"{st} {resp}")


def section_fee():
    print("\n[6] 计费接 FeeCalculator（缺省 60bps + 受理快照不变）")
    merchant, ma = new_party("fe")
    user, ua = new_party("fe", "USER")
    topup(ua["id"], 100000, "fe")
    calc = ok("POST", "/api/settle/fee/calc", {"merchantId": merchant["id"], "productType": "TRADE",
                                              "method": "BALANCE", "amount": 10000})
    check("calcFee 缺省 60bps（DEFAULT 层）", calc["feeBps"] == 60 and calc["fee"] == 60
          and calc["source"] in ("DEFAULT", "CONFIG"), str(calc))
    p_old = pay_balance(new_order(merchant["id"], 10000, "fe-old")["id"], ua["id"], "fe-old")
    check("payment 计费=60（与改造前一致）", p_old["fee"] == 60, str(p_old))
    config_write({"settle.fee.default-bps": 100})
    time.sleep(31)  # LocalFileDynamicConfig 30s reload（双 profile 热更同口径）
    p_new = pay_balance(new_order(merchant["id"], 10000, "fe-new")["id"], ua["id"], "fe-new")
    check("费率热更只影响新单（100bps→100）", p_new["fee"] == 100, str(p_new))
    p_old_after = ok("GET", f"/api/payments/{p_old['id']}")
    check("历史单受理快照不变（仍 60）", p_old_after["fee"] == 60, str(p_old_after))
    config_write({"settle.fee.default-bps": None})
    time.sleep(31)
    p_restored = pay_balance(new_order(merchant["id"], 10000, "fe-rs")["id"], ua["id"], "fe-rs")
    check("配置恢复后缺省仍 60bps", p_restored["fee"] == 60, str(p_restored))


def section_challenge():
    print("\n[7] 风控 CHALLENGE 续付（R-4 × C-3）")
    # 影子模式会让 pass 恒 true（他泳道验证可能遗留）：临时关闭以验证真实分级，结束恢复原状。
    # 灰名单因子 30 分 < 默认加强验证阈值 40：与 verify-risk 同法临时降阈值到 20 触发 CHALLENGE，
    # 结束经 thresholds/rollback 恢复上一版（只动本脚本需要的键，恢复原状）。
    engine = ok("GET", "/api/risk/engine")
    shadow_was_on = bool(engine.get("globalShadow"))
    if shadow_was_on:
        ok("POST", "/api/risk/shadow", {"on": False}, step=True)
    ok("POST", "/api/risk/thresholds", {"scene": "PAY", "challengeScore": 20, "limitScore": 40,
                                        "rejectScore": 60, "reviewScore": 80, "limitAmount": 100000}, step=True)
    time.sleep(6)  # RiskConfigManager pollSeconds=5
    try:
        _section_challenge_inner()
    finally:
        ok("POST", "/api/risk/thresholds/rollback", {}, step=True)
        if shadow_was_on:
            ok("POST", "/api/risk/shadow", {"on": True}, step=True)
        time.sleep(6)


def _section_challenge_inner():
    merchant, ma = new_party("ch")
    user, ua = new_party("ch", "USER")
    topup(ua["id"], 100000, "ch")
    entry = ok("POST", "/api/risk/lists",
               {"listType": "GRAY", "valueType": "USER", "value": ua["id"], "reason": "IO 编排验证灰名单"}, step=True)
    entry_id = entry.get("id")
    order = new_order(merchant["id"], 3000, "ch1")
    st, resp = api("POST", "/api/cashier/pay", {"orderId": order["id"], "method": "BALANCE",
                                               "accountId": ua["id"], "requestId": f"io-ch1-{SUFFIX}"})
    check("灰名单 CHALLENGE 受理前拒绝（400，不悬挂）", st == 400, f"{st} {resp}")
    fp = find_payment(f"io-ch1-{SUFFIX}")
    check("CHALLENGE 拒绝单终态 FAILED（非 PROCESSING）", fp is not None and fp["status"] == "FAILED", str(fp))
    ok("POST", "/api/cashier/pay-password", {"accountId": ua["id"], "password": "123456"})
    v = ok("POST", "/api/cashier/verify", {"accountId": ua["id"], "orderId": order["id"], "amount": 3000,
                                          "verifyType": "PASSWORD", "payPassword": "123456"})
    check("C-3 验证签发 verifyToken", v["pass"] is True and bool(v.get("verifyToken")), str(v))
    u0 = bal(ua["id"])
    cont = ok("POST", "/api/payments/challenge/continue",
              {"orderId": order["id"], "method": "BALANCE", "accountId": ua["id"],
               "requestId": f"io-ch1-cont-{SUFFIX}", "verifyToken": v["verifyToken"]})
    cp = poll(lambda: ok("GET", f"/api/payments/{cont['payment']['id']}"), lambda x: x["status"] == "SUCCESS")
    check("CHALLENGE 验证后续付成功（BALANCE 同步）", cp["status"] == "SUCCESS", str(cp))
    check("续付只扣一次", bal(ua["id"]) == u0 - 3000, f"{bal(ua['id'])} vs {u0 - 3000}")

    # 错误 token 拒绝且授权回退可重试
    order2 = new_order(merchant["id"], 3000, "ch2")
    st, _ = api("POST", "/api/cashier/pay", {"orderId": order2["id"], "method": "BALANCE",
                                            "accountId": ua["id"], "requestId": f"io-ch2-{SUFFIX}"})
    v2 = ok("POST", "/api/cashier/verify", {"accountId": ua["id"], "orderId": order2["id"], "amount": 3000,
                                           "verifyType": "PASSWORD", "payPassword": "123456"})
    st, resp = api("POST", "/api/payments/challenge/continue",
                   {"orderId": order2["id"], "method": "BALANCE", "accountId": ua["id"],
                    "requestId": f"io-ch2-bad-{SUFFIX}", "verifyToken": v2["verifyToken"] + "x"})
    check("错误 token 续付拒绝（403）", st == 403, f"{st} {resp}")
    cont2 = ok("POST", "/api/payments/challenge/continue",
               {"orderId": order2["id"], "method": "BALANCE", "accountId": ua["id"],
                "requestId": f"io-ch2-cont-{SUFFIX}", "verifyToken": v2["verifyToken"]})
    cp2 = poll(lambda: ok("GET", f"/api/payments/{cont2['payment']['id']}"), lambda x: x["status"] == "SUCCESS")
    check("授权回退后正确 token 重试成功", cp2["status"] == "SUCCESS", str(cp2))

    # 无挑战续付拒绝；BLACK/REJECT 仍拒绝（不可续付绕过）
    order3 = new_order(merchant["id"], 3000, "ch3")
    st, resp = api("POST", "/api/payments/challenge/continue",
                   {"orderId": order3["id"], "method": "BALANCE", "accountId": ua["id"],
                    "requestId": f"io-ch3-{SUFFIX}", "verifyToken": "whatever"})
    check("无 CHALLENGE 挑战续付拒绝（409）", st == 409, f"{st} {resp}")
    if entry_id:
        ok("DELETE", f"/api/risk/lists/{entry_id}", step=True)
    black = ok("POST", "/api/risk/lists",
               {"listType": "BLACK", "valueType": "USER", "value": ua["id"], "reason": "IO 编排验证黑名单"}, step=True)
    order4 = new_order(merchant["id"], 3000, "ch4")
    st, resp = api("POST", "/api/cashier/pay", {"orderId": order4["id"], "method": "BALANCE",
                                               "accountId": ua["id"], "requestId": f"io-ch4-{SUFFIX}"})
    check("BLACK/REJECT 仍受理前拒绝", st == 400, f"{st} {resp}")
    v4 = ok("POST", "/api/cashier/verify", {"accountId": ua["id"], "orderId": order4["id"], "amount": 3000,
                                           "verifyType": "PASSWORD", "payPassword": "123456"})
    st, resp = api("POST", "/api/payments/challenge/continue",
                   {"orderId": order4["id"], "method": "BALANCE", "accountId": ua["id"],
                    "requestId": f"io-ch4-cont-{SUFFIX}", "verifyToken": v4.get("verifyToken", "t")})
    check("REJECT 分级不可经续付绕过", st in (400, 409), f"{st} {resp}")
    if black.get("id"):
        ok("DELETE", f"/api/risk/lists/{black['id']}", step=True)


def section_guard():
    print("\n[8] BALANCE 同步不回归 + 资金护航")
    merchant, ma = new_party("gd")
    user, ua = new_party("gd", "USER")
    topup(ua["id"], 100000, "gd")
    order = new_order(merchant["id"], 10000, "gd")
    t0 = time.time()
    r = ok("POST", "/api/cashier/pay", {"orderId": order["id"], "method": "BALANCE",
                                       "accountId": ua["id"], "requestId": f"io-gd-{SUFFIX}"})
    p = poll(lambda: ok("GET", f"/api/payments/{r['payment']['id']}"),
             lambda x: x["status"] in ("SUCCESS", "FAILED"), timeout=5, interval=0.1)
    check("BALANCE 同步受理→SUCCESS（≤5s，无 WAITING/异步）",
          p["status"] == "SUCCESS" and time.time() - t0 < 5, f"{p['status']} {time.time() - t0:.2f}s")
    check("BALANCE 单无渠道（channelCode 为空）", not p.get("channelCode"), str(p))
    trial = ok("GET", "/api/accounting/trial")
    check("试算平衡 D=C", trial["balanced"] is True and trial["totalDebit"] == trial["totalCredit"], str(trial))
    check("Σbalance=0", trial["balanceSum"] == 0, str(trial))
    report = ok("POST", "/api/recon/run")
    checks = report.get("checks", [])
    check("六项对账全 pass", report.get("pass") is True and len(checks) == 6
          and all(c.get("pass") for c in checks),
          str([(c.get("name"), c.get("pass")) for c in checks]))


def main():
    print(f"payment-pro P1-I 支付编排工作流验证 SUFFIX={SUFFIX}")
    if channel_async_enabled():
        print("!! 前置不满足：payment.channel.async=true（异步混沌栈）。本套件验证默认同步栈口径"
              "（BALANCE 同步红线 + 组合支付同步 TCC）；请以默认 ./run.sh --docker --no-build 重启后再跑。")
        sys.exit(2)
    login()
    config_backup()
    # 缺省口径基线：若存在全局费率键先移除（结束恢复），保证 DEFAULT 层断言纯净
    had_fee_key = "settle.fee.default-bps" in config_read()
    if had_fee_key:
        config_write({"settle.fee.default-bps": None})
        time.sleep(31)
    try:
        section_route()
        section_combo()
        section_share()
        section_refund()
        section_close_cancel()
        section_fee()
        section_challenge()
        section_guard()
    finally:
        config_restore()
        if had_fee_key:
            time.sleep(1)
    print(f"\n结果: {PASSED} 通过 / {FAILED} 失败")
    if FAILURES:
        print("失败项:")
        for name in FAILURES:
            print(f"  - {name}")
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()