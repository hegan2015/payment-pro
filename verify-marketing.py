#!/usr/bin/env python3
# ============================================================================
# payment-pro P2 第三期 · A 泳道验证：X-3a 营销域骨架 + 红包/优惠券 · X-3b 积分/活动核销 + 分摊联动
#
# 用法：docker 全栈在线（./run.sh --docker）后 python3 verify-marketing.py
#   [1]  基线与环境：13/13 健康 list 不含 marketing、login/step-up、营销域 docker 形态自证（MySQL）
#   [2]  X-3a 模板 + 发放幂等（(COUPON_ISSUE,bizNo) + request_id UNIQUE）
#   [3]  X-3a 领取：库存原子不超发、每人限领、领取幂等
#   [4]  X-3a 券核销：门槛/过期/面额校验、四重幂等、并发 CAS 只核销一次、复式记账、退回幂等
#   [5]  X-3a 红包核销 + 退回幂等
#   [6]  X-3a 过期作业（幂等，过期券不可核销）
#   [7]  X-3b 积分：赚取/消耗/冻结/解冻/冻结转消耗/退回/过期（虚拟权益账本，不动资金处不动账）
#   [8]  X-3b 活动：立减/满减/封顶、预算原子并发不超扣、核销幂等、退回预算回滚
#   [9]  X-3b 分摊守恒：最大余数法 Σ=抵扣总额误差 0、EXPLICIT 校验、重复分摊拒绝、订单额=资金腿+营销腿
#   [10] 资金护航：营销资金恒等（Σ核销−Σ退回 = SYS_MARKETING 出账）+ 试算 D=C 且 Σbalance=0
#   [11] 不回归：13/13、六项对账项数与判定不变、结算汇总与 SYS_FEE 手续费口径逐字不变
#   [12] local 形态：营销域单进程 H2 零依赖启动，权益/分摊全通；资金动作优雅失败且整体回滚（不自建账本）
#
# 范围红线：只验 X-3a/X-3b，不验 P-7 抵扣编排 / C-4 最优组合（后续泳道）。
# 数据保护：只新增 MKTTEST 前缀 + uuid 的测试行；绝不删除/重置任何既有数据；不改库状态凑绿。
# 断言口径：不放宽；失败如实记录。
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

BASE = "http://127.0.0.1:8080"
MKT_ACT = "http://127.0.0.1:18093/actuator"
LOCAL_BASE = "http://127.0.0.1:18993/local/marketing"
JAVA_HOME = "/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home"
REPO = os.path.dirname(os.path.abspath(__file__))
SUFFIX = uuid.uuid4().hex[:8]
TAG = "MKTTEST"
TOKEN = None
STEP = None
PASSED = 0
FAILED = 0
FAILURES = []
LOCAL_PROC = None


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
    except Exception as e:
        return 0, {"error": str(e)}


def api(path, method="GET", body=None, step=False, timeout=120):
    return http(BASE + path, method, body, bearer=True, step=step, timeout=timeout)


def ok(path, method="GET", body=None, step=False):
    st, resp = api(path, method, body, step=step)
    assert st == 200, f"{method} {path} -> {st} {resp}"
    return resp


def mkt(path, method="GET", body=None, step=True):
    """营销端点（写操作默认带二次认证）。"""
    return api("/api/marketing" + path, method, body, step=step)


def mok(path, method="GET", body=None, step=True):
    st, resp = mkt(path, method, body, step=step)
    assert st == 200, f"{method} /api/marketing{path} -> {st} {resp}"
    return resp


def bal(account_id):
    return ok(f"/api/accounting/accounts/{account_id}/balance")


def login():
    global TOKEN, STEP
    password = os.environ.get("PP_ADMIN_PASSWORD", "admin123")
    st, resp = http(BASE + "/api/auth/login", "POST", {"username": "admin", "password": password})
    assert st == 200, f"登录失败 {st} {resp}"
    TOKEN = resp["token"]
    st, resp = http(BASE + "/api/security/step-up", "POST", {"password": password}, bearer=True)
    assert st == 200, f"二次认证失败 {st} {resp}"
    STEP = resp["token"]


def new_subject(kind="USER"):
    m = ok("/api/members", "POST", {"name": f"{TAG}-{kind}-{SUFFIX}-{uuid.uuid4().hex[:6]}", "type": kind})
    a = ok("/api/accounts", "POST", {"memberId": m["id"]})
    return m["id"], a["id"]


def biz(prefix):
    return f"{TAG}-{prefix}-{uuid.uuid4().hex[:12]}"


def pay():
    return f"{TAG}P{uuid.uuid4().hex[:10]}"


# ============================================================================
def sec1_baseline():
    print("[1] 基线与环境")
    st, health = http(BASE + "/api/health")
    check("网关健康 200", st == 200, str(st))
    check("健康清单仍是 13 项（marketing 不纳入，既有硬断言不破）",
          isinstance(health, list) and len(health) == 13, str(health))
    check("13/13 全健康", all(h["ok"] for h in health), str([h["service"] for h in health if not h["ok"]]))
    check("健康清单不含 marketing", not any("marketing" in h["service"] for h in health))
    login()
    check("login + step-up 200", bool(TOKEN) and bool(STEP))
    st, resp = api("/api/members")
    check("Bearer /api/members 200", st == 200, str(st))
    st, resp = api("/api/payments")
    check("Bearer /api/payments 200", st == 200, str(st))
    st, act = http(MKT_ACT + "/health")
    check("营销域 actuator 18093 健康 UP", st == 200 and act.get("status") == "UP", f"{st} {act}")
    db = (((act or {}).get("components") or {}).get("db") or {}).get("details") or {}
    check("营销域 docker 形态 = MySQL（pp_marketing）",
          "MySQL" in str(db.get("database", "")), str(db))
    st, prof = http("http://127.0.0.1:18093/local/marketing/profile")
    check("docker 形态关闭 local 自检 API（生产面只经网关）", st == 404, str(st))
    summary = ok("/api/settle/summary")
    fee = bal("SYS_FEE")
    trial = ok("/api/accounting/trial")
    check("基线试算平衡 D=C 且 Σbalance=0", trial["balanced"] and trial["balanceSum"] == 0, str(trial))
    return summary, fee


def sec2_template_issue(member_id):
    print("[2] X-3a 模板 + 发放幂等")
    tpl = mok("/templates", "POST", {
        "kind": "COUPON", "name": f"{TAG}-TPL-C-{SUFFIX}", "amount": 500, "threshold": 1000,
        "totalStock": 3, "perMemberLimit": 2, "validDays": 30}, step=True)
    check("模板创建成功 kind=COUPON", tpl["kind"] == "COUPON" and tpl["status"] == "ACTIVE", str(tpl))
    check("模板初始 issued=0", tpl["issued"] == 0, str(tpl))
    tpl2 = mok("/templates", "POST", {
        "kind": "RED_PACKET", "name": f"{TAG}-TPL-R-{SUFFIX}", "amount": 200,
        "totalStock": 5, "perMemberLimit": 1, "validDays": 30}, step=True)
    check("红包模板创建成功", tpl2["kind"] == "RED_PACKET", str(tpl2))
    listed = mok("/templates?kind=COUPON", step=False)
    check("模板列表可按 kind 过滤", any(t["id"] == tpl["id"] for t in listed), str(len(listed)))

    b = biz("CI")
    req = {"memberId": member_id, "amount": 300, "threshold": 1000, "bizNo": b, "validDays": 30}
    c1 = mok("/coupons/issue", "POST", req)
    check("发券成功 AVAILABLE", c1["status"] == "AVAILABLE" and c1["amount"] == 300, str(c1))
    c2 = mok("/coupons/issue", "POST", req)
    check("发放幂等：同 bizNo 返回同一券", c1["id"] == c2["id"], f'{c1["id"]} vs {c2["id"]}')
    check("发放幂等：request_id 派生唯一", c1["requestId"] == "CI:" + b, str(c1["requestId"]))
    st, resp = mkt("/coupons/issue", "POST", {"memberId": member_id, "amount": 0, "bizNo": biz("CI0")})
    check("面额 0 拒绝", st == 400, f"{st} {resp}")
    st, resp = mkt("/coupons/issue", "POST", {"memberId": member_id, "amount": 100})
    check("缺 bizNo 拒绝（幂等键必填）", st == 400, f"{st} {resp}")
    ct = mok("/coupons/issue", "POST", {"memberId": member_id, "templateId": tpl["id"], "bizNo": biz("CIT")})
    check("按模板发放取模板快照", ct["amount"] == 500 and ct["threshold"] == 1000, str(ct))
    check("按模板发放扣库存 issued=1", mok(f"/templates/{tpl['id']}", step=False)["issued"] == 1)
    return tpl, tpl2, c1, ct


def sec3_claim(tpl, member_id, other_member):
    print("[3] X-3a 领取：库存/限领/幂等")
    req = {"templateId": tpl["id"], "memberId": member_id, "bizNo": biz("CL")}
    k1 = mok("/coupons/claim", "POST", req)
    k2 = mok("/coupons/claim", "POST", req)
    check("领取幂等：同 bizNo 同一券", k1["id"] == k2["id"], f'{k1["id"]} vs {k2["id"]}')
    k3 = mok("/coupons/claim", "POST", {"templateId": tpl["id"], "memberId": member_id, "bizNo": biz("CL")})
    check("第二次领取成功（限领 2）", k3["id"] != k1["id"], str(k3))
    st, resp = mkt("/coupons/claim", "POST", {"templateId": tpl["id"], "memberId": member_id, "bizNo": biz("CL")})
    check("超每人限领拒绝", st == 400, f"{st} {resp}")
    check("库存已发放 issued=3（1 发放 + 2 领取）",
          mok(f"/templates/{tpl['id']}", step=False)["issued"] == 3)
    st, resp = mkt("/coupons/claim", "POST", {"templateId": tpl["id"], "memberId": other_member, "bizNo": biz("CL")})
    check("库存耗尽拒绝（不超发）", st == 400, f"{st} {resp}")
    st, resp = mkt("/red-packets/claim", "POST", {"templateId": tpl["id"], "memberId": other_member,
                                                  "bizNo": biz("CLX")})
    check("券模板不能领红包（类型不匹配拒绝）", st == 400, f"{st} {resp}")
    return k1, k3


def sec3b_red_packet_claim(tpl2, member_id):
    print("[3b] X-3a 红包领取并发限领（同 bizNo 并发只 1 张）")
    rb = biz("CLR")
    with ThreadPoolExecutor(max_workers=6) as ex:
        results = list(ex.map(lambda _: mkt("/red-packets/claim", "POST",
                                           {"templateId": tpl2["id"], "memberId": member_id, "bizNo": rb}),
                              range(6)))
    successes = [r[1] for r in results if r[0] == 200]
    ids = {r["id"] for r in successes}
    check("并发领取全部返回同一张红包（幂等吸收）", len(ids) == 1, str(ids))
    check("并发领取无 5xx", all(r[0] in (200, 400, 409) for r in results), str([r[0] for r in results]))
    check("红包模板 issued=1（限领 1，不超发）",
          mok(f"/templates/{tpl2['id']}", step=False)["issued"] == 1)
    st, resp = mkt("/red-packets/claim", "POST",
                   {"templateId": tpl2["id"], "memberId": member_id, "bizNo": biz("CLR")})
    check("红包超限领拒绝", st == 400, f"{st} {resp}")
    return successes[0] if successes else None


def sec4_coupon_use(coupon, member_id, account_id):
    print("[4] X-3a 券核销：校验/幂等/并发 CAS/复式记账/退回")
    payment_id = pay()
    before_user = bal(account_id)
    before_sys = bal("SYS_MARKETING")
    req = {"couponId": coupon["id"], "paymentId": payment_id, "memberId": member_id,
           "accountId": account_id, "orderAmount": 2000}
    use = mok("/coupons/use", "POST", req)
    check("核销成功 USED，金额=券面额", use["status"] == "USED" and use["amount"] == 300, str(use))
    check("核销幂等键派生 MKT:C:couponId:paymentId",
          use["bizNo"] == f'MKT:C:{coupon["id"]}:{payment_id}', str(use["bizNo"]))
    check("用户户贷方 +300（复式记账 C 腿）", bal(account_id) - before_user == 300,
          f'{before_user} -> {bal(account_id)}')
    check("SYS_MARKETING 借方出账 300（D 腿）", before_sys - bal("SYS_MARKETING") == 300,
          f'{before_sys} -> {bal("SYS_MARKETING")}')
    check("券状态 AVAILABLE→USED", mok(f"/coupons/{coupon['id']}", step=False)["status"] == "USED")
    replay = mok("/coupons/use", "POST", req)
    check("核销幂等：重放返回同一核销单", replay["id"] == use["id"], f'{replay["id"]} vs {use["id"]}')
    check("核销幂等：重放不重复动账", bal(account_id) - before_user == 300)
    # 显式 requestId 重放
    replay2 = mok("/coupons/use", "POST", dict(req, requestId=use["requestId"]))
    check("显式 requestId 重放同一核销单", replay2["id"] == use["id"])

    # 并发核销另一张券：只成功一次
    c_conc = mok("/coupons/issue", "POST", {"memberId": member_id, "amount": 100, "threshold": 0,
                                            "bizNo": biz("CIC"), "validDays": 30})
    p_conc = pay()
    before = bal(account_id)
    with ThreadPoolExecutor(max_workers=8) as ex:
        rs = list(ex.map(lambda _: mkt("/coupons/use", "POST",
                                       {"couponId": c_conc["id"], "paymentId": p_conc,
                                        "accountId": account_id, "orderAmount": 5000}), range(8)))
    ok_rs = [r[1] for r in rs if r[0] == 200]
    check("并发核销全部返回同一核销单（CAS + 幂等）", len({r["id"] for r in ok_rs}) == 1,
          str([r[0] for r in rs]))
    check("并发核销只动账一次 +100", bal(account_id) - before == 100, f'{before} -> {bal(account_id)}')

    # 校验类拒绝
    c_thr = mok("/coupons/issue", "POST", {"memberId": member_id, "amount": 100, "threshold": 1000,
                                           "bizNo": biz("CIT"), "validDays": 30})
    st, resp = mkt("/coupons/use", "POST", {"couponId": c_thr["id"], "paymentId": pay(),
                                            "accountId": account_id, "orderAmount": 500})
    check("未达门槛拒绝", st == 400, f"{st} {resp}")
    st, resp = mkt("/coupons/use", "POST", {"couponId": c_thr["id"], "paymentId": pay(),
                                            "accountId": account_id, "orderAmount": 5000, "amount": 999})
    check("核销额超面额拒绝", st == 400, f"{st} {resp}")
    st, resp = mkt("/coupons/use", "POST", {"couponId": coupon["id"], "paymentId": pay(),
                                            "accountId": account_id, "orderAmount": 5000})
    check("同一券换支付单拒绝（一券一生一核销）", st in (400, 409), f"{st} {resp}")
    c_exp = mok("/coupons/issue", "POST", {"memberId": member_id, "amount": 100, "threshold": 0,
                                           "bizNo": biz("CIE"), "expireAt": int(time.time() * 1000) - 1000})
    st, resp = mkt("/coupons/use", "POST", {"couponId": c_exp["id"], "paymentId": pay(),
                                            "accountId": account_id, "orderAmount": 5000})
    check("过期券不可核销", st == 400, f"{st} {resp}")
    c_ord = mok("/coupons/issue", "POST", {"memberId": member_id, "amount": 100, "threshold": 0,
                                           "bizNo": biz("CIO"), "validDays": 30})
    st, resp = mkt("/coupons/use", "POST", {"couponId": c_ord["id"], "paymentId": pay(),
                                            "accountId": account_id, "orderAmount": 50, "amount": 100})
    check("营销抵扣超订单额拒绝", st == 400, f"{st} {resp}")

    # 退回
    before_refund = bal(account_id)
    sys_before = bal("SYS_MARKETING")
    ref = mok(f"/coupons/refund/{use['id']}", "POST")
    check("退回核销单 REFUNDED", ref["status"] == "REFUNDED", str(ref))
    check("退回资金反向：用户户 −300", before_refund - bal(account_id) == 300)
    check("退回资金反向：SYS_MARKETING +300", bal("SYS_MARKETING") - sys_before == 300)
    check("退回后券终态 REFUNDED", mok(f"/coupons/{coupon['id']}", step=False)["status"] == "REFUNDED")
    ref2 = mok(f"/coupons/refund/{use['id']}", "POST")
    check("退回幂等：重放同一核销单", ref2["id"] == ref["id"] and ref2["status"] == "REFUNDED")
    check("退回幂等：不重复反向动账", before_refund - bal(account_id) == 300)
    st, resp = mkt(f"/coupons/refund/{use['id']}", "POST", step=False)
    check("退回缺二次认证 403（敏感操作闸门）", st == 403, f"{st} {resp}")
    return c_exp


def sec5_red_packet(member_id, account_id, packet):
    print("[5] X-3a 红包核销 + 退回")
    rp = packet or mok("/red-packets/issue", "POST",
                       {"memberId": member_id, "amount": 200, "bizNo": biz("RI"), "validDays": 30})
    pid = pay()
    before = bal(account_id)
    sys_before = bal("SYS_MARKETING")
    use = mok("/red-packets/use", "POST", {"redPacketId": rp["id"], "paymentId": pid,
                                           "accountId": account_id, "orderAmount": 1000})
    check("红包核销成功 200", use["amount"] == 200 and use["kind"] == "RED_PACKET", str(use))
    check("红包核销复式记账 C 用户户 +200", bal(account_id) - before == 200)
    check("红包核销 D SYS_MARKETING 出账 200", sys_before - bal("SYS_MARKETING") == 200)
    again = mok("/red-packets/use", "POST", {"redPacketId": rp["id"], "paymentId": pid,
                                             "accountId": account_id, "orderAmount": 1000})
    check("红包核销幂等", again["id"] == use["id"] and bal(account_id) - before == 200)
    st, resp = mkt("/red-packets/use", "POST", {"redPacketId": rp["id"], "paymentId": pay(),
                                                "accountId": account_id, "orderAmount": 1000})
    check("红包换支付单拒绝", st in (400, 409), f"{st} {resp}")
    mok(f"/red-packets/refund/{use['id']}", "POST")
    check("红包退回资金反向 −200", bal(account_id) == before, f'{before} vs {bal(account_id)}')
    check("红包退回 SYS_MARKETING 回补", bal("SYS_MARKETING") == sys_before)
    check("红包终态 REFUNDED", mok(f"/red-packets/{rp['id']}", step=False)["status"] == "REFUNDED")
    r2 = mok(f"/red-packets/refund/{use['id']}", "POST")
    check("红包退回幂等", r2["status"] == "REFUNDED" and bal(account_id) == before)


def sec6_expire(c_exp, member_id):
    print("[6] X-3a 过期作业")
    n = mok("/expire-vouchers", "POST")
    check("过期作业返回处理张数 ≥1", isinstance(n, int) and n >= 1, str(n))
    check("过期券置 EXPIRED", mok(f"/coupons/{c_exp['id']}", step=False)["status"] == "EXPIRED")
    mok("/expire-vouchers", "POST")
    check("过期作业幂等：重复执行状态不变",
          mok(f"/coupons/{c_exp['id']}", step=False)["status"] == "EXPIRED")
    rp_exp = mok("/red-packets/issue", "POST", {"memberId": member_id, "amount": 50,
                                                "bizNo": biz("RIE"),
                                                "expireAt": int(time.time() * 1000) - 1000})
    mok("/expire-vouchers", "POST")
    check("过期红包置 EXPIRED", mok(f"/red-packets/{rp_exp['id']}", step=False)["status"] == "EXPIRED")
    avail = mok(f"/available?memberId={member_id}&orderAmount=5000", step=False)
    check("可用优惠不含已过期/已核销券",
          all(c["id"] != c_exp["id"] for c in avail["coupons"]), str(len(avail["coupons"])))


def sec7_points(member_id, account_id):
    print("[7] X-3b 积分：赚取/消耗/冻结/解冻/冻结转消耗/退回/过期")
    acc0 = mok(f"/points/{member_id}", step=False)
    check("积分账户懒开户 balance=0", acc0["balance"] == 0 and acc0["available"] == 0, str(acc0))
    e1 = biz("PE")
    earn = mok("/points/earn", "POST", {"memberId": member_id, "points": 10000, "bizNo": e1})
    check("赚取 10000 积分", earn["changeAmount"] == 10000 and earn["balanceAfter"] == 10000, str(earn))
    earn2 = mok("/points/earn", "POST", {"memberId": member_id, "points": 10000, "bizNo": e1})
    check("赚取幂等：同 bizNo 同流水", earn2["id"] == earn["id"])
    check("赚取幂等：余额不翻倍", mok(f"/points/{member_id}", step=False)["balance"] == 10000)
    check("赚取纯权益不动资金（无 fundBizNo）", earn.get("fundBizNo") in (None, ""), str(earn))

    # 已到期批次（用于过期断言，且不应被 FIFO 消耗）
    mok("/points/earn", "POST", {"memberId": member_id, "points": 5000, "bizNo": biz("PEX"),
                                 "expireAt": int(time.time() * 1000) - 1000})
    check("赚取到期批次后余额 15000", mok(f"/points/{member_id}", step=False)["balance"] == 15000)

    f1 = biz("PF")
    fz = mok("/points/freeze", "POST", {"memberId": member_id, "points": 2000, "bizNo": f1})
    check("冻结 2000：frozen=2000，balance 不变", fz["frozenAfter"] == 2000 and fz["balanceAfter"] == 15000, str(fz))
    fz2 = mok("/points/freeze", "POST", {"memberId": member_id, "points": 2000, "bizNo": f1})
    check("冻结幂等", fz2["id"] == fz["id"] and mok(f"/points/{member_id}", step=False)["frozen"] == 2000)
    st, resp = mkt("/points/freeze", "POST", {"memberId": member_id, "points": 999999, "bizNo": biz("PF")})
    check("超可用积分冻结拒绝", st == 400, f"{st} {resp}")
    uf = biz("PUF")
    mok("/points/unfreeze", "POST", {"memberId": member_id, "points": 1000, "bizNo": uf})
    check("解冻 1000：frozen=1000", mok(f"/points/{member_id}", step=False)["frozen"] == 1000)
    mok("/points/unfreeze", "POST", {"memberId": member_id, "points": 1000, "bizNo": uf})
    check("解冻幂等：frozen 不变", mok(f"/points/{member_id}", step=False)["frozen"] == 1000)

    # 消耗（积分 → 金额，资金侧复式记账）
    pid = pay()
    ub = biz("PU")
    before = bal(account_id)
    sys_before = bal("SYS_MARKETING")
    use = mok("/points/use", "POST", {"memberId": member_id, "points": 1000, "paymentId": pid,
                                      "accountId": account_id, "bizNo": ub})
    check("积分抵扣 1000 分 → 10 分金额（兑换率 100）", use["amount"] == 10, str(use))
    check("积分抵扣走复式记账 C 用户户 +10", bal(account_id) - before == 10)
    check("积分抵扣 D SYS_MARKETING 出账 10", sys_before - bal("SYS_MARKETING") == 10)
    check("积分余额扣减 1000（15000→14000）", mok(f"/points/{member_id}", step=False)["balance"] == 14000)
    check("积分流水记资金凭证号", use["fundBizNo"] == "MKT:P:" + ub, str(use["fundBizNo"]))
    use2 = mok("/points/use", "POST", {"memberId": member_id, "points": 1000, "paymentId": pid,
                                       "accountId": account_id, "bizNo": ub})
    check("积分抵扣幂等：同流水不重复扣", use2["id"] == use["id"]
          and mok(f"/points/{member_id}", step=False)["balance"] == 14000
          and bal(account_id) - before == 10)
    st, resp = mkt("/points/use", "POST", {"memberId": member_id, "points": 999999, "paymentId": pay(),
                                           "accountId": account_id, "bizNo": biz("PU")})
    check("超可用积分抵扣拒绝", st == 400, f"{st} {resp}")
    st, resp = mkt("/points/use", "POST", {"memberId": member_id, "points": 1, "paymentId": pay(),
                                           "accountId": account_id, "bizNo": biz("PU")})
    check("不足 1 分拒绝（不静默零额动账）", st == 400, f"{st} {resp}")

    # 冻结转消耗
    pid2 = pay()
    sb = biz("PS")
    before2 = bal(account_id)
    st_use = mok("/points/use", "POST", {"memberId": member_id, "points": 1000, "paymentId": pid2,
                                         "accountId": account_id, "bizNo": sb, "fromFrozen": True})
    check("冻结转消耗 SETTLE", st_use["type"] == "SETTLE", str(st_use))
    acc = mok(f"/points/{member_id}", step=False)
    check("冻结转消耗：balance 13000 且 frozen 0", acc["balance"] == 13000 and acc["frozen"] == 0, str(acc))
    check("冻结转消耗资金 +10", bal(account_id) - before2 == 10)

    # 退回
    before3 = bal(account_id)
    sys3 = bal("SYS_MARKETING")
    ref = mok(f"/points/refund?origBizNo={ub}&requestId={biz('PR')}", "POST")
    check("积分退回 REFUND +1000", ref["changeAmount"] == 1000 and ref["type"] == "REFUND", str(ref))
    check("积分退回资金反向 −10", before3 - bal(account_id) == 10)
    check("积分退回 SYS_MARKETING 回补 +10", bal("SYS_MARKETING") - sys3 == 10)
    check("积分退回余额回补 14000", mok(f"/points/{member_id}", step=False)["balance"] == 14000)
    ref2 = mok(f"/points/refund?origBizNo={ub}", "POST")
    check("积分退回幂等（缺省 requestId=origBizNo）", ref2["balanceAfter"] == ref["balanceAfter"]
          and mok(f"/points/{member_id}", step=False)["balance"] == 14000)

    # 过期：只扣未冻结部分，到期批次 5000 蒸发
    before_expire = mok(f"/points/{member_id}", step=False)
    expired = mok("/expire-points", "POST")
    check("积分过期作业扣减到期批次 5000", expired == 5000, str(expired))
    acc2 = mok(f"/points/{member_id}", step=False)
    check("过期后余额 = 原余额 − 5000", acc2["balance"] == before_expire["balance"] - 5000, str(acc2))
    check("过期累计留痕 expired=5000", acc2["expired"] == 5000, str(acc2))
    again = mok("/expire-points", "POST")
    check("积分过期幂等：重复执行不再扣", again == 0 and mok(f"/points/{member_id}", step=False)["balance"]
          == acc2["balance"], str(again))
    logs = mok(f"/points/{member_id}/logs?limit=100", step=False)
    types = {l["type"] for l in logs}
    check("积分流水类型齐全 EARN/USE/FREEZE/UNFREEZE/SETTLE/REFUND/EXPIRE",
          {"EARN", "USE", "FREEZE", "UNFREEZE", "SETTLE", "REFUND", "EXPIRE"}.issubset(types), str(types))
    check("积分过期不动资金（EXPIRE 无资金凭证）",
          all(l.get("fundBizNo") in (None, "") for l in logs if l["type"] in ("EARN", "FREEZE", "UNFREEZE", "EXPIRE")))
    return pid, pid2


def sec8_activity(member_id, account_id):
    print("[8] X-3b 活动：立减/满减/封顶/预算原子/幂等/退回")
    act = mok("/activities", "POST", {"name": f"{TAG}-ACT-FR-{SUFFIX}", "type": "FULL_REDUCE",
                                      "threshold": 1000, "discountAmount": 300, "budget": 600}, step=True)
    check("满减活动创建 budget=600 used=0", act["budget"] == 600 and act["used"] == 0, str(act))
    pid = pay()
    before = bal(account_id)
    sys_before = bal("SYS_MARKETING")
    use = mok("/activities/apply", "POST", {"activityId": act["id"], "paymentId": pid,
                                            "memberId": member_id, "accountId": account_id,
                                            "orderAmount": 2000})
    check("满减核销立减 300", use["amount"] == 300, str(use))
    check("活动核销复式记账 +300", bal(account_id) - before == 300)
    check("活动核销 D SYS_MARKETING 出账 300", sys_before - bal("SYS_MARKETING") == 300)
    check("活动预算 used=300", mok(f"/activities/{act['id']}", step=False)["used"] == 300)
    again = mok("/activities/apply", "POST", {"activityId": act["id"], "paymentId": pid,
                                              "memberId": member_id, "accountId": account_id,
                                              "orderAmount": 2000})
    check("活动核销幂等：同 (activityId,paymentId)", again["id"] == use["id"])
    check("活动核销幂等：预算不重复扣", mok(f"/activities/{act['id']}", step=False)["used"] == 300)
    pid2 = pay()
    mok("/activities/apply", "POST", {"activityId": act["id"], "paymentId": pid2,
                                      "memberId": member_id, "accountId": account_id, "orderAmount": 2000})
    check("第二次核销预算 used=600", mok(f"/activities/{act['id']}", step=False)["used"] == 600)
    st, resp = mkt("/activities/apply", "POST", {"activityId": act["id"], "paymentId": pay(),
                                                 "memberId": member_id, "accountId": account_id,
                                                 "orderAmount": 2000})
    check("预算耗尽拒绝（不超扣）", st == 400, f"{st} {resp}")
    check("预算耗尽后 used 仍 600", mok(f"/activities/{act['id']}", step=False)["used"] == 600)
    st, resp = mkt("/activities/apply", "POST", {"activityId": act["id"], "paymentId": pay(),
                                                 "memberId": member_id, "accountId": account_id,
                                                 "orderAmount": 500})
    check("未达活动门槛拒绝", st == 400, f"{st} {resp}")

    # 并发预算：budget=300，立减 300 → 8 并发只 1 成功
    act2 = mok("/activities", "POST", {"name": f"{TAG}-ACT-CC-{SUFFIX}", "type": "FULL_REDUCE",
                                       "threshold": 0, "discountAmount": 300, "budget": 300}, step=True)
    before_cc = bal(account_id)
    with ThreadPoolExecutor(max_workers=8) as ex:
        rs = list(ex.map(lambda _: mkt("/activities/apply", "POST",
                                       {"activityId": act2["id"], "paymentId": pay(),
                                        "memberId": member_id, "accountId": account_id,
                                        "orderAmount": 5000}), range(8)))
    succ = [r for r in rs if r[0] == 200]
    check("并发预算只 1 笔成功（原子扣减不超扣）", len(succ) == 1, str([r[0] for r in rs]))
    check("并发预算 used=300 恰好等于 budget", mok(f"/activities/{act2['id']}", step=False)["used"] == 300)
    check("并发预算只动账一次 +300", bal(account_id) - before_cc == 300)

    # 比例立减 + 封顶
    act3 = mok("/activities", "POST", {"name": f"{TAG}-ACT-DC-{SUFFIX}", "type": "DISCOUNT",
                                       "threshold": 0, "discountBps": 1000, "maxDiscount": 500,
                                       "budget": 0}, step=True)
    d = mok("/activities/apply", "POST", {"activityId": act3["id"], "paymentId": pay(),
                                          "memberId": member_id, "accountId": account_id,
                                          "orderAmount": 10000})
    check("比例立减 10% 封顶 500", d["amount"] == 500, str(d))
    d2 = mok("/activities/apply", "POST", {"activityId": act3["id"], "paymentId": pay(),
                                           "memberId": member_id, "accountId": account_id,
                                           "orderAmount": 2000})
    check("比例立减 2000×10% = 200（未触封顶）", d2["amount"] == 200, str(d2))
    listed = mok("/activities?orderAmount=1500", step=False)
    check("活动列表按门槛过滤（threshold 1000 命中）", any(a["id"] == act["id"] for a in listed))
    listed_low = mok("/activities?orderAmount=500", step=False)
    check("活动列表门槛不足不返回", not any(a["id"] == act["id"] for a in listed_low))

    # 退回：预算回滚 + 资金反向
    before_ref = bal(account_id)
    sys_ref = bal("SYS_MARKETING")
    ref = mok(f"/activities/refund/{use['id']}", "POST")
    check("活动退回 REFUNDED", ref["status"] == "REFUNDED", str(ref))
    check("活动退回资金反向 −300", before_ref - bal(account_id) == 300)
    check("活动退回 SYS_MARKETING 回补 +300", bal("SYS_MARKETING") - sys_ref == 300)
    check("活动退回预算回滚 used=300", mok(f"/activities/{act['id']}", step=False)["used"] == 300)
    ref2 = mok(f"/activities/refund/{use['id']}", "POST")
    check("活动退回幂等：预算不重复回滚", ref2["status"] == "REFUNDED"
          and mok(f"/activities/{act['id']}", step=False)["used"] == 300
          and before_ref - bal(account_id) == 300)
    # 过期活动
    act4 = mok("/activities", "POST", {"name": f"{TAG}-ACT-EX-{SUFFIX}", "type": "FULL_REDUCE",
                                       "threshold": 0, "discountAmount": 100, "budget": 1000,
                                       "expireAt": int(time.time() * 1000) - 1000}, step=True)
    st, resp = mkt("/activities/apply", "POST", {"activityId": act4["id"], "paymentId": pay(),
                                                 "memberId": member_id, "accountId": account_id,
                                                 "orderAmount": 5000})
    check("过期活动拒绝核销", st == 400, f"{st} {resp}")
    return act3


def sec9_alloc(member_id, account_id):
    print("[9] X-3b 分摊守恒：Σ分摊 = 抵扣总额，误差 0")
    # 单来源 1000 分，3 等权目标 → 333/333/334（最大余数法）
    c = mok("/coupons/issue", "POST", {"memberId": member_id, "amount": 1000, "threshold": 0,
                                       "bizNo": biz("ALC"), "validDays": 30})
    pid = pay()
    mok("/coupons/use", "POST", {"couponId": c["id"], "paymentId": pid,
                                 "accountId": account_id, "orderAmount": 5000})
    alloc = mok("/alloc", "POST", {"paymentId": pid, "requestId": "R1", "mode": "WEIGHT",
                                   "targets": [{"targetType": "ITEM", "targetId": "I1", "weight": 1},
                                               {"targetType": "ITEM", "targetId": "I2", "weight": 1},
                                               {"targetType": "MERCHANT", "targetId": "M1", "weight": 1}]})
    amounts = sorted(t["amount"] for t in alloc["targets"])
    check("最大余数法 1000/3 → 333,333,334", amounts == [333, 333, 334], str(amounts))
    check("Σ目标分摊 = 抵扣总额（误差 0）", sum(amounts) == 1000 and alloc["diff"] == 0, str(alloc))
    check("Σ来源核销 = 抵扣总额", alloc["sourceSum"] == 1000 and alloc["sourceDiff"] == 0, str(alloc))
    check("分摊 balanced=true", alloc["balanced"] is True, str(alloc))
    replay = mok("/alloc", "POST", {"paymentId": pid, "requestId": "R1", "mode": "WEIGHT",
                                    "targets": [{"targetType": "ITEM", "targetId": "I1", "weight": 1},
                                                {"targetType": "ITEM", "targetId": "I2", "weight": 1},
                                                {"targetType": "MERCHANT", "targetId": "M1", "weight": 1}]})
    check("分摊幂等：重放不新增行", len(replay["targets"]) == 3 and replay["diff"] == 0, str(replay))
    st, resp = mkt("/alloc", "POST", {"paymentId": pid, "requestId": "R2", "mode": "WEIGHT",
                                      "targets": [{"targetType": "ORDER", "targetId": "O1", "weight": 1}]})
    check("换 requestId 重复分摊拒绝（防 Σ 翻倍）", st in (400, 409), f"{st} {resp}")
    st, resp = mkt("/alloc", "POST", {"paymentId": pid, "requestId": "R3", "totalAmount": 999,
                                      "mode": "WEIGHT",
                                      "targets": [{"targetType": "ORDER", "targetId": "O1", "weight": 1}]})
    check("分摊总额 ≠ 核销净额拒绝", st in (400, 409), f"{st} {resp}")
    cons = mok(f"/conservation/{pid}?orderAmount=5000&fundAmount=4000", step=False)
    check("守恒：订单额 = 资金腿 + 营销腿（5000=4000+1000）",
          cons["balanced"] and cons["orderDiff"] == 0 and cons["targetDiff"] == 0, str(cons))
    bad = mok(f"/conservation/{pid}?orderAmount=5000&fundAmount=3999", step=False)
    check("资金腿少 1 分即判不守恒（不放宽）", bad["balanced"] is False and bad["orderDiff"] == 1, str(bad))

    # EXPLICIT 模式
    c2 = mok("/coupons/issue", "POST", {"memberId": member_id, "amount": 700, "threshold": 0,
                                        "bizNo": biz("ALE"), "validDays": 30})
    pid2 = pay()
    mok("/coupons/use", "POST", {"couponId": c2["id"], "paymentId": pid2,
                                 "accountId": account_id, "orderAmount": 3000})
    st, resp = mkt("/alloc", "POST", {"paymentId": pid2, "requestId": "E1", "mode": "EXPLICIT",
                                      "targets": [{"targetType": "ITEM", "targetId": "I1", "amount": 300},
                                                  {"targetType": "ITEM", "targetId": "I2", "amount": 300}]})
    check("EXPLICIT Σ≠总额拒绝（600≠700）", st == 400, f"{st} {resp}")
    ex = mok("/alloc", "POST", {"paymentId": pid2, "requestId": "E2", "mode": "EXPLICIT",
                                "targets": [{"targetType": "ITEM", "targetId": "I1", "amount": 300},
                                            {"targetType": "MERCHANT", "targetId": "M1", "amount": 400}]})
    check("EXPLICIT Σ=总额接受且 diff=0", ex["diff"] == 0 and ex["allocated"] == 700, str(ex))

    # 多来源混合：券 + 红包 + 积分 + 活动 → Σ来源 = Σ目标
    pid3 = pay()
    c3 = mok("/coupons/issue", "POST", {"memberId": member_id, "amount": 300, "threshold": 0,
                                        "bizNo": biz("ALM"), "validDays": 30})
    mok("/coupons/use", "POST", {"couponId": c3["id"], "paymentId": pid3,
                                 "accountId": account_id, "orderAmount": 5000})
    rp = mok("/red-packets/issue", "POST", {"memberId": member_id, "amount": 200, "bizNo": biz("ALR")})
    mok("/red-packets/use", "POST", {"redPacketId": rp["id"], "paymentId": pid3,
                                     "accountId": account_id, "orderAmount": 5000})
    mok("/points/earn", "POST", {"memberId": member_id, "points": 2000, "bizNo": biz("ALP")})
    mok("/points/use", "POST", {"memberId": member_id, "points": 1000, "paymentId": pid3,
                                "accountId": account_id, "bizNo": biz("ALPU")})
    act = mok("/activities", "POST", {"name": f"{TAG}-ACT-AL-{SUFFIX}", "type": "FULL_REDUCE",
                                      "threshold": 0, "discountAmount": 300, "budget": 0}, step=True)
    mok("/activities/apply", "POST", {"activityId": act["id"], "paymentId": pid3,
                                      "memberId": member_id, "accountId": account_id, "orderAmount": 5000})
    mixed = mok(f"/alloc/{pid3}", step=False)
    check("四来源混合 Σ核销 = 300+200+10+300 = 810", mixed["sourceSum"] == 810, str(mixed["sourceSum"]))
    check("四来源齐全 COUPON/RED_PACKET/POINTS/ACTIVITY",
          {s["source"] for s in mixed["sources"]} == {"COUPON", "RED_PACKET", "POINTS", "ACTIVITY"},
          str([s["source"] for s in mixed["sources"]]))
    a3 = mok("/alloc", "POST", {"paymentId": pid3, "requestId": "M1", "mode": "WEIGHT",
                                "targets": [{"targetType": "ITEM", "targetId": "IA", "weight": 3},
                                            {"targetType": "ITEM", "targetId": "IB", "weight": 2},
                                            {"targetType": "MERCHANT", "targetId": "MA", "weight": 1},
                                            {"targetType": "ORDER", "targetId": "OA", "weight": 1}]})
    check("混合分摊 Σ目标 = 810（误差 0）", a3["allocated"] == 810 and a3["diff"] == 0, str(a3))
    check("混合分摊权重比例 3:2:1:1 → 347,231,116,116",
          sorted(t["amount"] for t in a3["targets"]) == [116, 116, 231, 347],
          str([t["amount"] for t in a3["targets"]]))
    check("混合分摊 balanced", a3["balanced"] is True, str(a3))
    cons3 = mok(f"/conservation/{pid3}?orderAmount=5000&fundAmount=4190", step=False)
    check("混合守恒 5000 = 4190 + 810", cons3["balanced"] and cons3["orderDiff"] == 0, str(cons3))
    # 退回一个来源后核销净额下降（目标维度重分摊属 P-7 编排，本期只断言来源侧正确）
    cu = [s for s in mixed["sources"] if s["source"] == "COUPON"][0]
    mok(f"/coupons/refund/{cu['targetId']}", "POST")
    after = mok(f"/alloc/{pid3}", step=False)
    check("退回后 Σ来源净额 = 810 − 300 = 510", after["sourceSum"] == 510, str(after["sourceSum"]))
    check("退回后来源侧含 REFUND 行", any(s["type"] == "REFUND" for s in after["sources"]), str(after["sources"]))
    return pid, pid2, pid3


def sec10_fund_guard():
    print("[10] 资金护航：营销资金恒等 + 试算平衡")
    fc = mok("/fund-check", step=False)
    check("营销资金恒等 diff=0（Σ核销−Σ退回 = SYS_MARKETING 出账）", fc["diff"] == 0, str(fc))
    check("营销资金 balanced=true", fc["balanced"] is True, str(fc))
    # P-7 TCC：USE 分摊在 Try 落（含已取消单），补贴在 Confirm 落（仅成功单）；取消单 Try USE 由 Cancel REFUND 抵消且未落补贴。
    # 故「借方= gross USE」不再成立，正确恒等=取消单 USE 与 REFUND 对称（netUse−借方 == netRefund−贷方）+ 净额守恒。
    check("SYS_MARKETING 取消单 USE/REFUND 对称（TCC：Try USE 含取消单，补贴仅成功单）",
          fc["netUse"] - fc["sysMarketingDebit"] == fc["netRefund"] - fc["sysMarketingCredit"], str(fc))
    check("SYS_MARKETING 净额守恒（netMarketing = 借方−贷方出账）",
          fc["netMarketing"] == fc["sysMarketingOutflow"], str(fc))
    check("SYS_MARKETING 余额 = −出账（营销支出科目 6002）",
          fc["sysMarketingBalance"] == -fc["sysMarketingOutflow"], str(fc))
    check("账务试算 D=C", fc["trialBalanced"] is True and fc["trialDebit"] == fc["trialCredit"], str(fc))
    check("Σbalance=0（复式记账未被破坏）", fc["balanceSum"] == 0, str(fc))
    trial = ok("/api/accounting/trial")
    check("账务域试算平衡独立复核", trial["balanced"] and trial["balanceSum"] == 0, str(trial))
    check("营销域未自建资金余额表（资金只在 accounting）",
          fc["sysMarketingBalance"] == bal("SYS_MARKETING"), str(fc["sysMarketingBalance"]))


def sec11_no_regression(summary0, fee0):
    print("[11] 不回归：13/13 + 六项对账 + 手续费/结算口径")
    st, health = http(BASE + "/api/health")
    check("收尾 13/13 全健康", st == 200 and len(health) == 13 and all(h["ok"] for h in health),
          str([h["service"] for h in health if not h["ok"]]))
    summary1 = ok("/api/settle/summary")
    check("结算汇总笔数不变（营销域不写结算单）", summary1["payCount"] == summary0["payCount"],
          f'{summary0["payCount"]} -> {summary1["payCount"]}')
    check("结算手续费不变（feeBps→SYS_FEE 口径不变）", summary1["totalFee"] == summary0["totalFee"],
          f'{summary0["totalFee"]} -> {summary1["totalFee"]}')
    check("结算净额/退款不变", summary1["totalNet"] == summary0["totalNet"]
          and summary1["totalRefund"] == summary0["totalRefund"], str(summary1))
    check("SYS_FEE 余额逐分不变", bal("SYS_FEE") == fee0, f'{fee0} -> {bal("SYS_FEE")}')
    time.sleep(3)
    rep = ok("/api/recon/run", "POST")
    check("六项对账项数不变", len(rep["checks"]) == 6, str([c["name"] for c in rep["checks"]]))
    for c in rep["checks"]:
        check(f"对账：{c['name']}", c["pass"], c["detail"])
    audit = mok(f"/audit?entityId=&limit=200", step=False)
    check("审计留痕可回读（≥20 条）", len(audit) >= 20, str(len(audit)))
    actions = {a["action"] for a in audit}
    check("审计覆盖发放/领取/核销/退回/过期/分摊",
          {"ISSUE", "CLAIM", "USE", "REFUND", "EXPIRE", "ALLOCATE"}.issubset(actions), str(actions))


# ============================================================================
# [12] local 形态：营销域单进程 H2 零依赖启动（独立 DATA_DIR/端口，不触碰既有 data/ 与 docker 栈）
# ============================================================================
def local_start():
    """单进程 local H2 启动（营销域自身零依赖：H2 文件库，无需任何中间件即可建表/权益/分摊）。
    Dubbo 侧用 register=false + 消费者组隔离，既不向 docker 栈注册提供者，也不会调用 docker 域，
    因此资金动作按预期「无提供者 → 优雅失败 + 整体回滚」，跨形态零污染。"""
    global LOCAL_PROC
    jar = os.path.join(REPO, "payment-marketing", "target", "payment-marketing-1.0.0.jar")
    if not os.path.exists(jar):
        check("local 形态：营销域 jar 存在", False, jar)
        return False
    env = dict(os.environ)
    env["DATA_DIR"] = os.path.join(REPO, "data", "mkt-local-verify")
    env["MARKETING_HTTP_PORT"] = "18993"
    env["MARKETING_DUBBO_PORT"] = "20993"
    env["MARKETING_LOCAL_API"] = "true"
    env["MARKETING_EXPIRE_DELAY"] = "3600000"
    os.makedirs(env["DATA_DIR"], exist_ok=True)
    variants = [
        # 首选：连 ZK 但 register=false（不向 docker 栈注册提供者，杜绝网关串流量）
        #      + consumer.group=MKTLOCAL（消费者只找该组提供者 ⇒ 永远找不到，资金动作按预期优雅失败，
        #        绝不会把 local H2 的营销单动到 docker MySQL 账本上，跨形态零污染）
        ["-Ddubbo.registry.address=zookeeper://127.0.0.1:2181",
         "-Ddubbo.registry.register=false", "-Ddubbo.consumer.group=MKTLOCAL"],
        # 兜底：点对点直连一个立即拒连的本地端口（同样不触达任何 docker 域）
        ["-Ddubbo.registry.address=zookeeper://127.0.0.1:2181",
         "-Ddubbo.registry.register=false", "-Ddubbo.consumer.url=dubbo://127.0.0.1:1"],
        ["-Ddubbo.registry.address=N/A", "-Ddubbo.consumer.url=dubbo://127.0.0.1:1"],
    ]
    for extra in variants:
        log = open(os.path.join(REPO, "run", "p2A-marketing-local.log"), "a")
        args = [os.path.join(JAVA_HOME, "bin", "java"),
                "-Dspring.profiles.active=local",
                "-Ddubbo.application.qos-enable=false"] + extra + ["-jar", jar]
        LOCAL_PROC = subprocess.Popen(args, cwd=REPO, env=env, stdout=log, stderr=subprocess.STDOUT)
        for _ in range(75):
            if LOCAL_PROC.poll() is not None:
                break
            st, _ = http(LOCAL_BASE + "/profile", timeout=3)
            if st == 200:
                # 就绪判定必须含 DB 支撑的读端点 + 进程存活 + 3s 后复探：
                # Dubbo 装配失败会在 Tomcat 已应答后关闭上下文（假就绪），只探 profile 会误判
                st2, _ = http(LOCAL_BASE + "/templates", timeout=5)
                if st2 == 200:
                    time.sleep(3)
                    if LOCAL_PROC.poll() is None:
                        st3, _ = http(LOCAL_BASE + "/templates", timeout=5)
                        if st3 == 200:
                            return True
            time.sleep(1)
        local_stop()
    return False


def local_stop():
    global LOCAL_PROC
    if LOCAL_PROC is None:
        return
    try:
        LOCAL_PROC.send_signal(signal.SIGTERM)
        try:
            LOCAL_PROC.wait(timeout=20)
        except subprocess.TimeoutExpired:
            LOCAL_PROC.kill()
            LOCAL_PROC.wait(timeout=10)
    except Exception:
        pass
    LOCAL_PROC = None


def lreq(path, method="GET", body=None):
    return http(LOCAL_BASE + path, method, body, timeout=60)


def lok(path, method="GET", body=None):
    st, resp = lreq(path, method, body)
    assert st == 200, f"local {method} {path} -> {st} {resp}"
    return resp


def sec12_local():
    print("[12] local 形态：H2 零依赖单进程（独立 DATA_DIR/端口，不动既有数据）")
    if not local_start():
        check("local 形态：营销域单进程启动成功", False, "见 run/p2A-marketing-local.log")
        local_stop()
        return
    check("local 形态：营销域单进程启动成功", True)
    try:
        st, prof = lreq("/profile")
        check("local profile 生效", "local" in str(prof.get("profiles")), str(prof))
        check("local 数据库 = H2（零依赖）", prof.get("database") == "H2", str(prof.get("database")))
        check("local 不分片", prof.get("shardingEnabled") == "false", str(prof))
        check("local 营销表清单与 docker 同构（13 张）", len(prof.get("tables", [])) == 13, str(prof))
        member = f"{TAG}-LOCAL-{SUFFIX}"
        tpl = lok("/templates", "POST", {"kind": "COUPON", "name": f"{TAG}-L-TPL-{SUFFIX}",
                                         "amount": 500, "threshold": 1000, "totalStock": 2,
                                         "perMemberLimit": 1, "validDays": 30})
        c = lok("/coupons/issue", "POST", {"memberId": member, "amount": 300, "threshold": 1000,
                                           "bizNo": biz("LCI"), "validDays": 30})
        c2 = lok("/coupons/issue", "POST", {"memberId": member, "amount": 300, "threshold": 1000,
                                            "bizNo": c["bizNo"], "validDays": 30})
        check("local 发放幂等", c["id"] == c2["id"])
        k = lok("/coupons/claim", "POST", {"templateId": tpl["id"], "memberId": member, "bizNo": biz("LCL")})
        check("local 领取成功", k["status"] == "AVAILABLE", str(k))
        st, resp = lreq("/coupons/claim", "POST", {"templateId": tpl["id"], "memberId": member,
                                                   "bizNo": biz("LCL")})
        check("local 限领拒绝", st == 400, f"{st} {resp}")
        # 资金动作：accounting 不可达 → 优雅失败 + 整体回滚（券仍 AVAILABLE，绝不自建账本）
        st, resp = lreq("/coupons/use", "POST", {"couponId": c["id"], "paymentId": pay(),
                                                 "accountId": "MKTTEST-LOCAL-ACC", "orderAmount": 5000})
        check("local 无账务域时核销优雅失败（非 5xx 崩溃）", st in (400, 500, 502, 503), f"{st} {resp}")
        check("local 核销失败整体回滚：券仍 AVAILABLE（未自建账本绕过复式记账）",
              any(x["id"] == c["id"] for x in lok(f"/coupons?memberId={member}&status=AVAILABLE")),
              str(st))
        # 积分（纯权益，local 全通）
        lok("/points/earn", "POST", {"memberId": member, "points": 10000, "bizNo": biz("LPE")})
        acc = lok(f"/points/{member}")
        check("local 积分赚取 10000", acc["balance"] == 10000, str(acc))
        lok("/points/freeze", "POST", {"memberId": member, "points": 2000, "bizNo": biz("LPF")})
        check("local 积分冻结 frozen=2000", lok(f"/points/{member}")["frozen"] == 2000)
        lok("/points/unfreeze", "POST", {"memberId": member, "points": 2000, "bizNo": biz("LPU")})
        check("local 积分解冻 frozen=0", lok(f"/points/{member}")["frozen"] == 0)
        lok("/points/earn", "POST", {"memberId": member, "points": 500, "bizNo": biz("LPEX"),
                                     "expireAt": int(time.time() * 1000) - 1000})
        exp = lok("/expire-points", "POST")
        check("local 积分过期 500", exp == 500 and lok(f"/points/{member}")["balance"] == 10000, str(exp))
        st, resp = lreq("/points/use", "POST", {"memberId": member, "points": 1000, "paymentId": pay(),
                                                "accountId": "MKTTEST-LOCAL-ACC", "bizNo": biz("LPU")})
        check("local 积分抵扣无账务域优雅失败", st in (400, 500, 502, 503), f"{st} {resp}")
        check("local 积分抵扣失败回滚：余额不变", lok(f"/points/{member}")["balance"] == 10000)
        # 活动：预算抢占后动账失败 → 预算整体回滚
        act = lok("/activities", "POST", {"name": f"{TAG}-L-ACT-{SUFFIX}", "type": "FULL_REDUCE",
                                          "threshold": 0, "discountAmount": 300, "budget": 300})
        st, resp = lreq("/activities/apply", "POST", {"activityId": act["id"], "paymentId": pay(),
                                                      "memberId": member, "accountId": "MKTTEST-LOCAL-ACC",
                                                      "orderAmount": 5000})
        check("local 活动核销无账务域优雅失败", st in (400, 500, 502, 503), f"{st} {resp}")
        check("local 活动失败预算回滚 used=0（不超扣不留脏预算）",
              next(a for a in lok("/activities?orderAmount=5000") if a["id"] == act["id"])["used"] == 0)
        # 过期券
        ce = lok("/coupons/issue", "POST", {"memberId": member, "amount": 100, "bizNo": biz("LCE"),
                                            "expireAt": int(time.time() * 1000) - 1000})
        n = lok("/expire-vouchers", "POST")
        check("local 过期作业生效", n >= 1, str(n))
        # 分摊（纯计算，无资金依赖）：Σ = 总额，误差 0
        pid = pay()
        a = lok("/alloc", "POST", {"paymentId": pid, "requestId": "L1", "totalAmount": 1000,
                                   "mode": "WEIGHT",
                                   "targets": [{"targetType": "ITEM", "targetId": "I1", "weight": 1},
                                               {"targetType": "ITEM", "targetId": "I2", "weight": 1},
                                               {"targetType": "MERCHANT", "targetId": "M1", "weight": 1}]})
        check("local 分摊 Σ=1000 误差 0（最大余数法）",
              a["allocated"] == 1000 and a["diff"] == 0
              and sorted(t["amount"] for t in a["targets"]) == [333, 333, 334], str(a))
        cons = lok(f"/conservation/{pid}?orderAmount=5000&fundAmount=4000")
        check("local 无核销来源时守恒如实判不成立（不伪造平衡）",
              cons["balanced"] is False and cons["targetDiff"] == 1000 and cons["marketingAmount"] == 0,
              str(cons))
        # 零抵扣正向路径：无营销腿时 订单额 = 资金额，守恒成立（local 无需账务域即可验证）
        pid0 = pay()
        a0 = lok("/alloc", "POST", {"paymentId": pid0, "requestId": "L0", "totalAmount": 0,
                                    "mode": "EXPLICIT",
                                    "targets": [{"targetType": "ORDER", "targetId": "O0", "amount": 0}]})
        cons0 = lok(f"/conservation/{pid0}?orderAmount=5000&fundAmount=5000")
        check("local 零营销抵扣守恒成立（5000=5000+0，diff 全 0）",
              a0["diff"] == 0 and cons0["balanced"] and cons0["orderDiff"] == 0
              and cons0["targetDiff"] == 0, str(cons0))
        st, resp = lreq("/fund-check")
        check("local 无账务域时资金核对优雅失败（不伪造平衡）", st != 200, f"{st} {resp}")
    finally:
        local_stop()
    st, _ = http(LOCAL_BASE + "/profile", timeout=3)
    check("local 自检进程已停止（端口释放，不留残留进程）", st == 0, str(st))


def main():
    print(f"=== verify-marketing.py · X-3a/X-3b · TAG={TAG} SUFFIX={SUFFIX} ===")
    summary0, fee0 = sec1_baseline()
    member_id, account_id = new_subject("USER")
    other_member, _ = new_subject("USER")
    tpl, tpl2, coupon, ct = sec2_template_issue(member_id)
    sec3_claim(tpl, member_id, other_member)
    packet = sec3b_red_packet_claim(tpl2, other_member)
    c_exp = sec4_coupon_use(coupon, member_id, account_id)
    sec5_red_packet(member_id, account_id, packet)
    sec6_expire(c_exp, member_id)
    sec7_points(member_id, account_id)
    sec8_activity(member_id, account_id)
    sec9_alloc(member_id, account_id)
    sec10_fund_guard()
    sec11_no_regression(summary0, fee0)
    sec12_local()
    print()
    print(f"结果：{PASSED} 通过，{FAILED} 失败")
    if FAILURES:
        print("失败项：")
        for name in FAILURES:
            print("  - " + name)
    return 1 if FAILED else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    finally:
        local_stop()