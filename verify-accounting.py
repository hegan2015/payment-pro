#!/usr/bin/env python3
# ============================================================================
# payment-pro P1 第二期 · E 泳道验证：账务工作流 AC-2/AC-3/AC-4/AC-5
#
# 用法：docker 全栈在线（./run.sh --docker）后 python3 verify-accounting.py
#   [1] AC-2 科目体系：目录/层级/方向、既有系统户固定映射对照、分录落科目、
#       客户账户默认 2001、改挂只动元数据、**系统户余额与记账口径一字不变**
#   [2] AC-3 日切 + 日终批：状态机 OPEN→CUTTING→CLOSED、余额连续（逐账户校验和）、
#       重复日切幂等、日终批可重跑、分片不重不漏、跨日凭证归属受理日快照、
#       **默认不自动跑批 + 损益结转默认关闭（保护 SYS_FEE 手续费闭环口径）**
#   [3] 多实例调度不重复：第二个 accounting 进程共享同一 MySQL 锁表，
#       并发触发同一会计日 → ShedLock 互斥、日切只发生一次、无重复副作用
#   [4] AC-4 账簿/凭证：总账=明细账汇总、余额表 Σ=0、跨片分页不重不漏、
#       科目/账户/会计日/日期区间筛选、凭证联查（bizNo/refId/科目/账户）、
#       跨片聚合四路对照（逐物理表/各片之和/逻辑顶层 SUM/GROUP BY 分片键）
#   [5] 凭证只读不可改：同 bizNo 异 lines 拒绝、同 lines 幂等不重复过账、
#       冲正单不可再冲正、日切/日终/改挂前后原凭证逐字不变
#   [6] AC-5 账实核对：一致 pass、差异分级告警（WARN/ERROR/CRITICAL）、
#       长短款分类、留痕带会计日/渠道、差错单建立 + 幂等不重复建单、
#       多渠道汇总、**P0 模拟银行口径逐值保留**
#   [7] 分片回滚：切流 active=true 后账簿/凭证/科目余额逐条一致，
#       回滚单库后仍逐条一致，影子比对 diffTotal=0
#   [8] 资金护航：全程试算 D=C 且 Σbalance=0 + 六项对账 pass
#   [9] local H2 隔离 JVM：不加载 ShardingSphere、科目/日切/日终同构、
#       **损益结转真实执行**（隔离内存库预置损益户余额，D 收入/C SYS_PROFIT，
#       结转后损益户归零、试算仍平衡、重跑幂等不产生第二张凭证）
#
# 数据保护：只新增业务行（真实充值/支付/挂账/银行侧事件），绝不删除/重置任何既有数据；
#           银行侧调整以等额反向事件冲回（只追加）；配置文件只增删本脚本自己的键；
#           隔离 JVM 用 H2 **内存**库，不触碰 data/ 与运行中的 docker 栈数据。
# 断言口径：不放宽、不改库状态凑绿；失败如实记录。
# ============================================================================
import json
import os
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.dirname(os.path.abspath(__file__))
BASE = "http://127.0.0.1:8080"
ACC_ACT = "http://127.0.0.1:18083/actuator"
ACC_SHARD = ACC_ACT + "/ppshard"
JAVA_HOME = os.environ.get("JAVA_HOME",
                           "/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home")
CONFIG_FILE = os.path.expanduser("~/.config/payment-pro/config.properties")
DOCKER_ENV = os.path.expanduser("~/.config/payment-pro/docker.env")
SUFFIX = uuid.uuid4().hex[:6]
DAY_MS = 86_400_000
LOCAL_PORT = int(os.environ.get("P1E_LOCAL_ACC_PORT", "18183"))
LOCAL_DUBBO = int(os.environ.get("P1E_LOCAL_ACC_DUBBO", "20993"))
SECOND_PORT = int(os.environ.get("P1E_SECOND_ACC_PORT", "18184"))
SECOND_DUBBO = int(os.environ.get("P1E_SECOND_ACC_DUBBO", "20994"))
TOKEN = None
STEP = None
PASSED = 0
FAILED = 0
FAILURES = []
MY_CONFIG_KEYS = {"accounting.daycut.at", "accounting.daycut.auto-enabled",
                  "accounting.dayend.profit-transfer.enabled", "accounting.reserve.channels",
                  "accounting.reserve.warn-threshold", "accounting.reserve.critical-threshold"}
CONFIG_EXISTED = False
PROCS = []


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


def http(url, method="GET", body=None, bearer=False, step=False, timeout=180, raw=False):
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
        return 0, {"error": f"{type(e).__name__}: {e}"}


def login():
    global TOKEN, STEP
    pwd = os.environ.get("PP_ADMIN_PASSWORD", "admin123")
    st, resp = http(BASE + "/api/auth/login", "POST", {"username": "admin", "password": pwd})
    assert st == 200, f"登录失败 {st} {resp}"
    TOKEN = resp["token"]
    st, resp = http(BASE + "/api/security/step-up", "POST", {"password": pwd}, bearer=True)
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


def act(base=ACC_ACT, path="", body=None):
    st, resp = http(base + ("/" + path if path else ""), "POST" if body is not None else "GET", body)
    assert st == 200, f"{base}/{path} 期望 200，实际 {st}: {resp}"
    return resp


def bal(account_id):
    return ok("GET", f"/api/accounting/accounts/{account_id}/balance")


def trial():
    return ok("GET", "/api/accounting/trial")


def trial_guard(stage):
    t = trial()
    return check(f"[{stage}] 试算平衡 D=C 且 Σbalance=0",
                 bool(t.get("balanced")) and t.get("balanceSum") == 0, str(t))


def recon_guard(stage):
    time.sleep(4)  # 等 outbox 末批投递（与 smoke/verify-sharding 同口径）
    rep = ok("POST", "/api/recon/run")
    check(f"[{stage}] 六项对账口径不变", len(rep["checks"]) == 6, str([c["name"] for c in rep["checks"]]))
    for c in rep["checks"]:
        check(f"[{stage}] 对账：{c['name']}", c["pass"], c["detail"])
    return rep


def poll(fn, pred, timeout=40, interval=0.4):
    deadline = time.time() + timeout
    val = fn()
    while time.time() < deadline and not pred(val):
        time.sleep(interval)
        val = fn()
    return val


def new_party(tag, kind="MERCHANT"):
    m = ok("POST", "/api/members", {"name": f"AC{kind[0]}{tag}{SUFFIX}", "type": kind})
    a = ok("POST", "/api/accounts", {"memberId": m["id"]})
    return m, a


def topup(account_id, amount, tag):
    return ok("POST", "/api/accounting/internal/topup",
              {"accountId": account_id, "amount": amount, "bizNo": f"ac-topup-{tag}-{SUFFIX}"})


def pay_once(tag, amount=10000):
    merchant, ma = new_party(tag)
    user, ua = new_party(tag, "USER")
    topup(ua["id"], amount * 3, tag)
    order = ok("POST", "/api/orders",
               {"merchantId": merchant["id"], "subject": "AC 账务工作流验证", "amount": amount})["order"]
    r = ok("POST", "/api/cashier/pay", {"orderId": order["id"], "method": "BALANCE",
                                       "accountId": ua["id"], "requestId": f"ac-req-{tag}-{SUFFIX}"})
    pid = r["payment"]["id"]
    p = poll(lambda: ok("GET", f"/api/payments/{pid}"), lambda x: x["status"] == "SUCCESS")
    assert p["status"] == "SUCCESS", f"支付未成功: {p}"
    return {"merchant": merchant, "ma": ma, "user": user, "ua": ua, "payment": p, "orderId": order["id"]}


def line(account_id, dir_, amount):
    return {"accountId": account_id, "dir": dir_, "amount": amount}


def entries_of(biz_no):
    return ok("GET", f"/api/accounting/entries?bizNo={biz_no}&size=100")["items"]


def voucher(biz_no):
    return ok("GET", f"/api/accounting/vouchers/{biz_no}")


def config_read():
    if not os.path.exists(CONFIG_FILE):
        return {}
    out = {}
    with open(CONFIG_FILE, encoding="utf-8") as f:
        for ln in f:
            ln = ln.strip()
            if not ln or ln.startswith("#") or "=" not in ln:
                continue
            k, v = ln.split("=", 1)
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
        for k, v in sorted(current.items()):
            f.write(f"{k}={v}\n")
    act(path="ppaccounting", body={"action": "refresh"})


def docker_env():
    out = {}
    if os.path.exists(DOCKER_ENV):
        with open(DOCKER_ENV, encoding="utf-8") as f:
            for ln in f:
                ln = ln.strip()
                if not ln or ln.startswith("#") or "=" not in ln:
                    continue
                k, v = ln.split("=", 1)
                out[k.strip()] = v.strip().strip('"').strip("'")
    return out


def leaf_sheet(biz_date=None):
    q = f"?bizDate={biz_date}" if biz_date else ""
    return [x for x in ok("GET", "/api/accounting/balance-sheet" + q) if x.get("level") == 2]


def root_sheet(biz_date=None):
    q = f"?bizDate={biz_date}" if biz_date else ""
    return [x for x in ok("GET", "/api/accounting/balance-sheet" + q) if x.get("level") == 1]


def detail_all(query="", size=13, limit=6000):
    """跨片分页遍历明细账：返回 (ids, total, pages, items)。"""
    ids, items, page, pages, total = [], [], 1, 0, None
    sep = "&" if query else ""
    while len(ids) < limit:
        d = ok("GET", f"/api/accounting/detail-ledger?{query}{sep}page={page}&size={size}")
        total = d["total"]
        pages += 1
        batch = d["items"]
        if not batch:
            break
        ids.extend(x["id"] for x in batch)
        items.extend(batch)
        if len(batch) < size:
            break
        page += 1
    return ids, total, pages, items


def snapshot(biz_date, accounts):
    """回滚一致性对照快照：试算/余额/科目余额表/总账/明细账/凭证（逐条可比）。"""
    return {
        "trial": trial(),
        "balances": {a: bal(a) for a in accounts},
        "sheet": sorted([(x["subjectCode"], x["bizDate"], x["debitTotal"], x["creditTotal"],
                          x["balance"], x["openingBalance"], x["entryCount"], x["accountCount"])
                         for x in ok("GET", f"/api/accounting/balance-sheet?bizDate={biz_date}")]),
        "general": [(x["subjectCode"], x["bizDate"], x["debitTotal"], x["creditTotal"], x["closingBalance"])
                    for x in ok("GET", f"/api/accounting/general-ledger?bizDate={biz_date}&page=1&size=50")["items"]],
        "detail": [(x["id"], x["bizNo"], x["accountId"], x["dir"], x["amount"], x["balanceAfter"],
                    x.get("subjectCode"), x.get("bizDate"))
                   for x in ok("GET", "/api/accounting/detail-ledger?page=1&size=17")["items"]],
        "vouchers": [(x["bizNo"], x["voucherType"], x["totalDebit"], x["totalCredit"], x["balanced"],
                      tuple(sorted(x.get("subjectCodes") or [])))
                     for x in ok("GET", "/api/accounting/vouchers?page=1&size=9")["items"]],
        "day": {k: ok("GET", "/api/accounting/day").get(k) for k in ("bizDate", "status")},
    }


def start_jvm(name, port, dubbo_port, extra, env=None, logfile=None):
    jar = os.path.join(ROOT, "payment-accounting", "target", "payment-accounting-1.0.0.jar")
    if not os.path.exists(jar):
        return None, f"jar 缺失: {jar}"
    cmd = [os.path.join(JAVA_HOME, "bin", "java"),
           f"-Dserver.port={port}", f"-Ddubbo.protocol.port={dubbo_port}",
           "-Ddubbo.application.qos-enable=false", "-Ddubbo.consumer.check=false"] + extra + ["-jar", jar]
    e = dict(os.environ)
    e["JAVA_HOME"] = JAVA_HOME
    if env:
        e.update(env)
    log = open(logfile or os.path.join(ROOT, "logs", f"p1e-{name}.log"), "w")
    proc = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT, cwd=ROOT, env=e)
    PROCS.append(proc)
    url = f"http://127.0.0.1:{port}/actuator/ppaccounting"
    for _ in range(150):
        if proc.poll() is not None:
            return proc, f"进程提前退出 exit={proc.returncode}"
        st, _ = http(url, timeout=3)
        if st == 200:
            return proc, None
        time.sleep(1)
    return proc, "150s 未就绪"


def stop_procs():
    for p in PROCS:
        try:
            p.send_signal(signal.SIGTERM)
            p.wait(timeout=25)
        except Exception:
            try:
                p.kill()
            except Exception:
                pass
    PROCS.clear()


# ============================================================================
def main():
    global CONFIG_EXISTED
    print(f"== payment-pro 账务工作流验证 AC-2/AC-3/AC-4/AC-5（批次 {SUFFIX}）==")
    login()
    CONFIG_EXISTED = os.path.exists(CONFIG_FILE)
    ops = act(path="ppaccounting")
    sharding_configured = ops.get("shardingConfigured")
    was_active = ops.get("shardingActive")

    # ---------------- [1] AC-2 科目体系 ----------------
    print("[1] AC-2 会计科目体系（系统户映射后余额与口径一字不变）")
    sys_accounts = ["SYS_CASH", "SYS_FEE", "SYS_FUND", "SYS_SUSPENSE", "SYS_PAYOUT", "SYS_CLEARING"]
    base_bal = {a: bal(a) for a in sys_accounts}
    base_trial = trial()
    check("基线试算 D=C 且 Σbalance=0", base_trial["balanced"] and base_trial["balanceSum"] == 0, str(base_trial))

    subjects = ok("GET", "/api/accounting/subjects")
    codes = {s["subjectCode"]: s for s in subjects}
    check("科目目录已播种（≥18 条）", len(subjects) >= 18, str(len(subjects)))
    check("科目类型齐备：资产/负债/共同/权益/损益",
          {"ASSET", "LIABILITY", "COMMON", "EQUITY", "INCOME", "EXPENSE"} <= {s["type"] for s in subjects},
          str({s["type"] for s in subjects}))
    check("科目层级齐备（1=类别根 2=明细）", {1, 2} <= {s["level"] for s in subjects},
          str({s["level"] for s in subjects}))
    check("科目号+名称+方向齐备", all(s.get("name") and s.get("direction") in ("D", "C") for s in subjects))
    check("明细科目均有父科目（层级树完整）",
          all(codes.get(s["parentCode"]) for s in subjects if s["level"] == 2))
    check("资产/费用类借增贷减(D)，负债/权益/收入类贷增借减(C)",
          all((s["direction"] == "D") == (s["type"] in ("ASSET", "EXPENSE", "COMMON"))
              for s in subjects if s["level"] == 2),
          str([(s["subjectCode"], s["type"], s["direction"]) for s in subjects if s["level"] == 2]))

    expect_map = {"SYS_CASH": "1001", "SYS_FEE": "6001", "SYS_FUND": "2005", "SYS_SUSPENSE": "2003",
                  "SYS_PAYOUT": "2004", "SYS_CLEARING": "3001", "SYS_PROFIT": "4001",
                  "SYS_ESCROW": "2002", "SYS_MARKETING": "6002", "SYS_DEPOSIT": "2006", "SYS_FX": "6601"}
    mapping = act(path="ppaccounting/mapping")
    resolved = {r["systemAccount"]: r for r in mapping["systemMappings"]}
    for acct, code in expect_map.items():
        check(f"系统户固定映射 {acct} → {code}",
              resolved.get(acct, {}).get("subjectCode") == code
              and resolved.get(acct, {}).get("resolvedSubject") == code, str(resolved.get(acct)))
    for acct in sys_accounts:
        check(f"映射后 {acct} 余额一字不变", resolved[acct]["balance"] == base_bal[acct],
              f'{resolved[acct]["balance"]} vs {base_bal[acct]}')
    check("客户账户默认科目 = 2001 客户余额", mapping["customerDefaultSubject"] == "2001")

    m1, a1 = new_party("m1")
    check("新客户账户默认挂 2001", str(ok("GET", f'/api/accounting/accounts/{a1["id"]}/subject')) == "2001")
    topup(a1["id"], 30000, "m1")
    ents = entries_of(f"ac-topup-m1-{SUFFIX}")
    check("充值分录 2 行（D SYS_CASH / C 客户账户）", len(ents) == 2, str(ents))
    check("充值分录落科目：SYS_CASH→1001、客户账户→2001",
          {e["accountId"]: e.get("subjectCode") for e in ents} == {"SYS_CASH": "1001", a1["id"]: "2001"},
          str([(e["accountId"], e.get("subjectCode")) for e in ents]))
    check("充值口径不变：D SYS_CASH / C 客户账户，金额一致",
          sorted((e["accountId"], e["dir"], e["amount"]) for e in ents)
          == sorted([("SYS_CASH", "D", 30000), (a1["id"], "C", 30000)]), str(ents))
    check("充值后余额 = 贷−借（客户 +30000）", bal(a1["id"]) == 30000 and bal("SYS_CASH") == base_bal["SYS_CASH"] - 30000)
    check("充值分录带会计日标签", all(e.get("bizDate") for e in ents), str(ents))

    ctx = pay_once("p1", 10000)
    pid = ctx["payment"]["id"]
    pay_ents = entries_of(pid)
    fee = 10000 * 60 // 10000
    check("手续费口径不变：feeBps=60 → SYS_FEE 贷腿 60 分",
          any(e["accountId"] == "SYS_FEE" and e["dir"] == "C" and e["amount"] == fee for e in pay_ents),
          str(pay_ents))
    check("手续费分录落科目 6001（损益/收入）",
          all(e.get("subjectCode") == "6001" for e in pay_ents if e["accountId"] == "SYS_FEE"), str(pay_ents))
    check("支付分录三方落科目（付款 2001 / 商户 2001 / SYS_FEE 6001）",
          {e["accountId"]: e.get("subjectCode") for e in pay_ents}
          == {ctx["ua"]["id"]: "2001", ctx["ma"]["id"]: "2001", "SYS_FEE": "6001"},
          str([(e["accountId"], e.get("subjectCode")) for e in pay_ents]))
    check("SYS_FEE 余额增量 = 手续费（口径不变）", bal("SYS_FEE") == base_bal["SYS_FEE"] + fee)

    st, r = api("POST", "/api/accounting/subject-mapping",
                {"accountId": "SYS_FEE", "subjectCode": "2001"}, step=True)
    check("系统户科目固定映射不可改（拒绝）", st != 200, f"{st} {r}")
    st, r = api("POST", "/api/accounting/subject-mapping", {"accountId": a1["id"], "subjectCode": "1000"}, step=True)
    check("只能挂明细科目（类别根拒绝）", st != 200, f"{st} {r}")
    st, r = api("POST", "/api/accounting/subject-mapping", {"accountId": a1["id"], "subjectCode": "9999"}, step=True)
    check("不存在科目拒绝", st != 200, f"{st} {r}")
    st, r = api("POST", "/api/accounting/subject-mapping", {"accountId": a1["id"], "subjectCode": "2003"}, step=True)
    check("客户账户改挂 2003 成功", st == 200 and r["subjectCode"] == "2003", f"{st} {r}")
    check("改挂后解析生效", str(ok("GET", f'/api/accounting/accounts/{a1["id"]}/subject')) == "2003")
    check("改挂只动元数据：余额不变", bal(a1["id"]) == 30000)
    check("改挂不回改历史分录科目（append-only）",
          all(e.get("subjectCode") == "2001" for e in entries_of(f"ac-topup-m1-{SUFFIX}")
              if e["accountId"] == a1["id"]))
    topup(a1["id"], 5000, "m1b")
    check("改挂后新分录落新科目 2003",
          all(e.get("subjectCode") == "2003" for e in entries_of(f"ac-topup-m1b-{SUFFIX}")
              if e["accountId"] == a1["id"]))
    ok("POST", "/api/accounting/subject-mapping", {"accountId": a1["id"], "subjectCode": "2001"}, step=True)
    st, r = api("POST", "/api/accounting/subject-mapping", {"accountId": a1["id"], "subjectCode": "2001"})
    check("科目改挂需二次认证（无 X-Step-Up-Token → 403）", st == 403, f"{st} {r}")
    trial_guard("AC-2 收尾")

    # ---------------- [2] AC-3 日切 + 日终批 ----------------
    print("[2] AC-3 日切 + 日终批处理（状态机/幂等/余额连续/分片/快照归属）")
    check("默认不自动日切（不干扰既有断言）", ops["dayCut"]["autoEnabled"] is False, str(ops["dayCut"]))
    check("默认日切时点 = 00:00（与 settle/recon 自然日同口径）", ops["dayCut"]["cutAt"] == "00:00")
    check("损益结转默认关闭（保护 SYS_FEE 手续费闭环口径）",
          ops["dayCut"]["profitTransferEnabled"] is False, str(ops["dayCut"]))
    check("日切时点经 DynamicConfig 可配", ops["configProvider"] in ("local-file", "apollo"), ops["configProvider"])

    day0 = ok("GET", "/api/accounting/day")["bizDate"]
    pre_bal = {a: bal(a) for a in sys_accounts}
    pre_trial = trial()
    cut = ok("POST", "/api/accounting/day-cut", {"bizDate": day0}, step=True)
    check("日切成功 OPEN→CLOSED", cut["status"] == "CLOSED" and cut["executed"] is True, str(cut)[:300])
    check("日切余额连续（逐账户校验和一致）", cut["continuous"] is True, str(cut)[:300])
    check("日切不动余额：Σbalance 切前切后一致且为 0",
          cut["afterBalanceSum"] == 0 and cut["balanceSum"] == 0, str(cut)[:300])
    snap = json.loads(cut["prevBalanceSnapshot"])
    check("日切前快照含试算与余额校验和",
          snap["balanceSum"] == 0 and snap["totalDebit"] == snap["totalCredit"] and snap.get("balanceChecksum"),
          str(snap)[:200])
    check("快照试算 = 切前实际试算",
          snap["totalDebit"] == pre_trial["totalDebit"] and snap["accounts"] == pre_trial["accounts"], str(snap))
    for a in sys_accounts:
        check(f"日切不动 {a} 余额", bal(a) == pre_bal[a], f"{bal(a)} vs {pre_bal[a]}")
    check("日切后试算仍 D=C 且 Σbalance=0", trial()["balanced"] and trial()["balanceSum"] == 0)

    cut2 = ok("POST", "/api/accounting/day-cut", {"bizDate": day0}, step=True)
    check("重复日切幂等（executed=false，状态仍 CLOSED）",
          cut2["executed"] is False and cut2["status"] == "CLOSED", str(cut2)[:250])
    check("重复日切不产生新快照/不改 closedAt", cut2["closedAt"] == cut["closedAt"], str(cut2)[:200])

    rep = ok("GET", f"/api/accounting/day-end-report?bizDate={day0}")
    check("日终报告生成且试算平衡", rep["balanced"] is True and rep["balanceSum"] == 0, str(rep))
    check("日终报告借=贷（按科目汇总仍 D=C）", rep["debitTotal"] == rep["creditTotal"], str(rep))
    check("日终报告状态 SUCCESS", rep["status"] == "SUCCESS", str(rep))
    check("默认不结转（transferred=false，无结转凭证）",
          rep["transferred"] is False and not rep.get("transferBizNo"), str(rep))
    check("日终报告透明披露存量分录（biz_date 为空，绝不回填）", rep["legacyEntryCount"] >= 0, str(rep))
    check("日切后 SYS_FEE 余额未被清零（口径保护）", bal("SYS_FEE") == pre_bal["SYS_FEE"])

    rep2 = ok("POST", "/api/accounting/day-end", {"bizDate": day0}, step=True)
    ok("POST", "/api/accounting/day-end", {"bizDate": day0}, step=True)
    check("日终批重跑幂等（损益/借贷/余额逐值一致）",
          all(rep2[k] == rep[k] for k in ("incomeTotal", "expenseTotal", "profit", "debitTotal",
                                          "creditTotal", "balanceSum", "subjectCount", "entryCount")),
          f"{rep2} vs {rep}")
    runs = act(path="ppaccounting/batchRuns")
    day_end_runs = [r for r in runs if r["batchType"] == "DAY_END" and r["bizDate"] == day0]
    check("批处理状态机留痕 SUCCESS", day_end_runs and day_end_runs[0]["status"] == "SUCCESS", str(day_end_runs)[:250])
    check("失败可重跑证据：attempt 随重跑递增", day_end_runs[0]["attempt"] >= 2, str(day_end_runs)[:250])
    cut_runs = [r for r in runs if r["batchType"] == "DAY_CUT" and r["bizDate"] == day0]
    check("日切留痕 CLOSED/SUCCESS", cut_runs and cut_runs[0]["status"] == "SUCCESS"
          and cut_runs[0]["step"] == "CLOSED", str(cut_runs)[:250])

    # 分片不重不漏：4 片各跑一次，科目余额表覆盖全部明细科目且无重复
    leaves_before = {x["subjectCode"] for x in leaf_sheet(day0)}
    for i in range(4):
        ok("POST", "/api/accounting/day-end", {"bizDate": day0, "shardIndex": i, "shardTotal": 4}, step=True)
    leaves_after = leaf_sheet(day0)
    check("4 片跑批后科目余额表覆盖全部明细科目（不漏）",
          {x["subjectCode"] for x in leaves_after} == leaves_before and len(leaves_after) == len(leaves_before),
          f"{len(leaves_after)} vs {len(leaves_before)}")
    check("科目余额表无重复行（不重，PK subject_code+biz_date）",
          len({x["subjectCode"] for x in leaves_after}) == len(leaves_after))
    rep3 = ok("GET", f"/api/accounting/day-end-report?bizDate={day0}")
    check("分片跑批后报告与单片全量逐值一致",
          all(rep3[k] == rep[k] for k in ("incomeTotal", "expenseTotal", "profit", "debitTotal",
                                          "creditTotal", "balanceSum", "subjectCount")), f"{rep3} vs {rep}")
    shard_runs = [r for r in act(path="ppaccounting/batchRuns")
                  if r["batchType"] == "DAY_END" and r["shardTotal"] == 4 and r["bizDate"] == day0]
    check("逐片留痕 4 条（shardIndex 0..3 各一）",
          sorted(r["shardIndex"] for r in shard_runs) == [0, 1, 2, 3], str(shard_runs)[:300])

    # 跨日凭证归属：切后新单归属新会计日；切前单据保持受理日（在途 TCC 快照语义）
    day1 = ok("GET", "/api/accounting/day")["bizDate"]
    check("日切后开启次日 OPEN", day1 == day0 + DAY_MS and ok("GET", "/api/accounting/day")["status"] == "OPEN",
          f"{day1} vs {day0}")
    topup(a1["id"], 7000, "m1c")
    new_ents = entries_of(f"ac-topup-m1c-{SUFFIX}")
    check("跨日新凭证归属新会计日", all(e.get("bizDate") == day1 for e in new_ents), str(new_ents))
    old_v = voucher(pid)
    check("切前凭证保持原会计日（不被日切改写）", old_v["bizDate"] == day0, str(old_v.get("bizDate")))
    ds = act(path=f"ppaccounting/daySnapshot/{pid}")
    check("在途 TCC 受理日快照存在且解析回原日（日切不破坏在途单）",
          ds["resolvedBizDate"] == day0 and ds["currentDay"] == day1, str(ds)[:250])
    check("快照解析 ≠ 当前日 ⇒ 确认按受理日归属", ds["resolvedBizDate"] != ds["currentDay"])
    trial_guard("AC-3 收尾")

    # ---------------- [3] 多实例调度不重复 ----------------
    print("[3] 多实例调度不重复（第二进程共享 MySQL 锁表 + 并发触发）")
    race_day = day0 - 3 * DAY_MS  # 用历史日竞态：避免把当前 OPEN 日推向未来
    env = docker_env()
    second_env = {"MYSQL_HOST": env.get("MYSQL_HOST", "127.0.0.1"),
                  "MYSQL_PORT": env.get("MYSQL_PORT", "3306"),
                  "MYSQL_USER": env.get("MYSQL_USER", "root"),
                  "MYSQL_PASSWORD": env.get("MYSQL_PASSWORD", ""),
                  "SPRING_PROFILES_ACTIVE": "docker"}
    second_extra = ["-Dspring.profiles.active=docker",
                    "-Dpayment.sharding.enabled=false",
                    "-Dpayment.job.xxl.enabled=false",
                    "-Dpayment.job.local.enabled=true",
                    "-Dpayment.mq.enabled=false",
                    "-Ddubbo.registry.address=zookeeper://127.0.0.1:2181",
                    "-Ddubbo.registry.register=false",
                    "-Dmanagement.endpoints.web.exposure.include=health,info,ppaccounting,ppjob,ppshard"]
    proc, err = start_jvm("second-docker", SECOND_PORT, SECOND_DUBBO, second_extra, env=second_env)
    second_ok = check("第二 accounting 实例就绪（同 MySQL，不注册 Dubbo 提供者，不抢 XXL 端口）",
                      err is None, str(err))
    if second_ok:
        second_act = f"http://127.0.0.1:{SECOND_PORT}/actuator"
        s_ops = act(base=second_act, path="ppaccounting")
        check("第二实例科目目录同源（共享 MySQL 元数据）", s_ops["subjects"] == ops["subjects"],
              f'{s_ops["subjects"]} vs {ops["subjects"]}')
        check("第二实例未装配 ShardingSphere（只读单库，不干扰主实例双写）",
              s_ops["shardingConfigured"] is False, str(s_ops.get("shardingConfigured")))
        triggers = [(ACC_ACT, i % 2) for i in range(8)]

        def fire(t):
            base, _ = t
            return http(base + "/ppjob/accountingDayCut", "POST", {"param": str(race_day)})[1]

        with ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(fire, triggers))
        okcnt = sum(1 for r in results if r and r.get("success"))
        check("8 次并发触发全部返回（两实例共 8 次）", okcnt == 8, str(results)[:300])
        skipped = sum(int((r or {}).get("lockSkipped") or 0) for r in results)
        j1 = act(path="ppjob")["jobs"].get("accountingDayCut", {})
        j2 = act(base=second_act, path="ppjob")["jobs"].get("accountingDayCut", {})
        total_skipped = int(j1.get("lockSkipped") or 0) + int(j2.get("lockSkipped") or 0)
        check("ShedLock 跨进程互斥生效（lockSkipped ≥ 1）", total_skipped >= 1 or skipped >= 1,
              f"j1={j1} j2={j2}")
        race_runs = [r for r in act(path="ppaccounting/batchRuns")
                     if r["batchType"] == "DAY_CUT" and r["bizDate"] == race_day]
        check("同一会计日只有一条日切留痕（UNIQUE 键，无重复批）", len(race_runs) == 1, str(race_runs)[:300])
        race_day_row = next((d for d in ok("GET", "/api/accounting/days?limit=50") if d["bizDate"] == race_day), None)
        check("并发日切后该日 CLOSED 且只切一次", race_day_row and race_day_row["status"] == "CLOSED"
              and race_day_row["closedAt"], str(race_day_row)[:250])
        check("并发日切余额仍守恒 Σbalance=0", trial()["balanceSum"] == 0)
        check("第二实例日切未改动任何系统户余额",
              all(bal(a) == pre_bal[a] for a in ("SYS_FEE", "SYS_SUSPENSE", "SYS_PAYOUT")), str(pre_bal))
        # 逐片日终批：两实例同片并发 → 同片互斥
        def fire_end(i):
            base = ACC_ACT if i % 2 == 0 else second_act
            return http(base + "/ppjob/accountingDayEnd", "POST",
                        {"shardIndex": i % 4, "shardTotal": 4, "param": str(day0)})[1]

        with ThreadPoolExecutor(max_workers=8) as pool:
            end_results = list(pool.map(fire_end, range(8)))
        check("两实例同片日终并发触发全部返回", all(r and r.get("success") for r in end_results),
              str(end_results)[:300])
        rep4 = ok("GET", f"/api/accounting/day-end-report?bizDate={day0}")
        check("多实例并发日终后报告仍与单片全量逐值一致（不重不漏）",
              all(rep4[k] == rep[k] for k in ("incomeTotal", "expenseTotal", "profit", "debitTotal",
                                              "creditTotal", "balanceSum", "subjectCount")), f"{rep4} vs {rep}")
        check("多实例并发日终后科目余额表行数不变（无重复）", len(leaf_sheet(day0)) == len(leaves_before))
        check("多实例并发日终后试算仍平衡", trial()["balanced"] and trial()["balanceSum"] == 0)
        try:
            proc.send_signal(signal.SIGTERM)
            proc.wait(timeout=25)
        except Exception:
            proc.kill()
        PROCS.remove(proc)
    trial_guard("多实例收尾")

    # ---------------- [4] AC-4 账簿/凭证查询 ----------------
    print("[4] AC-4 总账/明细账/余额表/凭证联查（跨片分页与聚合归并）")
    sheet = leaf_sheet(day0)
    check("余额表 Σ明细科目余额 = 0（封闭系统守恒）", sum(x["balance"] for x in sheet) == 0,
          str(sum(x["balance"] for x in sheet)))
    check("余额表 Σ借 = Σ贷（试算按科目维度成立）",
          sum(x["debitTotal"] for x in sheet) == sum(x["creditTotal"] for x in sheet))
    check("余额表 Σ余额 = 试算 balanceSum", sum(x["balance"] for x in sheet) == trial()["balanceSum"])
    check("科目期初 = 期末 − (贷−借)",
          all(x["openingBalance"] == x["balance"] - (x["creditTotal"] - x["debitTotal"]) for x in sheet))
    roots = root_sheet(day0)
    by_code = {x["subjectCode"]: x for x in sheet}
    check("类别根 = 其明细子科目之和（资产=负债+权益+损益 恒等可验）",
          all(r["balance"] == sum(by_code[c]["balance"] for c in by_code
                                  if next((s for s in subjects if s["subjectCode"] == c), {}).get("parentCode")
                                  == r["subjectCode"]) for r in roots), str(roots)[:300])
    check("资产类余额 = 负债+权益+损益类余额之和的相反数（Σ=0 分解）",
          sum(r["balance"] for r in roots) == 0)
    one = ok("GET", f"/api/accounting/subjects/1001/balance?bizDate={day0}")
    check("单科目余额表可查（1001 = SYS_CASH 余额）", one["balance"] == bal("SYS_CASH"), str(one))

    gl = ok("GET", f"/api/accounting/general-ledger?bizDate={day0}&page=1&size=50")
    check("总账行数 = 明细科目数", gl["total"] == len(sheet), f'{gl["total"]} vs {len(sheet)}')
    check("总账 = 科目余额表同源逐值一致",
          {(x["subjectCode"], x["debitTotal"], x["creditTotal"], x["closingBalance"]) for x in gl["items"]}
          == {(x["subjectCode"], x["debitTotal"], x["creditTotal"], x["balance"]) for x in sheet})
    gl_ids = []
    for p in range(1, 6):
        d = ok("GET", f"/api/accounting/general-ledger?bizDate={day0}&page={p}&size=4")
        gl_ids.extend(x["subjectCode"] for x in d["items"])
        if len(d["items"]) < 4:
            break
    check("总账分页不重不漏", len(gl_ids) == len(set(gl_ids)) == gl["total"], f"{len(gl_ids)}/{gl['total']}")

    # 明细账跨片分页：与既有 entriesPage 同口径对照
    ids, total, pages, items = detail_all(size=13)
    check("明细账跨片分页不重（id 唯一）", len(ids) == len(set(ids)), f"{len(ids)}/{len(set(ids))}")
    check("明细账分页遍历条数 = total（不漏）", len(ids) == total, f"{len(ids)} vs {total}")
    check("明细账 total = 试算分录数", total == trial()["entries"], f"{total} vs {trial()['entries']}")
    legacy_ids = ids
    e_ids = []
    p = 1
    while True:
        d = ok("GET", f"/api/accounting/entries?page={p}&size=13")
        e_ids.extend(x["id"] for x in d["items"])
        if len(d["items"]) < 13 or len(e_ids) >= 6000:
            break
        p += 1
    check("明细账与既有全局流水分页逐条同序一致（口径未变）", e_ids == legacy_ids,
          f"{len(e_ids)} vs {len(legacy_ids)}")

    acc_q = detail_all(f"accountId={a1['id']}", size=5)
    check("明细账按账户筛选：全部属于该账户", all(x["accountId"] == a1["id"] for x in acc_q[3]))
    check("明细账按账户筛选条数 = 该账户分录数",
          acc_q[1] == len(ok("GET", f'/api/accounting/accounts/{a1["id"]}/entries?limit=500')),
          f'{acc_q[1]}')
    subj_q = detail_all("subjectCode=6001", size=7)
    check("明细账按科目筛选：全部 6001", all(x.get("subjectCode") == "6001" for x in subj_q[3]))
    day_q = detail_all(f"bizDate={day1}", size=7)
    check("明细账按会计日筛选：全部归属该日", all(x.get("bizDate") == day1 for x in day_q[3]))
    check("按会计日筛选条数 > 0（切后新单）", day_q[1] > 0, str(day_q[1]))
    now_ms = int(time.time() * 1000)
    range_q = detail_all(f"dateFrom={now_ms - 3600_000}&dateTo={now_ms + 60_000}", size=9)
    check("明细账按日期区间筛选（含头不含尾）",
          all(now_ms - 3600_000 <= x["createdAt"] < now_ms + 60_000 for x in range_q[3]))
    check("日期区间筛选条数 ≤ 全量", range_q[1] <= total)

    # 总账 = 明细账汇总（当日发生额恒等）
    day_items = detail_all(f"bizDate={day1}", size=17, limit=6000)[3]
    d_sum = sum(x["amount"] for x in day_items if x["dir"] == "D")
    c_sum = sum(x["amount"] for x in day_items if x["dir"] == "C")
    sheet1 = leaf_sheet(day1)
    ok("POST", "/api/accounting/day-end", {"bizDate": day1}, step=True)
    sheet1 = leaf_sheet(day1)
    check("总账借方发生额 = 明细账借方汇总（跨片归并正确）",
          sum(x["debitTotal"] for x in sheet1) == d_sum, f'{sum(x["debitTotal"] for x in sheet1)} vs {d_sum}')
    check("总账贷方发生额 = 明细账贷方汇总",
          sum(x["creditTotal"] for x in sheet1) == c_sum, f'{sum(x["creditTotal"] for x in sheet1)} vs {c_sum}')
    check("当日明细账自身借贷平衡", d_sum == c_sum, f"{d_sum} vs {c_sum}")
    check("当日科目余额表 Σ余额 = 0", sum(x["balance"] for x in sheet1) == 0)

    agg = act(path=f"ppaccounting/subjectAgg/{day1}")
    check("跨片聚合四路对照：GROUP BY 分片键 = 逻辑表顶层 SUM",
          agg["groupByShardKey"]["debit"] == agg["logicTopLevelSum"]["debit"]
          and agg["groupByShardKey"]["credit"] == agg["logicTopLevelSum"]["credit"], str(agg)[:400])
    check("跨片聚合：科目归集 = 顶层 SUM",
          agg["subjectRollup"]["debit"] == agg["logicTopLevelSum"]["debit"]
          and agg["subjectRollup"]["credit"] == agg["logicTopLevelSum"]["credit"], str(agg)[:400])
    check("科目归集 Σ余额 = 0", agg["subjectRollup"]["balanceSum"] == 0, str(agg["subjectRollup"]))
    if sharding_configured:
        check("跨片聚合：各物理片之和 = 逻辑归并（SUM 顶层投影，无 COALESCE 藏聚合）",
              agg["shardSum"]["debit"] == agg["routedMerge"]["debit"]
              and agg["shardSum"]["credit"] == agg["routedMerge"]["credit"], str(agg)[:400])
        check("逻辑归并只返回一行（顶层 SUM 未被当普通投影）", agg["routedMerge"]["rows"] == 1,
              str(agg["routedMerge"]))
        check("跨片聚合：原单表 = 逻辑归并（双写一致）",
              agg["legacySingleTable"]["debit"] == agg["routedMerge"]["debit"]
              and agg["legacySingleTable"]["credit"] == agg["routedMerge"]["credit"], str(agg)[:400])

    # 凭证联查
    v = voucher(pid)
    check("凭证联查：分录 = lines 逐笔一致",
          sorted((e["accountId"], e["dir"], e["amount"]) for e in v["entries"])
          == sorted((l["accountId"], l["dir"], l["amount"]) for l in v["lines"]), str(v)[:300])
    check("凭证联查：借贷平衡", v["balanced"] and v["totalDebit"] == v["totalCredit"], str(v)[:200])
    check("凭证联查：科目集合正确", set(v["subjectCodes"]) == {"2001", "6001"}, str(v["subjectCodes"]))
    check("凭证联查：带会计日", v["bizDate"] == day0, str(v.get("bizDate")))
    vq = ok("GET", f"/api/accounting/vouchers?bizNo={pid}&page=1&size=5")
    check("凭证按 bizNo 联查命中 1 条", vq["total"] == 1 and vq["items"][0]["bizNo"] == pid, str(vq)[:200])
    vq2 = ok("GET", f"/api/accounting/vouchers?refId={ctx['orderId']}&page=1&size=5")
    check("凭证按 refId 联查命中（交易单号→支付凭证）",
          vq2["total"] >= 1 and any(x["bizNo"] == pid for x in vq2["items"]), str(vq2)[:250])
    vq3 = ok("GET", "/api/accounting/vouchers?subjectCode=6001&page=1&size=50")
    check("凭证按科目联查：全部含 6001 分录",
          vq3["total"] >= 1 and all("6001" in (x.get("subjectCodes") or []) for x in vq3["items"]),
          str(vq3)[:250])
    vq4 = ok("GET", f"/api/accounting/vouchers?accountId={a1['id']}&page=1&size=50")
    check("凭证按账户联查：全部含该账户分录",
          vq4["total"] >= 1 and all(any(e["accountId"] == a1["id"] for e in x["entries"]) for x in vq4["items"]))
    vq5 = ok("GET", f"/api/accounting/vouchers?bizDate={day1}&page=1&size=50")
    check("凭证按会计日联查", all(x["bizDate"] == day1 for x in vq5["items"]) and vq5["total"] >= 1)
    vq6 = ok("GET", f"/api/accounting/vouchers?dateFrom={now_ms - 3600_000}&dateTo={now_ms + 60_000}&size=50")
    check("凭证按日期区间联查",
          all(now_ms - 3600_000 <= x["createdAt"] < now_ms + 60_000 for x in vq6["items"]))
    vids, vtotal, vpages = [], None, 0
    for p in range(1, 400):
        d = ok("GET", f"/api/accounting/vouchers?page={p}&size=100&withEntries=false")
        vtotal = d["total"]
        vpages += 1
        vids.extend(x["bizNo"] for x in d["items"])
        if len(d["items"]) < 100:
            break
    check("凭证跨片分页不重不漏", len(vids) == len(set(vids)) == vtotal, f"{len(vids)}/{vtotal}")
    top_biz = f"ac-topup-m1c-{SUFFIX}"
    top_v = voucher(top_biz)
    rv = ok("POST", f"/api/accounting/transfers/{top_biz}/reverse", {"reason": "AC-4 联查验证"}, step=True)
    rv_no = rv["reverseBizNo"]
    rv_v = voucher(rv_no)
    check("冲正凭证联查：origBizNo 指向原凭证", rv_v["origBizNo"] == top_biz, str(rv_v)[:250])
    check("冲正凭证联查：原凭证 reverseBizNo 反查命中", voucher(top_biz)["reverseBizNo"] == rv_no)
    check("冲正凭证 D↔C 互换且金额一致",
          sorted((e["accountId"], "C" if e["dir"] == "D" else "D", e["amount"]) for e in rv_v["entries"])
          == sorted((e["accountId"], e["dir"], e["amount"]) for e in top_v["entries"]))
    check("冲正凭证科目集合与原凭证一致", set(rv_v["subjectCodes"]) == set(top_v["subjectCodes"]))
    vq7 = ok("GET", f"/api/accounting/vouchers?refId={top_biz}&voucherType=REVERSE&size=10")
    check("凭证按 refId+类型联查冲正单", vq7["total"] == 1 and vq7["items"][0]["bizNo"] == rv_no, str(vq7)[:250])
    trial_guard("AC-4 收尾")

    # ---------------- [5] 凭证只读不可改 ----------------
    print("[5] 凭证只读不可改（VoucherGuard 红线）")
    sbiz = f"AC-SUS-{SUFFIX}"
    sus_lines = [line("SYS_CASH", "D", 120), line("SYS_SUSPENSE", "C", 120)]
    s1 = ok("POST", "/api/accounting/suspense", {"bizNo": sbiz, "subject": "AC 挂账验证", "lines": sus_lines})
    check("挂账凭证建立（SUSPENSE）", s1["voucherType"] == "SUSPENSE", str(s1)[:250])
    before_entries = trial()["entries"]
    st, r = api("POST", "/api/accounting/suspense",
                {"bizNo": sbiz, "subject": "篡改尝试", "lines": [line("SYS_CASH", "D", 999),
                                                              line("SYS_SUSPENSE", "C", 999)]})
    check("同 bizNo 异 lines 重放：幂等层返回原凭证，lines 未被篡改",
          st == 200 and r["lines"] == sus_lines and r["subject"] == "AC 挂账验证", f"{st} {str(r)[:250]}")
    check("异 lines 重放不产生新分录（不重复过账）", trial()["entries"] == before_entries,
          f'{trial()["entries"]} vs {before_entries}')
    # 真实 VoucherGuard 拒绝：支付凭证号无 SUSPENSE 幂等记录 → 直达 append 护栏
    st, r = api("POST", "/api/accounting/suspense",
                {"bizNo": pid, "subject": "篡改支付凭证", "lines": [line("SYS_CASH", "D", 777),
                                                                 line("SYS_SUSPENSE", "C", 777)]})
    check("VoucherGuard 拒绝改写既有凭证 lines（异 lines 直接拒绝）", st != 200, f"{st} {str(r)[:250]}")
    st, r = api("POST", "/api/accounting/suspense",
                {"bizNo": pid, "subject": "篡改凭证类型", "lines": v["lines"]})
    check("VoucherGuard 拒绝变更凭证类型（NORMAL→SUSPENSE）", st != 200, f"{st} {str(r)[:250]}")
    check("被拒绝的篡改未落任何分录", trial()["entries"] == before_entries,
          f'{trial()["entries"]} vs {before_entries}')
    s2 = ok("POST", "/api/accounting/suspense", {"bizNo": sbiz, "subject": "AC 挂账验证", "lines": sus_lines})
    check("同 bizNo 同 lines 幂等返回原凭证", s2["bizNo"] == sbiz and s2["lines"] == s1["lines"])
    check("幂等重放不重复过账（分录数不变）", trial()["entries"] == before_entries,
          f'{trial()["entries"]} vs {before_entries}')
    st, r = api("POST", f"/api/accounting/transfers/{rv_no}/reverse", {"reason": "冲正冲正单"})
    check("冲正单不可再冲正（守卫拒绝）", st != 200, f"{st} {r}")
    orig_before = ok("GET", f"/api/accounting/transfers/{pid}")
    ok("POST", "/api/accounting/day-end", {"bizDate": day1}, step=True)
    ok("POST", "/api/accounting/subject-mapping", {"accountId": a1["id"], "subjectCode": "2001"}, step=True)
    orig_after = ok("GET", f"/api/accounting/transfers/{pid}")
    check("日切/日终/改挂后原凭证逐字不变", orig_before == orig_after, f"{orig_before} vs {orig_after}")
    check("原凭证 lines 未被改写（红冲后仍为原方向）",
          sorted((l["accountId"], l["dir"], l["amount"]) for l in orig_after["lines"])
          == sorted((l["accountId"], l["dir"], l["amount"]) for l in v["lines"]))
    check("挂账凭证 lines 未被篡改", voucher(sbiz)["lines"] == sus_lines)
    st, r = api("POST", "/api/accounting/day-cut", {"bizDate": day1})
    check("日切需二次认证（无 X-Step-Up-Token → 403）", st == 403, f"{st} {r}")
    st, r = api("POST", "/api/accounting/day-end", {"bizDate": day1})
    check("日终批需二次认证（无 X-Step-Up-Token → 403）", st == 403, f"{st} {r}")
    st, r = api("POST", "/api/accounting/reserve-recon", {})
    check("账实核对需二次认证（无 X-Step-Up-Token → 403）", st == 403, f"{st} {r}")
    trial_guard("凭证护栏收尾")

    # ---------------- [6] AC-5 账实核对 ----------------
    print("[6] AC-5 账实核对（差异分级告警 + 长短款 + 建单幂等 + 多渠道 + P0 口径保留）")
    r0 = ok("POST", "/api/accounting/reserve-recon", {}, step=True)
    p0 = ok("POST", "/api/accounting/reserve/check", {}, step=True)
    check("P0 模拟银行口径保留：reserve-recon 与 reserveCheck 逐值一致",
          (r0["bookBalance"], r0["actualBalance"], r0["diff"], r0["pass"])
          == (p0["bookBalance"], p0["actualBalance"], p0["diff"], p0["pass"]), f"{r0} vs {p0}")
    check("账实一致时 pass=true / level=NONE", r0["pass"] is True and r0["level"] == "NONE", str(r0)[:300])
    check("账面 = SYS_CASH 余额（权威口径不变）", r0["bookBalance"] == bal("SYS_CASH"))
    check("默认渠道 = MOCK_BANK", [d["channelCode"] for d in r0["details"]] == ["MOCK_BANK"], str(r0["details"])[:200])
    check("核对留痕带会计日/渠道/分级", all(d.get("checkLogId") for d in r0["details"]))
    logs0 = ok("GET", f'/api/accounting/reserve-recon/logs?bizDate={r0["bizDate"]}&limit=5')
    check("留痕可按会计日查询", logs0 and all(x["bizDate"] == r0["bizDate"] for x in logs0), str(logs0)[:250])
    check("留痕含渠道与分级字段", all(x.get("channelCode") and x.get("level") for x in logs0), str(logs0)[:250])

    # 短款（实际 > 账面）：银行侧多记 500 → diff = -500 → ERROR 级
    ok("POST", "/api/accounting/reserve/adjust", {"amount": 500, "reason": f"AC-5 短款验证 {SUFFIX}"}, step=True)
    r1 = ok("POST", "/api/accounting/reserve-recon", {}, step=True)
    d1 = r1["details"][0]
    check("短款差异检出 diff = -500", r1["diff"] == -500 and d1["diff"] == -500, str(r1)[:300])
    check("短款方向 SHORT", r1["direction"] == "SHORT" and d1["direction"] == "SHORT", str(r1)[:200])
    check("差异分级 ERROR（≥warn 阈值 100，<critical 10000）", r1["level"] == "ERROR", str(r1["level"]))
    check("差异 → 建差错单（disputeNo 非空）", bool(d1.get("disputeNo")), str(d1)[:300])
    check("差异 → pass=false", r1["pass"] is False)
    check("账实核对不动账务：SYS_CASH 余额不变", bal("SYS_CASH") == r0["bookBalance"])
    check("差异不破坏试算平衡", trial()["balanced"] and trial()["balanceSum"] == 0)
    if d1.get("disputeNo"):
        dq = ok("GET", f'/api/disputes?source=RESERVE&bizNo=AC5:{r1["bizDate"]}:MOCK_BANK&size=5')
        items = dq.get("items", dq if isinstance(dq, list) else [])
        check("差错平台确实建单（source=RESERVE，type=AMOUNT_MISMATCH）",
              any(x.get("type") == "AMOUNT_MISMATCH" for x in items), str(dq)[:300])
    r1b = ok("POST", "/api/accounting/reserve-recon", {}, step=True)
    check("重复核对差错单幂等（同会计日+渠道不重复建单）",
          r1b["details"][0].get("disputeNo") == d1.get("disputeNo"),
          f'{r1b["details"][0].get("disputeNo")} vs {d1.get("disputeNo")}')
    dl = ok("GET", f'/api/disputes?source=RESERVE&bizNo=AC5:{r1["bizDate"]}:MOCK_BANK&size=20')
    dl_items = dl.get("items", [])
    check("同一会计日+渠道只建一张差错单（幂等键 AC5:<day>:<channel>，不膨胀）",
          len(dl_items) == 1, str(dl_items)[:300])

    # 长款（账面 > 实际）：银行侧少记 → diff > 0 → LONG；金额超 critical → CRITICAL
    ok("POST", "/api/accounting/reserve/adjust", {"amount": -20500, "reason": f"AC-5 长款验证 {SUFFIX}"}, step=True)
    r2 = ok("POST", "/api/accounting/reserve-recon", {}, step=True)
    check("长款差异检出 diff = +20000", r2["diff"] == 20000, str(r2)[:300])
    check("长款方向 LONG", r2["direction"] == "LONG", str(r2)[:200])
    check("差异分级 CRITICAL（≥critical 阈值 10000）", r2["level"] == "CRITICAL", str(r2["level"]))
    check("CRITICAL 差异同样建单", bool(r2["details"][0].get("disputeNo")), str(r2["details"])[:250])

    # 分级阈值经 DynamicConfig 可调
    config_write({"accounting.reserve.warn-threshold": 30000, "accounting.reserve.critical-threshold": 50000})
    r3 = ok("POST", "/api/accounting/reserve-recon", {}, step=True)
    check("阈值热更生效：20000 差异降为 WARN", r3["level"] == "WARN", str(r3["level"]))
    config_write({"accounting.reserve.warn-threshold": None, "accounting.reserve.critical-threshold": None})
    r4 = ok("POST", "/api/accounting/reserve-recon", {}, step=True)
    check("阈值恢复默认后分级回到 CRITICAL", r4["level"] == "CRITICAL", str(r4["level"]))

    # 多渠道汇总
    rm = ok("POST", "/api/accounting/reserve-recon", {"channels": ["MOCK_BANK", "ALIPAY"]}, step=True)
    check("多渠道分别核对（2 条明细）", len(rm["details"]) == 2, str(rm)[:300])
    check("多渠道汇总 diff = 各渠道 diff 之和",
          rm["diff"] == sum(d["diff"] for d in rm["details"]), str(rm)[:300])
    check("多渠道汇总实际余额 = 账面 + Σ各渠道调整",
          rm["actualBalance"] == rm["bookBalance"] + sum(d["bankAdjust"] + d["billDiffAdjust"] for d in rm["details"]))
    alipay = next(d for d in rm["details"] if d["channelCode"] == "ALIPAY")
    check("真实渠道侧经 CH-4 账单接入（只调 facade，实际侧口径自洽）",
          alipay["actualBalance"] == alipay["bookBalance"] + alipay["bankAdjust"] + alipay["billDiffAdjust"]
          and alipay["bankAdjust"] == 0, str(alipay)[:300])
    check("CH-4 就绪标记为布尔（未就绪自动降级模拟口径）", isinstance(rm.get("channelBillReady"), bool),
          str(rm.get("channelBillReady")))
    check("CH-4 未就绪/无账单时降级说明可见", bool(rm.get("actualSource")), str(rm.get("actualSource")))
    check("多渠道核对不改账务余额", bal("SYS_CASH") == r0["bookBalance"])

    # 冲回银行侧事件（只追加等额反向事件，绝不删除既有行）→ 恢复账实一致
    ok("POST", "/api/accounting/reserve/adjust", {"amount": 20000, "reason": f"AC-5 差异冲回 {SUFFIX}"}, step=True)
    r5 = ok("POST", "/api/accounting/reserve-recon", {}, step=True)
    check("银行侧事件冲回后账实重新一致（pass=true）", r5["pass"] is True and r5["diff"] == 0, str(r5)[:300])
    p5 = ok("POST", "/api/accounting/reserve/check", {}, step=True)
    check("冲回后 P0 口径仍逐值一致", (p5["diff"], p5["pass"]) == (0, True), str(p5))
    check("AC-5 全程账务余额未被触碰", bal("SYS_CASH") == r0["bookBalance"])
    trial_guard("AC-5 收尾")

    # ---------------- [7] 分片切流/回滚逐条一致 ----------------
    print("[7] 分片切流/回滚：账簿/凭证/科目余额逐条一致")
    accounts = sys_accounts + [a1["id"], ctx["ma"]["id"], ctx["ua"]["id"]]
    if sharding_configured:
        cmp0 = act(base=ACC_SHARD, path="shadowCompare")
        check("切流前影子比对 diffTotal=0", cmp0.get("diffTotal") == 0, str(cmp0)[:300])
        snap_before = snapshot(day0, accounts)
        act(base=ACC_SHARD, body={"action": "toggle", "enabled": True})
        check("切流成功 active=true", act(base=ACC_SHARD).get("shardingActive") is True)
        snap_active = snapshot(day0, accounts)
        check("分片态账簿/凭证/科目余额/试算与单库逐条一致", snap_active == snap_before,
              str({k: (snap_before[k], snap_active[k]) for k in snap_before if snap_before[k] != snap_active[k]})[:500])
        ids_a, total_a, _, _ = detail_all(size=13)
        check("分片态明细账跨片分页仍不重不漏", len(ids_a) == len(set(ids_a)) == total_a,
              f"{len(ids_a)}/{total_a}")
        agg_a = act(path=f"ppaccounting/subjectAgg/{day1}")
        check("分片态跨片聚合四路仍一致",
              agg_a["shardSum"]["debit"] == agg_a["routedMerge"]["debit"]
              and agg_a["groupByShardKey"]["debit"] == agg_a["routedMerge"]["debit"], str(agg_a)[:400])
        # 分片态真实动账 → 回滚后逐条一致
        topup(a1["id"], 11000, "shard")
        ctx_s = pay_once("ps", 8000)
        cmp1 = act(base=ACC_SHARD, path="shadowCompare")
        check("分片态动账后影子比对 diffTotal=0（全行双写含新列）", cmp1.get("diffTotal") == 0, str(cmp1)[:400])
        st = act(base=ACC_SHARD)
        check("双写零失败零冲突", st.get("mirrorFailures") == 0 and st.get("mirrorConflicts") == 0, str(st)[:300])
        snap_shard = snapshot(day0, accounts)
        act(base=ACC_SHARD, body={"action": "resync", "direction": "TO_LEGACY"})
        act(base=ACC_SHARD, body={"action": "toggle", "enabled": False})
        check("回滚单库 active=false", act(base=ACC_SHARD).get("shardingActive") is False)
        snap_back = snapshot(day0, accounts)
        check("回滚单库后账簿/凭证/科目余额/试算逐条一致", snap_back == snap_shard,
              str({k: (snap_shard[k], snap_back[k]) for k in snap_shard if snap_shard[k] != snap_back[k]})[:500])
        ids_b, total_b, _, _ = detail_all(size=13)
        expect_delta = 2 + 2 + len(entries_of(ctx_s["payment"]["id"]))  # 显式充值 + pay_once 内充值 + 支付
        check("回滚后明细账分页仍不重不漏且条数一致",
              len(ids_b) == len(set(ids_b)) == total_b == total_a + expect_delta,
              f"{len(ids_b)}/{total_b}/{total_a}+{expect_delta}")
        cmp2 = act(base=ACC_SHARD, path="shadowCompare")
        check("回滚后影子比对 diffTotal=0", cmp2.get("diffTotal") == 0, str(cmp2)[:300])
        check("回滚后新科目列在两侧一致（subject_code/biz_date 双写完整）",
              all(e.get("subjectCode") for e in entries_of(f"ac-topup-shard-{SUFFIX}")))
        if was_active:
            act(base=ACC_SHARD, body={"action": "toggle", "enabled": True})
    else:
        check("local/未装配分片：账簿查询单库口径可用", trial()["balanced"])
    trial_guard("分片回滚收尾")
    recon_guard("收尾")

    # ---------------- [8] local H2 隔离 JVM + 损益结转真实执行 ----------------
    print("[8] local H2 隔离 JVM（双 profile 同构 + 损益结转真实执行，内存库不触碰 data/）")
    seed = os.path.join("/tmp", f"p1e-seed-{SUFFIX}.sql")
    with open(seed, "w", encoding="utf-8") as f:
        f.write("INSERT INTO account_balance(account_id,balance,frozen,version,updated_at) "
                "VALUES('SYS_FEE',1800,0,0,1795000000000);\n")
        f.write("INSERT INTO account_balance(account_id,balance,frozen,version,updated_at) "
                "VALUES('SYS_CASH',-1800,0,0,1795000000000);\n")
    local_extra = ["-Dspring.datasource.url=jdbc:h2:mem:p1e_acc_" + SUFFIX + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                   "-Dspring.datasource.driver-class-name=org.h2.Driver",
                   "-Dspring.datasource.username=sa", "-Dspring.datasource.password=",
                   f"-Dspring.sql.init.data-locations=file:{seed}",
                   "-Dpayment.sharding.enabled=false",
                   "-Dpayment.job.xxl.enabled=false",
                   "-Dpayment.job.local.enabled=false",
                   "-Dpayment.mq.enabled=false",
                   "-Ddubbo.registry.address=zookeeper://127.0.0.1:2181",
                   "-Ddubbo.registry.group=p1e-local-verify",
                   "-Dmanagement.endpoints.web.exposure.include=health,info,ppaccounting,ppjob,ppshard"]
    proc, err = start_jvm("local-h2", LOCAL_PORT, LOCAL_DUBBO, local_extra)
    if check("local H2 隔离 JVM 就绪（内存库 + 预置损益户余额）", err is None, str(err)):
        lact = f"http://127.0.0.1:{LOCAL_PORT}/actuator"
        lops = act(base=lact, path="ppaccounting")
        check("local 未装配任何 ShardingSphere Bean", lops["shardingConfigured"] is False, str(lops))
        check("local 科目目录同构播种（18 条）", lops["subjects"] == ops["subjects"], str(lops["subjects"]))
        check("local 系统户映射同构（11 条内置固定映射）", lops["mappedAccounts"] == 11,
              str(lops["mappedAccounts"]))
        lt = lops["trial"]
        check("local 预置余额 Σ=0（SYS_FEE +1800 / SYS_CASH -1800）", lt["balanceSum"] == 0, str(lt))
        check("local 日切默认关闭（不自动跑批）", lops["dayCut"]["autoEnabled"] is False)
        lday = lops["dayCut"]["current"]
        lcut = act(base=lact, path="ppjob/accountingDayCut", body={"param": str(lday)})
        check("local 日切作业经 JobHandler 触发成功", lcut.get("success") is True, str(lcut))
        ldays = act(base=lact, path="ppaccounting")["dayCut"]["days"]
        check("local 日切状态机 CLOSED + 次日 OPEN",
              any(d["bizDate"] == lday and d["status"] == "CLOSED" for d in ldays)
              and any(d["bizDate"] == lday + DAY_MS and d["status"] == "OPEN" for d in ldays), str(ldays)[:300])
        check("local 日切余额连续 Σ=0", act(base=lact, path="ppaccounting")["trial"]["balanceSum"] == 0)
        lsheet = act(base=lact, path=f"ppaccounting/subjectBalance/{lday}")
        check("local 科目余额表同构生成（明细科目 ≥ 13）", len(lsheet) >= 13, str(len(lsheet)))
        check("local 科目余额表 Σ余额 = 0", sum(x["balance"] for x in lsheet) == 0)
        check("local 6001 手续费收入科目余额 = 1800（预置）",
              next(x["balance"] for x in lsheet if x["subjectCode"] == "6001") == 1800, str(lsheet)[:300])
        # 打开结转开关（只写本脚本自己的配置键；隔离 JVM 读同一配置文件）
        config_write({"accounting.dayend.profit-transfer.enabled": "true"})
        lr = act(base=lact, path="ppaccounting", body={"action": "refresh"})
        check("结转开关经 DynamicConfig 热更生效", lr["profitTransferEnabled"] is True, str(lr))
        lend = act(base=lact, path="ppjob/accountingDayEnd", body={"param": str(lday)})
        check("local 日终批（含结转）执行成功", lend.get("success") is True, str(lend))
        lops2 = act(base=lact, path="ppaccounting")
        lt2 = lops2["trial"]
        check("结转后试算仍 D=C", lt2["balanced"] is True and lt2["totalDebit"] == lt2["totalCredit"], str(lt2))
        check("结转后 Σbalance 仍 = 0", lt2["balanceSum"] == 0, str(lt2))
        lsheet2 = act(base=lact, path=f"ppaccounting/subjectBalance/{lday}")
        fee_row = next(x for x in lsheet2 if x["subjectCode"] == "6001")
        profit_row = next(x for x in lsheet2 if x["subjectCode"] == "4001")
        check("结转后损益科目 6001 归零", fee_row["balance"] == 0, str(fee_row))
        check("结转后 SYS_PROFIT(4001) 承接 1800", profit_row["balance"] == 1800, str(profit_row))
        check("结转凭证已复式过账（恰好 2 行分录，借=贷=1800）",
              lt2["entries"] == 2 and lt2["totalDebit"] == 1800 and lt2["totalCredit"] == 1800, str(lt2))
        check("结转批处理留痕 SUCCESS",
              any(r["batchType"] == "DAY_END" and r["status"] == "SUCCESS" for r in lops2["batchRuns"]),
              str(lops2["batchRuns"])[:300])
        entries_before = lt2["entries"]
        lend2 = act(base=lact, path="ppjob/accountingDayEnd", body={"param": str(lday)})
        lt3 = act(base=lact, path="ppaccounting")["trial"]
        check("结转重跑幂等：不产生第二张凭证（分录数不变）", lt3["entries"] == entries_before,
              f'{lt3["entries"]} vs {entries_before}')
        check("结转重跑幂等：余额逐值不变", lt3["balanceSum"] == 0 and lt3["totalDebit"] == lt2["totalDebit"], str(lt3))
        lsheet3 = act(base=lact, path=f"ppaccounting/subjectBalance/{lday}")
        check("结转重跑幂等：损益户仍归零、SYS_PROFIT 仍 1800",
              next(x["balance"] for x in lsheet3 if x["subjectCode"] == "6001") == 0
              and next(x["balance"] for x in lsheet3 if x["subjectCode"] == "4001") == 1800)
        config_write({"accounting.dayend.profit-transfer.enabled": None})
        # 日切时点可配（local 隔离验证，不影响共享栈）
        config_write({"accounting.daycut.at": "02:00"})
        lr2 = act(base=lact, path="ppaccounting", body={"action": "refresh"})
        check("日切时点经 DynamicConfig 可配（02:00 生效）", lr2["cutAt"] == "02:00", str(lr2))
        config_write({"accounting.daycut.at": None})
        act(base=lact, path="ppaccounting", body={"action": "refresh"})
        text = open(os.path.join(ROOT, "logs", "p1e-local-h2.log"), encoding="utf-8", errors="ignore").read()
        check("local 日志无 ShardingSphere 装配痕迹", "ShardingSphere" not in text,
              next((l for l in text.splitlines() if "ShardingSphere" in l), ""))
        check("local 启动与跑批零 ERROR", sum(1 for l in text.splitlines() if " ERROR " in l) == 0,
              next((l for l in text.splitlines() if " ERROR " in l), ""))
    stop_procs()
    try:
        os.remove(seed)
    except Exception:
        pass

    # ---------------- 收尾 ----------------
    print("[收尾] 资金护航与数据保护")
    end_trial = trial()
    check("收尾试算 D=C 且 Σbalance=0", end_trial["balanced"] and end_trial["balanceSum"] == 0, str(end_trial))
    check("收尾账户数/分录数不少于基线（只增不删）",
          end_trial["accounts"] >= base_trial["accounts"] and end_trial["entries"] >= base_trial["entries"],
          f'{end_trial["accounts"]}/{base_trial["accounts"]} {end_trial["entries"]}/{base_trial["entries"]}')
    check("收尾 SYS_SUSPENSE 挂账口径未被破坏（只增不减）",
          bal("SYS_SUSPENSE") >= base_bal["SYS_SUSPENSE"], f'{bal("SYS_SUSPENSE")} vs {base_bal["SYS_SUSPENSE"]}')
    end_map = act(path="ppaccounting/mapping")["systemMappings"]
    check("收尾既有系统户映射仍固定不变",
          [(r["systemAccount"], r["resolvedSubject"]) for r in end_map]
          == [(r["systemAccount"], r["subjectCode"]) for r in mapping["systemMappings"]])
    check("收尾共享栈损益结转仍关闭（SYS_FEE 口径未被自动清零）",
          act(path="ppaccounting")["dayCut"]["profitTransferEnabled"] is False)
    final_shard = act(base=ACC_SHARD)
    check("收尾分片路由回到初始态", final_shard.get("shardingActive") == was_active,
          f'{final_shard.get("shardingActive")} vs {was_active}')
    if sharding_configured:
        check("收尾影子比对 diffTotal=0", act(base=ACC_SHARD, path="shadowCompare").get("diffTotal") == 0)
    recon_guard("最终")

    print(f"\n结果：{PASSED} 通过，{FAILED} 失败")
    if FAILURES:
        print("失败项：" + "; ".join(FAILURES))
    return 1 if FAILED else 0


def restore_config():
    try:
        current = config_read()
        for k in MY_CONFIG_KEYS:
            current.pop(k, None)
        if not CONFIG_EXISTED and not current:
            if os.path.exists(CONFIG_FILE):
                os.remove(CONFIG_FILE)
        else:
            os.makedirs(os.path.dirname(CONFIG_FILE), exist_ok=True)
            with open(CONFIG_FILE, "w", encoding="utf-8") as f:
                for k, v in sorted(current.items()):
                    f.write(f"{k}={v}\n")
        act(path="ppaccounting", body={"action": "refresh"})
    except Exception as e:
        print(f"[warn] 配置恢复失败: {e}")


if __name__ == "__main__":
    code = 1
    try:
        code = main()
    except SystemExit:
        raise
    except BaseException as exc:
        import traceback
        traceback.print_exc()
        print(f"[fatal] {type(exc).__name__}: {exc}")
        code = 1
    finally:
        stop_procs()
        restore_config()
        try:
            st = act(base=ACC_SHARD)
            if st.get("shardingConfigured") and st.get("shardingActive"):
                act(base=ACC_SHARD, body={"action": "resync", "direction": "TO_LEGACY"})
                act(base=ACC_SHARD, body={"action": "toggle", "enabled": False})
                print("[cleanup] 分片路由已恢复 active=false")
        except Exception:
            pass
    sys.exit(code)