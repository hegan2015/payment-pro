#!/usr/bin/env python3
"""verify-notify-sign.py — S-3/N-1 通知签名 + HTTPS（商户验签）验证。

跑法: python3 verify-notify-sign.py   （payment-pro local/docker 栈需已启动，gateway 8080）
      PP_GATEWAY=http://127.0.0.1:8080 可覆盖网关地址。

本脚本内置「商户验签方」：用 create 返回的 appSecret 对 body+timestamp+nonce 做 HMAC-SHA256 比对，
并在脚本内用 threading HTTPServer 起本地回调接收 server（http + https 各一），nonce 去重防重放。

断言覆盖:
  - 商户 create 一次性返回 appSecret；
  - HMAC 签名通知 HTTP 送达 + 商户验签通过 + 签名三要素头 + DTO 补查 signType/signature；
  - 篡改 body / nonce → 验签失败（且接收方 HTTP 400 拒收）；
  - 重放同 nonce → 接收方 409 拒绝；新 nonce 正常 200；
  - HTTPS 回调送达（local 自签 config/payment-pro.p12，推送方信任 config/payment-pro-cert.pem）+ 验签通过；
  - 请求 RSA2 而 local 无平台私钥 → 降级 HMAC 并标记 signType，验签仍通过；
  - 密钥缺失（bogus appId）→ 通知留 PENDING 退避不丢 + lastError 告警标记 SIGN。

签名规范（与 NotifySigner.canonical 一致）: content = body + "\\n" + timestamp + "\\n" + nonce，
HMAC-SHA256(appSecret, content) 取 lowercase hex；头 X-Payment-Signature / X-Payment-Timestamp /
X-Payment-Nonce / X-Payment-Sign-Type。body 为收到的原始字节（勿重新序列化 JSON）。
"""
import hashlib
import hmac
import json
import os
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.abspath(__file__))
BASE = os.environ.get("PP_GATEWAY", "http://127.0.0.1:8080")

# ---- wave2 S-2 控制台登录：拿到 token 则 /api/** 带 Bearer；登录不可用则保持改造前行为 ----
import os as _os
TOKEN = None

def _login():
    global TOKEN
    try:
        _r = urllib.request.Request(BASE + "/api/auth/login",
                                    data=json.dumps({"username": "admin",
                                                     "password": _os.environ.get("PP_ADMIN_PASSWORD", "admin123")}).encode(),
                                    method="POST", headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(_r, timeout=10) as _resp:
            TOKEN = json.loads(_resp.read().decode()).get("token")
    except Exception:
        TOKEN = None

_login()
# ---- end wave2 S-2 ----

SUFFIX = str(int(time.time()))
passed = failed = 0


def _req_impl(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Accept", "application/json")
    if data is not None:
        r.add_header("Content-Type", "application/json")
    if TOKEN and path.startswith("/api/") and path != "/api/auth/login":
        r.add_header("Authorization", "Bearer " + TOKEN)
    try:
        with urllib.request.urlopen(r, timeout=15) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode() or "{}")
        except Exception:
            return e.code, {}


def req(method, path, body=None, *args, **kwargs):
    st, resp = _req_impl(method, path, body, *args, **kwargs)
    if st == 401 and path.startswith("/api/") and path != "/api/auth/login":
        _login()  # 会话过期/全栈重启（local 内存会话）时重登一次
        if TOKEN:
            st, resp = _req_impl(method, path, body, *args, **kwargs)
    return st, resp


def ok(method, path, body=None):
    st, resp = req(method, path, body)
    assert st == 200, f"{method} {path} -> {st} {resp}"
    return resp


def check(name, cond):
    global passed, failed
    if cond:
        passed += 1
        print(f"  [PASS] {name}")
    else:
        failed += 1
        print(f"  [FAIL] {name}")


def merchant_verify(secret, body, ts, nonce, sig, stype="HMAC_SHA256"):
    """商户验签方：HMAC-SHA256 over body+timestamp+nonce。RSA2 需平台公钥证书（local 最小版降级 HMAC）。"""
    if stype != "HMAC_SHA256" or not sig:
        return False
    content = (body + "\n" + ts + "\n" + nonce).encode("utf-8")
    expected = hmac.new(secret.encode("utf-8"), content, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, sig)


class Receiver:
    """本地回调接收 server：捕获原始 body+签名头，验签 + nonce 去重。"""

    def __init__(self, secret):
        self.secret = secret
        self.captures = []
        self.seen = set()
        self.lock = threading.Lock()

    def handler(self):
        recv = self

        class H(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def _reply(self, code, msg):
                payload = msg.encode()
                self.send_response(code)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def do_POST(self):
                n = int(self.headers.get("Content-Length", 0))
                body = self.rfile.read(n).decode("utf-8")
                sig = self.headers.get("X-Payment-Signature")
                ts = self.headers.get("X-Payment-Timestamp")
                nonce = self.headers.get("X-Payment-Nonce")
                stype = self.headers.get("X-Payment-Sign-Type", "HMAC_SHA256")
                with recv.lock:
                    recv.captures.append({"body": body, "sig": sig, "ts": ts, "nonce": nonce, "stype": stype})
                    if not merchant_verify(recv.secret, body, ts, nonce, sig, stype):
                        self._reply(400, "bad signature")
                        return
                    if nonce in recv.seen:
                        self._reply(409, "replay rejected")
                        return
                    recv.seen.add(nonce)
                self._reply(200, "ok")

            def log_message(self, *a):
                pass

        return H

    def capture_for(self, marker):
        with self.lock:
            for c in reversed(self.captures):
                if marker in c["body"]:
                    return c
        return None


def start_http(secret):
    recv = Receiver(secret)
    srv = ThreadingHTTPServer(("127.0.0.1", 0), recv.handler())
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return recv, srv, srv.server_address[1]


def start_https(secret):
    """用 config/payment-pro.p12（推送方信任的同一自签证书）起 HTTPS 接收 server。"""
    p12 = os.path.join(ROOT, "config", "payment-pro.p12")
    if not os.path.exists(p12):
        return None
    tmp = tempfile.mkdtemp(prefix="notify-tls-")
    cert, key = os.path.join(tmp, "c.pem"), os.path.join(tmp, "k.pem")
    try:
        subprocess.run(["openssl", "pkcs12", "-in", p12, "-passin", "pass:changeit",
                        "-nokeys", "-clcerts", "-out", cert], check=True, capture_output=True)
        subprocess.run(["openssl", "pkcs12", "-in", p12, "-passin", "pass:changeit",
                        "-nocerts", "-nodes", "-out", key], check=True, capture_output=True)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(cert, key)
    except Exception as e:
        print(f"  [SKIP] HTTPS 证书准备失败（{e}），跳过 HTTPS 断言")
        return None
    recv = Receiver(secret)
    srv = ThreadingHTTPServer(("127.0.0.1", 0), recv.handler())
    srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return recv, srv, srv.server_address[1]


def send_notify(biz, callback, app_id, sign_type="HMAC_SHA256", amount=1234):
    body = {"event": "PAY_SUCCESS", "bizId": biz, "callbackUrl": callback,
            "appId": app_id, "signType": sign_type,
            "payload": {"bizId": biz, "amount": amount, "status": "SUCCESS"}}
    return ok("POST", "/api/notify/send", body)


def wait_status(notify_id, want, timeout=25):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = ok("GET", f"/api/notify/{notify_id}")
        if last.get("status") in want:
            return last
        time.sleep(1)
    return last


def post_raw(port, body, ts, nonce, sig, stype="HMAC_SHA256", tls=False):
    """直接向接收方推送（模拟商户收到/重放），返回 HTTP 状态。"""
    url = f"{'https' if tls else 'http'}://localhost:{port}/cb"
    r = urllib.request.Request(url, data=body.encode("utf-8"), method="POST")
    r.add_header("Content-Type", "application/json")
    r.add_header("X-Payment-Signature", sig)
    r.add_header("X-Payment-Timestamp", ts)
    r.add_header("X-Payment-Nonce", nonce)
    r.add_header("X-Payment-Sign-Type", stype)
    ctx = ssl.create_default_context(cafile=os.path.join(ROOT, "config", "payment-pro-cert.pem")) if tls else None
    try:
        with urllib.request.urlopen(r, context=ctx, timeout=10) as resp:
            return resp.status
    except urllib.error.HTTPError as e:
        return e.code


print("== 1. 商户密钥 ==")
merchant = ok("POST", "/api/members", {"name": f"通知商户{SUFFIX}", "type": "MERCHANT"})
secret = merchant.get("appSecret")
app_id = merchant.get("appId")
check("create 一次性返回 appId/appSecret", bool(app_id) and bool(secret))

recv, srv, port = start_http(secret)
callback = f"http://localhost:{port}/cb"

print("== 2. HMAC 签名送达 + 商户验签 ==")
biz1 = f"NSIGN{SUFFIX}A"
n1 = send_notify(biz1, callback, app_id)
check("通知登记初始 PENDING", n1.get("status") == "PENDING")
d1 = wait_status(n1["id"], {"DELIVERED", "FAILED"})
check("HTTP 回调送达 DELIVERED", d1.get("status") == "DELIVERED")
cap1 = recv.capture_for(biz1)
check("推送携带 X-Payment-Signature 头", bool(cap1 and cap1["sig"]))
check("推送携带 timestamp/nonce 头", bool(cap1 and cap1["ts"] and cap1["nonce"]))
check("商户 HMAC 验签通过（正常）", bool(cap1) and merchant_verify(secret, cap1["body"], cap1["ts"], cap1["nonce"], cap1["sig"], cap1["stype"]))
check("签名算法头 HMAC_SHA256", bool(cap1) and cap1["stype"] == "HMAC_SHA256")
check("DTO 补查回传 signType/signature", d1.get("signType") == "HMAC_SHA256" and bool(d1.get("signature")))

print("== 3. 防篡改 ==")
tampered = cap1["body"].replace("1234", "9999") if cap1 else ""
check("篡改 body 验签失败", bool(cap1) and not merchant_verify(secret, tampered, cap1["ts"], cap1["nonce"], cap1["sig"]))
check("篡改 nonce 验签失败", bool(cap1) and not merchant_verify(secret, cap1["body"], cap1["ts"], cap1["nonce"] + "x", cap1["sig"]))
st_tamper = post_raw(port, tampered, cap1["ts"], cap1["nonce"] + "t", cap1["sig"]) if cap1 else 0
check("篡改 body 接收方 HTTP 400 拒收", st_tamper == 400)

print("== 4. 防重放（nonce 去重） ==")
st_replay = post_raw(port, cap1["body"], cap1["ts"], cap1["nonce"], cap1["sig"]) if cap1 else 0
check("重放同 nonce 被拒 409", st_replay == 409)
fresh_nonce = uuid.uuid4().hex
fresh_ts = str(int(time.time() * 1000))
fresh_body = json.dumps({"bizId": f"NSIGN{SUFFIX}FRESH", "amount": 1})
fresh_sig = hmac.new(secret.encode(), (fresh_body + "\n" + fresh_ts + "\n" + fresh_nonce).encode(), hashlib.sha256).hexdigest()
check("新 nonce 正常接收 200", post_raw(port, fresh_body, fresh_ts, fresh_nonce, fresh_sig) == 200)

print("== 5. HTTPS 回调送达 ==")
https = start_https(secret)
if https:
    hrecv, hsrv, hport = https
    biz2 = f"NSIGN{SUFFIX}B"
    n2 = send_notify(biz2, f"https://localhost:{hport}/cb", app_id)
    d2 = wait_status(n2["id"], {"DELIVERED", "FAILED"})
    check("HTTPS 回调送达 DELIVERED", d2.get("status") == "DELIVERED")
    cap2 = hrecv.capture_for(biz2)
    check("HTTPS 推送商户验签通过", bool(cap2) and merchant_verify(secret, cap2["body"], cap2["ts"], cap2["nonce"], cap2["sig"], cap2["stype"]))
    hsrv.shutdown()
else:
    check("HTTPS 回调送达 DELIVERED", False)
    check("HTTPS 推送商户验签通过", False)

print("== 6. RSA2 local 降级 HMAC ==")
biz3 = f"NSIGN{SUFFIX}C"
n3 = send_notify(biz3, callback, app_id, sign_type="RSA2")
d3 = wait_status(n3["id"], {"DELIVERED", "FAILED"})
check("请求 RSA2 送达（降级 HMAC）", d3.get("status") == "DELIVERED")
check("local 无平台私钥降级标记 signType=HMAC_SHA256", d3.get("signType") == "HMAC_SHA256")
cap3 = recv.capture_for(biz3)
check("降级后商户验签仍通过", bool(cap3) and merchant_verify(secret, cap3["body"], cap3["ts"], cap3["nonce"], cap3["sig"], cap3["stype"]))

print("== 7. 密钥缺失留 PENDING 不丢 ==")
biz4 = f"NSIGN{SUFFIX}D"
n4 = send_notify(biz4, callback, "appNONEXISTENT" + SUFFIX)
time.sleep(6)
d4 = ok("GET", f"/api/notify/{n4['id']}")
check("bogus appId 通知留 PENDING（不丢）", d4.get("status") == "PENDING")
check("密钥缺失 lastError 告警标记 SIGN", bool(d4.get("lastError")) and d4["lastError"].startswith("SIGN"))

srv.shutdown()
print(f"\n结果: {passed} 通过 / {failed} 失败")
sys.exit(1 if failed else 0)