#!/usr/bin/env python3
"""verify-risk-plus.py — wave3 P2·D 泳道：R-5 设备指纹/IP 画像 + R-6 反洗钱 AML。

跑法: python3 verify-risk-plus.py   （payment-pro 栈已启动，gateway 8080；local / docker 双 profile 通用）
      PP_PROFILE=local python3 verify-risk-plus.py

覆盖（对照两张卡验收）:
  [1] 基线：默认值口径未变（单笔限额/窗口/上限/分级阈值）+ 新因子默认影子 + AML 默认不阻断
      + ride 典型流量 evaluate score=0 PASS（带/不带 deviceId/ip 都不误拦）
  [2] ride 典型流量真实支付不被误拦（BALANCE 同步 SUCCESS、手续费 18 分、借贷正确、无 AML 误报）
  [3] R-5 设备指纹稳定性判定（STABLE→DRIFTING→UNSTABLE）+ 脱敏存储（原始标识不落库不出参）
  [4] R-5 设备-账户关联图谱（同设备多账户 / 同账户多设备双向）
  [5] R-5 IP 画像（归属地/代理/IDC/历史风险，离线沙箱数据源，不连外部情报 API）
  [6] R-5 画像因子接入既有评分体系（DEVICE 25 / IP 20 权重不变、因子编码集合不变、未另起体系）
      + QLExpress deviceRisk/ipRisk/deviceAccountCount/ipProxy 函数扩展
  [7] R-5 行为序列（时序样本 / 异地跳变 / 突发）
  [8] R-6 AML 六类可疑规则命中（大额/拆分/结构化/快进快出/异常时段/跨境）+ 自定义 QLExpress
  [9] R-6 名单筛查（制裁/PEP 沙箱名单，EXACT/ALIAS/未命中都留痕）+ 转人工经 DisputeFacade
      （source=PAYMENT、type=AML_STR、strategy=MANUAL 不动账）
  [10] R-6 STR 状态机（待审→上报→归档 / 待审→归档）+ 非法流转冲突 + 全程审计 + 交易引用一致
  [11] R-6 上报不阻断正常交易（非硬规则命中真实支付仍 SUCCESS）；硬规则才受理前拒绝且零悬挂单
  [12] 护栏红线：AML 规则编译失败/执行异常/超时 → 跳过并告警不阻断；R-1 引擎护栏仍生效；
      配置 JSON 不可解析 → last-known-good
  [13] 留存期可配 + 到期匿名化（deletedRows=0，绝不删历史行）
  [14] RBAC + 二次认证（未登录 401 / 无 X-Step-Up-Token 403 / 带 token 200）
  [15] local 降级等价（离线沙箱数据源双 profile 一致、无外部 API、local 内存计数）

数据保护：只新增 RPTEST 前缀测试数据；退出时停用全部 RPTEST AML 规则、软删测试名单条目、
把风控配置源字节级还原并将新键复位为默认值。绝不删除任何既有业务数据。
"""
import json
import os
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("PP_GATEWAY", "http://127.0.0.1:8080")
ADMIN_PASS = os.environ.get("PP_ADMIN_PASSWORD", "admin123")
CONFIG_FILE = os.path.expanduser("~/.config/payment-pro/config.properties")
APOLLO_ADMIN = os.environ.get("PP_APOLLO_ADMIN", "http://127.0.0.1:18071")
APOLLO_APP = os.environ.get("PP_APOLLO_APP_ID", "payment-pro")
APOLLO_NS = "risk-rules"
PROFILE = os.environ.get("PP_PROFILE", "docker")
SUFFIX = uuid.uuid4().hex[:8]
P = "RPTEST-" + SUFFIX
passed = failed = 0
FAILURES = []


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  [PASS] {name}")
    else:
        failed += 1
        FAILURES.append(name)
        print(f"  [FAIL] {name}  {detail}")


def req(method, path, body=None, token=None, base=None, step=None, timeout=60):
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


def ev(payer, amount, **kw):
    body = {"payerId": payer, "amount": amount, "method": kw.get("method", "BALANCE"),
            "scene": kw.get("scene", "PAY"), "traceId": P}
    for k in ("merchantId", "cardNo", "cardBin", "deviceId", "ip", "channelCode", "bizNo",
              "deviceOs", "deviceModel", "deviceUa"):
        if kw.get(k) is not None:
            body[k] = kw[k]
    return ok("POST", "/api/risk/evaluate", body)


def pstatus():
    return ok("GET", "/api/risk/profile/status")


def astatus():
    return ok("GET", "/api/risk/aml/status")


def device(dev):
    return ok("GET", f"/api/risk/device?deviceId={urllib.request.quote(dev)}")


def ipprof(ip):
    return ok("GET", f"/api/risk/ip?ip={ip}")


def reports(payer=None, status=None, limit=100):
    q = f"limit={limit}"
    if payer:
        q += "&payerId=" + urllib.request.quote(payer)
    if status:
        q += "&status=" + status
    return ok("GET", "/api/risk/aml/reports?" + q)


def reports_of(payer, rule_id):
    return [r for r in reports(payer) if r.get("ruleId") == rule_id]


def scan(payer=None, biz_date=None):
    body = {}
    if payer:
        body["payerId"] = payer
    if biz_date:
        body["bizDate"] = biz_date
    return ok("POST", "/api/risk/aml/scan", body, step=STEP)


def save_rule(rule):
    return ok("POST", "/api/risk/aml/rules", rule, step=STEP)


def disable_rule(rid):
    try:
        ok("POST", f"/api/risk/aml/rules/{rid}/disable", {}, step=STEP)
    except Exception as e:
        print(f"!! 停用规则 {rid} 失败: {e}")


def alerts(limit=300):
    return ok("GET", f"/api/risk/alerts?limit={limit}")


def alert_kinds():
    return {a.get("kind") for a in alerts()}


def wait_payment(pid, status, timeout=30):
    return poll(lambda: ok("GET", f"/api/payments/{pid}"), lambda p: p.get("status") == status,
                timeout=timeout, interval=0.5)


# ---------------- 配置源适配（local 文件 / Apollo risk-rules namespace） ----------------
PROVIDER = ok("GET", "/api/config").get("provider")
ORIGINAL = None
PUSHED = {}
TEST_RULES = []
TEST_WATCH = []


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
    assert st in (200, 400), f"创建 namespace 失败: {st} {resp}"
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
                          f"?name=verify-risk-plus-{tag}-{SUFFIX}&comment=verify-risk-plus&operator=apollo")
    assert st == 200, f"Apollo 发布失败: {st} {resp}"
    time.sleep(3)


def apollo_push(items):
    ns_id = apollo_ns_id()
    by_key = apollo_items()
    for k, v in items.items():
        if k in by_key:
            st, resp = apollo_req("PUT",
                                  f"/apps/{APOLLO_APP}/clusters/default/namespaces/{APOLLO_NS}/items/{by_key[k]}",
                                  {"key": k, "value": str(v), "comment": "verify-risk-plus",
                                   "dataChangeLastModifiedBy": "apollo"})
            assert st == 200, f"更新 item {k} 失败: {st} {resp}"
        else:
            st, resp = apollo_req("POST",
                                  f"/apps/{APOLLO_APP}/clusters/default/namespaces/{APOLLO_NS}/items",
                                  {"namespaceId": ns_id, "key": k, "value": str(v),
                                   "comment": "verify-risk-plus", "dataChangeCreatedBy": "apollo",
                                   "dataChangeLastModifiedBy": "apollo"})
            assert st == 200, f"创建 item {k} 失败: {st} {resp}"
    apollo_release("push")


def apollo_clear(keys):
    by_key = apollo_items()
    for k in keys:
        if k in by_key:
            apollo_req("DELETE", f"/items/{by_key[k]}?operator=apollo")
    apollo_release("clear")


def push(items, reload=True):
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


DEFAULTS = {
    "risk.device.enabled": "true",
    "risk.device.profile-score-enabled": "false",
    "risk.device.multi-account-threshold": "5",
    "risk.device.drift-threshold": "3",
    "risk.device.mask-enabled": "true",
    "risk.device.retention-days": "90",
    "risk.ip.profile-score-enabled": "false",
    "risk.ip.multi-account-threshold": "10",
    "risk.ip.history-reject-threshold": "3",
    "risk.ip.retention-days": "90",
    "risk.behavior.enabled": "true",
    "risk.behavior.window-millis": "60000",
    "risk.behavior.max-count": "20",
    "risk.behavior.retention-days": "30",
    "risk.aml.enabled": "true",
    "risk.aml.hard-block-enabled": "false",
    "risk.aml.score-enabled": "false",
    "risk.aml.screening-enabled": "true",
    "risk.aml.dispute-enabled": "true",
    "risk.aml.auto-scan-enabled": "false",
    "risk.aml.large-amount": "5000000",
    "risk.aml.day-amount": "5000000",
    "risk.aml.split-count": "10",
    "risk.aml.structuring-count": "3",
    "risk.aml.structuring-ratio": "90",
    "risk.aml.fast-window-millis": "600000",
    "risk.aml.fast-ratio": "90",
    "risk.aml.night-hours": "0,1,2,3,4,5",
    "risk.aml.night-amount": "1000000",
    "risk.aml.cross-border-amount": "1000000",
    "risk.aml.retention-days": "1825",
    "risk.aml.rules.json": "",
    "risk.ip.sandbox.json": "",
    "risk.rules.json": json.dumps({"globalShadow": True, "rules": []}),
    "risk.quota.rules.json": "[]",
    "risk.list.entries.json": "[]",
    "risk.score.thresholds.json": "{}",
    "risk.score.risk-devices": "",
    "risk.score.risk-ips": "",
    "risk.score.risk-hours": "",
    "risk.quota.force-memory": "false",
    "risk.score.amount-history": "false",
}


def cleanup():
    print("\n== 复位：停用测试规则 / 软删测试名单 / 还原配置源 ==")
    for rid in TEST_RULES:
        disable_rule(rid)
    for wid in TEST_WATCH:
        try:
            ok("POST", f"/api/risk/aml/watchlist/{wid}/remove", {}, step=STEP)
        except Exception as e:
            print(f"!! 软删名单 {wid} 失败: {e}")
    try:
        push(DEFAULTS)
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


def new_rule(rid, rtype, **kw):
    r = {"id": P + "-" + rid, "name": "RPTEST " + rtype, "ruleType": rtype,
         "score": kw.get("score", 60), "hardRule": kw.get("hard", False),
         "realtime": kw.get("realtime", False), "status": "ENABLED"}
    for k in ("threshold", "windowMillis", "minCount", "ratio", "conditionExpr"):
        if kw.get(k) is not None:
            r[k] = kw[k]
    save_rule(r)
    TEST_RULES.append(r["id"])
    return r["id"]


def main():
    global ORIGINAL
    print(f"== verify-risk-plus（批次 {SUFFIX}，配置源 {PROVIDER}，profile {PROFILE}）==")
    ORIGINAL = read_local()
    push(DEFAULTS)

    # ---------- 1. 基线与默认值口径 ----------
    print("[1] 基线：默认值口径未变 + 新因子默认影子 + AML 默认不阻断")
    e = ok("GET", "/api/risk/engine")
    check("默认规则/限额/名单集为空（P1-B 口径未变）",
          e.get("ruleCount") == 0 and e.get("quotaCount") == 0 and e.get("listCount") == 0, str(e)[:250])
    check("全局影子默认开", e.get("globalShadow") is True, str(e.get("globalShadow")))
    cfg = ok("GET", "/api/config").get("effective", {})
    check("单笔限额默认 10000000 分未变", cfg.get("payment.risk.single-limit") == 10000000, str(cfg)[:250])
    check("限频窗口默认 10000ms 未变", cfg.get("payment.risk.window-millis") == 10000, "")
    check("限频上限默认 5 笔未变", cfg.get("payment.risk.window-max") == 5, "")
    th = ok("GET", "/api/risk/thresholds?scene=PAY")
    check("分级阈值默认 40/60/80/95 未变",
          (th.get("challengeScore"), th.get("limitScore"), th.get("rejectScore"), th.get("reviewScore"))
          == (40, 60, 80, 95), str(th))
    ps = pstatus()
    check("设备画像评分默认影子（profile-score-enabled=false）",
          ps.get("deviceProfileScoreEnabled") is False, str(ps)[:300])
    check("IP 画像评分默认影子", ps.get("ipProfileScoreEnabled") is False, str(ps)[:300])
    check("设备标识脱敏默认开", ps.get("maskEnabled") is True, "")
    check("设备留存期默认 90 天可配", ps.get("deviceRetentionDays") == 90, "")
    check("IP 数据源为离线沙箱且不连外部 API",
          ps.get("ipDataSource") == "SANDBOX" and ps.get("ipExternalApi") is False, str(ps)[:300])
    a = astatus()
    check("AML 硬拦截默认关闭（上报不阻断）", a.get("hardBlockEnabled") is False, str(a)[:300])
    check("AML 评分贡献默认关闭", a.get("amlScoreEnabled") is False, "")
    check("AML 自动跑批默认关闭", a.get("autoScanEnabled") is False, "")
    check("AML 大额阈值默认 5000000 分（5 万元）", a.get("largeAmountThreshold") == 5000000, "")
    check("STR 留存期默认 1825 天（5 年）", a.get("retentionDays") == 1825, "")
    check("AML 名单为沙箱来源且无外部 API",
          a.get("watchlistSource") == "SANDBOX" and a.get("externalApi") is False, "")
    check("AML 内置规则已 seed（8 条）", a.get("ruleCount") >= 8, str(a.get("ruleCount")))
    rules = ok("GET", "/api/risk/aml/rules")
    builtin = [r for r in rules if str(r.get("id", "")).startswith("AML_")]
    check("内置规则全部 hardRule=false（默认零拦截）",
          len(builtin) >= 8 and all(r.get("hardRule") is False for r in builtin), str(builtin)[:300])
    check("当前无启用中的硬规则（含历史测试规则均已停用）",
          all(r.get("hardRule") is False for r in rules if r.get("status") == "ENABLED"),
          str([r.get("id") for r in rules if r.get("hardRule") and r.get("status") == "ENABLED"]))
    types = {r.get("ruleType") for r in rules}
    check("六类可疑规则类型齐备",
          {"LARGE_AMOUNT", "SPLIT", "STRUCTURING", "FAST_IN_OUT", "ABNORMAL_HOUR", "CROSS_BORDER"} <= types,
          str(types))
    r = ev(P + "-ride1", 3000, merchantId=P + "-M")
    check("ride 典型流量 evaluate 放行 score=0 action=PASS",
          r.get("pass") is True and r.get("score") == 0 and r.get("action") == "PASS", str(r)[:300])
    r = ev(P + "-ride2", 3000, deviceId=P + "-dev-ride", ip="114.114.114.9")
    check("ride 流量带 deviceId/ip 仍 score=0 PASS（新因子默认影子）",
          r.get("pass") is True and r.get("score") == 0, str(r)[:300])
    r = ev(P + "-topup", 50000, scene="TOPUP")
    check("ride 资金单（TOPUP）evaluate 放行", r.get("pass") is True and r.get("score") == 0, str(r)[:200])
    r = ev(P + "-big", 20000000)
    check("内置单笔限额仍生效且文案不变",
          r.get("pass") is False and r.get("reason") == "超出单笔限额 100000 元"
          and r.get("code") == "RISK_SINGLE_LIMIT", str(r)[:250])
    sb = ok("GET", "/api/risk/ip/sandbox")
    check("离线沙箱 IP 表可见（CIDR/归属地/代理/IDC）",
          isinstance(sb, list) and len(sb) >= 10 and all("cidr" in x for x in sb), str(sb)[:200])

    # ---------- 2. ride 真实支付不被误拦 ----------
    print("[2] ride 典型流量真实支付不被误拦（BALANCE 同步 + 手续费口径不变）")
    merchant = ok("POST", "/api/members", {"name": f"RPTEST商户{SUFFIX}", "type": "MERCHANT"})
    user = ok("POST", "/api/members", {"name": f"RPTEST用户{SUFFIX}", "type": "USER"})
    ma = ok("POST", "/api/accounts", {"memberId": merchant["id"]})
    ua = ok("POST", "/api/accounts", {"memberId": user["id"]})
    ok("POST", "/api/accounting/internal/topup", {"accountId": ua["id"], "amount": 1000000,
                                                 "bizNo": P + "-topup"})
    m0, u0 = bal(ma["id"]), bal(ua["id"])
    order = ok("POST", "/api/orders", {"merchantId": merchant["id"], "subject": "ride镜像行程",
                                       "amount": 3000})["order"]
    r = ok("POST", "/api/cashier/pay", {"orderId": order["id"], "method": "BALANCE",
                                       "accountId": ua["id"], "requestId": P + "-ride-pay"})
    pay = wait_payment(r["payment"]["id"], "SUCCESS")
    check("ride BALANCE 支付 SUCCESS（R-5/R-6 零误拦）", pay.get("status") == "SUCCESS", str(pay)[:250])
    check("手续费口径不变（3000 分 → 18 分）", pay.get("fee") == 18, str(pay.get("fee")))
    check("ride 支付借贷正确（付款方 -3000 / 商户 +2982）",
          bal(ua["id"]) == u0 - 3000 and bal(ma["id"]) == m0 + 2982,
          f"{bal(ua['id'])} vs {u0 - 3000}, {bal(ma['id'])} vs {m0 + 2982}")
    ds = ok("GET", f"/api/risk/decisions?payerId={ua['id']}&limit=5")
    check("真实支付处置留痕 pass=true score=0",
          any(d.get("pass") is True and d.get("score") == 0 for d in ds), str(ds)[:250])
    check("ride 真实支付未产生 AML 误报", len(reports(str(ua["id"]))) == 0,
          str(reports(str(ua["id"])))[:250])

    # ---------- 3. 设备指纹稳定性 + 脱敏 ----------
    print("[3] R-5：设备指纹稳定性判定 + 脱敏存储")
    dev = P + "-dev-A"
    attrs = {"deviceOs": "iOS-17", "deviceModel": "iPhone15", "deviceUa": "rp/1.0"}
    for i in range(2):
        ev(f"{P}-fp-{i}", 1000, deviceId=dev, **attrs)
    d = device(dev)
    check("同属性多次上报指纹稳定（STABLE）", d.get("stability") == "STABLE", str(d)[:300])
    check("初始漂移次数 0", d.get("driftCount") == 0, str(d.get("driftCount")))
    fp0 = d.get("fingerprintHash")
    check("属性指纹哈希非空", bool(fp0), str(d)[:200])
    ev(P + "-fp-2", 1000, deviceId=dev, deviceOs="iOS-17", deviceModel="iPhone15", deviceUa="rp/2.0")
    d = device(dev)
    check("UA 变化 → 漂移 1 次 DRIFTING",
          d.get("driftCount") == 1 and d.get("stability") == "DRIFTING", str(d)[:300])
    ev(P + "-fp-3", 1000, deviceId=dev, deviceOs="iOS-18", deviceModel="iPhone16", deviceUa="rp/2.0")
    ev(P + "-fp-4", 1000, deviceId=dev, deviceOs="Android-15", deviceModel="Pixel9", deviceUa="rp/3.0")
    d = device(dev)
    check("漂移达阈值 3 → UNSTABLE",
          d.get("driftCount") >= 3 and d.get("stability") == "UNSTABLE", str(d)[:300])
    check("脱敏存储键不等于原始设备标识", d.get("deviceId") != dev, str(d.get("deviceId")))
    check("展示掩码含 ****", "****" in (d.get("deviceMask") or ""), str(d.get("deviceMask")))
    check("原始设备标识不出现在画像出参", dev not in json.dumps(d, ensure_ascii=False), str(d)[:300])
    d2 = device(dev)
    check("同一原始标识稳定映射同一存储键", d2.get("deviceId") == d.get("deviceId"), "")
    push({"risk.device.mask-enabled": "false"})
    d3 = device(dev)
    check("脱敏开关可配（关闭后按原始标识存储）", d3.get("deviceId") == dev, str(d3)[:200])
    push({"risk.device.mask-enabled": "true"})

    # ---------- 4. 设备-账户关联图谱 ----------
    print("[4] R-5：设备-账户关联图谱（同设备多账户 / 同账户多设备）")
    gdev = P + "-dev-G"
    for i in range(5):
        ev(f"{P}-g-{i}", 1000, deviceId=gdev, ip="114.114.114.10")
    gd = device(gdev)
    check("同设备关联 5 个账户", gd.get("accountCount") == 5, str(gd)[:300])
    edges = ok("GET", f"/api/risk/device/accounts?deviceId={urllib.request.quote(gdev)}")
    check("设备→账户图谱 5 条边", len(edges) == 5, str(len(edges)))
    check("图谱边账户集合正确",
          {x.get("accountId") for x in edges} == {f"{P}-g-{i}" for i in range(5)}, str(edges)[:300])
    check("图谱边带首次/末次时间与笔数",
          all(x.get("firstSeen") and x.get("lastSeen") and x.get("txnCount") >= 1 for x in edges),
          str(edges)[:250])
    multi = f"{P}-g-0"
    ev(P + "-g-extra", 1000, deviceId=P + "-dev-G2")
    back = ok("GET", f"/api/risk/account/devices?accountId={urllib.request.quote(multi)}")
    check("账户→设备反向图谱可查", any(x.get("deviceId") == gd.get("deviceId") for x in back), str(back)[:250])
    solo = device(P + "-dev-G2")
    check("单账户设备 accountCount=1（对照）", solo.get("accountCount") == 1, str(solo)[:200])
    check("默认阈值 5 下 5 账户设备已构成风险信号（画像分>0）",
          gd.get("riskScore", 0) > 0, str(gd)[:250])

    # ---------- 5. IP 画像 ----------
    print("[5] R-5：IP 画像（归属地/代理/IDC/历史风险，离线沙箱）")
    tor = ipprof("185.220.101.7")
    check("Tor 出口网段判定为代理", tor.get("proxy") is True, str(tor)[:300])
    check("Tor 出口网段判定为 IDC", tor.get("idc") is True, "")
    check("Tor 出口归属地国家 DE", tor.get("country") == "DE", str(tor.get("country")))
    check("归属地含地域描述", "Tor" in (tor.get("region") or ""), str(tor.get("region")))
    cn = ipprof("114.114.114.5")
    check("国内 resolver 网段非代理且国家 CN",
          cn.get("proxy") is False and cn.get("country") == "CN", str(cn)[:250])
    us = ipprof("8.8.8.8")
    check("境外公共 resolver 判定 IDC + 国家 US",
          us.get("idc") is True and us.get("country") == "US", str(us)[:250])
    unk = ipprof("9.9.9.9")
    check("未匹配网段归属地未知（country 空，不当跨境）", unk.get("country") == "", str(unk)[:250])
    check("画像数据源标记 SANDBOX", tor.get("source") == "SANDBOX", str(tor.get("source")))
    hip = "203.0.113.77"
    for i in range(3):
        ev(f"{P}-iphist-{i}", 20000000, ip=hip)
    hp = ipprof(hip)
    check("IP 历史风险聚合本域拒绝留痕（rejectCount ≥ 3）",
          hp.get("rejectCount", 0) >= 3, str(hp)[:300])
    check("历史风险达阈值 → IP 画像风险分 > 0", hp.get("riskScore", 0) > 0, str(hp)[:250])
    ipc = P + "-ipacc"
    for i in range(3):
        ev(f"{P}-ipa-{i}", 1000, ip="103.21.244.9")
    check("同 IP 关联账户数聚合", ipprof("103.21.244.9").get("accountCount", 0) >= 3,
          str(ipprof("103.21.244.9"))[:250])
    push({"risk.ip.sandbox.json": json.dumps([{"cidr": "9.9.9.0/24", "region": "RPTEST自定义",
                                              "country": "JP", "proxy": True, "idc": False}])})
    custom = ipprof("9.9.9.9")
    check("沙箱表可配置覆盖（离线数据，仍无外部请求）",
          custom.get("country") == "JP" and custom.get("proxy") is True, str(custom)[:250])
    check("配置覆盖后数据源标记 CONFIG", custom.get("source") == "CONFIG", str(custom.get("source")))
    push({"risk.ip.sandbox.json": ""})
    check("清空覆盖回退内置离线表", ipprof("9.9.9.9").get("country") == "", "")

    # ---------- 6. 画像因子接入既有评分体系 ----------
    print("[6] R-5：画像因子接入既有 DEVICE/IP 因子（未另起评分体系）")
    push({"risk.device.profile-score-enabled": "true", "risk.ip.profile-score-enabled": "true",
          "risk.device.multi-account-threshold": "3", "risk.ip.history-reject-threshold": "3"})
    r = ev(P + "-score-1", 5000, deviceId=gdev, ip=hip)
    fs = {f.get("code"): f for f in r.get("factors", [])}
    check("因子编码集合仍为既有七类（未新增评分体系）",
          set(fs) <= {"FREQ", "AMOUNT_DEV", "LIST", "DEVICE", "IP", "TIME", "RULE"}, str(list(fs)))
    check("DEVICE 因子权重仍为 25", fs.get("DEVICE", {}).get("weight") == 25, str(fs.get("DEVICE")))
    check("IP 因子权重仍为 20", fs.get("IP", {}).get("weight") == 20, str(fs.get("IP")))
    check("设备画像命中 → DEVICE 因子得 25 分", fs.get("DEVICE", {}).get("score") == 25,
          str(fs.get("DEVICE")))
    check("IP 画像命中 → IP 因子得 20 分", fs.get("IP", {}).get("score") == 20, str(fs.get("IP")))
    check("总分 = 既有因子加权和 45（25+20）", r.get("score") == 45, str(r)[:300])
    check("DEVICE 因子说明含画像判定依据", "画像" in (fs.get("DEVICE", {}).get("detail") or ""),
          str(fs.get("DEVICE")))
    check("全局影子下画像命中只记录不拦截", r.get("pass") is True and r.get("shadow") is True, str(r)[:250])
    ok("POST", "/api/risk/shadow", {"on": False}, step=STEP)
    r = ev(P + "-score-2", 5000, deviceId=gdev, ip=hip)
    check("关闭影子后按既有分级处置（45 → CHALLENGE）",
          r.get("action") == "CHALLENGE" and r.get("pass") is False and r.get("score") == 45, str(r)[:300])
    ok("POST", "/api/risk/shadow", {"on": True}, step=STEP)
    rid = P + "-R-DEV"
    ok("POST", "/api/risk/rules", {"id": rid, "name": "画像设备规则", "scene": "PAY", "priority": 1,
                                   "conditionExpr": "deviceRisk(deviceId) && ipRisk(ip)",
                                   "action": "REJECT", "score": 10, "status": "PUBLISHED",
                                   "grayPercent": 100}, step=STEP)
    ok("POST", f"/api/risk/rules/{rid}/publish", {"grayPercent": 100}, step=STEP)
    r = ev(P + "-score-3", 5000, deviceId=gdev, ip=hip)
    check("既有 deviceRisk/ipRisk 函数已接入画像（规则命中）",
          any(rid in h for h in r.get("hitRules", [])), str(r)[:300])
    rid2 = P + "-R-DEV2"
    ok("POST", "/api/risk/rules", {"id": rid2, "name": "画像函数规则", "scene": "PAY", "priority": 2,
                                   "conditionExpr": "deviceAccountCount(deviceId) >= 3 && ipProxy(ip)",
                                   "action": "CHALLENGE", "score": 5, "status": "PUBLISHED",
                                   "grayPercent": 100}, step=STEP)
    ok("POST", f"/api/risk/rules/{rid2}/publish", {"grayPercent": 100}, step=STEP)
    r = ev(P + "-score-4", 5000, deviceId=gdev, ip="185.220.101.20")
    check("新增画像函数 deviceAccountCount/ipProxy 可用于 QLExpress",
          any(rid2 in h for h in r.get("hitRules", [])), str(r)[:300])
    ok("POST", f"/api/risk/rules/{rid}/disable", {}, step=STEP)
    ok("POST", f"/api/risk/rules/{rid2}/disable", {}, step=STEP)
    push({"risk.device.profile-score-enabled": "false", "risk.ip.profile-score-enabled": "false"})
    r = ev(P + "-score-5", 5000, deviceId=gdev, ip=hip)
    check("关闭画像评分开关 → 回到 score=0（默认口径可回退）",
          r.get("score") == 0 and r.get("pass") is True, str(r)[:300])

    # ---------- 7. 行为序列 ----------
    print("[7] R-5：行为序列（时序 / 异地跳变 / 突发）")
    bp = P + "-behavior"
    ev(bp, 1000, deviceId=P + "-dev-B", ip="114.114.114.20")
    ev(bp, 2000, deviceId=P + "-dev-B", ip="185.220.101.30")
    bl = ok("GET", f"/api/risk/behavior?payerId={urllib.request.quote(bp)}&limit=20")
    check("行为序列样本可查（≥2 条）", len(bl) >= 2, str(len(bl)))
    check("行为样本含金额/动作/地域",
          all(x.get("amount") is not None and x.get("action") and x.get("region") for x in bl), str(bl)[:250])
    an = ok("GET", f"/api/risk/behavior/analyze?payerId={urllib.request.quote(bp)}")
    check("异地跳变识别（geoJump=true）", an.get("geoJump") is True, str(an)[:300])
    check("跨境识别（countries 含 CN 与 DE）",
          {"CN", "DE"} <= set(an.get("countries") or []), str(an)[:300])
    check("窗口内笔数统计正确", an.get("count") >= 2, str(an.get("count")))
    check("默认突发阈值 20 下正常流量不判 burst", an.get("burst") is False, str(an)[:200])

    # ---------- 8. AML 六类可疑规则 ----------
    print("[8] R-6：AML 六类可疑规则命中 + 自定义 QLExpress")
    # 8.1 大额（实时）
    lp = P + "-aml-large"
    lr = new_rule("LARGE", "LARGE_AMOUNT", threshold=200000, realtime=True, score=60)
    r = ev(lp, 300000)
    check("大额命中不阻断交易（pass=true）", r.get("pass") is True, str(r)[:250])
    reps = reports_of(lp, lr)
    check("大额实时命中生成 STR", len(reps) == 1, str(reps)[:300])
    if reps:
        check("STR 初始状态 PENDING（待审）", reps[0].get("status") == "PENDING", str(reps[0])[:250])
        check("STR 记录规则类型与金额",
              reps[0].get("ruleType") == "LARGE_AMOUNT" and reps[0].get("txnAmount") == 300000,
              str(reps[0])[:250])
        check("STR 分级由既有 DispositionService 给出（60 分 LIMIT 档，本笔超降额上限 → REJECT）",
              reps[0].get("disposition") == "REJECT", str(reps[0].get("disposition")))
        check("STR 幂等键含业务日", bool(reps[0].get("bizDate")), str(reps[0])[:200])
    disable_rule(lr)
    # 8.2 拆分（聚合，需跨限频窗口分批）
    sp = P + "-aml-split"
    sr = new_rule("SPLIT", "SPLIT", threshold=100000, minCount=8, score=70)
    for batch in range(2):
        for i in range(4):
            ev(sp, 15000)
        if batch == 0:
            time.sleep(11)
    res = scan(sp)
    check("拆分规则扫描命中（8 笔小额累计超阈值）",
          any("SPLIT" in h for h in res.get("hitRules", [])), str(res)[:350])
    srep = reports_of(sp, sr)
    check("拆分生成 STR 且笔数=8", len(srep) == 1 and srep[0].get("txnCount") == 8, str(srep)[:300])
    res2 = scan(sp)
    check("重复扫描幂等（reused ≥ 1，created 不增）",
          res2.get("reused", 0) >= 1 and res2.get("created", 0) == 0, str(res2)[:300])
    check("重复扫描不重复建报告", len(reports_of(sp, sr)) == 1, str(reports_of(sp, sr))[:250])
    disable_rule(sr)
    # 8.3 结构化
    stp = P + "-aml-struct"
    str_ = new_rule("STRUCT", "STRUCTURING", threshold=100000, minCount=3, ratio=90, score=70)
    for i in range(3):
        ev(stp, 95000)
    res = scan(stp)
    check("结构化规则命中（3 笔贴近阈值下方）",
          any("STRUCTURING" in h for h in res.get("hitRules", [])), str(res)[:350])
    check("结构化生成 STR", len(reports_of(stp, str_)) == 1, str(reports_of(stp, str_))[:250])
    disable_rule(str_)
    # 8.4 快进快出
    fp = P + "-aml-fast"
    fr = new_rule("FAST", "FAST_IN_OUT", threshold=50000, windowMillis=600000, ratio=90, score=80)
    ev(fp, 60000, scene="TOPUP")
    ev(fp, 58000, scene="WITHDRAW")
    res = scan(fp)
    check("快进快出规则命中（窗口内入账后快速出账 ≥90%）",
          any("FAST_IN_OUT" in h for h in res.get("hitRules", [])), str(res)[:350])
    frep = reports_of(fp, fr)
    check("快进快出生成 STR", len(frep) == 1, str(frep)[:250])
    if frep:
        detail = json.loads(frep[0].get("detail") or "{}")
        obs = detail.get("observations", {})
        check("STR 明细落聚合观测口径（入账 60000 / 出账 58000）",
              obs.get("fastIn") == 60000 and obs.get("fastOut") == 58000, str(obs)[:250])
    disable_rule(fr)
    # 8.5 异常时段
    np = P + "-aml-night"
    hour = time.localtime().tm_hour
    push({"risk.aml.night-hours": str(hour)})
    nr = new_rule("NIGHT", "ABNORMAL_HOUR", threshold=1000, minCount=1, realtime=True, score=50)
    ev(np, 5000)
    nrep = reports_of(np, nr)
    check("异常时段规则实时命中生成 STR", len(nrep) == 1, str(nrep)[:300])
    if nrep:
        check("STR 分级复用既有分档（50 分 → CHALLENGE，未另起体系）",
              nrep[0].get("disposition") == "CHALLENGE", str(nrep[0])[:300])
    disable_rule(nr)
    push({"risk.aml.night-hours": "0,1,2,3,4,5"})
    # 8.6 跨境
    cp = P + "-aml-cross"
    cr = new_rule("CROSS", "CROSS_BORDER", threshold=1000, minCount=1, realtime=True, score=60)
    ev(cp, 5000, ip="185.220.101.40")
    check("跨境规则命中生成 STR（沙箱归属地 DE）", len(reports_of(cp, cr)) == 1,
          str(reports_of(cp, cr))[:300])
    disable_rule(cr)
    cp2 = P + "-aml-cross-cn"
    cr2 = new_rule("CROSS2", "CROSS_BORDER", threshold=1000, minCount=1, realtime=True, score=60)
    ev(cp2, 5000, ip="114.114.114.40")
    check("境内 IP 不判跨境（零误报）", len(reports_of(cp2, cr2)) == 0, str(reports_of(cp2, cr2))[:250])
    ev(cp2 + "-unk", 5000, ip="9.9.9.9")
    check("未知归属地不判跨境（零误报）", len(reports_of(cp2 + "-unk", cr2)) == 0, "")
    disable_rule(cr2)
    # 8.7 自定义 QLExpress（复用既有引擎）
    xp = P + "-aml-custom"
    xr = new_rule("CUSTOM", "CUSTOM", conditionExpr="dayCount(payerId) >= 2 && dayAmount(payerId) > 10000",
                  score=75)
    ev(xp, 8000)
    ev(xp, 9000)
    res = scan(xp)
    check("自定义 QLExpress 规则（dayCount/dayAmount 函数）命中",
          any("CUSTOM" in h for h in res.get("hitRules", [])), str(res)[:350])
    check("自定义规则生成 STR", len(reports_of(xp, xr)) == 1, str(reports_of(xp, xr))[:250])
    disable_rule(xr)

    # ---------- 9. 名单筛查（制裁/PEP 沙箱） ----------
    print("[9] R-6：名单筛查（制裁/PEP）+ 转人工经 DisputeFacade（MANUAL 不动账）")
    san = P + "-SAN-ENTITY"
    pep = P + "-PEP-OFFICIAL"
    w1 = ok("POST", "/api/risk/aml/watchlist", {"listType": "SANCTION", "name": san,
                                               "aliases": "RPTEST制裁别名," + san + "-ALT",
                                               "country": "XX"}, step=STEP)
    TEST_WATCH.append(str(w1.get("id")))
    w2 = ok("POST", "/api/risk/aml/watchlist", {"listType": "PEP", "name": pep,
                                               "aliases": "RPTEST政要别名", "country": "CN"}, step=STEP)
    TEST_WATCH.append(str(w2.get("id")))
    wl = ok("GET", "/api/risk/aml/watchlist?listType=SANCTION")
    check("沙箱制裁名单可增可查", any(x.get("name") == san for x in wl), str(wl)[:300])
    s1 = ok("POST", "/api/risk/aml/screen", {"subjectType": "PAYER", "subject": san})
    check("制裁名单精确命中（EXACT）",
          s1.get("hit") is True and s1.get("matchType") == "EXACT" and s1.get("listType") == "SANCTION",
          str(s1)[:300])
    s2 = ok("POST", "/api/risk/aml/screen", {"subjectType": "PAYER", "subject": san + "-ALT"})
    check("别名命中（ALIAS）", s2.get("hit") is True and s2.get("matchType") == "ALIAS", str(s2)[:300])
    s3 = ok("POST", "/api/risk/aml/screen", {"subjectType": "PAYER", "subject": pep})
    check("PEP 名单命中", s3.get("hit") is True and s3.get("listType") == "PEP", str(s3)[:300])
    s4 = ok("POST", "/api/risk/aml/screen", {"subjectType": "PAYER", "subject": P + "-clean-subject"})
    check("未命中也留痕（hit=false）", s4.get("hit") is False, str(s4)[:250])
    sl = ok("GET", "/api/risk/aml/screenings?limit=50")
    check("筛查留痕可查（含命中与未命中）",
          any(x.get("hit") is False for x in sl) and any(x.get("hit") is True for x in sl), str(sl)[:300])
    check("筛查对象脱敏（掩码含 ****）",
          all("****" in (x.get("subjectMask") or "") for x in sl if x.get("subjectMask")), str(sl)[:250])
    mr = ev(san, 8000, bizNo=P + "-san-biz")
    check("制裁命中不阻断交易（默认非硬规则）", mr.get("pass") is True, str(mr)[:250])
    srep = [x for x in reports(san) if x.get("ruleType") == "SANCTION"]
    check("制裁命中生成 STR", len(srep) >= 1, str(srep)[:300])
    if srep:
        check("制裁 STR 分 100 → 既有分级 MANUAL_REVIEW",
              srep[0].get("score") == 100 and srep[0].get("disposition") == "MANUAL_REVIEW", str(srep[0])[:300])
        check("制裁 STR 经 DisputeFacade 建单（disputeId 非空）", bool(srep[0].get("disputeId")),
              str(srep[0])[:300])
        if srep[0].get("disputeId"):
            dsp = ok("GET", f"/api/disputes/{srep[0]['disputeId']}")
            check("差错单 source=PAYMENT", dsp.get("source") == "PAYMENT", str(dsp)[:300])
            check("差错单 type=AML_STR", dsp.get("type") == "AML_STR", str(dsp)[:250])
            check("差错单 strategy=MANUAL（不自动动账）", dsp.get("strategy") == "MANUAL", str(dsp)[:250])
            check("差错单未被自动核销（仍待人工）",
                  dsp.get("status") in ("OPEN", "ASSIGNED"), str(dsp.get("status")))
    ok("POST", f"/api/risk/aml/watchlist/{w1['id']}/remove", {}, step=STEP)
    s5 = ok("POST", "/api/risk/aml/screen", {"subjectType": "PAYER", "subject": san})
    check("名单软删后不再命中", s5.get("hit") is False, str(s5)[:250])
    wl2 = ok("GET", "/api/risk/aml/watchlist?listType=SANCTION")
    check("软删保留历史行（status=REMOVED 可审计）",
          any(x.get("name") == san and x.get("status") == "REMOVED" for x in wl2), str(wl2)[:300])

    # ---------- 10. STR 状态机与审计 ----------
    print("[10] R-6：STR 状态机（待审→上报→归档）+ 审计 + 交易引用")
    target = srep[0] if srep else (reports_of(lp, lr) or [None])[0]
    assert target, "无可用于状态机验证的 STR"
    rid_ = target["id"]
    got = ok("GET", f"/api/risk/aml/reports/{rid_}")
    check("STR 详情可查且带报告编号", str(got.get("reportNo", "")).startswith("STR"), str(got)[:250])
    check("STR 详情带交易引用", isinstance(got.get("paymentIds"), list) and len(got["paymentIds"]) >= 1,
          str(got)[:300])
    sub = ok("POST", f"/api/risk/aml/reports/{rid_}/submit", {"note": "RPTEST 上报监管"}, step=STEP)
    check("上报：PENDING → SUBMITTED", sub.get("status") == "SUBMITTED", str(sub)[:250])
    check("上报记录操作人与时间", sub.get("operator") == "admin" and sub.get("submittedAt"), str(sub)[:250])
    st2, resp2 = req("POST", f"/api/risk/aml/reports/{rid_}/submit", {"note": "重复上报"},
                     token=ADMIN, step=STEP)
    check("重复上报冲突（409 A0409）", st2 == 409, f"{st2} {resp2}")
    clo = ok("POST", f"/api/risk/aml/reports/{rid_}/close", {"note": "RPTEST 归档"}, step=STEP)
    check("归档：SUBMITTED → CLOSED", clo.get("status") == "CLOSED", str(clo)[:250])
    check("归档记录 closedAt", bool(clo.get("closedAt")), str(clo)[:200])
    st3, resp3 = req("POST", f"/api/risk/aml/reports/{rid_}/close", {}, token=ADMIN, step=STEP)
    check("重复归档冲突（409）", st3 == 409, f"{st3} {resp3}")
    aud = ok("GET", f"/api/risk/aml/reports/{rid_}/audit")
    actions = [x.get("action") for x in aud]
    check("审计含 CREATE/SUBMIT/CLOSE 全链路",
          {"CREATE", "SUBMIT", "CLOSE"} <= set(actions), str(actions))
    check("审计记录状态前后值与操作人",
          any(x.get("fromStatus") == "PENDING" and x.get("toStatus") == "SUBMITTED"
              and x.get("operator") == "admin" for x in aud), str(aud)[:350])
    pend = [x for x in reports(status="PENDING") if x.get("id") != rid_]
    if pend:
        p2 = pend[0]["id"]
        c2 = ok("POST", f"/api/risk/aml/reports/{p2}/close", {"note": "RPTEST 排除误报"}, step=STEP)
        check("待审可直接归档（排除误报 PENDING → CLOSED）", c2.get("status") == "CLOSED", str(c2)[:250])
    txns = ok("GET", f"/api/risk/aml/txns?payerId={urllib.request.quote(str(target['payerId']))}&limit=50")
    check("AML 交易流水镜像可查", len(txns) >= 1, str(len(txns)))
    check("报告交易引用与流水一致",
          set(got.get("paymentIds") or [])
          <= ({str(t.get("paymentId")) for t in txns} | {f"TXN:{t.get('createdAt')}" for t in txns}),
          f"refs={got.get('paymentIds')} txns={[t.get('paymentId') for t in txns]}")
    check("STR 留存期落库（5 年）",
          got.get("retentionUntil") and got["retentionUntil"] > got["createdAt"], str(got)[:250])

    # ---------- 11. 上报不阻断正常交易 / 硬规则才拒绝 ----------
    print("[11] R-6：上报不阻断正常交易；硬规则受理前拒绝且零悬挂单")
    ok("POST", "/api/accounting/internal/topup", {"accountId": ua["id"], "amount": 500000,
                                                 "bizNo": P + "-topup2"})
    nr2 = new_rule("NONHARD", "LARGE_AMOUNT", threshold=1000, realtime=True, score=60, hard=False)
    u1, m1 = bal(ua["id"]), bal(ma["id"])
    order2 = ok("POST", "/api/orders", {"merchantId": merchant["id"], "subject": "ride镜像行程2",
                                        "amount": 3000})["order"]
    r2 = ok("POST", "/api/cashier/pay", {"orderId": order2["id"], "method": "BALANCE",
                                        "accountId": ua["id"], "requestId": P + "-ride-pay2"})
    pay2 = wait_payment(r2["payment"]["id"], "SUCCESS")
    check("AML 非硬规则命中下真实支付仍 SUCCESS（上报不阻断）",
          pay2.get("status") == "SUCCESS", str(pay2)[:300])
    check("不阻断时手续费口径不变（18 分）", pay2.get("fee") == 18, str(pay2.get("fee")))
    check("不阻断时资金正常划转", bal(ua["id"]) == u1 - 3000 and bal(ma["id"]) == m1 + 2982,
          f"{bal(ua['id'])} vs {u1 - 3000}")
    check("AML 命中同时生成 STR（监测与放行并存）",
          len(reports_of(str(ua["id"]), nr2)) >= 1, str(reports_of(str(ua["id"]), nr2))[:300])
    disable_rule(nr2)
    # 硬规则：开启硬拦截 + 关闭全局影子
    hr = new_rule("HARD", "LARGE_AMOUNT", threshold=1000, realtime=True, score=90, hard=True)
    push({"risk.aml.hard-block-enabled": "true"})
    ok("POST", "/api/risk/shadow", {"on": False}, step=STEP)
    rh = ev(P + "-hard-ev", 5000)
    check("硬规则命中 → 受理前拒绝（pass=false）", rh.get("pass") is False, str(rh)[:300])
    check("硬规则拒绝命中留痕含 AML 规则",
          any("AML:" in h for h in rh.get("hitRules", [])), str(rh)[:300])
    u2, m2 = bal(ua["id"]), bal(ma["id"])
    order3 = ok("POST", "/api/orders", {"merchantId": merchant["id"], "subject": "ride镜像行程3",
                                        "amount": 3000})["order"]
    st_h, resp_h = req("POST", "/api/cashier/pay", {"orderId": order3["id"], "method": "BALANCE",
                                                    "accountId": ua["id"], "requestId": P + "-ride-pay3"},
                       token=ADMIN)
    check("硬规则拒绝在受理前返回 400 风控拒绝",
          st_h == 400 and "风控拒绝" in str(resp_h.get("error", "")), f"{st_h} {resp_h}")
    plist = ok("GET", "/api/payments")
    rows = plist if isinstance(plist, list) else plist.get("list", plist.get("records", []))
    mine = [x for x in rows if x.get("requestId") == P + "-ride-pay3"]
    check("硬规则拒绝的真实支付单终态 FAILED（非 PROCESSING 悬挂）",
          bool(mine) and all(x.get("status") == "FAILED" for x in mine), str(mine)[:300])
    check("硬规则拒绝余额未动（受理前拒绝）",
          bal(ua["id"]) == u2 and bal(ma["id"]) == m2, f"{bal(ua['id'])} vs {u2}")
    disable_rule(hr)
    push({"risk.aml.hard-block-enabled": "false"})
    ok("POST", "/api/risk/shadow", {"on": True}, step=STEP)
    order4 = ok("POST", "/api/orders", {"merchantId": merchant["id"], "subject": "ride镜像行程4",
                                        "amount": 3000})["order"]
    r4 = ok("POST", "/api/cashier/pay", {"orderId": order4["id"], "method": "BALANCE",
                                        "accountId": ua["id"], "requestId": P + "-ride-pay4"})
    pay4 = wait_payment(r4["payment"]["id"], "SUCCESS")
    check("关闭硬拦截后 ride 流量恢复 SUCCESS（可回退）", pay4.get("status") == "SUCCESS", str(pay4)[:250])

    # ---------- 12. 护栏红线 ----------
    print("[12] 护栏：AML 规则编译失败/执行异常/超时 → 跳过并告警不阻断；R-1 护栏仍生效")
    gp = P + "-guard"
    bad1 = new_rule("BADCOMPILE", "CUSTOM", conditionExpr="amount > 100 &&", score=99)
    bad2 = new_rule("BADEXEC", "CUSTOM", conditionExpr="1/0 > 0", score=99)
    bad3 = new_rule("BADTIMEOUT", "CUSTOM",
                    conditionExpr="sum=0; for(i=0;i<99999999;i++){sum=sum+i;} return sum>0;", score=99)
    rl = ok("GET", "/api/risk/aml/rules")
    bc = next((x for x in rl if x.get("id") == bad1), {})
    check("AML 编译失败标记 COMPILE_FAILED", bc.get("compileStatus") == "COMPILE_FAILED", str(bc)[:250])
    rg = ev(gp, 5000)
    check("护栏下正常交易仍放行（AML 规则错误不拦截）", rg.get("pass") is True, str(rg)[:250])
    res = scan(gp)
    check("扫描降级计数 ≥ 2（编译失败 + 执行异常/超时）", res.get("degraded", 0) >= 2, str(res)[:350])
    kinds = alert_kinds()
    check("编译失败告警留痕", "AML_RULE_COMPILE_FAIL" in kinds, str(kinds)[:300])
    check("执行异常/超时告警留痕",
          bool(kinds & {"AML_RULE_EXEC", "AML_RULE_TIMEOUT"}), str(kinds)[:300])
    check("护栏降级不产生误报 STR", len(reports(gp)) == 0, str(reports(gp))[:250])
    for b in (bad1, bad2, bad3):
        disable_rule(b)
    # R-1 既有引擎护栏仍生效
    ok("POST", "/api/risk/rules", {"id": P + "-R-BAD", "name": "语法错", "conditionExpr": "amount > 100 &&",
                                   "action": "REJECT", "score": 90, "status": "PUBLISHED",
                                   "grayPercent": 100, "priority": 1}, step=STEP)
    ok("POST", f"/api/risk/rules/{P}-R-BAD/publish", {"grayPercent": 100}, step=STEP)
    rg2 = ev(P + "-guard2", 5000)
    check("R-1 引擎护栏仍生效（degraded=true 且放行）",
          rg2.get("degraded") is True and rg2.get("pass") is True, str(rg2)[:300])
    ok("POST", f"/api/risk/rules/{P}-R-BAD/disable", {}, step=STEP)
    # last-known-good
    ok("GET", "/api/risk/aml/rules")
    before = astatus().get("ruleCount")
    push({"risk.aml.rules.json": "{不是合法JSON"})
    ok("GET", "/api/risk/aml/rules")
    after = astatus().get("ruleCount")
    check("AML 配置 JSON 不可解析 → last-known-good（规则集不清空）",
          after == before and after >= 8, f"{before} -> {after}")
    check("配置解析失败告警留痕", "AML_CONFIG_PARSE_FAIL" in alert_kinds(), str(alert_kinds())[:300])
    push({"risk.aml.rules.json": ""})
    rg3 = ev(P + "-guard3", 5000)
    check("护栏全程不影响正常交易放行", rg3.get("pass") is True and rg3.get("score") == 0, str(rg3)[:250])

    # ---------- 13. 留存与脱敏 ----------
    print("[13] 留存期可配 + 到期匿名化（不删历史行）")
    ps2 = pstatus()
    check("留存期可配（设备/IP/行为）",
          ps2.get("deviceRetentionDays") == 90 and ps2.get("ipRetentionDays") == 90
          and ps2.get("behaviorRetentionDays") == 30, str(ps2)[:300])
    push({"risk.device.retention-days": "1", "risk.ip.retention-days": "1",
          "risk.behavior.retention-days": "1"})
    check("留存期配置热更生效", pstatus().get("deviceRetentionDays") == 1, str(pstatus())[:250])
    ret_dev = P + "-dev-RET"
    ev(P + "-retention", 1000, deviceId=ret_dev, ip="203.0.113.90")
    rd = device(ret_dev)
    now_ms = int(time.time() * 1000)
    check("留存到期时间按配置落库（≈ now + 1 天）",
          rd.get("retentionUntil") and abs(rd["retentionUntil"] - (now_ms + 86400000)) < 120000,
          str(rd)[:300])
    check("新采集行未到期（expired=false）", rd.get("expired") is False, str(rd)[:200])
    ret = ok("POST", "/api/risk/profile/retention", {}, step=STEP)
    check("当前时间执行留存：未到期行不匿名化", ret.get("anonymizedDevices") == 0, str(ret)[:300])
    check("留存期执行绝不删行（deletedRows=0，mode=ANONYMIZE）",
          ret.get("deletedRows") == 0 and ret.get("mode") == "ANONYMIZE", str(ret)[:300])
    ret2 = ok("POST", "/api/risk/profile/retention", {"beforeMillis": now_ms + 2 * 86400000}, step=STEP)
    check("补跑历史留存窗口：到期设备行被匿名化（≥1）",
          ret2.get("anonymizedDevices", 0) >= 1, str(ret2)[:300])
    check("补跑留存同样不删行", ret2.get("deletedRows") == 0, str(ret2)[:250])
    rd2 = device(ret_dev)
    check("到期行标记 expired=true", rd2.get("expired") is True, str(rd2)[:300])
    check("到期行标签/指纹/掩码已匿名化清空",
          not rd2.get("tags") and not rd2.get("fingerprintHash") and not rd2.get("deviceMask"),
          str(rd2)[:300])
    check("匿名化保留主键与计数（历史行仍可审计）",
          rd2.get("deviceId") == rd.get("deviceId") and rd2.get("accountCount") == rd.get("accountCount"),
          str(rd2)[:300])
    push({"risk.device.retention-days": "90", "risk.ip.retention-days": "90",
          "risk.behavior.retention-days": "30"})

    # ---------- 14. RBAC + 二次认证 ----------
    print("[14] RBAC + 二次认证")
    st4, _ = req("GET", f"/api/risk/device?deviceId={dev}")
    check("未登录访问画像端点 401", st4 == 401, str(st4))
    st5, _ = req("GET", "/api/risk/aml/reports")
    check("未登录访问 AML 报告 401", st5 == 401, str(st5))
    st6, _ = req("POST", "/api/risk/aml/scan", {}, token=ADMIN)
    check("无 X-Step-Up-Token 的敏感操作 403", st6 == 403, str(st6))
    st7, _ = req("POST", "/api/risk/aml/watchlist", {"listType": "SANCTION", "name": P + "-X"},
                 token=ADMIN)
    check("无二次认证的名单变更 403", st7 == 403, str(st7))
    st8, _ = req("POST", "/api/risk/aml/scan", {}, token=ADMIN, step=STEP)
    check("带二次认证的敏感操作 200", st8 == 200, str(st8))

    # ---------- 15. local 降级等价 ----------
    print("[15] local 降级等价（离线沙箱双 profile 一致）")
    ps3 = pstatus()
    check("双 profile 均为离线沙箱数据源（无外部 API 依赖）",
          ps3.get("ipDataSource") in ("SANDBOX", "CONFIG") and ps3.get("ipExternalApi") is False, str(ps3)[:250])
    check("AML 名单双 profile 均为沙箱来源", astatus().get("watchlistSource") == "SANDBOX", "")
    check("沙箱归属地判定与 profile 无关（DE 代理网段）",
          ipprof("185.220.101.7").get("proxy") is True, "")
    health = ok("GET", "/api/health")
    check("画像/AML 不依赖 Redis 且全栈 13 进程在线（local 零依赖可运行）",
          isinstance(health, list) and len(health) == 13 and all(x.get("ok") for x in health),
          str([x.get("service") for x in health if not x.get("ok")]) if isinstance(health, list) else str(health))
    if PROFILE == "local":
        check("local profile 限额计数为内存实现",
              ok("GET", "/api/risk/engine").get("quotaProvider") == "memory", "")
    fin = ev(P + "-final-ride", 3000, merchantId=P + "-M")
    check("收尾 ride 典型流量仍 score=0 PASS（默认口径已复位）",
          fin.get("pass") is True and fin.get("score") == 0 and fin.get("action") == "PASS", str(fin)[:300])
    check("收尾默认影子已恢复", ok("GET", "/api/risk/engine").get("globalShadow") is True, "")
    check("收尾画像评分开关已复位为影子",
          pstatus().get("deviceProfileScoreEnabled") is False
          and pstatus().get("ipProfileScoreEnabled") is False, str(pstatus())[:250])
    check("收尾 AML 硬拦截已复位关闭", astatus().get("hardBlockEnabled") is False, "")


if __name__ == "__main__":
    try:
        main()
    finally:
        cleanup()
    print(f"\n== verify-risk-plus 结果: {passed} 通过 / {failed} 失败 ==")
    if FAILURES:
        print("失败项:")
        for f in FAILURES:
            print("  - " + f)
    sys_exit = 1 if failed else 0
    raise SystemExit(sys_exit)