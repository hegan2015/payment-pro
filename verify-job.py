#!/usr/bin/env python3
"""verify-job.py — I-3 分布式调度（XXL-Job + ShedLock）验证。

跑法（docker 全栈已启动，gateway 8080）：
  python3 verify-job.py                 # 默认 docker：起临时第二 payment 实例做并发/降级验证，验完停掉
  PP_PROFILE=local python3 verify-job.py # local：只跑不依赖 MySQL/第二实例的断言，其余 SKIP（不新增进程）

断言分组：
  [1] shedlock 锁表双 profile 同构 + 锁提供者就绪
  [2] local @Scheduled 语义不变（fixedDelay 5s/3s 逐字节保留）+ 单实例兜底按节拍驱动（验收④）
  [3] ShedLock 互斥：锁被外部持有时，两个实例触发同一作业都跳过、不抢锁（验收②）
  [4] 双实例并发 recover：同一 xid 只被驱动一次（version 单调、outbox 无重复行、账务/结算/通知不重复）（验收①）
  [5] 分片 recon 不重不漏（CRC32 取模，并集=全量、两两不相交、逐片报告落库）（验收③）
  [6] XXL-Job 执行器真注册到共享栈 admin:8094（xxl_job_registry 实证 + 经 admin API 真触发）
  [7] 调度中心不可达 → 本地 @Scheduled 兜底仍驱动补偿（验收⑤，资金兜底服务端常驻）

数据红线：只读业务表；唯一写入是 shedlock 协调表的锁行（[3] 结束立即复原），
          以及 additive 新建测试商户/账户/支付单（与 smoke 同构，不删不改既有数据）。
临时实例：run/payment-jobverify.pid（绝不覆盖 run/payment.pid），脚本退出必停。
"""
import json
import os
import re
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zlib
from concurrent.futures import ThreadPoolExecutor

BASE = os.environ.get("PP_GATEWAY", "http://127.0.0.1:8080")
ROOT = os.path.dirname(os.path.abspath(__file__))
PROFILE = os.environ.get("PP_PROFILE", "docker")
DOCKER = os.environ.get("DOCKER_BIN", "/Users/hegan/.docker/bin/docker")
MYSQL_CONTAINER = os.environ.get("PP_MYSQL_CONTAINER", "claim-shared-mysql")
PAYMENT_A = os.environ.get("PP_PAYMENT_ACTUATOR", "http://127.0.0.1:18085")
NOTIFY_A = os.environ.get("PP_NOTIFY_ACTUATOR", "http://127.0.0.1:18090")
RECON_A = os.environ.get("PP_RECON_ACTUATOR", "http://127.0.0.1:18091")
# 临时第二实例（端口/Dubbo/执行器端口全部错开主实例）
B_HTTP = int(os.environ.get("PP_JOB_B_HTTP", "18185"))
B_DUBBO = int(os.environ.get("PP_JOB_B_DUBBO", "20985"))
B_XXL = int(os.environ.get("PP_JOB_B_XXL", "9988"))
PAYMENT_B = f"http://127.0.0.1:{B_HTTP}"
# 不可达的调度中心地址（验收⑤：不动共享栈容器，避免影响并行泳道）
BOGUS_ADMIN = os.environ.get("PP_JOB_BOGUS_ADMIN", "http://127.0.0.1:18094/xxl-job-admin")
XXL_ADMIN = os.environ.get("PAYMENT_XXL_ADMIN", "http://127.0.0.1:8094/xxl-job-admin")

SUFFIX = uuid.uuid4().hex[:6]
PASSED = FAILED = SKIPPED = 0
TOKEN = None
B_PID = None
MYSQL_OK = False


def check(name, cond, detail=""):
    global PASSED, FAILED
    if cond:
        PASSED += 1
        print(f"  [PASS] {name}")
    else:
        FAILED += 1
        print(f"  [FAIL] {name}  {detail}")


def skip(name, reason):
    global SKIPPED
    SKIPPED += 1
    print(f"  [SKIP] {name}  ({reason})")


def login():
    global TOKEN
    try:
        r = urllib.request.Request(BASE + "/api/auth/login",
                                   data=json.dumps({"username": "admin",
                                                    "password": os.environ.get("PP_ADMIN_PASSWORD", "admin123")}).encode(),
                                   method="POST", headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(r, timeout=10) as resp:
            TOKEN = json.loads(resp.read().decode()).get("token")
    except Exception:
        TOKEN = None


def http(method, url, body=None, timeout=30, bearer=False, form=None, cookie=None):
    data = None
    headers = {}
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if bearer and TOKEN:
        headers["Authorization"] = "Bearer " + TOKEN
    if cookie:
        headers["Cookie"] = cookie
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            text = resp.read().decode()
            return resp.status, (json.loads(text) if text else None), dict(resp.headers)
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        try:
            return e.code, json.loads(text), dict(e.headers)
        except Exception:
            return e.code, {"error": text}, dict(e.headers)
    except Exception as e:
        return 0, {"error": str(e)}, {}


def api(method, path, body=None):
    st, resp, _ = http(method, BASE + path, body, bearer=True)
    if st == 401:
        login()
        st, resp, _ = http(method, BASE + path, body, bearer=True)
    return st, resp


def ok(method, path, body=None):
    st, resp = api(method, path, body)
    assert st == 200, f"{method} {path} 期望 200，实际 {st}: {resp}"
    return resp


def poll(fn, pred, timeout=30, interval=0.4):
    deadline = time.time() + timeout
    val = fn()
    while not pred(val) and time.time() < deadline:
        time.sleep(interval)
        val = fn()
    return val


def ppjob(base_url):
    st, resp, _ = http("GET", base_url + "/actuator/ppjob", timeout=10)
    return resp if st == 200 else None


def trigger(base_url, name, shard_index=0, shard_total=1, param=""):
    # actuator @WriteOperation 要求所有非 @Selector 参数在 body 中出现且非 null（param 传空串而非 null）
    body = {"shardIndex": shard_index, "shardTotal": shard_total, "param": param if param is not None else ""}
    st, resp, _ = http("POST", base_url + "/actuator/ppjob/" + name, body, timeout=180)
    return st, resp


def mysql(sql, database=None):
    """只读/协调表写入经共享栈 MySQL 容器；口令从仓库外 env 取，绝不回显。"""
    if not MYSQL_OK:
        return None
    env = dict(os.environ)
    try:
        with open(os.path.expanduser("~/.config/payment-pro/docker.env")) as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    env[k] = v
    except Exception:
        return None
    pwd = env.get("MYSQL_ROOT_PASSWORD") or env.get("MYSQL_PASSWORD") or ""
    cmd = [DOCKER, "exec", "-e", f"MYSQL_PWD={pwd}", MYSQL_CONTAINER, "mysql", "-uroot", "-N", "-B"]
    if database:
        cmd.append(database)
    cmd += ["-e", sql]
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
        if out.returncode != 0:
            return None
        return out.stdout.strip()
    except Exception:
        return None


def mysql_rows(sql, database=None):
    raw = mysql(sql, database)
    if raw is None or raw == "":
        return []
    return [line.split("\t") for line in raw.splitlines()]


def start_instance_b(xxl_enabled, admin_addr):
    """起临时第二 payment 实例（docker profile，端口全错开），返回是否就绪。"""
    global B_PID
    java_home = "/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home"
    java = os.path.join(java_home, "bin", "java")
    if not os.path.exists(java):
        java = "java"
    env = dict(os.environ)
    try:
        with open(os.path.expanduser("~/.config/payment-pro/docker.env")) as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    env[k] = v
    except Exception as e:
        print(f"  !! 读取 docker.env 失败: {e}")
        return False
    env.update({
        "PAYMENT_HTTP_PORT": str(B_HTTP),
        "PAYMENT_DUBBO_PORT": str(B_DUBBO),
        "PAYMENT_XXL_ENABLED": "true" if xxl_enabled else "false",
        "PAYMENT_XXL_ADMIN": admin_addr,
        "PAYMENT_XXL_PORT": str(B_XXL),
        "PAYMENT_XXL_APPNAME": "payment-executor",
    })
    jar = os.path.join(ROOT, "payment-payment", "target", "payment-payment-1.0.0.jar")
    if not os.path.exists(jar):
        print(f"  !! 缺少 {jar}，先 ./mvnw package -DskipTests")
        return False
    log = open(os.path.join(ROOT, "logs", "payment-jobverify.log"), "ab")
    proc = subprocess.Popen([java, "-Dspring.profiles.active=docker", "-jar", jar],
                            stdout=log, stderr=subprocess.STDOUT, env=env, cwd=ROOT)
    B_PID = proc.pid
    with open(os.path.join(ROOT, "run", "payment-jobverify.pid"), "w") as f:
        f.write(str(proc.pid))
    print(f"  ==> 临时第二 payment 实例 pid={proc.pid} http={B_HTTP} dubbo={B_DUBBO} xxl={B_XXL} "
          f"xxlEnabled={xxl_enabled} admin={admin_addr}")
    deadline = time.time() + 120
    while time.time() < deadline:
        st, _, _ = http("GET", PAYMENT_B + "/actuator/health", timeout=3)
        if st == 200:
            return True
        if proc.poll() is not None:
            print("  !! 第二实例提前退出，见 logs/payment-jobverify.log")
            return False
        time.sleep(2)
    return False


def stop_instance_b():
    global B_PID
    if B_PID is None:
        return
    try:
        os.kill(B_PID, signal.SIGTERM)
        for _ in range(30):
            try:
                os.kill(B_PID, 0)
                time.sleep(1)
            except OSError:
                break
        else:
            os.kill(B_PID, signal.SIGKILL)
    except Exception:
        pass
    pidfile = os.path.join(ROOT, "run", "payment-jobverify.pid")
    if os.path.exists(pidfile):
        os.remove(pidfile)
    print(f"  ==> 临时第二实例已停止 pid={B_PID}")
    B_PID = None


def stability_gate(rounds=3, timeout=180):
    """重启/停临时实例后的稳定性门禁：连续 3 次 13/13 健康 + login 200 + Bearer members 200。"""
    deadline = time.time() + timeout
    good = 0
    while time.time() < deadline and good < rounds:
        st, resp = api("GET", "/api/health")
        healthy = st == 200 and isinstance(resp, list) and len(resp) == 13 and all(x.get("ok") for x in resp)
        login()
        st2, _ = api("GET", "/api/members")
        if healthy and st2 == 200:
            good += 1
        else:
            good = 0
        time.sleep(2)
    return good >= rounds


def main():
    global MYSQL_OK
    print(f"== I-3 分布式调度验证（profile={PROFILE} 批次 {SUFFIX}）==")
    login()

    # ---------- 0. 前置 ----------
    print("[0] 前置：健康门禁 + 调度端点可达")
    st, health = api("GET", "/api/health")
    check("网关 13 进程健康", st == 200 and isinstance(health, list) and len(health) == 13
          and all(x.get("ok") for x in health), str(health)[:200])
    a = ppjob(PAYMENT_A)
    n = ppjob(NOTIFY_A)
    r = ppjob(RECON_A)
    check("payment /actuator/ppjob 可达", a is not None, str(a)[:200])
    check("notify /actuator/ppjob 可达", n is not None, str(n)[:200])
    check("recon /actuator/ppjob 可达", r is not None, str(r)[:200])
    if not (a and n and r):
        print("!! 调度端点不可达，后续断言无法执行")
        return finish()
    check("payment 注册 recoverTransactions/dispatchOutbox",
          {"recoverTransactions", "dispatchOutbox"} <= set(a.get("handlers", [])), str(a.get("handlers")))
    check("notify 注册 notifyPush", "notifyPush" in n.get("handlers", []), str(n.get("handlers")))
    check("recon 注册 reconRun", "reconRun" in r.get("handlers", []), str(r.get("handlers")))
    MYSQL_OK = PROFILE == "docker"
    if MYSQL_OK:
        MYSQL_OK = mysql("SELECT 1") == "1"
        check("共享栈 MySQL 可访问（只读断言用）", MYSQL_OK)

    # ---------- 1. shedlock 锁表 ----------
    print("[1] shedlock 锁表双 profile 同构 + 锁提供者就绪")
    for label, snap in (("payment", a), ("notify", n), ("recon", r)):
        sl = snap.get("shedlock", {})
        check(f"{label} ShedLock 装配且锁表可读", sl.get("enabled") is True and "rows" in sl, str(sl)[:200])
    if MYSQL_OK:
        for db in ("pp_payment", "pp_notify", "pp_recon"):
            cols = mysql_rows("SELECT column_name, data_type FROM information_schema.columns "
                              f"WHERE table_schema='{db}' AND table_name='shedlock' ORDER BY ordinal_position")
            got = {c[0]: c[1] for c in cols}
            check(f"{db}.shedlock 列型 BIGINT 毫秒（name/lock_until/locked_at/locked_by）",
                  got.get("name") == "varchar" and got.get("lock_until") == "bigint"
                  and got.get("locked_at") == "bigint" and got.get("locked_by") == "varchar", str(got))
        pk = mysql("SELECT COUNT(*) FROM information_schema.key_column_usage "
                   "WHERE table_schema='pp_payment' AND table_name='shedlock' AND constraint_name='PRIMARY'")
        check("shedlock.name 为主键", pk == "1", str(pk))
    else:
        skip("MySQL shedlock DDL 断言", "local profile 或 MySQL 不可达")
    # local H2 schema.sql 双份 DDL 存在性（静态）
    for mod in ("payment-payment", "payment-notify", "payment-recon"):
        for f in ("schema.sql", "schema-mysql.sql"):
            p = os.path.join(ROOT, mod, "src", "main", "resources", f)
            txt = open(p, encoding="utf-8").read() if os.path.exists(p) else ""
            check(f"{mod}/{f} 含 shedlock DDL（BIGINT）",
                  "CREATE TABLE IF NOT EXISTS shedlock" in txt and "lock_until BIGINT" in txt)

    # ---------- 2. local @Scheduled 语义不变（验收④） ----------
    print("[2] local @Scheduled 语义不变（fixedDelay 逐字节保留）+ 兜底节拍")
    sched_src = open(os.path.join(ROOT, "payment-payment", "src", "main", "java", "com", "qoder",
                                  "payment", "payment", "service", "PaymentScheduler.java"), encoding="utf-8").read()
    check("recoverTransactions 仍 @Scheduled(fixedDelay = 5000)",
          re.search(r"@Scheduled\(fixedDelay = 5000\)\s*\n\s*public void recoverTransactions\(\)", sched_src) is not None)
    check("dispatchOutbox 仍 @Scheduled(fixedDelay = 3000)",
          re.search(r"@Scheduled\(fixedDelay = 3000\)\s*\n\s*public void dispatchOutbox\(\)", sched_src) is not None)
    check("reconcileProcessing 仍 fixedDelayString 可配（默认 5000）",
          '@Scheduled(fixedDelayString = "${payment.reconcile.interval-ms:5000}")' in sched_src)
    notify_job = open(os.path.join(ROOT, "payment-notify", "src", "main", "java", "com", "qoder",
                                   "payment", "notify", "service", "NotifyPushJob.java"), encoding="utf-8").read()
    check("notifyPush 本地触发仍 @Scheduled(fixedDelay = 3000)",
          re.search(r"@Scheduled\(fixedDelay = 3000\)", notify_job) is not None)
    pusher = open(os.path.join(ROOT, "payment-notify", "src", "main", "java", "com", "qoder",
                               "payment", "notify", "service", "NotifyPusher.java"), encoding="utf-8").read()
    check("NotifyPusher#push 加 @SchedulerLock(notifyPush)",
          '@SchedulerLock(name = "notifyPush"' in pusher)
    check("NotifyPusher#deadSweep 兜底扫描保持常驻 @Scheduled(30000)",
          "@Scheduled(fixedDelay = 30000, initialDelay = 20000)" in pusher)
    check("本地兜底 runner 已装配（matchIfMissing=true）",
          a.get("local", {}).get("runnerPresent") is True, str(a.get("local")))
    if PROFILE == "local":
        check("local 不连 XXL-Job（零依赖）", a.get("xxl", {}).get("enabled") is False, str(a.get("xxl")))
        before = ppjob(PAYMENT_A)["jobs"]
        time.sleep(12)
        after = ppjob(PAYMENT_A)["jobs"]
        d_rec = after["recoverTransactions"]["localRuns"] - before["recoverTransactions"]["localRuns"]
        d_out = after["dispatchOutbox"]["localRuns"] - before["dispatchOutbox"]["localRuns"]
        check("local 单实例 recover 按 5s 节拍驱动（12s 内 >=2 次）", d_rec >= 2, str(d_rec))
        check("local 单实例 outbox 按 3s 节拍驱动（12s 内 >=3 次）", d_out >= 3, str(d_out))
        check("local 单实例 ShedLock 生效（锁获取计数增加）",
              after["recoverTransactions"]["lockAcquired"] > before["recoverTransactions"]["lockAcquired"])
    else:
        skip("local profile 零依赖/节拍断言", "当前 docker profile（由临时实例 B 的兜底节拍等价验证，见 [7]）")

    # ---------- 6. XXL-Job 执行器真注册（提前跑，便于后续断言引用） ----------
    print("[6] XXL-Job 执行器真注册到共享栈 admin:8094")
    if PROFILE != "docker":
        skip("执行器注册断言", "local profile 不连 XXL-Job")
    else:
        check("payment 执行器已启动且调度中心可达",
              a.get("xxl", {}).get("executorRegistered") is True and a["xxl"].get("adminReachable") is True,
              str(a.get("xxl")))
        check("payment appname=payment-executor", a.get("xxl", {}).get("appname") == "payment-executor",
              str(a.get("xxl", {}).get("appname")))
        if MYSQL_OK:
            groups = mysql_rows("SELECT app_name, address_type FROM xxl_job.xxl_job_group "
                                "WHERE app_name LIKE 'payment%'")
            names = {g[0] for g in groups}
            check("调度中心已建 payment 执行器分组（additive，不动既有 claim 分组）",
                  {"payment-executor", "payment-notify-executor", "payment-recon-executor"} <= names, str(names))
            claim = mysql("SELECT COUNT(*) FROM xxl_job.xxl_job_group WHERE app_name='claim-pro-executor'")
            check("既有 claim-pro-executor 分组未被删改", claim == "1", str(claim))
            for app, port in (("payment-executor", 9998), ("payment-notify-executor", 9997),
                              ("payment-recon-executor", 9996)):
                reg = mysql("SELECT registry_value FROM xxl_job.xxl_job_registry "
                            f"WHERE registry_group='EXECUTOR' AND registry_key='{app}'")
                check(f"{app} 自动注册在线（含 :{port}）", reg is not None and f":{port}" in reg, str(reg))
            jobs = mysql_rows("SELECT executor_handler, trigger_status FROM xxl_job.xxl_job_info "
                              "WHERE executor_handler IN ('recoverTransactions','dispatchOutbox','notifyPush','reconRun')")
            handlers = {j[0] for j in jobs}
            check("调度中心已建 4 个 JobHandler 任务记录",
                  {"recoverTransactions", "dispatchOutbox", "notifyPush", "reconRun"} <= handlers, str(handlers))
            legacy = mysql("SELECT COUNT(*) FROM xxl_job.xxl_job_info WHERE executor_handler IN "
                           "('demoJobHandler','graphBuildJobHandler','goldRegressionJobHandler')")
            check("既有 3 条任务记录未被删改", legacy == "3", str(legacy))
        else:
            skip("xxl_job 库注册实证", "MySQL 不可达")
        # 真触发实证：调度中心 cron 触发 → 执行器执行回执（xxl_job_log trigger_code/handle_code=200）
        if MYSQL_OK:
            delivered = mysql_rows("SELECT l.job_id, i.executor_handler, l.trigger_code, l.handle_code "
                                   "FROM xxl_job.xxl_job_log l JOIN xxl_job.xxl_job_info i ON i.id = l.job_id "
                                   "WHERE i.executor_handler IN ('recoverTransactions','dispatchOutbox','notifyPush') "
                                   "AND l.trigger_code = 200 AND l.handle_code = 200")
            got = {d[1] for d in delivered}
            check("调度中心真触发且执行器真执行（xxl_job_log trigger/handle=200）",
                  {"recoverTransactions", "dispatchOutbox", "notifyPush"} <= got, str(got))
            addr = mysql("SELECT DISTINCT executor_address FROM xxl_job.xxl_job_log "
                         "WHERE executor_address LIKE '%host.docker.internal:999%'")
            check("触发回执地址为宿主执行器（host.docker.internal 回调成功）",
                  addr is not None and "host.docker.internal" in addr, str(addr))
            before = ppjob(PAYMENT_A)["jobs"]["recoverTransactions"]["remoteTriggers"]
            after = poll(lambda: ppjob(PAYMENT_A)["jobs"]["recoverTransactions"]["remoteTriggers"],
                         lambda v: v > before, timeout=25, interval=1)
            check("执行器持续收到 cron 触发（remoteTriggers 递增）", after > before, f"{before}->{after}")
        else:
            skip("xxl_job_log 真触发实证", "MySQL 不可达")
        # 附加：经 admin UI API 手动触发一次（控制台口令非默认时 SKIP，不影响上面的真触发实证）
        st, resp, hdrs = http("POST", XXL_ADMIN + "/login", form={"userName": "admin", "password": "123456"})
        cookie = hdrs.get("Set-Cookie", "").split(";")[0] if hdrs else ""
        if st == 200 and cookie and isinstance(resp, dict) and resp.get("code") == 200:
            jid = mysql("SELECT id FROM xxl_job.xxl_job_info WHERE executor_handler='recoverTransactions' LIMIT 1") \
                if MYSQL_OK else None
            if jid:
                before = ppjob(PAYMENT_A)["jobs"]["recoverTransactions"]["remoteTriggers"]
                st2, resp2, _ = http("POST", XXL_ADMIN + "/jobinfo/trigger",
                                     form={"id": jid, "executorParam": "", "addressList": ""}, cookie=cookie)
                check("admin API 手动触发返回成功", isinstance(resp2, dict) and resp2.get("code") == 200, str(resp2))
                after = poll(lambda: ppjob(PAYMENT_A)["jobs"]["recoverTransactions"]["remoteTriggers"],
                             lambda v: v > before, timeout=20, interval=1)
                check("手动触发被执行器接收（remoteTriggers 增加）", after > before, f"{before}->{after}")
            else:
                skip("admin API 手动触发", "取不到 jobId")
        else:
            skip("admin API 手动触发", "控制台口令非默认（真触发实证已由 xxl_job_log + remoteTriggers 覆盖）")

    # ---------- 临时第二实例 ----------
    b = None
    if PROFILE == "docker":
        print("[*] 起临时第二 payment 实例（docker，端口错开；调度中心指向不可达地址以验证兜底）")
        if start_instance_b(True, BOGUS_ADMIN):
            b = ppjob(PAYMENT_B)
            check("第二实例调度端点可达", b is not None)
        else:
            check("第二实例启动", False, "见 logs/payment-jobverify.log")
    else:
        skip("第二实例相关断言（[3][4][7]）", "local profile 不起额外进程")

    try:
        # ---------- 3. ShedLock 互斥（验收②） ----------
        print("[3] ShedLock 互斥：锁被持有时另一实例跳过")
        if MYSQL_OK and b:
            row = mysql_rows("SELECT name FROM pp_payment.shedlock WHERE name='recoverTransactions'")
            now_ms = int(time.time() * 1000)
            if not row:
                mysql(f"INSERT INTO pp_payment.shedlock(name,lock_until,locked_at,locked_by) "
                      f"VALUES('recoverTransactions',0,{now_ms},'verify-job-init')")
            mysql(f"UPDATE pp_payment.shedlock SET lock_until={now_ms + 120000}, locked_at={now_ms}, "
                  f"locked_by='verify-job-holder' WHERE name='recoverTransactions'")
            held = mysql("SELECT locked_by FROM pp_payment.shedlock WHERE name='recoverTransactions'")
            check("外部持有 recoverTransactions 锁（仅协调表，验完复原）", held == "verify-job-holder", str(held))
            sa = ppjob(PAYMENT_A)["jobs"]["recoverTransactions"]
            sb = ppjob(PAYMENT_B)["jobs"]["recoverTransactions"]
            results = []
            with ThreadPoolExecutor(max_workers=6) as pool:
                futs = [pool.submit(trigger, u, "recoverTransactions")
                        for u in (PAYMENT_A, PAYMENT_B) for _ in range(3)]
                for f in futs:
                    results.append(f.result())
            check("锁被持有期间 6 次触发均无异常返回", all(st == 200 for st, _ in results), str(results)[:200])
            ea = ppjob(PAYMENT_A)["jobs"]["recoverTransactions"]
            eb = ppjob(PAYMENT_B)["jobs"]["recoverTransactions"]
            check("实例A 全部跳过（lockSkipped +3，lockAcquired 不变）",
                  ea["lockSkipped"] - sa["lockSkipped"] >= 3 and ea["lockAcquired"] == sa["lockAcquired"],
                  f"{sa}->{ea}")
            check("实例B 全部跳过（lockSkipped +3，lockAcquired 不变）",
                  eb["lockSkipped"] - sb["lockSkipped"] >= 3 and eb["lockAcquired"] == sb["lockAcquired"],
                  f"{sb}->{eb}")
            still = mysql("SELECT locked_by FROM pp_payment.shedlock WHERE name='recoverTransactions'")
            check("持锁者未被抢占（locked_by 不变）", still == "verify-job-holder", str(still))
            # 复原锁行（只动协调表）
            mysql("UPDATE pp_payment.shedlock SET lock_until=0 WHERE name='recoverTransactions' "
                  "AND locked_by='verify-job-holder'")
            released = mysql("SELECT lock_until FROM pp_payment.shedlock WHERE name='recoverTransactions'")
            check("锁已复原释放", released == "0", str(released))
            stt, res = trigger(PAYMENT_B, "recoverTransactions")
            eb2 = ppjob(PAYMENT_B)["jobs"]["recoverTransactions"]
            check("释放后触发即拿到锁执行", stt == 200 and res.get("success") is True
                  and eb2["lockAcquired"] > eb["lockAcquired"], str(res))
        else:
            skip("ShedLock 外部持锁互斥断言", "需 docker + MySQL（local H2 文件库单进程独占，无法外部持锁）")

        # ---------- 4. 双实例并发 recover（验收①） ----------
        print("[4] 双实例并发 recover：同一 xid 只被驱动一次")
        merchant = ok("POST", "/api/members", {"name": f"调度商户{SUFFIX}", "type": "MERCHANT"})
        user = ok("POST", "/api/members", {"name": f"调度用户{SUFFIX}", "type": "USER"})
        ma = ok("POST", "/api/accounts", {"memberId": merchant["id"]})
        ua = ok("POST", "/api/accounts", {"memberId": user["id"]})
        ok("POST", "/api/accounting/internal/topup", {"accountId": ua["id"], "amount": 100000,
                                                      "bizNo": "job-topup-" + SUFFIX})
        order = ok("POST", "/api/orders", {"merchantId": merchant["id"], "subject": "调度并发商品",
                                           "amount": 3000})["order"]
        bal_before = ok("GET", f"/api/accounting/accounts/{ua['id']}/balance")
        targets = [PAYMENT_A] + ([PAYMENT_B] if b else [])
        sa = ppjob(PAYMENT_A)["jobs"]["recoverTransactions"]
        sb = ppjob(PAYMENT_B)["jobs"]["recoverTransactions"] if b else None
        r = ok("POST", "/api/cashier/pay", {"orderId": order["id"], "method": "BALANCE",
                                            "accountId": ua["id"], "requestId": "job-req-" + SUFFIX})
        pid = r["payment"]["id"]
        xid = r["payment"].get("xid")
        versions = []
        if MYSQL_OK and xid:
            v0 = mysql(f"SELECT version FROM pp_payment.dtx_transaction WHERE xid='{xid}'")
            if v0:
                versions.append(int(v0))

        def storm(_):
            out = []
            for u in targets:
                out.append(trigger(u, "recoverTransactions"))
                out.append(trigger(u, "dispatchOutbox"))
                if MYSQL_OK and xid:
                    vv = mysql(f"SELECT version FROM pp_payment.dtx_transaction WHERE xid='{xid}'")
                    if vv:
                        out.append(("version", int(vv)))
            return out

        storm_start = time.time()
        with ThreadPoolExecutor(max_workers=8) as pool:
            storm_results = list(pool.map(storm, range(6)))
        storm_elapsed = time.time() - storm_start
        for row in storm_results:
            for item in row:
                if isinstance(item, tuple) and item[0] == "version":
                    versions.append(item[1])
        p = poll(lambda: ok("GET", f"/api/payments/{pid}"), lambda x: x["status"] == "SUCCESS", timeout=40)
        check("并发 recover 风暴下支付单收敛 SUCCESS", p["status"] == "SUCCESS", str(p)[:200])
        check("TCC version 单调不减（无回退/无重复驱动回写）",
              not versions or all(versions[i] <= versions[i + 1] for i in range(len(versions) - 1)), str(versions))
        if MYSQL_OK:
            dup = mysql_rows(f"SELECT event, biz_id, COUNT(*) c FROM pp_payment.outbox WHERE biz_id='{pid}' "
                             f"GROUP BY event, biz_id HAVING c > 1")
            check("outbox 无重复行（同 event+bizId 唯一）", not dup, str(dup))
            cnt = mysql_rows(f"SELECT event, COUNT(*) FROM pp_payment.outbox WHERE biz_id='{pid}' GROUP BY event")
            got = {c[0]: int(c[1]) for c in cnt}
            check("SETTLE_PAY/NOTIFY_PAY 各恰好 1 行", got.get("SETTLE_PAY") == 1 and got.get("NOTIFY_PAY") == 1,
                  str(got))
            if xid:
                fin = mysql_rows(f"SELECT status, version, retry_count FROM pp_payment.dtx_transaction WHERE xid='{xid}'")
                check("dtx 终态 CONFIRMED 且重试次数未爆炸", fin and fin[0][0] == "CONFIRMED"
                      and int(fin[0][2]) <= 8, str(fin))
                branches = mysql(f"SELECT COUNT(*) FROM pp_payment.dtx_branch WHERE xid='{xid}'")
                dupb = mysql(f"SELECT COUNT(*) FROM (SELECT branch_id FROM pp_payment.dtx_branch "
                             f"WHERE xid='{xid}' GROUP BY branch_id HAVING COUNT(*)>1) t")
                check("dtx 分支无重复（分支数>0 且 branch_id 唯一）",
                      branches not in (None, "0") and dupb == "0", f"branches={branches} dup={dupb}")
        else:
            skip("outbox/dtx SQL 唯一性断言", "MySQL 不可达")
        tr = ok("GET", f"/api/accounting/transfers/{pid}")
        debit = sum(l["amount"] for l in tr["lines"] if l["dir"] == "D")
        credit = sum(l["amount"] for l in tr["lines"] if l["dir"] == "C")
        check("账务凭证借贷等额且无重复分录（借方=支付金额）", debit == 3000 and credit == 3000,
              f"D={debit} C={credit}")
        bal_after = ok("GET", f"/api/accounting/accounts/{ua['id']}/balance")
        check("付款方只扣一次 3000", bal_after == bal_before - 3000, f"{bal_before}->{bal_after}")
        sr = poll(lambda: [x for x in ok("GET", f"/api/settle/records?merchantId={merchant['id']}")
                           if x.get("paymentId") == pid and x["type"] == "PAY"], lambda x: len(x) > 0, timeout=40)
        check("结算单恰好 1 条（并发驱动不重复结算）", len(sr) == 1, str(len(sr)))
        nts = poll(lambda: ok("GET", f"/api/notify/records?bizId={pid}"), lambda x: len(x) > 0, timeout=40)
        check("通知记录恰好 1 条（并发驱动不重复通知）", len(nts) == 1, str(len(nts)))
        ea = ppjob(PAYMENT_A)["jobs"]["recoverTransactions"]
        check("实例A 并发期间真实执行了 recover（lockAcquired 增加）",
              ea["lockAcquired"] > sa["lockAcquired"], f"{sa}->{ea}")
        if b and sb:
            eb = ppjob(PAYMENT_B)["jobs"]["recoverTransactions"]
            check("实例B 也参与竞争（acquired 或 skipped 增加）",
                  eb["lockAcquired"] + eb["lockSkipped"] > sb["lockAcquired"] + sb["lockSkipped"], f"{sb}->{eb}")
            # 12 次并发触发 + 风暴期间 B 的本地 5s 节拍（A 由 XXL 驱动时本地静默）
            budget = 12 + int(storm_elapsed / 5) + 2
            got = (ea["lockAcquired"] - sa["lockAcquired"]) + (eb["lockAcquired"] - sb["lockAcquired"])
            check("两实例锁获取总数不超过触发次数上界（互斥生效，无并行重复驱动）",
                  got <= budget, f"acquired={got} budget={budget} elapsed={storm_elapsed:.1f}s")
            check("并发竞争中出现锁跳过（而非两边同时执行）",
                  (ea["lockSkipped"] - sa["lockSkipped"]) + (eb["lockSkipped"] - sb["lockSkipped"]) > 0,
                  f"A+{ea['lockSkipped'] - sa['lockSkipped']} B+{eb['lockSkipped'] - sb['lockSkipped']}")

        # ---------- 7. 调度中心不可达兜底（验收⑤） ----------
        print("[7] 调度中心不可达 → 本地 @Scheduled 兜底仍驱动（资金兜底常驻）")
        if b:
            check("第二实例调度中心不可达被探测到", b.get("xxl", {}).get("adminReachable") is False,
                  str(b.get("xxl")))
            jb = ppjob(PAYMENT_B)["jobs"]
            check("不可达时全部作业回切本地兜底（localActive=true）",
                  all(v.get("localActive") is True for v in jb.values()),
                  str({k: v.get("localActive") for k, v in jb.items()}))
            time.sleep(13)
            jb2 = ppjob(PAYMENT_B)["jobs"]
            d_rec = jb2["recoverTransactions"]["localRuns"] - jb["recoverTransactions"]["localRuns"]
            d_out = jb2["dispatchOutbox"]["localRuns"] - jb["dispatchOutbox"]["localRuns"]
            check("兜底 recover 按 5s 节拍持续驱动（13s 内 >=2 次）", d_rec >= 2, str(d_rec))
            check("兜底 outbox 按 3s 节拍持续驱动（13s 内 >=3 次）", d_out >= 3, str(d_out))
            check("兜底真实拿到锁执行业务（lockAcquired 增加，非空转）",
                  jb2["dispatchOutbox"]["lockAcquired"] > jb["dispatchOutbox"]["lockAcquired"],
                  f"{jb['dispatchOutbox']['lockAcquired']}->{jb2['dispatchOutbox']['lockAcquired']}")
            # 兜底期间端到端：新建支付单仍收敛 + 结算/通知送达
            o2 = ok("POST", "/api/orders", {"merchantId": merchant["id"], "subject": "兜底商品",
                                            "amount": 1000})["order"]
            r2 = ok("POST", "/api/cashier/pay", {"orderId": o2["id"], "method": "BALANCE",
                                                 "accountId": ua["id"], "requestId": "job-req2-" + SUFFIX})
            p2 = poll(lambda: ok("GET", f"/api/payments/{r2['payment']['id']}"),
                      lambda x: x["status"] == "SUCCESS", timeout=40)
            check("调度中心不可达期间支付仍收敛 SUCCESS", p2["status"] == "SUCCESS", str(p2)[:160])
            p2id = r2["payment"]["id"]
            sr2 = poll(lambda: [x for x in ok("GET", f"/api/settle/records?merchantId={merchant['id']}")
                                if x.get("paymentId") == p2id and x["type"] == "PAY"],
                       lambda x: len(x) > 0, timeout=40)
            check("兜底期间 outbox 投递不停摆（结算单生成）", len(sr2) == 1, str(len(sr2)))
            nt2 = poll(lambda: ok("GET", f"/api/notify/records?bizId={p2id}"), lambda x: len(x) > 0, timeout=40)
            check("兜底期间通知记录生成", len(nt2) == 1, str(len(nt2)))
            if PROFILE == "docker":
                ja = poll(lambda: ppjob(PAYMENT_A)["jobs"],
                          lambda j: j["recoverTransactions"]["localActive"] is False
                          and j["recoverTransactions"]["remoteTriggers"] > 0, timeout=40, interval=2)
                check("主实例由 XXL-Job 驱动时本地兜底静默（docker @Scheduled 让位）",
                      ja["recoverTransactions"]["localActive"] is False
                      and ja["recoverTransactions"]["remoteTriggers"] > 0,
                      str(ja["recoverTransactions"])[:200])
                check("未配置调度任务的作业仍由本地兜底常驻（reconcileProcessing）",
                      ja["reconcileProcessing"]["localActive"] is True, str(ja.get("reconcileProcessing"))[:200])
        else:
            skip("调度中心不可达兜底断言", "第二实例未就绪")

        # ---------- 5. 分片 recon 不重不漏（验收③） ----------
        print("[5] 分片 recon 不重不漏")
        st, plan3, _ = http("GET", RECON_A + "/actuator/pprecon?shardTotal=3", timeout=60)
        check("recon /actuator/pprecon 可达", st == 200 and plan3 is not None, str(plan3)[:200])
        if st == 200 and plan3:
            shards = plan3["shards"]
            sets = [set(s["paymentIds"]) for s in shards]
            union = set().union(*sets) if sets else set()
            all_success = {p["id"] for p in ok("GET", "/api/payments") if p["status"] == "SUCCESS"}
            check("分片并集 == 全量 SUCCESS 支付单（不漏）", union == all_success,
                  f"union={len(union)} all={len(all_success)}")
            check("分片两两不相交（不重）",
                  sum(len(s) for s in sets) == len(union), f"sum={sum(len(s) for s in sets)} union={len(union)}")
            check("分片计数合计 == totalPayments", sum(s["count"] for s in shards) == plan3["totalPayments"])
            check("每片归属与 CRC32 取模一致",
                  all(all(zlib.crc32(i.encode()) % 3 == s["shardIndex"] for i in s["paymentIds"])
                      for s in shards))
            st1, plan1, _ = http("GET", RECON_A + "/actuator/pprecon?shardTotal=1", timeout=60)
            check("单片(0/1)即全量（run() 语义不变）",
                  st1 == 200 and plan1["shards"][0]["count"] == plan1["totalPayments"]
                  and set(plan1["shards"][0]["paymentIds"]) == all_success, str(plan1)[:200])
            reports_before = mysql("SELECT COUNT(*) FROM pp_recon.recon_report") if MYSQL_OK else None
            for i in range(3):
                stt, res = trigger(RECON_A, "reconRun", i, 3)
                check(f"分片 {i}/3 触发执行成功", stt == 200 and res.get("success") is True, str(res)[:200])
            if MYSQL_OK:
                reports_after = mysql("SELECT COUNT(*) FROM pp_recon.recon_report")
                check("逐片报告落库（新增 3 条）",
                      reports_before is not None and int(reports_after) - int(reports_before) == 3,
                      f"{reports_before}->{reports_after}")
                locks = mysql_rows("SELECT name FROM pp_recon.shedlock WHERE name LIKE 'reconRun:%'")
                names = {x[0] for x in locks}
                check("逐片独立锁名 reconRun:<idx>/<total>（各片可并行）",
                      {"reconRun:0/3", "reconRun:1/3", "reconRun:2/3"} <= names, str(names))
            latest = ok("GET", "/api/recon/latest")
            check("分片对账后最新报告六项齐全且通过", latest and len(latest.get("checks", [])) == 6
                  and latest.get("pass") is True, str(latest)[:200])
            stt, res = trigger(RECON_A, "reconRun", 0, 1)
            check("单片全量 reconRun 成功（与 /api/recon/run 等价）",
                  stt == 200 and res.get("success") is True, str(res)[:200])
            full = ok("POST", "/api/recon/run")
            check("控制台 /api/recon/run 仍全量六项通过（无回归）",
                  len(full.get("checks", [])) == 6 and full.get("pass") is True, str(full)[:200])
        # 分片互斥：同片重复触发不并发（锁跳过计数）
        sr0 = ppjob(RECON_A)["jobs"]["reconRun"]
        with ThreadPoolExecutor(max_workers=4) as pool:
            list(pool.map(lambda _: trigger(RECON_A, "reconRun", 0, 3), range(4)))
        sr1 = ppjob(RECON_A)["jobs"]["reconRun"]
        check("同片并发触发被 ShedLock 收敛（acquired+skipped 计入，无异常）",
              sr1["lockAcquired"] + sr1["lockSkipped"] >= sr0["lockAcquired"] + sr0["lockSkipped"] + 4,
              f"{sr0}->{sr1}")
    finally:
        if B_PID is not None:
            stop_instance_b()
            print("  ==> 停临时实例后重过稳定性门禁（避免 Dubbo invoker 残留影响后续脚本）")
            check("临时实例清理后全栈仍 13/13 稳定", stability_gate(), "健康门禁未过")
    return finish()


def finish():
    print(f"\n== verify-job 结果：PASS={PASSED} FAIL={FAILED} SKIP={SKIPPED} ==")
    return 1 if FAILED else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    finally:
        if B_PID is not None:
            stop_instance_b()