#!/usr/bin/env python3
# payment-pro 工业级特性验证：并发幂等 / 订单竞态 / 渠道宕机 TCC 补偿 / 全停重启持久化
# 用法：./run.sh 启动后执行 python3 verify-pro.py（会临时停启个别服务与整体重启）
import concurrent.futures
import json
import os
import signal
import subprocess
import time
import urllib.error
import urllib.request
import uuid

BASE = "http://127.0.0.1:8080"
ROOT = os.path.dirname(os.path.abspath(__file__))
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


def req(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
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
    except Exception as e:
        return 0, {"error": str(e)}


def ok(method, path, body=None):
    st, resp = req(method, path, body)
    assert st == 200, f"{method} {path} 期望 200，实际 {st}: {resp}"
    return resp


def bal(aid):
    return ok("GET", f"/api/accounting/accounts/{aid}/balance")


def poll(fn, pred, timeout=30, interval=0.5):
    deadline = time.time() + timeout
    val = fn()
    while time.time() < deadline and not pred(val):
        time.sleep(interval)
        val = fn()
    return val


def wait_health(timeout=180):
    def down():
        st, h = req("GET", "/api/health")
        return [x["service"] for x in h if not x["ok"]] if st == 200 else ["gateway"]
    return poll(down, lambda d: not d, timeout=timeout, interval=2)


def stop_svc(name):
    pidfile = os.path.join(ROOT, "run", f"{name}.pid")
    with open(pidfile) as f:
        pid = int(f.read().strip())
    os.kill(pid, signal.SIGTERM)
    os.remove(pidfile)
    deadline = time.time() + 20
    while time.time() < deadline:
        try:
            os.kill(pid, 0)
            time.sleep(0.5)
        except OSError:
            return
    os.kill(pid, signal.SIGKILL)


def start_svc(name):
    java = os.environ.get("JAVA_HOME", "/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home") + "/bin/java"
    log = open(os.path.join(ROOT, "logs", f"{name}.log"), "ab")
    p = subprocess.Popen([java, "-jar", os.path.join(ROOT, f"payment-{name}", "target", f"payment-{name}-1.0.0.jar")],
                         stdout=log, stderr=log, cwd=ROOT, start_new_session=True)
    with open(os.path.join(ROOT, "run", f"{name}.pid"), "w") as f:
        f.write(str(p.pid))


def setup_pair(tag):
    m = ok("POST", "/api/members", {"name": f"验证商户{tag}{SUFFIX}", "type": "MERCHANT"})
    u = ok("POST", "/api/members", {"name": f"验证用户{tag}{SUFFIX}", "type": "USER"})
    ma = ok("POST", "/api/accounts", {"memberId": m["id"]})
    ua = ok("POST", "/api/accounts", {"memberId": u["id"]})
    ok("POST", "/api/accounting/internal/topup", {"accountId": ua["id"], "amount": 100000, "bizNo": f"vt-{tag}-{SUFFIX}"})
    return m, ma, ua


def main():
    print(f"== payment-pro 工业级特性验证（批次 {SUFFIX}）==")
    assert not wait_health(60), "启动前请确保全部服务健康"

    # ---------- A. 并发幂等：同 requestId 8 并发 ----------
    print("[A] 并发幂等：同一 requestId 8 并发只产生一笔支付")
    m, ma, ua = setup_pair("A")
    u0, m0 = bal(ua["id"]), bal(ma["id"])
    order = ok("POST", "/api/orders", {"merchantId": m["id"], "subject": "并发幂等", "amount": 3000})["order"]
    rid = "same-" + SUFFIX

    def pay_same(_):
        return req("POST", "/api/cashier/pay", {"orderId": order["id"], "method": "BALANCE",
                                                "accountId": ua["id"], "requestId": rid})
    with concurrent.futures.ThreadPoolExecutor(8) as ex:
        results = list(ex.map(pay_same, range(8)))
    oks = [r for st, r in results if st == 200]
    ids = {r["payment"]["id"] for r in oks}
    check("8 并发全部受理或幂等命中", len(oks) >= 1 and all(st in (200, 409) for st, _ in results),
          str([st for st, _ in results]))
    check("只产生一个支付单", len(ids) == 1, str(ids))
    pid = oks[0]["payment"]["id"]
    p = poll(lambda: ok("GET", f"/api/payments/{pid}"), lambda x: x["status"] in ("SUCCESS", "FAILED"))
    check("终态 SUCCESS", p["status"] == "SUCCESS", str(p))
    check("余额只扣一次 3000", bal(ua["id"]) == u0 - 3000 and bal(ma["id"]) == m0 + 2982)
    pays = [x for x in ok("GET", "/api/payments") if x["orderId"] == order["id"]]
    check("库中该订单仅一笔支付单", len(pays) == 1, str(len(pays)))

    # ---------- B. 并发竞态：同订单不同 requestId 8 并发 ----------
    print("[B] 订单竞态：同订单 8 个不同 requestId 并发，仅一笔成功")
    order = ok("POST", "/api/orders", {"merchantId": m["id"], "subject": "并发竞态", "amount": 2000})["order"]
    u0, m0 = bal(ua["id"]), bal(ma["id"])

    def pay_diff(i):
        return req("POST", "/api/cashier/pay", {"orderId": order["id"], "method": "BALANCE",
                                                "accountId": ua["id"], "requestId": f"race-{i}-{SUFFIX}"})
    with concurrent.futures.ThreadPoolExecutor(8) as ex:
        results = list(ex.map(pay_diff, range(8)))
    time.sleep(6)  # 等待 TCC 二阶段与补偿收敛
    pays = [x for x in ok("GET", "/api/payments") if x["orderId"] == order["id"]]
    succ = [x for x in pays if x["status"] == "SUCCESS"]
    check("恰好一笔 SUCCESS", len(succ) == 1, str([(x["id"], x["status"]) for x in pays]))
    check("其余全部 FAILED（TCC 回滚）", all(x["status"] == "FAILED" for x in pays if x["status"] != "SUCCESS"),
          str([(x["id"], x["status"]) for x in pays]))
    check("余额只扣一次 2000", bal(ua["id"]) == u0 - 2000 and bal(ma["id"]) == m0 + 1988)
    o = ok("GET", f"/api/orders/{order['id']}")
    check("订单 PAID 且关联唯一成功支付单", o["status"] == "PAID" and o["paymentId"] == succ[0]["id"])

    # ---------- C. 渠道宕机：TCC Try 失败自动回滚，恢复后对账通过 ----------
    print("[C] 混沌：停掉渠道网关 → 银行卡支付失败回滚 → 重启渠道 → 对账通过")
    u0, m0, cash0 = bal(ua["id"]), bal(ma["id"]), bal("SYS_CASH")
    order = ok("POST", "/api/orders", {"merchantId": m["id"], "subject": "渠道宕机", "amount": 1500})["order"]
    stop_svc("channel")
    time.sleep(2)
    st, resp = req("POST", "/api/cashier/pay", {"orderId": order["id"], "method": "BANKCARD",
                                                "cardNo": "6222020202020202", "requestId": "chaos-" + SUFFIX})
    check("渠道宕机支付受理失败", st != 200, f"{st} {resp}")
    pc = next((x for x in ok("GET", "/api/payments") if x["orderId"] == order["id"]), None)
    check("失败支付单留痕", pc is not None)
    o = ok("GET", f"/api/orders/{order['id']}")
    check("订单释放回 CREATED（防悬挂）", o["status"] == "CREATED" and o["paymentId"] is None, str(o))
    check("三方余额分毫不差", bal(ua["id"]) == u0 and bal(ma["id"]) == m0 and bal("SYS_CASH") == cash0)
    start_svc("channel")
    down = wait_health()
    check("渠道重启后全部恢复健康", not down, ",".join(down))
    if pc:
        pc = poll(lambda: ok("GET", f"/api/payments/{pc['id']}"), lambda x: x["status"] == "FAILED", timeout=120, interval=2)
        check("渠道恢复后异步补偿置 FAILED", pc["status"] == "FAILED", str(pc))
    r = ok("POST", "/api/cashier/pay", {"orderId": order["id"], "method": "BANKCARD",
                                        "cardNo": "6222020202020202", "requestId": "chaos2-" + SUFFIX})
    p = poll(lambda: ok("GET", f"/api/payments/{r['payment']['id']}"), lambda x: x["status"] == "SUCCESS")
    check("恢复后同一订单支付成功", p["status"] == "SUCCESS")
    time.sleep(6)  # 等待 outbox 投递
    rep = ok("POST", "/api/recon/run")
    check("宕机补偿后对账全部通过", rep["pass"], str([c for c in rep["checks"] if not c["pass"]]))

    # ---------- D. 全停重启：H2 持久化 + 协调器恢复 ----------
    print("[D] 持久化：全部停止 → 重启 → 数据与余额完整")
    poll(lambda: ok("GET", "/api/payments"), lambda ps: all(x["status"] != "PROCESSING" for x in ps),
         timeout=120, interval=2)
    time.sleep(6)  # 等待末批 outbox 投递收敛
    before_members = len(ok("GET", "/api/members"))
    before_pays = ok("GET", "/api/payments")
    before_bal = {a["id"]: bal(a["id"]) for a in ok("GET", "/api/accounts")}
    subprocess.run([os.path.join(ROOT, "stop.sh")], cwd=ROOT, check=True, capture_output=True)
    time.sleep(3)
    subprocess.run([os.path.join(ROOT, "run.sh"), "--no-build"], cwd=ROOT, check=True, capture_output=True)
    down = wait_health()
    check("重启后全部健康", not down, ",".join(down))
    check("会员数持久化", len(ok("GET", "/api/members")) == before_members)
    after_pays = ok("GET", "/api/payments")
    check("支付单持久化（含终态）", len(after_pays) == len(before_pays)
          and all(a["status"] == b["status"] for a, b in
                  zip(sorted(after_pays, key=lambda x: x["id"]), sorted(before_pays, key=lambda x: x["id"]))))
    after_bal = {a["id"]: bal(a["id"]) for a in ok("GET", "/api/accounts")}
    check("全部账户余额持久化", after_bal == before_bal,
          str({k: (before_bal[k], after_bal.get(k)) for k in before_bal if before_bal[k] != after_bal.get(k)}))
    rep = ok("POST", "/api/recon/run")
    check("重启后对账全部通过", rep["pass"], str([c for c in rep["checks"] if not c["pass"]]))

    print(f"\n结果：{PASSED} 通过，{FAILED} 失败")
    raise SystemExit(1 if FAILED else 0)


if __name__ == "__main__":
    main()