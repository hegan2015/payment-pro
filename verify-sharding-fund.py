#!/usr/bin/env python3
# ============================================================================
# payment-pro I-5b 验证：资金域分库分表（payment / accounting / trade）
#
# 用法：docker 全栈在线（./run.sh --docker）后 python3 verify-sharding-fund.py
#   ① 8 并发同 requestId 跨片仍只 1 支付单（独立幂等索引表，E-7 核心；ID 形态不变）
#   ② 分片后试算平衡 D=C 且 Σbalance=0（跨片 SUM 归并 = 各片之和 = 原单表）
#   ③ 六项对账全 pass（切流前 / 分片态 / 回滚态 / 收尾每步必跑）
#   ④ 跨片转账 A 片→B 片 TCC 收敛、余额守恒（含退款反向）
#   ⑤ 全局发号无重号（三域并发，workerId 互斥 ⇒ ID 空间不相交）
#   ⑥ 切回单库逐条一致（余额/流水/分页/汇总/支付单全量快照，回滚）
#   ⑦ 影子读比对零差异（原单表 vs 分片侧逐行逐列）
#   ⑧ 协调器库不分片 + 既有数据原样保留 + local H2 单库不装配
#
# 资金护航：任一步试算/对账不平或影子比对差异非 0 → 立即停止并回滚单库，如实报告。
# 数据保护：只新增业务行（真实支付链路）+ 影子副本同步，绝不删除/重置任何既有数据。
# ============================================================================
import concurrent.futures
import json
import os
import re
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
import zlib

BASE = "http://127.0.0.1:8080"
PAY_ACT = "http://127.0.0.1:18085/actuator/ppshard"
ACC_ACT = "http://127.0.0.1:18083/actuator/ppshard"
TRD_ACT = "http://127.0.0.1:18084/actuator/ppshard"
ACTS = {"payment": PAY_ACT, "accounting": ACC_ACT, "trade": TRD_ACT}
LOCAL_ACC_PORT = int(os.environ.get("I5B_LOCAL_ACC_PORT", "18183"))
JAVA_HOME = os.environ.get("JAVA_HOME", "/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home")
ROOT = os.path.dirname(os.path.abspath(__file__))
SUFFIX = uuid.uuid4().hex[:6]
SHARDS = 4
TOKEN = None
PASSED = 0
FAILED = 0
FAILURES = []


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


def _login():
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


def http(url, method="GET", body=None, bearer=False, timeout=90):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, method=method,
                               headers={"Content-Type": "application/json", "Accept": "*/*"})
    if bearer and TOKEN:
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
        return 0, {"error": str(e)}


def api(method, path, body=None):
    st, resp = http(BASE + path, method, body, bearer=True)
    if st == 401:
        _login()
        st, resp = http(BASE + path, method, body, bearer=True)
    return st, resp


def ok(method, path, body=None):
    st, resp = api(method, path, body)
    assert st == 200, f"{method} {path} 期望 200，实际 {st}: {resp}"
    return resp


def act(url, path="", body=None):
    full = url + ("/" + path if path else "")
    st, resp = http(full, "POST" if body is not None else "GET", body)
    assert st == 200, f"{full} 期望 200，实际 {st}: {resp}"
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


def shard_of(key):
    """与 Java java.util.zip.CRC32 / MySQL CRC32 同一 IEEE CRC-32 算法。"""
    return zlib.crc32((key or "").encode("utf-8")) % SHARDS


def trial_guard(stage):
    t = ok("GET", "/api/accounting/trial")
    good = check(f"[{stage}] 试算平衡 D=C 且 Σbalance=0",
                 bool(t.get("balanced")) and t.get("balanceSum") == 0, str(t))
    return good, t


def recon_guard(stage):
    time.sleep(4)  # 等 outbox 末批投递（与 smoke/verify-sharding 同口径）
    rep = ok("POST", "/api/recon/run")
    names = [c["name"] for c in rep["checks"]]
    check(f"[{stage}] 六项对账口径不变", len(rep["checks"]) == 6, str(names))
    for c in rep["checks"]:
        check(f"[{stage}] 对账：{c['name']}", c["pass"], c["detail"])
    latest = ok("GET", "/api/recon/latest")
    check(f"[{stage}] 对账报告持久化可回读", latest.get("id") == rep["id"],
          f'{latest.get("id")} vs {rep["id"]}')
    return rep


def compare_guard(stage, must_zero=True):
    """影子读比对：三域原单表 vs 分片侧逐行逐列，差异数必须为 0。"""
    total = 0
    for name, url in ACTS.items():
        c = act(url, "shadowCompare")
        d = c.get("diffTotal", -1)
        total += d if isinstance(d, int) else 0
        check(f"[{stage}] {name} 影子读比对零差异", d == 0,
              json.dumps({k: v for k, v in c.items() if k != "samples"}, ensure_ascii=False)[:400])
        for tbl, one in c.items():
            if isinstance(one, dict) and "legacyRows" in one:
                check(f"[{stage}] {name}.{tbl} 原单表行数 = 分片侧行数（零删除）",
                      one["legacyRows"] == one["shardRows"], str(one))
    if must_zero:
        check(f"[{stage}] 三域影子比对合计差异 = 0", total == 0, str(total))
    return total


def toggle_all(enabled, stage):
    out = {}
    for name, url in ACTS.items():
        r = act(url, body={"action": "toggle", "enabled": enabled})
        out[name] = r
        if r.get("aborted"):
            check(f"[{stage}] {name} 切换护栏放行（差异非 0 即拒绝）", False, str(r.get("reason")))
        else:
            check(f"[{stage}] {name} 路由切换成功 → {'SHARDED' if enabled else 'SINGLE'}",
                  r.get("shardingActive") is enabled, str(r))
    return out


def pay_once(tag, amount=3000, topup_multiple=3):
    """真实业务链路：商户/用户开户 → 充值 → 下单 → 余额支付（TCC）→ outbox → 结算登记。"""
    merchant = ok("POST", "/api/members", {"name": f"I5B商户{tag}{SUFFIX}", "type": "MERCHANT"})
    user = ok("POST", "/api/members", {"name": f"I5B用户{tag}{SUFFIX}", "type": "USER"})
    ma = ok("POST", "/api/accounts", {"memberId": merchant["id"]})
    ua = ok("POST", "/api/accounts", {"memberId": user["id"]})
    ok("POST", "/api/accounting/internal/topup",
       {"accountId": ua["id"], "amount": amount * topup_multiple, "bizNo": f"i5b-topup-{tag}-{SUFFIX}"})
    order = ok("POST", "/api/orders",
               {"merchantId": merchant["id"], "subject": "I5B 资金分片验证", "amount": amount})["order"]
    r = ok("POST", "/api/cashier/pay", {"orderId": order["id"], "method": "BALANCE",
                                       "accountId": ua["id"], "requestId": f"i5b-req-{tag}-{SUFFIX}"})
    pid = r["payment"]["id"]
    p = poll(lambda: ok("GET", f"/api/payments/{pid}"), lambda x: x["status"] == "SUCCESS")
    assert p["status"] == "SUCCESS", f"支付未成功: {p}"
    return {"merchant": merchant, "ma": ma, "ua": ua, "order": order, "payment": p}


def entries_page_all(size=13, limit=4000):
    """跨片分页遍历流水：返回 (ids, total, 页数)。"""
    ids, page, pages, total = [], 1, 0, None
    while len(ids) < limit:
        d = ok("GET", f"/api/accounting/entries?page={page}&size={size}")
        total = d["total"]
        pages += 1
        items = d["items"]
        if not items:
            break
        ids.extend(x["id"] for x in items)
        if len(items) < size:
            break
        page += 1
    return ids, total, pages


def payments_snapshot():
    return {x["id"]: (x["status"], x["amount"], x["fee"], x.get("refundedAmount"), x["requestId"])
            for x in ok("GET", "/api/payments")}


def start_local(module, port, dubbo_port, name):
    """隔离 local JVM：H2 内存库（不建/不碰任何 data/ 文件）、独立注册分组、显式空闲 Dubbo 端口。"""
    jar = os.path.join(ROOT, module, "target", f"{module}-1.0.0.jar")
    if not os.path.exists(jar):
        return None, f"jar 缺失: {jar}"
    cmd = [os.path.join(JAVA_HOME, "bin", "java"),
           f"-Dserver.port={port}",
           f"-Dspring.datasource.url=jdbc:h2:mem:i5b_{name};MODE=MySQL;DATABASE_TO_LOWER=TRUE",
           "-Dspring.datasource.driver-class-name=org.h2.Driver",
           "-Dspring.datasource.username=sa",
           "-Dspring.datasource.password=",
           "-Dpayment.sharding.enabled=false",
           "-Dpayment.job.local.enabled=false",
           "-Dpayment.job.shedlock.enabled=false",
           "-Dpayment.job.xxl.enabled=false",
           "-Dpayment.mq.enabled=false",
           "-Ddubbo.registry.address=zookeeper://127.0.0.1:2181",
           "-Ddubbo.registry.group=i5b-local-verify",
           "-Ddubbo.consumer.check=false",
           f"-Ddubbo.protocol.port={dubbo_port}",
           "-Ddubbo.application.qos-enable=false",
           "-jar", jar]
    log = open(os.path.join(ROOT, "logs", f"i5b-local-{name}.log"), "w")
    proc = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT, cwd=ROOT)
    url = f"http://127.0.0.1:{port}/actuator/ppshard"
    for _ in range(120):
        if proc.poll() is not None:
            return proc, f"进程提前退出 exit={proc.returncode}"
        try:
            st, _ = http(url, timeout=3)
            if st == 200:
                return proc, None
        except Exception:
            pass
        time.sleep(1)
    return proc, "120s 未就绪"


def rollback_and_exit(reason):
    print(f"!! {reason} —— 立即回滚单库（资金安全护栏）")
    for name, url in ACTS.items():
        try:
            act(url, body={"action": "resync", "direction": "TO_LEGACY"})
            act(url, body={"action": "toggle", "enabled": False})
        except Exception as e:
            print(f"   回滚 {name} 异常: {e}")
    print(f"\n结果：{PASSED} 通过，{FAILED} 失败")
    if FAILURES:
        print("失败项：" + "; ".join(FAILURES))
    raise SystemExit(1)


def main():
    print(f"== payment-pro I-5b 资金域分库分表验证（批次 {SUFFIX}）==")
    _login()

    # ---------- 0. 前置：健康 + 装配 + 基线快照 ----------
    print("[0] 前置：13/13 健康 + 资金域分片装配 + 既有数据基线")
    health = poll(lambda: ok("GET", "/api/health"), lambda h: all(x["ok"] for x in h), timeout=90, interval=2)
    check("13 个进程健康", len(health) == 13 and all(x["ok"] for x in health), str(len(health)))
    check("控制台登录拿到 Bearer", bool(TOKEN))
    st = {n: act(u) for n, u in ACTS.items()}
    for n in ACTS:
        if not check(f"{n} 已装配 ShardingSphere（docker）", bool(st[n].get("shardingConfigured")), str(st[n])[:200]):
            print("!! 分片未装配：请确认 docker profile 且 PAYMENT_SHARDING_ENABLED 未被置 false")
            print(f"\n结果：{PASSED} 通过，{FAILED} 失败")
            raise SystemExit(1)
    check("payment 分片键：payment=id / refund_record=payment_id",
          st["payment"].get("shardTables") == {"payment": "id", "refund_record": "payment_id"},
          str(st["payment"].get("shardTables")))
    check("accounting 分片键：account_balance/ledger_entry 均=account_id（同账户同片）",
          st["accounting"].get("shardTables") == {"account_balance": "account_id", "ledger_entry": "account_id"},
          str(st["accounting"].get("shardTables")))
    check("trade 分片键：trade_order=merchant_id",
          st["trade"].get("shardTables") == {"trade_order": "merchant_id"}, str(st["trade"].get("shardTables")))
    check("三域均 4 片 + 读写分离 + 全行双写开启",
          all(st[n].get("shardCount") == 4 and st[n].get("readwriteSplitting") and st[n].get("mirrorLegacy")
              for n in ACTS), str({n: st[n].get("shardCount") for n in ACTS}))
    check("启动默认单库观察态（initial-active=false，切流须显式触发）",
          all(st[n].get("shardingActive") is False and st[n].get("idMode") == "SEQUENCE" for n in ACTS),
          str({n: st[n].get("shardingActive") for n in ACTS}))
    check("路由状态已持久化（重启不丢权威侧）",
          all(st[n].get("routeStatePersisted") is False for n in ACTS),
          str({n: st[n].get("routeStatePersisted") for n in ACTS}))
    # 协调器库不分片：dtx/outbox/幂等表不得出现在分片表清单
    for n in ACTS:
        tables = set((st[n].get("shardTables") or {}).keys())
        check(f"{n} 协调器/幂等/支撑表绝不碎片",
              not (tables & {"dtx_transaction", "dtx_branch", "outbox", "idempotent_record",
                             "sequences", "shedlock", "tcc_branch_log", "transfer_record",
                             "payment_request_index", "refund_request_index", "shard_route_state"}),
              str(tables))
    base_pay = payments_snapshot()
    base_orders = {x["id"] for x in ok("GET", "/api/orders")}
    good, base_trial = trial_guard("前置")
    if not good:
        print("!! 试算不平，立即停止（不做任何分片操作）")
        raise SystemExit(1)
    base_rows = {n: dict(st[n].get("physicalRows") or {}) for n in ACTS}
    check("基线：原单表行数 > 0（既有数据存在，全程必须保留）",
          all(base_rows[n].get(t, 0) > 0 for n, t in
              (("payment", "payment"), ("accounting", "account_balance"), ("trade", "trade_order"))),
          str({n: base_rows[n] for n in ACTS})[:300])
    base_index = st["payment"].get("paymentRequestIndexRows", 0)
    check("幂等索引已回填覆盖既有支付单（索引行数 ≥ 既有支付单数）",
          base_index >= len(base_pay), f"index={base_index} payments={len(base_pay)}")
    compare_guard("前置单库态")
    recon_guard("前置")

    # ---------- 1. 对账护航下切流到分片 ----------
    print("[1] 切流：全量同步 + 影子比对零差异后切到分片（差异非 0 即拒绝）")
    t_on = toggle_all(True, "切流")
    if any(r.get("aborted") for r in t_on.values()):
        rollback_and_exit("切流被护栏拒绝（影子比对差异非 0）")
    st_on = {n: act(u) for n, u in ACTS.items()}
    check("三域取号切到全局发号 SNOWFLAKE",
          all(st_on[n].get("idMode") == "SNOWFLAKE" for n in ACTS), str({n: st_on[n].get("idMode") for n in ACTS}))
    check("切流后路由状态持久化为 true", all(act(u).get("routeStatePersisted") is True for u in ACTS.values()))
    good, _ = trial_guard("切流后")
    if not good:
        rollback_and_exit("切流后试算不平")
    compare_guard("切流后")

    # ---------- 2. 分片态真实业务 + ID 形态不变 ----------
    print("[2] 分片态真实支付链路 + ID 形态未变（P/R/O/E 前缀 + 十进制数字）")
    ctx = pay_once("shard")
    pid = ctx["payment"]["id"]
    check("分片态 paymentId 形态仍为 P+十进制数字（未嵌基因、未改前缀）",
          re.fullmatch(r"P\d+", pid) is not None, pid)
    check("分片态 paymentId 长度 ≤ 32（VARCHAR(32) 容纳，ride 存量兼容）", len(pid) <= 32, str(len(pid)))
    check("分片态 orderId 形态仍为 O+十进制数字",
          re.fullmatch(r"O\d+", ctx["order"]["id"]) is not None, ctx["order"]["id"])
    ents = ok("GET", f'/api/accounting/entries?bizNo={pid}&size=50')["items"]
    check("分片态分录 id 形态仍为 E+十进制数字", all(re.fullmatch(r"E\d+", e["id"]) for e in ents),
          str([e["id"] for e in ents])[:200])
    check("分片态支付单落库可回读（跨片 selectById）", ok("GET", f"/api/payments/{pid}")["status"] == "SUCCESS")
    idx = act(PAY_ACT, f"index/{ctx['payment']['requestId']}")
    check("幂等索引占位命中且指向同一支付单", idx.get("indexPaymentId") == pid, str(idx)[:300])
    check("索引记录的 shard = CRC32(paymentId)%4（Java/MySQL/Python 三侧同算法）",
          idx.get("expectedShard") == shard_of(pid), f'{idx.get("expectedShard")} vs {shard_of(pid)}')
    check("索引表先占位再路由：逻辑表与原单表同 requestId 均只 1 行",
          len(idx.get("routedPayment") or []) == 1 and len(idx.get("legacyPayment") or []) == 1, str(idx)[:300])
    pv = act(PAY_ACT, f"payment/{pid}")
    check("支付单物理落片唯一且 = CRC32(paymentId)%4（双写不错位）",
          pv.get("expectedShard") == shard_of(pid)
          and sum(1 for v in pv["physicalDistribution"].values() if v > 0) == 1
          and pv["physicalDistribution"][f"payment_{shard_of(pid)}"] == 1, str(pv)[:300])
    check("支付单原单表影子副本同样只 1 行（回滚可见）", pv.get("legacyRows") == 1, str(pv.get("legacyRows")))
    ss = act(PAY_ACT, "shardSummary")
    dist = [s["v0"] for s in ss["paymentShards"]]
    check("payment 已真实分布到多个分片（非全落一片）", sum(1 for x in dist if x > 0) >= 2, str(dist))
    check("payment 各片行数之和 = 原单表行数 = 逻辑归并行数",
          ss["paymentShardSum"][0] == ss["paymentLegacy"]["v0"] == ss["paymentRouted"]["v0"],
          f'{ss["paymentShardSum"]} vs {ss["paymentLegacy"]} vs {ss["paymentRouted"]}')
    check("payment 各片金额之和 = 原单表 = 逻辑归并（跨片 SUM 归并正确）",
          ss["paymentShardSum"][1] == ss["paymentLegacy"]["v1"] == ss["paymentRouted"]["v1"], str(ss["paymentShardSum"]))
    check("payment 各片手续费之和 = 原单表 = 逻辑归并（feeBps→SYS_FEE 口径不变）",
          ss["paymentShardSum"][2] == ss["paymentLegacy"]["v2"] == ss["paymentRouted"]["v2"], str(ss["paymentShardSum"]))
    check("refund_record 各片之和 = 原单表 = 逻辑归并",
          ss["refundShardSum"][0] == ss["refundLegacy"]["v0"] == ss["refundRouted"]["v0"], str(ss["refundShardSum"]))
    now_pay = payments_snapshot()
    drift = [k for k, v in base_pay.items() if now_pay.get(k) != v]
    check("切流后既有支付单原样保留（全量快照逐条一致）", not drift, str(drift)[:200])

    # ---------- 3. 8 并发同 requestId 跨片只 1 支付单（E-7 硬指标） ----------
    print("[3] 跨片全局幂等：同一 requestId 8 并发 × 3 轮，每轮只 1 支付单")
    m, ma, ua = ctx["merchant"], ctx["ma"], ctx["ua"]
    ok("POST", "/api/accounting/internal/topup",
       {"accountId": ua["id"], "amount": 200000, "bizNo": f"i5b-conc-topup-{SUFFIX}"})
    for round_no in range(3):
        order = ok("POST", "/api/orders", {"merchantId": m["id"], "subject": f"跨片幂等{round_no}",
                                           "amount": 3000})["order"]
        rid = f"i5b-same-{round_no}-{SUFFIX}"
        u0, m0 = bal(ua["id"]), bal(ma["id"])

        def pay_same(_):
            return api("POST", "/api/cashier/pay", {"orderId": order["id"], "method": "BALANCE",
                                                    "accountId": ua["id"], "requestId": rid})
        with concurrent.futures.ThreadPoolExecutor(8) as ex:
            results = list(ex.map(pay_same, range(8)))
        oks = [r for s, r in results if s == 200]
        ids = {r["payment"]["id"] for r in oks}
        check(f"[轮{round_no}] 8 并发全部受理或幂等命中",
              len(oks) >= 1 and all(s in (200, 409) for s, _ in results), str([s for s, _ in results]))
        check(f"[轮{round_no}] 跨片仍只产生一个支付单（索引表全局幂等）", len(ids) == 1, str(ids))
        winner = oks[0]["payment"]["id"]
        p = poll(lambda: ok("GET", f"/api/payments/{winner}"), lambda x: x["status"] in ("SUCCESS", "FAILED"))
        check(f"[轮{round_no}] 终态 SUCCESS", p["status"] == "SUCCESS", str(p))
        check(f"[轮{round_no}] 余额只扣一次 3000（商户只入账一次净额）",
              bal(ua["id"]) == u0 - 3000 and bal(ma["id"]) == m0 + 2982,
              f"{bal(ua['id'])}/{u0} {bal(ma['id'])}/{m0}")
        pays = [x for x in ok("GET", "/api/payments") if x["orderId"] == order["id"]]
        check(f"[轮{round_no}] 库中该订单仅一笔支付单（跨片全量扫描）", len(pays) == 1, str(len(pays)))
        iv = act(PAY_ACT, f"index/{rid}")
        check(f"[轮{round_no}] 索引表该 requestId 只 1 行且指向胜者",
              iv.get("indexPaymentId") == winner and len(iv.get("routedPayment") or []) == 1, str(iv)[:250])
        check(f"[轮{round_no}] 幂等回放 repeated=true（语义不变）",
              sum(1 for r in oks if r.get("repeated")) == len(oks) - 1, str([r.get("repeated") for r in oks]))
    good, _ = trial_guard("跨片幂等后")
    if not good:
        rollback_and_exit("跨片并发幂等后试算不平")

    # ---------- 4. 跨片转账 A 片→B 片 TCC 收敛 + 余额守恒 ----------
    print("[4] 跨片转账：付款户与收款户刻意落不同片，TCC 收敛 + 余额守恒 + 退款反向")
    cross = None
    for attempt in range(12):
        cand = pay_once(f"cross{attempt}", amount=4000)
        if shard_of(cand["ua"]["id"]) != shard_of(cand["ma"]["id"]):
            cross = cand
            break
    if not check("构造出 A 片→B 片跨片转账场景", cross is not None,
                 f"12 次尝试未找到异片账户对（CRC32%4）"):
        rollback_and_exit("无法构造跨片场景")
    payer, payee = cross["ua"]["id"], cross["ma"]["id"]
    sa, sb = shard_of(payer), shard_of(payee)
    check("付款户与收款户确实分属不同片", sa != sb, f"{payer}→片{sa} / {payee}→片{sb}")
    amount, fee = cross["payment"]["amount"], cross["payment"]["fee"]
    p0, q0 = bal(payer), bal(payee)
    check("跨片转账 TCC 收敛：付款户扣全额、收款户入净额（片内余额一致）",
          p0 == 4000 * 3 - amount and q0 == amount - fee, f"{p0}/{q0} amount={amount} fee={fee}")
    check("跨片转账手续费入 SYS_FEE（口径不变）", fee == amount * 60 // 10000, str(fee))
    acc_ss = act(ACC_ACT, "shardSummary")
    check("account_balance 各片余额之和 = 原单表 = 逻辑归并（Σbalance 跨片守恒）",
          acc_ss["balanceShardSum"][1] == acc_ss["balanceLegacy"]["v1"] == acc_ss["balanceRouted"]["v1"],
          f'{acc_ss["balanceShardSum"]} vs {acc_ss["balanceLegacy"]} vs {acc_ss["balanceRouted"]}')
    check("account_balance 各片冻结之和 = 原单表 = 逻辑归并（TCC 冻结无残留）",
          acc_ss["balanceShardSum"][2] == acc_ss["balanceLegacy"]["v2"] == acc_ss["balanceRouted"]["v2"] == 0,
          str(acc_ss["balanceShardSum"]))
    check("account_balance 各片账户数之和 = 原单表 = 逻辑归并",
          acc_ss["balanceShardSum"][0] == acc_ss["balanceLegacy"]["v0"] == acc_ss["balanceRouted"]["v0"],
          str(acc_ss["balanceShardSum"]))
    check("ledger_entry 各片借方之和 = 各片贷方之和（跨片 ΣD=ΣC）",
          acc_ss["entryShardSum"][1] == acc_ss["entryShardSum"][2], str(acc_ss["entryShardSum"]))
    check("ledger_entry 各片之和 = 原单表 = 逻辑归并（D/C 双向）",
          acc_ss["entryShardSum"] == [acc_ss["entryLegacy"][f"v{i}"] for i in range(3)]
          == [acc_ss["entryRouted"][f"v{i}"] for i in range(3)],
          f'{acc_ss["entryShardSum"]} vs {acc_ss["entryLegacy"]} vs {acc_ss["entryRouted"]}')
    av = act(ACC_ACT, f"account/{payer}")
    check("同账户必落同片：付款户余额行只在 CRC32(accountId)%4 那一片",
          av.get("expectedShard") == sa
          and sum(1 for v in av["balanceDistribution"].values() if v > 0) == 1
          and av["balanceDistribution"][f"account_balance_{sa}"] == 1, str(av)[:300])
    check("同账户全部分录与余额同片（片内余额/冻结一致、排序加锁片内成立）",
          sum(1 for v in av["entryDistribution"].values() if v > 0) == 1
          and av["entryDistribution"][f"ledger_entry_{sa}"] > 0, str(av["entryDistribution"]))
    check("付款户余额：原单表影子副本与分片侧逐列一致",
          av["legacyBalance"] == av["routedBalance"], f'{av["legacyBalance"]} vs {av["routedBalance"]}')
    av2 = act(ACC_ACT, f"account/{payee}")
    check("收款户落另一片且余额行唯一（A 片→B 片真实跨片）",
          av2.get("expectedShard") == sb and av2["balanceDistribution"][f"account_balance_{sb}"] == 1
          and sum(1 for v in av2["balanceDistribution"].values() if v > 0) == 1, str(av2)[:300])
    trd_ss = act(TRD_ACT, "shardSummary")
    check("trade_order 各片之和 = 原单表 = 逻辑归并（订单数/金额/已退）",
          trd_ss["shardSum"] == [trd_ss["legacy"][f"v{i}"] for i in range(5)]
          == [trd_ss["routed"][f"v{i}"] for i in range(5)],
          f'{trd_ss["shardSum"]} vs {trd_ss["legacy"]} vs {trd_ss["routed"]}')
    mv = act(TRD_ACT, f"merchant/{m['id']}")
    check("trade 单商户查询只落单片（merchant_id 即分片键）",
          mv.get("expectedShard") == shard_of(m["id"])
          and sum(1 for v in mv["physicalDistribution"].values() if v > 0) == 1, str(mv)[:300])
    # 跨片退款反向：TCC 收敛后余额回到转账前
    fee_acct0 = bal("SYS_FEE")
    r = ok("POST", f"/api/payments/{cross['payment']['id']}/refund",
           {"amount": amount, "requestId": f"i5b-cross-refund-{SUFFIX}"})
    check("跨片退款受理返回 refundId（R+数字形态不变）",
          re.fullmatch(r"R\d+", r["refundId"]) is not None, str(r.get("refundId")))
    poll(lambda: ok("GET", f"/api/payments/{cross['payment']['id']}"), lambda x: x["status"] == "REFUNDED")
    check("跨片退款 TCC 收敛：付款户/收款户余额逐分回到转账前",
          bal(payer) == p0 + amount and bal(payee) == q0 - (amount - fee),
          f"{bal(payer)}/{p0 + amount} {bal(payee)}/{q0 - (amount - fee)}")
    check("跨片退款后 SYS_FEE 全额退回手续费（feeBps→SYS_FEE 口径不变）",
          bal("SYS_FEE") == fee_acct0 - fee, f'{bal("SYS_FEE")} vs {fee_acct0 - fee}')
    good, t_after = trial_guard("跨片转账+退款后")
    if not good:
        rollback_and_exit("跨片转账后试算不平")
    check("跨片转账+退款后 Σbalance 仍为 0（全局守恒）", t_after["balanceSum"] == 0, str(t_after))
    compare_guard("跨片转账后")
    recon_guard("分片态")

    # ---------- 5. 全局发号无重号 ----------
    print("[5] 全局发号：三域各 3 万、8 线程并发，合计 9 万无重号")
    gens = {n: act(u, body={"action": "idgen", "count": 30000, "threads": 8}) for n, u in ACTS.items()}
    for n, g in gens.items():
        check(f"{n} 3 万 ID 无重号", g["distinct"] == 30000 and g["duplicates"] == 0, str(g)[:200])
        check(f"{n} ID 内 workerId 唯一且等于分配值", g["workerIdsInIds"] == [g["workerId"]], str(g["workerIdsInIds"]))
        check(f"{n} workerId 由 ZK 临时节点/显式配置分配（非端口兜底）",
              g["workerIdSource"] in ("ZOOKEEPER", "CONFIG"), str(g["workerIdSource"]))
        check(f"{n} workerId 在 0..1023 合法区间", 0 <= g["workerId"] <= 1023, str(g["workerId"]))
    wids = [g["workerId"] for g in gens.values()]
    check("三域 workerId 互不相同（ID 空间不相交 ⇒ 9 万全局无重号）", len(set(wids)) == 3, str(wids))
    check("合计 9 万 ID 生成", sum(g["requested"] for g in gens.values()) == 90000, str(wids))
    prefixes = {"payment": "P", "accounting": "E", "trade": "O"}
    for n, g in gens.items():
        check(f"{n} ID 可读性保留前缀 {prefixes[n]}（形态未变）",
              all(x.startswith(prefixes[n]) and x[len(prefixes[n]):].isdigit() for x in g["sample"]), str(g["sample"]))
        check(f"{n} ID 长度 ≤ 32（VARCHAR(32) 容纳）", all(len(x) <= 32 for x in g["sample"]), str(g["sample"]))

    # ---------- 6. 切回单库：逐条一致（回滚） ----------
    print("[6] 一键回滚单库：余额/流水/分页/汇总/支付单全量快照逐条一致")
    snap_pay = payments_snapshot()
    snap_orders = {x["id"] for x in ok("GET", "/api/orders")}
    snap_bal = {a: bal(a) for a in (payer, payee, ua["id"], ma["id"], "SYS_CASH", "SYS_FEE", "SYS_SUSPENSE")}
    snap_entries, snap_total, snap_pages = entries_page_all()
    snap_acc = act(ACC_ACT, "shardSummary")
    snap_trd = act(TRD_ACT, "shardSummary")
    snap_payss = act(PAY_ACT, "shardSummary")
    snap_trial = ok("GET", "/api/accounting/trial")
    t_off = toggle_all(False, "回滚")
    if any(r.get("aborted") for r in t_off.values()):
        rollback_and_exit("回滚被护栏拒绝（影子比对差异非 0）")
    check("三域已切回单库 + 取号回到 SEQUENCE",
          all(act(u).get("shardingActive") is False and act(u).get("idMode") == "SEQUENCE" for u in ACTS.values()))
    check("回滚后路由状态持久化为 false（重启仍单库）",
          all(act(u).get("routeStatePersisted") is False for u in ACTS.values()))
    rb_pay = payments_snapshot()
    rb_drift = [k for k in set(snap_pay) | set(rb_pay) if snap_pay.get(k) != rb_pay.get(k)]
    check("回滚后支付单全量快照逐条一致（状态/金额/手续费/已退/幂等键）", not rb_drift, str(rb_drift)[:300])
    check("回滚后订单集合逐条一致（不重不漏）", {x["id"] for x in ok("GET", "/api/orders")} == snap_orders)
    check("回滚后余额逐个账户逐分一致（含 SYS_ 系统户）",
          all(bal(a) == v for a, v in snap_bal.items()),
          str({a: (bal(a), v) for a, v in snap_bal.items() if bal(a) != v}))
    back_entries, back_total, back_pages = entries_page_all()
    check("回滚后流水分页逐条一致（顺序含，不重不漏）", back_entries == snap_entries,
          f"{len(back_entries)} vs {len(snap_entries)}")
    check("回滚后流水总数一致", back_total == snap_total, f"{back_total} vs {snap_total}")
    check("回滚后分页无重复", len(back_entries) == len(set(back_entries)))
    check("回滚后账务汇总一致（余额/冻结/账户数/借贷/分录数）",
          act(ACC_ACT, "shardSummary")["balanceRouted"] == snap_acc["balanceRouted"]
          and act(ACC_ACT, "shardSummary")["entryRouted"] == snap_acc["entryRouted"], "")
    check("回滚后交易汇总一致", act(TRD_ACT, "shardSummary")["routed"] == snap_trd["routed"], "")
    check("回滚后支付汇总一致", act(PAY_ACT, "shardSummary")["paymentRouted"] == snap_payss["paymentRouted"], "")
    check("回滚后试算平衡逐项一致", ok("GET", "/api/accounting/trial") == snap_trial, "")
    good, _ = trial_guard("单库回滚态")
    if not good:
        rollback_and_exit("回滚后试算不平")
    compare_guard("单库回滚态")
    recon_guard("单库回滚态")

    # ---------- 7. 单库态真实业务 + 再切回分片（双向 additive 一致） ----------
    print("[7] 单库态业务（ID 回短序列形态）+ 再切回分片双向一致")
    ctx2 = pay_once("rollback")
    check("单库态 paymentId 回到序列形态 P+短数字（next_value_for）",
          re.fullmatch(r"P\d{1,10}", ctx2["payment"]["id"]) is not None, ctx2["payment"]["id"])
    check("单库态 orderId 回到序列形态 O+短数字",
          re.fullmatch(r"O\d{1,10}", ctx2["order"]["id"]) is not None, ctx2["order"]["id"])
    e2 = ok("GET", f'/api/accounting/entries?bizNo={ctx2["payment"]["id"]}&size=50')["items"]
    check("单库态分录 id 回到序列形态 E+短数字",
          all(re.fullmatch(r"E\d{1,10}", x["id"]) for x in e2), str([x["id"] for x in e2])[:200])
    good, _ = trial_guard("单库态业务后")
    if not good:
        rollback_and_exit("单库态业务后试算不平")
    t_on2 = toggle_all(True, "再切流")
    if any(r.get("aborted") for r in t_on2.values()):
        rollback_and_exit("再切流被护栏拒绝")
    check("再切回分片后含单库期新增支付单（additive 双写回分片）",
          ctx2["payment"]["id"] in payments_snapshot(), ctx2["payment"]["id"])
    ss2 = act(PAY_ACT, "shardSummary")
    check("往返切换后 payment 汇总 = 各片之和 = 原单表",
          ss2["paymentShardSum"] == [ss2["paymentLegacy"][f"v{i}"] for i in range(4)]
          == [ss2["paymentRouted"][f"v{i}"] for i in range(4)], str(ss2["paymentShardSum"]))
    acc2 = act(ACC_ACT, "shardSummary")
    check("往返切换后账务汇总 = 各片之和 = 原单表",
          acc2["balanceShardSum"] == [acc2["balanceLegacy"][f"v{i}"] for i in range(3)]
          == [acc2["balanceRouted"][f"v{i}"] for i in range(3)]
          and acc2["entryShardSum"] == [acc2["entryLegacy"][f"v{i}"] for i in range(3)]
          == [acc2["entryRouted"][f"v{i}"] for i in range(3)], str(acc2["balanceShardSum"]))
    trd2 = act(TRD_ACT, "shardSummary")
    check("往返切换后交易汇总 = 各片之和 = 原单表",
          trd2["shardSum"] == [trd2["legacy"][f"v{i}"] for i in range(5)]
          == [trd2["routed"][f"v{i}"] for i in range(5)], str(trd2["shardSum"]))
    compare_guard("往返切换后")
    recon_guard("往返切换后")

    # ---------- 8. 收尾：回滚到单库观察态 + 数据保护复核 ----------
    print("[8] 收尾：回到单库观察态（保守默认）+ 既有数据原样保留复核")
    toggle_all(False, "收尾回滚")
    final = {n: act(u) for n, u in ACTS.items()}
    for n in ACTS:
        rows = final[n].get("physicalRows") or {}
        for tbl in (final[n].get("shardTables") or {}):
            check(f"[收尾] {n}.{tbl} 原单表行数 = 各分片之和（全程零删除）",
                  rows.get(tbl, 0) == sum(rows.get(f"{tbl}_{k}", 0) for k in range(SHARDS)), str(rows)[:300])
            check(f"[收尾] {n}.{tbl} 原单表行数不少于基线（既有行全部保留）",
                  rows.get(tbl, 0) >= base_rows[n].get(tbl, 0),
                  f'{rows.get(tbl)} vs {base_rows[n].get(tbl)}')
    check("全程全行双写零失败", all(final[n].get("mirrorFailures", 0) == 0 for n in ACTS),
          str({n: final[n].get("mirrorFailures") for n in ACTS}))
    check("全程双写零唯一键冲突（绝不改写他行）", all(final[n].get("mirrorConflicts", 0) == 0 for n in ACTS),
          str({n: final[n].get("mirrorConflicts") for n in ACTS}))
    check("双写确实发生（mirrorRows > 0）", all(final[n].get("mirrorRows", 0) > 0 for n in ACTS),
          str({n: final[n].get("mirrorRows") for n in ACTS}))
    end_pay = payments_snapshot()
    end_drift = [k for k, v in base_pay.items() if end_pay.get(k) != v]
    check("收尾既有支付单全量快照仍逐条一致（原行零篡改）", not end_drift, str(end_drift)[:200])
    check("收尾既有订单集合仍全部保留", base_orders.issubset({x["id"] for x in ok("GET", "/api/orders")}))
    end_trial = ok("GET", "/api/accounting/trial")
    check("收尾账户数/分录数不少于基线（只增不删）",
          end_trial["accounts"] >= base_trial["accounts"] and end_trial["entries"] >= base_trial["entries"],
          f'{end_trial["accounts"]}/{base_trial["accounts"]} {end_trial["entries"]}/{base_trial["entries"]}')
    good, _ = trial_guard("收尾")
    recon_guard("收尾")
    check("收尾处于单库观察态（保守默认，切流开关就绪）",
          all(final[n].get("shardingActive") is False for n in ACTS),
          str({n: final[n].get("shardingActive") for n in ACTS}))

    # ---------- 9. local H2 单库不装配 ShardingSphere（三域全覆盖） ----------
    print("[9] local H2 单库兼容（隔离 JVM + 内存库，不触碰 data/ 与运行中的 docker 栈）")
    procs = []
    try:
        for module, port, dport, name in (
                ("payment-payment", int(os.environ.get("I5B_LOCAL_PAY_PORT", "18185")), 20995, "payment"),
                ("payment-accounting", LOCAL_ACC_PORT, 20993, "accounting"),
                ("payment-trade", int(os.environ.get("I5B_LOCAL_TRD_PORT", "18184")), 20994, "trade")):
            proc, err = start_local(module, port, dport, name)
            if proc:
                procs.append(proc)
            url = f"http://127.0.0.1:{port}/actuator/ppshard"
            if not check(f"local {name} 启动就绪（H2 内存库，schema.sql 新增表两端通用）", err is None, str(err)):
                continue
            lst = act(url)
            check(f"local {name} 未装配任何 ShardingSphere Bean", lst.get("shardingConfigured") is False, str(lst))
            check(f"local {name} 取号仍走 next_value_for（SEQUENCE）", lst.get("idMode") == "SEQUENCE", str(lst))
            w = act(url, body={"action": "toggle", "enabled": True})
            check(f"local {name} 分片开关不可用（始终单库）", w.get("shardingConfigured") is False, str(w))
            text = open(os.path.join(ROOT, "logs", f"i5b-local-{name}.log"), encoding="utf-8", errors="ignore").read()
            check(f"local {name} 日志无 ShardingSphere 装配痕迹",
                  "ShardingSphere" not in text and "分片前置初始化" not in text,
                  next((l for l in text.splitlines() if "ShardingSphere" in l), ""))
            check(f"local {name} 启动零 ERROR（H2 建表/取号/幂等表全部可用）",
                  sum(1 for l in text.splitlines() if " ERROR " in l) == 0,
                  next((l for l in text.splitlines() if " ERROR " in l), ""))
            if name == "accounting":
                lt = lst.get("trial") or {}
                check("local accounting 空库试算全 0（SUM 顶层投影 NULL→0 两端一致）",
                      lt.get("totalDebit") == 0 and lt.get("totalCredit") == 0 and lt.get("balanceSum") == 0
                      and lt.get("accounts") == 0 and lt.get("entries") == 0, str(lt))
    finally:
        for p in procs:
            try:
                p.send_signal(signal.SIGTERM)
                p.wait(timeout=20)
            except Exception:
                p.kill()

    print(f"\n结果：{PASSED} 通过，{FAILED} 失败")
    if FAILURES:
        print("失败项：" + "; ".join(FAILURES))
    raise SystemExit(1 if FAILED else 0)


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except BaseException as exc:  # 任何未预期异常都必须把资金域退回单库（绝不带着分片态离场）
        import traceback
        traceback.print_exc()
        rollback_and_exit(f"未预期异常 {type(exc).__name__}: {exc}")