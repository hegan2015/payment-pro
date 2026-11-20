#!/usr/bin/env python3
# ============================================================================
# payment-pro P1 第二期 · D 泳道验证：清结算工作流 ST-1~ST-5
#
# 用法：docker 全栈在线（./run.sh --docker）后 python3 verify-settle.py
#   [1] 缺省口径基线：feeBps=60（现值）/ T+1 / 提现费率 0 —— 证明口径未变
#   [2] ST-1 T+N 日切与批次状态机：未到期不出批 → 改周期出批 → 净额恒等 → 重复跑批幂等
#   [3] 分片调度：4 片按 merchant 取模不重不漏；并发触发（多实例语义）ShedLock 互斥不重结
#   [4] ST-2 费率：分层/阶梯/分成、DynamicConfig 热更生效、在途单与历史结算用受理快照不变
#   [5] ST-3 分账：Σ分账 = 结算净额（误差 0）、复式记账双腿、幂等、超额拒绝、回退幂等
#   [6] ST-4 提现：受理→到账 / 失败回滚解冻 / 在途补单收敛 / 回执幂等 / requestId 幂等不重扣 / 二次认证
#   [7] ST-5 账单：日/月账单金额 = Σ流水、CSV 明细逐笔一致、重复生成幂等、跨片归并四路一致
#   [8] 分片回滚：一键切回单库后 结算单/批次/账单/汇总 逐条一致，再切回分片仍一致
#   [9] 资金护航：试算平衡 D=C 且 Σbalance=0 + 六项对账 pass
#
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
SETTLE_ACT = "http://127.0.0.1:18089/actuator"
CONFIG_FILE = os.path.expanduser("~/.config/payment-pro/config.properties")
SUFFIX = uuid.uuid4().hex[:6]
DAY_MS = 86_400_000
TOKEN = None
STEP = None
PASSED = 0
FAILED = 0
FAILURES = []
CONFIG_ORIGINAL = None
CONFIG_EXISTED = False


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
    # 结算周期配置与分账动账/回退已纳入二次认证闸门：自动附加 token，不放宽任何断言
    if not step and method.upper() == "POST":
        p = path.split("?")[0]
        if p.startswith("/api/settle/cycles") or p.startswith("/api/settle/shares"):
            step = True
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
    st, resp = http(SETTLE_ACT + ("/" + path if path else ""), "POST" if body is not None else "GET", body)
    assert st == 200, f"{SETTLE_ACT}/{path} 期望 200，实际 {st}: {resp}"
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


def today_start():
    """Asia/Shanghai 当日 00:00 epoch millis（与 settle 域 SettleSupport 同口径）。"""
    os.environ["TZ"] = "Asia/Shanghai"
    time.tzset()
    lt = time.localtime()
    return int(time.mktime((lt.tm_year, lt.tm_mon, lt.tm_mday, 0, 0, 0, 0, 0, -1))) * 1000


def new_party(tag, kind="MERCHANT"):
    m = ok("POST", "/api/members", {"name": f"ST{kind[0]}{tag}{SUFFIX}", "type": kind})
    a = ok("POST", "/api/accounts", {"memberId": m["id"]})
    return m, a


def pay_once(tag, amount=10000):
    merchant, ma = new_party(tag)
    user, ua = new_party(tag, "USER")
    ok("POST", "/api/accounting/internal/topup",
       {"accountId": ua["id"], "amount": amount * 3, "bizNo": f"st-topup-{tag}-{SUFFIX}"})
    order = ok("POST", "/api/orders",
               {"merchantId": merchant["id"], "subject": "ST 清结算验证", "amount": amount})["order"]
    r = ok("POST", "/api/cashier/pay", {"orderId": order["id"], "method": "BALANCE",
                                       "accountId": ua["id"], "requestId": f"st-req-{tag}-{SUFFIX}"})
    pid = r["payment"]["id"]
    p = poll(lambda: ok("GET", f"/api/payments/{pid}"), lambda x: x["status"] == "SUCCESS")
    assert p["status"] == "SUCCESS", f"支付未成功: {p}"
    sr = poll(lambda: next((x for x in ok("GET", f"/api/settle/records?merchantId={merchant['id']}")
                            if x.get("paymentId") == pid and x["type"] == "PAY"), None),
              lambda x: x is not None)
    assert sr is not None, f"结算单未生成: {pid}"
    return {"merchant": merchant, "ma": ma, "user": user, "ua": ua, "payment": p, "record": sr}


def batches_of(merchant_id):
    return ok("GET", f"/api/settle/batches?merchantId={merchant_id}")


def batch_key(b):
    return (b["merchantId"], b["bizDate"])


def snapshot(merchant_id):
    """回滚一致性对照快照：结算单 / 批次 / 账单 / 汇总（逐条可比）。"""
    recs = ok("GET", f"/api/settle/records?merchantId={merchant_id}")
    bs = ok("GET", f"/api/settle/batches?merchantId={merchant_id}")
    bills = ok("GET", f"/api/settle/bills?merchantId={merchant_id}")
    summary = ok("GET", "/api/settle/summary")
    return {
        "records": sorted([(r["id"], r["type"], r["bizId"], r["gross"], r["fee"], r["net"],
                            r["status"], r["createdAt"]) for r in recs]),
        "batches": sorted([(b["id"], b["bizDate"], b["gross"], b["fee"], b["net"], b["refund"],
                            b["recordCount"], b["status"]) for b in bs]),
        "bills": sorted([(b["id"], b["bizDate"], b["billType"], b["gross"], b["fee"], b["net"],
                          b["refund"], b["share"], b["recordCount"]) for b in bills]),
        "summary": {k: summary[k] for k in ("payCount", "totalGross", "totalFee", "totalNet", "totalRefund")},
    }


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
        for k, v in sorted(current.items()):
            f.write(f"{k}={v}\n")
    act("ppsettle", {"action": "refresh"})


def trial_guard(stage):
    t = ok("GET", "/api/accounting/trial")
    return check(f"[{stage}] 试算平衡 D=C 且 Σbalance=0",
                 bool(t.get("balanced")) and t.get("balanceSum") == 0, str(t))


def main():
    global CONFIG_ORIGINAL, CONFIG_EXISTED
    print(f"== payment-pro 清结算工作流验证 ST-1~ST-5（批次 {SUFFIX}）==")
    login()
    CONFIG_EXISTED = os.path.exists(CONFIG_FILE)
    CONFIG_ORIGINAL = config_read()
    day = today_start()

    # ---------------- [1] 缺省口径基线 ----------------
    print("[1] 缺省口径基线（证明手续费/周期口径未变）")
    ops = act("ppsettle")
    check("缺省费率 = 现值 60bps", ops["defaults"]["feeBps"] == 60, str(ops["defaults"]))
    check("代码默认费率常量 = 60", ops["defaults"]["feeBpsCodeDefault"] == 60, str(ops["defaults"]))
    check("缺省结算周期 = T+1", ops["defaults"]["cycleDays"] == 1, str(ops["defaults"]))
    check("提现缺省费率 = 0（资金单 fee=0 口径）", ops["defaults"]["withdrawFeeBps"] == 0, str(ops["defaults"]))
    check("三个 JobHandler 已注册", set(act("ppjob")["handlers"]) >= {"settleBatch", "settleBill", "withdrawReconcile"},
          str(act("ppjob")["handlers"]))
    check("ShedLock 已装配（多实例互斥前提）", act("ppjob")["shedlock"]["enabled"] is True, str(act("ppjob")["shedlock"]))
    trial_guard("基线")

    # ---------------- [2] ST-1 T+N 日切与状态机 ----------------
    print("[2] ST-1 T+N 结算：日切到期判定 + 批次状态机 + 净额恒等 + 幂等")
    ctx = pay_once("A")
    mid = ctx["merchant"]["id"]
    rec = ctx["record"]
    check("结算单手续费 = 受理快照 60bps", rec["fee"] == (rec["gross"] * 60 + 5000) // 10000, str(rec))
    cyc = ok("GET", f"/api/settle/cycles?merchantId={mid}")
    check("新商户周期取动态默认 T+1（不落库）", cyc["cycleDays"] == 1 and cyc["status"] == "DEFAULT", str(cyc))
    run = ok("POST", "/api/settle/batch/run", {"bizDate": day}, step=True)
    check("T+1 当日未到期：不出批次", not any(batch_key(b) == (mid, day) for b in run["batches"]),
          str(run["batches"])[:200])
    check("T+1 未到期计入 notDue", run["notDue"] >= 1, str(run["notDue"]))
    check("未到期商户无批次落库", batches_of(mid) == [], str(batches_of(mid)))
    ok("POST", "/api/settle/cycles", {"merchantId": mid, "cycleDays": 0})
    run2 = ok("POST", "/api/settle/batch/run", {"bizDate": day}, step=True)
    mine = [b for b in run2["batches"] if batch_key(b) == (mid, day)]
    check("改 T+0 后当日到期出批次", len(mine) == 1, str(run2["batches"])[:300])
    b = mine[0]
    check("批次状态机终态 SETTLED", b["status"] == "SETTLED", str(b))
    check("批次周期记录 = T+0", b["cycleDays"] == 0, str(b))
    check("批次业务日 = 当日日切", b["bizDate"] == day and b["bizDateText"] == time.strftime("%Y-%m-%d"), str(b))
    check("批次净额恒等 net = gross - refund - fee",
          b["net"] == b["gross"] - b["refund"] - b["fee"], str(b))
    check("批次净额 = 该商户当日结算单净额", b["net"] == rec["net"] and b["recordCount"] == 1, str(b))
    check("批次手续费 = 结算单手续费快照", b["fee"] == rec["fee"], str(b))
    check("批次结算时间已落库", b["settledAt"] is not None and b["settledAt"] >= b["createdAt"], str(b))
    m_before = bal(ctx["ma"]["id"])
    run3 = ok("POST", "/api/settle/batch/run", {"bizDate": day}, step=True)
    again = [x for x in run3["batches"] if batch_key(x) == (mid, day)]
    check("重复跑批幂等：同一批次 id", len(again) == 1 and again[0]["id"] == b["id"], str(again))
    check("重复跑批计入 skipped 不重结", run3["skipped"] >= 1 and run3["created"] == 0, str(run3))
    check("重复跑批不重复动账（商户余额不变）", bal(ctx["ma"]["id"]) == m_before, str(bal(ctx["ma"]["id"])))
    check("批次可单查", ok("GET", f"/api/settle/batches/{b['id']}")["id"] == b["id"])

    # ---------------- [3] 分片调度不重不漏 + 多实例互斥 ----------------
    print("[3] 分片跑批：按 merchant 取模不重不漏 + 并发触发 ShedLock 互斥")
    shard_ctxs = [pay_once(f"S{i}", amount=2000 + i * 100) for i in range(4)]
    for c in shard_ctxs:
        ok("POST", "/api/settle/cycles", {"merchantId": c["merchant"]["id"], "cycleDays": 0})
    shard_runs = []
    for i in range(4):
        shard_runs.append(ok("POST", "/api/settle/batch/run",
                             {"bizDate": day, "shardIndex": i, "shardTotal": 4}, step=True))
    seen = {}
    dup = []
    for i, r in enumerate(shard_runs):
        check(f"分片 {i}/4 上报分片参数", r["shardIndex"] == i and r["shardTotal"] == 4, str(r)[:150])
        for x in r["batches"]:
            if x["merchantId"] in seen:
                dup.append(x["merchantId"])
            seen[x["merchantId"]] = i
    check("分片间商户不重复（不重）", not dup, str(dup))
    want = {c["merchant"]["id"] for c in shard_ctxs}
    check("4 片并集覆盖全部新商户（不漏）", want <= set(seen.keys()), str(want - set(seen.keys())))
    for c in shard_ctxs:
        bs = [x for x in batches_of(c["merchant"]["id"]) if x["bizDate"] == day]
        check(f"商户 {c['merchant']['id']} 当日恰一个批次", len(bs) == 1 and bs[0]["status"] == "SETTLED", str(bs))
        check(f"商户 {c['merchant']['id']} 批次净额 = 结算净额",
              bs and bs[0]["net"] == c["record"]["net"], str(bs))
    total_before = act("ppsettle")["batch"]["total"]
    net_before = act("ppsettle")["batch"]["settledNet"]
    with ThreadPoolExecutor(max_workers=4) as pool:
        futs = [pool.submit(api, "POST", "/api/settle/batch/run", {"bizDate": day}, True) for _ in range(4)]
        results = [f.result() for f in futs]
    check("并发 4 次触发全部返回 200（多实例语义）", all(st == 200 for st, _ in results),
          str([st for st, _ in results]))
    ops_after = act("ppsettle")
    check("并发触发不新增批次（ShedLock + 幂等键吸收）", ops_after["batch"]["total"] == total_before,
          f'{total_before} -> {ops_after["batch"]["total"]}')
    check("并发触发净额不变（不重结）", ops_after["batch"]["settledNet"] == net_before,
          f'{net_before} -> {ops_after["batch"]["settledNet"]}')
    check("无 FAILED 批次", ops_after["batch"]["byStatus"].get("FAILED", 0) == 0,
          str(ops_after["batch"]["byStatus"]))
    job = act("ppjob")["jobs"]["settleBatch"]
    check("settleBatch 已注册为 JobHandler", "settleBatch" in act("ppjob")["handlers"], str(act("ppjob")["handlers"]))
    # 真·调度路径：经 /actuator/ppjob/settleBatch（JobHandler + 逐片 ShedLock），而非仅 facade 直调
    jb = act("ppjob")["jobs"]["settleBatch"]
    with ThreadPoolExecutor(max_workers=4) as pool:
        jfuts = [pool.submit(act, "ppjob/settleBatch",
                             {"shardIndex": 0, "shardTotal": 1, "param": str(day)}) for _ in range(4)]
        jres = [f.result() for f in jfuts]
    ja = act("ppjob")["jobs"]["settleBatch"]
    check("JobHandler 路径 4 次并发触发全部成功", all(r["success"] for r in jres), str(jres)[:250])
    check("同片并发计入 ShedLock acquired/skipped（无异常）",
          (ja["lockAcquired"] + ja["lockSkipped"]) - (jb["lockAcquired"] + jb["lockSkipped"]) == 4,
          f'{jb["lockAcquired"]}/{jb["lockSkipped"]} -> {ja["lockAcquired"]}/{ja["lockSkipped"]}')
    lock_names = [r["name"] for r in act("ppjob")["shedlock"]["rows"]]
    check("逐片锁名落库 settleBatch:0/1（同片多实例互斥）", "settleBatch:0/1" in lock_names, str(lock_names))
    ops_job = act("ppsettle")
    check("JobHandler 并发跑批不新增批次（不重结）", ops_job["batch"]["total"] == total_before,
          f'{total_before} -> {ops_job["batch"]["total"]}')
    check("JobHandler 并发跑批净额不变", ops_job["batch"]["settledNet"] == net_before,
          f'{net_before} -> {ops_job["batch"]["settledNet"]}')
    for i in range(4):
        r = act("ppjob/settleBatch", {"shardIndex": i, "shardTotal": 4, "param": str(day)})
        check(f"JobHandler 分片 {i}/4 触发成功", r["success"] and r["shardIndex"] == i and r["shardTotal"] == 4, str(r))
    lock_names = [r["name"] for r in act("ppjob")["shedlock"]["rows"]]
    check("4 片各自独立锁名（不共用锁名 ⇒ 分片广播不互相跳过）",
          all(f"settleBatch:{i}/4" in lock_names for i in range(4)), str(lock_names))
    for c in shard_ctxs:
        bs = [x for x in batches_of(c["merchant"]["id"]) if x["bizDate"] == day]
        check(f"JobHandler 跑批后商户 {c['merchant']['id']} 仍恰一个批次", len(bs) == 1, str(bs))
    check("JobHandler 跑批后仍无 FAILED 批次",
          act("ppsettle")["batch"]["byStatus"].get("FAILED", 0) == 0, str(act("ppsettle")["batch"]["byStatus"]))

    # ---------------- [4] ST-2 费率配置 ----------------
    print("[4] ST-2 费率：分层/阶梯/分成 + 热更 + 在途单快照不变")
    calc0 = ok("POST", "/api/settle/fee/calc",
               {"merchantId": mid, "productType": "TRADE", "method": "BALANCE", "amount": 3000})
    check("无规则时缺省 60bps → fee=18", calc0["feeBps"] == 60 and calc0["fee"] == 18 and calc0["source"] == "DEFAULT",
          str(calc0))
    rule = ok("POST", "/api/settle/fee-rules", {
        "merchantId": mid, "productType": "TRADE", "method": "CARD", "feeType": "TIERED",
        "tiers": [{"min": 0, "max": 5000, "bps": 100}, {"min": 5000, "max": None, "bps": 50}],
        "shareRatio": 3000}, step=True)
    check("阶梯规则保存 version=1", rule["version"] == 1 and rule["feeType"] == "TIERED", str(rule))
    t1 = ok("POST", "/api/settle/fee/calc",
            {"merchantId": mid, "productType": "TRADE", "method": "CARD", "amount": 3000})
    check("阶梯区间 [0,5000) 命中 100bps → fee=30", t1["feeBps"] == 100 and t1["fee"] == 30, str(t1))
    t2 = ok("POST", "/api/settle/fee/calc",
            {"merchantId": mid, "productType": "TRADE", "method": "CARD", "amount": 10000})
    check("阶梯区间 [5000,∞) 命中 50bps → fee=50", t2["feeBps"] == 50 and t2["fee"] == 50, str(t2))
    t3 = ok("POST", "/api/settle/fee/calc",
            {"merchantId": mid, "productType": "TRADE", "method": "BALANCE", "amount": 3000})
    check("支付方式分层：BALANCE 不受 CARD 规则影响仍 60bps", t3["feeBps"] == 60 and t3["source"] == "DEFAULT", str(t3))
    rule2 = ok("POST", "/api/settle/fee-rules", {
        "merchantId": mid, "productType": "TRADE", "method": "CARD", "feeType": "FIXED", "feeBps": 80}, step=True)
    check("费率变更 version 自增（审计留痕）", rule2["version"] == 2 and rule2["id"] == rule["id"], str(rule2))
    check("规则可查询", any(x["id"] == rule["id"] for x in ok("GET", f"/api/settle/fee-rules?merchantId={mid}")))
    rec_now = next(x for x in ok("GET", f"/api/settle/records?merchantId={mid}") if x["id"] == rec["id"])
    check("费率变更后历史结算单手续费快照不变", rec_now["fee"] == rec["fee"], str(rec_now))
    b_now = ok("GET", f"/api/settle/batches/{b['id']}")
    check("费率变更后历史批次净额不变", b_now["net"] == b["net"] and b_now["fee"] == b["fee"], str(b_now))
    # DynamicConfig 热更（settle-fee 语义键；local-file provider 立即 refresh，Apollo provider 长轮询推送）
    config_write({"settle.fee.default-bps": 100})
    hot = ok("POST", "/api/settle/fee/calc",
             {"merchantId": mid, "productType": "OTHER", "method": "BALANCE", "amount": 3000})
    check("配置热更生效：缺省层 100bps → fee=30", hot["feeBps"] == 100 and hot["fee"] == 30 and hot["source"] == "CONFIG",
          str(hot))
    hot_rule = ok("POST", "/api/settle/fee/calc",
                  {"merchantId": mid, "productType": "TRADE", "method": "CARD", "amount": 3000})
    check("DB 规则优先于配置（80bps 不被热更覆盖）", hot_rule["feeBps"] == 80 and hot_rule["source"] == "RULE",
          str(hot_rule))
    rec_hot = next(x for x in ok("GET", f"/api/settle/records?merchantId={mid}") if x["id"] == rec["id"])
    check("热更不影响在途/历史结算单快照", rec_hot["fee"] == rec["fee"], str(rec_hot))
    config_write({"settle.cycle.default-days": 0})
    check("周期热更生效：默认 T+0", ok("GET", f"/api/settle/cycles?merchantId={new_party('CY')[0]['id']}")["cycleDays"] == 0)
    config_write({"settle.fee.default-bps": None, "settle.cycle.default-days": None})
    back = ok("POST", "/api/settle/fee/calc",
              {"merchantId": mid, "productType": "OTHER", "method": "BALANCE", "amount": 3000})
    check("配置回滚后缺省费率复原 60bps", back["feeBps"] == 60 and back["source"] == "DEFAULT", str(back))
    check("配置回滚后周期复原 T+1", act("ppsettle")["defaults"]["cycleDays"] == 1, str(act("ppsettle")["defaults"]))

    # ---------------- [5] ST-3 分账守恒 ----------------
    print("[5] ST-3 分账（结算侧）：净额守恒误差 0 + 复式记账 + 幂等 + 回退")
    recv1_m, recv1 = new_party("R1", "USER")
    recv2_m, recv2 = new_party("R2", "USER")
    share_id = "SHR" + SUFFIX
    net = rec["net"]
    part1 = net // 3
    part2 = net - part1
    m0, r10, r20 = bal(ctx["ma"]["id"]), bal(recv1["id"]), bal(recv2["id"])
    d1 = ok("POST", "/api/settle/shares", {"shareId": share_id, "paymentId": rec["paymentId"],
                                          "merchantId": mid, "merchantAccountId": ctx["ma"]["id"],
                                          "receiverAccountId": recv1["id"], "amount": part1, "fee": 0})
    d2 = ok("POST", "/api/settle/shares", {"shareId": share_id, "paymentId": rec["paymentId"],
                                          "merchantId": mid, "merchantAccountId": ctx["ma"]["id"],
                                          "receiverAccountId": recv2["id"], "amount": part2, "fee": 0})
    check("分账明细 net = amount - fee", d1["net"] == part1 and d2["net"] == part2, f"{d1} {d2}")
    ss = ok("GET", f"/api/settle/shares/summary?shareId={share_id}")
    check("Σ分账 = 结算净额（误差 0）", ss["allocated"] == net and ss["diff"] == 0 and ss["balanced"] is True, str(ss))
    check("分账笔数 = 2", ss["detailCount"] == 2, str(ss))
    check("出账商户余额减少 = Σ分账", bal(ctx["ma"]["id"]) - m0 == -net, str(bal(ctx["ma"]["id"]) - m0))
    check("收款方 1 余额增加 = 其分账额", bal(recv1["id"]) - r10 == part1, str(bal(recv1["id"]) - r10))
    check("收款方 2 余额增加 = 其分账额", bal(recv2["id"]) - r20 == part2, str(bal(recv2["id"]) - r20))
    check("分账走复式记账（凭证可回查）",
          ok("GET", f"/api/accounting/transfers/{d1['bizNo']}")["bizNo"] == d1["bizNo"])
    d1r = ok("POST", "/api/settle/shares", {"shareId": share_id, "paymentId": rec["paymentId"],
                                           "merchantId": mid, "merchantAccountId": ctx["ma"]["id"],
                                           "receiverAccountId": recv1["id"], "amount": part1, "fee": 0})
    check("重复分账消息幂等（同一明细 id）", d1r["id"] == d1["id"], str(d1r))
    check("重复分账不重复动账", bal(recv1["id"]) - r10 == part1, str(bal(recv1["id"]) - r10))
    recv3_m, recv3 = new_party("R3", "USER")
    st, resp = api("POST", "/api/settle/shares", {"shareId": share_id, "paymentId": rec["paymentId"],
                                                 "merchantId": mid, "merchantAccountId": ctx["ma"]["id"],
                                                 "receiverAccountId": recv3["id"], "amount": 1, "fee": 0})
    check("超额分账拒绝 400（守恒护栏，绝不动账）", st == 400, f"{st} {resp}")
    check("超额拒绝后收款方 3 余额为 0", bal(recv3["id"]) == 0, str(bal(recv3["id"])))
    m1, r11 = bal(ctx["ma"]["id"]), bal(recv1["id"])
    back1 = ok("POST", "/api/settle/shares/back", {"shareId": share_id, "paymentId": rec["paymentId"],
                                                  "merchantId": mid, "merchantAccountId": ctx["ma"]["id"],
                                                  "receiverAccountId": recv1["id"], "amount": part1, "fee": 0})
    check("回退登记 type=SHARE_BACK", back1["type"] == "SHARE_BACK", str(back1))
    check("回退资金反向划转（商户回收 / 收款方扣回）",
          bal(ctx["ma"]["id"]) - m1 == part1 and bal(recv1["id"]) - r11 == -part1,
          f'{bal(ctx["ma"]["id"]) - m1} {bal(recv1["id"]) - r11}')
    back1r = ok("POST", "/api/settle/shares/back", {"shareId": share_id, "paymentId": rec["paymentId"],
                                                   "merchantId": mid, "merchantAccountId": ctx["ma"]["id"],
                                                   "receiverAccountId": recv1["id"], "amount": part1, "fee": 0})
    check("回退幂等（同一明细 id，不重复动账）",
          back1r["id"] == back1["id"] and bal(recv1["id"]) - r11 == -part1, str(back1r))
    ss2 = ok("GET", f"/api/settle/shares/summary?shareId={share_id}")
    check("回退后已分账净额 = 剩余分账", ss2["allocated"] == part2 and ss2["backAmount"] == part1, str(ss2))
    check("分账明细可列举", len(ok("GET", f"/api/settle/shares?shareId={share_id}")) == 3)
    trial_guard("分账后")

    # ---------------- [6] ST-4 提现到银行 ----------------
    print("[6] ST-4 提现：状态机 + 失败回滚解冻 + 补单收敛 + 幂等 + 二次认证")
    wm, wa = new_party("W", "USER")
    ok("POST", "/api/accounting/internal/topup",
       {"accountId": wa["id"], "amount": 100000, "bizNo": f"st-wtopup-{SUFFIX}"})
    b0 = bal(wa["id"])
    cash0, fee0, payout0 = bal("SYS_CASH"), bal("SYS_FEE"), bal("SYS_PAYOUT")
    st, resp = api("POST", "/api/settle/withdraw",
                   {"accountId": wa["id"], "amount": 1000, "bankCardNo": "6222000000000001",
                    "requestId": f"st-wd-deny-{SUFFIX}"})
    check("提现缺二次认证拒绝 403", st == 403, f"{st} {resp}")
    check("拒绝后余额不变（未受理绝不动账）", bal(wa["id"]) == b0, str(bal(wa["id"])))
    w1 = ok("POST", "/api/settle/withdraw",
            {"accountId": wa["id"], "amount": 1000, "bankCardNo": "6222000000000001",
             "requestId": f"st-wd-ok-{SUFFIX}"}, step=True)
    check("沙箱同步出款终态 SUCCESS", w1["status"] == "SUCCESS", str(w1))
    check("出金渠道 = 沙箱（不接真实银行）", w1["channelCode"] == "SANDBOX_BANK", str(w1))
    check("到账后账户余额减少 = 出金额", bal(wa["id"]) - b0 == -1000, str(bal(wa["id"]) - b0))
    # SYS_CASH 是资产户，余额 = 贷累计 − 借累计（充值记借方 ⇒ 余额为负）；
    # 出金记贷方 ⇒ 余额向 0 上升，即备付金实际减少。与既有 fund.withdraw（D 账户/C SYS_CASH）口径一致。
    check("到账后备付金实际减少 = 出金额（SYS_CASH 贷方）", bal("SYS_CASH") - cash0 == 1000, str(bal("SYS_CASH") - cash0))
    check("在途户 SYS_PAYOUT 已清零（冻结释放）", bal("SYS_PAYOUT") == payout0, str(bal("SYS_PAYOUT")))
    w1r = ok("POST", "/api/settle/withdraw",
             {"accountId": wa["id"], "amount": 1000, "bankCardNo": "6222000000000001",
              "requestId": f"st-wd-ok-{SUFFIX}"}, step=True)
    check("requestId 幂等返回同一提现单", w1r["id"] == w1["id"], str(w1r))
    check("幂等不重扣（余额/备付金不变）",
          bal(wa["id"]) - b0 == -1000 and bal("SYS_CASH") - cash0 == 1000, str(bal(wa["id"]) - b0))
    b1 = bal(wa["id"])
    w2 = ok("POST", "/api/settle/withdraw",
            {"accountId": wa["id"], "amount": 2000, "bankCardNo": "622200000000FAIL",
             "requestId": f"st-wd-fail-{SUFFIX}"}, step=True)
    check("渠道失败终态 FAILED", w2["status"] == "FAILED" and w2["failReason"], str(w2))
    check("失败回滚解冻：余额全额复原", bal(wa["id"]) == b1, str(bal(wa["id"]) - b1))
    check("失败不影响备付金（未真实出款）", bal("SYS_CASH") - cash0 == 1000, str(bal("SYS_CASH") - cash0))
    check("失败后在途户清零", bal("SYS_PAYOUT") == payout0, str(bal("SYS_PAYOUT")))
    b2 = bal(wa["id"])
    w3 = ok("POST", "/api/settle/withdraw",
            {"accountId": wa["id"], "amount": 3000, "bankCardNo": "6222000000PENDING",
             "requestId": f"st-wd-pend-{SUFFIX}"}, step=True)
    check("异步出款受理 PROCESSING", w3["status"] == "PROCESSING", str(w3))
    check("受理即冻结在途：可用余额减少", bal(wa["id"]) - b2 == -3000, str(bal(wa["id"]) - b2))
    check("在途资金挂 SYS_PAYOUT", bal("SYS_PAYOUT") - payout0 == 3000, str(bal("SYS_PAYOUT") - payout0))
    w3a = ok("POST", f"/api/settle/withdraw/{w3['id']}/reconcile")
    check("补单：延迟未到仍在途（不臆断终态）", w3a["status"] in ("PROCESSING", "SUCCESS"), str(w3a))
    time.sleep(2)
    w3b = ok("POST", f"/api/settle/withdraw/{w3['id']}/reconcile")
    check("补单收敛 SUCCESS（回执丢失兜底）", w3b["status"] == "SUCCESS", str(w3b))
    check("补单后余额减少 = 出金额（不重扣）", bal(wa["id"]) - b2 == -3000, str(bal(wa["id"]) - b2))
    check("补单后备付金实际减少 = 累计出金额", bal("SYS_CASH") - cash0 == 4000, str(bal("SYS_CASH") - cash0))
    check("补单后在途户清零", bal("SYS_PAYOUT") == payout0, str(bal("SYS_PAYOUT")))
    check("补单计数递增", w3b["reconcileCount"] >= 1, str(w3b))
    w3c = ok("POST", f"/api/settle/withdraw/{w3['id']}/receipt", {"status": "FAILED", "reason": "乱序回执"})
    check("终态后乱序回执被吸收（不翻状态不动账）",
          w3c["status"] == "SUCCESS" and bal(wa["id"]) - b2 == -3000, str(w3c))
    st, resp = api("POST", "/api/settle/withdraw",
                   {"accountId": wa["id"], "amount": 999_999_999, "bankCardNo": "6222000000000001",
                    "requestId": f"st-wd-poor-{SUFFIX}"}, step=True)
    check("余额不足拒绝 400（不落单不动账）", st == 400, f"{st} {resp}")
    check("提现单可查询", ok("GET", f"/api/settle/withdraw/{w1['id']}")["id"] == w1["id"])
    trial_guard("提现后")

    # ---------------- [7] ST-5 商户账单/对账单 ----------------
    print("[7] ST-5 账单：金额 = Σ流水 + CSV 逐笔一致 + 幂等 + 跨片归并")
    bill = ok("POST", "/api/settle/bills", {"merchantId": mid, "billType": "DAILY"})
    check("日账单 gross = 结算单 gross", bill["gross"] == rec["gross"], str(bill))
    check("日账单 fee = 结算单 fee", bill["fee"] == rec["fee"], str(bill))
    check("日账单 net = 结算单 net = 批次 net", bill["net"] == rec["net"] == b["net"], str(bill))
    check("日账单 share = Σ分账净额（回退后剩余）", bill["share"] == part2, str(bill))
    check("日账单笔数 = 当日结算单数", bill["recordCount"] == 1, str(bill))
    check("账单文件路径落 data/bills", bill["filePath"].startswith("data/bills"), str(bill))
    st, csv = http(BASE + f"/api/settle/bills/{bill['id']}/download", "GET", bearer=True, raw=True)
    check("CSV 下载 200", st == 200, str(st))
    lines = [l for l in csv.splitlines() if l and not l.startswith("#")]
    sections = {}
    cur = None
    for l in csv.splitlines():
        if l.startswith("# "):
            cur = l[2:].strip()
            sections[cur] = []
        elif l and cur:
            sections[cur].append(l)
    check("CSV 含汇总/明细/分账三段", set(sections.keys()) >= {"SUMMARY", "RECORDS", "SHARES"}, str(list(sections.keys())))
    summary_row = sections["SUMMARY"][1].split(",")
    check("CSV 汇总行净额与账单一致", int(summary_row[5]) == bill["net"], str(summary_row))
    detail_rows = [l.split(",") for l in sections["RECORDS"][1:]]
    detail_net = sum(int(r[6]) if r[1] == "PAY" else -int(r[6]) for r in detail_rows)
    check("CSV 明细逐笔求和 = 账单净额（误差 0）", detail_net == bill["net"], f"{detail_net} vs {bill['net']}")
    check("CSV 明细笔数 = 账单笔数", len(detail_rows) == bill["recordCount"], str(len(detail_rows)))
    share_rows = [l.split(",") for l in sections["SHARES"][1:]]
    share_net = sum(int(r[3]) if r[2] == "SHARE" else -int(r[3]) for r in share_rows)
    check("CSV 分账段求和 = 账单 share", share_net == bill["share"], f"{share_net} vs {bill['share']}")
    bill2 = ok("POST", "/api/settle/bills", {"merchantId": mid, "billType": "DAILY"})
    check("重复生成账单幂等（同一 id 同金额）",
          bill2["id"] == bill["id"] and bill2["net"] == bill["net"], str(bill2))
    monthly = ok("POST", "/api/settle/bills", {"merchantId": mid, "billType": "MONTHLY"})
    check("月账单 net ≥ 日账单 net（含当日）", monthly["net"] >= bill["net"], str(monthly))
    check("月账单业务日 = 当月 1 日", monthly["bizDate"] <= day and monthly["bizDateText"] == time.strftime("%Y-%m"),
          str(monthly))
    check("账单可列举", any(x["id"] == bill["id"] for x in ok("GET", f"/api/settle/bills?merchantId={mid}")))
    agg = act(f"ppsettle/agg/{mid}/{day}")
    keys = ("recordCount", "gross", "refund", "fee", "net")
    routed = {k: agg["routed"][k] for k in keys}
    check("跨片归并：逻辑表顶层 SUM 有值", routed["net"] == rec["net"], str(routed))
    if agg.get("shardingConfigured"):
        shard_sum = {k: agg["shardSum"][k] for k in keys}
        legacy = {k: agg["legacySingleTable"][k] for k in keys}
        check("账单口径：各片之和 = 逻辑表归并", shard_sum == routed, f"{shard_sum} vs {routed}")
        check("账单口径：原单表 = 逻辑表归并（影子写一致）", legacy == routed, f"{legacy} vs {routed}")
        check("账单口径：商户精确路由到预期分片",
              agg["expectedShard"] == next(i for i, s in enumerate(agg["shards"])
                                          if s["recordCount"] == routed["recordCount"]), str(agg["expectedShard"]))
    job_bill = ok("POST", "/api/settle/bills", {"merchantId": mid, "billType": "DAILY"})
    check("账单跑批作业可触发（settleBill）", act("ppjob/settleBill", {"shardIndex": 0, "shardTotal": 1})["success"] is True)
    check("跑批后账单仍幂等", ok("POST", "/api/settle/bills", {"merchantId": mid, "billType": "DAILY"})["id"] == job_bill["id"])

    # ---------------- [8] 分片回滚逐条一致 ----------------
    print("[8] 一键回滚单库：结算单/批次/账单/汇总 逐条一致")
    shard_state = act("ppshard")
    if shard_state.get("shardingConfigured"):
        before = snapshot(mid)
        agg_before = act(f"ppsettle/agg/{mid}/{day}")
        off = act("ppshard", {"action": "toggle", "enabled": False})
        check("回滚开关切到单库", off["shardingActive"] is False, str(off))
        after = snapshot(mid)
        check("回滚后结算单逐条一致", after["records"] == before["records"], "records diff")
        check("回滚后批次逐条一致", after["batches"] == before["batches"], "batches diff")
        check("回滚后账单逐条一致", after["bills"] == before["bills"], "bills diff")
        check("回滚后全局汇总一致", after["summary"] == before["summary"],
              f'{before["summary"]} vs {after["summary"]}')
        agg_after = act(f"ppsettle/agg/{mid}/{day}")
        check("回滚后账单口径聚合一致", agg_after["routed"] == agg_before["routed"],
              f'{agg_before["routed"]} vs {agg_after["routed"]}')
        rb = ok("POST", "/api/settle/batch/run", {"bizDate": day}, step=True)
        check("单库态跑批仍幂等不重结", rb["created"] == 0 and rb["skipped"] >= 1, str(rb)[:200])
        rbill = ok("POST", "/api/settle/bills", {"merchantId": mid, "billType": "DAILY"})
        check("单库态账单仍幂等", rbill["id"] == bill["id"] and rbill["net"] == bill["net"], str(rbill))
        on = act("ppshard", {"action": "toggle", "enabled": True})
        check("切回分片态", on["shardingActive"] is True, str(on))
        again = snapshot(mid)
        check("切回分片后逐条仍一致", again == before, "snapshot diff after re-enable")
        check("影子写失败计数为 0", act("ppshard").get("mirrorFailures", 0) == 0, str(act("ppshard").get("mirrorFailures")))
    else:
        check("分片未装配（local 单库）跳过分片对照", True)

    # ---------------- [9] 资金护航 ----------------
    print("[9] 资金护航：试算平衡 + 六项对账")
    trial_guard("收尾")
    time.sleep(4)
    rep = ok("POST", "/api/recon/run")
    check("六项对账口径不变", len(rep["checks"]) == 6, str([c["name"] for c in rep["checks"]]))
    for c in rep["checks"]:
        check(f"对账：{c['name']}", c["pass"], c["detail"])
    check("对账报告持久化可回读", ok("GET", "/api/recon/latest").get("id") == rep["id"])

    print()
    print(f"结果：{PASSED} 通过，{FAILED} 失败")
    if FAILURES:
        print("失败项：")
        for name in FAILURES:
            print("  - " + name)
    return 1 if FAILED else 0


def restore_config():
    """恢复配置文件原状（只动本脚本的键；原本不存在则删除本脚本创建的文件）。"""
    try:
        mine = {"settle.fee.default-bps", "settle.cycle.default-days"}
        current = config_read()
        for k in mine:
            current.pop(k, None)
        if not CONFIG_EXISTED and not current:
            if os.path.exists(CONFIG_FILE):
                os.remove(CONFIG_FILE)
        else:
            os.makedirs(os.path.dirname(CONFIG_FILE), exist_ok=True)
            with open(CONFIG_FILE, "w", encoding="utf-8") as f:
                for k, v in sorted(current.items()):
                    f.write(f"{k}={v}\n")
        act("ppsettle", {"action": "refresh"})
    except Exception as e:
        print(f"[warn] 配置恢复失败: {e}")


if __name__ == "__main__":
    code = 1
    try:
        code = main()
    except Exception as exc:
        print(f"[fatal] {type(exc).__name__}: {exc}")
        code = 1
    finally:
        restore_config()
        try:
            state = act("ppshard")
            if state.get("shardingConfigured") and not state.get("shardingActive"):
                act("ppshard", {"action": "toggle", "enabled": True})
                print("[cleanup] 分片路由已恢复 active=true")
        except Exception:
            pass
    sys.exit(code)