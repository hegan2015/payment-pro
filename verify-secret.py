#!/usr/bin/env python3
"""verify-secret.py — wave2 M-1/S-1 密钥轮换/多密钥/证书到期 + SecretProvider 验证。
跑法: python3 verify-secret.py  （payment-pro local/docker 栈需已启动，gateway 8080；需 openssl）

覆盖:
  - SECTEST 商户 create 一次性返回明文；版本列表仅指纹；list/get 脱敏（E-1 不回退）；
  - OpenAPI HMAC：v1 可验签；rotate(grace=600) 后新旧并存均可验签；
  - rotate(grace=2)：宽限期内 v2 可验签，期后（验签时点）v2 拒绝、v3 通、v1 仍在 600s 宽限内通；
  - 证书：openssl 自签 5 天 PEM 上传解析（SN/公钥/有效期），到期查询/扫描告警
    （ERROR 日志 logs/member.log + 18081 prometheus payment_member_cert_alert / cert_expiring）；
  - RSA2：openssl dgst -sha256 -sign 商户私钥签名 → openapi sign_type=RSA2 验签通；错 key 拒；
  - /api/secrets/status：Vault 配置缺失自动降级 local（S-1，零依赖不报错）；
  - 轮换后 notify 回调链用 ACTIVE 新密钥签名送达且商户验签通过（旧密钥验不过）。
测试数据独立前缀 SECTEST + uuid，可重复跑。
"""
import base64
import hashlib
import hmac
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BASE = os.environ.get("PP_GATEWAY", "http://127.0.0.1:8080")
ROOT = os.path.dirname(os.path.abspath(__file__))
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


def req(method, path, body=None, token=None, base=None, step=None, accept="application/json"):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request((base or BASE) + path, data=data, method=method)
    r.add_header("Accept", accept)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    if step:
        r.add_header("X-Step-Up-Token", step)
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
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


st, resp = req("POST", "/api/auth/login", {"username": "admin", "password": ADMIN_PASS})
assert st == 200 and resp.get("token"), f"admin 登录失败: {st} {resp}"
ADMIN = resp["token"]


def ok(method, path, body=None):
    st, resp = req(method, path, body, token=ADMIN)
    assert st == 200, f"{method} {path} 期望 200 实际 {st}: {resp}"
    return resp


def step_up():
    st, resp = req("POST", "/api/security/step-up",
                   {"username": "admin", "password": ADMIN_PASS}, token=ADMIN)
    assert st == 200 and resp.get("token"), f"step-up 颁发失败: {st} {resp}"
    return resp["token"]


def sensitive_ok(method, path, body=None):
    """S-7 敏感操作（密钥轮换）需二次认证头。"""
    st, resp = req(method, path, body, token=ADMIN, step=step_up())
    assert st == 200, f"{method} {path} 期望 200 实际 {st}: {resp}"
    return resp


def canonical(params):
    items = sorted((k, v) for k, v in params.items() if v is not None and k != "sign")
    return "&".join(f"{k}={v}" for k, v in items)


def openapi_call(endpoint, secret, extra=None, sign_type=None, rsa_key=None):
    params = {"appId": APP_ID, "timestamp": int(time.time() * 1000)}
    params.update(extra or {})
    if sign_type:
        params["sign_type"] = sign_type
    data = canonical(params)
    if rsa_key:
        sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", rsa_key],
                             input=data.encode(), capture_output=True, check=True).stdout
        params["sign"] = base64.b64encode(sig).decode()
    else:
        params["sign"] = hmac.new(secret.encode(), data.encode(), hashlib.sha256).hexdigest()
    return req("POST", "/openapi/" + endpoint, params)  # 无 Bearer：HMAC/RSA2 独立链


print("== 1. 商户创建 / 版本化 / 脱敏 ==")
m = ok("POST", "/api/members", {"name": "SECTEST-mer-" + SUFFIX, "type": "MERCHANT"})
MID, APP_ID, V1 = m["id"], m.get("appId"), m.get("appSecret")
check("create 一次性返回 appSecret + secretVersion=1", bool(V1) and m.get("secretVersion") == 1, str(m)[:200])
vers = ok("GET", f"/api/members/{MID}/secrets")
check("版本列表 v1 ACTIVE 含指纹", len(vers) == 1 and vers[0]["status"] == "ACTIVE" and vers[0]["version"] == 1
      and bool(vers[0].get("fingerprint")), str(vers))
check("版本列表不含明文/密文", V1 not in json.dumps(vers) and "secretCipher" not in json.dumps(vers))
check("指纹=SHA-256 前 16hex", vers[0]["fingerprint"] == hashlib.sha256(V1.encode()).hexdigest()[:16],
      vers[0].get("fingerprint", ""))
listed = next(x for x in ok("GET", "/api/members") if x["id"] == MID)
got = ok("GET", f"/api/members/{MID}")
check("E-1 不回退：list/get appSecret 脱敏", not listed.get("appSecret") and not got.get("appSecret"))
st, resp = openapi_call("account.open", V1, {"name": "SECTEST-open-" + SUFFIX, "type": "USER"})
check("OpenAPI v1 HMAC 验签通过（无 Bearer）", st == 200 and resp.get("accountId"), f"{st} {resp}")
ACC = (resp or {}).get("accountId")

print("== 2. 轮换 grace=600：新旧并存验签 ==")
r2 = sensitive_ok("POST", f"/api/members/{MID}/secrets/rotate", {"graceSeconds": 600})
V2 = r2.get("appSecret")
check("rotate 返回新明文 v2（≠v1）", bool(V2) and V2 != V1 and r2.get("secretVersion") == 2, str(r2)[:200])
st, _ = openapi_call("account.balance", V1, {"accountId": ACC})
check("宽限期内旧密钥 v1 验签通过", st == 200, str(st))
st, _ = openapi_call("account.balance", V2, {"accountId": ACC})
check("新密钥 v2 验签通过", st == 200, str(st))
vers = ok("GET", f"/api/members/{MID}/secrets")
v1row = next(v for v in vers if v["version"] == 1)
check("v1 RETIRED 且 graceUntil≈now+600s", v1row["status"] == "RETIRED" and v1row.get("graceUntil")
      and 500_000 < v1row["graceUntil"] - time.time() * 1000 <= 600_000, str(v1row))
check("轮换后版本列表仍无明文", V1 not in json.dumps(vers) and V2 not in json.dumps(vers))

print("== 3. 再轮换 grace=2：期后即时拒绝（验签时点） ==")
r3 = sensitive_ok("POST", f"/api/members/{MID}/secrets/rotate", {"graceSeconds": 2})
V3 = r3.get("appSecret")
check("rotate 返回 v3", bool(V3) and r3.get("secretVersion") == 3, str(r3)[:150])
st, _ = openapi_call("account.balance", V2, {"accountId": ACC})
check("2s 宽限内 v2 仍可验签", st == 200, str(st))
time.sleep(3)
st, resp = openapi_call("account.balance", V2, {"accountId": ACC})
check("宽限期后 v2 拒绝 400（缓存 TTL 内也即时生效）", st == 400, f"{st} {resp}")
st, _ = openapi_call("account.balance", V3, {"accountId": ACC})
check("ACTIVE v3 验签通过", st == 200, str(st))
st, _ = openapi_call("account.balance", V1, {"accountId": ACC})
check("v1 仍在 600s 宽限内可验签", st == 200, str(st))
st, _ = openapi_call("account.balance", "wrong-secret-" + SUFFIX, {"accountId": ACC})
check("错误密钥 400", st == 400, str(st))

print("== 4. 证书上传 / 到期告警 ==")
tmp = tempfile.mkdtemp(prefix="sectest-")
key1, cert1, key2 = (os.path.join(tmp, n) for n in ("k1.pem", "c1.pem", "k2.pem"))
subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-keyout", key1, "-out", cert1,
                "-days", "5", "-nodes", "-subj", "/CN=SECTEST-" + SUFFIX], capture_output=True, check=True)
subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-keyout", key2, "-out", os.path.join(tmp, "c2.pem"),
                "-days", "5", "-nodes", "-subj", "/CN=SECTEST-other-" + SUFFIX], capture_output=True, check=True)
pem = open(cert1).read()
c = ok("POST", f"/api/members/{MID}/certs", {"certPem": pem})
check("证书上传解析 SN/公钥/有效期", bool(c.get("certSn")) and bool(c.get("publicKey"))
      and c.get("notAfter") and 0 < (c.get("daysLeft") or 0) <= 5, str(c)[:200])
certs = ok("GET", f"/api/members/{MID}/certs")
check("证书列表可查且不含 PEM 原文", len(certs) >= 1 and "BEGIN CERTIFICATE" not in json.dumps(certs))
exp = ok("GET", "/api/certs/expiring?days=30")
check("到期查询含本商户（5 天 < 30 天阈值）", any(x.get("memberId") == MID for x in exp), str(exp)[:200])
alerts = ok("POST", "/api/certs/scan?days=30")
check("扫描告警含本商户", any(x.get("memberId") == MID for x in alerts), str(alerts)[:200])
time.sleep(1)
logtext = ""
for lp in (os.path.join(ROOT, "logs", "member.log"),):
    if os.path.exists(lp):
        with open(lp, errors="ignore") as f:
            f.seek(max(0, os.path.getsize(lp) - 200_000))
            logtext = f.read()
check("member.log 含 ERROR 证书到期告警（memberId）", f"证书到期告警 memberId={MID}" in logtext)
st, prom = req("GET", "/actuator/prometheus", base="http://127.0.0.1:18081", accept="text/plain")
prom = prom if isinstance(prom, str) else json.dumps(prom)
check("prometheus payment_member_cert_alert 计数", st == 200 and "payment_member_cert_alert" in prom)
check("prometheus payment_member_cert_expiring gauge", "payment_member_cert_expiring" in prom)

print("== 5. RSA2 验签（商户证书私钥） ==")
st, resp = openapi_call("account.balance", None, {"accountId": ACC}, sign_type="RSA2", rsa_key=key1)
check("RSA2 正确私钥签名验签通过", st == 200, f"{st} {resp}")
st, resp = openapi_call("account.balance", None, {"accountId": ACC}, sign_type="RSA2", rsa_key=key2)
check("RSA2 错误私钥签名拒绝 400", st == 400, f"{st} {resp}")
st, _ = openapi_call("account.balance", V3, {"accountId": ACC})
check("上传证书后 HMAC 链不受影响", st == 200, str(st))

print("== 6. SecretProvider 状态（S-1 Vault 降级） ==")
status = ok("GET", "/api/secrets/status")
provider = status.get("provider")
check("provider 为 Vault/Local/Kms 之一", provider in ("VaultSecretProvider", "LocalFileSecretProvider", "KmsSecretProvider"),
      str(status))
if provider == "VaultSecretProvider" and not os.environ.get("VAULT_ADDR"):
    check("Vault 配置缺失自动降级 local（不报错）", status.get("degradedToLocal") is True, str(status))
else:
    check("local/已配置 Vault 状态自洽", provider == "LocalFileSecretProvider" or status.get("vaultEnabled"), str(status))

print("== 7. 轮换后 notify 链用 ACTIVE 新密钥签名 ==")
captures = []


class H(BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(n).decode()
        captures.append({"body": body, "sig": self.headers.get("X-Payment-Signature"),
                         "ts": self.headers.get("X-Payment-Timestamp"), "nonce": self.headers.get("X-Payment-Nonce")})
        payload = b"ok"
        self.send_response(200)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *a):
        pass


srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
threading.Thread(target=srv.serve_forever, daemon=True).start()
callback = f"http://127.0.0.1:{srv.server_address[1]}/cb"
biz = "SECTEST-NOTIFY-" + SUFFIX
n = ok("POST", "/api/notify/send", {"event": "PAY_SUCCESS", "bizId": biz, "callbackUrl": callback,
                                  "appId": APP_ID, "payload": {"bizId": biz, "amount": 1, "status": "SUCCESS"}})
deadline = time.time() + 25
final = None
while time.time() < deadline:
    final = ok("GET", f"/api/notify/{n['id']}")
    if final.get("status") in ("DELIVERED", "FAILED", "DEAD"):
        break
    time.sleep(1)
check("轮换后通知 DELIVERED", final and final.get("status") == "DELIVERED", str(final)[:200])
cap = next((x for x in captures if biz in x["body"]), None)


def notify_verify(secret, cap):
    content = (cap["body"] + "\n" + cap["ts"] + "\n" + cap["nonce"]).encode()
    return hmac.compare_digest(hmac.new(secret.encode(), content, hashlib.sha256).hexdigest(), cap["sig"] or "")


check("回调用 ACTIVE v3 密钥验签通过", bool(cap) and notify_verify(V3, cap), str(cap)[:200])
check("旧 v1 密钥验不过（签名已切换 ACTIVE）", bool(cap) and not notify_verify(V1, cap))
srv.shutdown()

print()
print(f"verify-secret: {passed} passed, {failed} failed (共 {passed + failed} 项)")
sys.exit(1 if failed else 0)