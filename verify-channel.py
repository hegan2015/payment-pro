#!/usr/bin/env python3
"""verify-channel.py — P1 A 泳道「渠道与路由工作流」五卡验证（CH-2a/CH-2b/CH-3/CH-4/CH-5）。

跑法: python3 verify-channel.py        （payment-pro 栈需已启动；默认 docker profile，local 亦可）
      PP_PROFILE=local / PP_GATEWAY / PP_CHANNEL_OPS 可覆盖端点。

覆盖面（对照任务卡验收）:
  A CH-2a SPI 抽象与可插拔：4 个适配器实现注册、按 channelCode 路由、模拟银行重构后拒绝文案不变、
    空适配器（ECHO）可插拔、渠道侧查询 SPI 只读。
  B CH-2b 沙箱适配器 + 故障注入四种（延迟/拒绝/回执丢失/金额不符）+ 宕机/超时 + HMAC 验签。
  C CH-3 路由打分择优（成功率/成本/权重）+ 维护绕行 + 熔断切换 + 适配器级故障切换 +
    无可用渠道/指定渠道不可用一律受理前拒绝（**不产生悬挂单**：无 channel_trade 行）。
  D CH-4 对账文件下载（沙箱/mock 生成）+ CSV 解析 + 汇总完整性 + 逐笔比对 +
    四类差异（金额不符/渠道多单/账单缺行/解析失败）经 DisputeFacade 建差错单（source=PAYMENT，策略 MANUAL）。
  E CH-5 单笔/日限额受理前拒绝 + 日累计跨日重置 + 维护窗口绕行 + DynamicConfig 动态下发（改文件即生效）。
  F 同步默认路径不回归：真实银行卡支付 SUCCESS/0000 拒绝 FAILED/BALANCE 支付同步语义不变。
  G 异步 WAITING 全链路（仅 PAYMENT_CHANNEL_ASYNC=true 的栈跑，否则整段 SKIP 并说明）：
    沙箱受理 PROCESSING → 回执 → SUCCESS；回执丢失 → 查询补单收敛；拒绝 → TCC 回滚；
    金额不符 → 不过账 + 差错单；沙箱宕机 → 故障切换备选渠道，一单一行无悬挂。

数据保护：只新增本脚本自有测试数据（CHT 前缀业务号 / 沙箱渠道侧账本 / 渠道域新表），
绝不删除或改写既有支付/账务/对账行；退出时把渠道配置、故障注入、动态配置文件字节级还原。
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

BASE = os.environ.get("PP_GATEWAY", "http://127.0.0.1:8080")
OPS = os.environ.get("PP_CHANNEL_OPS", "http://127.0.0.1:18087")
ADMIN_PASS = os.environ.get("PP_ADMIN_PASSWORD", "admin123")
CONFIG_FILE = os.path.expanduser("~/.config/payment-pro/config.properties")
SUFFIX = uuid.uuid4().hex[:8]
P = "CHT" + SUFFIX
TZ = timezone(timedelta(hours=8))
TODAY = datetime.now(TZ).strftime("%Y%m%d")
YESTERDAY = (datetime.now(TZ) - timedelta(days=1)).strftime("%Y%m%d")
TOKEN = None
PASSED = FAILED = SKIPPED = 0
CARD = "6222020202020202"
DECLINE_CARD = "6222020202020000"
CONFIG_BACKUP = None
TOUCHED = {}


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


def _call(method, url, body=None, headers=None, timeout=40):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            text = resp.read().decode()
            try:
                return resp.status, (json.loads(text) if text.strip() else None)
            except Exception:
                return resp.status, text
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        try:
            return e.code, json.loads(text)
        except Exception:
            return e.code, text
    except Exception as e:
        return 0, str(e)


STEP_TOKEN = None
STEP_EXPIRES = 0.0


def _step_token():
    """渠道运营写操作（限额/维护窗/账单比对）已纳入二次认证闸门；再验会话用户自己的口令。"""
    global STEP_TOKEN, STEP_EXPIRES
    if STEP_TOKEN and time.time() < STEP_EXPIRES:
        return STEP_TOKEN
    st, resp = api("POST", "/api/security/step-up",
                   {"password": os.environ.get("PP_ADMIN_PASSWORD", "admin123")})
    if st == 200 and isinstance(resp, dict) and resp.get("token"):
        STEP_TOKEN = resp["token"]
        STEP_EXPIRES = time.time() + max(10, (resp.get("expiresIn") or 300) - 10)
        return STEP_TOKEN
    return None


def api(method, path, body=None, auth=True, step=False):
    headers = {}
    if auth and TOKEN and path.startswith("/api/") and path != "/api/auth/login":
        headers["Authorization"] = "Bearer " + TOKEN
    if step:
        t = _step_token()
        if t:
            headers["X-Step-Up-Token"] = t
    return _call(method, BASE + path, body, headers)


def ops(method, path, body=None):
    return _call(method, OPS + path, body)


def ok(method, path, body=None):
    st, resp = api(method, path, body)
    if st != 200:
        raise AssertionError(f"{method} {path} -> {st} {resp}")
    return resp


def poll(fn, pred, timeout=40, interval=0.5):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = fn()
        try:
            if pred(last):
                return last
        except Exception:
            pass
        time.sleep(interval)
    return last


def login():
    global TOKEN
    st, resp = api("POST", "/api/auth/login", {"username": "admin", "password": ADMIN_PASS}, auth=False)
    if st == 200 and isinstance(resp, dict) and resp.get("token"):
        TOKEN = resp["token"]
    return st


def snapshot_configs():
    st, resp = ops("GET", "/channel-ops/configs")
    if st == 200:
        for c in resp:
            TOUCHED[c["channelCode"]] = c


def restore_all():
    """还原渠道配置/故障注入/指标/动态配置文件（字节级），不留测试态影响其它泳道。"""
    ops("POST", "/channel-ops/faults/reset")
    for code, c in TOUCHED.items():
        ops("POST", f"/channel-ops/limit/{code}?single={c.get('singleLimit') or 0}&daily={c.get('dailyLimit') or 0}")
        ops("POST", f"/channel-ops/maintenance/{code}?start={c.get('maintenanceStart') or 0}&end={c.get('maintenanceEnd') or 0}")
        ops("POST", f"/channel-ops/status/{code}?status={c.get('status') or 'ENABLED'}")
        ops("POST", f"/channel-ops/score/{code}?weight={c.get('weight') or 0}&feeBps={c.get('feeBps') or 0}")
        ops("POST", f"/channel-ops/metrics/{code}/reset")
    if CONFIG_BACKUP is not None:
        try:
            if CONFIG_BACKUP == b"":
                if os.path.exists(CONFIG_FILE):
                    os.remove(CONFIG_FILE)
            else:
                with open(CONFIG_FILE, "wb") as f:
                    f.write(CONFIG_BACKUP)
            ops("POST", "/channel-ops/config/refresh")
        except Exception as e:
            print(f"  [WARN] 动态配置文件还原失败: {e}")


def backup_config():
    global CONFIG_BACKUP
    CONFIG_BACKUP = b""
    if os.path.exists(CONFIG_FILE):
        with open(CONFIG_FILE, "rb") as f:
            CONFIG_BACKUP = f.read()


def append_config(lines):
    with open(CONFIG_FILE, "ab") as f:
        f.write(("\n# verify-channel temporary\n" + "\n".join(lines) + "\n").encode())
    ops("POST", "/channel-ops/config/refresh")


def dispute(did):
    st, resp = api("GET", f"/api/disputes/{did}")
    return resp if st == 200 else None


def find_dispute(biz_no):
    st, resp = api("GET", f"/api/disputes?source=PAYMENT&bizNo={biz_no}&size=50")
    if st != 200:
        return None
    items = resp.get("items") or resp.get("records") or resp.get("list") or []
    for it in items:
        if it.get("bizNo") == biz_no:
            return it
    return None


def trade(biz_no):
    st, resp = api("GET", f"/api/channel/trades/{biz_no}")
    return resp if st == 200 else None


def accept(biz_no, amount=1000, channel=None, card=CARD, typ="PAY", orig=None):
    body = {"bizNo": biz_no, "amount": amount, "cardNo": card, "type": typ}
    if channel:
        body["channelCode"] = channel
    if orig:
        body["origBizNo"] = orig
    return ops("POST", "/channel-ops/trade/accept", body)


def capture(biz_no, typ="PAY", amount=1000):
    return ops("POST", "/channel-ops/trade/capture", {"bizNo": biz_no, "type": typ, "amount": amount})


def bal(account_id):
    return ok("GET", f"/api/accounting/accounts/{account_id}/balance")


def ensure_captured(biz_no, amount=1000, typ="PAY"):
    """同步 stack：PREAUTH → capture；异步 stack：等模拟银行 job 落终态后 capture 幂等 noop。"""
    t = trade(biz_no)
    if t and t.get("status") == "SUBMITTED":
        t = poll(lambda: trade(biz_no), lambda x: x and x.get("status") != "SUBMITTED", timeout=45)
    if t and t.get("status") == "PREAUTH":
        capture(biz_no, typ=typ, amount=amount)
    return trade(biz_no)


def wait_pay(pid, timeout=120, accelerate=True):
    """等支付单终态。accelerate=True 时周期性调用产品既有补单入口 POST /api/payments/{id}/reconverge
    （幂等、服务端兜底同一路径）以缩短等待；accelerate=False 则纯靠 @Scheduled 补单 job 自动收敛
    （payment.reconcile.timeout-ms 默认 60s，故 timeout 需 > 60s）。"""
    deadline = time.time() + timeout
    last, n = None, 0
    while time.time() < deadline:
        st, p = api("GET", f"/api/payments/{pid}")
        if st == 200 and isinstance(p, dict):
            last = p
            if p.get("status") in ("SUCCESS", "FAILED", "REFUNDED"):
                return p
        if accelerate and n % 5 == 4:
            api("POST", f"/api/payments/{pid}/reconverge")
        n += 1
        time.sleep(1)
    return last


def route(amount=2000, channel=None, card=CARD, typ=None):
    q = f"?amount={amount}"
    if channel:
        q += f"&channelCode={channel}"
    if card:
        q += f"&cardNo={card}"
    if typ:
        q += f"&type={typ}"
    return ok("GET", "/api/channel/route" + q)


def candidate(result, code):
    for c in result.get("candidates", []):
        if c.get("channelCode") == code:
            return c
    return {}


def download(code, day=TODAY):
    return api("POST", "/api/channel/bills/download", {"channelCode": code, "bizDate": day})


# ============================== A. CH-2a SPI ==============================

def section_a():
    print("[A] CH-2a 渠道 SPI 抽象 + 模拟银行重构（可插拔 / channelCode 路由 / 拒绝文案不变）")
    adapters = ok("GET", "/api/channel/adapters")
    codes = {a["channelCode"]: a for a in adapters}
    check("SPI 注册表含 4 个适配器实现", len(adapters) >= 4 and
          {"MOCK_BANK", "MOCK_BANK_B", "SANDBOX_BANK", "ECHO_BANK"} <= set(codes), str(list(codes)))
    check("模拟银行重构为 SPI 实现 MockBankAdapter",
          codes.get("MOCK_BANK", {}).get("adapterClass") == "MockBankAdapter", str(codes.get("MOCK_BANK")))
    check("备用渠道同 SPI 不同 channelCode（可插拔）",
          codes.get("MOCK_BANK_B", {}).get("adapterClass") == "MockBankAdapter")
    check("沙箱适配器 SandboxBankAdapter 已注册且 env=SANDBOX",
          codes.get("SANDBOX_BANK", {}).get("adapterClass") == "SandboxBankAdapter"
          and codes.get("SANDBOX_BANK", {}).get("env") == "SANDBOX", str(codes.get("SANDBOX_BANK")))
    check("空适配器 EchoChannelAdapter 可插拔注册（出厂 DISABLED 不接流量）",
          codes.get("ECHO_BANK", {}).get("adapterClass") == "EchoChannelAdapter"
          and codes.get("ECHO_BANK", {}).get("configStatus") == "DISABLED", str(codes.get("ECHO_BANK")))

    st, r = ops("POST", "/channel-ops/spi/pay", {"channelCode": "MOCK_BANK", "bizNo": P + "A1", "amount": 2000, "cardNo": CARD})
    check("SPI 直探模拟银行受理成功", st == 200 and r.get("status") in ("SUCCESS", "PROCESSING"), str(r))
    st, r = ops("POST", "/channel-ops/spi/pay", {"channelCode": "MOCK_BANK", "bizNo": P + "A2", "amount": 2000, "cardNo": DECLINE_CARD})
    check("尾号 0000 拒绝文案与重构前逐字一致",
          st == 200 and r.get("status") == "FAILED"
          and r.get("reason") == "渠道拒绝: 银行风控拦截 (卡号尾号 0000)", str(r))
    check("业务拒绝不允许故障切换（failoverAllowed=false）", r.get("failoverAllowed") is False, str(r))
    st, r = ops("POST", "/channel-ops/spi/pay", {"channelCode": "ECHO_BANK", "bizNo": P + "A3", "amount": 100, "cardNo": CARD})
    check("空适配器经 SPI 受理成功（新增渠道零改动接入）",
          st == 200 and r.get("status") == "SUCCESS" and str(r.get("channelOrderNo", "")).startswith("ECHO-"), str(r))
    st, r = ops("POST", "/channel-ops/spi/pay", {"channelCode": "NO_SUCH", "bizNo": P + "A4", "amount": 100, "cardNo": CARD})
    check("未注册渠道明确拒绝（不静默降级）", st == 400, str(r))

    st, r = accept(P + "A5", 1500, channel="MOCK_BANK_B")
    check("channelCode 路由正确：指定备用渠道落痕 MOCK_BANK_B",
          st == 200 and r.get("channelCode") == "MOCK_BANK_B", str(r))
    preview = route(1500)
    st, r = accept(P + "A6", 1500)
    check("缺省受理落痕与路由预览决策一致（预览即受理口径）",
          st == 200 and r.get("channelCode") == preview.get("channelCode"), str(r))
    check("默认路由选中同步模拟银行主渠道 MOCK_BANK（默认行为不变）",
          r.get("channelCode") == "MOCK_BANK", str(r.get("channelCode")))
    st, r = api("GET", f"/api/channel/trades/{P}A5/side")
    check("渠道侧查询 SPI（只读视图）可用", st == 200 and r.get("channelCode") == "MOCK_BANK_B", str(r))
    st, r = ops("GET", f"/channel-ops/spi/query?channelCode=MOCK_BANK&bizNo={P}A6")
    check("SPI query 返回渠道侧状态（PREAUTH/SUBMITTED/CAPTURED）",
          st == 200 and r.get("channelStatus") in ("PREAUTH", "SUBMITTED", "CAPTURED"), str(r))


# ============================== C. CH-3 路由 ==============================

def section_c(async_on):
    print("[C] CH-3 路由择优 + 故障切换（硬过滤 / 打分 / 绕行 / 不产生悬挂单）")
    r = route(2000)
    elig = [c for c in r["candidates"] if c.get("eligible")]
    check("路由决策可观测（选中渠道 + 评分 + 原因）",
          r.get("channelCode") and r.get("reason") and elig and elig[0].get("score") is not None, str(r)[:300])
    best = max(elig, key=lambda c: c.get("score") or 0)
    check("择优命中评分最高候选", r["channelCode"] == best["channelCode"], f'{r["channelCode"]} vs {best["channelCode"]}')
    check("默认选中模拟银行主渠道（同步路径不变）",
          async_on or r["channelCode"] == "MOCK_BANK", str(r["channelCode"]))
    if not async_on:
        sb = candidate(r, "SANDBOX_BANK")
        check("同步 stack 硬过滤异步沙箱渠道（杜绝悬挂单）",
              not sb.get("eligible") and "PAYMENT_CHANNEL_ASYNC" in (sb.get("reason") or ""), str(sb))
    echo = candidate(r, "ECHO_BANK")
    check("停用渠道被硬过滤", not echo.get("eligible") and "DISABLED" in (echo.get("reason") or ""), str(echo))

    # 成本/权重择优：把主渠道成本抬高、权重压低 → 备选胜出
    ops("POST", "/channel-ops/score/MOCK_BANK?weight=0&feeBps=9000")
    r2 = route(2000)
    check("成本/权重择优生效（主渠道成本抬高后备选胜出）",
          r2["channelCode"] != "MOCK_BANK" and candidate(r2, "MOCK_BANK").get("eligible"), str(r2)[:300])
    ops("POST", "/channel-ops/score/MOCK_BANK?weight=100&feeBps=60")

    # 维护窗口绕行 + 受理落到备选
    now = int(time.time() * 1000)
    ops("POST", f"/channel-ops/maintenance/MOCK_BANK?start={now - 1000}&end={now + 120000}")
    r3 = route(2000)
    mb = candidate(r3, "MOCK_BANK")
    check("维护窗口内路由排除该渠道", not mb.get("eligible") and "维护" in (mb.get("reason") or ""), str(mb))
    st, t = accept(P + "C1", 1200)
    check("维护期受理绕行到备选渠道（成功且不落主渠道）",
          st == 200 and t.get("channelCode") not in (None, "MOCK_BANK"), str(t))
    # 指定渠道不可用 → 拒绝，不静默改道
    st, t = accept(P + "C2", 1200, channel="MOCK_BANK")
    check("指定渠道维护中 → 受理前拒绝（不静默改道）", st == 400, str(t))
    check("拒绝后无渠道流水（不产生悬挂单）", trade(P + "C2") is None, str(trade(P + "C2")))
    ops("POST", "/channel-ops/maintenance/MOCK_BANK?start=0&end=0")

    # 熔断切换
    ops("POST", "/channel-ops/metrics/MOCK_BANK/breaker?ms=30000")
    r4 = route(2000)
    check("渠道熔断 → 路由自动绕行备选",
          r4["channelCode"] != "MOCK_BANK" and candidate(r4, "MOCK_BANK").get("breakerOpen"), str(r4)[:300])
    st, t = accept(P + "C3", 1300)
    check("熔断期受理落备选渠道", st == 200 and t.get("channelCode") != "MOCK_BANK", str(t))
    ops("POST", "/channel-ops/metrics/MOCK_BANK/breaker?ms=0")

    # 无可用渠道 → 拒绝受理（不留悬挂单）
    ops("POST", f"/channel-ops/maintenance/MOCK_BANK?start={now - 1000}&end={now + 120000}")
    ops("POST", f"/channel-ops/maintenance/MOCK_BANK_B?start={now - 1000}&end={now + 120000}")
    ops("POST", "/channel-ops/status/SANDBOX_BANK?status=DISABLED")
    r5 = route(2000)
    check("全部渠道不可用 → 决策为空 + 原因", r5.get("channelCode") is None and "无可用渠道" in r5.get("reason", ""), str(r5)[:300])
    st, t = accept(P + "C4", 1400)
    check("无可用渠道 → 受理前拒绝 400", st == 400 and "渠道路由拒绝" in str(t.get("error", "")), str(t))
    check("无可用渠道拒绝后无渠道流水（不产生悬挂单）", trade(P + "C4") is None, str(trade(P + "C4")))
    ops("POST", "/channel-ops/maintenance/MOCK_BANK?start=0&end=0")
    ops("POST", "/channel-ops/maintenance/MOCK_BANK_B?start=0&end=0")
    ops("POST", "/channel-ops/status/SANDBOX_BANK?status=ENABLED")

    # 业务拒绝不触发切换（0000 卡）：一单不留行
    st, t = accept(P + "C5", 1500, card=DECLINE_CARD)
    check("银行拒绝不触发故障切换（原样拒绝）",
          st == 400 and "渠道拒绝: 银行风控拦截 (卡号尾号 0000)" == t.get("error"), str(t))
    check("银行拒绝后无渠道流水（TCC Cancel 空回滚）", trade(P + "C5") is None, str(trade(P + "C5")))

    # 退款沿用原渠道（不受维护/限额阻断）
    ensure_captured(P + "A6", amount=1500)
    ensure_captured(P + "A5", amount=1500)
    ops("POST", f"/channel-ops/maintenance/MOCK_BANK?start={now - 1000}&end={now + 120000}")
    orig_channel = (trade(P + "A6") or {}).get("channelCode")
    st, t = accept(P + "C6", 1500, typ="REFUND", orig=P + "A6")
    check("退款沿用原扣款渠道且不受维护窗口阻断",
          st == 200 and t.get("channelCode") == orig_channel and t.get("type") == "REFUND", str(t))
    ops("POST", "/channel-ops/maintenance/MOCK_BANK?start=0&end=0")
    st, t = accept(P + "C7", 1500, typ="REFUND", orig=P + "A5")
    check("退款路由到原单渠道 MOCK_BANK_B（不择优改道）",
          st == 200 and t.get("channelCode") == "MOCK_BANK_B", str(t))


# ============================== E. CH-5 限额/维护 ==============================

def section_e():
    print("[E] CH-5 渠道限额 / 维护窗口（受理前拒绝 + 日累计 + 动态下发）")
    base = ok("GET", "/api/channel/MOCK_BANK_B/limit")
    base_used, base_count = base.get("usedAmount") or 0, base.get("usedCount") or 0
    ops("POST", "/channel-ops/limit/MOCK_BANK_B?single=1000&daily=0")
    v = ok("GET", "/api/channel/MOCK_BANK_B/limit")
    check("限额设置生效（单笔 1000 分）", v.get("singleLimit") == 1000, str(v))
    st, t = accept(P + "E1", 2000, channel="MOCK_BANK_B")
    check("超单笔限额 → 受理前拒绝 400", st == 400 and "渠道限额拒绝" in str(t.get("error", "")), str(t))
    check("超限拒绝未落渠道流水（受理前拒绝，无悬挂单）", trade(P + "E1") is None, str(trade(P + "E1")))
    st, t = accept(P + "E2", 1000, channel="MOCK_BANK_B")
    check("等于单笔限额边界放行", st == 200 and t.get("channelCode") == "MOCK_BANK_B", str(t))

    ops("POST", f"/channel-ops/limit/MOCK_BANK_B?single=0&daily={base_used + 1500}")
    st, t = accept(P + "E3", 1000, channel="MOCK_BANK_B")
    check("日累计超限 → 受理前拒绝（已用+本笔>日限额）",
          st == 400 and "日限额" in str(t.get("error", "")), str(t))
    check("日累计拒绝未落流水", trade(P + "E3") is None, str(trade(P + "E3")))
    v = ok("GET", "/api/channel/MOCK_BANK_B/limit")
    check("日累计金额/笔数正确（只累计成功受理）",
          v.get("usedAmount") == base_used + 1000 and v.get("usedCount") == base_count + 1
          and v.get("dailyRemaining") == 500, str(v))
    st, y = ops("GET", f"/channel-ops/usage/MOCK_BANK_B?bizDate={YESTERDAY}")
    check("跨日自然重置（昨日累计为 0，按 biz_date 分行）",
          st == 200 and y.get("usedAmount") == 0 and y.get("bizDate") == YESTERDAY, str(y))
    ops("POST", "/channel-ops/limit/MOCK_BANK_B?single=0&daily=0")

    # 维护窗口视图 + 路由排除
    now = int(time.time() * 1000)
    ops("POST", f"/channel-ops/maintenance/MOCK_BANK_B?start={now - 1000}&end={now + 60000}")
    v = ok("GET", "/api/channel/MOCK_BANK_B/limit")
    check("维护窗口视图 inMaintenance=true / available=false",
          v.get("inMaintenance") is True and v.get("available") is False, str(v))
    r = route(2000)
    check("维护窗口内路由排除（硬过滤）", not candidate(r, "MOCK_BANK_B").get("eligible"), str(r)[:200])
    ops("POST", "/channel-ops/maintenance/MOCK_BANK_B?start=0&end=0")

    # DynamicConfig 动态下发（改文件 → refresh → 立即生效，无需重启）
    backup_config()
    try:
        append_config([
            "channel.MOCK_BANK_B.single-limit=500",
            f"channel.MOCK_BANK_B.maintenance-start={now - 1000}",
            f"channel.MOCK_BANK_B.maintenance-end={now + 60000}",
        ])
        v = ok("GET", "/api/channel/MOCK_BANK_B/limit")
        check("DynamicConfig 动态下发限额生效（500 分覆盖 DB）",
              v.get("singleLimit") == 500 and v.get("limitSource") == "dynamic-config", str(v))
        check("DynamicConfig 动态下发维护窗口生效",
              v.get("inMaintenance") is True and v.get("maintenanceSource") == "dynamic-config", str(v))
        st, t = accept(P + "E4", 800, channel="MOCK_BANK_B")
        check("动态限额超限受理前拒绝", st == 400 and "渠道限额拒绝" in str(t.get("error", "")), str(t))
        r = route(800)
        check("动态维护窗口路由排除", not candidate(r, "MOCK_BANK_B").get("eligible"), str(r)[:200])
    finally:
        if CONFIG_BACKUP is not None:
            if CONFIG_BACKUP == b"":
                if os.path.exists(CONFIG_FILE):
                    os.remove(CONFIG_FILE)
            else:
                with open(CONFIG_FILE, "wb") as f:
                    f.write(CONFIG_BACKUP)
            ops("POST", "/channel-ops/config/refresh")
    v = ok("GET", "/api/channel/MOCK_BANK_B/limit")
    check("配置还原后回到 DB 生效值（动态覆盖可回滚）",
          v.get("singleLimit") is None and v.get("inMaintenance") is False, str(v))


# ============================== D. CH-4 对账文件 ==============================

def section_d():
    print("[D] CH-4 对账文件下载 / CSV 解析 / 逐笔比对 / 差异接差错平台")
    # 构造本脚本自有终态流水（3 笔 PAY + 1 笔 REFUND），不触碰既有业务数据
    rows = [(P + "D1", 1000), (P + "D2", 2000), (P + "D3", 3000)]
    for biz, amt in rows:
        st, _ = accept(biz, amt, channel="MOCK_BANK")
        assert st == 200, f"构造流水失败 {biz}"
        ensure_captured(biz, amount=amt)
    st, _ = accept(P + "D4", 1000, channel="MOCK_BANK", typ="REFUND", orig=P + "D1")
    assert st == 200
    ensure_captured(P + "D4", amount=1000, typ="REFUND")

    st, bill = download("MOCK_BANK")
    check("账单下载 + 解析入库（status=PARSED）", st == 200 and bill.get("status") == "PARSED", str(bill)[:300])
    check("解析笔数/金额与声明汇总一致（完整性校验）",
          bill.get("parsedCount") == bill.get("totalCount") and bill.get("parsedAmount") == bill.get("totalAmount"), str(bill)[:300])
    mine = [d for d in bill.get("details", []) if d.get("bizNo", "").startswith(P + "D")]
    check("自有 4 笔流水全部入明细（含 REFUND）", len(mine) == 4, str(len(mine)))
    check("逐笔比对全部一致（无差异）",
          all(d.get("matchResult") == "MATCH" for d in mine) and bill.get("diffCount") == 0, str(bill)[:300])
    path = bill.get("filePath")
    exists = bool(path) and os.path.exists(path)
    check("账单文件落盘 data/bills/<channel>/<bizDate>.csv", exists, str(path))
    if exists:
        with open(path, encoding="utf-8") as f:
            content = f.read()
        check("CSV 格式：表头 + 明细 + #TOTAL 汇总行",
              content.startswith("channel_order_no,biz_no,type,amount,status,trade_time") and "#TOTAL," in content,
              content[:120])
    bill_id = bill.get("id")
    st, again = download("MOCK_BANK")
    check("重复下载幂等（同一账单 id、明细不重复）",
          st == 200 and again.get("id") == bill_id and again.get("parsedCount") == bill.get("parsedCount"), str(again)[:200])
    st, lst = api("GET", f"/api/channel/bills?channelCode=MOCK_BANK&bizDate={TODAY}")
    check("账单列表可查", st == 200 and any(b.get("id") == bill_id for b in lst), str(lst)[:200])
    st, det = api("GET", f"/api/channel/bills/{bill_id}")
    check("账单详情含明细", st == 200 and len(det.get("details", [])) >= 4, str(det)[:200])

    # 差异 1：金额不符 → AMOUNT_MISMATCH 差错单
    ops("POST", f"/channel-ops/faults/bill/MOCK_BANK", {"amountBizNo": P + "D1", "amountDelta": 100})
    st, b2 = download("MOCK_BANK")
    diffs = {d["diffType"]: d for d in b2.get("diffs", []) if d.get("bizNo") == P + "D1"}
    check("金额不符差异被识别（AMOUNT_DIFF）", st == 200 and "AMOUNT_DIFF" in diffs, str(b2.get("diffs"))[:300])
    d = diffs.get("AMOUNT_DIFF", {})
    check("金额差异明细含账单/平台双金额", d.get("billAmount") == 1100 and d.get("localAmount") == 1000, str(d))
    dsp = dispute(d.get("disputeId")) if d.get("disputeId") else None
    check("金额差异经 DisputeFacade 建差错单（source=PAYMENT/AMOUNT_MISMATCH/MANUAL）",
          dsp is not None and dsp.get("source") == "PAYMENT" and dsp.get("type") == "AMOUNT_MISMATCH"
          and dsp.get("strategy") == "MANUAL", str(dsp))
    ops("POST", "/channel-ops/faults/bill/MOCK_BANK/reset")

    # 差异 2：渠道多单（平台无流水）→ ONE_SIDED
    extra = f"{P}DX,{P}DX,PAY,4242,CAPTURED,{int(time.time() * 1000)}"
    ops("POST", "/channel-ops/faults/bill/MOCK_BANK", {"extraRows": [extra]})
    st, b3 = download("MOCK_BANK")
    dx = [d for d in b3.get("diffs", []) if d.get("bizNo") == P + "DX"]
    check("渠道多单差异被识别（LOCAL_MISSING）", st == 200 and dx and dx[0]["diffType"] == "LOCAL_MISSING", str(b3.get("diffs"))[:300])
    dsp = dispute(dx[0].get("disputeId")) if dx and dx[0].get("disputeId") else None
    check("渠道多单建单边账差错单（ONE_SIDED）",
          dsp is not None and dsp.get("type") == "ONE_SIDED" and dsp.get("source") == "PAYMENT", str(dsp))
    ops("POST", "/channel-ops/faults/bill/MOCK_BANK/reset")

    # 差异 3：账单缺行（平台有流水）→ CHANNEL_MISSING
    ops("POST", "/channel-ops/faults/bill/MOCK_BANK", {"dropBizNo": P + "D2"})
    st, b4 = download("MOCK_BANK")
    dm = [d for d in b4.get("diffs", []) if d.get("bizNo") == P + "D2"]
    check("账单缺行差异被识别（BILL_MISSING）", st == 200 and dm and dm[0]["diffType"] == "BILL_MISSING", str(b4.get("diffs"))[:300])
    dsp = dispute(dm[0].get("disputeId")) if dm and dm[0].get("disputeId") else None
    check("账单缺行建短款差错单（CHANNEL_MISSING）",
          dsp is not None and dsp.get("type") == "CHANNEL_MISSING", str(dsp))
    ops("POST", "/channel-ops/faults/bill/MOCK_BANK/reset")

    # 差异 4：汇总不符 → 解析完整性拦截 + 差错单
    ops("POST", "/channel-ops/faults/bill/MOCK_BANK", {"totalDelta": 777})
    st, b5 = download("MOCK_BANK")
    check("声明汇总与明细不符 → 账单 FAILED（不入库脏数据）",
          st == 200 and b5.get("status") == "FAILED" and "汇总不符" in str(b5.get("message")), str(b5)[:300])
    dsp = find_dispute(f"MOCK_BANK:{TODAY}")
    check("汇总不符建差错单（AMOUNT_MISMATCH）",
          dsp is not None and dsp.get("type") == "AMOUNT_MISMATCH", str(dsp))
    ops("POST", "/channel-ops/faults/bill/MOCK_BANK/reset")

    # 差异 5：明细非法 → 解析失败 + 差错单
    ops("POST", "/channel-ops/faults/bill/MOCK_BANK", {"extraRows": [f"{P}DBAD,{P}DBAD,PAY,notanumber,CAPTURED,1"]})
    st, b6 = download("MOCK_BANK")
    check("非法明细 → 解析失败 FAILED（不静默丢行）",
          st == 200 and b6.get("status") == "FAILED" and "解析失败" in str(b6.get("message")), str(b6)[:300])
    ops("POST", "/channel-ops/faults/bill/MOCK_BANK/reset")

    # 恢复干净账单
    st, b7 = download("MOCK_BANK")
    check("故障复位后账单恢复 PARSED 且零差异",
          st == 200 and b7.get("status") == "PARSED" and b7.get("diffCount") == 0, str(b7)[:300])
    st, b8 = api("POST", f"/api/channel/bills/{bill_id}/reconcile", step=True)
    check("重新比对幂等（差错单不重复建）", st == 200 and b8.get("diffCount") == 0, str(b8)[:200])

    # 沙箱账单：渠道侧账本生成 → 下载解析 → 平台无流水 → 单边账差异
    ops("POST", "/channel-ops/faults", {"delayMs": 200})
    for i in (1, 2):
        ops("POST", "/channel-ops/sandbox/order", {"bizNo": f"{P}SB{i}", "amount": 1000 * i, "type": "PAY"})
    time.sleep(2.0)
    st, sb = download("SANDBOX_BANK")
    check("沙箱账单下载解析成功（渠道侧账本生成 CSV）",
          st == 200 and sb.get("status") == "PARSED" and sb.get("parsedCount") >= 2, str(sb)[:300])
    sbmine = [d for d in sb.get("details", []) if d.get("bizNo", "").startswith(P + "SB")]
    check("沙箱账单明细与平台流水比对出单边账差异",
          len(sbmine) >= 2 and all(d.get("matchResult") == "LOCAL_MISSING" for d in sbmine), str(sbmine)[:300])
    check("沙箱账单差异建差错单（source=PAYMENT）",
          all(d.get("disputeId") for d in sbmine), str(sbmine)[:300])

    # 下载失败 → 差错单（沙箱宕机）
    ops("POST", "/channel-ops/faults", {"down": True})
    st, fail = download("SANDBOX_BANK", YESTERDAY)
    check("渠道不可达 → 账单下载 FAILED", st == 200 and fail.get("status") == "FAILED", str(fail)[:300])
    dsp = find_dispute(f"SANDBOX_BANK:{YESTERDAY}")
    check("下载失败建差错单（SOURCE_UNAVAILABLE）",
          dsp is not None and dsp.get("type") == "SOURCE_UNAVAILABLE", str(dsp))
    ops("POST", "/channel-ops/faults/reset")

    # 日切作业（I-3 JobHandler）可手动触发且幂等
    st, jobs = ops("GET", "/channel-ops/jobs")
    check("渠道账单下载作业已注册到 I-3 调度表", st == 200 and "channelBillDownload" in jobs, str(jobs))
    st, jr = ops("POST", f"/channel-ops/jobs/channelBillDownload?param=MOCK_BANK:{TODAY}")
    check("JobHandler 触发成功（ShedLock 加锁执行）", st == 200 and jr.get("success") is True, str(jr))


# ============================== B. CH-2b 沙箱 ==============================

def section_b(async_on):
    print("[B] CH-2b 沙箱适配器 + 异步回执 + 四类故障注入 + 签名验签")
    ops("POST", "/channel-ops/faults/reset")
    ops("POST", "/channel-ops/faults", {"delayMs": 300})
    st, f = ops("GET", "/channel-ops/faults")
    check("故障注入可配置（延迟 300ms）", st == 200 and f.get("delayMs") == 300, str(f))

    t0 = time.time()
    st, r = ops("POST", "/channel-ops/spi/pay", {"channelCode": "SANDBOX_BANK", "bizNo": P + "B1", "amount": 2000, "cardNo": CARD})
    cost = (time.time() - t0) * 1000
    if async_on:
        check("沙箱异步受理返回 PROCESSING（走 CH-1 WAITING 路径）",
              st == 200 and r.get("status") == "PROCESSING" and r.get("channelOrderNo"), str(r))
    else:
        check("同步 stack 沙箱降级为同步终态（不产生 WAITING）",
              st == 200 and r.get("status") == "SUCCESS", str(r))
        check("沙箱延迟注入生效（受理耗时 >= 250ms）", cost >= 250, f"{cost:.0f}ms")

    # 拒绝注入
    ops("POST", "/channel-ops/faults", {"reject": True, "targetBizNo": P + "B2"})
    st, r = ops("POST", "/channel-ops/spi/pay", {"channelCode": "SANDBOX_BANK", "bizNo": P + "B2", "amount": 2000, "cardNo": CARD})
    if async_on:
        check("拒绝注入：受理仍 PROCESSING（终态由回执给 FAILED）",
              st == 200 and r.get("status") == "PROCESSING", str(r))
    else:
        check("拒绝注入：沙箱明确拒绝且不可切换",
              st == 200 and r.get("status") == "FAILED" and "沙箱渠道拒绝" in str(r.get("reason"))
              and r.get("failoverAllowed") is False, str(r))
    ops("POST", "/channel-ops/faults", {"reject": False, "targetBizNo": None})

    # 金额不符注入
    ops("POST", "/channel-ops/faults", {"amountMismatch": True, "amountDelta": 100, "targetBizNo": P + "B3"})
    st, r = ops("POST", "/channel-ops/spi/pay", {"channelCode": "SANDBOX_BANK", "bizNo": P + "B3", "amount": 2000, "cardNo": CARD})
    if async_on:
        check("金额不符注入：受理 PROCESSING（终态金额与请求不符）",
              st == 200 and r.get("status") == "PROCESSING", str(r))
    else:
        check("金额不符注入：沙箱拒绝受理（资金护航）",
              st == 200 and r.get("status") == "FAILED" and "金额不符" in str(r.get("reason")), str(r))
    ops("POST", "/channel-ops/faults", {"amountMismatch": False, "targetBizNo": None})

    # 回执丢失注入：渠道侧落终态但不推回执
    ops("POST", "/channel-ops/faults", {"receiptLoss": True, "delayMs": 200, "targetBizNo": P + "B4"})
    st, r = ops("POST", "/channel-ops/sandbox/order", {"bizNo": P + "B4", "amount": 3000, "type": "PAY"})
    check("沙箱渠道侧受理成功（真实 HTTP + 签名）", st == 200, str(r)[:200])
    time.sleep(2.0)
    st, orders = ops("GET", "/channel-ops/sandbox/orders?limit=50")
    mine = [o for o in orders if o.get("bizNo") == P + "B4"]
    check("回执丢失注入：渠道侧已终态但未推回执（receiptSent=0）",
          bool(mine) and mine[0].get("status") == "CAPTURED" and mine[0].get("receiptSent") == 0, str(mine))
    st, q = ops("GET", f"/channel-ops/spi/query?channelCode=SANDBOX_BANK&bizNo={P}B4")
    check("回执丢失可由 SPI 查询补单得到终态（CAPTURED）",
          st == 200 and q.get("channelStatus") == "CAPTURED" and q.get("status") == "SUCCESS", str(q))
    ops("POST", "/channel-ops/faults", {"receiptLoss": False, "targetBizNo": None})

    # 宕机注入：确未受理 → 允许故障切换
    ops("POST", "/channel-ops/faults", {"down": True})
    st, r = ops("POST", "/channel-ops/spi/pay", {"channelCode": "SANDBOX_BANK", "bizNo": P + "B5", "amount": 2000, "cardNo": CARD})
    check("沙箱宕机 → 明确未受理且允许故障切换（failoverAllowed=true）",
          st == 200 and r.get("status") == "FAILED" and r.get("failoverAllowed") is True, str(r))
    st, r = ops("POST", "/channel-ops/sandbox/order", {"bizNo": P + "B6", "amount": 1000})
    check("沙箱宕机时渠道侧下单失败（503/502）", st in (502, 503), str(r)[:200])
    ops("POST", "/channel-ops/faults/reset")

    # 验签：无签名 / 错签名一律 401
    st, r = _call("POST", OPS + "/sandbox-bank/query", {"bizNo": P + "B4"})
    check("渠道端点无签名 → 401 拒绝", st == 401, str(r)[:200])
    st, r = _call("POST", OPS + "/sandbox-bank/query", {"bizNo": P + "B4"},
                  {"X-Channel-Sign": "deadbeef", "X-Channel-Timestamp": str(int(time.time() * 1000))})
    check("渠道端点错误签名 → 401 拒绝（HMAC 验签生效）", st == 401, str(r)[:200])
    st, r = _call("POST", OPS + "/channel-ops/sandbox/receipt",
                  {"bizNo": P + "B4", "status": "CAPTURED", "amount": 3000})
    check("回执回调无签名 → 401（不落地任何状态）", st == 401, str(r)[:200])

    # 金额不符回执被既有护栏拒绝（不改写状态）
    st, t = accept(P + "B7", 2000, channel="MOCK_BANK")
    check("构造平台侧受理单用于回执护栏断言", st == 200, str(t))
    st, r = api("POST", "/api/channel/receipt", {"bizNo": P + "B7", "status": "CAPTURED", "amount": 9999})
    check("金额不符回执被拒绝（400，资金护航）", st == 400 and "金额" in str(r.get("error", "")), str(r))
    check("金额不符回执未改写渠道状态", (trade(P + "B7") or {}).get("status") in ("PREAUTH", "SUBMITTED"),
          str(trade(P + "B7")))


# ============================== F. 同步默认路径不回归 ==============================

def section_f(async_on):
    print("[F] 同步默认路径不回归（真实银行卡 / BALANCE / 手续费口径）")
    merchant = ok("POST", "/api/members", {"name": f"渠道验证商户{SUFFIX}", "type": "MERCHANT"})
    user = ok("POST", "/api/members", {"name": f"渠道验证用户{SUFFIX}", "type": "USER"})
    ma = ok("POST", "/api/accounts", {"memberId": merchant["id"]})
    ua = ok("POST", "/api/accounts", {"memberId": user["id"]})
    ok("POST", "/api/accounting/internal/topup", {"accountId": ua["id"], "amount": 100000, "bizNo": P + "TOPUP"})

    order = ok("POST", "/api/orders", {"merchantId": merchant["id"], "subject": "渠道验证卡支付", "amount": 2000})["order"]
    r = ok("POST", "/api/cashier/pay", {"orderId": order["id"], "method": "BANKCARD", "cardNo": CARD, "requestId": P + "REQ1"})
    pid = r["payment"]["id"]
    p = wait_pay(pid, timeout=150)
    check("银行卡支付终态 SUCCESS（同步路径不回归）", p and p["status"] == "SUCCESS", str(p))
    check("手续费口径不变（2000 分 → 12 分）", p.get("fee") == 12, str(p.get("fee")))
    check("卡号脱敏对外不变", p.get("cardNo") == "622202****0202", str(p.get("cardNo")))
    ch = trade(pid)
    check("渠道流水落痕 CAPTURED + channelCode", ch and ch["status"] == "CAPTURED" and ch.get("channelCode"), str(ch))
    if not async_on:
        check("同步 stack 渠道码为主渠道 MOCK_BANK", ch.get("channelCode") == "MOCK_BANK", str(ch))
        check("同步渠道无异步受理时间（submittedAt 为空）", ch.get("submittedAt") is None, str(ch))

    order2 = ok("POST", "/api/orders", {"merchantId": merchant["id"], "subject": "渠道验证拒绝卡", "amount": 2000})["order"]
    st, resp = api("POST", "/api/cashier/pay", {"orderId": order2["id"], "method": "BANKCARD",
                                               "cardNo": DECLINE_CARD, "requestId": P + "REQ2"})
    check("尾号 0000 银行拒绝 400 且文案不变",
          st == 400 and "渠道拒绝" in str(resp.get("error", "")), str(resp))
    plist = ok("GET", "/api/payments")
    pc = next((x for x in plist if x.get("orderId") == order2["id"]), None)
    if pc:
        pc = wait_pay(pc["id"], timeout=150)
        check("拒绝支付单 FAILED 且留痕（TCC Cancel）", pc and pc["status"] == "FAILED" and pc.get("failReason"), str(pc))
        oc = ok("GET", f"/api/orders/{order2['id']}")
        check("拒绝订单保持 CREATED（空回滚/释放）", oc["status"] == "CREATED" and oc.get("paymentId") is None, str(oc))
        st, _ = api("GET", f"/api/accounting/transfers/{pc['id']}")
        check("拒绝支付无账务单据（未动账）", st == 404, str(st))
    else:
        check("拒绝支付单 FAILED 且留痕（TCC Cancel）", False, "未找到支付单")

    # 退款（同步语义不变）
    r = ok("POST", f"/api/payments/{pid}/refund", {"amount": 2000, "requestId": P + "RF1"})
    p = poll(lambda: ok("GET", f"/api/payments/{pid}"), lambda x: x["status"] == "REFUNDED", timeout=60)
    check("银行卡全额退款 REFUNDED（同步退款语义不变）", p["status"] == "REFUNDED", str(p))
    chs = ok("GET", "/api/channel/trades")
    check("渠道留有退款记录（origBizNo 指向原支付单）",
          any(c.get("origBizNo") == pid and c["type"] == "REFUND" for c in chs), "无退款流水")

    # BALANCE 分支同步语义（红线：不得引入 WAITING/异步回执）
    order3 = ok("POST", "/api/orders", {"merchantId": merchant["id"], "subject": "渠道验证余额支付", "amount": 3000})["order"]
    r = ok("POST", "/api/cashier/pay", {"orderId": order3["id"], "method": "BALANCE",
                                       "accountId": ua["id"], "requestId": P + "REQ3"})
    pid3 = r["payment"]["id"]
    p3 = poll(lambda: ok("GET", f"/api/payments/{pid3}"), lambda x: x["status"] in ("SUCCESS", "FAILED"), timeout=60)
    check("BALANCE 支付同步收敛 SUCCESS（无渠道分支不变）", p3["status"] == "SUCCESS", str(p3))
    check("BALANCE 支付无渠道流水（不经渠道 SPI）", trade(pid3) is None, str(trade(pid3)))
    check("BALANCE 手续费口径不变（3000 → 18 分）", p3.get("fee") == 18, str(p3.get("fee")))
    check("账户余额正确扣减（100000-3000）", bal(ua["id"]) == 97000, str(bal(ua["id"])))
    return {"merchant": merchant, "user": user, "ma": ma, "ua": ua}


# ============================== G. 异步 WAITING 全链路 ==============================

def section_g(ctx):
    print("[G] CH-2b/CH-3 异步 WAITING 全链路（PAYMENT_CHANNEL_ASYNC=true）")
    ops("POST", "/channel-ops/faults/reset")
    ops("POST", "/channel-ops/faults", {"delayMs": 800})
    # 让沙箱成为择优主渠道：压低模拟银行主渠道评分因子 + 抬高沙箱权重（纯评分择优，可观测）
    ops("POST", "/channel-ops/score/MOCK_BANK?weight=0&feeBps=9000")
    ops("POST", "/channel-ops/score/SANDBOX_BANK?weight=500&feeBps=10")
    r = route(2000)
    check("异步 stack 沙箱渠道可被路由选中（权重择优）", r["channelCode"] == "SANDBOX_BANK", str(r)[:300])

    merchant, ua = ctx["merchant"], ctx["ua"]

    def card_pay(subject, amount, req):
        order = ok("POST", "/api/orders", {"merchantId": merchant["id"], "subject": subject, "amount": amount})["order"]
        resp = ok("POST", "/api/cashier/pay", {"orderId": order["id"], "method": "BANKCARD",
                                              "cardNo": CARD, "requestId": req})
        return order, resp["payment"]["id"]

    # 1) 正常异步：PROCESSING → 回执 → SUCCESS
    order, pid = card_pay("沙箱异步支付", 2000, P + "G1")
    ch = trade(pid)
    check("沙箱受理落 SUBMITTED（异步受理未扣款）", ch and ch["status"] == "SUBMITTED", str(ch))
    p = wait_pay(pid, timeout=170, accelerate=False)
    check("沙箱回执驱动 WAITING → SUCCESS（纯自动补单 job，支付成功 ⇔ 渠道已扣款）",
          p and p["status"] == "SUCCESS", str(p))
    ch = trade(pid)
    check("渠道终态 CAPTURED + 渠道方订单号", ch["status"] == "CAPTURED" and str(ch.get("channelOrderNo", "")).startswith("SBX-"), str(ch))
    check("异步受理落痕 channelCode=SANDBOX_BANK", ch.get("channelCode") == "SANDBOX_BANK", str(ch))

    # 2) 回执丢失 → 查询补单收敛
    ops("POST", "/channel-ops/faults", {"receiptLoss": True, "targetBizNo": None})
    order, pid2 = card_pay("沙箱回执丢失", 2100, P + "G2")
    p2 = wait_pay(pid2, timeout=120)
    check("回执丢失 → 查询补单收敛 SUCCESS（不重不丢）", p2 and p2["status"] == "SUCCESS", str(p2))
    check("补单收敛后渠道终态 CAPTURED", (trade(pid2) or {}).get("status") == "CAPTURED", str(trade(pid2)))
    ops("POST", "/channel-ops/faults", {"receiptLoss": False})

    # 3) 异步拒绝 → TCC 回滚（无悬挂、未动账）
    ops("POST", "/channel-ops/faults", {"reject": True})
    order, pid3 = card_pay("沙箱异步拒绝", 2200, P + "G3")
    p3 = wait_pay(pid3, timeout=120)
    check("沙箱异步拒绝 → 支付 FAILED（TCC 回滚）", p3 and p3["status"] == "FAILED", str(p3))
    check("拒绝后渠道终态 FAILED（非 SUBMITTED 悬挂）", (trade(pid3) or {}).get("status") == "FAILED", str(trade(pid3)))
    oc = ok("GET", f"/api/orders/{order['id']}")
    check("拒绝订单保持 CREATED（释放）", oc["status"] == "CREATED", str(oc))
    st, _ = api("GET", f"/api/accounting/transfers/{pid3}")
    check("异步拒绝未动账（无账务单据）", st == 404, str(st))
    ops("POST", "/channel-ops/faults", {"reject": False})

    # 4) 金额不符回执 → 不过账 + 差错单（渠道级合成单，避免留下真实悬挂支付单）
    ops("POST", "/channel-ops/faults", {"amountMismatch": True, "amountDelta": 100, "targetBizNo": P + "G4"})
    st, t = accept(P + "G4", 2300, channel="SANDBOX_BANK")
    check("金额不符注入下沙箱受理 SUBMITTED", st == 200 and t.get("status") == "SUBMITTED", str(t))
    time.sleep(8)
    t = trade(P + "G4")
    check("金额不符回执被护栏拒绝 → 渠道仍 SUBMITTED（绝不过账）", t and t["status"] == "SUBMITTED", str(t))
    dsp = find_dispute(P + "G4")
    check("金额不符建差错单（AMOUNT_MISMATCH，留人工）",
          dsp is not None and dsp.get("type") == "AMOUNT_MISMATCH", str(dsp))
    ops("POST", "/channel-ops/faults/reset")
    ops("POST", "/channel-ops/trade/void", {"bizNo": P + "G4"})
    check("人工撤销后渠道 VOIDED（不留悬挂）", (trade(P + "G4") or {}).get("status") == "VOIDED", str(trade(P + "G4")))

    # 5) 沙箱宕机 → 适配器级故障切换（一单一行，落备选渠道）
    ops("POST", "/channel-ops/faults", {"down": True})
    order, pid5 = card_pay("沙箱宕机切换", 2400, P + "G5")
    p5 = wait_pay(pid5, timeout=120)
    ch5 = trade(pid5)
    check("沙箱宕机 → 故障切换备选渠道且支付 SUCCESS",
          p5 and p5["status"] == "SUCCESS" and ch5 and ch5.get("channelCode") != "SANDBOX_BANK", str(p5) + str(ch5))
    check("故障切换后一单只有一行渠道流水（无重复受理）",
          ch5 and ch5.get("bizNo") == pid5 and ch5.get("status") == "CAPTURED", str(ch5))
    ops("POST", "/channel-ops/faults/reset")

    # 6) 超时 → 状态未知 → 不臆断失败（PROCESSING/补单收敛）
    ops("POST", "/channel-ops/faults", {"timeoutMs": 4000, "delayMs": 500})
    st, t = accept(P + "G6", 2500, channel="SANDBOX_BANK")
    check("受理超时 → 状态未知留 SUBMITTED（不臆断失败）",
          st == 200 and t.get("status") == "SUBMITTED", str(t))
    ops("POST", "/channel-ops/faults/reset")
    t = poll(lambda: trade(P + "G6"), lambda x: x and x["status"] in ("CAPTURED", "FAILED"), timeout=60)
    check("超时单经查询补单收敛终态（无悬挂）", t and t["status"] in ("CAPTURED", "FAILED"), str(t))

    # 7) 异步段收尾：本脚本所有支付单均终态，无 SUBMITTED 悬挂
    ops("POST", "/channel-ops/score/SANDBOX_BANK?weight=50&feeBps=55")
    ops("POST", "/channel-ops/score/MOCK_BANK?weight=100&feeBps=60")
    leftovers = []
    for pid_x in (pid, pid2, pid3, pid5):
        st_x, px = api("GET", f"/api/payments/{pid_x}")
        if st_x == 200 and px["status"] not in ("SUCCESS", "FAILED", "REFUNDED"):
            leftovers.append((pid_x, px["status"]))
    check("异步段全部支付单终态（无悬挂单）", not leftovers, str(leftovers))


def main():
    print(f"== payment-pro 渠道与路由工作流验证（批次 {SUFFIX}，gateway {BASE}，ops {OPS}）==")
    st = login()
    check("控制台登录取得 Bearer（RBAC 面可用）", st == 200 and bool(TOKEN), str(st))
    st, health = api("GET", "/api/health", auth=False)
    check("13 个进程健康", st == 200 and isinstance(health, list) and len(health) == 13
          and all(h["ok"] for h in health), str(health)[:200])
    st, runtime = ops("GET", "/channel-ops/runtime")
    if st != 200:
        print(f"!! 渠道域内部运维面不可达（{OPS}）: {runtime}")
        print("   请确认 payment-channel 已启动且 server.port=18087")
        return 2
    async_on = bool(runtime.get("async"))
    print(f"   运行态: async={async_on} provider={runtime.get('configProvider')} "
          f"adapters={runtime.get('adapters')} strategy={runtime.get('strategy')}")
    snapshot_configs()
    try:
        section_a()
        section_c(async_on)
        section_e()
        section_d()
        section_b(async_on)
        ctx = section_f(async_on)
        if async_on:
            section_g(ctx)
        else:
            skip("G 异步 WAITING 全链路（7 组）", "当前栈 PAYMENT_CHANNEL_ASYNC=false；"
                 "异步段需以 ./run.sh --docker 前 export PAYMENT_CHANNEL_ASYNC=true 全栈重启后重跑本脚本")
    finally:
        restore_all()
        print("  [CLEANUP] 渠道配置/故障注入/指标/动态配置文件已还原")
    print(f"\n== 结果: PASS={PASSED} FAIL={FAILED} SKIP={SKIPPED} ==")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())