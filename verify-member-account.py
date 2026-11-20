#!/usr/bin/env python3
"""verify-member-account.py — wave3 P1 C 泳道八卡验证（M-2/M-3/M-4/X-2/M-5/A-1/A-2/A-3）。
跑法: python3 verify-member-account.py  （payment-pro 栈需已启动，gateway 8080）

覆盖:
  - M-2 KYC 四要素沙箱核验/失败留痕/幂等/敏感要素脱敏（明文不出现在任何响应）;
  - M-3 分级限额默认不误拦（ride 量级 topup/transfer 实际成功）、升级需 KYC、只升不降;
  - M-4/X-2 进件状态机（提交→通过/驳回/补件→重提）+ 审核留痕 + READONLY 越权 403 + 执照号幂等;
  - M-5 C 端注册/口令登录/失败锁定/短信沙箱/会话/登出/改密（旧口令校验+全端失效）/冻结拒绝;
  - A-1 多账户类型独立开户/幂等/独立核算、getByMember 与 account.open 缺省 BALANCE 不回归;
  - A-2 绑卡四要素/Luhn/默认卡切换/解绑顺位/重绑幂等/卡号脱敏（只留后四位）;
  - A-3 冻结按原因细分（RISK/JUDICIAL/PREAUTH/DISPUTE）、解冻须对应原因、司法双人复核、
        bizNo 幂等、冻结期间动账闸门真实生效、全程试算平衡 D=C 且 Σbalance=0;
  - ride 红线: account.open 幂等懒开户语义不变、account.balance 裸数字、E-1 list/get 不泄 appSecret、
        getByAppId 内部验签链路可用（新商户 HMAC 全通）。
测试数据独立前缀 W3C + uuid，可重复跑；只新增行，不删改既有数据。
"""
import hashlib
import hmac
import json
import os
import random
import string
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("PP_GATEWAY", "http://127.0.0.1:8080")
SUFFIX = uuid.uuid4().hex[:8]
ADMIN_PASS = os.environ.get("PP_ADMIN_PASSWORD", "admin123")
passed = failed = 0


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  [PASS] {name}")
    else:
        failed += 1
        print(f"  [FAIL] {name}  {detail}")


_DEFAULT_TOKEN = object()


def req(method, path, body=None, token=_DEFAULT_TOKEN):
    # 缺省带控制台 Bearer（/api/** 安全链兜底）；显式 token=None 用于未登录/HMAC/登录端点
    if token is _DEFAULT_TOKEN:
        token = ADMIN
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Accept", "application/json")
    if data is not None:
        r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
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


st, resp = req("POST", "/api/auth/login", {"username": "admin", "password": ADMIN_PASS}, token=None)
assert st == 200 and resp.get("token"), f"admin 登录失败: {st} {resp}"
ADMIN = resp["token"]


def ok(method, path, body=None, token=None):
    st, resp = req(method, path, body, token=token or ADMIN)
    assert st == 200, f"{method} {path} 期望 200 实际 {st}: {resp}"
    return resp


# ---------------- 沙箱要素生成 ----------------
ID_WEIGHTS = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2]
ID_CODES = "10X98765432"


def gen_id_card(seq=None):
    seq = seq if seq is not None else random.randint(1, 999)
    base = f"11010119900307{seq:03d}"
    total = sum(int(c) * w for c, w in zip(base, ID_WEIGHTS))
    return base + ID_CODES[total % 11]


def luhn_check_digit(digits):
    total, alt = 0, True
    for c in reversed(digits):
        n = int(c)
        if alt:
            n *= 2
            if n > 9:
                n -= 9
        total += n
        alt = not alt
    return str((10 - total % 10) % 10)


def gen_card(bin_prefix="622202"):
    body = bin_prefix + "".join(random.choice(string.digits) for _ in range(9))
    return body + luhn_check_digit(body)


def gen_mobile():
    return "139" + "".join(random.choice(string.digits) for _ in range(8))


def gen_license():
    return "91350200" + "".join(random.choice(string.ascii_uppercase + string.digits) for _ in range(8))


def canonical(params):
    items = sorted((k, v) for k, v in params.items() if v is not None and k != "sign")
    return "&".join(f"{k}={v}" for k, v in items)


def openapi_call(endpoint, app_id, secret, extra=None):
    params = {"appId": app_id, "timestamp": int(time.time() * 1000)}
    params.update(extra or {})
    params["sign"] = hmac.new(secret.encode(), canonical(params).encode(), hashlib.sha256).hexdigest()
    return req("POST", "/openapi/" + endpoint, params, token=None)


def trial_balanced(tag):
    t = ok("GET", "/api/accounting/trial")
    check(f"试算平衡 D=C 且 Σbalance=0（{tag}）",
          t.get("balanced") is True and t.get("totalDebit") == t.get("totalCredit")
          and t.get("balanceSum") == 0, str(t))


print("== 1. M-2 KYC 四要素 / 脱敏 / 幂等 ==")
kyc_member = ok("POST", "/api/members", {"name": "W3C-kyc-" + SUFFIX, "type": "USER"})
KID = kyc_member["id"]
ID1, CARD1, MOB1 = gen_id_card(), gen_card(), gen_mobile()
st, resp = req("GET", f"/api/members/{KID}/kyc")
check("未提交 KYC 查询 404", st == 404, f"{st} {resp}")
kyc_body = {"name": "张三", "idCardNo": ID1, "bankCardNo": CARD1, "mobile": MOB1}
kyc = ok("POST", f"/api/members/{KID}/kyc", kyc_body)
check("四要素一致 PASS + 等级 II", kyc.get("pass") is True and kyc.get("level") == "II"
      and kyc.get("verifyStatus") == "PASS", str(kyc))
blob = json.dumps(kyc, ensure_ascii=False)
check("KYC 出参脱敏（身份证/卡号/手机号明文不出现）",
      ID1 not in blob and CARD1 not in blob and MOB1 not in blob
      and kyc.get("idCardMask", "").endswith(ID1[-4:]) and kyc.get("bankCardMask", "").endswith(CARD1[-4:]),
      blob[:200])
kyc2 = ok("POST", f"/api/members/{KID}/kyc", {"name": "李四", "idCardNo": gen_id_card(),
                                             "bankCardNo": gen_card(), "mobile": gen_mobile()})
check("PASS 后重复提交幂等返回原结果", kyc2.get("nameMask") == kyc.get("nameMask")
      and kyc2.get("verifiedAt") == kyc.get("verifiedAt"), str(kyc2))
check("getKyc 与提交一致", ok("GET", f"/api/members/{KID}/kyc").get("verifyStatus") == "PASS")
bad_member = ok("POST", "/api/members", {"name": "W3C-kycbad-" + SUFFIX, "type": "USER"})
BID = bad_member["id"]
BAD_ID = ID1[:-1] + ("0" if ID1[-1] != "0" else "1")
bad = ok("POST", f"/api/members/{BID}/kyc", {"name": "张三", "idCardNo": BAD_ID,
                                            "bankCardNo": CARD1, "mobile": MOB1})
check("校验位不一致 FAIL 留痕 + reason", bad.get("pass") is False and bad.get("verifyStatus") == "FAIL"
      and bool(bad.get("reason")), str(bad))
check("FAIL 响应不泄敏感明文", BAD_ID not in json.dumps(bad) and CARD1 not in json.dumps(bad))
retry = ok("POST", f"/api/members/{BID}/kyc", kyc_body)
check("FAIL 修正重提 → PASS", retry.get("pass") is True, str(retry))
bad_card = ok("POST", f"/api/members/{BID}/kyc", kyc_body)  # 已 PASS 幂等
check("重提后幂等保持 PASS", bad_card.get("pass") is True)

print("== 2. M-3 分级限额 / 升级 ==")
kyc_acc = ok("POST", "/api/accounts", {"memberId": KID})
KA = kyc_acc["id"]
check("新户缺省 BALANCE + level I", kyc_acc.get("accountType") == "BALANCE" and kyc_acc.get("level") == "I",
      str(kyc_acc))
lv = ok("GET", f"/api/accounts/{KA}/level")
check("getLevel 限额规则（I 类单笔 1000 万分）", lv.get("level") == "I" and lv.get("singleLimit") == 10_000_000
      and lv.get("kycLevel") == "II", str(lv))
lc = ok("GET", f"/api/accounts/{KA}/limit-check?amount=100000&kind=SINGLE")
check("ride 量级单笔限额校验通过（默认不误拦）", lc.get("pass") is True, str(lc))
lc2 = ok("GET", f"/api/accounts/{KA}/limit-check?amount=20000000&kind=SINGLE")
check("超单笔限额拒绝", lc2.get("pass") is False and bool(lc2.get("reason")), str(lc2))
lc3 = ok("GET", f"/api/accounts/{KA}/limit-check?amount=999999999999&kind=BALANCE")
check("超余额上限拒绝", lc3.get("pass") is False, str(lc3))
nokyc_member = ok("POST", "/api/members", {"name": "W3C-nokyc-" + SUFFIX, "type": "USER"})
NK_ACC = ok("POST", "/api/accounts", {"memberId": nokyc_member["id"]})["id"]
st, resp = req("POST", f"/api/accounts/{NK_ACC}/level/upgrade", {"targetLevel": "II"})
check("未实名升级 II 拒绝", st == 400, f"{st} {resp}")
up2 = ok("POST", f"/api/accounts/{KA}/level/upgrade", {"targetLevel": "II"})
check("KYC PASS 升级 II 成功", up2.get("level") == "II", str(up2))
up3 = ok("POST", f"/api/accounts/{KA}/level/upgrade", {"targetLevel": "III"})
check("operator 复核升级 III 成功", up3.get("level") == "III", str(up3))
st, resp = req("POST", f"/api/accounts/{KA}/level/upgrade", {"targetLevel": "II"})
check("只升不降（III→II 409）", st == 409, f"{st} {resp}")

print("== 3. M-4/X-2 进件审核流 ==")
OP_NAME, RO_NAME = "w3cop" + SUFFIX, "w3cro" + SUFFIX
OP_PWD = "OpPass!" + SUFFIX
op_user = ok("POST", "/api/console/users", {"username": OP_NAME, "password": OP_PWD, "roles": ["OPERATOR"]})
ro_user = ok("POST", "/api/console/users", {"username": RO_NAME, "password": OP_PWD, "roles": ["READONLY"]})
st, resp = req("POST", "/api/auth/login", {"username": OP_NAME, "password": OP_PWD}, token=None)
assert st == 200, f"OPERATOR 登录失败: {st} {resp}"
OP = resp["token"]
st, resp = req("POST", "/api/auth/login", {"username": RO_NAME, "password": OP_PWD}, token=None)
assert st == 200, f"READONLY 登录失败: {st} {resp}"
RO = resp["token"]
LIC1 = gen_license()
app_body = {"merchantName": "W3C商户一" + SUFFIX, "licenseNo": LIC1, "legalPerson": "王法人",
            "contact": gen_mobile(), "qualification": json.dumps({"docs": ["sandbox://license.pdf"]})}
app1 = ok("POST", "/api/merchant/applications", app_body)
A1 = app1["id"]
check("进件提交 → SUBMITTED", app1.get("status") == "SUBMITTED", str(app1))
check("执照号/法人/联系方式脱敏（明文不出现）",
      LIC1 not in json.dumps(app1) and "王法人" not in json.dumps(app1)
      and app_body["contact"] not in json.dumps(app1) and bool(app1.get("licenseNoMask")), str(app1))
app_dup = ok("POST", "/api/merchant/applications", app_body)
check("(MERCHANT_APP,licenseNo) 幂等防重", app_dup.get("id") == A1, str(app_dup))
tasks = ok("GET", "/api/merchant/review?status=PENDING")
check("SUBMITTED 生成 PENDING 审核任务", any(t.get("applicationId") == A1 for t in tasks), str(tasks)[:200])
st, resp = req("POST", f"/api/merchant/review/{A1}/approve", {"opinion": "越权尝试"}, token=RO)
check("READONLY 审核越权 403", st == 403, f"{st} {resp}")
approved = ok("POST", f"/api/merchant/review/{A1}/approve", {"opinion": "资质齐全"}, token=OP)
check("OPERATOR 审核通过 → APPROVED + 创建 MERCHANT 会员",
      approved.get("status") == "APPROVED" and approved.get("memberId") and approved.get("appId"), str(approved))
MER_ID, APP_ID, APP_SECRET = approved["memberId"], approved["appId"], approved.get("appSecret")
check("approve 一次性返回 appSecret（create 口径）", bool(APP_SECRET), str(approved)[:200])
got_app = ok("GET", f"/api/merchant/applications/{A1}")
check("查询出参 appSecret=null（E-1 口径）", got_app.get("appSecret") is None, str(got_app))
contract = ok("POST", "/api/merchant/contracts", {"applicationId": A1, "products": "BALANCE,CARD", "feeBps": 60})
check("签约 SIGNED + 费率落库", contract.get("status") == "SIGNED" and contract.get("feeBps") == 60, str(contract))
contract2 = ok("POST", "/api/merchant/contracts", {"applicationId": A1, "products": "BALANCE", "feeBps": 30})
check("一单一约幂等", contract2.get("id") == contract["id"], str(contract2))
SCARD = gen_card("622848")
sc = ok("POST", "/api/merchant/settle-cards", {"applicationId": A1, "cardNo": SCARD})
check("结算卡绑定 DEFAULT + 脱敏", sc.get("status") == "DEFAULT" and sc.get("cardNoMask", "").endswith(SCARD[-4:])
      and SCARD not in json.dumps(sc), str(sc))
sc2 = ok("POST", "/api/merchant/settle-cards", {"applicationId": A1, "cardNo": SCARD})
check("结算卡重复绑定幂等", sc2.get("id") == sc["id"])
st, resp = req("POST", "/api/merchant/settle-cards", {"applicationId": A1, "cardNo": SCARD[:-1] + "0"})
check("非法卡号结算卡拒绝", st == 400, f"{st} {resp}")
LIC2 = gen_license()
app2 = ok("POST", "/api/merchant/applications", {**app_body, "merchantName": "W3C商户二" + SUFFIX, "licenseNo": LIC2})
A2 = app2["id"]
rej = ok("POST", f"/api/merchant/review/{A2}/reject", {"reason": "资质模糊"}, token=OP)
check("驳回 → REJECTED + 原因留痕", rej.get("status") == "REJECTED" and rej.get("rejectReason") == "资质模糊", str(rej))
st, resp = req("POST", f"/api/merchant/review/{A2}/approve", {}, token=OP)
check("REJECTED 不可直接审核（状态机）", st == 409, f"{st} {resp}")
re2 = ok("POST", f"/api/merchant/applications/{A2}/resubmit", {**app_body, "licenseNo": LIC2,
                                                              "merchantName": "W3C商户二" + SUFFIX})
check("驳回重提 → SUBMITTED", re2.get("status") == "SUBMITTED" and re2.get("id") == A2, str(re2))
ok("POST", f"/api/merchant/review/{A2}/approve", {"opinion": "重提通过", "reviewer": "hacker-forged"}, token=OP)
LIC3 = gen_license()
app3 = ok("POST", "/api/merchant/applications", {**app_body, "merchantName": "W3C商户三" + SUFFIX, "licenseNo": LIC3})
A3 = app3["id"]
sup = ok("POST", f"/api/merchant/review/{A3}/supplement", {"opinion": "请补充开户许可证"}, token=OP)
check("补件 → SUPPLEMENT", sup.get("status") == "SUPPLEMENT", str(sup))
ok("POST", f"/api/merchant/applications/{A3}/resubmit", {**app_body, "licenseNo": LIC3,
                                                       "merchantName": "W3C商户三" + SUFFIX,
                                                       "qualification": json.dumps({"docs": ["sandbox://permit.pdf"]})})
ok("POST", f"/api/merchant/review/{A3}/approve", {"opinion": "补件齐全"}, token=OP)
all_tasks = ok("GET", "/api/merchant/review")
mine = [t for t in all_tasks if t.get("applicationId") in (A1, A2, A3)]
check("审核留痕：APPROVED/REJECTED/SUPPLEMENT 任务含 reviewer+opinion",
      {t["status"] for t in mine} >= {"APPROVED", "REJECTED", "SUPPLEMENT"}
      and all(t.get("reviewer") == OP_NAME for t in mine if t["status"] != "PENDING")
      and any(t.get("opinion") == "资质模糊" for t in mine), str(mine)[:300])
check("reviewer 恒取会话用户（body 伪造 hacker-forged 无效）",
      all(t.get("reviewer") != "hacker-forged" for t in mine), str(mine)[:200])
audit = ok("GET", "/api/audit?action=REVIEW_APPROVE&size=50")
check("审核动作审计落库（audit_log）", any(i.get("bizNo") == A1 for i in audit.get("items", [])),
      str(audit)[:200])
audit_kyc = ok("GET", "/api/audit?action=KYC_SUBMIT&size=50")
check("KYC 审计留痕（脱敏 detail）", any(i.get("bizNo") == KID for i in audit_kyc.get("items", []))
      and ID1 not in json.dumps(audit_kyc), str(audit_kyc)[:200])

print("== 4. M-5 C 端登录认证 ==")
U1_MOB, U2_MOB = gen_mobile(), gen_mobile()
PWD1 = "Passw0rd!" + SUFFIX
u1 = ok("POST", "/api/member/register", {"name": "W3C-u1-" + SUFFIX, "mobile": U1_MOB, "password": PWD1})
U1 = u1["id"]
check("C 端注册 → USER 会员（无 appSecret）", u1.get("type") == "USER" and not u1.get("appSecret"), str(u1))
st, resp = req("POST", "/api/member/register", {"name": "W3C-dup-" + SUFFIX, "mobile": U1_MOB, "password": PWD1})
check("手机号重复注册 409", st == 409, f"{st} {resp}")
for i in range(5):
    st, resp = req("POST", "/api/member/login", {"principal": U1_MOB, "password": "WrongPass!1"})
check("口令错 5 次均 401（统一错误不泄露存在性）", st == 401, f"{st} {resp}")
st, resp = req("POST", "/api/member/login", {"principal": U1_MOB, "password": "WrongPass!1"})
check("失败满 5 次触发锁定 429", st == 429, f"{st} {resp}")
st, resp = req("POST", "/api/member/login", {"principal": U1_MOB, "password": PWD1})
check("锁定期正确口令也拒绝", st == 429, f"{st} {resp}")
u2 = ok("POST", "/api/member/register", {"name": "W3C-u2-" + SUFFIX, "mobile": U2_MOB, "password": PWD1})
U2 = u2["id"]
sms = ok("POST", "/api/member/sms-code", {"mobile": U2_MOB})
check("短信沙箱验证码下发（手机号脱敏）", bool(sms.get("sandboxCode")) and U2_MOB not in json.dumps(sms), str(sms))
st, resp = req("POST", "/api/member/sms-code", {"mobile": U2_MOB})
check("60s 限频 429", st == 429, f"{st} {resp}")
tok = ok("POST", "/api/member/login", {"mobile": U2_MOB, "smsCode": sms["sandboxCode"]})
check("短信验证码登录成功", bool(tok.get("token")) and tok.get("memberId") == U2, str(tok))
sess = ok("GET", "/api/member/session?token=" + tok["token"])
check("会话查询 valid", sess.get("valid") is True and sess.get("memberId") == U2, str(sess))
st, resp = req("POST", "/api/member/login", {"mobile": U2_MOB, "smsCode": sms["sandboxCode"]})
check("验证码一次性（复用拒绝）", st == 401, f"{st} {resp}")
ok("POST", "/api/member/logout", {"token": tok["token"]})
sess2 = ok("GET", "/api/member/session?token=" + tok["token"])
check("登出后会话失效", sess2.get("valid") is False, str(sess2))
tok2 = ok("POST", "/api/member/login", {"principal": U2_MOB, "password": PWD1})
st, resp = req("POST", "/api/member/password", {"memberId": U2, "oldPassword": "WrongOld!1", "newPassword": "NewPass!234"})
check("改密旧口令错误 401", st == 401, f"{st} {resp}")
ok("POST", "/api/member/password", {"memberId": U2, "oldPassword": PWD1, "newPassword": "NewPass!234"})
sess3 = ok("GET", "/api/member/session?token=" + tok2["token"])
check("改密成功 → 旧会话全端失效", sess3.get("valid") is False, str(sess3))
st, resp = req("POST", "/api/member/login", {"principal": U2_MOB, "password": PWD1})
check("旧口令登录拒绝", st == 401, f"{st} {resp}")
tok3 = ok("POST", "/api/member/login", {"principal": U2_MOB, "password": "NewPass!234"})
check("新口令登录成功", bool(tok3.get("token")), str(tok3))
ok("POST", f"/api/members/{U2}/status", {"status": "FROZEN"})
sess4 = ok("GET", "/api/member/session?token=" + tok3["token"])
check("会员冻结 → 既有会话失效", sess4.get("valid") is False, str(sess4))
st, resp = req("POST", "/api/member/login", {"principal": U2_MOB, "password": "NewPass!234"})
check("冻结会员登录 403", st == 403, f"{st} {resp}")
ok("POST", f"/api/members/{U2}/status", {"status": "ACTIVE"})
check("解冻后可登录", bool(ok("POST", "/api/member/login", {"principal": U2_MOB, "password": "NewPass!234"}).get("token")))
audit_pwd = ok("GET", "/api/audit?action=MEMBER_PWD_CHANGE&size=50")
check("改密审计留痕（无口令明文）", any(i.get("bizNo") == U2 for i in audit_pwd.get("items", []))
      and PWD1 not in json.dumps(audit_pwd) and "NewPass!234" not in json.dumps(audit_pwd), str(audit_pwd)[:200])

print("== 5. A-1 多账户类型 / ride account.open 红线 ==")
multi = ok("POST", "/api/members", {"name": "W3C-multi-" + SUFFIX, "type": "USER"})
MID = multi["id"]
bal = ok("POST", "/api/accounts", {"memberId": MID})
BA = bal["id"]
dep = ok("POST", "/api/accounts/typed", {"memberId": MID, "accountType": "DEPOSIT"})
DA = dep["id"]
pend = ok("POST", "/api/accounts/typed", {"memberId": MID, "accountType": "PENDING_SETTLE"})
PA = pend["id"]
check("多类型独立开户（BALANCE/DEPOSIT/PENDING_SETTLE）",
      len({BA, DA, PA}) == 3 and dep.get("accountType") == "DEPOSIT" and pend.get("accountType") == "PENDING_SETTLE",
      f"{BA} {DA} {PA}")
dep2 = ok("POST", "/api/accounts/typed", {"memberId": MID, "accountType": "DEPOSIT"})
check("(memberId,accountType) 开户幂等", dep2.get("id") == DA, str(dep2))
st, resp = req("POST", "/api/accounts", {"memberId": MID})
check("既有 create 重复 BALANCE 仍 409（语义不变）", st == 409, f"{st} {resp}")
accs = ok("GET", f"/api/accounts?memberId={MID}")
check("list 返回多户且 BALANCE 恒首位（account.open 取 get(0) 不回归）",
      len(accs) == 3 and accs[0]["id"] == BA and accs[0]["accountType"] == "BALANCE", str(accs)[:200])
BIZ = "W3C" + SUFFIX
ok("POST", "/api/accounting/internal/topup", {"accountId": BA, "amount": 100000, "bizNo": BIZ + "T1"})
ok("POST", "/api/accounting/internal/topup", {"accountId": DA, "amount": 50000, "bizNo": BIZ + "T2"})
b1 = ok("GET", f"/api/accounting/accounts/{BA}/balance")
b2 = ok("GET", f"/api/accounting/accounts/{DA}/balance")
check("各类型独立核算（余额互不影响）", b1 == 100000 and b2 == 50000, f"{b1} {b2}")
st, resp = openapi_call("account.open", APP_ID, APP_SECRET, {"name": "W3C-open-" + SUFFIX, "type": "USER"})
check("account.open 懒开户（新商户 HMAC 验签链路可用）", st == 200 and resp.get("memberId") and resp.get("accountId"),
      f"{st} {resp}")
OPEN_M, OPEN_A = resp["memberId"], resp["accountId"]
st, resp2 = openapi_call("account.open", APP_ID, APP_SECRET, {"name": "W3C-open-" + SUFFIX, "type": "USER"})
check("account.open 幂等：重复返回同 memberId/accountId",
      st == 200 and resp2.get("memberId") == OPEN_M and resp2.get("accountId") == OPEN_A, f"{st} {resp2}")
st, bal_resp = openapi_call("account.balance", APP_ID, APP_SECRET, {"accountId": OPEN_A})
check("account.balance 返回裸数字 long（结构不变）", st == 200 and isinstance(bal_resp, int), f"{st} {bal_resp}")
open_accs = ok("GET", f"/api/accounts?memberId={OPEN_M}")
check("懒开户缺省 BALANCE 户", len(open_accs) == 1 and open_accs[0]["accountType"] == "BALANCE", str(open_accs))
trial_balanced("多账户充值后")

print("== 6. A-2 绑卡管理 ==")
BCARD1, BCARD2 = gen_card("622202"), gen_card("621700")
BMOB = gen_mobile()
bind_body = {"cardNo": BCARD1, "mobile": BMOB, "name": "张三", "idCardNo": ID1}
c1 = ok("POST", f"/api/accounts/{BA}/cards", bind_body)
C1 = c1["id"]
check("绑卡四要素通过 → BOUND + 首卡默认", c1.get("status") == "BOUND" and c1.get("isDefault") is True, str(c1))
check("卡号脱敏只留后四位（明文/哈希不出参）",
      c1.get("cardNoMask", "").endswith(BCARD1[-4:]) and BCARD1 not in json.dumps(c1)
      and hashlib.sha256(BCARD1.encode()).hexdigest() not in json.dumps(c1)
      and c1.get("bankName") == "工商银行", str(c1))
c1dup = ok("POST", f"/api/accounts/{BA}/cards", bind_body)
check("重复绑卡幂等（同卡返回原记录）", c1dup.get("id") == C1, str(c1dup))
st, resp = req("POST", f"/api/accounts/{BA}/cards", {**bind_body, "cardNo": BCARD1[:-1] + "0"})
check("Luhn 不通过拒绝", st == 400, f"{st} {resp}")
st, resp = req("POST", f"/api/accounts/{BA}/cards", {**bind_body, "idCardNo": BAD_ID})
check("身份证校验位不通过拒绝", st == 400, f"{st} {resp}")
c2 = ok("POST", f"/api/accounts/{BA}/cards", {**bind_body, "cardNo": BCARD2})
C2 = c2["id"]
check("第二张卡非默认 + BIN 归属", c2.get("isDefault") is False and c2.get("bankName") == "建设银行", str(c2))
d2 = ok("POST", f"/api/accounts/{BA}/cards/{C2}/default")
check("默认卡切换", d2.get("isDefault") is True, str(d2))
cards = ok("GET", f"/api/accounts/{BA}/cards")
check("一账户一默认卡", sum(1 for c in cards if c.get("isDefault")) == 1
      and next(c for c in cards if c["id"] == C1)["isDefault"] is False, str(cards)[:200])
check("卡列表全脱敏", BCARD1 not in json.dumps(cards) and BCARD2 not in json.dumps(cards))
u2card = ok("POST", f"/api/cards/{C2}/unbind")
check("解绑默认卡 → UNBOUND 留痕", u2card.get("status") == "UNBOUND" and u2card.get("unboundAt"), str(u2card))
cards2 = ok("GET", f"/api/accounts/{BA}/cards")
check("解绑后默认卡自动顺位", next(c for c in cards2 if c["id"] == C1).get("isDefault") is True, str(cards2)[:200])
ok("POST", f"/api/cards/{C1}/unbind")
rebind = ok("POST", f"/api/accounts/{BA}/cards", bind_body)
check("解绑后重绑幂等复用原行（新签约号）", rebind.get("id") == C1 and rebind.get("status") == "BOUND"
      and rebind.get("signNo") != c1.get("signNo"), str(rebind))

print("== 7. A-3 冻结细分 / 对应解冻 / 双人复核 ==")
frz_member = ok("POST", "/api/members", {"name": "W3C-frz-" + SUFFIX, "type": "USER"})
FA = ok("POST", "/api/accounts", {"memberId": frz_member["id"]})["id"]
ok("POST", "/api/accounting/internal/topup", {"accountId": FA, "amount": 100000, "bizNo": BIZ + "FT"})
f1 = ok("POST", f"/api/accounts/{FA}/freeze", {"amount": 60000, "reasonType": "RISK",
                                              "reason": "风控模型命中", "bizNo": BIZ + "F1"})
F1 = f1["id"]
check("RISK 金额冻结 → ACTIVE 冻结单", f1.get("status") == "ACTIVE" and f1.get("reasonType") == "RISK", str(f1))
f1dup = ok("POST", f"/api/accounts/{FA}/freeze", {"amount": 60000, "reasonType": "RISK", "bizNo": BIZ + "F1"})
check("冻结 bizNo 幂等", f1dup.get("id") == F1, str(f1dup))
st, resp = openapi_call("account.transfer", APP_ID, APP_SECRET,
                        {"fromAccountId": FA, "toAccountId": BA, "amount": 50000, "bizNo": BIZ + "X1"})
check("冻结后可用余额不足动账拒绝（真实闸门）", st != 200, f"{st} {resp}")
st, resp = openapi_call("account.transfer", APP_ID, APP_SECRET,
                        {"fromAccountId": FA, "toAccountId": BA, "amount": 30000, "bizNo": BIZ + "X2"})
check("可用余额内动账成功（available=balance−frozen）", st == 200, f"{st} {resp}")
st, resp = req("POST", f"/api/accounts/{FA}/unfreeze", {"freezeId": F1, "reasonType": "DISPUTE", "bizNo": BIZ + "U0"})
check("解冻原因不符拒绝", st == 400, f"{st} {resp}")
u1f = ok("POST", f"/api/accounts/{FA}/unfreeze", {"freezeId": F1, "reasonType": "RISK", "bizNo": BIZ + "U1"})
check("对应原因解冻 → RELEASED + 回执号", u1f.get("status") == "RELEASED" and u1f.get("releaseBizNo") == BIZ + "U1",
      str(u1f))
u1dup = ok("POST", f"/api/accounts/{FA}/unfreeze", {"freezeId": F1, "reasonType": "RISK", "bizNo": BIZ + "U1"})
check("解冻 bizNo 幂等", u1dup.get("status") == "RELEASED" and u1dup.get("releasedAt") == u1f.get("releasedAt"))
st, resp = openapi_call("account.transfer", APP_ID, APP_SECRET,
                        {"fromAccountId": FA, "toAccountId": BA, "amount": 50000, "bizNo": BIZ + "X3"})
check("解冻后动账恢复", st == 200, f"{st} {resp}")
fj = ok("POST", f"/api/accounts/{FA}/freeze", {"amount": 10000, "reasonType": "JUDICIAL",
                                              "reason": "司法协冻", "bizNo": BIZ + "FJ"})
FJ = fj["id"]
st, resp = req("POST", f"/api/accounts/{FA}/unfreeze", {"freezeId": FJ, "reasonType": "JUDICIAL", "bizNo": BIZ + "UJ0"})
check("司法解冻无复核人 403", st == 403, f"{st} {resp}")
st, resp = req("POST", f"/api/accounts/{FA}/unfreeze", {"freezeId": FJ, "reasonType": "JUDICIAL",
                                                       "authorizedBy": "admin", "bizNo": BIZ + "UJ1"})
check("司法解冻复核人=操作人 403（双人原则）", st == 403, f"{st} {resp}")
uj = ok("POST", f"/api/accounts/{FA}/unfreeze", {"freezeId": FJ, "reasonType": "JUDICIAL",
                                                "authorizedBy": OP_NAME, "bizNo": BIZ + "UJ2"})
check("司法解冻双人复核通过", uj.get("status") == "RELEASED" and uj.get("authorizedBy") == OP_NAME, str(uj))
for reason in ("PREAUTH", "DISPUTE"):
    fr = ok("POST", f"/api/accounts/{FA}/freeze", {"amount": 5000, "reasonType": reason, "bizNo": BIZ + "F" + reason})
    ur = ok("POST", f"/api/accounts/{FA}/unfreeze", {"accountId": FA, "reasonType": reason, "bizNo": BIZ + "U" + reason})
    check(f"{reason} 冻结/按原因定位解冻闭环", fr.get("status") == "ACTIVE" and ur.get("status") == "RELEASED"
          and ur.get("id") == fr["id"], f"{fr} {ur}")
st, resp = req("POST", f"/api/accounts/{FA}/freeze", {"amount": 999999999, "reasonType": "RISK", "bizNo": BIZ + "FBig"})
check("超可用余额冻结拒绝", st != 200, f"{st} {resp}")
freezes = ok("GET", f"/api/accounts/{FA}/freezes")
check("冻结单全程留痕（4 单全 RELEASED + 原因/回执）",
      len(freezes) == 4 and all(f["status"] == "RELEASED" for f in freezes)
      and {f["reasonType"] for f in freezes} == {"RISK", "JUDICIAL", "PREAUTH", "DISPUTE"}, str(freezes)[:300])
trial_balanced("金额冻结/解冻全程")
sf = ok("POST", f"/api/accounts/{FA}/status-ex", {"status": "FROZEN", "freezeType": "FULL", "reason": "风控全额"})
check("状态冻结 FULL（兼容 FROZEN）", sf.get("status") == "FROZEN" and sf.get("freezeType") == "FULL", str(sf))
st, resp = req("POST", "/api/accounting/internal/topup", {"accountId": FA, "amount": 100, "bizNo": BIZ + "FT2"})
check("状态冻结期间入账拒绝（账务闸门）", st != 200, f"{st} {resp}")
ok("POST", f"/api/accounts/{FA}/status", {"status": "ACTIVE"})
ok("POST", "/api/accounting/internal/topup", {"accountId": FA, "amount": 100, "bizNo": BIZ + "FT3"})
check("解冻后入账恢复", ok("GET", f"/api/accounting/accounts/{FA}/balance") == 20100,
      str(ok("GET", f"/api/accounting/accounts/{FA}/balance")))
sj = ok("POST", f"/api/accounts/{FA}/status-ex", {"status": "FROZEN", "freezeType": "JUDICIAL", "reason": "司法"})
check("司法状态冻结", sj.get("freezeType") == "JUDICIAL", str(sj))
st, resp = req("POST", f"/api/accounts/{FA}/status", {"status": "ACTIVE"})
check("司法冻结旧接口解冻拒绝（须双人复核）", st == 403, f"{st} {resp}")
sj2 = ok("POST", f"/api/accounts/{FA}/status-ex", {"status": "ACTIVE", "authorizedBy": OP_NAME})
check("司法状态冻结双人解冻", sj2.get("status") == "ACTIVE" and sj2.get("freezeType") == "NONE", str(sj2))
st, resp = req("GET", f"/api/accounts/{FA}/freezes?status=ACTIVE")
check("ACTIVE 冻结单清零", st == 200 and resp == [], f"{st} {resp}")
trial_balanced("状态冻结/解冻后")

print("== 8. E-1 脱敏不回退 / 控制台语义不变 ==")
members = ok("GET", "/api/members")
check("list 全量 appSecret/graceSecrets/certPublicKey 脱敏",
      all(not m.get("appSecret") and not m.get("graceSecrets") and not m.get("certPublicKey") for m in members),
      "存在泄密行")
got_m = ok("GET", f"/api/members/{MER_ID}")
check("get 单查脱敏（进件商户）", not got_m.get("appSecret") and not got_m.get("certPublicKey"), str(got_m))
st, resp = req("POST", "/api/auth/login", {"username": "admin", "password": ADMIN_PASS}, token=None)
check("控制台 /api/auth/login 语义不变（200+token+user）",
      st == 200 and resp.get("token") and resp.get("user", {}).get("username") == "admin", f"{st} {resp}")
st, resp = openapi_call("account.open", APP_ID, APP_SECRET, {"name": "W3C-open2-" + SUFFIX, "type": "USER"})
check("getByAppId 内部验签链路持续可用（E-1 后）", st == 200 and resp.get("accountId"), f"{st} {resp}")
st, resp = req("GET", "/api/members", token=None)
check("未登录 /api/members 仍 401（安全链不回退）", st == 401, f"{st} {resp}")

print()
print(f"verify-member-account: {passed} passed, {failed} failed (total {passed + failed})")
sys.exit(1 if failed else 0)