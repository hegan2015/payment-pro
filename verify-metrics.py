#!/usr/bin/env python3
# payment-pro I-8 可观测验证：全 13 域 actuator health/prometheus/metrics + 业务指标名断言
# 用法：./run.sh 启动后执行 python3 verify-metrics.py
#       docker 模式：PP_PROFILE=docker python3 verify-metrics.py（跳过内嵌 registry，其由 Docker ZK 替代）
# 只读脚本：不发业务流量、不停启服务；业务指标为启动预注册 0 值基线，无需先跑 smoke。
import json
import os
import re
import urllib.error
import urllib.request

PROFILE = os.environ.get("PP_PROFILE", "local")
PASSED = 0
FAILED = 0

# 域 → (端口环境变量, 默认 actuator 端口, 是否依赖 payment-common 含业务指标)
# I-8：原 web-application-type=none 的域已改 servlet，内嵌 Tomcat 仅承载 actuator；
# 端口镜像 Dubbo 2088x → 1808x（registry 18092），gateway 与业务 HTTP 同端口 8080。
DOMAINS = [
    ("registry",   "REGISTRY_HTTP_PORT",   18092, False),
    ("member",     "MEMBER_HTTP_PORT",     18081, True),
    ("account",    "ACCOUNT_HTTP_PORT",    18082, True),
    ("accounting", "ACCOUNTING_HTTP_PORT", 18083, True),
    ("trade",      "TRADE_HTTP_PORT",      18084, True),
    ("payment",    "PAYMENT_HTTP_PORT",    18085, True),
    ("cashier",    "CASHIER_HTTP_PORT",    18086, True),
    ("channel",    "CHANNEL_HTTP_PORT",    18087, True),
    ("risk",       "RISK_HTTP_PORT",       18088, True),
    ("settle",     "SETTLE_HTTP_PORT",     18089, True),
    ("notify",     "NOTIFY_HTTP_PORT",     18090, True),
    ("recon",      "RECON_HTTP_PORT",      18091, True),
    ("gateway",    "GATEWAY_PORT",         8080,  True),
]

# 重点域必含业务指标名（命名规范 payment_<domain>_<event>_total；tcc_dead_backlog 为 gauge 暴露 DEAD 积压）
REQUIRED_METRICS = {
    "gateway": ["payment_gateway_pay_success_total"],
    "payment": [
        "payment_payment_pay_success_total",
        "payment_payment_pay_fail_total",
        "payment_payment_refund_total",
        "payment_payment_tcc_dead_total",
        "payment_app_tcc_dead_backlog",
        "payment_payment_outbox_dead_total",
    ],
    "accounting": ["payment_accounting_pay_success_total"],
    "recon": ["payment_recon_dispute_total", "payment_recon_run_total"],
    "notify": ["payment_notify_dead_total", "payment_notify_delivered_total"],
}


def check(name, cond, detail=""):
    global PASSED, FAILED
    if cond:
        PASSED += 1
        print(f"  ✓ {name}")
    else:
        FAILED += 1
        print(f"  ✗ {name}  {detail}")


def get(url):
    try:
        with urllib.request.urlopen(url, timeout=8) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except Exception as e:
        return 0, str(e)


def has_series(text, name):
    """精确匹配指标序列名（行首 name{labels} 或 name value），避免 _total 子串误匹配。"""
    return re.search(r"^" + re.escape(name) + r"[{ ]", text, re.M) is not None


def verify_domain(domain, env_key, default_port, has_common):
    port = int(os.environ.get(env_key, default_port))
    base = f"http://127.0.0.1:{port}"
    print(f"\n[{domain}] {base}")

    st, body = get(base + "/actuator/health")
    try:
        health = json.loads(body)
    except Exception:
        health = {}
    check(f"{domain} health=UP", st == 200 and health.get("status") == "UP", f"st={st} body={body[:120]}")
    check(f"{domain} health show-details=always", "components" in health, body[:120])

    st, prom = get(base + "/actuator/prometheus")
    check(f"{domain} prometheus 200", st == 200, f"st={st}")
    check(f"{domain} 含 jvm_ 指标", "jvm_" in prom)
    check(f"{domain} 含 process_ 指标", "process_" in prom)

    st, body = get(base + "/actuator/metrics")
    try:
        names = json.loads(body).get("names", [])
    except Exception:
        names = []
    check(f"{domain} metrics 端点可用", st == 200 and len(names) > 0, f"st={st}")

    if has_common:
        check(f"{domain} 含 payment_ 业务指标", f"payment_{domain}_" in prom)
    for metric in REQUIRED_METRICS.get(domain, []):
        check(f"{domain} 含 {metric}", has_series(prom, metric))


def main():
    print(f"payment-pro I-8 可观测验证（profile={PROFILE}）")
    for domain, env_key, port, has_common in DOMAINS:
        if domain == "registry" and PROFILE == "docker":
            print("\n[registry] docker 模式由 Docker ZK 替代内嵌注册中心，跳过")
            continue
        verify_domain(domain, env_key, port, has_common)
    print(f"\n结果：{PASSED} 通过，{FAILED} 失败")
    raise SystemExit(1 if FAILED else 0)


if __name__ == "__main__":
    main()