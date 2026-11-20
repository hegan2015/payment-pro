#!/usr/bin/env python3
"""verify-mq.py — I-4 验证：RocketMQ 事务消息 + 多 topic（docker profile 专用）。

跑法（持 wave3 锁、全栈 docker 已启动、共享栈 RocketMQ 在跑）：
  python3 verify-mq.py

断言覆盖（卡 §2.A I-4 / §5.2）：
  A 事务消息主路径：支付/退款终态后半消息 COMMIT（outbox.half_state/msg_id 留痕）、
    消费偏移一致 Consume Diff Total=0、结算/通知恰好一次、BALANCE 同步语义不变（无 WAITING）。
  B 多 topic 路由：SETTLE_PAY/NOTIFY_PAY→PP_PAY、SETTLE_REFUND/NOTIFY_REFUND→PP_REFUND、
    topic 实建；双投幂等吸收：同一事件经 PP_PAY/PP_OUTBOX 重复投递只结算/通知一次（幂等键不变）。
  C broker 全停：半消息发送失败吞异常、支付同步语义不变、outbox 留 PENDING 由 3s 轮询兜底投递成功；
    恢复 broker 后事务消息主路径自动恢复。
  D 混沌：commit 半消息前 kill -9 payment（tx-chaos-delay 注入崩溃窗口）→ 全栈重启后
    broker 回查 checkLocalTransaction 触发 COMMIT（half_state 留痕+日志），结算/通知不丢不重。
  E 回滚开关：PAYMENT_MQ_TRANSACTIONAL=false + PAYMENT_MQ_MULTI_TOPIC=false 一键切回
    普通消息 + PP_OUTBOX 单 topic（升级前等价路径），投递/幂等不回归；验毕恢复事务消息形态。
  F 混沌后资金护航：六项对账 pass + 试算平衡 D=C 且 Σbalance=0。

红线：outbox 唯一事实来源、3s 轮询兜底永不删除；绝不删改既有业务数据（只读断言+本脚本新增单据）；
      重启只能全栈 stop.sh/run.sh --docker --no-build，重启后过稳定性门禁（3 轮 health 13/13 + login + Bearer members）。
"""
import json
import os
import signal
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("PP_GATEWAY", "http://127.0.0.1:8080")
ROOT = os.path.dirname(os.path.abspath(__file__))
PROFILE = os.environ.get("PP_PROFILE", "docker")
SUFFIX = uuid.uuid4().hex[:6]
TOKEN = None
PASSED = FAILED = SKIPPED = 0

DOCKER = "/Users/hegan/.docker/bin/docker"
if not os.path.exists(DOCKER):
    DOCKER = "docker"
BROKER = os.environ.get("PP_ROCKETMQ_BROKER", "claim-shared-rocketmq-broker")
NAMESRV = os.environ.get("PP_ROCKETMQ_NAMESRV_CONTAINER", "claim-shared-rocketmq-namesrv")
DASHBOARD = os.environ.get("PP_ROCKETMQ_DASHBOARD", "claim-shared-rocketmq-dashboard")
MQADMIN_BIN = "/home/rocketmq/rocketmq-5.1.4/bin"
NS = "rocketmq-namesrv:9876"
CONSUMER_GROUP = "payment-outbox-consumer"
JAVA_HOME = "/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home"


def check(name, cond, detail=""):
    global PASSED, FAILED
    if cond:
        PASSED += 1
        print(f"  [PASS] {name}", flush=True)
    else:
        FAILED += 1
        print(f"  [FAIL] {name}  {detail}", flush=True)


def skip(name, why):
    global SKIPPED
    SKIPPED += 1
    print(f"  [SKIP] {name}  ({why})", flush=True)


# ---------- HTTP（控制台 Bearer 双态兼容） ----------

def _login():
    global TOKEN
    try:
        r = urllib.request.Request(BASE + "/api/auth/login",
                                   data=json.dumps({"username": "admin",
                                                    "password": os.environ.get("PP_ADMIN_PASSWORD", "admin123")}).encode(),
                                   method="POST", headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(r, timeout=10) as resp:
            body = json.loads(resp.read().decode())
            TOKEN = body.get("token") or (body.get("data") or {}).get("token")
    except Exception:
        TOKEN = None


def _req(method, path, body=None, timeout=60):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Accept", "application/json")
    if data is not None:
        r.add_header("Content-Type", "application/json")
    if TOKEN and path.startswith("/api/") and path != "/api/auth/login":
        r.add_header("Authorization", "Bearer " + TOKEN)
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
        return -1, {"error": str(e)}


def req(method, path, body=None, timeout=60):
    st, resp = _req(method, path, body, timeout)
    if st == 401 and path.startswith("/api/") and path != "/api/auth/login":
        _login()
        if TOKEN:
            st, resp = _req(method, path, body, timeout)
    return st, resp


def ok(method, path, body=None, timeout=60):
    st, resp = req(method, path, body, timeout)
    assert st == 200, f"{method} {path} 期望 200，实际 {st}: {resp}"
    return resp


def poll(fn, pred, timeout=20, interval=0.5):
    deadline = time.time() + timeout
    val = fn()
    while time.time() < deadline and not pred(val):
        time.sleep(interval)
        val = fn()
    return val


def bal(account_id):
    return ok("GET", f"/api/accounting/accounts/{account_id}/balance")


# ---------- MySQL / RocketMQ / 全栈重启 ----------

def _mysql_creds():
    user, pwd = "root", ""
    path = os.path.expanduser("~/.config/payment-pro/docker.env")
    if os.path.exists(path):
        with open(path) as f:
            for line in f:
                line = line.strip()
                if line.startswith("MYSQL_USER="):
                    user = line.split("=", 1)[1].strip().strip('"').strip("'")
                elif line.startswith("MYSQL_PASSWORD="):
                    pwd = line.split("=", 1)[1].strip().strip('"').strip("'")
    return user, pwd


def sql(db, statement):
    user, pwd = _mysql_creds()
    env = dict(os.environ)
    env["MYSQL_PWD"] = pwd  # 不回显口令
    container = os.environ.get("PP_MYSQL_CONTAINER", "claim-shared-mysql")
    r = subprocess.run([DOCKER, "exec", "-e", "MYSQL_PWD", container,
                        "mysql", f"-u{user}", "-N", "-B", "-e", statement, db],
                       capture_output=True, text=True, env=env, timeout=30)
    if r.returncode != 0:
        raise RuntimeError(f"mysql 执行失败: {r.stderr.strip()[:200]}")
    return r.stdout.strip()


def outbox_rows(biz_id):
    out = sql("pp_payment",
              "SELECT id,event,status,IFNULL(topic,''),IFNULL(msg_id,''),IFNULL(half_state,''),attempts "
              f"FROM outbox WHERE biz_id='{biz_id}' ORDER BY id")
    rows = []
    for line in out.splitlines():
        p = line.split("\t")
        if len(p) == 7:
            rows.append({"id": p[0], "event": p[1], "status": p[2], "topic": p[3],
                         "msg_id": p[4], "half_state": p[5], "attempts": int(p[6])})
    return rows


def mqadmin(args, timeout=60):
    cmd = f"cd {MQADMIN_BIN} && sh mqadmin {args} -n {NS}"
    r = subprocess.run([DOCKER, "exec", BROKER, "sh", "-c", cmd],
                       capture_output=True, text=True, timeout=timeout)
    return r.returncode, r.stdout + r.stderr


def consume_diff_total():
    rc, out = mqadmin(f"consumerProgress -g {CONSUMER_GROUP}")
    if rc != 0:
        return None
    for line in out.splitlines():
        if line.startswith("Consume Diff Total:"):
            try:
                return int(line.split(":")[1].strip())
            except ValueError:
                return None
    return None


def half_topic_max_offset():
    rc, out = mqadmin("topicStatus -t RMQ_SYS_TRANS_HALF_TOPIC")
    if rc != 0:
        return None
    total = 0
    for line in out.splitlines():
        p = line.split()
        if len(p) >= 4 and p[0] != "#Broker" and p[1].isdigit():
            try:
                total += int(p[3])
            except ValueError:
                pass
    return total


def docker_ctl(action, container, timeout=90):
    r = subprocess.run([DOCKER, action, container], capture_output=True, text=True, timeout=timeout)
    return r.returncode == 0


def full_restart(extra_env=None, label=""):
    """全栈重启（严禁单域）：stop.sh → run.sh --docker --no-build，随后稳定性门禁。"""
    env = dict(os.environ)
    env["JAVA_HOME"] = JAVA_HOME
    if extra_env:
        env.update(extra_env)
    print(f"== 全栈重启 {label} ==", flush=True)
    r = subprocess.run(["./stop.sh"], cwd=ROOT, env=env, capture_output=True, text=True, timeout=180)
    if r.returncode != 0:
        print(r.stdout[-500:], r.stderr[-500:], flush=True)
    r = subprocess.run(["./run.sh", "--docker", "--no-build"], cwd=ROOT, env=env,
                       capture_output=True, text=True, timeout=420)
    if r.returncode != 0:
        print(r.stdout[-800:], r.stderr[-800:], flush=True)
        raise RuntimeError(f"run.sh 启动失败 {label}")
    stability_gate()


def stability_gate(rounds=3, timeout=240):
    """连续 3 轮：health 13/13 + login 200 + Bearer GET /api/members 200（防垂死旧进程假就绪）。"""
    _login()
    deadline = time.time() + timeout
    good = 0
    while time.time() < deadline and good < rounds:
        st, h = req("GET", "/api/health", timeout=10)
        healthy = st == 200 and isinstance(h, list) and len(h) == 13 and all(x.get("ok") for x in h)
        st2, _ = req("POST", "/api/auth/login",
                     {"username": "admin", "password": os.environ.get("PP_ADMIN_PASSWORD", "admin123")}, timeout=10)
        st3, _ = req("GET", "/api/members", timeout=10)
        if healthy and st2 == 200 and st3 == 200:
            good += 1
        else:
            good = 0
            _login()
        time.sleep(3)
    if good < rounds:
        raise RuntimeError("稳定性门禁未通过（health/login/members 连续 3 轮）")
    print("== 稳定性门禁通过：3 轮 health 13/13 + login 200 + Bearer members 200 ==", flush=True)
    warmup()


def warmup(timeout=150):
    """业务探针预热：重启后 Dubbo 消费端 directory 恢复可能滞后于 health 探针（实测 ~60s），
    以真实开户链路探测直至 200，避免把 invoker 恢复窗口的 500 误判为功能失败。"""
    deadline = time.time() + timeout
    n = 0
    while time.time() < deadline:
        n += 1
        st, m = req("POST", "/api/members", {"name": f"MQ预热探针{SUFFIX}-{n}", "type": "USER"})
        if st == 200 and isinstance(m, dict) and m.get("id"):
            st2, a = req("POST", "/api/accounts", {"memberId": m["id"]})
            if st2 == 200:
                print(f"== 业务预热完成（开户链路 200，探针 {n} 次）==", flush=True)
                return
        time.sleep(3)
    raise RuntimeError("业务预热超时：开户链路持续非 200（Dubbo invoker 未恢复）")


# ---------- 业务造单（全部 additive，本脚本自有单据） ----------

def make_pay(amount, tag, pay_timeout=60):
    """商户下单 + BALANCE 支付（同步受理→SUCCESS），返回 (merchant, payer_account, order, payment_id, accept_resp)。"""
    merchant = ok("POST", "/api/members", {"name": f"MQ商户{tag}{SUFFIX}", "type": "MERCHANT"})
    user = ok("POST", "/api/members", {"name": f"MQ用户{tag}{SUFFIX}", "type": "USER"})
    ma = ok("POST", "/api/accounts", {"memberId": merchant["id"]})
    ua = ok("POST", "/api/accounts", {"memberId": user["id"]})
    ok("POST", "/api/accounting/internal/topup",
       {"accountId": ua["id"], "amount": 1000000, "bizNo": f"mq-topup-{tag}-{SUFFIX}"})
    order = ok("POST", "/api/orders", {"merchantId": merchant["id"], "subject": f"MQ商品{tag}", "amount": amount})["order"]
    r = ok("POST", "/api/cashier/pay", {"orderId": order["id"], "method": "BALANCE",
                                        "accountId": ua["id"], "requestId": f"mq-{tag}-{SUFFIX}"}, timeout=pay_timeout)
    return merchant, ma, ua, order, r["payment"]["id"], r


def settle_records(merchant_id, payment_id, rtype):
    recs = ok("GET", f"/api/settle/records?merchantId={merchant_id}")
    return [x for x in recs if x.get("paymentId") == payment_id and x.get("type") == rtype]


def notify_records(biz_id, event):
    recs = ok("GET", f"/api/notify/records?bizId={biz_id}")
    return [x for x in recs if x.get("event") == event]


def wait_delivered(biz_id, events, timeout=25):
    def done():
        rows = outbox_rows(biz_id)
        return all(any(r["event"] == e and r["status"] == "DELIVERED" for r in rows) for e in events)
    poll(lambda: done(), lambda x: x, timeout=timeout, interval=1)
    return outbox_rows(biz_id)


def send_duplicate(topic, event, biz_id):
    body = json.dumps({"event": event, "bizId": biz_id})
    rc, out = mqadmin(f"sendMessage -t {topic} -c {event} -k {biz_id} -p '{body}'")
    return rc == 0, out[-200:]


def main():
    global TOKEN
    print(f"== verify-mq.py（I-4 事务消息+多 topic，批次 {SUFFIX}，profile={PROFILE}）==", flush=True)
    if PROFILE != "docker":
        print("local profile 无 MQ（payment.mq.enabled=false 纯轮询，零依赖）——本脚本仅 docker 运行。")
        print("local 纯轮询等价路径由 smoke-test.py 79/79 复验。")
        return 0
    _login()

    # ---------- 0. 前置：DDL 实建 + topic 预建（additive） ----------
    print("[0] 前置：outbox 增列实建 + 路由 topic 预建")
    cols = sql("pp_payment", "SELECT column_name FROM information_schema.columns "
                             "WHERE table_schema='pp_payment' AND table_name='outbox' "
                             "AND column_name IN ('topic','msg_id','half_state') ORDER BY column_name")
    check("MySQL pp_payment.outbox 增列 topic/msg_id/half_state 实建",
          sorted(cols.splitlines()) == ["half_state", "msg_id", "topic"], cols)
    for t in ["PP_PAY", "PP_REFUND", "PP_SHARE", "PP_SETTLE", "PP_SIGN"]:
        mqadmin(f"updateTopic -c DefaultCluster -t {t} -r 8 -w 8")
    rc, out = mqadmin("topicList")
    check("broker 可达且 PP_OUTBOX legacy topic 保留", rc == 0 and "PP_OUTBOX" in out, out[-200:])
    stability_gate()

    # ---------- A. 事务消息主路径（① 半消息 commit / 消费偏移 / 恰好一次） ----------
    print("[A] 事务消息主路径：支付+退款终态 → 半消息 COMMIT → 消费投递恰好一次")
    merchant, ma, ua, order, pid, accept = make_pay(3000, "a")
    check("BALANCE 同步受理 200（无 WAITING/异步回执引入）",
          accept["payment"]["status"] in ("PROCESSING", "SUCCESS"), str(accept["payment"]["status"]))
    p = poll(lambda: ok("GET", f"/api/payments/{pid}"), lambda x: x["status"] == "SUCCESS", timeout=25)
    check("支付同步收敛 SUCCESS（手续费口径不变 18 分）", p["status"] == "SUCCESS" and p["fee"] == 18, str(p))
    dtx = sql("pp_payment", f"SELECT status FROM dtx_transaction WHERE biz_no='{pid}'")
    check("TCC 全局事务 CONFIRMED（终态语义不变）", dtx == "CONFIRMED", dtx)
    rows = wait_delivered(pid, ["SETTLE_PAY", "NOTIFY_PAY"])
    sp = next((r for r in rows if r["event"] == "SETTLE_PAY"), {})
    np_ = next((r for r in rows if r["event"] == "NOTIFY_PAY"), {})
    check("SETTLE_PAY 半消息 COMMIT 留痕（half_state=COMMIT + msg_id 回执）",
          sp.get("half_state") == "COMMIT" and bool(sp.get("msg_id")), str(sp))
    check("NOTIFY_PAY 半消息 COMMIT 留痕", np_.get("half_state") == "COMMIT" and bool(np_.get("msg_id")), str(np_))
    check("支付事件路由 PP_PAY（多 topic）", sp.get("topic") == "PP_PAY" and np_.get("topic") == "PP_PAY", str(rows))
    sr = settle_records(merchant["id"], pid, "PAY")
    nr = notify_records(pid, "PAY_SUCCESS")
    check("结算恰好一次 net=2982 fee=18", len(sr) == 1 and sr[0]["net"] == 2982 and sr[0]["fee"] == 18, str(sr))
    check("通知恰好一次 PAY_SUCCESS", len(nr) == 1, str(nr))
    check("付款方扣 3000 / 商户净额 +2982 / 手续费户 +18",
          bal(ua["id"]) == 1000000 - 3000 and bal(ma["id"]) == 2982 and bal("SYS_FEE") >= 18)

    r = ok("POST", f"/api/payments/{pid}/refund", {"amount": 1000, "requestId": f"mq-rf-a-{SUFFIX}"})
    poll(lambda: ok("GET", f"/api/payments/{pid}"), lambda x: x["refundedAmount"] == 1000, timeout=25)
    refund_id = r.get("refundId")
    if not refund_id:
        recs = poll(lambda: settle_records(merchant["id"], pid, "REFUND"), lambda x: len(x) > 0, timeout=25)
        refund_id = recs[0]["bizId"] if recs else None
    if refund_id:
        rrows = wait_delivered(refund_id, ["SETTLE_REFUND", "NOTIFY_REFUND"])
        srf = next((x for x in rrows if x["event"] == "SETTLE_REFUND"), {})
        nrf = next((x for x in rrows if x["event"] == "NOTIFY_REFUND"), {})
        check("退款事件路由 PP_REFUND + 半消息 COMMIT 留痕",
              srf.get("topic") == "PP_REFUND" and nrf.get("topic") == "PP_REFUND"
              and srf.get("half_state") == "COMMIT" and nrf.get("half_state") == "COMMIT"
              and bool(srf.get("msg_id")) and bool(nrf.get("msg_id")), str(rrows))
    else:
        check("退款事件路由 PP_REFUND + 半消息 COMMIT 留痕", False, "未取到退款单号")
    srf_recs = settle_records(merchant["id"], pid, "REFUND")
    check("退款结算恰好一次（手续费按比例 6 分冲回，net=994）",
          len(srf_recs) == 1 and srf_recs[0]["fee"] == 6 and srf_recs[0]["net"] == 994, str(srf_recs))
    check("退款通知恰好一次 REFUND_SUCCESS", len(notify_records(refund_id or pid, "REFUND_SUCCESS")) == 1)
    diff = poll(consume_diff_total, lambda d: d == 0, timeout=30, interval=2)
    check("消费偏移一致 Consume Diff Total=0", diff == 0, str(diff))
    rc, out = mqadmin("topicList")
    check("PP_PAY/PP_REFUND topic 实建（有流量路由）", "PP_PAY" in out and "PP_REFUND" in out)

    # ---------- B. 双投幂等吸收（⑤ 回查/轮询/重复消息不重结算不重通知） ----------
    print("[B] 双投幂等吸收：同一事件重复投递（含 legacy topic）只结算/通知一次")
    fee_before, m_before, u_before = bal("SYS_FEE"), bal(ma["id"]), bal(ua["id"])
    for topic in ["PP_PAY", "PP_OUTBOX"]:
        okd, detail = send_duplicate(topic, "SETTLE_PAY", pid)
        check(f"重复 SETTLE_PAY 消息已注入 {topic}", okd, detail)
        okd, detail = send_duplicate(topic, "NOTIFY_PAY", pid)
        check(f"重复 NOTIFY_PAY 消息已注入 {topic}", okd, detail)
    if refund_id:
        okd, detail = send_duplicate("PP_REFUND", "SETTLE_REFUND", refund_id)
        check("重复 SETTLE_REFUND 消息已注入 PP_REFUND", okd, detail)
    time.sleep(8)  # 等消费端吸收全部重复消息
    check("重复投递后结算仍恰好一次（SETTLE_PAY 幂等键不变）",
          len(settle_records(merchant["id"], pid, "PAY")) == 1)
    check("重复投递后通知仍恰好一次（NOTIFY 幂等键不变）", len(notify_records(pid, "PAY_SUCCESS")) == 1)
    if refund_id:
        check("重复投递后退款结算仍恰好一次", len(settle_records(merchant["id"], pid, "REFUND")) == 1)
    check("重复投递不动账（余额三户不变）",
          bal("SYS_FEE") == fee_before and bal(ma["id"]) == m_before and bal(ua["id"]) == u_before)
    rows_after = outbox_rows(pid)
    check("重复投递不改 outbox 状态机（仍 DELIVERED、attempts 不增）",
          all(r["status"] == "DELIVERED" and r["attempts"] == 0 for r in rows_after), str(rows_after))
    diff = poll(consume_diff_total, lambda d: d == 0, timeout=30, interval=2)
    check("双投吸收后 Consume Diff Total=0", diff == 0, str(diff))

    # ---------- C. broker 全停 → 轮询兜底（③） ----------
    print("[C] broker 全停：半消息失败吞异常，3s 轮询兜底投递成功（验毕恢复）")
    check("停止 broker 容器", docker_ctl("stop", BROKER), BROKER)
    time.sleep(3)
    try:
        mc2, mac2, uac2, oc2, pid2, accept2 = make_pay(2000, "c", pay_timeout=90)
        check("broker 全停时 BALANCE 支付仍同步 SUCCESS（MQ 失败不阻断资金）",
              accept2["payment"]["status"] in ("PROCESSING", "SUCCESS"), str(accept2["payment"]["status"]))
        p2 = poll(lambda: ok("GET", f"/api/payments/{pid2}"), lambda x: x["status"] == "SUCCESS", timeout=30)
        check("broker 全停支付终态 SUCCESS", p2["status"] == "SUCCESS", str(p2))
        rows2 = wait_delivered(pid2, ["SETTLE_PAY", "NOTIFY_PAY"], timeout=30)
        check("broker 全停 outbox 由轮询兜底 DELIVERED（不丢）",
              all(r["status"] == "DELIVERED" for r in rows2), str(rows2))
        check("半消息未发留痕为空（msg_id/half_state NULL，与现状一致）",
              all(not r["msg_id"] and not r["half_state"] for r in rows2), str(rows2))
        check("broker 全停结算恰好一次 net=1988",
              len(settle_records(mc2["id"], pid2, "PAY")) == 1
              and settle_records(mc2["id"], pid2, "PAY")[0]["net"] == 1988)
        check("broker 全停通知恰好一次", len(notify_records(pid2, "PAY_SUCCESS")) == 1)
    finally:
        check("恢复 broker 容器", docker_ctl("start", BROKER), BROKER)
        ready = poll(lambda: mqadmin("topicList")[0], lambda rc: rc == 0, timeout=90, interval=3)
        check("broker 恢复就绪（topicList 可达）", ready == 0)
        check("dashboard 恢复（共享 netns 重挂）", docker_ctl("restart", DASHBOARD), DASHBOARD)
    pid3 = None
    if ready == 0:
        time.sleep(5)
        mc3, mac3, uac3, oc3, pid3, _ = make_pay(1000, "c2")
        rows3 = wait_delivered(pid3, ["SETTLE_PAY", "NOTIFY_PAY"])
        check("broker 恢复后事务消息主路径自动恢复（half_state=COMMIT）",
              all(r["half_state"] == "COMMIT" and r["msg_id"] for r in rows3), str(rows3))

    # ---------- D. 混沌：commit 半消息前 kill payment → 回查 COMMIT（②） ----------
    print("[D] 混沌：半消息 commit 前 kill -9 payment → 全栈重启 → broker 回查 COMMIT")
    full_restart({"PAYMENT_MQ_TX_CHAOS_DELAY_MS": "20000"}, "(注入 20s 判定延迟)")
    half_before = half_topic_max_offset()
    merchant_d, mad, uad, od, pid_d_holder = None, None, None, None, {}

    def chaos_pay():
        try:
            m, ma_, ua_, o, pid_, acc = make_pay(1500, "d", pay_timeout=90)
            pid_d_holder.update(merchant=m, ma=ma_, ua=ua_, order=o, pid=pid_, accept=acc)
        except Exception as e:
            pid_d_holder.setdefault("error", str(e))

    t = threading.Thread(target=chaos_pay, daemon=True)
    t.start()
    # 等本地事务提交 + 半消息发出（进入 20s 判定延迟窗口），在 grace(4s) 轮询兜底前 kill
    pid_d = None
    deadline = time.time() + 15
    while time.time() < deadline and not pid_d:
        time.sleep(0.5)
        found = sql("pp_payment", f"SELECT id FROM payment WHERE request_id='mq-d-{SUFFIX}'")
        if found:
            pid_d = found.splitlines()[0]
    check("混沌支付单已受理落库", bool(pid_d), str(pid_d_holder))
    if pid_d:
        time.sleep(1.0)  # 确保半消息已发（commit 后毫秒级），仍处判定延迟窗口
        pst = sql("pp_payment", f"SELECT status FROM payment WHERE id='{pid_d}'")
        check("kill 前本地事务已提交（payment SUCCESS，outbox 同事务落库）", pst == "SUCCESS", pst)
        rows_d0 = outbox_rows(pid_d)
        check("kill 前 outbox PENDING 且半消息回执未落（崩溃窗口特征）",
              len(rows_d0) >= 1 and all(r["status"] == "PENDING" and not r["half_state"] for r in rows_d0), str(rows_d0))
        half_mid = half_topic_max_offset()
        check("半消息已进入 broker（RMQ_SYS_TRANS_HALF_TOPIC offset 增长）",
              half_mid is not None and half_before is not None and half_mid > half_before,
              f"{half_before} -> {half_mid}")
        with open(os.path.join(ROOT, "run", "payment.pid")) as f:
            pay_pid = int(f.read().strip())
        os.kill(pay_pid, signal.SIGKILL)
        print(f"== 已 kill -9 payment (pid {pay_pid})，半消息 commit 丢失，等 broker 回查 ==", flush=True)
    t.join(timeout=30)

    full_restart(None, "(混沌恢复，延迟归零)")
    if pid_d:
        rows_d = wait_delivered(pid_d, ["SETTLE_PAY", "NOTIFY_PAY"], timeout=40)
        check("重启后 outbox 全部 DELIVERED（轮询兜底+回查双保险，不丢）",
              all(r["status"] == "DELIVERED" for r in rows_d), str(rows_d))
        # 回查窗口：broker transactionTimeOut=6s / checkInterval=30s，重启后 ≤30s 触发
        def checkback_done():
            rs = outbox_rows(pid_d)
            return any(r["event"] == "SETTLE_PAY" and r["half_state"] == "COMMIT" for r in rs)
        got = poll(checkback_done, lambda x: x, timeout=150, interval=3)
        rows_d2 = outbox_rows(pid_d)
        spd = next((r for r in rows_d2 if r["event"] == "SETTLE_PAY"), {})
        check("broker 回查触发 COMMIT（checkLocalTransaction 按 outbox 行判定+留痕）",
              got and spd.get("half_state") == "COMMIT" and bool(spd.get("msg_id")), str(rows_d2))
        log = open(os.path.join(ROOT, "logs", "payment.log"), encoding="utf-8", errors="replace").read()
        check("回查日志留痕（事务回查 event=SETTLE_PAY bizId=本单 state=COMMIT）",
              f"事务回查 event=SETTLE_PAY bizId={pid_d} state=COMMIT" in log)
        # 商户/账户从 DB 支付单取（pay 线程被 kill 时 HTTP 中断，不依赖其返回值）
        prow = sql("pp_payment", f"SELECT merchant_id,payer_account_id,merchant_account_id FROM payment WHERE id='{pid_d}'")
        pp = prow.split("\t")
        if len(pp) == 3:
            mid_d, payer_d, merc_d = pp
            time.sleep(8)  # 回查 COMMIT 后消费端再投一次 → 幂等吸收
            check("回查双投后结算仍恰好一次（不重）", len(settle_records(mid_d, pid_d, "PAY")) == 1)
            check("回查双投后通知仍恰好一次（不重）", len(notify_records(pid_d, "PAY_SUCCESS")) == 1)
            check("混沌单资金正确（付款方 -1500 / 商户 +1491）",
                  bal(payer_d) == 1000000 - 1500 and bal(merc_d) == 1491,
                  f"{bal(payer_d)}/{bal(merc_d)}")
        else:
            check("混沌单资金正确（付款方 -1500 / 商户 +1491）", False, prow)
        check("混沌单无 DEAD（不丢）", all(r["status"] != "DEAD" for r in rows_d2), str(rows_d2))
    else:
        skip("混沌回查断言", "混沌支付单未落库")

    # ---------- E. 回滚开关：普通消息 + 单 topic（§5.2 一键回滚） ----------
    print("[E] 回滚开关：transactional=false + multi-topic=false → 普通消息 PP_OUTBOX（升级前等价）")
    full_restart({"PAYMENT_MQ_TRANSACTIONAL": "false", "PAYMENT_MQ_MULTI_TOPIC": "false"}, "(回滚形态)")
    me, mae, uae, oe, pid_e, accept_e = make_pay(1000, "e")
    poll(lambda: ok("GET", f"/api/payments/{pid_e}"), lambda x: x["status"] == "SUCCESS", timeout=25)
    rows_e = wait_delivered(pid_e, ["SETTLE_PAY", "NOTIFY_PAY"])
    check("回滚形态全部事件走 PP_OUTBOX 单 topic", all(r["topic"] == "PP_OUTBOX" for r in rows_e), str(rows_e))
    check("回滚形态普通消息投递 DELIVERED（无半消息留痕，升级前等价）",
          all(r["status"] == "DELIVERED" and not r["half_state"] and not r["msg_id"] for r in rows_e), str(rows_e))
    check("回滚形态结算/通知恰好一次",
          len(settle_records(me["id"], pid_e, "PAY")) == 1 and len(notify_records(pid_e, "PAY_SUCCESS")) == 1)
    diff = poll(consume_diff_total, lambda d: d == 0, timeout=30, interval=2)
    check("回滚形态 Consume Diff Total=0", diff == 0, str(diff))
    full_restart(None, "(恢复事务消息+多 topic 最终形态)")

    # ---------- F. 混沌后资金护航：六项对账 + 试算平衡 ----------
    print("[F] 资金护航：六项对账 pass + 试算平衡")
    time.sleep(4)
    report = ok("POST", "/api/recon/run")
    checks = report.get("checks", [])
    check("对账六项全 pass", len(checks) == 6 and all(c["pass"] for c in checks),
          "; ".join(f"{c['name']}:{c['pass']}" for c in checks))
    trial = ok("GET", "/api/accounting/trial")
    check("试算平衡 D=C 且 Σbalance=0", trial.get("balanced") and trial.get("balanceSum") == 0, str(trial))

    print(f"\n结果：{PASSED} 通过，{FAILED} 失败，{SKIPPED} 跳过", flush=True)
    return 1 if FAILED else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:
        print(f"\n[ERROR] 脚本异常终止: {e}", flush=True)
        import traceback
        traceback.print_exc()
        sys.exit(2)