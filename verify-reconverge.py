#!/usr/bin/env python3
"""verify-reconverge.py — CH-1 / S-5 / P-1 验证：渠道查询 SPI + 终态回执幂等 + PROCESSING 查询补单收敛。

跑法（gateway 8080，栈需已启动）：
  python3 verify-reconverge.py                    # 同步模式（默认）：不回归 + 查询/回执幂等 + 补单幂等
  PAYMENT_CHANNEL_ASYNC=true ./run.sh             # 异步模式（channel+payment 同开关）后再跑：
                                                  #   WAITING 资金冻结 / 回执驱动 / 回执丢失查询补单 / 异步拒绝
  PP_CHAOS=1 python3 verify-reconverge.py         # 追加停渠道混沌：制造在途单 → 恢复 → 补单收敛 FAILED
  PP_RESTART_CONSUMER=1                           # 混沌时一并重启 payment（规避 provider 重启后 Dubbo 不重连）

依赖主会话新增的网关端点（缺失则相关断言 SKIP，其余照常执行）：
  GET  /api/channel/trades/{bizNo}    -> ChannelFacade.queryChannelTrade(bizNo)
  POST /api/channel/receipt           -> ChannelFacade.receiveReceipt(ChannelReceiptRequest)
  POST /api/payments/{id}/reconverge  -> PaymentFacade.reconverge(paymentId)
                                         （需先在 payment-facade 的 PaymentFacade 补一行接口声明）

资金护航断言：WAITING 期间无凭证/未过账；终态只翻一次；重复回执/重复补单余额与分录数不变、
              outbox 下游（通知/清结算）不重复；补单不绕过 TCC（账务-订单-渠道三方一致 + 对账通过）。
"""
import json
import os
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("PP_GATEWAY", "http://127.0.0.1:8080")

# ---- wave2 S-2 控制台登录：拿到 token 则 /api/** 带 Bearer；登录不可用则保持改造前行为 ----
import os as _os
TOKEN = None

def _login():
    global TOKEN
    try:
        _r = urllib.request.Request(BASE + "/api/auth/login",
                                    data=json.dumps({"username": "admin",
                                                     "password": _os.environ.get("PP_ADMIN_PASSWORD", "admin123")}).encode(),
                                    method="POST", headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(_r, timeout=10) as _resp:
            TOKEN = json.loads(_resp.read().decode()).get("token")
    except Exception:
        TOKEN = None

_login()
# ---- end wave2 S-2 ----

ROOT = os.path.dirname(os.path.abspath(__file__))
SUFFIX = uuid.uuid4().hex[:6]
PROFILE = os.environ.get("PP_PROFILE", "local")
CHAOS = os.environ.get("PP_CHAOS") == "1"
RESTART_CONSUMER = os.environ.get("PP_RESTART_CONSUMER") == "1"
PASSED = FAILED = SKIPPED = 0
HAVE_QUERY = HAVE_RECEIPT = HAVE_RECONVERGE = False
ASYNC = False


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


def req(method, path, body=None, *args, **kwargs):
    st, resp = _req_impl(method, path, body, *args, **kwargs)
    if st == 401 and path.startswith("/api/") and path != "/api/auth/login":
        _login()  # 会话过期/全栈重启（local 内存会话）时重登一次
        if TOKEN:
            st, resp = _req_impl(method, path, body, *args, **kwargs)
    return st, resp


def ok(method, path, body=None):
    st, resp = req(method, path, body)
    assert st == 200, f"{method} {path} 期望 200，实际 {st}: {resp}"
    return resp


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


def entries_total(biz_no):
    page = ok("GET", f"/api/accounting/entries?bizNo={biz_no}&size=200")
    return page.get("total", len(page.get("items") or []))


def has_voucher(biz_no):
    st, _ = req("GET", f"/api/accounting/transfers/{biz_no}")
    return st == 200


def channel_trade(biz_no):
    if HAVE_QUERY:
        st, resp = req("GET", f"/api/channel/trades/{biz_no}")
        return resp if st == 200 else None
    return next((t for t in ok("GET", "/api/channel/trades") if t["bizNo"] == biz_no), None)


def notify_count(biz_id):
    return len(ok("GET", f"/api/notify/records?bizId={biz_id}") or [])


def settle_count(biz_id):
    return len([r for r in (ok("GET", "/api/settle/records") or []) if r.get("bizId") == biz_id])


def setup_pair(tag):
    m = ok("POST", "/api/members", {"name": f"补单商户{tag}{SUFFIX}", "type": "MERCHANT"})
    u = ok("POST", "/api/members", {"name": f"补单用户{tag}{SUFFIX}", "type": "USER"})
    ma = ok("POST", "/api/accounts", {"memberId": m["id"]})
    ua = ok("POST", "/api/accounts", {"memberId": u["id"]})
    ok("POST", "/api/accounting/internal/topup",
       {"accountId": ua["id"], "amount": 100000, "bizNo": f"rc-{tag}-{SUFFIX}"})
    return m, ma, ua


def new_order(merchant_id, amount, subject):
    return ok("POST", "/api/orders",
              {"merchantId": merchant_id, "subject": subject, "amount": amount})["order"]


def card_pay(order_id, tag, card="6222020202020202"):
    return req("POST", "/api/cashier/pay",
               {"orderId": order_id, "method": "BANKCARD", "cardNo": card, "requestId": f"rc-{tag}-{SUFFIX}"})


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


def docker_env():
    """脚本自行重启子进程时必须继承 docker.env（run.sh 会 source，脚本不会），
    否则 MySQL 口令为空 → 进程启动即 Access denied 死亡，表现为下游 No provider available。"""
    env = dict(os.environ)
    if PROFILE != "docker":
        return env
    path = os.path.expanduser("~/.config/payment-pro/docker.env")
    if os.path.exists(path):
        with open(path) as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                if line.startswith("export "):
                    line = line[7:]
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip().strip('"').strip("'")
    return env


def start_svc(name):
    java = os.environ.get("JAVA_HOME",
                          "/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home") + "/bin/java"
    log = open(os.path.join(ROOT, "logs", f"{name}.log"), "ab")
    args = [java]
    if PROFILE == "docker":
        args.append("-Dspring.profiles.active=docker")
    args += ["-jar", os.path.join(ROOT, f"payment-{name}", "target", f"payment-{name}-1.0.0.jar")]
    p = subprocess.Popen(args, stdout=log, stderr=log, cwd=ROOT, start_new_session=True, env=docker_env())
    with open(os.path.join(ROOT, "run", f"{name}.pid"), "w") as f:
        f.write(str(p.pid))


def probe_endpoints():
    """探测主会话是否已接好三个新端点（缺失则相关断言 SKIP）。"""
    global HAVE_QUERY, HAVE_RECEIPT, HAVE_RECONVERGE
    trades = ok("GET", "/api/channel/trades") or []
    if trades:
        st, resp = req("GET", f"/api/channel/trades/{trades[0]['bizNo']}")
        HAVE_QUERY = st == 200 and isinstance(resp, dict) and resp.get("bizNo") == trades[0]["bizNo"]
    else:
        st, resp = req("GET", "/api/channel/trades/PROBE-ABSENT")
        HAVE_QUERY = st == 404 and isinstance(resp, dict) and resp.get("code") == "A0404"
    st, resp = req("POST", "/api/channel/receipt", {"bizNo": "PROBE-ABSENT", "status": "CAPTURED"})
    HAVE_RECEIPT = st == 404 and isinstance(resp, dict) and resp.get("code") == "A0404"
    pays = ok("GET", "/api/payments") or []
    terminal = next((p for p in pays if p["status"] in ("SUCCESS", "FAILED", "REFUNDED")), None)
    if terminal:
        st, resp = req("POST", f"/api/payments/{terminal['id']}/reconverge", {})
        HAVE_RECONVERGE = st == 200 and isinstance(resp, dict) and resp.get("id") == terminal["id"]
    else:
        st, resp = req("POST", "/api/payments/PROBE-ABSENT/reconverge", {})
        HAVE_RECONVERGE = st == 404 and isinstance(resp, dict) and resp.get("code") == "A0404"
    print(f"== 端点探测 query={HAVE_QUERY} receipt={HAVE_RECEIPT} reconverge={HAVE_RECONVERGE} ==")
    if not (HAVE_QUERY and HAVE_RECEIPT and HAVE_RECONVERGE):
        print("   提示: 需主会话在网关接 GET /api/channel/trades/{bizNo}、POST /api/channel/receipt、")
        print("         POST /api/payments/{id}/reconverge（并在 PaymentFacade 补 reconverge 声明）")


def main():
    global ASYNC
    print(f"== CH-1/S-5/P-1 查询补单收敛验证（批次 {SUFFIX}，profile={PROFILE}）==")
    assert not wait_health(60), "启动前请确保全部服务健康"
    probe_endpoints()

    # ---------- A. 同步支付不回归 ----------
    print("[A] 同步路径不回归：BALANCE / CARD 支付 PROCESSING→SUCCESS")
    m, ma, ua = setup_pair("A")
    mid, maid, uaid = m["id"], ma["id"], ua["id"]
    u0, m0, cash0 = bal(uaid), bal(maid), bal("SYS_CASH")
    order = new_order(mid, 3000, "补单-余额支付")
    r = ok("POST", "/api/cashier/pay",
           {"orderId": order["id"], "method": "BALANCE", "accountId": uaid, "requestId": f"rc-bal-{SUFFIX}"})
    pid_bal = r["payment"]["id"]
    check("余额支付受理返回支付单与 xid", bool(pid_bal) and bool(r["payment"].get("xid")), str(r["payment"]))
    p = wait_terminal(pid_bal)
    fee = p["fee"]
    check("余额支付 PROCESSING→SUCCESS（不回归）", p["status"] == "SUCCESS", str(p))
    check("余额只扣一次（含手续费）", bal(uaid) == u0 - 3000 and bal(maid) == m0 + 3000 - fee,
          f"{bal(uaid)}/{bal(maid)}")
    check("余额支付分录 3 条（不重复过账）", entries_total(pid_bal) == 3, str(entries_total(pid_bal)))
    o = ok("GET", f"/api/orders/{order['id']}")
    check("订单 PAID 且关联支付单", o["status"] == "PAID" and o["paymentId"] == pid_bal, str(o))

    order_card = new_order(mid, 2000, "补单-银行卡支付")
    st, r2 = card_pay(order_card["id"], "card")
    assert st == 200, f"银行卡支付受理失败 {st} {r2}"
    pid_card = r2["payment"]["id"]
    # 同步渠道在 pay() 内即完成二阶段；异步渠道停在 PROCESSING（dtx WAITING）
    probe = poll(lambda: payment(pid_card), lambda x: x["status"] != "PROCESSING", timeout=3, interval=0.3)
    ASYNC = probe["status"] == "PROCESSING"
    print(f"== 渠道模式探测：{'异步（WAITING）' if ASYNC else '同步（模拟银行默认）'} ==")
    if not ASYNC:
        check("银行卡同步支付 PROCESSING→SUCCESS（不回归）", probe["status"] == "SUCCESS", str(probe))
        ct = channel_trade(pid_card)
        check("同步渠道终态 CAPTURED", ct is not None and ct["status"] == "CAPTURED", str(ct))
        check("银行卡支付现金户/商户户正确入账", bal("SYS_CASH") == cash0 - 2000 and bal(maid) == m0 + 3000 - fee + 2000 - probe["fee"],
              f"{bal('SYS_CASH')}/{bal(maid)}")
        check("银行卡支付分录 3 条", entries_total(pid_card) == 3, str(entries_total(pid_card)))
    else:
        check("异步渠道受理停在 PROCESSING（dtx WAITING）", probe["status"] == "PROCESSING", str(probe))

    # ---------- D. 异步：WAITING 资金冻结 + 回执驱动收敛 ----------
    print("[D] 异步渠道：WAITING 期间资金冻结未过账 → 终态回执 → resumeCommit")
    if ASYNC:
        check("WAITING 期间无账务凭证（未过账）", not has_voucher(pid_card))
        check("WAITING 期间分录数为 0", entries_total(pid_card) == 0, str(entries_total(pid_card)))
        check("WAITING 期间商户/现金户余额不变", bal(maid) == m0 + 3000 - fee and bal("SYS_CASH") == cash0,
              f"{bal(maid)}/{bal('SYS_CASH')}")
        oc = ok("GET", f"/api/orders/{order_card['id']}")
        check("WAITING 期间订单仍 PAYING", oc["status"] == "PAYING", str(oc))
        ct = channel_trade(pid_card)
        check("渠道异步受理状态 SUBMITTED", ct is not None and ct["status"] in ("SUBMITTED", "CAPTURED"), str(ct))
        if HAVE_RECEIPT:
            rc = ok("POST", "/api/channel/receipt",
                    {"bizNo": pid_card, "status": "CAPTURED", "amount": 2000,
                     "channelOrderNo": f"MOCK-{SUFFIX}", "raw": "{\"mock\":\"receipt\"}"})
            check("回执落地渠道终态 CAPTURED", rc.get("status") == "CAPTURED", str(rc))
            check("回执记录渠道方订单号", rc.get("channelOrderNo") == f"MOCK-{SUFFIX}", str(rc))
            if HAVE_RECONVERGE:
                ok("POST", f"/api/payments/{pid_card}/reconverge", {})
                pr = poll(lambda: payment(pid_card), lambda x: x["status"] != "PROCESSING", timeout=20)
                check("回执后补单 resumeCommit→SUCCESS", pr["status"] == "SUCCESS", str(pr))
                check("收敛后分录恰 3 条（只过账一次）", entries_total(pid_card) == 3, str(entries_total(pid_card)))
                cash_items = (ok("GET", f"/api/accounting/entries?bizNo={pid_card}&accountId=SYS_CASH&size=50")
                              .get("items") or [])
                cash_leg = sum(-e["amount"] if e["dir"] == "D" else e["amount"] for e in cash_items)
                check("收敛后现金户/商户户入账正确",
                      cash_leg == -2000 and bal(maid) == m0 + 3000 - fee + 2000 - pr["fee"],
                      f"cashLeg={cash_leg} merchant={bal(maid)}")
                oc = ok("GET", f"/api/orders/{order_card['id']}")
                check("收敛后订单 PAID（三方一致）", oc["status"] == "PAID" and oc["paymentId"] == pid_card, str(oc))
            else:
                skip("回执后 resumeCommit→SUCCESS", "缺 POST /api/payments/{id}/reconverge")
                skip("收敛后分录/余额/订单一致", "缺 reconverge 端点")
        else:
            skip("回执驱动收敛", "缺 POST /api/channel/receipt")
    else:
        skip("异步 WAITING/回执驱动", "同步模式；PAYMENT_CHANNEL_ASYNC=true 重启 channel+payment 后复跑")

    # ---------- E. 异步：回执丢失 → 收敛 job 主动查询；异步拒绝 → 解冻 ----------
    print("[E] 异步渠道：回执丢失 → 补单 job 查询收敛；银行异步拒绝 → resumeCancel 解冻")
    if ASYNC:
        order_lost = new_order(mid, 1700, "补单-回执丢失")
        st, rl = card_pay(order_lost["id"], "lost")
        assert st == 200, f"受理失败 {st} {rl}"
        pid_lost = rl["payment"]["id"]
        check("回执丢失场景受理停在 PROCESSING", payment(pid_lost)["status"] == "PROCESSING")
        # 不推任何回执：模拟银行侧自行落地 CAPTURED，支付侧只能靠主动查询收敛
        ct = poll(lambda: channel_trade(pid_lost), lambda t: t is not None and t["status"] == "CAPTURED", timeout=30)
        check("银行侧已落地 CAPTURED 但回执未推达", ct is not None and ct["status"] == "CAPTURED", str(ct))
        check("查询补单前支付单仍在途（未臆断终态）", payment(pid_lost)["status"] == "PROCESSING")
        if HAVE_RECONVERGE:
            ok("POST", f"/api/payments/{pid_lost}/reconverge", {})
            pl = poll(lambda: payment(pid_lost), lambda x: x["status"] != "PROCESSING", timeout=30)
            check("主动查询补单收敛 SUCCESS（无回执）", pl["status"] == "SUCCESS", str(pl))
            check("补单只过账一次（分录 3 条）", entries_total(pid_lost) == 3, str(entries_total(pid_lost)))
            time.sleep(6)  # 等 outbox 投递落地（通知/清结算），再取基线
            n0, s0 = notify_count(pid_lost), settle_count(pid_lost)
            c_before, m_before = bal("SYS_CASH"), bal(maid)
            ok("POST", f"/api/payments/{pid_lost}/reconverge", {})
            ok("POST", f"/api/payments/{pid_lost}/reconverge", {})
            time.sleep(6)
            check("重复补单不重复通知（outbox 幂等）", notify_count(pid_lost) == n0,
                  f"{n0}->{notify_count(pid_lost)}")
            check("重复补单不重复清结算单", settle_count(pid_lost) == s0 == 1, f"{s0}->{settle_count(pid_lost)}")
            check("重复补单余额/分录不变",
                  entries_total(pid_lost) == 3 and bal("SYS_CASH") == c_before and bal(maid) == m_before,
                  f"{entries_total(pid_lost)}/{bal('SYS_CASH')}/{bal(maid)}")
        else:
            pl = poll(lambda: payment(pid_lost), lambda x: x["status"] != "PROCESSING", timeout=150, interval=3)
            check("收敛 job 兜底查询收敛 SUCCESS（无回执、无手动）", pl["status"] == "SUCCESS", str(pl))
            check("job 补单只过账一次（分录 3 条）", entries_total(pid_lost) == 3, str(entries_total(pid_lost)))
            skip("重复补单 outbox 幂等", "缺 reconverge 端点")

        # 收敛 job 兜底：不推回执、不手动补单，证明 @Scheduled 扫描能自行收敛（双 profile 同构）
        order_job = new_order(mid, 900, "补单-job兜底")
        st, rj = card_pay(order_job["id"], "job")
        assert st == 200, f"受理失败 {st} {rj}"
        pid_job = rj["payment"]["id"]
        pj = poll(lambda: payment(pid_job), lambda x: x["status"] != "PROCESSING", timeout=180, interval=3)
        check("回执丢失且无手动补单：收敛 job 自行置 SUCCESS", pj["status"] == "SUCCESS", str(pj))
        check("job 收敛只过账一次（分录 3 条）", entries_total(pid_job) == 3, str(entries_total(pid_job)))

        # 银行异步拒绝（尾号 9999）：FAILED 终态 → resumeCancel → 解冻释放，资金分毫未动
        m1, c1 = bal(maid), bal("SYS_CASH")
        order_dec = new_order(mid, 1200, "补单-异步拒绝")
        st, rd = card_pay(order_dec["id"], "decline", card="6222020202029999")
        assert st == 200, f"受理失败 {st} {rd}"
        pid_dec = rd["payment"]["id"]
        ct = poll(lambda: channel_trade(pid_dec), lambda t: t is not None and t["status"] == "FAILED", timeout=30)
        check("银行异步拒绝落地 FAILED", ct is not None and ct["status"] == "FAILED", str(ct))
        if HAVE_RECONVERGE:
            ok("POST", f"/api/payments/{pid_dec}/reconverge", {})
        pd = poll(lambda: payment(pid_dec), lambda x: x["status"] != "PROCESSING", timeout=150, interval=3)
        check("渠道 FAILED → resumeCancel → 支付单 FAILED", pd["status"] == "FAILED", str(pd))
        check("拒绝单无账务凭证（未过账）", not has_voucher(pid_dec))
        check("拒绝后资金分毫未动（解冻）", bal(maid) == m1 and bal("SYS_CASH") == c1, f"{bal(maid)}/{bal('SYS_CASH')}")
        od = ok("GET", f"/api/orders/{order_dec['id']}")
        check("拒绝后订单释放回 CREATED（防悬挂）", od["status"] == "CREATED" and od["paymentId"] is None, str(od))
    else:
        skip("异步回执丢失/job 兜底/异步拒绝", "同步模式；PAYMENT_CHANNEL_ASYNC=true 重启后复跑")

    # ---------- B. 渠道查询 SPI ----------
    print("[B] 渠道查询 SPI：只读返回当前 CAS 状态")
    if HAVE_QUERY:
        t = ok("GET", f"/api/channel/trades/{pid_card}")
        check("查询返回 bizNo/type/amount", t["bizNo"] == pid_card and t["type"] == "PAY" and t["amount"] == 2000, str(t))
        check("查询返回当前 CAS 状态", t["status"] in ("PREAUTH", "SUBMITTED", "CAPTURED", "FAILED", "VOIDED"), str(t))
        st, resp = req("GET", f"/api/channel/trades/ABSENT-{SUFFIX}")
        check("查询不存在返回 A0404（调用方不得臆断失败）", st == 404 and resp.get("code") == "A0404", f"{st} {resp}")
        n0 = len(ok("GET", "/api/channel/trades"))
        t2 = ok("GET", f"/api/channel/trades/{pid_card}")
        check("查询只读：不新增渠道交易", len(ok("GET", "/api/channel/trades")) == n0)
        if not ASYNC:
            check("查询只读：不改写渠道状态", t2["status"] == t["status"], f"{t['status']}->{t2['status']}")
        else:
            skip("查询只读不改写状态", "异步模式银行侧 job 会自行落地终态")
    else:
        skip("渠道查询 SPI", "缺 GET /api/channel/trades/{bizNo}")

    # ---------- C. 回执幂等 / 乱序 / 金额护航 ----------
    print("[C] 终态回执幂等：重复/中间态/金额不符一律吸收，不动账")
    captured = next((t for t in ok("GET", "/api/channel/trades")
                     if t["status"] == "CAPTURED" and t["type"] == "PAY"), None)
    if HAVE_RECEIPT and captured:
        biz = captured["bizNo"]
        m0, c0, e0 = bal(maid), bal("SYS_CASH"), entries_total(biz)
        rc = ok("POST", "/api/channel/receipt", {"bizNo": biz, "status": "CAPTURED", "amount": captured["amount"]})
        check("重复终态回执幂等吸收（状态不变）", rc.get("status") == "CAPTURED", str(rc))
        rc = ok("POST", "/api/channel/receipt", {"bizNo": biz, "status": "SUBMITTED"})
        check("中间态回执后到被忽略（不覆盖终态）", rc.get("status") == "CAPTURED", str(rc))
        st, resp = req("POST", "/api/channel/receipt",
                       {"bizNo": biz, "status": "CAPTURED", "amount": captured["amount"] + 1})
        check("回执金额不符被拒绝（资金护航）", st == 400 and resp.get("code") == "A0400", f"{st} {resp}")
        st, resp = req("POST", "/api/channel/receipt", {"bizNo": f"ABSENT-{SUFFIX}", "status": "CAPTURED"})
        check("未知 bizNo 回执返回 A0404", st == 404 and resp.get("code") == "A0404", f"{st} {resp}")
        for _ in range(3):
            ok("POST", "/api/channel/receipt", {"bizNo": biz, "status": "CAPTURED"})
        check("重复回执不重复过账（余额/分录不变）",
              bal(maid) == m0 and bal("SYS_CASH") == c0 and entries_total(biz) == e0,
              f"{bal(maid)}/{bal('SYS_CASH')}/{entries_total(biz)}")
    else:
        skip("回执幂等/乱序/金额护航",
             "缺 POST /api/channel/receipt" if not HAVE_RECEIPT else "无 CAPTURED 渠道单可靶")

    # ---------- G. 手动补单幂等（终态单） ----------
    print("[G] 手动补单幂等：终态单重复 reconverge 无副作用")
    if HAVE_RECONVERGE:
        target = pid_card if payment(pid_card)["status"] != "PROCESSING" else pid_bal
        p0 = payment(target)
        time.sleep(6)  # 等 outbox 投递落地，基线才有意义
        m0, c0, e0 = bal(maid), bal("SYS_CASH"), entries_total(target)
        n0, s0 = notify_count(target), settle_count(target)
        r1 = ok("POST", f"/api/payments/{target}/reconverge", {})
        r2 = ok("POST", f"/api/payments/{target}/reconverge", {})
        check("终态单补单返回原终态（幂等）", r1["status"] == p0["status"] == r2["status"], f"{p0['status']}/{r1['status']}/{r2['status']}")
        time.sleep(6)
        check("重复补单余额不变", bal(maid) == m0 and bal("SYS_CASH") == c0)
        check("重复补单分录数不变", entries_total(target) == e0, f"{e0}->{entries_total(target)}")
        check("重复补单不新增通知/清结算（outbox 不重复）",
              notify_count(target) == n0 and settle_count(target) == s0,
              f"{n0}->{notify_count(target)} / {s0}->{settle_count(target)}")
        st, resp = req("POST", f"/api/payments/ABSENT-{SUFFIX}/reconverge", {})
        check("未知支付单补单返回 A0404", st == 404 and resp.get("code") == "A0404", f"{st} {resp}")
    else:
        skip("手动补单幂等", "缺 POST /api/payments/{id}/reconverge")

    # ---------- F. 混沌（可选）：停渠道制造在途单 → 恢复 → 补单收敛 ----------
    print("[F] 混沌：停渠道 → 在途单留痕 → 恢复 → 补单收敛 FAILED（PP_CHAOS=1 启用）")
    if CHAOS:
        m2, c2, u2 = bal(maid), bal("SYS_CASH"), bal(uaid)
        order_ch = new_order(mid, 1500, "补单-渠道宕机")
        stop_svc("channel")
        time.sleep(2)
        st, rc = card_pay(order_ch["id"], "chaos")
        check("渠道宕机时银行卡支付受理失败", st != 200, f"{st} {rc}")
        pc = next((x for x in ok("GET", "/api/payments") if x["orderId"] == order_ch["id"]), None)
        check("在途单留痕 PROCESSING（一阶段未完成）", pc is not None and pc["status"] == "PROCESSING", str(pc))
        start_svc("channel")
        if RESTART_CONSUMER:
            stop_svc("payment")
            start_svc("payment")
        down = wait_health()
        check("渠道恢复后全部健康", not down, ",".join(down))
        if pc:
            if HAVE_RECONVERGE:
                req("POST", f"/api/payments/{pc['id']}/reconverge", {})
            pf = poll(lambda: payment(pc["id"]), lambda x: x["status"] == "FAILED", timeout=150, interval=3)
            check("恢复后补单/补偿收敛 FAILED", pf["status"] == "FAILED", str(pf))
            check("宕机单无账务凭证（未过账）", not has_voucher(pc["id"]))
            check("宕机单资金分毫未动", bal(maid) == m2 and bal("SYS_CASH") == c2 and bal(uaid) == u2,
                  f"{bal(maid)}/{bal('SYS_CASH')}/{bal(uaid)}")
            och = ok("GET", f"/api/orders/{order_ch['id']}")
            check("宕机单订单释放回 CREATED", och["status"] == "CREATED" and och["paymentId"] is None, str(och))
            if HAVE_RECONVERGE:
                e0 = entries_total(pc["id"])
                ok("POST", f"/api/payments/{pc['id']}/reconverge", {})
                ok("POST", f"/api/payments/{pc['id']}/reconverge", {})
                check("失败单重复补单幂等（分录/状态不变）",
                      entries_total(pc["id"]) == e0 == 0 and payment(pc["id"])["status"] == "FAILED")
            else:
                skip("失败单重复补单幂等", "缺 reconverge 端点")
        else:
            skip("宕机在途单收敛断言", "未取到留痕支付单")
    else:
        skip("渠道宕机混沌", "未设 PP_CHAOS=1（会临时停启 channel，需主会话配合）")

    # ---------- H. 收敛后对账 ----------
    print("[H] 收敛后对账：五方一致 + 试算平衡")
    time.sleep(6)
    rep = ok("POST", "/api/recon/run")
    check("补单收敛后对账全部通过", rep["pass"], str([c for c in rep["checks"] if not c["pass"]]))
    trial = ok("GET", "/api/accounting/trial")
    check("试算平衡 D=C 且 Σbalance=0", trial["balanced"] and trial["balanceSum"] == 0, str(trial))

    print(f"\n结果: {PASSED} 通过 / {FAILED} 失败 / {SKIPPED} 跳过")
    if SKIPPED:
        print("跳过项需：① 主会话接好 3 个网关端点；② PAYMENT_CHANNEL_ASYNC=true 重启 channel+payment 跑异步段；③ PP_CHAOS=1 跑混沌段")
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()