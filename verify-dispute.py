#!/usr/bin/env python3
"""verify-dispute.py — wave2 C 泳道验证：S-4 自动冲正/挂账 + X-1 差错平台 + RC-1 对账分类 + N-2 死信工单。

跑法: python3 verify-dispute.py   （payment-pro 栈需已启动，gateway 8080；默认 PP_PROFILE=docker）
      PP_PROFILE=local 时跳过需直造单边账的 DB 段（local H2 文件库进程独占，无法 additive 插测试行）。

认证双态兼容（B 泳道 OAuth2 落地前后均可跑）：先试 POST /api/auth/login，200 则后续带 Bearer，
404/401 则不带鉴权头。OpenAPI HMAC 链不受影响。

造差异手段（合法、additive，不删改既有业务数据）：
  - 单边账/金额不符：向 pp_payment 插入 DSPTEST 前缀的合成 SUCCESS 支付单（本脚本自有测试行），
    金额不符单另经 /api/accounting/internal/topup 以支付单号为 bizNo 过一笔不等额凭证；
    验证完毕将自有合成行置 FAILED 退出对账窗口（仅 UPDATE 本脚本插入的行）。
  - 通知死信：本地回调 server 先回 500 耗尽重试 → FAILED → NOTIFY_DEAD 差错单 → 翻 200 → approve 重放闭环。

断言覆盖: 对账失败分类建单 + 幂等不重复建单 / 影子模式不自动动账 / 开启后红冲蓝补+挂账修复幂等 +
          修复后试算平衡 / 人工 assign/approve/reject/resolve/close 状态机 + 审计留痕 /
          对账复核通过自动 RESOLVED / NOTIFY_DEAD 建单 + 告警指标 + 重放闭环 / stats 正确。
"""
import json
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BASE = os.environ.get("PP_GATEWAY", "http://127.0.0.1:8080")
PROFILE = os.environ.get("PP_PROFILE", "docker")
SUFFIX = uuid.uuid4().hex[:8]
TOKEN = None
PASSED = FAILED = SKIPPED = 0
INSERTED_IDS = []
HAVE_DB = False


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


STEP_TOKEN = None
STEP_EXPIRES = 0.0


def _sensitive(path):
    """S-7 需二次认证的端点（差错 approve 真实动账，已纳入闸门）。"""
    p = path.split("?")[0]
    if not p.startswith("/api/"):
        return False
    if p.startswith("/api/disputes/autofix/run") or p.startswith("/api/accounting/reserve/adjust"):
        return True
    parts = p.strip("/").split("/")
    if len(parts) == 4 and parts[1] == "disputes" and parts[3] == "approve":
        return True
    if len(parts) == 4 and parts[1] == "reviews" and parts[3] in ("approve", "reject"):
        return True
    if len(parts) == 5 and parts[1] == "members" and parts[3] == "secrets" and parts[4] == "rotate":
        return True
    return False


def step_token(force=False):
    """二次认证绑会话：再验当前登录用户自己的口令，operator 恒为会话用户。"""
    global STEP_TOKEN, STEP_EXPIRES
    if not TOKEN:
        return None
    if not force and STEP_TOKEN and time.time() < STEP_EXPIRES:
        return STEP_TOKEN
    st, resp = req("POST", "/api/security/step-up",
                   {"password": os.environ.get("PP_ADMIN_PASSWORD", "admin123")})
    if st == 200 and isinstance(resp, dict) and resp.get("token"):
        STEP_TOKEN = resp["token"]
        STEP_EXPIRES = time.time() + max(10, (resp.get("expiresIn") or 300) - 10)
        return STEP_TOKEN
    return None


def req(method, path, body=None, base=None, timeout=30, _retried=False):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request((base or BASE) + path, data=data, method=method)
    r.add_header("Accept", "application/json")
    if data is not None:
        r.add_header("Content-Type", "application/json")
    if TOKEN:
        r.add_header("Authorization", "Bearer " + TOKEN)
    sensitive = _sensitive(path)
    if sensitive:
        stk = step_token()
        if stk:
            r.add_header("X-Step-Up-Token", stk)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            text = resp.read().decode()
            return resp.status, json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        try:
            parsed = json.loads(text)
        except Exception:
            parsed = {"error": text}
        if e.code == 403 and sensitive and not _retried and step_token(force=True):
            return req(method, path, body, base, timeout, _retried=True)
        return e.code, parsed
    except Exception as e:
        return 0, {"error": str(e)}


def ok(method, path, body=None):
    st, resp = req(method, path, body)
    assert st == 200, f"{method} {path} 期望 200，实际 {st}: {resp}"
    return resp


def poll(fn, pred, timeout=30, interval=1):
    deadline = time.time() + timeout
    val = fn()
    while time.time() < deadline and not pred(val):
        time.sleep(interval)
        val = fn()
    return val


def login_dual_state():
    """B 泳道 OAuth2 双态兼容：login 200 → Bearer；404/401 → 无鉴权继续。"""
    global TOKEN
    pwd = os.environ.get("PP_ADMIN_PASSWORD", "admin123")
    st, resp = req("POST", "/api/auth/login", {"username": "admin", "password": pwd})
    if st == 200 and isinstance(resp, dict):
        token = resp.get("token") or resp.get("accessToken") or resp.get("access_token")
        if not token and isinstance(resp.get("data"), dict):
            token = resp["data"].get("token") or resp["data"].get("accessToken")
        TOKEN = token
        print(f"== 认证: login 200，Bearer 模式 token={'有' if TOKEN else '无'} ==")
    else:
        print(f"== 认证: login {st}（B 泳道未落地），无鉴权模式 ==")


# ---------- MySQL（docker）additive 测试行 ----------

def _docker_bin():
    p = "/Users/hegan/.docker/bin/docker"
    return p if os.path.exists(p) else "docker"


def _mysql_creds():
    user, pwd = "root", "ClaimShared_2026"
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


def sql(db, statement):
    user, pwd = _mysql_creds()
    env = dict(os.environ)
    env["MYSQL_PWD"] = pwd  # 不回显口令
    container = os.environ.get("PP_MYSQL_CONTAINER", "claim-shared-mysql")
    r = subprocess.run(
        [_docker_bin(), "exec", "-e", "MYSQL_PWD", container,
         "mysql", f"-u{user}", "-N", "-B", "-e", statement, db],
        capture_output=True, text=True, env=env, timeout=30)
    if r.returncode != 0:
        raise RuntimeError(f"mysql 执行失败: {r.stderr.strip()[:200]}")
    return r.stdout.strip()


def probe_db():
    global HAVE_DB
    if PROFILE != "docker":
        print(f"== profile={PROFILE}：跳过直造单边账 DB 段（local H2 进程独占） ==")
        return
    try:
        sql("pp_payment", "SELECT 1")
        HAVE_DB = True
        print("== MySQL 可用：合成单边账/金额不符段启用 ==")
    except Exception as e:
        print(f"== MySQL 不可用（{e}）：DB 段 SKIP ==")


def insert_synthetic_payment(pid, order_id, merchant_id, payer, payee, amount):
    now = int(time.time() * 1000)
    sql("pp_payment",
        "INSERT INTO payment(id,order_id,merchant_id,method,payer_account_id,merchant_account_id,"
        "amount,fee,status,refunded_amount,refunded_fee,request_id,created_at,biz_type) "
        f"VALUES('{pid}','{order_id}','{merchant_id}','BALANCE','{payer}','{payee}',{amount},0,"
        f"'SUCCESS',0,0,'{pid}-req',{now},'TRADE')")
    INSERTED_IDS.append(pid)


def cleanup_synthetic():
    if INSERTED_IDS and HAVE_DB:
        ids = ",".join(f"'{i}'" for i in INSERTED_IDS)
        try:
            sql("pp_payment",
                f"UPDATE payment SET status='FAILED', fail_reason='DSPTEST cleanup' "
                f"WHERE id IN ({ids}) AND id LIKE 'DSPTEST%'")
            print(f"== 清理：合成测试支付单置 FAILED 退出对账窗口 {INSERTED_IDS} ==")
        except Exception as e:
            print(f"!! 清理失败（需人工核查 DSPTEST 行）: {e}")


# ----------  helpers ----------

def bal(aid):
    return ok("GET", f"/api/accounting/accounts/{aid}/balance")


def trial():
    return ok("GET", "/api/accounting/trial")


def suspense():
    return ok("GET", "/api/accounting/suspense/balance")


def disputes_by_biz(biz_no):
    return ok("GET", f"/api/disputes?bizNo={biz_no}&size=50")["items"]


def dispute_of(biz_no, check_name):
    items = [d for d in disputes_by_biz(biz_no) if d.get("checkName") == check_name]
    assert items, f"未找到差错单 bizNo={biz_no} check={check_name}"
    return items[0]


def entries_total(biz_no):
    page = ok("GET", f"/api/accounting/entries?bizNo={biz_no}&size=200")
    return page.get("total", len(page.get("items") or []))


def dead_metric_raw():
    try:
        with urllib.request.urlopen("http://127.0.0.1:18090/actuator/prometheus", timeout=10) as r:
            text = r.read().decode()
        total = 0.0
        for line in text.splitlines():
            if line.startswith("payment_notify_dead_total"):
                total += float(line.rsplit(" ", 1)[1])
        return total
    except Exception:
        return -1


class Receiver:
    """本地回调 server：ok=False 回 500（耗尽重试），翻 True 后回 200（重放送达）。"""

    def __init__(self):
        self.ok = False
        self.captures = []
        self.lock = threading.Lock()

    def handler(self):
        recv = self

        class H(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def do_POST(self):
                n = int(self.headers.get("Content-Length", 0))
                body = self.rfile.read(n).decode()
                with recv.lock:
                    recv.captures.append({"body": body, "sig": self.headers.get("X-Payment-Signature"),
                                          "ok": recv.ok})
                code = 200 if recv.ok else 500
                payload = b"ok" if recv.ok else b"boom"
                self.send_response(code)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *a):
                pass

        return H

    def start(self):
        srv = ThreadingHTTPServer(("127.0.0.1", 0), self.handler())
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        return srv, srv.server_address[1]


def main():
    login_dual_state()
    probe_db()

    print("== A. 平台基础 ==")
    h = ok("GET", "/api/health")
    check("全栈健康 13/13", len(h) == 13 and all(x["ok"] for x in h),
          str([x["service"] for x in h if not x["ok"]]))
    t0 = trial()
    check("基线试算平衡 D=C 且 Σbalance=0", t0["balanced"] and t0["balanceSum"] == 0, str(t0))
    page = ok("GET", "/api/disputes?page=1&size=5")
    check("差错分页结构 items/page/size/total",
          isinstance(page.get("items"), list) and page["page"] == 1 and page["size"] == 5
          and isinstance(page.get("total"), int), str(page)[:200])
    check("分页 size 生效", len(page["items"]) <= 5)
    stats0 = ok("GET", "/api/disputes/stats")
    check("stats 结构 total/byStatus/byType/bySource",
          isinstance(stats0.get("total"), int) and isinstance(stats0.get("byStatus"), dict)
          and isinstance(stats0.get("byType"), dict) and isinstance(stats0.get("bySource"), dict))
    st, resp = req("GET", "/api/disputes/999999999")
    check("不存在差错单 404", st == 404, f"{st} {resp}")

    print("== B. 真实支付不回归（合成行锚定订单） ==")
    merchant = ok("POST", "/api/members", {"name": f"差错商户{SUFFIX}", "type": "MERCHANT"})
    user = ok("POST", "/api/members", {"name": f"差错用户{SUFFIX}", "type": "USER"})
    ma = ok("POST", "/api/accounts", {"memberId": merchant["id"]})["id"]
    ua = ok("POST", "/api/accounts", {"memberId": user["id"]})["id"]
    ok("POST", "/api/accounting/internal/topup",
       {"accountId": ua, "amount": 100000, "bizNo": f"DSPTEST-TOPUP-{SUFFIX}"})
    order = ok("POST", "/api/orders",
               {"merchantId": merchant["id"], "subject": "差错验证订单", "amount": 1000})["order"]
    r = ok("POST", "/api/cashier/pay",
           {"orderId": order["id"], "method": "BALANCE", "accountId": ua, "requestId": f"DSPTEST-PAY-{SUFFIX}"})
    pid_real = r["payment"]["id"]
    p = poll(lambda: ok("GET", f"/api/payments/{pid_real}"), lambda x: x["status"] != "PROCESSING", timeout=30)
    check("余额支付 SUCCESS（不回归）", p["status"] == "SUCCESS", str(p))
    check("订单 PAID（不回归）", ok("GET", f"/api/orders/{order['id']}")["status"] == "PAID")

    # 合成差异目标账户（独立会员独立小额户，不污染既有账）
    target_user = ok("POST", "/api/members", {"name": f"差错目标户{SUFFIX}", "type": "USER"})
    test_acct = ok("POST", "/api/accounts", {"memberId": target_user["id"]})["id"]
    bal_test_before = bal(test_acct)
    pa = f"DSPTEST{SUFFIX}A"   # 单边账：SUCCESS 支付无账务凭证，金额 500
    pb = f"DSPTEST{SUFFIX}B"   # 金额不符：凭证借方 400 ≠ 支付金额 600

    if HAVE_DB:
        print("== C. RC-1 对账失败分类建单 + 幂等 ==")
        insert_synthetic_payment(pa, order["id"], merchant["id"], ua, ma, 500)
        insert_synthetic_payment(pb, order["id"], merchant["id"], ua, ma, 600)
        ok("POST", "/api/accounting/internal/topup", {"accountId": test_acct, "amount": 400, "bizNo": pb})
        rep = ok("POST", "/api/recon/run")
        check("对账失败（存在差异）", rep["pass"] is False, str(rep)[:300])
        acct_check = next(c for c in rep["checks"] if c["name"] == "支付-账务一致")
        check("支付-账务一致判失败（口径不回归）", acct_check["pass"] is False)
        check("missingBizNos 含合成差异单（additive 明细）",
              pa in (acct_check.get("missingBizNos") or []) and pb in (acct_check.get("missingBizNos") or []),
              str(acct_check))
        check("disputeType 分类字段存在", bool(acct_check.get("disputeType")))
        check("report.disputeIds 非空（additive）", bool(rep.get("disputeIds")))
        da = disputes_by_biz(pa)
        db = disputes_by_biz(pb)
        check("单边账单生成 2 张（账务+清结算）", len(da) == 2, str(da)[:300])
        check("金额不符单生成 2 张（账务+清结算）", len(db) == 2, str(db)[:300])
        da_acct = dispute_of(pa, "支付-账务一致")
        db_acct = dispute_of(pb, "支付-账务一致")
        da_settle = dispute_of(pa, "支付-清结算一致")
        db_settle = dispute_of(pb, "支付-清结算一致")
        check("单边账分类 ONE_SIDED + 策略 SUSPENSE",
              da_acct["type"] == "ONE_SIDED" and da_acct["strategy"] == "SUSPENSE", str(da_acct))
        check("金额不符分类 AMOUNT_MISMATCH + 策略 REVERSE_AND_REPOST",
              db_acct["type"] == "AMOUNT_MISMATCH" and db_acct["strategy"] == "REVERSE_AND_REPOST", str(db_acct))
        check("结算缺失分类 ONE_SIDED + 策略 MANUAL（不臆断动账）",
              da_settle["type"] == "ONE_SIDED" and da_settle["strategy"] == "MANUAL")
        check("差错单初始 OPEN + source RECON",
              da_acct["status"] == "OPEN" and da_acct["source"] == "RECON")
        ids_before = {d["id"] for d in da + db}
        rep2 = ok("POST", "/api/recon/run")
        ids_after = {d["id"] for d in disputes_by_biz(pa) + disputes_by_biz(pb)}
        check("重复对账幂等不重复建单", ids_before == ids_after and len(ids_after) == 4,
              f"{ids_before} -> {ids_after}")
        check("重复对账 disputeIds 回链同一批单", ids_before.issubset(set(rep2.get("disputeIds") or [])))

        print("== D. S-4 影子模式（默认 autofix=false） ==")
        entries0, susp0 = trial()["entries"], suspense()
        shadow = ok("POST", "/api/disputes/autofix/run", {})
        check("缺省触发=SHADOW（双 profile 默认影子）", shadow["mode"] == "SHADOW", str(shadow)[:200])
        check("影子模式给出建议策略清单", da_acct["id"] in [i["id"] for i in shadow["items"]])
        check("影子模式不自动动账（分录/挂账不变）", trial()["entries"] == entries0 and suspense() == susp0)
        check("影子模式差错单仍 OPEN", ok("GET", f"/api/disputes/{da_acct['id']}")["status"] == "OPEN")

        print("== E. 人工状态机 + 审计留痕 ==")
        sid = da_settle["id"]
        a = ok("POST", f"/api/disputes/{sid}/assign", {"assignee": "dsp-tester", "operator": "dsp-lead"})
        check("assign OPEN→ASSIGNED", a["status"] == "ASSIGNED" and a["assignee"] == "dsp-tester")
        st, resp = req("POST", f"/api/disputes/{sid}/approve", {"operator": "dsp-lead"})
        check("MANUAL 策略 approve 被拒（不强行冲正）", st == 409, f"{st} {resp}")
        rv = ok("POST", f"/api/disputes/{sid}/resolve", {"operator": "dsp-tester", "resolution": "人工核对：结算在途"})
        check("resolve ASSIGNED→RESOLVED + resolvedAt", rv["status"] == "RESOLVED" and rv.get("resolvedAt"))
        cl = ok("POST", f"/api/disputes/{sid}/close", {"operator": "dsp-lead"})
        check("close RESOLVED→CLOSED", cl["status"] == "CLOSED")
        st, resp = req("POST", f"/api/disputes/{sid}/close", {})
        check("重复 close 被拒（终态）", st == 409, f"{st} {resp}")
        logs = ok("GET", f"/api/disputes/{sid}")["logs"]
        actions = [l["action"] for l in logs]
        check("审计留痕 CREATE/ASSIGN/RESOLVE/CLOSE 齐备",
              all(x in actions for x in ("CREATE", "ASSIGN", "RESOLVE", "CLOSE")), str(actions))
        check("审计含操作人与前后状态",
              any(l["operator"] == "dsp-tester" and l["beforeStatus"] == "ASSIGNED"
                  and l["afterStatus"] == "RESOLVED" for l in logs))

        print("== F. S-4 修复执行：挂账 + 红冲蓝补 + 幂等 + 试算平衡 ==")
        susp_base = suspense()
        aid = da_acct["id"]
        f1 = ok("POST", f"/api/disputes/{aid}/approve", {"operator": "dsp-fixer"})
        check("approve 挂账修复 OPEN→RESOLVED", f1["status"] == "RESOLVED", str(f1))
        check("fix_biz_no 确定性 DSPF+id", f1["fixBizNo"] == f"DSPF{aid}", str(f1.get("fixBizNo")))
        check("SYS_SUSPENSE 余额=基线+单边账 500", suspense() == susp_base + 500,
              f"{suspense()} vs {susp_base + 500}")
        sv = ok("GET", f"/api/accounting/transfers/DSPF{aid}")
        check("挂账凭证 voucherType=SUSPENSE 且借贷平衡 500",
              sv["voucherType"] == "SUSPENSE"
              and sum(l["amount"] for l in sv["lines"] if l["dir"] == "D") == 500
              and sum(l["amount"] for l in sv["lines"] if l["dir"] == "C") == 500)
        tf = trial()
        check("挂账修复后试算 D=C 且 Σbalance=0", tf["balanced"] and tf["balanceSum"] == 0, str(tf))
        e_fix = entries_total(f"DSPF{aid}")
        f1b = ok("POST", f"/api/disputes/{aid}/approve", {"operator": "dsp-fixer"})
        check("重复 approve 幂等（终态 no-op）", f1b["status"] == "RESOLVED")
        check("重复 approve 不重复动账（分录/挂账不变）",
              entries_total(f"DSPF{aid}") == e_fix == 2 and suspense() == susp_base + 500)

        bid = db_acct["id"]
        run1 = ok("POST", "/api/disputes/autofix/run", {"execute": True})
        check("execute=true 显式授权=EXECUTE 模式", run1["mode"] == "EXECUTE", str(run1)[:200])
        f2 = ok("GET", f"/api/disputes/{bid}")
        check("金额不符单自动红冲蓝补 RESOLVED", f2["status"] == "RESOLVED", str(f2))
        check("fix_biz_no 含红冲 RV + 蓝补 DSPB",
              "RV" in (f2["fixBizNo"] or "") and f"DSPB{bid}" in (f2["fixBizNo"] or ""), str(f2.get("fixBizNo")))
        check("蓝补后账户余额=正确金额 600（400 红冲回滚+600 蓝补）",
              bal(test_acct) == bal_test_before + 600, f"{bal(test_acct)} vs {bal_test_before + 600}")
        orig = ok("GET", f"/api/accounting/transfers/{pb}")
        check("原凭证不可篡改（lines 仍 400）+ 回链红冲凭证",
              sum(l["amount"] for l in orig["lines"] if l["dir"] == "D") == 400
              and (orig.get("reverseBizNo") or "").startswith("RV"), str(orig)[:200])
        blue = ok("GET", f"/api/accounting/transfers/DSPB{bid}")
        check("蓝补凭证借方=支付金额 600",
              sum(l["amount"] for l in blue["lines"] if l["dir"] == "D") == 600)
        tf2 = trial()
        check("红冲蓝补后试算 D=C 且 Σbalance=0", tf2["balanced"] and tf2["balanceSum"] == 0, str(tf2))
        e_blue = entries_total(f"DSPB{bid}")
        bal_mid, susp_mid = bal(test_acct), suspense()
        ok("POST", "/api/disputes/autofix/run", {"execute": True})
        f2b = ok("GET", f"/api/disputes/{bid}")
        check("重复 execute 幂等（仍 RESOLVED、不重复动账）",
              f2b["status"] == "RESOLVED" and entries_total(f"DSPB{bid}") == e_blue == 2
              and bal(test_acct) == bal_mid and suspense() == susp_mid)

        print("== G. 清理合成行 → 对账复核通过 → 自动 RESOLVED 留痕 ==")
        cleanup_synthetic()
        INSERTED_IDS.clear()
        rep3 = ok("POST", "/api/recon/run")
        check("清理后对账恢复全过", rep3["pass"] is True,
              str([c for c in rep3["checks"] if not c["pass"]]))
        g1 = ok("GET", f"/api/disputes/{db_settle['id']}")
        check("对账通过自动核销 OPEN 单→RESOLVED", g1["status"] == "RESOLVED", str(g1))
        check("自动核销留痕 AUTO_RESOLVE",
              any(l["action"] == "AUTO_RESOLVE" for l in g1["logs"]), str(g1["logs"])[:300])
        check("已修复单不被重复核销/改状态", ok("GET", f"/api/disputes/{aid}")["status"] == "RESOLVED")
    else:
        for name in ("C 分类建单", "D 影子模式", "E 人工状态机", "F 修复执行", "G 自动核销"):
            skip(name, "需 docker MySQL 直造单边账（PP_PROFILE=docker）")

    print("== E2. 人工登记 + reject 状态机（无 DB 依赖） ==")
    mbiz = f"DSPTESTM{SUFFIX}"
    m1 = ok("POST", "/api/disputes", {"type": "STATUS_MISMATCH", "source": "PAYMENT",
                                      "bizNo": mbiz, "amount": 1, "reason": "验证人工登记"})
    m2 = ok("POST", "/api/disputes", {"type": "STATUS_MISMATCH", "source": "PAYMENT",
                                      "bizNo": mbiz, "amount": 1, "reason": "验证人工登记"})
    check("人工登记幂等（同来源同 bizNo 同单）", m1["id"] == m2["id"] and m1["status"] == "OPEN")
    rj = ok("POST", f"/api/disputes/{m1['id']}/reject", {"operator": "dsp-lead", "reason": "误报"})
    check("reject OPEN→IGNORED", rj["status"] == "IGNORED")
    st, resp = req("POST", f"/api/disputes/{m1['id']}/assign", {"assignee": "x"})
    check("IGNORED 后 assign 被拒（非法迁移）", st == 409, f"{st} {resp}")
    ok("POST", f"/api/disputes/{m1['id']}/close", {})
    st, resp = req("POST", f"/api/disputes/{m1['id']}/resolve", {})
    check("CLOSED 后 resolve 被拒（终态）", st == 409, f"{st} {resp}")

    print("== H. N-2 通知死信告警 + 工单 + 重放闭环 ==")
    recv = Receiver()
    srv, port = recv.start()
    dead0 = dead_metric_raw()
    nbiz = f"DSPTESTN{SUFFIX}"
    n = ok("POST", "/api/notify/send",
           {"event": "PAY_SUCCESS", "bizId": nbiz, "callbackUrl": f"http://127.0.0.1:{port}/cb",
            "appId": merchant["appId"], "signType": "HMAC_SHA256",
            "payload": {"bizId": nbiz, "amount": 777, "status": "SUCCESS"}})
    nid = n["id"]
    print(f"  ... 等待重试耗尽（5 次退避 ~80s）notifyId={nid}")
    d = poll(lambda: ok("GET", f"/api/notify/{nid}"), lambda x: x["status"] == "FAILED", timeout=170, interval=2)
    check("重试耗尽置死信 FAILED（attempts>=5）", d["status"] == "FAILED" and d["attempts"] >= 5, str(d))
    nd = ok("GET", f"/api/disputes?source=NOTIFY&bizNo={nid}")["items"]
    check("NOTIFY_DEAD 差错单自动建单（幂等 by notifyId）", len(nd) == 1, str(nd)[:300])
    nd = nd[0]
    check("死信单 type/strategy/amount 正确",
          nd["type"] == "NOTIFY_DEAD" and nd["strategy"] == "NOTIFY_REPLAY" and nd["amount"] == 777, str(nd))
    dead1 = dead_metric_raw()
    check("告警指标 payment_notify_dead_total 递增", dead1 > dead0 >= 0, f"{dead0} -> {dead1}")
    time.sleep(32)  # 覆盖一轮死信兜底扫描（30s），验证幂等补建不重复
    check("兜底扫描不重复建单", len(ok("GET", f"/api/disputes?source=NOTIFY&bizNo={nid}")["items"]) == 1)
    recv.ok = True
    ap = ok("POST", f"/api/disputes/{nd['id']}/approve", {"operator": "dsp-fixer"})
    check("approve 触发重放（FIXING 等待送达）", ap["status"] == "FIXING", str(ap))
    nr = ok("GET", f"/api/notify/{nid}")
    check("重放重置 PENDING/attempts=0", nr["status"] in ("PENDING", "DELIVERED") and nr["attempts"] <= 1, str(nr))
    d2 = poll(lambda: ok("GET", f"/api/notify/{nid}"), lambda x: x["status"] == "DELIVERED", timeout=25, interval=1)
    check("重放送达 DELIVERED", d2["status"] == "DELIVERED", str(d2))
    nd2 = poll(lambda: ok("GET", f"/api/disputes/{nd['id']}"), lambda x: x["status"] == "RESOLVED", timeout=15)
    check("送达后差错单自动 RESOLVED（闭环）", nd2["status"] == "RESOLVED", str(nd2))
    actions = [l["action"] for l in nd2["logs"]]
    check("重放闭环留痕 REPLAY_TRIGGERED + AUTO_RESOLVE",
          "REPLAY_TRIGGERED" in actions and "AUTO_RESOLVE" in actions, str(actions))
    with recv.lock:
        replayed = [c for c in recv.captures if c["ok"]]
    check("重放推送仍带签名头（additive 红线）", bool(replayed) and bool(replayed[-1]["sig"]))
    n_cap = len(recv.captures)
    ok("POST", f"/api/disputes/{nd['id']}/approve", {})
    time.sleep(4)
    check("终态重复 approve 不重复推送", len(recv.captures) == n_cap)
    srv.shutdown()

    print("== I. stats 正确性 ==")
    stats = ok("GET", "/api/disputes/stats")
    check("stats.total = Σ byStatus", stats["total"] == sum(stats["byStatus"].values()), str(stats))
    check("stats.open = OPEN+ASSIGNED+FIXING",
          stats["open"] == sum(stats["byStatus"].get(s, 0) for s in ("OPEN", "ASSIGNED", "FIXING")))
    check("stats.bySource 含 RECON/NOTIFY",
          "RECON" in stats["bySource"] and "NOTIFY" in stats["bySource"], str(stats["bySource"]))
    check("stats.total >= 本次建单数", stats["total"] >= stats0["total"] + (6 if HAVE_DB else 2))
    flt = ok("GET", "/api/disputes?status=RESOLVED&size=50")
    check("status 过滤生效", all(i["status"] == "RESOLVED" for i in flt["items"]))
    tfinal = trial()
    check("收尾试算平衡 D=C 且 Σbalance=0", tfinal["balanced"] and tfinal["balanceSum"] == 0, str(tfinal))

    print(f"\n结果: {PASSED} 通过 / {FAILED} 失败 / {SKIPPED} 跳过")
    return 1 if FAILED else 0


if __name__ == "__main__":
    rc = 1
    try:
        rc = main()
    finally:
        cleanup_synthetic()
    sys.exit(rc)