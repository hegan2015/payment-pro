#!/usr/bin/env python3
"""verify-recon.py — P1-J 对账工作流验证：RC-2 日切批量对账 / RC-3 渠道内外对账 /
RC-4 对账/差错报表 / RC-5 分片调度对账。

跑法: python3 verify-recon.py   （payment-pro 栈需已启动，gateway 8080；默认 PP_PROFILE=docker）
      PP_PROFILE=local 时跳过 MySQL 对照段（local H2 文件库进程独占），API 段照跑。

断言覆盖:
  [1] 六项对账结构与判定口径不变（POST /api/recon/run 六项全过）+ 试算 D=C 且 Σbalance=0
  [2] 主会话 FAILED 单语义校验仍在：P360（FAILED 却留账务凭证）被检出 + MANUAL 差错单，
      实时与日切双口径均检出，且不翻聚合 pass
  [3] RC-2 日切批量：批次状态机 + 同会计日幂等重跑不重复建单（批次行/差错单不增，attempt 递增）
  [4] RC-5 分片调度：pprecon 计划并集=全量交集=空；日切分片各片计数和=全量、进度 Σchecked=Σtotal；
      全局聚合项只由分片 0 核对；同片并发 ShedLock 收敛；失败/重跑幂等；XXL 分片广播记录待启用
  [5] RC-3 渠道内外：CH-4 账单逐笔比对基线一致；故障注入金额不符/长款/短款三类差异分类建单
      + 处置建议；幂等重跑差异行/差错单不增；手续费口径如实披露
  [6] RC-4 报表：跨片分页归并对照 MySQL 物理表不重不漏；summary 四维聚合与 SQL 对照；
      差错时效阶段时长；三类 CSV 导出
  [7] 收尾门禁：六项仍全过 + 试算平衡

造差异手段（合法、additive）：新建 RC3 前缀银行卡支付单（真实业务链路）+ CH-4 账单故障注入
（只影响模拟银行账单文件，不动任何业务数据），完毕复位。不删改任何既有行。
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
import zlib
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone

BASE = os.environ.get("PP_GATEWAY", "http://127.0.0.1:8080")
RECON = os.environ.get("PP_RECON_ACTUATOR", "http://127.0.0.1:18091")
OPS = os.environ.get("PP_CHANNEL_OPS", "http://127.0.0.1:18087")
PROFILE = os.environ.get("PP_PROFILE", "docker")
SUFFIX = uuid.uuid4().hex[:8]
P = "RC3" + SUFFIX
ZONE = timezone(timedelta(hours=8))  # Asia/Shanghai（与 accounting 会计日同口径）
TOKEN = None
PASSED = FAILED = SKIPPED = 0
MYSQL_OK = False
CARD = "6222020202020202"


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


def req(method, path, body=None, base=None, timeout=120):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request((base or BASE) + path, data=data, method=method)
    r.add_header("Accept", "application/json")
    if data is not None:
        r.add_header("Content-Type", "application/json")
    if TOKEN:
        r.add_header("Authorization", "Bearer " + TOKEN)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            text = resp.read().decode()
            ctype = resp.headers.get("Content-Type", "")
            if "text/csv" in ctype:
                return resp.status, text
            return resp.status, json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        try:
            parsed = json.loads(text)
        except Exception:
            parsed = {"error": text}
        return e.code, parsed
    except Exception as e:
        return 0, {"error": str(e)}


def ok(method, path, body=None, base=None):
    st, resp = req(method, path, body, base)
    assert st == 200, f"{method} {path} 期望 200，实际 {st}: {str(resp)[:300]}"
    return resp


def login():
    global TOKEN
    pwd = os.environ.get("PP_ADMIN_PASSWORD", "admin123")
    st, resp = req("POST", "/api/auth/login", {"username": "admin", "password": pwd})
    if st == 200 and isinstance(resp, dict):
        TOKEN = resp.get("token") or resp.get("accessToken") or resp.get("access_token")
        print(f"== 认证: login 200，Bearer 模式 ==")
    else:
        print(f"== 认证: login {st}，无鉴权模式 ==")


def _docker_bin():
    p = "/Users/hegan/.docker/bin/docker"
    return p if os.path.exists(p) else "docker"


def _mysql_creds():
    user, pwd = "root", ""
    path = os.path.expanduser("~/.config/payment-pro/docker.env")
    if os.path.exists(path):
        with open(path) as f:
            for line in f:
                line = line.strip()
                if line.startswith("MYSQL_USER="):
                    user = line.split("=", 1)[1]
                elif line.startswith("MYSQL_PASSWORD="):
                    pwd = line.split("=", 1)[1]
    return user, pwd


def sql(statement, db="pp_recon"):
    user, pwd = _mysql_creds()
    env = dict(os.environ)
    env["MYSQL_PWD"] = pwd  # 不回显口令
    container = os.environ.get("PP_MYSQL_CONTAINER", "claim-shared-mysql")
    r = subprocess.run(
        [_docker_bin(), "exec", "-e", "MYSQL_PWD", container,
         "mysql", f"-u{user}", "-N", "-B", "-e", statement, db],
        capture_output=True, text=True, env=env, timeout=60)
    if r.returncode != 0:
        raise RuntimeError(f"mysql 执行失败: {r.stderr.strip()[:200]}")
    return r.stdout.strip()


def sql_rows(statement, db="pp_recon"):
    out = sql(statement, db)
    return [line.split("\t") for line in out.splitlines() if line]


def probe_db():
    global MYSQL_OK
    if PROFILE != "docker":
        print(f"== profile={PROFILE}：跳过 MySQL 对照段 ==")
        return
    try:
        sql("SELECT 1")
        MYSQL_OK = True
        print("== MySQL 可用：跨片归并/物理表对照段启用 ==")
    except Exception as e:
        print(f"== MySQL 不可用（{e}）：对照段 SKIP ==")


def day_of(millis):
    return int(datetime.fromtimestamp(millis / 1000, ZONE).replace(
        hour=0, minute=0, second=0, microsecond=0).timestamp() * 1000)


def day_text(millis):
    return datetime.fromtimestamp(millis / 1000, ZONE).strftime("%Y%m%d")


def today():
    return day_of(int(time.time() * 1000))


def trigger(name, shard_index=0, shard_total=1, param=""):
    body = {"shardIndex": shard_index, "shardTotal": shard_total, "param": param if param is not None else ""}
    return req("POST", f"/actuator/ppjob/{name}", body, base=RECON, timeout=180)


def ppjob():
    return ok("GET", "/actuator/ppjob", base=RECON)


def ops(method, path, body=None):
    return req(method, path, body, base=OPS)


def dispute_stats():
    return ok("GET", "/api/disputes/stats")


def find_disputes(biz_no, source="RECON"):
    resp = ok("GET", f"/api/disputes?source={source}&bizNo={biz_no}&size=50")
    return resp.get("items") or []


def wait_payment(pid, want="SUCCESS", timeout=90):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        st, p = req("GET", f"/api/payments/{pid}")
        if st == 200 and isinstance(p, dict):
            last = p
            if p.get("status") == want:
                return p
            if p.get("status") in ("FAILED", "REFUNDED") and want == "SUCCESS":
                return p
        time.sleep(1)
    return last


def wait_settle(pid, timeout=30):
    deadline = time.time() + timeout
    while time.time() < deadline:
        recs = ok("GET", "/api/settle/records")
        if any(r.get("bizId") == pid and r.get("type") == "PAY" for r in recs):
            return True
        time.sleep(1)
    return False


def make_card_payment(tag, amount=2000):
    """真实业务链路：商户/用户开户 → 充值 → 下单 → 银行卡支付（渠道 TCC）。"""
    merchant = ok("POST", "/api/members", {"name": f"J商户{tag}{SUFFIX}", "type": "MERCHANT"})
    user = ok("POST", "/api/members", {"name": f"J用户{tag}{SUFFIX}", "type": "USER"})
    ma = ok("POST", "/api/accounts", {"memberId": merchant["id"]})
    ua = ok("POST", "/api/accounts", {"memberId": user["id"]})
    ok("POST", "/api/accounting/internal/topup",
       {"accountId": ua["id"], "amount": 100000, "bizNo": P + tag + "-topup"})
    order = ok("POST", "/api/orders",
               {"merchantId": merchant["id"], "subject": f"J对账商品{tag}", "amount": amount})["order"]
    r = ok("POST", "/api/cashier/pay",
           {"orderId": order["id"], "method": "BANKCARD", "cardNo": CARD, "requestId": P + tag + "-req"})
    p = wait_payment(r["payment"]["id"])
    return p, ma, merchant


def six_checks(rep):
    return [c["name"] for c in rep.get("checks", [])]


EXPECTED_CHECKS = ["账务试算平衡", "支付单采集", "支付-账务一致", "支付-交易一致", "支付-清结算一致", "支付-渠道一致"]


def main():
    login()
    probe_db()
    st, health = req("GET", "/api/health")
    check("网关健康 13/13", st == 200 and isinstance(health, list) and len(health) == 13
          and all(x.get("ok") for x in health), str(health)[:200])

    payments = ok("GET", "/api/payments")
    p360 = next((p for p in payments if p["id"] == "P360"), None)

    # ---------- 1. 六项结构与判定口径不变 ----------
    print("[1] 六项对账结构与判定口径不变（ride 红线）")
    time.sleep(4)  # 等 outbox 末批投递
    rep = ok("POST", "/api/recon/run")
    check("POST /api/recon/run 六项结构不变", six_checks(rep) == EXPECTED_CHECKS, str(six_checks(rep)))
    check("六项全过", rep.get("pass") is True and all(c["pass"] for c in rep["checks"]), str(rep)[:300])
    trial = ok("GET", "/api/accounting/trial")
    check("试算 D=C 且 Σbalance=0", trial.get("balanced") is True and trial.get("balanceSum") == 0, str(trial))
    latest = ok("GET", "/api/recon/latest")
    check("latest 回读同一报告且六项", latest.get("id") == rep["id"] and len(latest.get("checks", [])) == 6,
          str(latest)[:200])
    combo = [p for p in payments if p.get("method") == "COMBO" and p.get("status") == "SUCCESS"]
    if combo:
        acct = next(c for c in rep["checks"] if c["name"] == "支付-账务一致")
        combo_ids = {p["id"] for p in combo}
        check("COMBO 主单按腿归集核对（折进既有项，无假单边账）",
              acct["pass"] is True and not (combo_ids & set(acct.get("missingBizNos") or [])),
              f'combo={combo_ids} missing={acct.get("missingBizNos")}')
    else:
        skip("COMBO 按腿归集段", "栈内无 SUCCESS COMBO 支付单")

    # ---------- 2. FAILED 单语义校验保留 + P360 检出建单 ----------
    print("[2] FAILED 单语义校验（主会话盲区补齐）保留且检出 P360")
    if p360 is None:
        skip("P360 检出段", "栈内无 P360 支付单")
    else:
        check("P360 为 FAILED 单", p360["status"] == "FAILED", str(p360)[:200])
        st, tr = req("GET", "/api/accounting/transfers/P360")
        dirty = st == 200 and isinstance(tr, dict) and len(tr.get("lines") or []) > 0
        check("P360 FAILED 却留账务凭证（脏单前提成立）", dirty, str(tr)[:200])
        acct = next(c for c in rep["checks"] if c["name"] == "支付-账务一致")
        check("实时对账检出 FAILED 已动账未冲正（不翻 pass）",
              acct["pass"] is True and "FAILED" in acct.get("detail", "")
              and "P360" in (acct.get("missingBizNos") or []), str(acct)[:300])
        dsp = find_disputes("P360")
        check("P360 语义违规建 MANUAL 差错单（不自动动账）",
              any(d.get("strategy") == "MANUAL" and d.get("checkName") == "支付-账务一致" for d in dsp),
              str(dsp)[:300])
        # 日切口径同样检出（按 P360 归日）
        d360 = day_of(p360["createdAt"])
        rep_d = ok("POST", f"/api/recon/daily?bizDate={d360}")
        check("日切对账六项结构一致", six_checks(rep_d) == EXPECTED_CHECKS, str(six_checks(rep_d)))
        acct_d = next(c for c in rep_d["checks"] if c["name"] == "支付-账务一致")
        check("日切口径同样检出 P360（不翻 pass）",
              acct_d["pass"] is True and "P360" in (acct_d.get("missingBizNos") or []), str(acct_d)[:300])
        dsp_d = [d for d in find_disputes("P360")
                 if d.get("checkName") == "支付-账务一致" and d.get("bizDate") in (day_text(d360), day_text(today()))]
        check("日切口径 P360 差错单带会计日维度（未关闭同来源单幂等复用）", len(dsp_d) >= 1, str(dsp_d)[:200])

    # ---------- 3. RC-2 日切批量：状态机 + 幂等重跑 ----------
    print("[3] RC-2 日切批量对账：批次状态机 + 同会计日幂等重跑")
    day = day_of(p360["createdAt"]) if p360 else today()
    batches0 = ok("GET", f"/api/recon/batches?bizDate={day}&reconType=DAILY")
    rows0 = [b for b in batches0 if b["shardTotal"] == 1]
    stats0 = dispute_stats()["total"]
    reports0 = int(sql("SELECT COUNT(*) FROM recon_report")) if MYSQL_OK else None
    r1 = ok("POST", f"/api/recon/daily?bizDate={day}")
    check("日切报告带 bizDate/reconType（additive）", r1.get("bizDate") == day and r1.get("reconType") == "DAILY",
          str({k: r1.get(k) for k in ('bizDate', 'reconType')})[:200])
    check("日切六项全过（语义违规不翻 pass）", r1.get("pass") is True, str(r1)[:300])
    stats1 = dispute_stats()["total"]
    batches1 = ok("GET", f"/api/recon/batches?bizDate={day}&reconType=DAILY")
    rows1 = [b for b in batches1 if b["shardTotal"] == 1]
    check("批次行建立且状态 SUCCESS", len(rows1) == 1 and rows1[0]["status"] == "SUCCESS"
          and rows1[0]["reportId"] == r1["id"], str(rows1)[:300])
    r2 = ok("POST", f"/api/recon/daily?bizDate={day}")
    batches2 = ok("GET", f"/api/recon/batches?bizDate={day}&reconType=DAILY")
    rows2 = [b for b in batches2 if b["shardTotal"] == 1]
    stats2 = dispute_stats()["total"]
    check("同会计日重跑不重复建批次行", len(rows2) == 1 and rows2[0]["id"] == rows1[0]["id"], str(rows2)[:300])
    check("重跑 attempt 递增（幂等续跑）", rows2[0]["attempt"] >= rows1[0]["attempt"] + 1,
          f'{rows1[0]["attempt"]}->{rows2[0]["attempt"]}')
    check("重跑不重复建差错单", stats2 == stats1, f"{stats1}->{stats2}")
    check("重跑报告为新执行留痕（报告可增，单不增）", r2["id"] != r1["id"], f'{r1["id"]}/{r2["id"]}')
    if MYSQL_OK:
        reports2 = int(sql("SELECT COUNT(*) FROM recon_report"))
        check("两次日切各落一份报告（+2）", reports2 - reports0 == 2, f"{reports0}->{reports2}")
        cols = sql_rows("SELECT COLUMN_NAME FROM information_schema.columns "
                        "WHERE table_schema='pp_recon' AND table_name='recon_report' "
                        "AND column_name IN ('biz_date','recon_type','channel_code')")
        check("recon_report additive 列就绪（单表）", len(cols) == 3, str(cols))
        phys = sql_rows("SELECT table_name FROM information_schema.tables WHERE table_schema='pp_recon' "
                        "AND table_name LIKE 'recon_report\\_%'")
        ok_cols = True
        for (t,) in phys:
            c = sql_rows(f"SELECT COLUMN_NAME FROM information_schema.columns WHERE table_schema='pp_recon' "
                         f"AND table_name='{t}' AND column_name IN ('biz_date','recon_type','channel_code')")
            ok_cols &= len(c) == 3
        check("分片物理表 additive 列同步补齐", ok_cols, str(phys))
    # 当前账面会计日（可能已被手动日切推进）空跑不报错
    cur = ok("GET", "/api/accounting/day")
    r_cur = ok("POST", "/api/recon/daily")
    check("缺省=当前账面会计日（不硬编码当前日期）", r_cur.get("bizDate") == cur.get("bizDate")
          and len(r_cur.get("checks", [])) == 6, f'cur={cur.get("bizDate")} rep={r_cur.get("bizDate")}')

    # ---------- 4. RC-5 分片调度：不重不漏 + 锁 + 重跑 ----------
    print("[4] RC-5 分片调度对账：不重不漏 + 全局项分片 0 + ShedLock 收敛")
    time.sleep(4)  # 等在途单收敛/outbox 末批投递，保证计数快照稳定
    payments = ok("GET", "/api/payments")  # 重取快照（避免前序段新增单影响计数）
    st, plan3 = req("GET", "/actuator/pprecon?shardTotal=3", base=RECON)
    check("pprecon 分片计划可达", st == 200 and plan3 is not None, str(plan3)[:200])
    sets = [set(s["paymentIds"]) for s in plan3["shards"]]
    union = set().union(*sets)
    all_success = {p["id"] for p in payments if p["status"] == "SUCCESS"}
    check("分片并集=全量 SUCCESS（不漏）", union == all_success, f"union={len(union)} all={len(all_success)}")
    check("分片两两不相交（不重）", sum(len(s) for s in sets) == len(union),
          f"sum={sum(len(s) for s in sets)} union={len(union)}")
    check("每片归属 CRC32 取模一致",
          all(all(zlib.crc32(i.encode()) % 3 == s["shardIndex"] for i in s["paymentIds"]) for s in plan3["shards"]))
    scoped = [p for p in payments if p["status"] in ("SUCCESS", "FAILED") and day_of(p["createdAt"]) == day]
    expect = [0, 0, 0]
    for p in scoped:
        expect[zlib.crc32(p["id"].encode()) % 3] += 1
    reports_b = int(sql("SELECT COUNT(*) FROM recon_report")) if MYSQL_OK else None
    for i in range(3):
        stt, res = trigger("reconDaily", i, 3, str(day))
        check(f"日切分片 {i}/3 触发成功", stt == 200 and res.get("success") is True, str(res)[:200])
    prog = ok("GET", f"/api/recon/progress?bizDate={day}&reconType=DAILY")
    shards3 = [s for s in prog["shards"] if s["shardTotal"] == 3]
    check("各片进度行齐备（3 片）", len(shards3) == 3, str(prog)[:300])
    check("各片 total 与 CRC32 归属计数一致（不重不漏）",
          sorted(s["total"] for s in shards3) == sorted(expect), f'{[s["total"] for s in shards3]} vs {expect}')
    check("进度 Σchecked=Σtotal=日切全量", prog["total"] == len(scoped) and prog["checked"] == prog["total"]
          and prog["percent"] == 100, f'total={prog["total"]} checked={prog["checked"]} scoped={len(scoped)}')
    batches3 = [b for b in ok("GET", f"/api/recon/batches?bizDate={day}&reconType=DAILY") if b["shardTotal"] == 3]
    check("分片批次行各片唯一（3 行）", len(batches3) == 3
          and {b["shardIndex"] for b in batches3} == {0, 1, 2}, str(batches3)[:300])
    b0 = next(b for b in batches3 if b["shardIndex"] == 0)
    b1 = next(b for b in batches3 if b["shardIndex"] == 1)
    rep0 = ok("GET", f"/api/recon/reports/{b0['reportId']}")
    rep1 = ok("GET", f"/api/recon/reports/{b1['reportId']}")
    check("全局聚合项（试算平衡）只由分片 0 核对",
          "D=C" in rep0["checks"][0].get("detail", "") and "跳过" in rep1["checks"][0].get("detail", ""),
          f's0={rep0["checks"][0].get("detail")[:60]} s1={rep1["checks"][0].get("detail")[:60]}')
    check("分片报告六项结构不变", six_checks(rep0) == EXPECTED_CHECKS and six_checks(rep1) == EXPECTED_CHECKS)
    if MYSQL_OK:
        reports_a = int(sql("SELECT COUNT(*) FROM recon_report"))
        check("逐片报告落库（+3）", reports_a - reports_b == 3, f"{reports_b}->{reports_a}")
        locks = {r[0] for r in sql_rows("SELECT name FROM shedlock WHERE name LIKE 'reconDaily:%'")}
        check("逐片独立锁名 reconDaily:<day>:<i>/<n>",
              {f"reconDaily:{day}:0/3", f"reconDaily:{day}:1/3", f"reconDaily:{day}:2/3"} <= locks, str(locks))
    # 同片并发触发：ShedLock 收敛（acquired+skipped 计数），批次行不重复
    snap0 = ppjob()["jobs"]["reconDaily"]
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(lambda _: trigger("reconDaily", 0, 3, str(day)), range(4)))
    snap1 = ppjob()["jobs"]["reconDaily"]
    check("同片并发触发被 ShedLock 收敛（多实例不重复）",
          snap1["lockAcquired"] + snap1["lockSkipped"] >= snap0["lockAcquired"] + snap0["lockSkipped"] + 4,
          f"{snap0}->{snap1}")
    batches3b = [b for b in ok("GET", f"/api/recon/batches?bizDate={day}&reconType=DAILY") if b["shardTotal"] == 3]
    check("并发/重跑后分片批次行仍各片唯一", len(batches3b) == 3, str(len(batches3b)))
    stats_shard = dispute_stats()["total"]
    stt, res = trigger("reconDaily", 0, 3, str(day))
    check("分片重跑幂等成功（故障可续跑）", stt == 200 and res.get("success") is True, str(res)[:200])
    check("分片重跑不重复建差错单", dispute_stats()["total"] == stats_shard)
    if MYSQL_OK:
        xxl = sql_rows("SELECT executor_route_strategy, trigger_status FROM xxl_job_info "
                       "WHERE executor_handler='reconDaily'", db="xxl_job")
        check("XXL 分片广播记录就绪且默认停用（不自动跑批）",
              len(xxl) == 1 and xxl[0][0] == "SHARDING_BROADCAST" and xxl[0][1] == "0", str(xxl))
        xxl_c = sql_rows("SELECT executor_handler, trigger_status FROM xxl_job_info "
                         "WHERE executor_handler='reconChannel'", db="xxl_job")
        check("XXL reconChannel 记录就绪且默认停用", len(xxl_c) == 1 and xxl_c[0][1] == "0", str(xxl_c))
        handlers = ppjob()["handlers"]
        check("reconDaily/reconChannel 注册进调度注册表",
              "reconDaily" in handlers and "reconChannel" in handlers, str(handlers))

    # ---------- 5. RC-3 渠道内外对账 ----------
    print("[5] RC-3 渠道内外对账：逐笔比对 + 长款/短款/金额分类建单 + 处置建议")
    p1, ma1, m1 = make_card_payment("A", 2000)
    p2, _, _ = make_card_payment("B", 3000)
    p3, _, _ = make_card_payment("C", 1500)
    check("三笔银行卡支付 SUCCESS（真实渠道链路）",
          all(p and p.get("status") == "SUCCESS" for p in (p1, p2, p3)),
          str([p and p.get("status") for p in (p1, p2, p3)]))
    check("结算单齐备（outbox 投递）", all(wait_settle(p["id"]) for p in (p1, p2, p3)))
    t = today()
    # CH-3 路由择优可能选中备用渠道：按实际路由渠道注入故障与比对（不硬编码 MOCK_BANK）
    def trade_channel(pid):
        st, tr = req("GET", f"/api/channel/trades/{pid}")
        return tr.get("channelCode") if st == 200 and isinstance(tr, dict) else None
    codes = {trade_channel(p["id"]) for p in (p1, p2, p3)}
    check("三笔支付路由同一渠道（故障注入靶点一致）", len(codes) == 1 and None not in codes, str(codes))
    code = next(iter(codes)) if len(codes) == 1 and None not in codes else "MOCK_BANK"
    print(f"  ==> RC-3 目标渠道: {code}")
    ops("POST", f"/channel-ops/faults/bill/{code}/reset")
    base = ok("POST", f"/api/recon/channel?channelCode={code}&bizDate={t}")
    check("CH-4 账单口径接入（source=BILL）", base.get("source") == "BILL" and base.get("billId"),
          str({k: base.get(k) for k in ('source', 'billId', 'message')})[:300])
    check("无故障基线逐笔一致（pass）", base.get("pass") is True and base.get("matchedCount", 0) >= 3,
          str({k: base.get(k) for k in ('pass', 'matchedCount', 'diffs')})[:300])
    check("手续费口径如实披露（feeNote）", bool(base.get("feeNote")), str(base.get("feeNote"))[:200])
    # 注入三类账单差异（只改账单文件，不动业务数据）
    long_biz = P + "LONG"
    extra = f"{long_biz}-CO,{long_biz},PAY,500,CAPTURED,{int(time.time() * 1000)}"
    ops("POST", f"/channel-ops/faults/bill/{code}",
        {"amountBizNo": p1["id"], "amountDelta": 100, "extraRows": [extra], "dropBizNo": p2["id"]})
    inj = ok("POST", f"/api/recon/channel?channelCode={code}&bizDate={t}")
    diffs = {d["diffType"]: d for d in inj.get("diffs", []) if d["bizNo"] in (p1["id"], p2["id"], long_biz)}
    check("金额不符分类 AMOUNT（账单多 100 分）",
          "AMOUNT" in diffs and diffs["AMOUNT"]["externalAmount"] == diffs["AMOUNT"]["internalAmount"] + 100,
          str(diffs.get("AMOUNT"))[:300])
    check("长款分类 LONG（外部有内部无）", "LONG" in diffs and diffs["LONG"]["bizNo"] == long_biz,
          str(diffs.get("LONG"))[:300])
    check("短款分类 SHORT（内部有账单无）", "SHORT" in diffs and diffs["SHORT"]["bizNo"] == p2["id"],
          str(diffs.get("SHORT"))[:300])
    check("三类差异均建差错单", all(diffs[k].get("disputeId") for k in ("AMOUNT", "LONG", "SHORT")),
          str({k: diffs[k].get("disputeId") for k in diffs})[:200])
    check("处置建议齐备（长款挂账/短款挂账/金额人工）",
          all(diffs[k].get("suggestion") for k in ("AMOUNT", "LONG", "SHORT"))
          and "挂账" in diffs["LONG"]["suggestion"] and "挂账" in diffs["SHORT"]["suggestion"],
          str({k: diffs[k].get("suggestion") for k in diffs})[:300])
    d_long = ok("GET", f"/api/disputes/{diffs['LONG']['disputeId']}")
    d_short = ok("GET", f"/api/disputes/{diffs['SHORT']['disputeId']}")
    d_amt = ok("GET", f"/api/disputes/{diffs['AMOUNT']['disputeId']}")
    check("长款 ONE_SIDED/SUSPENSE、短款 CHANNEL_MISSING/SUSPENSE、金额 AMOUNT_MISMATCH/MANUAL",
          d_long["type"] == "ONE_SIDED" and d_long["strategy"] == "SUSPENSE"
          and d_short["type"] == "CHANNEL_MISSING" and d_short["strategy"] == "SUSPENSE"
          and d_amt["type"] == "AMOUNT_MISMATCH" and d_amt["strategy"] == "MANUAL",
          str([(d_long["type"], d_long["strategy"]), (d_short["type"], d_short["strategy"]),
               (d_amt["type"], d_amt["strategy"])]))
    check("差错单来源 RECON/渠道内外对账/会计日维度",
          all(d["source"] == "RECON" and d["checkName"] == "渠道内外对账" and d["bizDate"] == day_text(t)
              for d in (d_long, d_short, d_amt)))
    check("汇总校验 netDiff=外部总额-内部总额", inj.get("netDiff") == inj["externalTotal"] - inj["internalTotal"],
          str({k: inj.get(k) for k in ('netDiff', 'externalTotal', 'internalTotal')}))
    check("差异计数分类正确", inj["longCount"] >= 1 and inj["shortCount"] >= 1 and inj["amountCount"] >= 1
          and inj["pass"] is False, str({k: inj.get(k) for k in ('longCount', 'shortCount', 'amountCount', 'pass')}))
    # 幂等重跑：差异行/差错单不增，run 行复用
    diffs_before = ok("GET", f"/api/recon/channel-diffs?channelCode={code}&bizDate={t}")
    stats_before = dispute_stats()["total"]
    inj2 = ok("POST", f"/api/recon/channel?channelCode={code}&bizDate={t}")
    diffs_after = ok("GET", f"/api/recon/channel-diffs?channelCode={code}&bizDate={t}")
    check("重跑 run 行复用（同一 id）", inj2.get("id") == inj.get("id"), f'{inj.get("id")}/{inj2.get("id")}')
    check("重跑差异行不增（唯一键去重）", len(diffs_after) == len(diffs_before),
          f"{len(diffs_before)}->{len(diffs_after)}")
    check("重跑差错单不增（幂等键去重）", dispute_stats()["total"] == stats_before)
    # 复位故障 → 干净账单重比对（历史差异行保留留痕，本轮无新差异）
    ops("POST", f"/channel-ops/faults/bill/{code}/reset")
    clean = ok("POST", f"/api/recon/channel?channelCode={code}&bizDate={t}")
    check("故障复位后逐笔一致（pass 恢复）", clean.get("pass") is True and len(clean.get("diffs", [])) == 0,
          str({k: clean.get(k) for k in ('pass', 'diffs')})[:300])
    check("历史差异行保留留痕（不删既有行）",
          len(ok("GET", f"/api/recon/channel-diffs?channelCode={code}&bizDate={t}")) == len(diffs_after))

    # ---------- 6. RC-4 报表：跨片归并 + 聚合对照 + 时效 + CSV ----------
    print("[6] RC-4 对账/差错报表：跨片归并 + 聚合对照 + 时效 + CSV")
    page1 = ok("GET", "/api/recon/reports?page=1&size=50")
    total = page1["total"]
    ids, page = [], 1
    while True:
        pg = ok("GET", f"/api/recon/reports?page={page}&size=50")
        items = pg["items"]
        ids.extend(r["id"] for r in items)
        if len(ids) >= total or not items:
            break
        page += 1
    check("跨片分页不重（id 唯一）", len(ids) == len(set(ids)), f"{len(ids)}/{len(set(ids))}")
    check("跨片分页不漏（计数=total）", len(ids) == total, f"{len(ids)}/{total}")
    if MYSQL_OK:
        db_ids = {r[0] for r in sql_rows("SELECT id FROM recon_report")}
        check("分页并集=单表全量（影子镜像一致）", set(ids) == db_ids, f"api={len(ids)} db={len(db_ids)}")
        phys_sum = sum(int(sql(f"SELECT COUNT(*) FROM recon_report_{k}")) for k in range(4))
        check("跨片归并=各物理表行数和（顶层无藏 SUM）", phys_sum == total, f"phys={phys_sum} total={total}")
    summ = ok("GET", "/api/recon/summary")
    check("summary 按日聚合 runs 合计=报告总数",
          sum(r["runs"] for r in summ["byDay"]) == summ["totalRuns"] == total,
          f'{sum(r["runs"] for r in summ["byDay"])}/{summ["totalRuns"]}/{total}')
    stats = dispute_stats()
    check("summary 差错按类型=差错平台 stats",
          {r["key"]: r["disputes"] for r in summ["disputeByType"]} == stats["byType"],
          f'{summ["disputeByType"]} vs {stats["byType"]}')
    if MYSQL_OK:
        db_types = {r[0]: int(r[1]) for r in sql_rows(
            "SELECT diff_type, COUNT(*) FROM channel_recon_diff GROUP BY diff_type")}
        check("summary 差异类型聚合与 SQL 对照一致",
              {r["key"]: r["runs"] for r in summ["byDiffType"]} == db_types,
              f'{summ["byDiffType"]} vs {db_types}')
        db_ch = {r[0]: int(r[1]) for r in sql_rows(
            "SELECT channel_code, COUNT(*) FROM channel_recon_run GROUP BY channel_code")}
        check("summary 渠道维度 runs 与 SQL 对照一致",
              {r["key"]: r["runs"] for r in summ["byChannel"]} == db_ch, f'{summ["byChannel"]} vs {db_ch}')
    check("summary 商户维度有归并行", len(summ["byMerchant"]) >= 1, str(summ["byMerchant"])[:200])
    sla = ok("GET", "/api/recon/dispute-sla")
    check("时效报表五阶段齐备", [s["name"] for s in sla["stages"]]
          == ["建单→分派", "分派→修复", "修复→核销", "核销→关闭", "建单→核销"], str(sla["stages"])[:300])
    check("时效逐单数=差错单总数", sla["total"] == stats["total"] == len(sla["items"]),
          f'{sla["total"]}/{stats["total"]}/{len(sla["items"])}')
    resolved = [i for i in sla["items"] if i.get("resolvedAt") and i.get("createToResolveMs") is not None]
    check("已核销单建单→核销时长非负", all(i["createToResolveMs"] >= 0 for i in resolved), str(resolved[:2])[:200])
    st, csv1 = req("GET", "/api/recon/reports/export.csv")
    check("报告 CSV 导出（行数=total+1 表头）", st == 200 and isinstance(csv1, str)
          and csv1.startswith("id,checkedAt,pass,") and len(csv1.strip().splitlines()) == total + 1,
          f"st={st} lines={len(csv1.strip().splitlines()) if isinstance(csv1, str) else '?'}")
    st, csv2 = req("GET", "/api/recon/dispute-sla/export.csv")
    check("差错时效 CSV 导出", st == 200 and isinstance(csv2, str)
          and csv2.startswith("disputeNo,type,") and len(csv2.strip().splitlines()) == sla["total"] + 1,
          f"st={st}")
    st, csv3 = req("GET", f"/api/recon/channel-diffs/export.csv?channelCode={code}&bizDate={t}")
    check("渠道差异 CSV 导出（含处置建议列）", st == 200 and isinstance(csv3, str)
          and csv3.startswith("channelCode,bizDate,") and "suggestion" in csv3.splitlines()[0]
          and len(csv3.strip().splitlines()) == len(diffs_after) + 1, f"st={st}")

    # ---------- 7. 收尾门禁 ----------
    print("[7] 收尾门禁：六项仍全过 + 试算平衡")
    time.sleep(4)
    final = ok("POST", "/api/recon/run")
    check("收尾六项全过（新增支付单不回归）", final.get("pass") is True
          and six_checks(final) == EXPECTED_CHECKS, str(final)[:300])
    trial2 = ok("GET", "/api/accounting/trial")
    check("收尾试算 D=C 且 Σbalance=0", trial2.get("balanced") is True and trial2.get("balanceSum") == 0,
          str(trial2))

    print(f"\n== verify-recon 结果：PASS={PASSED} FAIL={FAILED} SKIP={SKIPPED} ==")
    return 1 if FAILED else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except AssertionError as e:
        print(f"\n[ABORT] {e}")
        print(f"\n== verify-recon 结果：PASS={PASSED} FAIL={FAILED + 1} SKIP={SKIPPED} ==")
        sys.exit(1)