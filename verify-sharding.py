#!/usr/bin/env python3
# ============================================================================
# payment-pro I-5a 验证：ShardingSphere-JDBC 试点（settle/recon 非资金域）+ 全局发号
#
# 用法：docker 全栈在线（./run.sh --docker）后 python3 verify-sharding.py
#   ① 全局发号 10 万 ID 无重号（两域各 5 万、8 线程并发；workerId 互斥 ⇒ ID 空间不相交）
#   ② settle 按 merchant 分片后 summary 净额 = 各片之和 = 原单表 = 账务恒等式
#   ③ recon_report 跨片分页不重不漏（ORDER BY/LIMIT 归并）
#   ④ 读写分离下结算登记后立即查可见（读己之写 hint 走主）
#   ⑤ 一键切回 payment.sharding.enabled=false 单库，查询/分页/汇总结果与切前一致（回滚）
#   ⑥ local H2 单库不加载任何 ShardingSphere Bean 也能跑（隔离 JVM，内存库，不碰 data/）
#
# 资金护航：每步必跑试算平衡（D=C、Σbalance=0）+ 六项对账 pass；不平即停并提示回滚。
# 数据保护：只新增业务行（真实支付链路），绝不删除/重置任何既有数据。
# ============================================================================
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

BASE = "http://127.0.0.1:8080"
SETTLE_ACT = "http://127.0.0.1:18089/actuator/ppshard"
RECON_ACT = "http://127.0.0.1:18091/actuator/ppshard"
LOCAL_SETTLE_PORT = int(os.environ.get("I5A_LOCAL_SETTLE_PORT", "18189"))
LOCAL_RECON_PORT = int(os.environ.get("I5A_LOCAL_RECON_PORT", "18191"))
JAVA_HOME = os.environ.get("JAVA_HOME", "/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home")
ROOT = os.path.dirname(os.path.abspath(__file__))
SUFFIX = uuid.uuid4().hex[:6]
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


def http(url, method="GET", body=None, bearer=False, timeout=60):
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


def poll(fn, pred, timeout=25, interval=0.4):
    deadline = time.time() + timeout
    val = fn()
    while time.time() < deadline and not pred(val):
        time.sleep(interval)
        val = fn()
    return val


def trial_guard(stage):
    """资金护航：试算平衡 D=C 且 Σbalance=0。不平立即返回 False（调用方停止并回滚）。"""
    t = ok("GET", "/api/accounting/trial")
    good = check(f"[{stage}] 试算平衡 D=C 且 Σbalance=0",
                 bool(t.get("balanced")) and t.get("balanceSum") == 0, str(t))
    return good, t


def recon_guard(stage):
    time.sleep(4)  # 等 outbox 末批投递（与 smoke-test 同口径，避免把在途投递误判为单边账）
    rep = ok("POST", "/api/recon/run")
    names = [c["name"] for c in rep["checks"]]
    check(f"[{stage}] 六项对账口径不变", len(rep["checks"]) == 6, str(names))
    for c in rep["checks"]:
        check(f"[{stage}] 对账：{c['name']}", c["pass"], c["detail"])
    latest = ok("GET", "/api/recon/latest")
    check(f"[{stage}] 对账报告持久化可回读（跨片 selectLatest）", latest.get("id") == rep["id"],
          f'{latest.get("id")} vs {rep["id"]}')
    return rep


def pay_once(tag, amount=3000):
    """真实业务链路：商户/用户开户 → 充值 → 下单 → 余额支付（TCC）→ outbox → 结算登记。"""
    merchant = ok("POST", "/api/members", {"name": f"I5A商户{tag}{SUFFIX}", "type": "MERCHANT"})
    user = ok("POST", "/api/members", {"name": f"I5A用户{tag}{SUFFIX}", "type": "USER"})
    ma = ok("POST", "/api/accounts", {"memberId": merchant["id"]})
    ua = ok("POST", "/api/accounts", {"memberId": user["id"]})
    ok("POST", "/api/accounting/internal/topup",
       {"accountId": ua["id"], "amount": amount * 3, "bizNo": f"i5a-topup-{tag}-{SUFFIX}"})
    order = ok("POST", "/api/orders",
               {"merchantId": merchant["id"], "subject": "I5A 分片验证", "amount": amount})["order"]
    r = ok("POST", "/api/cashier/pay", {"orderId": order["id"], "method": "BALANCE",
                                       "accountId": ua["id"], "requestId": f"i5a-req-{tag}-{SUFFIX}"})
    pid = r["payment"]["id"]
    p = poll(lambda: ok("GET", f"/api/payments/{pid}"), lambda x: x["status"] == "SUCCESS")
    assert p["status"] == "SUCCESS", f"支付未成功: {p}"
    return {"merchant": merchant, "ma": ma, "ua": ua, "order": order, "payment": p}


def settle_record_of(merchant_id, payment_id):
    return poll(lambda: next((x for x in ok("GET", f"/api/settle/records?merchantId={merchant_id}")
                              if x.get("paymentId") == payment_id and x["type"] == "PAY"), None),
                lambda x: x is not None)


def sql_evidence(log_path, call, logic_contains):
    """从 sql-show 日志抓取真实路由证据（Logic SQL → Actual SQL），返回 (调用结果, 命中块)。"""
    off = os.path.getsize(log_path) if os.path.exists(log_path) else 0
    result = call()
    time.sleep(0.4)
    with open(log_path, encoding="utf-8", errors="ignore") as f:
        f.seek(off)
        text = f.read()
    blocks, cur = [], None
    for line in text.splitlines():
        if "Logic SQL:" in line:
            if cur:
                blocks.append(cur)
            cur = {"logic": line.split("Logic SQL:", 1)[1].strip(), "actual": []}
        elif "Actual SQL:" in line and cur is not None:
            cur["actual"].append(line.split("Actual SQL:", 1)[1].strip())
    if cur:
        blocks.append(cur)
    return result, [b for b in blocks if logic_contains in b["logic"]]


SUMMARY_KEYS = ["payCount", "totalGross", "totalFee", "totalNet", "totalRefund"]


def summary_subset(d):
    return {k: d[k] for k in SUMMARY_KEYS}


def page_all(size=7, limit=2000):
    """跨片分页遍历：返回 (ids, 页数)。"""
    ids, offset, pages = [], 0, 0
    while offset < limit:
        batch = act(RECON_ACT, f"page/{offset}:{size}")
        pages += 1
        if not batch:
            break
        ids.extend(x["id"] for x in batch)
        if len(batch) < size:
            break
        offset += size
    return ids, pages


def start_local(module, port, dubbo_name):
    """隔离 local JVM：H2 内存库（不建/不碰任何 data/ 文件）、无注册中心、随机 Dubbo 端口。"""
    jar = os.path.join(ROOT, module, "target", f"{module}-1.0.0.jar")
    if not os.path.exists(jar):
        return None, f"jar 缺失: {jar}"
    cmd = [os.path.join(JAVA_HOME, "bin", "java"),
           f"-Dserver.port={port}",
           f"-Dspring.datasource.url=jdbc:h2:mem:i5a_{dubbo_name};MODE=MySQL;DATABASE_TO_LOWER=TRUE",
           "-Dspring.datasource.driver-class-name=org.h2.Driver",
           "-Dspring.datasource.username=sa",
           "-Dspring.datasource.password=",
           "-Dpayment.sharding.enabled=false",
           "-Dpayment.job.local.enabled=false",
           "-Dpayment.job.shedlock.enabled=false",
           "-Dpayment.job.xxl.enabled=false",
           "-Ddubbo.registry.address=zookeeper://127.0.0.1:2181",
           "-Ddubbo.registry.group=i5a-local-verify",
           "-Ddubbo.consumer.check=false",
           "-Ddubbo.protocol.port=-1",
           "-Ddubbo.application.qos-enable=false",
           "-jar", jar]
    log = open(os.path.join(ROOT, "logs", f"i5a-local-{dubbo_name}.log"), "w")
    proc = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT, cwd=ROOT)
    url = f"http://127.0.0.1:{port}/actuator/ppshard"
    for _ in range(90):
        if proc.poll() is not None:
            return proc, f"进程提前退出 exit={proc.returncode}"
        try:
            st, _ = http(url, timeout=3)
            if st == 200:
                return proc, None
        except Exception:
            pass
        time.sleep(1)
    return proc, "90s 未就绪"


def main():
    print(f"== payment-pro I-5a 分片试点 + 全局发号验证（批次 {SUFFIX}）==")
    _login()

    # ---------- 0. 前置 ----------
    print("[0] 前置：13/13 健康 + 分片装配态")
    health = poll(lambda: ok("GET", "/api/health"), lambda h: all(x["ok"] for x in h), timeout=90, interval=2)
    check("13 个进程健康", len(health) == 13 and all(x["ok"] for x in health), str(len(health)))
    check("控制台登录拿到 Bearer", bool(TOKEN))
    st_settle = act(SETTLE_ACT)
    st_recon = act(RECON_ACT)
    sharding_on = bool(st_settle.get("shardingConfigured")) and bool(st_recon.get("shardingConfigured"))
    if not check("settle/recon 已装配 ShardingSphere（docker 试点）", sharding_on,
                 f'settle={st_settle.get("shardingConfigured")} recon={st_recon.get("shardingConfigured")}'):
        print("!! 分片未装配：请确认 docker profile 且 PAYMENT_SHARDING_ENABLED 未被置 false")
        print(f"\n结果：{PASSED} 通过，{FAILED} 失败")
        raise SystemExit(1)
    check("settle 分片键=merchant_id / 4 片",
          st_settle.get("shardTables") == {"settle_record": "merchant_id"} and st_settle.get("shardCount") == 4,
          str(st_settle.get("shardTables")))
    check("recon 分片键=id / 4 片",
          st_recon.get("shardTables") == {"recon_report": "id"} and st_recon.get("shardCount") == 4,
          str(st_recon.get("shardTables")))
    check("读写分离已装配", bool(st_settle.get("readwriteSplitting")) and bool(st_recon.get("readwriteSplitting")))
    check("影子写回原单表已开启（回滚一致性前提）",
          bool(st_settle.get("mirrorLegacy")) and bool(st_recon.get("mirrorLegacy")))
    check("分片期取号走全局发号 SNOWFLAKE",
          st_settle.get("idMode") == "SNOWFLAKE" and st_recon.get("idMode") == "SNOWFLAKE")
    good, _ = trial_guard("前置")
    if not good:
        print("!! 试算不平，立即停止（不做任何分片操作）")
        raise SystemExit(1)

    # ---------- 1. 全局发号 10 万无重号 ----------
    print("[1] 全局发号：两域各 5 万、8 线程并发，合计 10 万")
    s_gen = act(SETTLE_ACT, body={"action": "idgen", "count": 50000, "threads": 8})
    r_gen = act(RECON_ACT, body={"action": "idgen", "count": 50000, "threads": 8})
    check("settle 5 万 ID 无重号", s_gen["distinct"] == 50000 and s_gen["duplicates"] == 0, str(s_gen))
    check("recon 5 万 ID 无重号", r_gen["distinct"] == 50000 and r_gen["duplicates"] == 0, str(r_gen))
    total_distinct = s_gen["distinct"] + r_gen["distinct"]
    check("合计 10 万 ID 生成", s_gen["requested"] + r_gen["requested"] == 100000, str(total_distinct))
    check("两域 workerId 互不相同（ID 空间不相交 ⇒ 10 万全局无重号）",
          s_gen["workerId"] != r_gen["workerId"], f'{s_gen["workerId"]} vs {r_gen["workerId"]}')
    check("settle ID 内 workerId 唯一且等于分配值", s_gen["workerIdsInIds"] == [s_gen["workerId"]],
          str(s_gen["workerIdsInIds"]))
    check("recon ID 内 workerId 唯一且等于分配值", r_gen["workerIdsInIds"] == [r_gen["workerId"]],
          str(r_gen["workerIdsInIds"]))
    check("workerId 由 ZK 临时节点/显式配置分配（非端口兜底）",
          s_gen["workerIdSource"] in ("ZOOKEEPER", "CONFIG") and r_gen["workerIdSource"] in ("ZOOKEEPER", "CONFIG"),
          f'{s_gen["workerIdSource"]}/{r_gen["workerIdSource"]}')
    check("workerId 在 0..1023 合法区间",
          all(0 <= g["workerId"] <= 1023 for g in (s_gen, r_gen)))
    check("ID 单调递增区间有效（雪花时间位）", s_gen["maxId"] > s_gen["minId"] > 0, str(s_gen["minId"]))
    check("ID 可读性保留前缀 S/RC",
          all(x.startswith("S") for x in s_gen["sample"]) and all(x.startswith("RC") for x in r_gen["sample"]),
          f'{s_gen["sample"]} {r_gen["sample"]}')
    check("ID 长度 ≤ 32（VARCHAR(32) 容纳）",
          all(len(x) <= 32 for x in s_gen["sample"] + r_gen["sample"]))
    print(f'      workerId: settle={s_gen["workerId"]}({s_gen["workerIdSource"]}) '
          f'recon={r_gen["workerId"]}({r_gen["workerIdSource"]})，'
          f'耗时 {s_gen["elapsedMs"]}ms/{r_gen["elapsedMs"]}ms')

    # ---------- 2. 既有数据原样保留 + 单片路由 ----------
    print("[2] 数据保护 + settle 单片路由")
    for st, logic in ((st_settle, "settle_record"), (st_recon, "recon_report")):
        rows = st["physicalRows"]
        shard_sum = sum(rows[f"{logic}_{k}"] for k in range(4))
        check(f"{logic} 原单表行数 = 各分片之和（既有行零删除）", rows[logic] == shard_sum,
              f'legacy={rows[logic]} shards={shard_sum}')
        check(f"{logic} 分片表已建成 4 张", all(f"{logic}_{k}" in rows for k in range(4)))
        check(f"{logic} 数据已分布到多片（非全落一片）",
              sum(1 for k in range(4) if rows[f"{logic}_{k}"] > 0) >= 2, str(rows))
    ctx = pay_once("shard")
    mid, pid = ctx["merchant"]["id"], ctx["payment"]["id"]
    sr = settle_record_of(mid, pid)
    check("outbox 投递生成结算单（分片期写入）", sr is not None, str(sr))
    check("分片期结算单 ID 为雪花形态 S+18~19 位",
          sr is not None and re.fullmatch(r"S\d{17,20}", sr["id"]) is not None, str(sr and sr["id"]))
    route = act(SETTLE_ACT, f"route/{mid}")
    dist = route["physicalDistribution"]
    expected = route["expectedShard"]
    check("该商户结算行只落单一物理分片",
          sum(1 for v in dist.values() if v > 0) == 1
          and dist[f"settle_record_{expected}"] == route["routedCount"], str(dist))
    check("records(merchantId) 返回行数 = 该片行数", route["routedCount"] == dist[f"settle_record_{expected}"],
          str(route["routedCount"]))
    pv = route.get("previewWriteRouteOnly", {})
    print(f'      PREVIEW 支持={pv.get("supported")}（5.5.0 JDBC 不支持 RAL PREVIEW，改用 sql-show 真实路由日志断言）')
    settle_log = os.path.join(ROOT, "logs", "settle.log")
    _, hits = sql_evidence(settle_log, lambda: act(SETTLE_ACT, f"records/{mid}"),
                           "FROM settle_record WHERE merchant_id=")
    check("真实路由日志：records(merchantId) 只下发 1 条 Actual SQL（单片路由）",
          bool(hits) and len(hits[-1]["actual"]) == 1, str(hits[-1:] if hits else "无日志"))
    if hits:
        actual = hits[-1]["actual"][0]
        check(f"单片路由落在 CRC32 期望分片 settle_record_{expected}", f"settle_record_{expected}" in actual, actual)
        check("读己之写：结算查询实际走主库 ds_write", actual.startswith("ds_write"), actual)
        check("单片路由未误扫其它 3 片",
              sum(1 for k in range(4) if f"settle_record_{k}" in actual) == 1, actual)

    # ---------- 3. 跨片 SUM 归并 + 账务恒等式 ----------
    print("[3] settle summary 跨片 SUM 归并 = 各片之和 = 原单表 = 账务恒等式")
    ss = act(SETTLE_ACT, "shardSummary")
    routed, shard_sum, legacy = summary_subset(ss["routed"]), summary_subset(ss["shardSum"]), summary_subset(ss["legacySingleTable"])
    check("summary() = 各片 SUM 之和（5 项逐项相等）", routed == shard_sum, f"{routed} vs {shard_sum}")
    check("summary() = 原单库单表聚合（5 项逐项相等）", routed == legacy, f"{routed} vs {legacy}")
    check("结算恒等式 net = gross - refund - fee",
          routed["totalNet"] == routed["totalGross"] - routed["totalRefund"] - routed["totalFee"], str(routed))
    check("跨片 COUNT 归并正确（payCount>0）", routed["payCount"] > 0, str(routed["payCount"]))
    _, sum_hits = sql_evidence(os.path.join(ROOT, "logs", "settle.log"),
                               lambda: act(SETTLE_ACT, "summary"), "SUM(gross) FROM settle_record")
    if sum_hits:
        fanout = " ".join(sum_hits[-1]["actual"])
        check("真实路由日志：SUM 下发到全部 4 片后归并（跨片聚合引擎生效）",
              all(f"settle_record_{k}" in fanout for k in range(4)), fanout[:300])
    # 手续费口径：与 smoke-test 同口径用「增量」对齐账务（绝对值存量差为历史遗留，非本次改造引入）
    print(f'      手续费净额={routed["totalFee"]} / SYS_FEE 余额={bal("SYS_FEE")}（存量差 {bal("SYS_FEE") - routed["totalFee"]} 分，历史遗留）')
    good, _ = trial_guard("跨片聚合")
    if not good:
        act(SETTLE_ACT, body={"action": "toggle", "enabled": False})
        act(RECON_ACT, body={"action": "toggle", "enabled": False})
        print("!! 试算不平，已回滚分片开关")
        raise SystemExit(1)

    # ---------- 4. recon_report 跨片分页不重不漏 ----------
    print("[4] recon_report 跨片分页（ORDER BY/LIMIT 归并）不重不漏")
    rep1 = recon_guard("分片期")
    check("分片期对账报告 ID 为雪花形态 RC+17~20 位", re.fullmatch(r"RC\d{17,20}", rep1["id"]) is not None, rep1["id"])
    phys = act(RECON_ACT, "physical/-")["tables"]
    legacy_rows = phys["recon_report"]
    shard_rows = [r for k in range(4) for r in phys[f"recon_report_{k}"]]
    legacy_ids = [r["id"] for r in legacy_rows]
    shard_ids = [r["id"] for r in shard_rows]
    check("原单表报告集合 = 各分片并集（不重不漏）",
          len(legacy_ids) == len(set(legacy_ids)) and set(legacy_ids) == set(shard_ids)
          and len(shard_ids) == len(set(shard_ids)), f"{len(legacy_ids)} vs {len(shard_ids)}")
    expected_order = []
    for _, grp in __import__("itertools").groupby(sorted(legacy_rows, key=lambda x: -int(x["checked_at"])),
                                                  key=lambda x: int(x["checked_at"])):
        expected_order.extend(r["id"] for r in sorted(grp, key=lambda x: x["id"], reverse=True))
    count = act(RECON_ACT)["reportCount"]
    check("跨片 COUNT(*) 归并 = 原单表行数", count == len(legacy_ids), f"{count} vs {len(legacy_ids)}")
    paged, pages = page_all(7)
    check("跨片分页无重复", len(paged) == len(set(paged)), f"{len(paged)} 行 / {len(set(paged))} 唯一")
    check("跨片分页无遗漏（总数 = COUNT）", len(paged) == count, f"{len(paged)} vs {count}")
    check("跨片分页顺序 = 全局 ORDER BY checked_at DESC,id DESC", paged == expected_order,
          f"首个差异: {next((i for i, (a, b) in enumerate(zip(paged, expected_order)) if a != b), 'none')}")
    check("分页确实跨多页（归并引擎生效）", pages >= 3, str(pages))
    mid_page = act(RECON_ACT, "page/13:9")
    check("中间页（offset 13）与全局序一致", [x["id"] for x in mid_page] == expected_order[13:22],
          str([x["id"] for x in mid_page][:3]))
    check("最新报告在首页首条（跨片 LIMIT 1 归并）", paged and paged[0] == rep1["id"], f"{paged[:1]} vs {rep1['id']}")
    rr = act(RECON_ACT, f"route/{rep1['id']}")
    rdist = rr["physicalDistribution"]
    check("报告按 id 哈希只落单片", sum(1 for v in rdist.values() if v > 0) == 1
          and rdist[f"recon_report_{rr['expectedShard']}"] == 1, str(rdist))

    # ---------- 5. 读写分离：结算登记后立即查可见 ----------
    print("[5] 读写分离读己之写：结算登记后立即查可见")
    before_hint = act(SETTLE_ACT)["writeRouteHintReads"]
    fee0 = bal("SYS_FEE")
    sum0 = summary_subset(act(SETTLE_ACT, "summary"))
    ctx2 = pay_once("ryw")
    sr2 = settle_record_of(ctx2["merchant"]["id"], ctx2["payment"]["id"])
    check("第二笔结算单登记成功", sr2 is not None, str(sr2))
    visible = []
    for _ in range(3):
        rows = act(SETTLE_ACT, f"records/{ctx2['merchant']['id']}")
        visible.append(any(x["id"] == sr2["id"] for x in rows))
    check("登记后立即连续 3 次查询均可见（读己之写走主，无副本延迟）", all(visible), str(visible))
    gateway_rows = ok("GET", f"/api/settle/records?merchantId={ctx2['merchant']['id']}")
    check("网关侧 records 同步可见（同一主库读）", any(x["id"] == sr2["id"] for x in gateway_rows))
    after_hint = act(SETTLE_ACT)["writeRouteHintReads"]
    check("读己之写 hint 实际生效（计数递增）", after_hint > before_hint, f"{before_hint} -> {after_hint}")
    sum1 = summary_subset(act(SETTLE_ACT, "summary"))
    check("结算笔数 +1", sum1["payCount"] - sum0["payCount"] == 1, f'{sum0["payCount"]} -> {sum1["payCount"]}')
    check("手续费净额增量 = SYS_FEE 余额增量", sum1["totalFee"] - sum0["totalFee"] == bal("SYS_FEE") - fee0)
    check("新商户余额 = 该笔结算净额（账务恒等式）", bal(ctx2["ma"]["id"]) == sr2["net"],
          f'{bal(ctx2["ma"]["id"])} vs {sr2["net"]}')
    check("结算净额增量 = 该笔结算净额", sum1["totalNet"] - sum0["totalNet"] == sr2["net"], str(sum1))
    ss2 = act(SETTLE_ACT, "shardSummary")
    check("新增行后 summary 仍 = 各片之和", summary_subset(ss2["routed"]) == summary_subset(ss2["shardSum"]))
    check("新增行后 summary 仍 = 原单表（影子写实时生效）",
          summary_subset(ss2["routed"]) == summary_subset(ss2["legacySingleTable"]),
          f'{summary_subset(ss2["routed"])} vs {summary_subset(ss2["legacySingleTable"])}')
    check("影子写失败数为 0", act(SETTLE_ACT)["mirrorFailures"] == 0 and act(RECON_ACT)["mirrorFailures"] == 0)
    fee1 = bal("SYS_FEE")
    good, _ = trial_guard("读己之写")
    if not good:
        act(SETTLE_ACT, body={"action": "toggle", "enabled": False})
        act(RECON_ACT, body={"action": "toggle", "enabled": False})
        print("!! 试算不平，已回滚分片开关")
        raise SystemExit(1)

    # ---------- 6. 回滚：一键切回单库，结果一致 ----------
    print("[6] 回滚验证：payment.sharding.enabled=false 等价开关，结果与切前一致")
    snap_summary = summary_subset(act(SETTLE_ACT, "summary"))
    snap_records = [x["id"] for x in act(SETTLE_ACT, f"records/{mid}")]
    snap_all_records = [x["id"] for x in act(SETTLE_ACT, "records/-")]
    snap_count = act(RECON_ACT)["reportCount"]
    snap_page, _ = page_all(7)
    t_off_s = act(SETTLE_ACT, body={"action": "toggle", "enabled": False})
    t_off_r = act(RECON_ACT, body={"action": "toggle", "enabled": False})
    check("settle 已切回单库", t_off_s["shardingActive"] is False and t_off_s["idMode"] == "SEQUENCE", str(t_off_s))
    check("recon 已切回单库", t_off_r["shardingActive"] is False and t_off_r["idMode"] == "SEQUENCE", str(t_off_r))
    check("切回后 summary 与切前逐项一致", summary_subset(act(SETTLE_ACT, "summary")) == snap_summary,
          str(summary_subset(act(SETTLE_ACT, "summary"))))
    check("切回后单商户 records 与切前一致", [x["id"] for x in act(SETTLE_ACT, f"records/{mid}")] == snap_records)
    check("切回后全量 records 与切前一致（顺序含）",
          [x["id"] for x in act(SETTLE_ACT, "records/-")] == snap_all_records)
    check("切回后报告总数一致", act(RECON_ACT)["reportCount"] == snap_count)
    back_page, _ = page_all(7)
    check("切回后跨片分页结果与切前逐条一致（不重不漏）", back_page == snap_page,
          f"{len(back_page)} vs {len(snap_page)}")
    check("切回后最新报告仍可回读", ok("GET", "/api/recon/latest").get("id") == rep1["id"])
    # 单库态下真实业务：取号必须回到 next_value_for（短 ID）
    ctx3 = pay_once("rollback")
    sr3 = settle_record_of(ctx3["merchant"]["id"], ctx3["payment"]["id"])
    check("单库态结算单 ID 回到序列形态 S+短数字（next_value_for）",
          sr3 is not None and re.fullmatch(r"S\d{1,10}", sr3["id"]) is not None, str(sr3 and sr3["id"]))
    rep2 = recon_guard("单库回滚态")
    check("单库态对账报告 ID 回到序列形态 RC+短数字", re.fullmatch(r"RC\d{1,10}", rep2["id"]) is not None, rep2["id"])
    sum_rb = summary_subset(act(SETTLE_ACT, "summary"))
    check("单库态 summary 仍 = 分片表聚合之和（双向 additive 复制无损）",
          sum_rb == summary_subset(act(SETTLE_ACT, "shardSummary")["shardSum"]), str(sum_rb))
    check("单库态结算恒等式仍成立",
          sum_rb["totalNet"] == sum_rb["totalGross"] - sum_rb["totalRefund"] - sum_rb["totalFee"], str(sum_rb))
    check("单库态手续费净额增量 = SYS_FEE 增量（口径不变）",
          sum_rb["totalFee"] - sum1["totalFee"] == bal("SYS_FEE") - fee1, str(sum_rb))
    good, _ = trial_guard("单库回滚态")
    if not good:
        print("!! 单库态试算不平，停止")
        raise SystemExit(1)
    # 再切回分片：双向一致
    act(SETTLE_ACT, body={"action": "toggle", "enabled": True})
    act(RECON_ACT, body={"action": "toggle", "enabled": True})
    check("再切回分片后 summary 与单库态一致", summary_subset(act(SETTLE_ACT, "summary")) == sum_rb,
          str(summary_subset(act(SETTLE_ACT, "summary"))))
    re_page, _ = page_all(7)
    check("再切回分片后分页含单库期新增报告（additive 复制回分片）", rep2["id"] in re_page,
          f'{rep2["id"]} in {len(re_page)} 行')
    check("再切回分片后分页仍无重复", len(re_page) == len(set(re_page)))
    ss3 = act(SETTLE_ACT, "shardSummary")
    check("往返切换后 summary = 各片之和 = 原单表",
          summary_subset(ss3["routed"]) == summary_subset(ss3["shardSum"]) == summary_subset(ss3["legacySingleTable"]))

    # ---------- 7. local H2 单库不加载 ShardingSphere ----------
    print("[7] local H2 单库兼容（隔离 JVM + 内存库，不触碰 data/ 与运行中的 docker 栈）")
    procs = []
    try:
        for module, port, name in (("payment-settle", LOCAL_SETTLE_PORT, "settle"),
                                   ("payment-recon", LOCAL_RECON_PORT, "recon")):
            proc, err = start_local(module, port, name)
            if proc:
                procs.append(proc)
            url = f"http://127.0.0.1:{port}/actuator/ppshard"
            if not check(f"local {name} 启动就绪（H2 内存库）", err is None, str(err)):
                continue
            st = act(url)
            check(f"local {name} 未装配任何 ShardingSphere Bean", st.get("shardingConfigured") is False, str(st))
            check(f"local {name} 取号仍走 next_value_for（SEQUENCE）", st.get("idMode") == "SEQUENCE", str(st))
            w = act(url, body={"action": "toggle", "enabled": True})
            check(f"local {name} 分片开关不可用（始终单库）", w.get("shardingConfigured") is False, str(w))
            if name == "settle":
                s = st["summary"]
                check("local settle 空表 summary 全 0（SUM NULL→0 两端一致）",
                      all(s[k] == 0 for k in SUMMARY_KEYS), str(s))
                check("local settle records 查询可用（mapper SQL 两端通用）", act(url, "records/-") == [])
            else:
                check("local recon 报告数 0", st.get("reportCount") == 0, str(st.get("reportCount")))
                check("local recon 跨片分页 SQL 在 H2 单表可跑", act(url, "page/0:5") == [])
            logfile = os.path.join(ROOT, "logs", f"i5a-local-{name}.log")
            text = open(logfile, encoding="utf-8", errors="ignore").read()
            check(f"local {name} 日志无 ShardingSphere 装配痕迹",
                  "ShardingSphere" not in text and "I-5a 分片试点装配" not in text,
                  next((l for l in text.splitlines() if "ShardingSphere" in l), ""))
    finally:
        for p in procs:
            try:
                p.send_signal(signal.SIGTERM)
                p.wait(timeout=20)
            except Exception:
                p.kill()

    # ---------- 8. 收尾资金护航 ----------
    print("[8] 收尾：试算平衡 + 六项对账 + 数据保护复核")
    good, _ = trial_guard("收尾")
    recon_guard("收尾")
    final_settle = act(SETTLE_ACT)
    final_recon = act(RECON_ACT)
    for st, logic in ((final_settle, "settle_record"), (final_recon, "recon_report")):
        rows = st["physicalRows"]
        check(f"{logic} 收尾：原单表行数 = 各分片之和（全程零删除）",
              rows[logic] == sum(rows[f"{logic}_{k}"] for k in range(4)), str(rows))
    check("全程影子写零失败", final_settle["mirrorFailures"] == 0 and final_recon["mirrorFailures"] == 0)
    check("收尾处于分片试点态（docker 默认）",
          final_settle["shardingActive"] and final_recon["shardingActive"])

    print(f"\n结果：{PASSED} 通过，{FAILED} 失败")
    if FAILURES:
        print("失败项：" + "; ".join(FAILURES))
    raise SystemExit(1 if FAILED else 0)


if __name__ == "__main__":
    main()