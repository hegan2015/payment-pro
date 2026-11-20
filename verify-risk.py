#!/usr/bin/env python3
"""verify-risk.py — wave3 P1·B 泳道：R-1 规则引擎 / R-2 多维限额 / R-3 名单 / R-4 评分分级处置。

跑法: python3 verify-risk.py   （payment-pro 栈已启动，gateway 8080；local / docker 双 profile 通用）
      PP_PROFILE=local python3 verify-risk.py   （断言限额计数 provider 为 memory）

覆盖（对照四张卡验收）:
  [1] 基线与默认值口径：规则/限额/名单默认空、全局影子默认开、单笔限额 10000000 / 窗口 10000 / 上限 5
      不变；ride 典型流量 evaluate score=0 action=PASS
  [2] ride 典型流量真实支付不被误拦（BALANCE 同步 SUCCESS、手续费口径 18 分不变）
  [3] R-1 规则热更不重启生效 + 版本化 + 灰度比例/白名单 + 一键回滚（回滚不被配置轮询覆盖）
  [4] §5.4 误配置护栏：编译失败 / 执行异常 / 超时 → 放行并告警，内置保守规则仍生效
  [5] 影子模式只记录不拦截（decision_log shadow=true 可查）
  [6] R-2 多维限额矩阵：单笔/日/月 × 用户/商户/渠道/支付方式/卡 + 维度组合独立 + 并发不超扣
      + 拒绝不消耗额度 + 动态停用 + 回滚 + 用量周期 key
  [7] 受理前拒绝零悬挂单：限额拒绝的真实支付单终态 FAILED（非 PROCESSING），余额未动
  [8] R-3 黑/白/灰名单：白 > 黑 > 灰优先级、过期失效、软删、命中查询、变更审计（operator 留痕）
  [9] R-4 评分因子（频率/金额偏离/名单/设备/IP/时段/规则）→ 分级处置（放行/加强验证/限额/拒绝/转人工）
      + 转人工经 DisputeFacade 建单（source=PAYMENT、strategy=MANUAL）+ 人审 approve/reject 回写差错单
  [10] RBAC + 二次认证：未登录 401、无 X-Step-Up-Token 403、带 token 200
  [11] local 内存降级等价：同一限额矩阵在 force-memory 下结果逐项一致

数据保护：只新增 RISKTEST 前缀测试数据与风控配置快照；脚本退出时把风控配置源字节级还原，
并把规则/限额/名单/阈值复位为空（默认宽松），绝不删除任何既有业务数据。
"""
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor

BASE = os.environ.get("PP_GATEWAY", "http://127.0.0.1:8080")
ADMIN_PASS = os.environ.get("PP_ADMIN_PASSWORD", "admin123")
CONFIG_FILE = os.path.expanduser("~/.config/payment-pro/config.properties")
APOLLO_ADMIN = os.environ.get("PP_APOLLO_ADMIN", "http://127.0.0.1:18071")
APOLLO_APP = os.environ.get("PP_APOLLO_APP_ID", "payment-pro")
APOLLO_NS = "risk-rules"
PROFILE = os.environ.get("PP_PROFILE", "docker")
SUFFIX = uuid.uuid4().hex[:8]
# 卡号维度按 BIN 计数：用批次数字派生唯一 BIN，保证脚本可重复跑（不与历史 Redis 计数串扰）
NUMSUF = str(uuid.uuid4().int)[:6]
CARD_UM = "6222" + NUMSUF + "039999"
CARD_SINGLE = "6223" + NUMSUF + "048888"
CARD_DAY = "6224" + NUMSUF + "057777"
passed = failed = 0


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  [PASS] {name}")
    else:
        failed += 1
        print(f"  [FAIL] {name}  {detail}")


def req(method, path, body=None, token=None, base=None, step=None, timeout=30):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request((base or BASE) + path, data=data, method=method)
    r.add_header("Accept", "application/json")
    if data is not None:
        r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    if step:
        r.add_header("X-Step-Up-Token", step)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            text = resp.read().decode()
            try:
                return resp.status, json.loads(text) if text else None
            except Exception:
                return resp.status, text
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        try:
            return e.code, json.loads(text)
        except Exception:
            return e.code, {"error": text}
    except Exception as e:
        return 0, {"error": str(e)}


def poll(fn, pred, timeout=40, interval=1):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = fn()
        if pred(last):
            return last
        time.sleep(interval)
    return last


st, resp = req("POST", "/api/auth/login", {"username": "admin", "password": ADMIN_PASS})
assert st == 200 and resp.get("token"), f"admin 登录失败: {st} {resp}"
ADMIN = resp["token"]
st, resp = req("POST", "/api/security/step-up", {"password": ADMIN_PASS}, token=ADMIN)
assert st == 200 and resp.get("token"), f"二次认证失败: {st} {resp}"
STEP = resp["token"]


def ok(method, path, body=None, step=None):
    st, resp = req(method, path, body, token=ADMIN, step=step)
    assert st == 200, f"{method} {path} 期望 200 实际 {st}: {resp}"
    return resp


def bal(account_id):
    return ok("GET", f"/api/accounting/accounts/{account_id}/balance")


def engine():
    return ok("GET", "/api/risk/engine")


def ev(payer, amount, **kw):
    body = {"payerId": payer, "amount": amount, "method": kw.get("method", "BALANCE"),
            "scene": kw.get("scene", "PAY"), "traceId": "RISKTEST-" + SUFFIX}
    for k in ("merchantId", "cardNo", "cardBin", "deviceId", "ip", "channelCode", "bizNo"):
        if kw.get(k) is not None:
            body[k] = kw[k]
    return ok("POST", "/api/risk/evaluate", body)


def alerts(limit=200):
    return ok("GET", f"/api/risk/alerts?limit={limit}")


def alert_kinds():
    return {a.get("kind") for a in alerts()}


def decisions(payer, limit=20):
    return ok("GET", f"/api/risk/decisions?payerId={payer}&limit={limit}")


def versions(kind, limit=20):
    return ok("GET", f"/api/risk/versions?kind={kind}&limit={limit}")


# ---------------- 配置源适配（local 文件 / Apollo risk-rules namespace） ----------------
PROVIDER = ok("GET", "/api/config").get("provider")
ORIGINAL = None
ORIGINAL_KEYS = {}
PUSHED = {}


def read_local():
    if not os.path.exists(CONFIG_FILE):
        return None
    with open(CONFIG_FILE, "rb") as f:
        return f.read()


def write_local(text):
    os.makedirs(os.path.dirname(CONFIG_FILE), exist_ok=True)
    with open(CONFIG_FILE, "w") as f:
        f.write(text)
    os.chmod(CONFIG_FILE, 0o600)


def apollo_req(method, path, body=None):
    return req(method, path, body, base=APOLLO_ADMIN)


def apollo_ns_id():
    st, ns = apollo_req("GET", f"/apps/{APOLLO_APP}/clusters/default/namespaces/{APOLLO_NS}")
    if st == 200 and isinstance(ns, dict) and ns.get("id"):
        return ns["id"]
    st, resp = apollo_req("POST", f"/apps/{APOLLO_APP}/appnamespaces",
                          {"name": APOLLO_NS, "appId": APOLLO_APP, "format": "properties",
                           "comment": APOLLO_NS, "dataChangeCreatedBy": "apollo"})
    assert st in (200, 400), f"创建 namespace {APOLLO_NS} 失败: {st} {resp}"
    st, ns = apollo_req("GET", f"/apps/{APOLLO_APP}/clusters/default/namespaces/{APOLLO_NS}")
    assert st == 200, f"读取 namespace 失败: {st} {ns}"
    return ns["id"]


def apollo_items():
    st, items = apollo_req("GET", f"/apps/{APOLLO_APP}/clusters/default/namespaces/{APOLLO_NS}/items")
    if st != 200 or not isinstance(items, list):
        return {}
    return {i["key"]: i["id"] for i in items if i.get("key")}


def apollo_release(tag):
    st, resp = apollo_req("POST",
                          f"/apps/{APOLLO_APP}/clusters/default/namespaces/{APOLLO_NS}/releases"
                          f"?name=verify-risk-{tag}-{SUFFIX}&comment=verify-risk&operator=apollo")
    assert st == 200, f"Apollo 发布失败: {st} {resp}"
    time.sleep(3)


def apollo_push(items):
    st, _ = apollo_req("GET", f"/apps/{APOLLO_APP}")
    if st != 200:
        st, resp = apollo_req("POST", "/apps", {
            "appId": APOLLO_APP, "name": APOLLO_APP, "orgId": "TEST", "orgName": "TEST",
            "ownerName": "apollo", "ownerEmail": "apollo@localhost", "dataChangeCreatedBy": "apollo"})
        assert st == 200, f"创建 Apollo app 失败: {st} {resp}"
    ns_id = apollo_ns_id()
    by_key = apollo_items()
    for k, v in items.items():
        if k in by_key:
            st, resp = apollo_req("PUT",
                                  f"/apps/{APOLLO_APP}/clusters/default/namespaces/{APOLLO_NS}/items/{by_key[k]}",
                                  {"key": k, "value": str(v), "comment": "verify-risk",
                                   "dataChangeLastModifiedBy": "apollo"})
            assert st == 200, f"更新 item {k} 失败: {st} {resp}"
        else:
            st, resp = apollo_req("POST",
                                  f"/apps/{APOLLO_APP}/clusters/default/namespaces/{APOLLO_NS}/items",
                                  {"namespaceId": ns_id, "key": k, "value": str(v), "comment": "verify-risk",
                                   "dataChangeCreatedBy": "apollo", "dataChangeLastModifiedBy": "apollo"})
            assert st == 200, f"创建 item {k} 失败: {st} {resp}"
    apollo_release("push")


def apollo_clear(keys):
    by_key = apollo_items()
    for k in keys:
        if k in by_key:
            apollo_req("DELETE", f"/items/{by_key[k]}?operator=apollo")
    apollo_release("clear")


def push(items, reload=True):
    """下发风控配置（合并已推送键），返回 reload 后的引擎状态。"""
    PUSHED.update(items)
    if PROVIDER == "apollo":
        apollo_push(PUSHED)
    else:
        base = {}
        if ORIGINAL:
            for line in ORIGINAL.decode().splitlines():
                if "=" in line and not line.strip().startswith("#"):
                    k, v = line.split("=", 1)
                    base[k.strip()] = v.strip()
        base.update(PUSHED)
        write_local("".join(f"{k}={v}\n" for k, v in base.items()))
    if reload:
        return ok("POST", "/api/risk/reload", {})
    return None


def set_rules(rules, shadow=False, reload=True):
    return push({"risk.rules.json": json.dumps({"globalShadow": shadow, "rules": rules})}, reload)


def set_quotas(quotas, reload=True):
    return push({"risk.quota.rules.json": json.dumps(quotas)}, reload)


RESET_SEQ = [0]


def empty_json(obj=False):
    """语义为空但字面唯一的 JSON（保证每次复位都构成一次配置变更，覆盖控制台编辑）。"""
    RESET_SEQ[0] += 1
    pad = " " * RESET_SEQ[0]
    return "{" + pad + "}" if obj else "[" + pad + "]"


def reset_config():
    """复位：先把四类载荷显式置空（保证每 kind 都发生一次变更），再还原配置源。"""
    try:
        push({"risk.rules.json": json.dumps({"globalShadow": True, "rules": []}).replace("[]", empty_json()),
              "risk.quota.rules.json": empty_json(),
              "risk.list.entries.json": empty_json(),
              "risk.score.thresholds.json": empty_json(True),
              "risk.quota.force-memory": "false",
              "risk.score.amount-history": "false",
              "risk.score.freq-threshold": "5",
              "risk.score.risk-devices": "",
              "risk.score.risk-ips": "",
              "risk.score.risk-hours": "",
              "risk.rules.timeout-ms": "200",
              "risk.rules.hard-timeout-ms": "400"})
    except Exception as e:
        print(f"!! 复位下发失败: {e}")
    try:
        if PROVIDER == "apollo":
            apollo_clear(list(PUSHED.keys()))
        else:
            if ORIGINAL is None:
                if os.path.exists(CONFIG_FILE):
                    os.remove(CONFIG_FILE)
            else:
                write_local(ORIGINAL.decode())
        PUSHED.clear()
        ok("POST", "/api/risk/reload", {})
    except Exception as e:
        print(f"!! 配置源还原失败（需人工检查）: {e}")


def wait_payment(pid, status, timeout=25):
    return poll(lambda: ok("GET", f"/api/payments/{pid}"), lambda p: p.get("status") == status,
                timeout=timeout, interval=0.5)


def main():
    global ORIGINAL
    print(f"== verify-risk（批次 {SUFFIX}，配置源 {PROVIDER}，profile {PROFILE}）==")
    ORIGINAL = read_local()

    # ---------- 1. 基线与默认值口径 ----------
    print("[1] 基线：默认空规则/限额/名单 + 影子默认开 + 限额默认值口径不变")
    reset_config()
    e = engine()
    check("默认规则集为空", e.get("ruleCount") == 0, str(e.get("ruleCount")))
    check("默认限额集为空", e.get("quotaCount") == 0, str(e.get("quotaCount")))
    check("默认名单集为空", e.get("listCount") == 0, str(e.get("listCount")))
    check("全局影子模式默认开（新下发规则只观察）", e.get("globalShadow") is True, str(e.get("globalShadow")))
    check("规则引擎为 QLExpress", e.get("engine") == "QLExpress", str(e.get("engine")))
    cfg = ok("GET", "/api/config").get("effective", {})
    check("单笔限额默认 10000000 分未变", cfg.get("payment.risk.single-limit") == 10000000, str(cfg))
    check("限频窗口默认 10000ms 未变", cfg.get("payment.risk.window-millis") == 10000, str(cfg))
    check("限频上限默认 5 笔未变", cfg.get("payment.risk.window-max") == 5, str(cfg))
    th = ok("GET", "/api/risk/thresholds?scene=PAY")
    check("分级阈值默认 40/60/80/95", (th.get("challengeScore"), th.get("limitScore"),
                                    th.get("rejectScore"), th.get("reviewScore")) == (40, 60, 80, 95), str(th))
    ride_payer = "RISKTEST-ride-" + SUFFIX
    r = ev(ride_payer, 3000, merchantId="RISKTEST-M" + SUFFIX)
    check("ride 典型流量 evaluate 放行 score=0 action=PASS",
          r.get("pass") is True and r.get("score") == 0 and r.get("action") == "PASS", str(r)[:300])
    check("ride 典型流量无命中规则/名单/限额",
          not r.get("hitRules") and not r.get("listHit") and not r.get("quotaHit"), str(r)[:300])
    r = ev("RISKTEST-topup-" + SUFFIX, 50000, scene="TOPUP")
    check("ride 资金单（TOPUP）evaluate 放行", r.get("pass") is True and r.get("score") == 0, str(r)[:200])
    r = ev("RISKTEST-big-" + SUFFIX, 20000000)
    check("内置单笔限额仍生效且文案不变",
          r.get("pass") is False and r.get("reason") == "超出单笔限额 100000 元"
          and r.get("code") == "RISK_SINGLE_LIMIT", str(r)[:250])

    # ---------- 2. ride 典型流量真实支付 ----------
    print("[2] ride 典型流量真实支付不被误拦（BALANCE 同步 + 手续费口径不变）")
    merchant = ok("POST", "/api/members", {"name": f"RISKTEST商户{SUFFIX}", "type": "MERCHANT"})
    user = ok("POST", "/api/members", {"name": f"RISKTEST用户{SUFFIX}", "type": "USER"})
    ma = ok("POST", "/api/accounts", {"memberId": merchant["id"]})
    ua = ok("POST", "/api/accounts", {"memberId": user["id"]})
    ok("POST", "/api/accounting/internal/topup", {"accountId": ua["id"], "amount": 1000000,
                                                 "bizNo": "RISKTEST-topup-" + SUFFIX})
    m0, u0 = bal(ma["id"]), bal(ua["id"])
    order = ok("POST", "/api/orders", {"merchantId": merchant["id"], "subject": "ride镜像行程", "amount": 3000})["order"]
    r = ok("POST", "/api/cashier/pay", {"orderId": order["id"], "method": "BALANCE",
                                       "accountId": ua["id"], "requestId": "RISKTEST-ride-" + SUFFIX})
    p = wait_payment(r["payment"]["id"], "SUCCESS")
    check("ride BALANCE 支付 SUCCESS（新风控零误拦）", p.get("status") == "SUCCESS", str(p)[:250])
    check("手续费口径不变（3000 分 → 18 分）", p.get("fee") == 18, str(p.get("fee")))
    check("ride 支付借贷正确（付款方 -3000 / 商户 +2982）",
          bal(ua["id"]) == u0 - 3000 and bal(ma["id"]) == m0 + 2982,
          f"{bal(ua['id'])} vs {u0 - 3000}, {bal(ma['id'])} vs {m0 + 2982}")
    ds = decisions(ua["id"], 5)
    check("真实支付处置留痕可查（pass=true score=0）",
          any(d.get("pass") is True and d.get("score") == 0 for d in ds), str(ds)[:250])

    # ---------- 3. R-1 热更 / 灰度 / 回滚 ----------
    print("[3] R-1：规则热更不重启生效 + 灰度比例/白名单 + 一键回滚")
    v0 = engine().get("rulesVersion")
    hot_payer = "RISKTEST-hot-" + SUFFIX
    rule = {"id": "R_HOT", "name": "大额拦截", "scene": "PAY", "priority": 10,
            "conditionExpr": "amount > 100000", "action": "REJECT", "score": 30,
            "grayPercent": 100, "status": "PUBLISHED"}
    # 不手动 reload，验证自动传播（local 文件 30s reload / Apollo 推送 + 5s 轮询）
    set_rules([rule], shadow=True, reload=False)
    e = poll(engine, lambda x: x.get("ruleCount") == 1, timeout=50, interval=2)
    check("规则热更自动生效（不重启、不手动 reload）", e.get("ruleCount") == 1, str(e)[:250])
    check("规则集版本号递增", e.get("rulesVersion") > v0, f"{v0} -> {e.get('rulesVersion')}")
    check("规则编译状态 OK", ok("GET", "/api/risk/rules")[0].get("compileStatus") == "OK",
          str(ok("GET", "/api/risk/rules"))[:250])
    r = ev(hot_payer, 200000)
    check("全局影子下规则命中只记录不拦截",
          r.get("pass") is True and r.get("shadow") is True and r.get("action") == "REJECT"
          and any("R_HOT" in h for h in r.get("hitRules", [])), str(r)[:300])
    ok("POST", "/api/risk/shadow", {"on": False}, step=STEP)
    r = ev(hot_payer, 200000)
    check("关闭影子后规则正式拦截", r.get("pass") is False and r.get("action") == "REJECT", str(r)[:250])
    r = ev(hot_payer, 50000)
    check("未达规则条件正常放行", r.get("pass") is True, str(r)[:250])
    vs = versions("RULES")
    check("规则版本历史含 CONFIG 来源与审计字段",
          any(x.get("source") == "CONFIG" and x.get("operator") for x in vs), str(vs)[:250])
    # 灰度 0% → 不生效
    ok("POST", "/api/risk/rules/R_HOT/publish", {"grayPercent": 0}, step=STEP)
    r = ev(hot_payer, 200000)
    check("灰度 0% 规则不生效（放行）",
          r.get("pass") is True and not any("R_HOT" in h for h in r.get("hitRules", [])), str(r)[:250])
    # 白名单恒生效
    ok("POST", "/api/risk/rules/R_HOT/publish", {"grayPercent": 0, "whitelist": hot_payer}, step=STEP)
    r = ev(hot_payer, 200000)
    check("灰度白名单恒生效（命中拦截）", r.get("pass") is False, str(r)[:250])
    other = ev("RISKTEST-gray-other-" + SUFFIX, 200000)
    check("灰度外用户不受影响", other.get("pass") is True, str(other)[:250])
    # 一键回滚：回到上一版（grayPercent=0 无白名单）
    before = ok("GET", "/api/risk/rules")[0]
    snap = ok("POST", "/api/risk/rules/rollback", {}, step=STEP)
    after = ok("GET", "/api/risk/rules")[0]
    check("回滚生成 ROLLBACK 新版本", snap.get("source") == "ROLLBACK", str(snap)[:200])
    check("回滚恢复上一版灰度配置（白名单清空）",
          after.get("grayPercent") == 0 and not after.get("whitelist"),
          f"before={before.get('grayPercent')}/{before.get('whitelist')} after={after.get('grayPercent')}/{after.get('whitelist')}")
    r = ev(hot_payer + "-rb1", 200000)
    check("回滚后按上一版语义放行", r.get("pass") is True, str(r)[:250])
    time.sleep(7)  # 跨一个配置轮询周期
    r = ev(hot_payer + "-rb2", 200000)
    check("回滚结果不被配置轮询覆盖（配置未变）", r.get("pass") is True, str(r)[:250])

    # ---------- 4. 误配置护栏 ----------
    print("[4] §5.4 护栏：编译失败 / 执行异常 / 超时 → 放行并告警")
    set_rules([
        {"id": "R_BAD_COMPILE", "name": "语法错", "conditionExpr": "amount > 100 &&",
         "action": "REJECT", "score": 90, "status": "PUBLISHED", "priority": 1},
        {"id": "R_BAD_EXEC", "name": "除零", "conditionExpr": "1/0 > 0",
         "action": "REJECT", "score": 90, "status": "PUBLISHED", "priority": 2},
        {"id": "R_GOOD", "name": "正常大额", "conditionExpr": "amount > 100000",
         "action": "REJECT", "score": 30, "status": "PUBLISHED", "priority": 3},
    ], shadow=False)
    e = engine()
    check("编译失败规则被标记（不阻断装载）", e.get("compileFailedRules") >= 1, str(e)[:250])
    check("编译失败告警留痕 RULE_COMPILE_FAIL", "RULE_COMPILE_FAIL" in alert_kinds(), str(alert_kinds()))
    guard_payer = "RISKTEST-guard-" + SUFFIX
    r = ev(guard_payer, 50000)
    check("编译失败+执行异常规则不影响正常交易（放行）",
          r.get("pass") is True and r.get("degraded") is True, str(r)[:300])
    check("执行异常告警留痕 RULE_EXEC", "RULE_EXEC" in alert_kinds(), str(alert_kinds()))
    r = ev("RISKTEST-guard2-" + SUFFIX, 200000)
    check("同批正常规则仍生效（不被坏规则拖垮）",
          r.get("pass") is False and any("R_GOOD" in h for h in r.get("hitRules", [])), str(r)[:300])
    r = ev("RISKTEST-guard3-" + SUFFIX, 20000000)
    check("护栏降级时内置单笔限额仍拦截（不静默放行高风险）",
          r.get("pass") is False and r.get("code") == "RISK_SINGLE_LIMIT", str(r)[:250])
    # 超时护栏
    set_rules([{"id": "R_SLOW", "name": "死循环", "status": "PUBLISHED", "action": "REJECT", "score": 90,
                "conditionExpr": "sum=0; for(i=0;i<3000000;i++){sum=sum+i}; sum>0"}], shadow=False)
    push({"risk.rules.timeout-ms": "50", "risk.rules.hard-timeout-ms": "300"})
    t0 = time.time()
    r = ev("RISKTEST-slow-" + SUFFIX, 1000)
    elapsed = time.time() - t0
    check("规则超时 → 放行（不拦截正常交易）",
          r.get("pass") is True and r.get("degraded") is True, str(r)[:300])
    check("超时受上限约束（<3s 返回）", elapsed < 3.0, f"{elapsed:.2f}s")
    check("超时告警留痕 RULE_TIMEOUT", "RULE_TIMEOUT" in alert_kinds(), str(alert_kinds()))
    check("引擎超时计数 >0", engine().get("timeouts", 0) > 0, str(engine().get("timeouts")))
    push({"risk.rules.timeout-ms": "200", "risk.rules.hard-timeout-ms": "400"})
    # 不可解析 JSON → last-known-good
    push({"risk.rules.json": "{不是合法JSON"})
    e = engine()
    check("配置 JSON 不可解析 → 保留 last-known-good（规则集不清空）",
          e.get("ruleCount") == 1 and e.get("configParseFailures", 0) >= 1, str(e)[:250])
    check("解析失败告警留痕 CONFIG_PARSE_FAIL", "CONFIG_PARSE_FAIL" in alert_kinds(), str(alert_kinds()))

    # ---------- 5. 影子模式留痕 ----------
    print("[5] 影子模式：只记录命中不拦截，留痕可查")
    set_rules([rule], shadow=True)
    shadow_payer = "RISKTEST-shadow-" + SUFFIX
    r = ev(shadow_payer, 200000)
    check("影子模式 pass=true 且记录命中与分数",
          r.get("pass") is True and r.get("shadow") is True and r.get("score", 0) >= 0
          and any("R_HOT" in h for h in r.get("hitRules", [])), str(r)[:300])
    d = poll(lambda: decisions(shadow_payer, 5), lambda xs: len(xs) > 0, timeout=10, interval=0.5)
    check("影子处置落 decision_log（shadow=true）",
          d and d[0].get("shadow") is True and d[0].get("pass") is True, str(d)[:300])
    check("影子留痕含命中规则（可解释）", d and "R_HOT" in (d[0].get("hitRules") or ""), str(d)[:300])

    # ---------- 6. R-2 多维限额矩阵 ----------
    print("[6] R-2：单笔/日/月 × 用户/商户/渠道/支付方式/卡 + 组合独立 + 并发不超扣")
    set_rules([], shadow=False)
    prov = engine().get("quotaProvider")
    check("限额计数 provider 与 profile 一致",
          prov == ("redis" if PROFILE == "docker" else "memory"), f"{prov} / {PROFILE}")

    def quota_matrix(tag):
        """同一限额矩阵跑一遍（逐维度隔离配置，避免维度间串扰），返回逐项结果。"""
        out = {}
        # 6.1 用户 × 日累计金额
        set_quotas([{"id": "Q_USER_DAY", "dimensions": "USER", "period": "DAY", "limitAmount": 500000}])
        out["loaded"] = engine().get("quotaCount")
        p = f"RISKTEST-{tag}-ud-" + SUFFIX
        out["user_day_1"] = ev(p, 200000).get("pass")
        out["user_day_2"] = ev(p, 200000).get("pass")
        r = ev(p, 200000)
        out["user_day_3_reject"] = (r.get("pass"), r.get("code"), "Q_USER_DAY" in (r.get("quotaHit") or ""))
        out["user_day_small_ok"] = ev(p, 100000).get("pass")
        # 6.2 用户×支付方式 × 日笔数（维度组合）
        set_quotas([{"id": "Q_UM_CNT", "dimensions": "USER,METHOD", "period": "DAY", "limitCount": 3}])
        p2 = f"RISKTEST-{tag}-um-" + SUFFIX
        for i in range(3):
            out[f"um_{i}"] = ev(p2, 1000, method="BALANCE").get("pass")
        r = ev(p2, 1000, method="BALANCE")
        out["um_4_reject"] = (r.get("pass"), "Q_UM_CNT" in (r.get("quotaHit") or ""))
        out["um_other_method_ok"] = ev(p2, 1000, method="CARD", cardNo=CARD_UM).get("pass")
        # 6.3 商户 × 月累计金额
        set_quotas([{"id": "Q_MER_MONTH", "dimensions": "MERCHANT", "period": "MONTH", "limitAmount": 1000000}])
        mer = f"RISKTEST-{tag}-MER-{SUFFIX}"
        out["mer_1"] = ev(f"RISKTEST-{tag}-m1-" + SUFFIX, 600000, merchantId=mer).get("pass")
        r = ev(f"RISKTEST-{tag}-m2-" + SUFFIX, 600000, merchantId=mer)
        out["mer_2_reject"] = (r.get("pass"), "Q_MER_MONTH" in (r.get("quotaHit") or ""))
        # 6.4 渠道 × 日累计金额
        set_quotas([{"id": "Q_CH_DAY", "dimensions": "CHANNEL", "period": "DAY", "limitAmount": 300000}])
        ch = f"CH_{tag}_{SUFFIX}"
        out["ch_1"] = ev(f"RISKTEST-{tag}-c1-" + SUFFIX, 200000, channelCode=ch).get("pass")
        r = ev(f"RISKTEST-{tag}-c2-" + SUFFIX, 200000, channelCode=ch)
        out["ch_2_reject"] = (r.get("pass"), "Q_CH_DAY" in (r.get("quotaHit") or ""))
        # 6.5 支付方式 × 单笔（scopeKey 限定 CARD）
        set_quotas([{"id": "Q_METHOD_SINGLE", "dimensions": "METHOD", "period": "SINGLE",
                     "limitAmount": 200000, "scopeKey": "method=CARD"}])
        r = ev(f"RISKTEST-{tag}-single-" + SUFFIX, 300000, method="CARD", cardNo=CARD_SINGLE)
        out["single_reject"] = (r.get("pass"), "Q_METHOD_SINGLE" in (r.get("quotaHit") or ""))
        out["single_balance_ok"] = ev(f"RISKTEST-{tag}-single2-" + SUFFIX, 300000, method="BALANCE").get("pass")
        # 6.6 卡（BIN）× 日笔数
        set_quotas([{"id": "Q_CARD_DAY", "dimensions": "CARD", "period": "DAY", "limitCount": 2,
                     "scopeKey": "method=CARD"}])
        card = CARD_DAY
        out["card_1"] = ev(f"RISKTEST-{tag}-k1-" + SUFFIX, 1000, method="CARD", cardNo=card).get("pass")
        out["card_2"] = ev(f"RISKTEST-{tag}-k2-" + SUFFIX, 1000, method="CARD", cardNo=card).get("pass")
        r = ev(f"RISKTEST-{tag}-k3-" + SUFFIX, 1000, method="CARD", cardNo=card)
        out["card_3_reject"] = (r.get("pass"), "Q_CARD_DAY" in (r.get("quotaHit") or ""))
        # 6.7 多规则共存：金额维度与笔数维度互不干扰
        set_quotas([{"id": "Q_USER_DAY", "dimensions": "USER", "period": "DAY", "limitAmount": 500000},
                    {"id": "Q_UM_CNT", "dimensions": "USER,METHOD", "period": "DAY", "limitCount": 3}])
        p3 = f"RISKTEST-{tag}-combo-" + SUFFIX
        for i in range(3):
            out[f"combo_{i}"] = ev(p3, 1000).get("pass")
        r = ev(p3, 1000)
        out["combo_reject_by_count"] = (r.get("pass"), "Q_UM_CNT" in (r.get("quotaHit") or ""))
        return out

    mx = quota_matrix("redis" if PROFILE == "docker" else "mem")
    check("限额规则动态装载生效", mx["loaded"] == 1, str(mx["loaded"]))
    check("用户日累计：前 2 笔放行", mx["user_day_1"] and mx["user_day_2"], str(mx))
    check("用户日累计：第 3 笔超限拒绝且错误码规范",
          mx["user_day_3_reject"] == (False, "RISK_QUOTA_EXCEEDED", True), str(mx["user_day_3_reject"]))
    check("拒绝不消耗额度（后续小额仍放行）", mx["user_day_small_ok"] is True, str(mx))
    check("用户×支付方式 笔数限额：前 3 笔放行",
          all(mx[f"um_{i}"] for i in range(3)), str(mx))
    check("用户×支付方式 笔数限额：第 4 笔拒绝", mx["um_4_reject"] == (False, True), str(mx["um_4_reject"]))
    check("维度组合独立计数（同用户换支付方式放行）", mx["um_other_method_ok"] is True, str(mx))
    check("商户月累计：第 1 笔放行", mx["mer_1"] is True, str(mx))
    check("商户月累计：第 2 笔超限拒绝", mx["mer_2_reject"] == (False, True), str(mx["mer_2_reject"]))
    check("渠道日累计：第 1 笔放行", mx["ch_1"] is True, str(mx))
    check("渠道日累计：第 2 笔超限拒绝", mx["ch_2_reject"] == (False, True), str(mx["ch_2_reject"]))
    check("支付方式单笔限额（scopeKey 限定 CARD）拒绝", mx["single_reject"] == (False, True), str(mx["single_reject"]))
    check("scopeKey 之外支付方式不受该单笔限额约束", mx["single_balance_ok"] is True, str(mx))
    check("卡维度日笔数：前 2 笔放行", mx["card_1"] and mx["card_2"], str(mx))
    check("卡维度日笔数：第 3 笔拒绝", mx["card_3_reject"] == (False, True), str(mx["card_3_reject"]))
    check("多限额共存：金额未超时按笔数维度拒绝（各维度独立）",
          all(mx[f"combo_{i}"] for i in range(3)) and mx["combo_reject_by_count"] == (False, True), str(mx))
    usage = ok("GET", "/api/risk/quotas/usage?keyPrefix=Q_USER_DAY")
    today = time.strftime("%Y%m%d")
    check("限额用量按周期 key 记账（跨日自然重置）",
          any(u.get("periodKey") == today for u in usage), str(usage)[:250])
    # 并发不超扣（商户维度，8 线程各不同 payer，避开限频）
    set_quotas([{"id": "Q_CONC", "dimensions": "MERCHANT", "period": "DAY", "limitAmount": 300000}])
    conc_mer = "RISKTEST-CONC-" + SUFFIX

    def one(i):
        return ev(f"RISKTEST-conc{i}-" + SUFFIX, 100000, merchantId=conc_mer).get("pass")

    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(one, range(8)))
    check("并发 8 线程累计不超扣（恰好 3 笔放行）", sum(1 for x in results if x) == 3, str(results))
    # 动态停用 + 回滚
    ok("POST", "/api/risk/quotas/Q_CONC/disable", {}, step=STEP)
    check("限额停用即时生效",
          ev("RISKTEST-conc-after-" + SUFFIX, 100000, merchantId=conc_mer).get("pass") is True)
    ok("POST", "/api/risk/quotas/rollback", {}, step=STEP)
    check("限额回滚恢复上一版（重新生效）",
          any(q.get("id") == "Q_CONC" and q.get("status") == "ENABLED" for q in ok("GET", "/api/risk/quotas")),
          str(ok("GET", "/api/risk/quotas"))[:250])

    # ---------- 7. 受理前拒绝零悬挂单 ----------
    print("[7] 受理前拒绝：真实支付单终态 FAILED，无 PROCESSING 悬挂单，余额未动")
    set_quotas([{"id": "Q_BLOCK", "dimensions": "USER", "period": "DAY", "limitAmount": 1,
                 "scopeKey": "payerId=" + ua["id"]}])
    u1 = bal(ua["id"])
    order2 = ok("POST", "/api/orders", {"merchantId": merchant["id"], "subject": "限额拒绝", "amount": 2000})["order"]
    st, resp = req("POST", "/api/cashier/pay", {"orderId": order2["id"], "method": "BALANCE",
                                               "accountId": ua["id"], "requestId": "RISKTEST-block-" + SUFFIX},
                   token=ADMIN)
    check("多维限额拒绝在受理前返回 400 风控拒绝",
          st == 400 and "风控拒绝" in str(resp.get("error", "")) and "超出限额" in str(resp.get("error", "")),
          f"{st} {resp}")
    plist = ok("GET", "/api/payments")
    rows = plist if isinstance(plist, list) else plist.get("list", plist.get("records", []))
    mine = [x for x in rows if x.get("requestId") == "RISKTEST-block-" + SUFFIX]
    check("被拒支付单终态 FAILED（非 PROCESSING 悬挂）",
          mine and all(x.get("status") == "FAILED" for x in mine), str(mine)[:300])
    check("限额拒绝未动账", bal(ua["id"]) == u1, f"{bal(ua['id'])} vs {u1}")
    set_quotas([])
    order3 = ok("POST", "/api/orders", {"merchantId": merchant["id"], "subject": "限额解除", "amount": 2000})["order"]
    r = ok("POST", "/api/cashier/pay", {"orderId": order3["id"], "method": "BALANCE",
                                       "accountId": ua["id"], "requestId": "RISKTEST-unblock-" + SUFFIX})
    p = wait_payment(r["payment"]["id"], "SUCCESS")
    check("限额解除后同一账户支付恢复 SUCCESS", p.get("status") == "SUCCESS", str(p)[:200])

    # ---------- 8. R-3 名单 ----------
    print("[8] R-3：黑/白/灰优先级 + 过期失效 + 软删 + 命中查询 + 变更审计")
    black_payer = "RISKTEST-black-" + SUFFIX
    b = ok("POST", "/api/risk/lists", {"listType": "BLACK", "valueType": "USER", "value": black_payer,
                                       "reason": "RISKTEST 黑名单"}, step=STEP)
    r = ev(black_payer, 1000)
    check("黑名单命中拒绝（错误码 RISK_LIST_HIT）",
          r.get("pass") is False and r.get("code") == "RISK_LIST_HIT" and r.get("listHit") == "BLACK",
          str(r)[:300])
    hit = ok("GET", f"/api/risk/lists/hit?valueType=USER&value={black_payer}")
    check("名单命中查询 API 正确", hit.get("hit") is True and hit.get("listType") == "BLACK", str(hit))
    # 白名单优先于黑名单（不同维度同时命中）
    white_mer = "RISKTEST-WHITE-MER-" + SUFFIX
    ok("POST", "/api/risk/lists", {"listType": "WHITE", "valueType": "MERCHANT", "value": white_mer,
                                   "reason": "RISKTEST 白名单"}, step=STEP)
    r = ev(black_payer, 1000, merchantId=white_mer)
    check("白名单优先级高于黑名单（同时命中 → 放行）",
          r.get("pass") is True and r.get("listHit") == "WHITE", str(r)[:300])
    r = ev(black_payer, 20000000, merchantId=white_mer)
    check("白名单跳过内置单笔限额（显式放行语义）", r.get("pass") is True, str(r)[:250])
    # 灰名单 → 加强验证（先把加强验证阈值降到 20，灰名单因子 30 分即可触发）
    ok("POST", "/api/risk/thresholds", {"scene": "PAY", "challengeScore": 20, "limitScore": 40,
                                        "rejectScore": 60, "reviewScore": 80, "limitAmount": 100000}, step=STEP)
    gray_dev = "RISKTEST-DEV-GRAY-" + SUFFIX
    ok("POST", "/api/risk/lists", {"listType": "GRAY", "valueType": "DEVICE", "value": gray_dev}, step=STEP)
    r = ev("RISKTEST-gray-" + SUFFIX, 1000, deviceId=gray_dev)
    check("灰名单命中 → CHALLENGE 加强验证（不受全局影子影响）",
          r.get("pass") is False and r.get("action") == "CHALLENGE" and r.get("listHit") == "GRAY"
          and r.get("shadow") is False, str(r)[:300])
    check("灰名单因子加分可解释（LIST 因子 30 分）",
          any(f.get("code") == "LIST" and f.get("score") == 30 for f in r.get("factors", [])),
          str(r.get("factors"))[:300])
    # 过期失效
    exp_ip = "203.0.113.77"
    ok("POST", "/api/risk/lists", {"listType": "BLACK", "valueType": "IP", "value": exp_ip,
                                   "expireAt": int(time.time() * 1000) + 2500}, step=STEP)
    r = ev("RISKTEST-exp-" + SUFFIX, 1000, ip=exp_ip)
    check("未过期黑名单 IP 命中拒绝", r.get("pass") is False, str(r)[:250])
    time.sleep(3)
    r = ev("RISKTEST-exp2-" + SUFFIX, 1000, ip=exp_ip)
    check("过期名单自动失效（放行）", r.get("pass") is True, str(r)[:250])
    # 软删 + 审计
    ok("DELETE", f"/api/risk/lists/{b['id']}", step=STEP)
    r = ev(black_payer, 1000)
    check("名单软删后即时放行", r.get("pass") is True, str(r)[:250])
    entries = ok("GET", "/api/risk/lists?valueType=USER&value=" + black_payer)
    check("软删保留历史行（status=REMOVED，不物理删除）",
          any(x.get("status") == "REMOVED" for x in entries), str(entries)[:250])
    lv = versions("LISTS")
    check("名单变更审计含 operator 与变更说明",
          sum(1 for x in lv if x.get("operator") == "admin" and x.get("changeNote")) >= 4,
          str(lv)[:300])
    check("名单审计记录新增与移除两类动作",
          any("名单新增" in (x.get("changeNote") or "") for x in lv)
          and any("名单移除" in (x.get("changeNote") or "") for x in lv), str(lv)[:300])
    ok("POST", "/api/risk/lists/rollback", {}, step=STEP)
    check("名单一键回滚生成 ROLLBACK 版本",
          any(x.get("source") == "ROLLBACK" for x in versions("LISTS")), str(versions("LISTS"))[:250])
    push({"risk.list.entries.json": "[]"})

    # ---------- 9. R-4 评分分级 + 转人工 ----------
    print("[9] R-4：多因子评分 → 分级处置 + 转人工建单 + 人审裁决")
    hour = time.localtime().tm_hour
    push({"risk.score.risk-devices": "RISKDEV-" + SUFFIX,
          "risk.score.risk-ips": "198.51.100.9",
          "risk.score.risk-hours": "",
          "risk.score.weights.json": json.dumps({"FREQ": 25, "AMOUNT_DEV": 20, "LIST": 30,
                                                 "DEVICE": 25, "IP": 20, "TIME": 10})})
    ok("POST", "/api/risk/thresholds", {"scene": "PAY", "challengeScore": 20, "limitScore": 40,
                                        "rejectScore": 60, "reviewScore": 80, "limitAmount": 100000}, step=STEP)
    set_rules([
        {"id": "R_S10", "name": "中大额", "conditionExpr": "amount > 100000", "action": "PASS",
         "score": 10, "status": "PUBLISHED", "priority": 1},
        {"id": "R_S30", "name": "超大额", "conditionExpr": "amount > 500000", "action": "PASS",
         "score": 30, "status": "PUBLISHED", "priority": 2},
    ], shadow=False)
    dev, rip = "RISKDEV-" + SUFFIX, "198.51.100.9"
    r = ev("RISKTEST-sc1-" + SUFFIX, 50000, deviceId=dev)
    check("单因子（设备 25）→ CHALLENGE 加强验证",
          r.get("score") == 25 and r.get("action") == "CHALLENGE" and r.get("pass") is False, str(r)[:300])
    r = ev("RISKTEST-sc2-" + SUFFIX, 50000, deviceId=dev, ip=rip)
    check("双因子（设备+IP=45）→ LIMIT 降额放行（未超降额上限）",
          r.get("score") == 45 and r.get("action") == "LIMIT" and r.get("pass") is True, str(r)[:300])
    r = ev("RISKTEST-sc3-" + SUFFIX, 200000, deviceId=dev, ip=rip)
    check("LIMIT 档超降额上限 → REJECT",
          r.get("action") == "REJECT" and r.get("pass") is False, str(r)[:300])
    push({"risk.score.risk-hours": str(hour)})
    r = ev("RISKTEST-sc4-" + SUFFIX, 50000, deviceId=dev, ip=rip)
    check("三因子（设备+IP+时段=55）→ LIMIT", r.get("score") == 55, str(r)[:300])
    r = ev("RISKTEST-sc5-" + SUFFIX, 200000, deviceId=dev, ip=rip)
    check("因子+规则分（55+10=65）→ REJECT",
          r.get("score") == 65 and r.get("action") == "REJECT", str(r)[:300])
    mr_payer = "RISKTEST-mr-" + SUFFIX
    r = ev(mr_payer, 600000, deviceId=dev, ip=rip, bizNo="RISKTEST-MR-" + SUFFIX)
    check("高分（55+10+30=95）→ MANUAL_REVIEW 转人工",
          r.get("score") == 95 and r.get("action") == "MANUAL_REVIEW" and r.get("pass") is False, str(r)[:300])
    check("转人工经 DisputeFacade 建单（返回 disputeId）", bool(r.get("disputeId")), str(r)[:300])
    did = r.get("decisionId")
    dsp = ok("GET", f"/api/disputes/{r['disputeId']}")
    check("差错单 source=PAYMENT / type=RISK_MANUAL_REVIEW / strategy=MANUAL（不自动动账）",
          dsp.get("source") == "PAYMENT" and dsp.get("type") == "RISK_MANUAL_REVIEW"
          and dsp.get("strategy") == "MANUAL", str(dsp)[:300])
    d = ok("GET", f"/api/risk/decisions/{did}")
    check("处置留痕含分数/动作/命中规则/因子明细（可解释）",
          d.get("score") == 95 and d.get("action") == "MANUAL_REVIEW"
          and "R_S30" in (d.get("hitRules") or "") and "DEVICE" in (d.get("factors") or ""),
          str(d)[:400])
    check("人审工单初始 PENDING", d.get("reviewStatus") == "PENDING", str(d.get("reviewStatus")))
    ok("POST", f"/api/risk/decisions/{did}/review", {"approve": True}, step=STEP)
    d = ok("GET", f"/api/risk/decisions/{did}")
    dsp = ok("GET", f"/api/disputes/{r['disputeId']}")
    check("人审 approve → 工单 APPROVED", d.get("reviewStatus") == "APPROVED", str(d.get("reviewStatus")))
    check("人审 approve → 差错单核销 RESOLVED", dsp.get("status") == "RESOLVED", str(dsp.get("status")))
    r2 = ev("RISKTEST-mr2-" + SUFFIX, 600000, deviceId=dev, ip=rip, bizNo="RISKTEST-MR2-" + SUFFIX)
    ok("POST", f"/api/risk/decisions/{r2['decisionId']}/review", {"approve": False}, step=STEP)
    dsp2 = ok("GET", f"/api/disputes/{r2['disputeId']}")
    check("人审 reject → 差错单 IGNORED", dsp2.get("status") == "IGNORED", str(dsp2.get("status")))
    st, resp = req("POST", f"/api/risk/decisions/{r2['decisionId']}/review", {"approve": True},
                   token=ADMIN, step=STEP)
    check("重复人审裁决拒绝 409（不可反复改判）", st == 409, f"{st} {resp}")
    # 阈值动态生效
    ok("POST", "/api/risk/thresholds", {"scene": "PAY", "challengeScore": 100, "limitScore": 120,
                                        "rejectScore": 140, "reviewScore": 160, "limitAmount": 100000}, step=STEP)
    r = ev("RISKTEST-th-" + SUFFIX, 50000, deviceId=dev, ip=rip)
    check("阈值热更后同一分数改判放行（55 < 100）",
          r.get("score") == 55 and r.get("action") == "PASS" and r.get("pass") is True, str(r)[:300])
    ok("POST", "/api/risk/thresholds/rollback", {}, step=STEP)
    check("阈值回滚恢复上一版", ok("GET", "/api/risk/thresholds?scene=PAY").get("challengeScore") == 20,
          str(ok("GET", "/api/risk/thresholds?scene=PAY")))
    # 金额偏离因子（显式开启历史累计）
    push({"risk.score.amount-history": "true", "risk.score.amount-min-sample": "2",
          "risk.score.amount-ratio": "3", "risk.score.risk-devices": "", "risk.score.risk-ips": "",
          "risk.score.risk-hours": ""})
    set_rules([], shadow=False)
    dev_payer = "RISKTEST-dev-" + SUFFIX
    for i in range(3):
        ev(dev_payer, 1000)
    r = ev(dev_payer, 100000)
    check("金额偏离因子命中（均额 1000，本笔 100000 → 100 倍 > 3）",
          any(f.get("code") == "AMOUNT_DEV" and f.get("score") == 20 for f in r.get("factors", [])),
          str(r.get("factors"))[:400])
    push({"risk.score.amount-history": "false", "risk.score.freq-threshold": "3"})
    # 频率因子（阈值可配：窗口内已有 3 笔即加分）
    fq_payer = "RISKTEST-freq-" + SUFFIX
    last = None
    for i in range(4):
        last = ev(fq_payer, 100)
    check("频率因子在窗口内笔数达阈值后加分（FREQ 25）",
          any(f.get("code") == "FREQ" and f.get("score") == 25 for f in last.get("factors", [])),
          str(last.get("factors"))[:400])
    push({"risk.score.freq-threshold": "5"})

    # ---------- 10. RBAC + 二次认证 ----------
    print("[10] RBAC + 二次认证")
    st, _ = req("GET", "/api/risk/rules")
    check("未登录读风控规则 401", st == 401, str(st))
    st, resp = req("POST", "/api/risk/rules", {"id": "R_X", "conditionExpr": "amount>1"}, token=ADMIN)
    check("敏感写操作缺 X-Step-Up-Token 403", st == 403, f"{st} {resp}")
    st, resp = req("POST", "/api/risk/rules", {"id": "R_X", "conditionExpr": "amount>1"},
                   token=ADMIN, step="bogus-token")
    check("伪造 step-up token 403", st == 403, f"{st} {resp}")
    st, resp = req("POST", "/api/risk/lists", {"listType": "BLACK", "valueType": "USER", "value": "x"},
                   token=ADMIN)
    check("名单写操作缺二次认证 403", st == 403, f"{st} {resp}")
    st, resp = req("POST", "/api/risk/rules", {"id": "R_X", "conditionExpr": "amount>1", "status": "DRAFT"},
                   token=ADMIN, step=STEP)
    check("带合法二次认证写操作 200", st == 200, f"{st} {resp}")
    st, resp = req("POST", "/api/risk/rules", {"id": "R_BAD", "conditionExpr": ""}, token=ADMIN, step=STEP)
    check("空表达式参数校验 400", st == 400, f"{st} {resp}")

    # ---------- 11. local 内存降级等价 ----------
    print("[11] local 内存降级等价：同一限额矩阵 force-memory 结果逐项一致")
    set_rules([], shadow=True)
    push({"risk.quota.force-memory": "true"})
    check("force-memory 生效（provider=memory）", engine().get("quotaProvider") == "memory",
          str(engine().get("quotaProvider")))
    mx2 = quota_matrix("mem")
    diffs = {k: (mx.get(k), mx2.get(k)) for k in mx if mx.get(k) != mx2.get(k)}
    check("Redis 计数 ≡ 内存计数（限额矩阵逐项一致）", not diffs, str(diffs)[:400])
    push({"risk.quota.force-memory": "false"})
    check("关闭 force-memory 恢复 profile 计数器",
          engine().get("quotaProvider") == ("redis" if PROFILE == "docker" else "memory"),
          str(engine().get("quotaProvider")))

    # ---------- 收尾复位 ----------
    print("[12] 复位：风控配置回到默认宽松（不影响后续 smoke / ride 流量）")
    reset_config()
    e = engine()
    check("复位后规则/限额/名单均为空",
          e.get("ruleCount") == 0 and e.get("quotaCount") == 0 and e.get("listCount") == 0, str(e)[:250])
    check("复位后全局影子恢复默认开", e.get("globalShadow") is True, str(e.get("globalShadow")))
    r = ev("RISKTEST-final-" + SUFFIX, 3000)
    check("复位后 ride 典型流量放行 score=0", r.get("pass") is True and r.get("score") == 0, str(r)[:250])
    # 配置源缺键（raw=null）路径：默认值兜底，不得 NPE / 不得保留旧规则
    if PROVIDER != "apollo" and ORIGINAL:
        stripped = "".join(l for l in ORIGINAL.decode().splitlines(keepends=True)
                           if not l.strip().startswith("risk."))
        try:
            write_local(stripped)
            s = ok("POST", "/api/risk/reload", {})
            check("配置源缺风控键 → 默认值兜底（raw=null 不 NPE，规则/限额/名单清空）",
                  s.get("ruleCount") == 0 and s.get("quotaCount") == 0 and s.get("listCount") == 0
                  and s.get("globalShadow") is True, str(s)[:250])
        finally:
            write_local(ORIGINAL.decode())
            ok("POST", "/api/risk/reload", {})
    if PROVIDER != "apollo":
        if ORIGINAL is None:
            check("原配置文件不存在 → 清理后仍不存在", not os.path.exists(CONFIG_FILE))
        else:
            check("原配置文件字节级还原", read_local() == ORIGINAL)


try:
    main()
finally:
    try:
        reset_config()
    except Exception as ex:
        print(f"!! 风控配置复位失败（需人工检查 {CONFIG_FILE} / Apollo {APOLLO_NS}）: {ex}")

print(f"\n== verify-risk 结果: {passed} 通过 / {failed} 失败 ==")
sys.exit(1 if failed else 0)