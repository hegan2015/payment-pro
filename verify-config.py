#!/usr/bin/env python3
"""verify-config.py — wave3 I-2 配置中心 Apollo + local 文件降级验证。
跑法: python3 verify-config.py  （payment-pro 栈需已启动，gateway 8080；docker profile 亦可）

覆盖（对照 I-2 卡验收）:
  ① local 改 ~/.config/payment-pro/config.properties 的 payment.fee-bps → 30s 自动 reload /
     POST /api/config/refresh 手动 reload 后，新支付单手续费按新值；
     已受理单用受理时快照（payment.fee 落库）：费率变更后其二阶段账务/结算/退款仍按旧值，
     退款手续费按快照比例冲回（refundedFee + 退款单据 SYS_FEE 借腿逐笔断言）；
  ② 配置源不可达 → 支付仍成功（降级红线）：local 删文件→默认值兜底；
     apollo provider 时停 pp-apollo-configservice 容器→last-known-good/默认值，支付不阻断；
  ③ GET /api/config 需登录（401）、密钥类键脱敏（******，明文不出网关）、
     POST /api/config/refresh RBAC（READONLY 403 A0403，ADMIN 通配 * 200）；
  ④ 默认值与改造前逐一相同：fee-bps=60 / risk.single-limit=10000000 /
     risk.window-millis=10000 / risk.window-max=5（effective 与 defaults 双表断言 + 行为断言）；
  ⑤ 恢复原文件后口径回归（新单 fee 复原 18 分），脚本退出时原文件字节级还原。
风控热更：window-max=2 第 3 笔拒「频繁」；single-limit=1000 拒「超出单笔限额 10 元」；恢复后放行。
测试数据独立前缀 CFGTEST + uuid，可重复跑；全程不删除任何业务数据。
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("PP_GATEWAY", "http://127.0.0.1:8080")
ADMIN_PASS = os.environ.get("PP_ADMIN_PASSWORD", "admin123")
CONFIG_FILE = os.path.expanduser("~/.config/payment-pro/config.properties")
DOCKER = os.environ.get("DOCKER_BIN", "/Users/hegan/.docker/bin/docker")
APOLLO_ADMIN = os.environ.get("PP_APOLLO_ADMIN", "http://127.0.0.1:18071")
APOLLO_APP = os.environ.get("PP_APOLLO_APP_ID", "payment-pro")
SUFFIX = uuid.uuid4().hex[:8]
DEFAULTS = {
    "payment.fee-bps": 60,
    "payment.risk.single-limit": 10000000,
    "payment.risk.window-millis": 10000,
    "payment.risk.window-max": 5,
}
passed = failed = 0


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  [PASS] {name}")
    else:
        failed += 1
        print(f"  [FAIL] {name}  {detail}")


def req(method, path, body=None, token=None, base=None, accept="application/json", timeout=20):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request((base or BASE) + path, data=data, method=method)
    r.add_header("Accept", accept)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
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


def poll(fn, pred, timeout=30, interval=1):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = fn()
        if pred(last):
            return last
        time.sleep(interval)
    return last


def fee_of(amount, bps):
    return (amount * bps + 5000) // 10000


st, resp = req("POST", "/api/auth/login", {"username": "admin", "password": ADMIN_PASS})
assert st == 200 and resp.get("token"), f"admin 登录失败: {st} {resp}"
ADMIN = resp["token"]


def ok(method, path, body=None, token=None):
    st, resp = req(method, path, body, token=token or ADMIN)
    assert st == 200, f"{method} {path} 期望 200 实际 {st}: {resp}"
    return resp


def bal(account_id):
    return ok("GET", f"/api/accounting/accounts/{account_id}/balance")


def config_view():
    return ok("GET", "/api/config")


def refresh():
    st, resp = req("POST", "/api/config/refresh", {}, token=ADMIN)
    assert st == 200, f"refresh 期望 200 实际 {st}: {resp}"
    return resp


def transfer_fee_line(biz_no, dir_):
    """单据中 SYS_FEE 腿金额（无则 0）。"""
    st, tr = req("GET", f"/api/accounting/transfers/{biz_no}", token=ADMIN)
    if st != 200:
        return None
    return sum(l["amount"] for l in tr.get("lines", [])
               if l["accountId"] == "SYS_FEE" and l["dir"] == dir_)


# ---------------- 配置源适配（local 文件 / apollo adminservice OpenAPI） ----------------
PROVIDER = None
ORIGINAL = None  # 原文件字节（None=原本不存在）


def write_local(text):
    os.makedirs(os.path.dirname(CONFIG_FILE), exist_ok=True)
    with open(CONFIG_FILE, "w") as f:
        f.write(text)
    os.chmod(CONFIG_FILE, 0o600)


def apollo_req(method, path, body=None):
    return req(method, path, body, base=APOLLO_ADMIN)


def apollo_namespace_id():
    st, ns = apollo_req("GET", f"/apps/{APOLLO_APP}/clusters/default/namespaces/application")
    if st == 200 and isinstance(ns, dict) and ns.get("id"):
        return ns["id"]
    st, resp = apollo_req("POST", f"/apps/{APOLLO_APP}/appnamespaces",
                          {"name": "application", "appId": APOLLO_APP, "format": "properties",
                           "comment": "application", "dataChangeCreatedBy": "apollo"})
    assert st in (200, 400), f"创建 namespace 失败: {st} {resp}"
    st, ns = apollo_req("GET", f"/apps/{APOLLO_APP}/clusters/default/namespaces/application")
    assert st == 200, f"读取 namespace 失败: {st} {ns}"
    return ns["id"]


def apollo_item_ids():
    st, items = apollo_req("GET", f"/apps/{APOLLO_APP}/clusters/default/namespaces/application/items")
    if st != 200 or not isinstance(items, list):
        return {}
    return {i["key"]: i["id"] for i in items if i.get("key")}


def apollo_publish(items):
    """经 adminservice OpenAPI 发布 application namespace（app/namespace/item 幂等）。"""
    st, _ = apollo_req("GET", f"/apps/{APOLLO_APP}")
    if st != 200:
        st, resp = apollo_req("POST", "/apps", {
            "appId": APOLLO_APP, "name": APOLLO_APP, "orgId": "TEST", "orgName": "TEST",
            "ownerName": "apollo", "ownerEmail": "apollo@localhost", "dataChangeCreatedBy": "apollo"})
        assert st == 200, f"创建 Apollo app 失败: {st} {resp}"
    ns_id = apollo_namespace_id()
    by_key = apollo_item_ids()
    for k, v in items.items():
        if k in by_key:
            st, resp = apollo_req("PUT", f"/apps/{APOLLO_APP}/clusters/default/namespaces/application/items/{by_key[k]}",
                                  {"key": k, "value": str(v), "comment": "verify-config",
                                   "dataChangeLastModifiedBy": "apollo"})
            assert st == 200, f"更新 item {k} 失败: {st} {resp}"
        else:
            st, resp = apollo_req("POST", f"/apps/{APOLLO_APP}/clusters/default/namespaces/application/items",
                                  {"namespaceId": ns_id, "key": k, "value": str(v), "comment": "verify-config",
                                   "dataChangeCreatedBy": "apollo", "dataChangeLastModifiedBy": "apollo"})
            assert st == 200, f"创建 item {k} 失败: {st} {resp}"
    st, resp = apollo_req("POST",
                          f"/apps/{APOLLO_APP}/clusters/default/namespaces/application/releases"
                          f"?name=verify-config-{SUFFIX}&comment=verify-config&operator=apollo")
    assert st == 200, f"发布失败: {st} {resp}"
    time.sleep(3)  # 长轮询推送窗口


def apollo_delete_keys(keys):
    by_key = apollo_item_ids()
    for k in keys:
        if k in by_key:
            # adminservice 删除映射为短路径 /items/{itemId}（非全路径）
            apollo_req("DELETE", f"/items/{by_key[k]}?operator=apollo")


def wait_propagation():
    """local 文件模式下各域独立 30s 定时 reload（控制台 refresh 只刷新网关自身视图），
    等待一个完整周期保证 payment/risk 等消费域均已加载新值。Apollo 长轮询 ~1s 推送无需此等待。"""
    if PROVIDER == "local-file":
        time.sleep(31)


def set_overrides(items, propagate=True):
    if PROVIDER == "apollo":
        apollo_publish(items)
        refresh()
    else:
        write_local("".join(f"{k}={v}\n" for k, v in items.items()))
        refresh()
        if propagate:
            wait_propagation()


def restore_source(propagate=True):
    if PROVIDER == "apollo":
        # 清理测试用密钥类 item（脱敏断言遗留），再发布默认值
        apollo_delete_keys(("payment.notify.secret-token", "spring.datasource.password"))
        apollo_publish(DEFAULTS)
        refresh()
    else:
        if ORIGINAL is None:
            if os.path.exists(CONFIG_FILE):
                os.remove(CONFIG_FILE)
        else:
            write_local(ORIGINAL.decode())
        refresh()
        if propagate:
            wait_propagation()


def pay(amount, payer, merchant, tag, bps_expect=None, expect_fee=None):
    order = ok("POST", "/api/orders", {"merchantId": merchant["id"], "subject": f"CFGTEST-{tag}", "amount": amount})["order"]
    r = ok("POST", "/api/cashier/pay", {"orderId": order["id"], "method": "BALANCE",
                                        "accountId": payer["id"], "requestId": f"cfg-{tag}-{SUFFIX}"})
    p = poll(lambda: ok("GET", f"/api/payments/{r['payment']['id']}"), lambda x: x["status"] in ("SUCCESS", "FAILED"))
    return order, p


def main():
    global PROVIDER, ORIGINAL
    print(f"== verify-config（批次 {SUFFIX}）==")
    if os.path.exists(CONFIG_FILE):
        with open(CONFIG_FILE, "rb") as f:
            ORIGINAL = f.read()

    print("[1] 控制台认证闸门")
    st, resp = req("GET", "/api/config")
    check("GET /api/config 无 token 401+A0401", st == 401 and resp.get("code") == "A0401", f"{st} {resp}")
    st, resp = req("POST", "/api/config/refresh", {})
    check("POST /api/config/refresh 无 token 401", st == 401, str(st))
    view = config_view()
    PROVIDER = view.get("provider")
    check("GET /api/config admin 200 且 provider 合法", PROVIDER in ("local-file", "apollo"), str(view)[:200])
    print(f"    provider={PROVIDER}")

    print("[2] 默认值与改造前逐一相同（口径红线）")
    check("effective 四项 == 冻结默认值", view.get("effective") == DEFAULTS, str(view.get("effective")))
    check("defaults 表 == 冻结默认值", view.get("defaults") == DEFAULTS, str(view.get("defaults")))
    stats = ok("GET", "/api/stats")
    check("/api/stats feeBps == 60", stats.get("feeBps") == 60, str(stats.get("feeBps")))
    merchant = ok("POST", "/api/members", {"name": f"CFGTEST商户{SUFFIX}", "type": "MERCHANT"})
    ok("POST", "/api/accounts", {"memberId": merchant["id"]})
    payers = {}
    for tag in ("A", "B", "C"):
        m = ok("POST", "/api/members", {"name": f"CFGTEST付款{tag}{SUFFIX}", "type": "USER"})
        a = ok("POST", "/api/accounts", {"memberId": m["id"]})
        ok("POST", "/api/accounting/internal/topup", {"accountId": a["id"], "amount": 100000, "bizNo": f"cfg-topup-{tag}-{SUFFIX}"})
        payers[tag] = a
    order_old, p_old = pay(3000, payers["A"], merchant, "old")
    check("默认费率新单 SUCCESS 且 fee=18（3000@60bps）", p_old["status"] == "SUCCESS" and p_old["fee"] == 18, str(p_old)[:200])
    check("默认费率账务 SYS_FEE 贷腿=18", transfer_fee_line(p_old["id"], "C") == 18)
    sr = poll(lambda: next((x for x in ok("GET", f"/api/settle/records?merchantId={merchant['id']}")
                            if x.get("paymentId") == p_old["id"] and x["type"] == "PAY"), None),
              lambda x: x is not None)
    check("默认费率结算单 fee=18 net=2982", sr is not None and sr["fee"] == 18 and sr["net"] == 2982, str(sr))

    print("[3] 费率热更（改配置不重启生效）")
    if PROVIDER == "local-file":
        # 3a. 30s 定时自动 reload（不调 refresh）
        write_local("payment.fee-bps=100\n")
        v = poll(config_view, lambda x: x.get("effective", {}).get("payment.fee-bps") == 100, timeout=45, interval=3)
        check("local 文件 30s 自动 reload 生效 fee-bps=100", v.get("effective", {}).get("payment.fee-bps") == 100, str(v.get("effective")))
        wait_propagation()  # payment 域独立 reload 周期，确保新费率受理生效
    else:
        set_overrides({"payment.fee-bps": 100})
        v = config_view()
        check("apollo 发布后 fee-bps=100 生效", v.get("effective", {}).get("payment.fee-bps") == 100, str(v.get("effective")))
    r = refresh()
    check("POST /api/config/refresh admin 200", isinstance(r, dict) and "provider" in r, str(r))
    v = config_view()
    check("overrides 含 payment.fee-bps=100", str(v.get("overrides", {}).get("payment.fee-bps")) == "100", str(v.get("overrides")))
    stats = ok("GET", "/api/stats")
    check("/api/stats feeBps 热更=100", stats.get("feeBps") == 100, str(stats.get("feeBps")))
    order_new, p_new = pay(3000, payers["A"], merchant, "new")
    check("新费率新单 fee=30（3000@100bps）", p_new["status"] == "SUCCESS" and p_new["fee"] == 30, str(p_new)[:200])
    check("新费率账务 SYS_FEE 贷腿=30", transfer_fee_line(p_new["id"], "C") == 30)

    print("[4] 已受理单快照（费率变更不影响在途/已受理单）")
    # 旧单（受理时 60bps，fee=18）在 100bps 生效期间部分退 1000 → 手续费按快照 18 比例退 6
    r = ok("POST", f"/api/payments/{p_old['id']}/refund", {"amount": 1000, "requestId": f"cfg-rf-old1-{SUFFIX}"})
    p = poll(lambda: ok("GET", f"/api/payments/{p_old['id']}"), lambda x: x["refundedAmount"] == 1000)
    check("旧单配置变更后部分退 1000 成功", r["refundAmount"] == 1000 and p["refundedAmount"] == 1000, str(p)[:200])
    check("旧单退款手续费按受理快照 18 比例退 6（非当前 30）", p["refundedFee"] == 6, str(p.get("refundedFee")))
    check("旧单退款单据 SYS_FEE 借腿=6", transfer_fee_line(r["refundId"], "D") == 6)
    # 恢复默认费率后，新单（受理时 100bps，fee=30）全额退 → 仍按快照 30 冲回
    restore_source(propagate=False)  # 退款读快照不读配置，无需等跨域传播
    v = config_view()
    check("恢复后 effective == 默认值", v.get("effective") == DEFAULTS, str(v.get("effective")))
    r = ok("POST", f"/api/payments/{p_new['id']}/refund", {"amount": 3000, "requestId": f"cfg-rf-new-{SUFFIX}"})
    p = poll(lambda: ok("GET", f"/api/payments/{p_new['id']}"), lambda x: x["status"] == "REFUNDED")
    check("新单恢复默认后全额退 REFUNDED", p["status"] == "REFUNDED" and r["refundAmount"] == 3000, str(p)[:200])
    check("新单 fee 快照仍=30 且退款手续费冲回 30（非当前 18）", p["fee"] == 30 and p["refundedFee"] == 30, str(p)[:200])
    check("新单退款单据 SYS_FEE 借腿=30", transfer_fee_line(r["refundId"], "D") == 30)
    check("新单原账务 SYS_FEE 贷腿仍=30（二阶段用受理快照）", transfer_fee_line(p_new["id"], "C") == 30)
    r = ok("POST", f"/api/payments/{p_old['id']}/refund", {"amount": 2000, "requestId": f"cfg-rf-old2-{SUFFIX}"})
    p = poll(lambda: ok("GET", f"/api/payments/{p_old['id']}"), lambda x: x["status"] == "REFUNDED")
    check("旧单剩余 2000 退尽 REFUNDED 且累计退手续费=18（快照全额冲回）",
          p["status"] == "REFUNDED" and p["refundedFee"] == 18, str(p)[:200])

    print("[5] 风控限额/窗口热更")
    # 窗口放宽到 60s 排除脚本节奏干扰，同时验证 window-millis 键热更
    set_overrides({"payment.risk.window-millis": 60000, "payment.risk.window-max": 2})
    for i in range(2):
        _, p = pay(1, payers["B"], merchant, f"freq{i}")
        check(f"window-max=2 第{i + 1}笔放行", p["status"] == "SUCCESS", str(p)[:150])
    order = ok("POST", "/api/orders", {"merchantId": merchant["id"], "subject": "CFGTEST-freq2", "amount": 1})["order"]
    st, resp = req("POST", "/api/cashier/pay", {"orderId": order["id"], "method": "BALANCE",
                                                "accountId": payers["B"]["id"], "requestId": f"cfg-freq2-{SUFFIX}"}, token=ADMIN)
    check("window-max=2 第 3 笔拒「频繁」", st == 400 and "频繁" in resp.get("error", ""), f"{st} {resp}")
    req("POST", f"/api/orders/{order['id']}/close", {}, token=ADMIN)
    set_overrides({"payment.risk.single-limit": 1000})
    v = config_view()
    check("single-limit=1000 生效", v.get("effective", {}).get("payment.risk.single-limit") == 1000, str(v.get("effective")))
    order = ok("POST", "/api/orders", {"merchantId": merchant["id"], "subject": "CFGTEST-limit", "amount": 2000})["order"]
    st, resp = req("POST", "/api/cashier/pay", {"orderId": order["id"], "method": "BALANCE",
                                                "accountId": payers["B"]["id"], "requestId": f"cfg-limit-{SUFFIX}"}, token=ADMIN)
    check("single-limit=1000 拒 2000 且文案按配置（10 元）",
          st == 400 and "风控拒绝" in resp.get("error", "") and "超出单笔限额 10 元" in resp.get("error", ""), f"{st} {resp}")
    req("POST", f"/api/orders/{order['id']}/close", {}, token=ADMIN)
    restore_source()
    _, p = pay(1, payers["B"], merchant, "after-restore")
    check("风控恢复默认后放行", p["status"] == "SUCCESS", str(p)[:150])

    print("[6] 控制台脱敏（密钥类键明文不出网关）")
    secret_val = f"CFGTEST-PLAINTEXT-{SUFFIX}"
    set_overrides({"payment.fee-bps": 60, "payment.notify.secret-token": secret_val, "spring.datasource.password": secret_val},
                  propagate=False)  # 仅网关视图断言，无需跨域传播
    st, resp = req("GET", "/api/config", token=ADMIN)
    raw_text = json.dumps(resp, ensure_ascii=False)
    ov = (resp or {}).get("overrides", {})
    check("secret-token 键脱敏 ******", ov.get("payment.notify.secret-token") == "******", str(ov))
    check("password 键脱敏 ******", ov.get("spring.datasource.password") == "******", str(ov))
    check("响应全文无密钥明文", secret_val not in raw_text, raw_text[:200])
    check("非敏感键原值可见", str(ov.get("payment.fee-bps")) == "60", str(ov))
    restore_source(propagate=False)

    print("[7] refresh RBAC（READONLY 403 / ADMIN 通配 200）")
    ro_name = f"cfgtest_ro_{SUFFIX}"
    ro_pwd = "Passw0rd-" + SUFFIX
    st, resp = req("POST", "/api/console/users", {"username": ro_name, "password": ro_pwd, "roles": ["READONLY"]}, token=ADMIN)
    check("创建 READONLY 用户 200", st == 200, f"{st} {resp}")
    st, resp = req("POST", "/api/auth/login", {"username": ro_name, "password": ro_pwd})
    ro_token = resp.get("token") if st == 200 else None
    check("READONLY 登录 200", st == 200 and bool(ro_token), f"{st} {resp}")
    st, _ = req("GET", "/api/config", token=ro_token)
    check("READONLY GET /api/config 200（登录即可读）", st == 200, str(st))
    st, resp = req("POST", "/api/config/refresh", {}, token=ro_token)
    check("READONLY POST refresh 403+A0403（config:write/* 缺失）", st == 403 and resp.get("code") == "A0403", f"{st} {resp}")
    st, resp = req("POST", "/api/config/refresh", {}, token=ADMIN)
    check("ADMIN 通配 * refresh 200", st == 200, f"{st} {resp}")

    print("[8] 降级红线：配置源不可达 → 支付仍成功")
    if PROVIDER == "apollo":
        names = subprocess.run([DOCKER, "ps", "--format", "{{.Names}}"], capture_output=True, text=True).stdout
        if "pp-apollo-configservice" in names:
            subprocess.run([DOCKER, "stop", "pp-apollo-configservice"], capture_output=True, text=True)
            try:
                time.sleep(3)
                _, p = pay(3000, payers["C"], merchant, "apollo-down")
                check("Apollo configservice 停掉后支付仍 SUCCESS（last-known-good/默认值）",
                      p["status"] == "SUCCESS" and p["fee"] in (18, 30), str(p)[:200])
                st, _ = req("GET", "/api/config", token=ADMIN)
                check("Apollo 停掉后 GET /api/config 仍 200（不阻塞）", st == 200, str(st))
            finally:
                subprocess.run([DOCKER, "start", "pp-apollo-configservice"], capture_output=True, text=True)
            names = subprocess.run([DOCKER, "ps", "--format", "{{.Names}}"], capture_output=True, text=True).stdout
            check("Apollo configservice 已恢复启动", "pp-apollo-configservice" in names)
        else:
            _, p = pay(3000, payers["C"], merchant, "apollo-absent")
            check("Apollo 未部署/不可达态支付仍 SUCCESS（默认值兜底）", p["status"] == "SUCCESS" and p["fee"] == 18, str(p)[:200])
    else:
        if os.path.exists(CONFIG_FILE):
            os.remove(CONFIG_FILE)
        r = refresh()
        v = config_view()
        check("local 文件缺失 refresh 200 不报错", isinstance(r, dict), str(r))
        check("文件缺失 fileExists=false", v.get("fileExists") is False, str(v)[:200])
        check("文件缺失 effective 回退默认值", v.get("effective") == DEFAULTS, str(v.get("effective")))
        wait_propagation()  # payment 域回到默认值需一个 reload 周期
        _, p = pay(3000, payers["C"], merchant, "file-missing")
        check("文件缺失支付仍 SUCCESS 且 fee=18（默认值兜底，不阻断）", p["status"] == "SUCCESS" and p["fee"] == 18, str(p)[:200])

    print("[9] 恢复与口径回归")
    restore_source()
    v = config_view()
    check("恢复后 effective == 默认值", v.get("effective") == DEFAULTS, str(v.get("effective")))
    stats = ok("GET", "/api/stats")
    check("恢复后 /api/stats feeBps == 60", stats.get("feeBps") == 60, str(stats.get("feeBps")))
    _, p = pay(3000, payers["C"], merchant, "final")
    check("恢复后新单 fee=18（smoke 口径回归）", p["status"] == "SUCCESS" and p["fee"] == 18, str(p)[:200])
    if PROVIDER == "local-file":
        if ORIGINAL is None:
            check("原文件不存在 → 清理后仍不存在", not os.path.exists(CONFIG_FILE))
        else:
            with open(CONFIG_FILE, "rb") as f:
                check("原文件字节级还原", f.read() == ORIGINAL)


try:
    main()
finally:
    # 无论成败都还原配置源，避免污染后续 smoke/其它泳道
    try:
        if PROVIDER:
            restore_source()
    except Exception as e:
        print(f"!! 配置源还原失败（需人工检查 {CONFIG_FILE}）: {e}")

print(f"\n== verify-config 结果: {passed} 通过 / {failed} 失败 ==")
sys.exit(1 if failed else 0)