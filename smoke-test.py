#!/usr/bin/env python3
# payment-pro 跨服务冒烟测试：全部经由网关 http://127.0.0.1:8080
# TCC 二阶段异步 Confirm + outbox 异步投递，断言前均轮询终态。
# 用法：先 ./run.sh 启动，再 python3 smoke-test.py（可重复执行，断言基于增量）
import hashlib
import hmac
import json
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BASE = "http://127.0.0.1:8080"
SUFFIX = uuid.uuid4().hex[:6]
PASSED = 0
FAILED = 0


def check(name, cond, detail=""):
    global PASSED, FAILED
    if cond:
        PASSED += 1
        print(f"  ✓ {name}")
    else:
        FAILED += 1
        print(f"  ✗ {name}  {detail}")


def req(method, path, body=None, raw=None):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    r = urllib.request.Request(BASE + path, data=data, method=method,
                               headers={"Content-Type": "application/json"})
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


def ok(method, path, body=None):
    st, resp = req(method, path, body)
    assert st == 200, f"{method} {path} 期望 200，实际 {st}: {resp}"
    return resp


def bal(account_id):
    return ok("GET", f"/api/accounting/accounts/{account_id}/balance")


def poll(fn, pred, timeout=20, interval=0.4):
    deadline = time.time() + timeout
    val = fn()
    while time.time() < deadline and not pred(val):
        time.sleep(interval)
        val = fn()
    return val


def wait_payment(pid, status):
    return poll(lambda: ok("GET", f"/api/payments/{pid}"),
                lambda p: p["status"] == status)


def wait_order(oid, status):
    return poll(lambda: ok("GET", f"/api/orders/{oid}"),
                lambda o: o["status"] == status)


def fee_of(amount):
    return (amount * 60 + 5000) // 10000


def sign_params(params, secret):
    items = sorted((k, v) for k, v in params.items() if v is not None and k != "sign")
    data = "&".join(f"{k}={v}" for k, v in items)
    return hmac.new(secret.encode(), data.encode(), hashlib.sha256).hexdigest()


def main():
    print(f"== payment-pro 冒烟测试（批次 {SUFFIX}）==")

    # ---------- 1. 健康检查 ----------
    print("[1] 13 个进程健康检查（Dubbo 探针）")
    health = poll(lambda: ok("GET", "/api/health"), lambda h: all(x["ok"] for x in h), timeout=90, interval=2)
    check("网关聚合 13 个进程", len(health) == 13, str(len(health)))
    down = [h["service"] for h in health if not h["ok"]]
    check("全部服务在线", not down, ",".join(down))

    # ---------- 2. 会员与账户 ----------
    print("[2] 会员域 / 账户域")
    merchant = ok("POST", "/api/members", {"name": f"冒烟商户{SUFFIX}", "type": "MERCHANT"})
    user1 = ok("POST", "/api/members", {"name": f"冒烟用户A{SUFFIX}", "type": "USER"})
    user2 = ok("POST", "/api/members", {"name": f"冒烟用户B{SUFFIX}", "type": "USER"})
    user3 = ok("POST", "/api/members", {"name": f"冒烟用户C{SUFFIX}", "type": "USER"})
    check("商户获得 appId/appSecret", bool(merchant.get("appId")) and bool(merchant.get("appSecret")))
    ma = ok("POST", "/api/accounts", {"memberId": merchant["id"]})
    ua1 = ok("POST", "/api/accounts", {"memberId": user1["id"]})
    ua2 = ok("POST", "/api/accounts", {"memberId": user2["id"]})
    ua3 = ok("POST", "/api/accounts", {"memberId": user3["id"]})
    check("开户成功且类型正确", ma["type"] == "MERCHANT" and ua1["type"] == "USER")
    st, resp = req("POST", "/api/accounts", {"memberId": user1["id"]})
    check("重复开户拒绝 409", st == 409, str(resp))
    st, resp = req("POST", "/api/orders", {"merchantId": user1["id"], "amount": 100, "subject": "x"})
    check("USER 不能收款下单 400", st == 400, str(resp))

    # ---------- 3. 充值与账务约束 ----------
    print("[3] 账务域：充值 / 幂等 / 借贷平衡")
    cash0, fee0 = bal("SYS_CASH"), bal("SYS_FEE")
    m0, u10, u20, u30 = bal(ma["id"]), bal(ua1["id"]), bal(ua2["id"]), bal(ua3["id"])
    settle0 = ok("GET", "/api/settle/summary")
    topup_biz = "topup-" + SUFFIX
    r = ok("POST", "/api/accounting/internal/topup", {"accountId": ua1["id"], "amount": 10000, "bizNo": topup_biz})
    check("充值 10000 分到账", r["balance"] == u10 + 10000, str(r))
    r = ok("POST", "/api/accounting/internal/topup", {"accountId": ua1["id"], "amount": 10000, "bizNo": topup_biz})
    check("充值 bizNo 幂等（余额不重复增加）", r["balance"] == u10 + 10000 and bal(ua1["id"]) == u10 + 10000, str(r))
    ok("POST", "/api/accounting/internal/topup", {"accountId": ua2["id"], "amount": 500, "bizNo": "topup2-" + SUFFIX})
    ok("POST", "/api/accounting/internal/topup", {"accountId": ua3["id"], "amount": 100, "bizNo": "topup3-" + SUFFIX})
    check("SYS_CASH 外部现金等额减少", bal("SYS_CASH") == cash0 - 10600)
    st, resp = req("POST", "/api/accounting/internal/topup", {"accountId": ua1["id"], "amount": 0, "bizNo": "bad-" + SUFFIX})
    check("非法金额充值拒绝 400", st == 400, str(resp))

    # ---------- 4. 余额支付全链路（TCC） ----------
    print("[4] 余额支付：收银台 → 风控 → TCC(账务/交易) → outbox → 清结算/通知")
    order_a = ok("POST", "/api/orders", {"merchantId": merchant["id"], "subject": "余额支付商品", "amount": 3000})["order"]
    cashier = ok("GET", f"/api/cashier?orderId={order_a['id']}")
    methods = {m["method"]: m for m in cashier["methods"]}
    check("收银台聚合订单与支付方式", cashier["order"]["id"] == order_a["id"]
          and "BALANCE" in methods and "CARD" in methods)
    check("收银台列出可用余额账户", any(a["id"] == ua1["id"] for a in methods["BALANCE"]["accounts"]))
    req_a = "req-a-" + SUFFIX
    r = ok("POST", "/api/cashier/pay", {"orderId": order_a["id"], "method": "BALANCE",
                                        "accountId": ua1["id"], "requestId": req_a})
    pa = wait_payment(r["payment"]["id"], "SUCCESS")
    check("TCC Confirm 后支付 SUCCESS 且手续费 18 分", pa["status"] == "SUCCESS" and pa["fee"] == 18, str(pa))
    check("付款方扣 3000", bal(ua1["id"]) == u10 + 10000 - 3000)
    check("商户净额 +2982", bal(ma["id"]) == m0 + 2982)
    check("手续费户 +18", bal("SYS_FEE") == fee0 + 18)
    oa = wait_order(order_a["id"], "PAID")
    check("交易单 PAID 且关联支付单", oa["status"] == "PAID" and oa["paymentId"] == pa["id"])
    tr = ok("GET", f"/api/accounting/transfers/{pa['id']}")
    debit = sum(l["amount"] for l in tr["lines"] if l["dir"] == "D")
    check("账务单据借方合计=支付金额", debit == 3000 and tr["refId"] == order_a["id"])
    sr = poll(lambda: next((x for x in ok("GET", f"/api/settle/records?merchantId={merchant['id']}")
                            if x.get("paymentId") == pa["id"] and x["type"] == "PAY"), None),
              lambda x: x is not None)
    check("outbox 投递生成结算单 net=2982", sr is not None and sr["net"] == 2982 and sr["fee"] == 18, str(sr))
    nts = poll(lambda: ok("GET", f"/api/notify/records?bizId={pa['id']}"), lambda x: len(x) > 0)
    check("生成 PAY_SUCCESS 通知（PULL 模式）",
          any(n["event"] == "PAY_SUCCESS" and n["status"] == "PULL" for n in nts), str(nts))
    r = ok("POST", "/api/cashier/pay", {"orderId": order_a["id"], "method": "BALANCE",
                                        "accountId": ua1["id"], "requestId": req_a})
    check("requestId 幂等返回同一支付单", r["payment"]["id"] == pa["id"] and r.get("repeated") is True
          and bal(ua1["id"]) == u10 + 7000)
    st, resp = req("POST", "/api/cashier/pay", {"orderId": order_a["id"], "method": "BALANCE",
                                                "accountId": ua1["id"], "requestId": "req-a2-" + SUFFIX})
    check("订单级重复支付拒绝 409", st == 409, str(resp))
    st, resp = req("POST", "/api/cashier/pay", {"orderId": order_a["id"], "method": "BALANCE",
                                                "accountId": ua1["id"]})
    check("缺 requestId 拒绝 400", st == 400, str(resp))

    # ---------- 5. 退款（部分 + 全额，手续费按比例退回） ----------
    print("[5] 退款 TCC：部分退 → 全额退，手续费按比例冲回")
    r = ok("POST", f"/api/payments/{pa['id']}/refund", {"amount": 1000, "requestId": "rf1-" + SUFFIX})
    p = poll(lambda: ok("GET", f"/api/payments/{pa['id']}"), lambda x: x["refundedAmount"] == 1000)
    check("部分退款 1000 成功", r["refundAmount"] == 1000 and p["refundedAmount"] == 1000)
    check("部分退手续费 6 分按比例退回", bal("SYS_FEE") == fee0 + 18 - 6
          and bal(ua1["id"]) == u10 + 8000 and bal(ma["id"]) == m0 + 2982 - 994)
    oa = wait_order(order_a["id"], "PARTIAL_REFUNDED")
    check("交易单 PARTIAL_REFUNDED", oa["status"] == "PARTIAL_REFUNDED" and oa["refundedAmount"] == 1000)
    r = ok("POST", f"/api/payments/{pa['id']}/refund", {"amount": 2000, "requestId": "rf2-" + SUFFIX})
    p = wait_payment(pa["id"], "REFUNDED")
    check("剩余 2000 全额退成功", r["refundAmount"] == 2000 and p["status"] == "REFUNDED")
    check("退款后三方余额复原", bal(ua1["id"]) == u10 + 10000 and bal(ma["id"]) == m0 and bal("SYS_FEE") == fee0)
    oa = wait_order(order_a["id"], "REFUNDED")
    check("交易单 REFUNDED", oa["status"] == "REFUNDED" and oa["refundedAmount"] == 3000)
    st, resp = req("POST", f"/api/payments/{pa['id']}/refund", {"amount": 1, "requestId": "rf3-" + SUFFIX})
    check("全额退后再退拒绝 409", st == 409, str(resp))
    r = ok("POST", f"/api/payments/{pa['id']}/refund", {"amount": 1000, "requestId": "rf1-" + SUFFIX})
    check("退款 requestId 幂等", r["refundAmount"] == 1000 and bal(ua1["id"]) == u10 + 10000)

    # ---------- 6. 银行卡支付（渠道网关 TCC） ----------
    print("[6] 银行卡支付：渠道成功 / 银行拒绝 / 渠道退款")
    order_b = ok("POST", "/api/orders", {"merchantId": merchant["id"], "subject": "银行卡商品", "amount": 2000})["order"]
    r = ok("POST", "/api/cashier/pay", {"orderId": order_b["id"], "method": "BANKCARD",
                                        "cardNo": "6222020202020202", "requestId": "req-b-" + SUFFIX})
    pb = wait_payment(r["payment"]["id"], "SUCCESS")
    check("62 开头卡支付成功", pb["status"] == "SUCCESS" and pb["fee"] == 12)
    check("卡号脱敏对外", pb["cardNo"] == "622202****0202", str(pb["cardNo"]))
    check("外部现金户借记 2000", bal("SYS_CASH") == cash0 - 10600 - 2000)
    check("商户净额 +1988", bal(ma["id"]) == m0 + 1988)
    order_c = ok("POST", "/api/orders", {"merchantId": merchant["id"], "subject": "拒绝卡商品", "amount": 2000})["order"]
    st, resp = req("POST", "/api/cashier/pay", {"orderId": order_c["id"], "method": "BANKCARD",
                                                "cardNo": "6222020202020000", "requestId": "req-c-" + SUFFIX})
    check("尾号 0000 银行拒绝 400", st == 400 and "渠道拒绝" in resp.get("error", ""), str(resp))
    pc = next((p for p in ok("GET", "/api/payments") if p["orderId"] == order_c["id"]), None)
    pc = wait_payment(pc["id"], "FAILED")
    check("失败支付单 FAILED 且留痕（TCC Cancel）", pc["status"] == "FAILED" and pc["failReason"], str(pc))
    oc = ok("GET", f"/api/orders/{order_c['id']}")
    check("渠道失败订单保持 CREATED（空回滚/释放）", oc["status"] == "CREATED" and oc["paymentId"] is None)
    check("渠道失败未动账", bal("SYS_CASH") == cash0 - 12600 and bal(ma["id"]) == m0 + 1988)
    st, _ = req("GET", f"/api/accounting/transfers/{pc['id']}")
    check("渠道失败无账务单据", st == 404)
    r = ok("POST", f"/api/payments/{pb['id']}/refund", {"amount": 2000, "requestId": "rf-b-" + SUFFIX})
    p = wait_payment(pb["id"], "REFUNDED")
    check("银行卡全额退款成功", p["status"] == "REFUNDED" and r["refundAmount"] == 2000)
    check("渠道退款后现金户复原", bal("SYS_CASH") == cash0 - 10600)
    check("银行卡退款手续费冲回", bal("SYS_FEE") == fee0 and bal(ma["id"]) == m0)
    chs = ok("GET", "/api/channel/trades")
    check("渠道留有退款记录", any(c.get("origBizNo") == pb["id"] and c["type"] == "REFUND" for c in chs))

    # ---------- 7. 风控与边界 ----------
    print("[7] 风控：单笔限额 / 余额不足 / 冻结 / 频率")
    order_big = ok("POST", "/api/orders", {"merchantId": merchant["id"], "subject": "超限商品", "amount": 20000000})["order"]
    st, resp = req("POST", "/api/cashier/pay", {"orderId": order_big["id"], "method": "BANKCARD",
                                                "cardNo": "6222020202020202", "requestId": "req-big-" + SUFFIX})
    check("单笔超 10 万元风控拒绝", st == 400 and "风控拒绝" in resp.get("error", ""), str(resp))
    ok("POST", f"/api/orders/{order_big['id']}/close", {})
    st, resp = req("POST", "/api/cashier/pay", {"orderId": order_big["id"], "method": "BALANCE",
                                                "accountId": ua1["id"], "requestId": "req-big2-" + SUFFIX})
    check("已关闭订单支付拒绝 409", st == 409, str(resp))
    order_poor = ok("POST", "/api/orders", {"merchantId": merchant["id"], "subject": "余额不足商品", "amount": 1000})["order"]
    st, resp = req("POST", "/api/cashier/pay", {"orderId": order_poor["id"], "method": "BALANCE",
                                                "accountId": ua2["id"], "requestId": "req-poor-" + SUFFIX})
    check("余额不足拒绝 400", st == 400 and "余额不足" in resp.get("error", ""), str(resp))
    check("余额不足未动账", bal(ua2["id"]) == u20 + 500)
    ok("POST", f"/api/accounts/{ua1['id']}/status", {"status": "FROZEN"})
    order_f = ok("POST", "/api/orders", {"merchantId": merchant["id"], "subject": "冻结测试", "amount": 100})["order"]
    st, resp = req("POST", "/api/cashier/pay", {"orderId": order_f["id"], "method": "BALANCE",
                                                "accountId": ua1["id"], "requestId": "req-f1-" + SUFFIX})
    check("冻结账户支付拒绝", st == 400 and "冻结" in resp.get("error", ""), str(resp))
    check("冻结支付失败未动账", bal(ua1["id"]) == u10 + 10000)
    ok("POST", f"/api/accounts/{ua1['id']}/status", {"status": "ACTIVE"})
    r = ok("POST", "/api/cashier/pay", {"orderId": order_f["id"], "method": "BALANCE",
                                        "accountId": ua1["id"], "requestId": "req-f2-" + SUFFIX})
    pf = wait_payment(r["payment"]["id"], "SUCCESS")
    check("解冻后支付成功", pf["status"] == "SUCCESS" and bal(ua1["id"]) == u10 + 10000 - 100)
    for i in range(5):
        o = ok("POST", "/api/orders", {"merchantId": merchant["id"], "subject": f"频率{i}", "amount": 1})["order"]
        r = ok("POST", "/api/cashier/pay", {"orderId": o["id"], "method": "BALANCE",
                                            "accountId": ua3["id"], "requestId": f"req-freq{i}-" + SUFFIX})
        wait_payment(r["payment"]["id"], "SUCCESS")
    o = ok("POST", "/api/orders", {"merchantId": merchant["id"], "subject": "频率6", "amount": 1})["order"]
    st, resp = req("POST", "/api/cashier/pay", {"orderId": o["id"], "method": "BALANCE",
                                                "accountId": ua3["id"], "requestId": "req-freq5-" + SUFFIX})
    check("10 秒第 6 笔触发频率风控", st == 400 and "频繁" in resp.get("error", ""), str(resp))
    check("频率测试商户共收 5 分", bal(ma["id"]) == m0 + 99 + 5)

    # ---------- 8. 异步通知（成功回调 + 失败重试） ----------
    print("[8] 消息通知：回调成功 / 失败退避重试")
    received = []

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
            received.append((self.path, body.decode()))
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"ok")

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 18099), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    payload = {"orderId": order_a["id"], "smoke": SUFFIX}
    r = ok("POST", "/api/notify/send", {"event": "PAY_SUCCESS", "bizId": "cb-" + SUFFIX,
                                        "payload": payload, "callbackUrl": "http://127.0.0.1:18099/ok"})
    nid = r["id"]
    rec = poll(lambda: ok("GET", f"/api/notify/{nid}"), lambda x: x["status"] == "DELIVERED", timeout=12)
    check("回调成功通知 DELIVERED", rec["status"] == "DELIVERED" and rec["attempts"] == 1, str(rec))
    check("商户实际收到回调报文", any(p == "/ok" and json.loads(b) == payload for p, b in received))
    r = ok("POST", "/api/notify/send", {"event": "PAY_SUCCESS", "bizId": "dead-" + SUFFIX,
                                        "payload": {}, "callbackUrl": "http://127.0.0.1:9/dead"})
    did = r["id"]
    rec = poll(lambda: ok("GET", f"/api/notify/{did}"), lambda x: x["attempts"] >= 1, timeout=12)
    check("死回调首次失败进入退避重试", rec["status"] == "PENDING" and rec["attempts"] == 1
          and rec["lastError"], str(rec))
    rec = poll(lambda: ok("GET", f"/api/notify/{did}"), lambda x: x["attempts"] >= 2, timeout=20, interval=1)
    check("退避后自动重试（attempts≥2）", rec["attempts"] >= 2, str(rec))
    server.shutdown()

    # ---------- 9. OpenAPI 签名接入 ----------
    print("[9] 开放网关 OpenAPI：HMAC-SHA256 签名")
    app_id, secret = merchant["appId"], merchant["appSecret"]
    params = {"appId": app_id, "timestamp": int(time.time() * 1000), "subject": "OpenAPI 商品", "amount": 800}
    params["sign"] = sign_params(params, secret)
    st, resp = req("POST", "/openapi/trade.create", params)
    check("签名下单成功", st == 200 and resp["amount"] == 800, str(resp))
    oo = resp
    params = {"appId": app_id, "timestamp": int(time.time() * 1000), "orderId": oo["id"],
              "method": "BALANCE", "accountId": ua1["id"], "outTradeNo": "open-" + SUFFIX}
    params["sign"] = sign_params(params, secret)
    st, resp = req("POST", "/openapi/trade.pay", params)
    check("签名支付受理成功", st == 200, str(resp))
    po = wait_payment(resp["payment"]["id"], "SUCCESS")
    check("OpenAPI 支付 SUCCESS", po["status"] == "SUCCESS")
    check("OpenAPI 支付金额正确", bal(ua1["id"]) == u10 + 10000 - 100 - 800)
    bad = dict(params, sign="0" * 64)
    st, resp = req("POST", "/openapi/trade.pay", bad)
    check("错误签名拒绝 400", st == 400 and "签名" in resp.get("error", ""), str(resp))
    old = dict(params, timestamp=int(time.time() * 1000) - 600000)
    old["sign"] = sign_params(old, secret)
    st, resp = req("POST", "/openapi/trade.pay", old)
    check("过期 timestamp 拒绝 400", st == 400, str(resp))

    # ---------- 10. 网关与错误处理 ----------
    print("[10] 网关路由 / 错误处理")
    st, _ = req("GET", "/api/nope/whatever")
    check("未知路由 404", st == 404)
    st, resp = req("POST", "/api/members", raw=b"{not json")
    check("非法 JSON 400", st == 400, str(resp))
    st, _ = req("GET", "/api/orders/O99999")
    check("不存在订单 404", st == 404)
    page = urllib.request.urlopen(BASE + "/", timeout=10).read().decode()
    check("网关托管 Web 控制台", "支付" in page and "<html" in page.lower())

    # ---------- 11. 对账与全局一致性 ----------
    print("[11] 对账域：跨服务核对")
    time.sleep(4)  # 等待 outbox 末批投递
    report = ok("POST", "/api/recon/run")
    for c in report["checks"]:
        check(f"对账：{c['name']}", c["pass"], c["detail"])
    trial = ok("GET", "/api/accounting/trial")
    check("试算平衡且余额和为零", trial["balanced"] and trial["balanceSum"] == 0, str(trial))
    settle1 = ok("GET", "/api/settle/summary")
    check("本批结算笔数 +9", settle1["payCount"] - settle0["payCount"] == 9,
          f'{settle0["payCount"]} -> {settle1["payCount"]}')
    check("本批结算手续费净额 = 手续费户增量", settle1["totalFee"] - settle0["totalFee"] == bal("SYS_FEE") - fee0)
    check("本批退款合计 5000", settle1["totalRefund"] - settle0["totalRefund"] == 5000)
    check("商户余额增量 = 结算净额增量", bal(ma["id"]) - m0 == settle1["totalNet"] - settle0["totalNet"])
    latest = ok("GET", "/api/recon/latest")
    check("对账报告已持久化可查", latest.get("id") == report["id"])

    print(f"\n结果：{PASSED} 通过，{FAILED} 失败")
    raise SystemExit(1 if FAILED else 0)


if __name__ == "__main__":
    main()