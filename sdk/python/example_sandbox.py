#!/usr/bin/env python3
"""SDK 沙箱示例（G-4b）：注册好的沙箱 appId/密钥经环境变量注入，跑通全链路。

    PP_APP_ID=sbx... PP_APP_SECRET=... python3 sdk/python/example_sandbox.py

流程：开户 → 充值 1000 分 → 余额校验 → 下单 → BALANCE 支付 → 查询 → 划转 → 提现。
沙箱账本独立，绝不触碰生产资金。退出码 0=全部断言通过。
"""
import os
import sys
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from payment_openapi import PaymentOpenApiClient  # noqa: E402

BASE = os.environ.get("PP_GATEWAY", "http://127.0.0.1:8080")
APP_ID = os.environ.get("PP_APP_ID")
APP_SECRET = os.environ.get("PP_APP_SECRET")
if not APP_ID or not APP_SECRET:
    print("需要 PP_APP_ID / PP_APP_SECRET（沙箱应用注册响应一次性返回）")
    sys.exit(2)

suffix = uuid.uuid4().hex[:8]
client = PaymentOpenApiClient(BASE, APP_ID, APP_SECRET, sandbox=True)

version = client.version()
print(f"[sdk] 版本公告 default={version.get('default')} current={version.get('current')} "
      f"appDefault={version.get('negotiation', {}).get('appDefault')}")

acct = client.account_open("OPTEST-SDK-" + suffix, "USER")
assert acct["accountId"].startswith("SBXA"), acct
print(f"[sdk] 沙箱开户 memberId={acct['memberId']} accountId={acct['accountId']}")

biz = "OPTEST-SDK-TOPUP-" + suffix
topup = client.fund_topup(acct["accountId"], 1000, biz)
payment = topup["payment"]          # v1 冻结形状 {payment, repeated}
assert payment["status"] == "SUCCESS" and payment["fee"] == 0, topup
again = client.fund_topup(acct["accountId"], 1000, biz)
assert again["payment"]["id"] == payment["id"] and again["repeated"] is True, "bizNo 幂等失败"
print(f"[sdk] 充值 paymentId={payment['id']} 幂等复放同单")

balance = client.account_balance(acct["accountId"])
assert balance == 1000, balance
assert isinstance(balance, int), "account.balance 必须是裸数字 long"
print(f"[sdk] 余额={balance}（裸数字 long）")

order = client.trade_create("OPTEST-SDK 行程", 300, "OPTEST-SDK-ORDER-" + suffix)
assert order["status"] == "CREATED", order
paid = client.trade_pay(order["id"], acct["accountId"], "BALANCE")["payment"]
assert paid["status"] == "SUCCESS", paid
queried = client.trade_query(order["id"])
assert queried["order"]["status"] == "SUCCESS" and queried["payment"]["id"] == paid["id"], queried
print(f"[sdk] 下单+支付+查询 orderId={order['id']} paymentId={paid['id']}")

peer = client.account_open("OPTEST-SDK-PEER-" + suffix, "USER")
client.account_transfer(acct["accountId"], peer["accountId"], 200, "OPTEST-SDK-TRANSFER-" + suffix)
assert client.account_balance(acct["accountId"]) == 500, "划转后余额应为 500"
assert client.account_balance(peer["accountId"]) == 200
withdraw = client.fund_withdraw(acct["accountId"], 100, "OPTEST-SDK-WITHDRAW-" + suffix)["payment"]
assert withdraw["status"] == "SUCCESS"
assert client.account_balance(acct["accountId"]) == 400
print("[sdk] 划转/提现完成，余额=400")

print("SDK 沙箱示例全部断言通过")