#!/usr/bin/env python3
"""C-5 多端收银台 + CH-6 签约代扣：Python SDK 端到端**沙箱**示例（可跑，退出码 0=全断言通过）。

    PP_APP_ID=sbx... PP_APP_SECRET=... python3 sdk/python/example_cashier_sandbox.py

沙箱应用由控制台注册（POST /api/openplatform/apps，sandbox=true，响应一次性返回密钥）。
本示例演示**业务方自渲染收银台**的完整对接序列（payment-pro 不提供 C 端页面）：

    开户 → 充值(幂等) → 下单 → cashier.methods 渲染 → 设口令 → cashier.verify(PASSWORD)
      → cashier.payVerified(携 verifyToken) → 轮询终态 → **同 requestId 幂等重放**
      → 小额免密 → 被扫(code.create/parse/scanPay/query 轮询) → 主扫(qrContent 归一 code=)
      → SMS/BIO 验证 → channel.sign 签约 → channel.agent.pay 代扣(幂等) → 解约后拒绝代扣

资金全程只在沙箱账本（SBXA/SBXO/SBXP），fee=0、同步 SUCCESS，绝不触碰生产资金。
多端适配指南：docs/cashier-multiclient.md；契约：docs/cashier-openapi.md。
"""
import os
import random
import sys
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from payment_openapi import (CASHIER_METHODS, SIGN_METHODS, OpenApiError,  # noqa: E402
                             PaymentOpenApiClient, new_request_id, normalize_scan_code)

BASE = os.environ.get("PP_GATEWAY", "http://127.0.0.1:8080")
APP_ID = os.environ.get("PP_APP_ID")
APP_SECRET = os.environ.get("PP_APP_SECRET")
if not APP_ID or not APP_SECRET:
    print("需要 PP_APP_ID / PP_APP_SECRET（沙箱应用注册响应一次性返回）")
    sys.exit(2)

SUFFIX = uuid.uuid4().hex[:8]
TAG = "MCTEST-SDKCS-" + SUFFIX
PASSWORD = "135790"
FREE_LIMIT = 1000


def luhn_card():
    """生成本示例专属 Luhn 合法沙箱卡号（每次不同 ⇒ 不撞风控卡号频率窗口）。"""
    while True:
        body = "622202" + "".join(random.choice("0123456789") for _ in range(9))
        for digit in "0123456789":
            candidate = body + digit
            total, alt = 0, False
            for ch in reversed(candidate):
                d = int(ch)
                if alt:
                    d *= 2
                    if d > 9:
                        d -= 9
                total += d
                alt = not alt
            if total % 10 == 0 and not candidate.endswith("0000"):
                return candidate


def step(label):
    print(f"[sdk-cashier] {label}")


client = PaymentOpenApiClient(BASE, APP_ID, APP_SECRET, sandbox=True)

# ---- 0. 版本/能力公告：确认收银台与签约方法已登记（多端对接能力面） ----
version = client.version()
sandbox_methods = version.get("sandbox", {}).get("methods", [])
assert set(CASHIER_METHODS).issubset(set(sandbox_methods)), f"沙箱未登记收银台方法: {sandbox_methods}"
assert set(SIGN_METHODS).issubset(set(sandbox_methods)), f"沙箱未登记签约方法: {sandbox_methods}"
assert version.get("multiclient", {}).get("qrNormalization"), "版本公告缺主扫归一口径"
step(f"能力公告 default={version.get('default')} 沙箱方法数={len(sandbox_methods)}")

# ---- 1. 开户 + 充值（bizNo 幂等） ----
payer = client.account_open(TAG + "-PAYER", "USER")
assert payer["accountId"].startswith("SBXA"), payer
payer_id = payer["accountId"]
topup_no = TAG + "-TOPUP"
topup = client.fund_topup(payer_id, 20000, topup_no)["payment"]
assert topup["status"] == "SUCCESS" and topup["fee"] == 0, topup
replay = client.fund_topup(payer_id, 20000, topup_no)
assert replay["payment"]["id"] == topup["id"] and replay["repeated"] is True, replay
assert client.account_balance(payer_id) == 20000
step(f"开户+充值 accountId={payer_id} 余额=20000（bizNo 幂等复放同单）")

# ---- 2. 下单 → 渲染支付方式（各端据此画收银台） ----
order = client.trade_create("MCTEST SDK 收银台行程", 1500, TAG + "-ORDER-A")
order_id = order["id"]
merchant_id = order["merchantId"]
view = client.cashier_methods(order_id, payer_id)
assert view["actualAmount"] == 1500 and view["freeVerifyLimit"] == FREE_LIMIT, view
balance_method = next(m for m in view["methods"] if m["method"] == "BALANCE")
assert balance_method["available"] is True and balance_method["category"] == "REAL", balance_method
assert any(m["category"] == "RESERVED" and m["available"] is False for m in view["methods"]), view
step(f"渲染 orderId={order_id} 可用方式={[m['method'] for m in view['methods'] if m['available']]}")

# ---- 3. 设口令 → PASSWORD 验证 → 验证前置受理 → 轮询终态 → 幂等重放 ----
assert client.cashier_pay_password_set(PASSWORD, account_id=payer_id) is True
verified = client.cashier_verify(account_id=payer_id, order_id=order_id, amount=1500,
                                verify_type="PASSWORD", pay_password=PASSWORD)
assert verified["pass"] is True and verified["verifyToken"], verified
request_id = new_request_id("PAY")
paid = client.cashier_pay_verified(order_id, request_id, account_id=payer_id,
                                  verify_token=verified["verifyToken"])
assert paid["payment"]["status"] == "SUCCESS" and paid["repeated"] is False, paid
terminal = client.wait_order_terminal(order_id)
assert terminal["order"]["status"] == "SUCCESS", terminal
assert terminal["payment"]["id"] == paid["payment"]["id"], terminal
again = client.cashier_pay_verified(order_id, request_id, account_id=payer_id)
assert again["repeated"] is True and again["payment"]["id"] == paid["payment"]["id"], again
assert client.account_balance(payer_id) == 18500, "幂等重放不得重复扣款"
step(f"验证+受理+轮询终态 paymentId={paid['payment']['id']} 幂等重放同单，余额=18500")

# ---- 4. 小额免密（amount ≤ freeVerifyLimit 免验证） ----
small = client.trade_create("MCTEST SDK 免密行程", 100, TAG + "-ORDER-B")
small_paid = client.cashier_pay_verified(small["id"], new_request_id("PAY"), account_id=payer_id)
assert small_paid["payment"]["status"] == "SUCCESS", small_paid
assert client.account_balance(payer_id) == 18400
step("小额免密受理成功（100 ≤ 1000），余额=18400")

# ---- 5. 被扫：用户出示付款码，商户扫（核销一次性 + 幂等 + 轮询 CONSUMED） ----
scan_order = client.trade_create("MCTEST SDK 被扫行程", 100, TAG + "-ORDER-C")
user_code = client.cashier_code_create("USER_PRESENTED", account_id=payer_id, order_id=scan_order["id"])
assert len(user_code["code"]) == 18 and user_code["code"].isdigit(), user_code
assert user_code["qrContent"] == user_code["code"], "被扫 qrContent 应等于码值本身"
assert normalize_scan_code(user_code["qrContent"]) == user_code["code"]
parsed = client.cashier_code_parse(user_code["code"])
assert parsed["status"] == "ACTIVE" and parsed["amount"] == 100, parsed
scan_rid = new_request_id("SCAN")
scanned = client.cashier_scan_pay(user_code["code"], scan_rid)
assert scanned["payment"]["status"] == "SUCCESS", scanned
consumed = client.wait_code_consumed(user_code["code"])
assert consumed["status"] == "CONSUMED", consumed
assert client.cashier_scan_pay(user_code["code"], scan_rid)["repeated"] is True
try:
    client.cashier_scan_pay(user_code["code"], new_request_id("SCAN"))
    raise AssertionError("已核销码异 requestId 应 409 防重放")
except OpenApiError as e:
    assert e.code == "A0409", e
assert client.account_balance(payer_id) == 18300
step(f"被扫核销 paymentId={scanned['payment']['id']} 一次性+幂等+防重放，余额=18300")

# ---- 6. 主扫：商户出二维码，用户扫（**qrContent 是 URL，必须归一取 code=**） ----
mp_order = client.trade_create("MCTEST SDK 主扫行程", 100, TAG + "-ORDER-D")
mp_code = client.cashier_code_create("MERCHANT_PRESENTED", order_id=mp_order["id"])
assert mp_code["qrContent"].startswith("https://cashier.payment-pro.example/scan?code="), mp_code
token = normalize_scan_code(mp_code["qrContent"])
assert token == mp_code["code"] and token.startswith("MP"), (token, mp_code["code"])
assert client.cashier_code_parse(token)["status"] == "ACTIVE"
mp_paid = client.cashier_scan_pay(token, new_request_id("SCAN"), payer_account_id=payer_id)
assert mp_paid["payment"]["status"] == "SUCCESS", mp_paid
assert mp_paid["payment"]["payerAccountId"] == payer_id, mp_paid
assert client.account_balance(payer_id) == 18200
step(f"主扫归一 token={token[:10]}... 受理成功，余额=18200")

# ---- 7. SMS / BIO 验证（沙箱取码；生产默认掩码，不得依赖响应取码） ----
masked = client.cashier_sms_send(account_id=payer_id, order_id=order_id)
assert masked["smsCode"] == "******" and masked["smsCodeMasked"] is True, masked
sms = client.cashier_sms_send(account_id=payer_id, order_id=order_id, sandbox=True)
assert len(sms["smsCode"]) == 6 and sms["smsCode"].isdigit(), sms
sms_verify = client.cashier_verify(account_id=payer_id, order_id=order_id, amount=1500,
                                  verify_type="SMS", sms_code=sms["smsCode"])
assert sms_verify["pass"] is True and sms_verify["challengeType"] == "SMS", sms_verify
bio = client.cashier_verify(account_id=payer_id, order_id=order_id, amount=1500,
                           verify_type="BIO", bio_token="MCTEST-BIO-" + SUFFIX)
assert bio["pass"] is True and bio["challengeType"] == "BIO", bio
bad = client.cashier_verify(account_id=payer_id, order_id=order_id, amount=1500,
                           verify_type="PASSWORD", pay_password="000000")
assert bad["pass"] is False and bad.get("verifyToken") in (None, ""), bad
step("SMS/BIO 验证通过；错口令 pass=false 不签发令牌（不受理不扣款）")

# ---- 8. 签约 → 代扣（幂等）→ 解约后拒绝 ----
card = luhn_card()
sign_rid = new_request_id("SIGN")
contract = client.channel_sign(sign_rid, account_id=payer_id, merchant_id=merchant_id, card_no=card,
                               single_limit=5000, period_limit=20000, period_type="DAY",
                               scope_note="MCTEST SDK 代扣授权")
assert contract["status"] == "SIGNED" and contract["signNo"], contract
assert contract["cardNoMask"].endswith(card[-4:]) and card not in str(contract), "卡号脱敏失败"
assert client.channel_sign(sign_rid, account_id=payer_id, merchant_id=merchant_id,
                           card_no=card)["repeated"] is True, "签约 requestId 幂等失败"
sign_no = contract["signNo"]
deduct_rid = new_request_id("DEDUCT")
deduct = client.channel_agent_pay(sign_no, 500, deduct_rid, merchant_id=merchant_id,
                                  subject="MCTEST SDK 代扣")
assert deduct["status"] == "SUCCESS" and deduct["deductNo"] and deduct["paymentId"], deduct
deduct_terminal = client.wait_deduct_terminal(sign_no, 500, deduct_rid)
assert deduct_terminal["deductNo"] == deduct["deductNo"] and deduct_terminal["repeated"] is True
assert client.account_balance(payer_id) == 17700, "代扣幂等重放不得重复扣款"
step(f"签约 signNo={sign_no} 代扣 deductNo={deduct['deductNo']} 幂等重放同单，余额=17700")

cancelled = client.channel_sign(new_request_id("SIGN"), action="UNSIGN", sign_no=sign_no,
                                reason="MCTEST SDK 示例解约")
assert cancelled["status"] == "CANCELLED", cancelled
try:
    client.channel_agent_pay(sign_no, 100, new_request_id("DEDUCT"), merchant_id=merchant_id)
    raise AssertionError("解约后应拒绝代扣（受理前拒绝，不落悬挂单）")
except OpenApiError as e:
    assert e.code in ("A0403", "A0409", "B0001"), e
assert client.account_balance(payer_id) == 17700, "拒绝代扣不得动账"
step("解约 CANCELLED；解约后代扣受理前拒绝，余额不变=17700")

print("Python SDK 收银台沙箱示例全部断言通过")