#!/usr/bin/env python3
"""verify-notify-gw.py — P1-H 泳道五卡验收（N-3 / N-4 / G-2 / G-3 / I-9）。

跑法: python3 verify-notify-gw.py
      PP_PROFILE=docker（默认）| local    PP_GATEWAY=http://127.0.0.1:8080
      PP_ADMIN_PASSWORD（默认 admin123）

覆盖:
  ① N-3 多事件类型：PAY_SUCCESS/REFUND_SUCCESS body **逐字节不变**（无 envelope），
     新事件 SHARE_SUCCESS/SHARE_BACK/SETTLE_BATCH/SIGN_SUCCESS/SIGN_CANCEL/DISPUTE_OPENED
     统一 envelope {event,bizId,timestamp,payload} + 同一套 HMAC 签名 + event:bizId 幂等 + 模板校验；
  ② N-4 查询分页脱敏（无密钥、签名仅预览、callbackUrl 去查询串）+ 既有 records/get 口径不变；
  ③ N-4 重发：需 step-up（无 token 403）、幂等（同 requestId 不重复重置）、
     **不重复推送已送达**、FAILED 重推送达、批量重发、商户 OpenAPI 归属隔离；
  ④ G-2 Swagger：/v3/api-docs 分组（console=/api/**、merchant=/openapi/**）、Swagger UI 可访问、
     免鉴权、敏感 schema 属性剔除、无密钥样示例值；
  ⑤ G-3 统一错误体 {error,code,traceId} + 码表 additive + **既有成功结构不变**
     （account.balance 裸数字 long、/api/health 裸 list、无堆栈/下游地址泄漏）；
  ⑥ I-9 双网关实例无状态：会话/登录限流/商户缓存/nonce/step-up token 全共享（Redis）；
  ⑦ I-9 灰度：header 强制 + 权重稳定哈希分流 + 共享状态 + 一键回滚 + 目标宕机 fail-open；
  ⑧ I-9 多实例调度不重复：第二个 notify 实例并跑，ShedLock 保证每条通知**恰好送达一次**；
  ⑨ ride 红线：/openapi/** 无 Bearer HMAC 仍通（含带无效 Bearer）、BALANCE 同步、http 回调仍可收。

脚本自管临时实例（gateway-2 @8081 / notify-2 @18190），**结束时必停并删 pid 文件**，
灰度配置结束时必回滚（enabled=false, weight=0），不留副作用。
"""
import hashlib
import hmac
import json
import os
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.abspath(__file__))
BASE = os.environ.get("PP_GATEWAY", "http://127.0.0.1:8080")
BASE2 = os.environ.get("PP_GATEWAY2", "http://127.0.0.1:8081")
PROFILE = os.environ.get("PP_PROFILE", "docker")
ADMIN_PW = os.environ.get("PP_ADMIN_PASSWORD", "admin123")
JAVA_HOME = os.environ.get("JAVA_HOME", "/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home")
JAVA_BIN = os.path.join(JAVA_HOME, "bin", "java") if os.path.isdir(JAVA_HOME) else "java"
DOCKER = shutil.which("docker") or "/Users/hegan/.docker/bin/docker"
SUFFIX = str(int(time.time()))
TOKEN = None
passed = failed = 0
PROCS = []
RECEIVERS = []


# ---------------- 基础设施 ----------------

def load_docker_env():
    """读仓库外口令（绝不回显）。"""
    path = os.path.expanduser("~/.config/payment-pro/docker.env")
    env = {}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip().strip('"').strip("'")
    return env


DOCKER_ENV = load_docker_env()


def login(base=BASE):
    global TOKEN
    try:
        r = urllib.request.Request(base + "/api/auth/login",
                                   data=json.dumps({"username": "admin", "password": ADMIN_PW}).encode(),
                                   method="POST", headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(r, timeout=15) as resp:
            TOKEN = json.loads(resp.read().decode()).get("token")
    except Exception:
        TOKEN = None
    return TOKEN


def raw_req(method, url, body=None, token=None, headers=None, timeout=30):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    r.add_header("Accept", "application/json")
    if data is not None:
        r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        r.add_header(k, v)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            text = resp.read().decode()
            return resp.status, _parse(text), {k.lower(): v for k, v in resp.headers.items()}, text
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        return e.code, _parse(text), {k.lower(): v for k, v in e.headers.items()}, text


def _parse(text):
    try:
        return json.loads(text or "{}")
    except Exception:
        return text


def req(method, path, body=None, token=None, headers=None, base=BASE, timeout=30, relogin=True):
    st, resp, hdrs, text = raw_req(method, base + path, body, token, headers, timeout)
    if st == 401 and relogin and base == BASE and path.startswith("/api/") and path != "/api/auth/login":
        if login():
            st, resp, hdrs, text = raw_req(method, base + path, body, TOKEN, headers, timeout)
    return st, resp, hdrs, text


def ok(method, path, body=None, token=None, headers=None, base=BASE):
    st, resp, _, _ = req(method, path, body, token, headers, base)
    assert st == 200, f"{method} {path} -> {st} {str(resp)[:300]}"
    return resp


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  [PASS] {name}")
    else:
        failed += 1
        print(f"  [FAIL] {name} {detail}")


def step_up(base=BASE, token=None):
    st, resp, _, _ = req("POST", "/api/security/step-up", {"password": ADMIN_PW},
                         token=token or TOKEN, base=base, relogin=False)
    assert st == 200 and resp.get("token"), f"step-up 颁发失败 {st} {resp}"
    return resp["token"]


def sign_body(secret, params):
    """OpenAPI HMAC：TreeMap k=v&（剔除 sign、跳过 null），timestamp 毫秒。"""
    items = sorted((k, v) for k, v in params.items() if k != "sign" and v is not None)
    canonical = "&".join(f"{k}={v}" for k, v in items)
    return hmac.new(secret.encode(), canonical.encode(), hashlib.sha256).hexdigest()


def openapi(method, params, secret, app_id, base=BASE, nonce=None, bearer=True):
    body = dict(params)
    body["appId"] = app_id
    body["timestamp"] = int(time.time() * 1000)
    if nonce:
        body["nonce"] = nonce
    body["sign"] = sign_body(secret, body)
    # 红线：/openapi/** 免 Bearer——故意带一个无效 Bearer，仍必须验签通过
    headers = {"Authorization": "Bearer invalid-token-must-be-ignored"} if bearer else {}
    return req("POST", f"/openapi/{method}", body, headers=headers, base=base, relogin=False)


def mysql_query(sql, db="pp_notify"):
    env = dict(os.environ)
    env["MYSQL_PWD"] = DOCKER_ENV.get("MYSQL_PASSWORD", DOCKER_ENV.get("MYSQL_ROOT_PASSWORD", ""))
    user = DOCKER_ENV.get("MYSQL_USER", "root")
    cmd = [DOCKER, "exec", "-e", "MYSQL_PWD", "claim-shared-mysql",
           "mysql", f"-u{user}", "-N", "-B", db, "-e", sql]
    try:
        p = subprocess.run(cmd, env=env, capture_output=True, text=True, timeout=40)
        return p.stdout.strip() if p.returncode == 0 else None
    except Exception:
        return None


def redis_cmd(*args):
    try:
        p = subprocess.run([DOCKER, "exec", "claim-shared-redis", "redis-cli", *args],
                           capture_output=True, text=True, timeout=30)
        return p.stdout.strip() if p.returncode == 0 else None
    except Exception:
        return None


def pick_port(candidates):
    for p in candidates:
        s = socket.socket()
        try:
            s.bind(("127.0.0.1", p))
            s.close()
            return p
        except OSError:
            s.close()
    return free_port()


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


# ---------------- 商户回调接收方（http，验签 + nonce 去重 + 可切换拒收） ----------------

class Receiver:
    """verify=True：商户验签方（用商户 appSecret）；
    verify=False：模拟 ride-pro 回调留痕方——**不验签也收**（§0.7 红线：签名头只能 additive）。
    支付域自动通知不带 appId，走平台共享密钥签名，故 ride 侧不能用商户密钥验签。"""

    def __init__(self, secret, verify=True):
        self.secret = secret
        self.verify = verify
        self.captures = []
        self.seen = set()
        self.lock = threading.Lock()
        self.reject_paths = set()

    def handler(recv):
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
                body = self.rfile.read(n).decode()
                sig = self.headers.get("X-Payment-Signature")
                ts = self.headers.get("X-Payment-Timestamp")
                nonce = self.headers.get("X-Payment-Nonce")
                stype = self.headers.get("X-Payment-Sign-Type", "HMAC_SHA256")
                accepted = False
                with recv.lock:
                    if self.path in recv.reject_paths:
                        recv.captures.append({"body": body, "sig": sig, "ts": ts, "nonce": nonce,
                                              "stype": stype, "path": self.path, "accepted": False})
                        self._reply(500, "merchant temporarily unavailable")
                        return
                    content = (body + "\n" + (ts or "") + "\n" + (nonce or "")).encode()
                    expect = hmac.new(recv.secret.encode(), content, hashlib.sha256).hexdigest()
                    if recv.verify and (stype != "HMAC_SHA256" or not sig
                                        or not hmac.compare_digest(expect, sig)):
                        recv.captures.append({"body": body, "sig": sig, "ts": ts, "nonce": nonce,
                                              "stype": stype, "path": self.path, "accepted": False})
                        self._reply(400, "bad signature")
                        return
                    if nonce in recv.seen:
                        self._reply(409, "replay rejected")
                        return
                    recv.seen.add(nonce)
                    accepted = True
                    recv.captures.append({"body": body, "sig": sig, "ts": ts, "nonce": nonce,
                                          "stype": stype, "path": self.path, "accepted": True})
                self._reply(200, "ok")

            def log_message(self, *a):
                pass
        return H

    def start(self):
        srv = ThreadingHTTPServer(("127.0.0.1", 0), self.handler())
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        RECEIVERS.append(srv)
        return srv.server_address[1]

    def count_for(self, marker):
        with self.lock:
            return sum(1 for c in self.captures if marker in c["body"])

    def count_ok_for(self, marker):
        with self.lock:
            return sum(1 for c in self.captures if marker in c["body"] and c["accepted"])

    def capture_for(self, marker):
        with self.lock:
            for c in reversed(self.captures):
                if marker in c["body"] and c["accepted"]:
                    return c
        return None


def wait_status(notify_id, want, timeout=30, base=BASE):
    deadline = time.time() + timeout
    last = {}
    while time.time() < deadline:
        st, last, _, _ = req("GET", f"/api/notify/{notify_id}", base=base)
        if st == 200 and isinstance(last, dict) and last.get("status") in want:
            return last
        time.sleep(1)
    return last if isinstance(last, dict) else {}


def send_notify(event, biz, callback, app_id, payload, base=BASE):
    return req("POST", "/api/notify/send",
               {"event": event, "bizId": biz, "callbackUrl": callback, "appId": app_id,
                "signType": "HMAC_SHA256", "payload": payload}, base=base)


# ---------------- 临时实例管理 ----------------

def start_instance(name, module, env_extra, ready_url, timeout=180):
    jar = os.path.join(ROOT, module, "target", f"{module}-1.0.0.jar")
    if not os.path.exists(jar):
        print(f"  [SKIP] {name}: 缺 {jar}")
        return None
    env = dict(os.environ)
    if PROFILE == "docker":
        env.update(DOCKER_ENV)
    env.update({k: str(v) for k, v in env_extra.items()})
    os.makedirs(os.path.join(ROOT, "logs"), exist_ok=True)
    os.makedirs(os.path.join(ROOT, "run"), exist_ok=True)
    log = open(os.path.join(ROOT, "logs", f"{name}.log"), "ab")
    args = [JAVA_BIN]
    if PROFILE == "docker":
        args.append("-Dspring.profiles.active=docker")
    args += ["-jar", jar]
    proc = subprocess.Popen(args, env=env, stdout=log, stderr=log, cwd=ROOT)
    pidfile = os.path.join(ROOT, "run", f"{name}.pid")
    with open(pidfile, "w") as fh:
        fh.write(str(proc.pid))
    PROCS.append((name, proc, pidfile))
    deadline = time.time() + timeout
    while time.time() < deadline:
        if proc.poll() is not None:
            print(f"  [SKIP] {name} 启动即退出，见 logs/{name}.log")
            return None
        try:
            with urllib.request.urlopen(ready_url, timeout=3) as r:
                if r.status == 200:
                    print(f"  [INFO] {name} 就绪 pid={proc.pid} {ready_url}")
                    return proc
        except Exception:
            pass
        time.sleep(2)
    print(f"  [SKIP] {name} {timeout}s 内未就绪")
    return None


def stop_instances():
    for name, proc, pidfile in PROCS:
        try:
            if proc.poll() is None:
                proc.terminate()
                try:
                    proc.wait(timeout=30)
                except Exception:
                    proc.kill()
                    proc.wait(timeout=10)
            print(f"  [INFO] 临时实例 {name} 已停 pid={proc.pid}")
        except Exception as e:
            print(f"  [WARN] 停 {name} 异常: {e}")
        try:
            if os.path.exists(pidfile):
                os.remove(pidfile)
                print(f"  [INFO] 已删 pid 文件 run/{name}.pid")
        except Exception:
            pass
    PROCS.clear()


def cleanup():
    try:
        if TOKEN:
            req("POST", "/api/gray/rollback", {}, token=TOKEN, relogin=False)
    except Exception:
        pass
    stop_instances()
    for srv in RECEIVERS:
        try:
            srv.shutdown()
        except Exception:
            pass


# ================= 1. N-3 多事件类型 + 冻结 body 逐字节不变 =================

def section_events(app_id, secret, callback, recv):
    print("== 1. N-3 多事件类型通知（冻结 body 不变 + 新事件 envelope） ==")
    biz = "HGPAY" + SUFFIX
    legacy_payload = {"paymentId": "P" + SUFFIX, "orderId": "O" + SUFFIX, "amount": 1234,
                      "status": "SUCCESS", "bizId": biz}
    st, n, _, _ = send_notify("PAY_SUCCESS", biz, callback, app_id, legacy_payload)
    check("PAY_SUCCESS 登记 200", st == 200, f"{st} {n}")
    d = wait_status(n["id"], {"DELIVERED"})
    check("PAY_SUCCESS http 回调送达", d.get("status") == "DELIVERED", str(d)[:200])
    cap = recv.capture_for(biz)
    expect_raw = json.dumps(legacy_payload, separators=(",", ":"), ensure_ascii=False)
    check("PAY_SUCCESS body 逐字节不变（无 envelope、字段序不变）",
          bool(cap) and cap["body"] == expect_raw, f"got={cap and cap['body']} want={expect_raw}")
    check("PAY_SUCCESS body 键集合与 payload 完全一致",
          bool(cap) and set(json.loads(cap["body"]).keys()) == set(legacy_payload.keys()))

    biz2 = "HGREF" + SUFFIX
    refund_payload = {"paymentId": "P" + SUFFIX, "refundId": "R" + SUFFIX, "amount": 300, "bizId": biz2}
    st, n2, _, _ = send_notify("REFUND_SUCCESS", biz2, callback, app_id, refund_payload)
    d2 = wait_status(n2["id"], {"DELIVERED"})
    cap2 = recv.capture_for(biz2)
    check("REFUND_SUCCESS 送达且 body 逐字节不变",
          d2.get("status") == "DELIVERED" and bool(cap2)
          and cap2["body"] == json.dumps(refund_payload, separators=(",", ":"), ensure_ascii=False),
          f"{d2.get('status')} {cap2 and cap2['body']}")

    new_events = [
        ("SHARE_SUCCESS", {"paymentId": "P" + SUFFIX, "shareId": "SH" + SUFFIX, "amount": 400}),
        ("SHARE_BACK", {"shareId": "SH" + SUFFIX, "amount": 100}),
        ("SETTLE_BATCH", {"batchId": "SB" + SUFFIX, "amount": 900}),
        ("SIGN_SUCCESS", {"agreementNo": "AG" + SUFFIX, "memberId": "M" + SUFFIX}),
        ("SIGN_CANCEL", {"agreementNo": "AG" + SUFFIX}),
        ("DISPUTE_OPENED", {"disputeId": "D" + SUFFIX, "type": "NOTIFY_DEAD"}),
    ]
    ids = {}
    for event, payload in new_events:
        ebiz = f"HG{event}{SUFFIX}"
        st, rec, _, _ = send_notify(event, ebiz, callback, app_id, payload)
        if st != 200:
            check(f"{event} 登记 200", False, f"{st} {rec}")
            continue
        ids[event] = rec["id"]
        dd = wait_status(rec["id"], {"DELIVERED"})
        cc = recv.capture_for(ebiz)
        envelope_ok = sign_ok = False
        if cc:
            try:
                parsed = json.loads(cc["body"])
                envelope_ok = (list(parsed.keys()) == ["event", "bizId", "timestamp", "payload"]
                               and parsed["event"] == event and parsed["bizId"] == ebiz
                               and isinstance(parsed["timestamp"], int) and parsed["payload"] == payload)
            except Exception:
                envelope_ok = False
            content = (cc["body"] + "\n" + (cc["ts"] or "") + "\n" + (cc["nonce"] or "")).encode()
            sign_ok = hmac.compare_digest(
                hmac.new(secret.encode(), content, hashlib.sha256).hexdigest(), cc["sig"] or "")
        check(f"{event} 送达 + 统一 envelope", dd.get("status") == "DELIVERED" and envelope_ok,
              f"{dd.get('status')} {cc and cc['body'][:160]}")
        check(f"{event} 签名与冻结事件同一套机制（商户验签通过）", sign_ok)

    ebiz = f"HGSHARE_SUCCESS{SUFFIX}"
    st, again, _, _ = send_notify("SHARE_SUCCESS", ebiz, callback, app_id, new_events[0][1])
    time.sleep(4)
    check("新事件 event:bizId 幂等（返回同一 notifyId）",
          st == 200 and again.get("id") == ids.get("SHARE_SUCCESS"),
          f"{st} {again.get('id')} vs {ids.get('SHARE_SUCCESS')}")
    check("幂等重复登记不重复推送（回调恰好 1 次）", recv.count_for(ebiz) == 1, f"count={recv.count_for(ebiz)}")

    st, cross, _, _ = send_notify("SHARE_BACK", ebiz, callback, app_id, {"shareId": "SH" + SUFFIX, "amount": 1})
    check("不同事件类型同 bizId 独立去重（新记录）",
          st == 200 and cross.get("id") != ids.get("SHARE_SUCCESS"), f"{st} {cross}")
    if st == 200:
        wait_status(cross["id"], {"DELIVERED"})

    st, bad, _, _ = send_notify("SHARE_SUCCESS", "HGBAD" + SUFFIX, callback, app_id, {"paymentId": "P1"})
    check("新事件缺必填字段 → 400 A0400", st == 400 and bad.get("code") == "A0400", f"{st} {bad}")
    st, legacy_bad, _, _ = send_notify("PAY_SUCCESS", "HGLAX" + SUFFIX, callback, app_id, {})
    check("冻结事件保持既有宽松语义（空 payload 仍受理）", st == 200, f"{st} {legacy_bad}")

    catalog = ok("GET", "/api/notify/events")
    by_name = {c["event"]: c for c in catalog}
    check("事件目录含 8 种类型", len(catalog) >= 8, str(len(catalog)))
    check("PAY_SUCCESS/REFUND_SUCCESS 标记 legacy=true 且 envelope=false",
          by_name.get("PAY_SUCCESS", {}).get("legacy") is True
          and by_name["PAY_SUCCESS"].get("envelope") is False
          and by_name.get("REFUND_SUCCESS", {}).get("legacy") is True)
    check("新事件标记 legacy=false 且 envelope=true",
          all(by_name.get(e, {}).get("legacy") is False and by_name[e].get("envelope") is True
              for e, _ in new_events))
    return ids


# ================= 2. N-4 查询分页脱敏 =================

def section_query(app_id, secret, ids):
    print("== 2. N-4 通知查询分页 + 脱敏 ==")
    page1 = ok("GET", f"/api/notify/query?appId={app_id}&page=1&size=2")
    check("分页返回 total/page/size/records",
          all(k in page1 for k in ("total", "page", "size", "records")) and page1["page"] == 1 and page1["size"] == 2)
    check("size 生效（records ≤ 2）", len(page1["records"]) <= 2, str(len(page1["records"])))
    check("total ≥ 本次登记条数", page1["total"] >= 8, str(page1["total"]))
    page2 = ok("GET", f"/api/notify/query?appId={app_id}&page=2&size=2")
    ids1 = {r["id"] for r in page1["records"]}
    ids2 = {r["id"] for r in page2["records"]}
    check("翻页不重叠", not (ids1 & ids2), f"{ids1} {ids2}")

    only_share = ok("GET", f"/api/notify/query?appId={app_id}&event=SHARE_SUCCESS&size=50")
    check("按事件过滤", all(r["event"] == "SHARE_SUCCESS" for r in only_share["records"]) and only_share["total"] >= 1)
    delivered = ok("GET", f"/api/notify/query?appId={app_id}&status=DELIVERED&size=50")
    check("按状态过滤", all(r["status"] == "DELIVERED" for r in delivered["records"]))
    now = int(time.time() * 1000)
    window = ok("GET", f"/api/notify/query?appId={app_id}&from={now - 600000}&to={now + 60000}&size=50")
    check("按时间窗过滤", window["total"] >= 1
          and all(now - 600000 <= r["createdAt"] <= now + 60000 for r in window["records"]))
    future = ok("GET", f"/api/notify/query?appId={app_id}&from={now + 3600000}&size=50")
    check("未来时间窗为空", future["total"] == 0 and future["records"] == [])
    other = ok("GET", f"/api/notify/query?appId=appNOTEXIST{SUFFIX}&size=50")
    check("按商户隔离（他人 appId 查不到）", other["total"] == 0)

    st, resp, _, text = req("GET", f"/api/notify/query?appId={app_id}&size=50")
    check("查询响应不含商户 appSecret 明文", secret not in text)
    check("查询响应不含 secret/password/privateKey 字段",
          not any(k in text for k in ('"appSecret"', '"password"', '"privateKey"', '"graceSecrets"')))
    signed = [r for r in resp["records"] if r.get("signature")]
    check("签名值脱敏为预览（****+长度）", bool(signed) and all("****" in r["signature"] for r in signed),
          str(signed[:1]))
    check("callbackUrl 去查询串", all("?" not in (r.get("callbackUrl") or "") for r in resp["records"]))

    nid = ids.get("SHARE_SUCCESS")
    full = ok("GET", f"/api/notify/{nid}")
    check("既有 GET /api/notify/{id} 仍返回完整签名（口径不变）",
          bool(full.get("signature")) and "****" not in full["signature"])
    legacy = ok("GET", f"/api/notify/records?bizId=HGSHARE_SUCCESS{SUFFIX}")
    check("既有 /api/notify/records 仍可用", isinstance(legacy, list) and len(legacy) >= 1)


# ================= 3. N-4 重发（step-up + 幂等 + 不重复推送） =================

def section_resend(app_id, secret, recv, ids, failing_biz, failing_id):
    print("== 3. N-4 重发：step-up 闸门 + 幂等 + 不重复推送已送达 ==")
    delivered_id = ids.get("SHARE_SUCCESS")
    marker = f"HGSHARE_SUCCESS{SUFFIX}"
    before = recv.count_ok_for(marker)

    st, resp, _, _ = req("POST", f"/api/notify/{delivered_id}/resend", {"requestId": "RS" + SUFFIX})
    check("重发无 X-Step-Up-Token → 403 A0403", st == 403 and resp.get("code") == "A0403", f"{st} {resp}")
    st, resp, _, _ = req("POST", "/api/notify/resend/batch",
                         {"notifyIds": [delivered_id], "requestId": "RSB" + SUFFIX})
    check("批量重发无 step-up → 403", st == 403 and resp.get("code") == "A0403", f"{st} {resp}")
    st, resp, _, _ = req("POST", f"/api/notify/{delivered_id}/resend", {"requestId": "RS" + SUFFIX},
                         headers={"X-Step-Up-Token": "bogus-token"})
    check("伪造 step-up token → 403", st == 403, f"{st} {resp}")

    tk = step_up()
    st, r1, _, _ = req("POST", f"/api/notify/{delivered_id}/resend", {"requestId": "RS" + SUFFIX},
                       headers={"X-Step-Up-Token": tk})
    check("带 step-up 重发已送达记录 → 200", st == 200, f"{st} {r1}")
    check("已送达记录不重置（status 仍 DELIVERED）", r1.get("status") == "DELIVERED", str(r1)[:200])
    check("已送达记录 resendCount 不增（不重复推送）", (r1.get("resendCount") or 0) == 0, str(r1.get("resendCount")))
    time.sleep(4)
    check("已送达记录重发后送达次数不变", recv.count_ok_for(marker) == before,
          f"{before} -> {recv.count_ok_for(marker)}")

    dead = wait_status(failing_id, {"FAILED"}, timeout=120)
    check("失败回调重试耗尽置 FAILED", dead.get("status") == "FAILED", str(dead)[:200])
    recv.reject_paths.clear()
    tk = step_up()
    st, r2, _, _ = req("POST", f"/api/notify/{failing_id}/resend", {"requestId": "RSD" + SUFFIX},
                       headers={"X-Step-Up-Token": tk})
    check("FAILED 记录带 step-up 重发 → 200 且重置 PENDING",
          st == 200 and r2.get("status") == "PENDING", f"{st} {str(r2)[:200]}")
    check("重发计数 +1", (r2.get("resendCount") or 0) == 1, str(r2.get("resendCount")))
    d = wait_status(failing_id, {"DELIVERED"}, timeout=40)
    check("FAILED 重推后送达", d.get("status") == "DELIVERED", str(d)[:200])
    check("重发不产生新记录（仍同一 notifyId）", d.get("id") == failing_id)
    check("重推恰好一次成功送达", recv.count_ok_for(failing_biz) == 1, str(recv.count_ok_for(failing_biz)))

    tk = step_up()
    st, r3, _, _ = req("POST", f"/api/notify/{failing_id}/resend", {"requestId": "RSD" + SUFFIX},
                       headers={"X-Step-Up-Token": tk})
    check("同 requestId 重发幂等（resendCount 不再增）",
          st == 200 and (r3.get("resendCount") or 0) == 1, f"{st} {str(r3)[:200]}")
    st, r4, _, _ = req("POST", f"/api/notify/{failing_id}/resend", {"requestId": "RSD2" + SUFFIX},
                       headers={"X-Step-Up-Token": tk})
    check("已送达后换 requestId 重发仍跳过（不重复推送）",
          st == 200 and r4.get("status") == "DELIVERED" and (r4.get("resendCount") or 0) == 1, str(r4)[:200])

    tk = step_up()
    batch_ids = [ids["SHARE_BACK"], ids["SETTLE_BATCH"], failing_id]
    st, b1, _, _ = req("POST", "/api/notify/resend/batch",
                       {"notifyIds": batch_ids, "requestId": "RB" + SUFFIX},
                       headers={"X-Step-Up-Token": tk})
    check("批量重发 200 且 requested=3", st == 200 and b1.get("requested") == 3, f"{st} {str(b1)[:200]}")
    check("批量重发全部跳过已送达（resent=0, skipped=3）",
          b1.get("resent") == 0 and b1.get("skipped") == 3, str(b1)[:200])
    st, b2, _, _ = req("POST", "/api/notify/resend/batch",
                       {"notifyIds": batch_ids, "requestId": "RB" + SUFFIX},
                       headers={"X-Step-Up-Token": tk})
    check("批量重发同 requestId 幂等", st == 200 and b2.get("resent") == b1.get("resent"), str(b2)[:200])
    st, b3, _, _ = req("POST", "/api/notify/resend/batch", {"notifyIds": batch_ids},
                       headers={"X-Step-Up-Token": tk})
    check("批量重发缺 requestId → 400", st == 400, f"{st} {b3}")
    st, b4, _, _ = req("POST", "/api/notify/resend/batch",
                       {"notifyIds": ["N999999999"], "requestId": "RBX" + SUFFIX},
                       headers={"X-Step-Up-Token": tk})
    check("批量重发不存在记录 → 404", st == 404, f"{st} {b4}")

    st, q, _, _ = openapi("notify.query", {"event": "SHARE_SUCCESS", "size": 5}, secret, app_id)
    check("OpenAPI notify.query 免 Bearer HMAC 通", st == 200 and q.get("total", 0) >= 1, f"{st} {str(q)[:200]}")
    check("OpenAPI notify.query 强制本商户 appId",
          all(r.get("appId") == app_id for r in q.get("records", [])))
    st, q2, _, _ = openapi("notify.query", {"appId": "appOTHER" + SUFFIX, "size": 5}, secret, app_id)
    check("OpenAPI notify.query 忽略 body 伪造 appId",
          st == 200 and all(r.get("appId") == app_id for r in q2.get("records", [])))
    st, rr, _, _ = openapi("notify.resend", {"notifyId": delivered_id, "requestId": "OA" + SUFFIX}, secret, app_id)
    check("OpenAPI notify.resend 本商户记录 200", st == 200, f"{st} {str(rr)[:200]}")
    st, rr2, _, _ = openapi("notify.resend", {"notifyId": "N999999999", "requestId": "OA2" + SUFFIX}, secret, app_id)
    check("OpenAPI notify.resend 不存在 → 404", st == 404, f"{st} {rr2}")


# ================= 4. G-2 Swagger / OpenAPI 文档 =================

def section_docs(app_id, secret):
    print("== 4. G-2 Swagger/OpenAPI 文档（免鉴权 + 不泄敏感 schema） ==")
    st, cfg, _, _ = req("GET", "/v3/api-docs/swagger-config", relogin=False)
    group_names = set()
    for u in (cfg.get("urls", []) if isinstance(cfg, dict) else []):
        group_names.add(u.get("name") or str(u.get("url", "")).rsplit("/", 1)[-1])
    check("swagger-config 免鉴权 200 且列出两个分组",
          st == 200 and isinstance(cfg, dict) and group_names >= {"console", "merchant"},
          f"{st} {str(cfg)[:200]}")
    st, console, _, ctext = req("GET", "/v3/api-docs/console", relogin=False)
    check("console 分组文档免鉴权 200", st == 200 and isinstance(console, dict), f"{st}")
    check("OpenAPI 3.x 规范", str(console.get("openapi", "")).startswith("3."), str(console.get("openapi")))
    cpaths = list((console.get("paths") or {}).keys())
    check("console 分组只含 /api/**", bool(cpaths) and all(p.startswith("/api/") for p in cpaths), str(cpaths[:5]))
    check("console 含通知查询/重发端点",
          "/api/notify/query" in cpaths and "/api/notify/{id}/resend" in cpaths and "/api/notify/resend/batch" in cpaths,
          str([p for p in cpaths if "notify" in p]))
    check("console 含灰度与错误码端点", "/api/gray/status" in cpaths and "/api/errors" in cpaths)
    st, merchant, _, mtext = req("GET", "/v3/api-docs/merchant", relogin=False)
    mpaths = list((merchant.get("paths") or {}).keys())
    check("merchant 分组 200 且只含 /openapi/**",
          st == 200 and bool(mpaths) and all(p.startswith("/openapi/") for p in mpaths), str(mpaths[:5]))
    frozen = ["/openapi/account.open", "/openapi/fund.topup", "/openapi/fund.withdraw", "/openapi/account.transfer",
              "/openapi/account.balance", "/openapi/trade.create", "/openapi/trade.pay", "/openapi/trade.query"]
    check("merchant 分组含 8 个冻结方法", all(p in mpaths for p in frozen),
          str([p for p in frozen if p not in mpaths]))
    check("merchant 分组含新增 notify.query/notify.resend",
          "/openapi/notify.query" in mpaths and "/openapi/notify.resend" in mpaths)
    check("merchant 文档含 HMAC 验签说明", "HMAC-SHA256" in mtext and "TreeMap" in mtext)
    check("文档含错误码表（G-3 联动）", "A0429" in ctext and "B0003" in ctext and "A0428" in ctext)

    st, ui, _, uitext = req("GET", "/swagger-ui/index.html", relogin=False)
    check("Swagger UI 可访问（免鉴权）", st == 200 and "swagger" in uitext.lower(), f"{st}")
    st, _, _, _ = req("GET", "/swagger-ui.html", relogin=False)
    check("/swagger-ui.html 可达", st == 200, f"{st}")

    sensitive = {"appsecret", "password", "gracesecrets", "privatekey", "certprivatekey",
                 "secret", "secretkey", "token", "accesstoken", "oldpassword", "newpassword"}
    leaked = []
    for label, document in (("console", console), ("merchant", merchant)):
        for name, schema in ((document.get("components") or {}).get("schemas") or {}).items():
            for prop in (schema.get("properties") or {}):
                if prop.lower() in sensitive:
                    leaked.append(f"{label}:{name}.{prop}")
    check("文档 schema 剔除全部敏感属性", not leaked, str(leaked[:6]))
    check("文档不含本次商户 appSecret 明文", secret not in ctext and secret not in mtext)
    long_examples = []
    for document in (console, merchant):
        for name, schema in ((document.get("components") or {}).get("schemas") or {}).items():
            for prop, sub in (schema.get("properties") or {}).items():
                for key in ("example", "default"):
                    v = sub.get(key)
                    if isinstance(v, str) and len(v) >= 32 and all(c.isalnum() or c in "+/=_-" for c in v):
                        long_examples.append(f"{name}.{prop}.{key}")
    check("文档无密钥样长示例值", not long_examples, str(long_examples[:6]))


# ================= 5. G-3 统一错误体 + 既有成功结构不变 =================

def section_errors(app_id, secret):
    print("== 5. G-3 统一错误码/响应 + 既有成功结构不变 ==")
    st, resp, _, _ = raw_req("GET", BASE + "/api/payments")
    check("未登录 /api/payments → 401 A0401", st == 401 and resp.get("code") == "A0401", f"{st} {resp}")
    check("401 错误体含 error/code/traceId", all(k in resp for k in ("error", "code", "traceId")), str(resp))
    st, resp, _, _ = raw_req("GET", BASE + "/api/notify/query")
    check("未登录通知查询 → 401", st == 401 and resp.get("code") == "A0401", f"{st}")
    st, resp, _, _ = req("GET", "/api/notify/N999999999")
    check("不存在通知 → 404 A0404 + traceId",
          st == 404 and resp.get("code") == "A0404" and resp.get("traceId"), f"{st} {resp}")
    st, resp, _, text = req("GET", "/api/notify/query?from=not-a-number")
    check("参数类型错误 → 400 A0400（不再 500）", st == 400 and resp.get("code") == "A0400", f"{st} {resp}")
    check("错误体不泄堆栈/下游地址",
          not any(k in text for k in ("\tat ", "dubbo://", "jdbc:", "Exception:", "127.0.0.1:2")), text[:200])
    st, resp, _, _ = req("GET", "/api/notify/resend/batch")
    check("方法不匹配 → 405 A0405", st == 405 and resp.get("code") == "A0405", f"{st} {resp}")

    r = urllib.request.Request(BASE + "/api/notify/send", data=b"{not-json", method="POST")
    r.add_header("Content-Type", "application/json")
    r.add_header("Authorization", "Bearer " + (TOKEN or ""))
    try:
        with urllib.request.urlopen(r, timeout=15) as rr:
            st, text = rr.status, rr.read().decode()
    except urllib.error.HTTPError as e:
        st, text = e.code, e.read().decode()
    check("非法 JSON → 400 A0400", st == 400 and "A0400" in text, f"{st} {text[:150]}")

    codes = ok("GET", "/api/errors")
    table = {c["code"]: c for c in codes["codes"]}
    legacy_expect = {"00000": 200, "A0400": 400, "A0401": 401, "A0403": 403, "A0404": 404,
                     "A0409": 409, "A0429": 429, "B0001": 400, "B0002": 500, "B0003": 503, "C0001": 502}
    check("既有 A/B/C 码表与 HTTP 映射一字不改",
          all(table.get(c, {}).get("httpStatus") == s for c, s in legacy_expect.items()),
          str({c: table.get(c) for c in legacy_expect if table.get(c, {}).get("httpStatus") != legacy_expect[c]}))
    additive = {c["code"] for c in codes["additive"]}
    check("新增码只 additive（A0428/A0405/A0413/B0004/C0002）",
          {"A0428", "A0405", "A0413", "B0004", "C0002"} <= additive, str(additive))
    check("新增码不与既有码冲突", not (additive & set(legacy_expect)))
    check("错误体字段声明为 error/code/traceId", codes["errorBody"] == ["error", "code", "traceId"])

    st, health, _, _ = req("GET", "/api/health", relogin=False)
    check("/api/health 免鉴权且仍为裸 list", st == 200 and isinstance(health, list), f"{st}")
    check("/api/health 13/13 全绿", len(health) == 13 and all(h.get("ok") for h in health),
          str([h.get("service") for h in health if not h.get("ok")]))
    st, members, _, _ = req("GET", "/api/members")
    check("/api/members 成功仍为裸 list（不套 envelope）", st == 200 and isinstance(members, list), f"{st}")
    check("/api/members 列表已脱敏（无 appSecret 明文）",
          all(not m.get("appSecret") for m in members))
    st, payments, _, _ = req("GET", "/api/payments")
    check("/api/payments 成功仍为裸数据", st == 200 and isinstance(payments, (list, dict)), f"{st}")
    st, _, _, _ = req("GET", "/actuator/health", relogin=False)
    check("actuator 免鉴权可达", st == 200, f"{st}")


# ================= 6/7. I-9 双实例无状态 + 灰度 =================

def section_multi_instance(app_id, secret, ids):
    print("== 6. I-9 双网关实例无状态（会话/限流/缓存/nonce/step-up 共享） ==")
    port2 = pick_port([8081, 8082, 8083, 8084])
    base2 = f"http://127.0.0.1:{port2}"
    inst2 = f"gateway-{port2}"
    proc = start_instance("gateway-2", "payment-gateway",
                          {"GATEWAY_PORT": port2, "GATEWAY_DUBBO_PORT": free_port(),
                           "GATEWAY_INSTANCE_ID": inst2, "PAYMENT_TLS_PORT": pick_port([8444, 8445, 8446])},
                          base2 + "/api/health")
    if proc is None:
        check("第二网关实例启动", False, "见 logs/gateway-2.log")
        return
    check(f"第二网关实例启动（端口错开 {port2}）", True)

    st, h2, _, _ = req("GET", "/api/health", base=base2, relogin=False)
    check("实例2 /api/health 13/13", st == 200 and len(h2) == 13 and all(x.get("ok") for x in h2), f"{st}")

    token_a = login(BASE)
    st, _, hdrs_a, _ = req("GET", "/api/members", token=token_a, base=BASE, relogin=False)
    st2, _, hdrs_b, _ = req("GET", "/api/members", token=token_a, base=base2, relogin=False)
    check("实例1 颁发的会话在实例2 直接可用（Redis 外置）", st == 200 and st2 == 200, f"{st}/{st2}")
    check("两实例 X-Instance-Id 不同且实例2 标识正确",
          hdrs_a.get("x-instance-id") != hdrs_b.get("x-instance-id")
          and hdrs_b.get("x-instance-id") == inst2,
          f"{hdrs_a.get('x-instance-id')} / {hdrs_b.get('x-instance-id')}")
    st, _, _, _ = req("GET", "/api/payments", token=token_a, base=base2, relogin=False)
    check("实例2 带 Bearer /api/payments 200", st == 200, f"{st}")

    token_b = login(base2)
    st, _, _, _ = req("GET", "/api/members", token=token_b, base=BASE, relogin=False)
    check("实例2 颁发的会话在实例1 可用", st == 200, f"{st}")
    req("POST", "/api/auth/logout", {}, token=token_b, base=base2, relogin=False)
    st, resp, _, _ = raw_req("GET", BASE + "/api/members", token=token_b)
    check("实例2 登出后实例1 立即 401（会话共享权威）", st == 401, f"{st} {resp}")

    bogus = "hglock" + SUFFIX
    codes5 = [raw_req("POST", BASE + "/api/auth/login", {"username": bogus, "password": "wrong"})[0]
              for _ in range(5)]
    check("实例1 专用用户 5 次错口令均 401", codes5 == [401] * 5, str(codes5))
    last_st, _, _, _ = raw_req("POST", BASE + "/api/auth/login", {"username": bogus, "password": "wrong"})
    check("实例1 第 6 次 → 429（阈值 5 不变）", last_st == 429, str(last_st))
    st, resp, _, _ = raw_req("POST", base2 + "/api/auth/login", {"username": bogus, "password": "wrong"})
    check("实例2 同用户名+IP 立即 429（限流计数外置共享）", st == 429, f"{st} {resp}")

    # 独立账户验缓存/nonce 共享（余额稳定，不受其它泳道影响）
    st, opened, _, _ = openapi("account.open", {"name": "HG缓存用户" + SUFFIX, "type": "USER"}, secret, app_id)
    acct = opened.get("accountId")
    openapi("fund.topup", {"accountId": acct, "amount": 1000, "bizNo": "HGC" + SUFFIX}, secret, app_id)
    st, bal, _, _ = openapi("account.balance", {"accountId": acct}, secret, app_id, base=BASE)
    check("实例1 OpenAPI HMAC 免 Bearer（带无效 Bearer）通", st == 200, f"{st} {bal}")
    if PROFILE == "docker":
        exists = redis_cmd("EXISTS", f"pp:openapi:app:{app_id}")
        check("商户凭证缓存写入共享 Redis（pp:openapi:app:*）", exists == "1", str(exists))
    st, bal2, _, _ = openapi("account.balance", {"accountId": acct}, secret, app_id, base=base2)
    check("实例2 OpenAPI HMAC 通且余额一致（同一商户缓存）", st == 200 and bal2 == bal == 1000, f"{st} {bal2} vs {bal}")
    check("account.balance 仍为裸数字 long（结构不变）",
          isinstance(bal, int) and not isinstance(bal, bool), str(bal))

    nonce = uuid.uuid4().hex
    st, _, _, _ = openapi("account.balance", {"accountId": acct}, secret, app_id, base=BASE, nonce=nonce)
    st2, resp2, _, _ = openapi("account.balance", {"accountId": acct}, secret, app_id, base=base2, nonce=nonce)
    check("实例1 用过的 nonce 在实例2 判重放 → 409 A0409",
          st == 200 and st2 == 409 and resp2.get("code") == "A0409", f"{st}/{st2} {resp2}")

    tk_a = step_up(BASE, token=token_a)
    nid = ids.get("SETTLE_BATCH")
    st, resp, _, _ = req("POST", f"/api/notify/{nid}/resend", {"requestId": "XINST" + SUFFIX},
                         token=token_a, headers={"X-Step-Up-Token": tk_a}, base=base2, relogin=False)
    check("实例1 颁发的 step-up token 在实例2 可用（外置共享）", st == 200, f"{st} {resp}")

    print("== 7. I-9 灰度路由 + 一键回滚 ==")
    st, cfg, _, _ = req("POST", "/api/gray/config",
                        {"enabled": True, "weight": 0, "tag": "gray", "target": base2},
                        token=token_a, relogin=False)
    check("灰度发布 200（target=实例2）", st == 200 and cfg.get("enabled") is True, f"{st} {cfg}")
    st, status_b, _, _ = req("GET", "/api/gray/status", token=token_a, base=base2, relogin=False)
    check("灰度状态跨实例共享（实例2 读到同一配置）",
          st == 200 and status_b.get("enabled") is True and status_b.get("target") == base2, str(status_b))
    check("灰度状态源为 redis（多实例一致）", status_b.get("stateSource") == "redis",
          str(status_b.get("stateSource")))

    st, _, hdrs, _ = req("GET", "/api/members", token=token_a, headers={"X-Gray-Tag": "gray"}, relogin=False)
    check("X-Gray-Tag: gray → 转发灰度实例",
          st == 200 and hdrs.get("x-gray-decision") == "gray"
          and hdrs.get("x-gray-upstream-instance") == inst2,
          f"{st} {hdrs.get('x-gray-decision')} {hdrs.get('x-gray-upstream-instance')}")
    st, _, hdrs, _ = req("GET", "/api/members", token=token_a, headers={"X-Gray-Tag": "stable"}, relogin=False)
    check("X-Gray-Tag: stable → 留稳定实例",
          st == 200 and hdrs.get("x-gray-decision") == "stable" and not hdrs.get("x-gray-upstream-instance"),
          f"{st} {hdrs.get('x-gray-decision')}")
    st, _, hdrs, _ = req("GET", "/api/members", token=token_a, relogin=False)
    check("weight=0 无 header → stable", hdrs.get("x-gray-decision") == "stable", str(hdrs.get("x-gray-decision")))

    req("POST", "/api/gray/config", {"enabled": True, "weight": 100, "target": base2}, token=token_a, relogin=False)
    st, _, hdrs, _ = req("GET", "/api/members", token=token_a, relogin=False)
    check("weight=100 → 全量灰度", st == 200 and hdrs.get("x-gray-decision") == "gray", str(hdrs.get("x-gray-decision")))
    st, health, hdrs, _ = req("GET", "/api/health", relogin=False)
    check("灰度全量时 /api/health 仍本地受理（监控不受影响）",
          st == 200 and len(health) == 13 and hdrs.get("x-gray-decision") != "gray", str(hdrs.get("x-gray-decision")))
    st, gbal, _, _ = openapi("account.balance", {"accountId": acct}, secret, app_id, base=BASE)
    check("灰度全量时 /openapi HMAC 经转发仍验签通过（body 原样）",
          st == 200 and gbal == 1000, f"{st} {gbal}")

    req("POST", "/api/gray/config", {"enabled": True, "weight": 50, "target": base2}, token=token_a, relogin=False)
    bucket = zlib.crc32(token_a.encode()) % 100
    st, _, hdrs, _ = req("GET", "/api/members", token=token_a, relogin=False)
    expect = "gray" if bucket < 50 else "stable"
    check("weight=50 按 crc32(token)%100 稳定哈希分流",
          hdrs.get("x-gray-decision") == expect,
          f"bucket={bucket} got={hdrs.get('x-gray-decision')} want={expect}")
    st, _, hdrs2, _ = req("GET", "/api/payments", token=token_a, relogin=False)
    check("同一会话分流稳定（不跳侧）", hdrs2.get("x-gray-decision") == hdrs.get("x-gray-decision"))

    st, rb, _, _ = req("POST", "/api/gray/rollback", {}, token=token_a, base=base2, relogin=False)
    check("一键回滚 200（enabled=false, weight=0）",
          st == 200 and rb.get("enabled") is False and rb.get("weight") == 0, f"{st} {rb}")
    st, status_a, _, _ = req("GET", "/api/gray/status", token=token_a, relogin=False)
    check("回滚跨实例即时生效（实例1 读到关闭态）", status_a.get("enabled") is False, str(status_a))
    st, _, hdrs, _ = req("GET", "/api/members", token=token_a, headers={"X-Gray-Tag": "gray"}, relogin=False)
    check("回滚后即使带 gray header 也回稳定实例",
          st == 200 and hdrs.get("x-gray-decision") == "stable", str(hdrs.get("x-gray-decision")))

    dead_port = free_port()
    req("POST", "/api/gray/config", {"enabled": True, "weight": 100,
                                     "target": f"http://127.0.0.1:{dead_port}"}, token=token_a, relogin=False)
    st, _, hdrs, _ = req("GET", "/api/members", token=token_a, relogin=False)
    check("灰度目标不可达 → fail-open 本实例受理 200",
          st == 200 and hdrs.get("x-gray-decision") == "stable-failopen",
          f"{st} {hdrs.get('x-gray-decision')}")
    req("POST", "/api/gray/rollback", {}, token=token_a, relogin=False)
    st, final, _, _ = req("GET", "/api/gray/status", token=token_a, relogin=False)
    check("收尾灰度处于回滚态", final.get("enabled") is False and final.get("weight") == 0, str(final))
    login(BASE)


# ================= 8. I-9 多实例调度不重复（ShedLock） =================

def section_shedlock(app_id, callback, recv):
    print("== 8. I-9 多实例调度不重复（第二个 notify 实例 + ShedLock） ==")
    if PROFILE != "docker":
        print("  [SKIP] local profile 单实例（H2 文件库独占），多实例调度断言仅 docker 跑")
        return
    nport = pick_port([18190, 18191, 18192])
    proc = start_instance("notify-2", "payment-notify",
                          {"NOTIFY_HTTP_PORT": nport, "NOTIFY_DUBBO_PORT": free_port(),
                           "NOTIFY_XXL_PORT": free_port(), "PAYMENT_XXL_ENABLED": "false"},
                          f"http://127.0.0.1:{nport}/actuator/health")
    if proc is None:
        check("第二 notify 实例启动", False, "见 logs/notify-2.log")
        return
    check(f"第二 notify 实例启动（端口错开 {nport}）", True)
    time.sleep(8)

    bizs = [f"HGLOCK{i}{SUFFIX}" for i in range(6)]
    nid_list = []
    for i, biz in enumerate(bizs):
        st, rec, _, _ = send_notify("PAY_SUCCESS", biz, callback, app_id,
                                    {"paymentId": "PL" + SUFFIX + str(i), "amount": 100 + i,
                                     "status": "SUCCESS", "bizId": biz})
        if st == 200:
            nid_list.append(rec["id"])
    deadline = time.time() + 60
    while time.time() < deadline:
        if all(req("GET", f"/api/notify/{n}")[1].get("status") == "DELIVERED" for n in nid_list):
            break
        time.sleep(2)
    check("双 notify 实例下 6 条通知全部送达",
          all(req("GET", f"/api/notify/{n}")[1].get("status") == "DELIVERED" for n in nid_list))
    duplicates = {b: recv.count_ok_for(b) for b in bizs if recv.count_ok_for(b) != 1}
    check("ShedLock 保证每条恰好推送一次（无重复投递）", not duplicates, str(duplicates))

    holder = mysql_query("SELECT locked_by FROM shedlock WHERE name='notifyPush'")
    check("shedlock notifyPush 单一持有者（多实例互斥）", bool(holder) and "\n" not in holder, str(holder))
    samples = []
    for _ in range(4):
        h = mysql_query("SELECT locked_by FROM shedlock WHERE name='notifyPush'")
        if h:
            samples.append(h)
        time.sleep(2)
    check("多轮采样锁持有者恒为单值（无并发双持）",
          bool(samples) and all("\n" not in s for s in samples), str(samples))
    resend_total = mysql_query("SELECT COUNT(*) FROM notify_record WHERE resend_count > 0")
    check("notify_record.resend_count additive 列在 MySQL 生效",
          resend_total is not None and int(resend_total) >= 1, str(resend_total))


# ================= 9. ride 红线 =================

def section_ride(app_id, merchant_id, merchant_name, secret, callback, recv):
    """callback/recv 为 ride 式不验签接收方（支付域自动通知用平台密钥签名）。"""
    print("== 9. ride 兼容红线（/openapi 免 Bearer HMAC、BALANCE 同步、http 回调） ==")
    st, opened, _, _ = openapi("account.open", {"name": "HG用户" + SUFFIX, "type": "USER"}, secret, app_id)
    check("account.open 免 Bearer（带无效 Bearer 仍通）", st == 200 and opened.get("accountId"), f"{st} {opened}")
    account_id = opened.get("accountId")
    st, topped, _, _ = openapi("fund.topup", {"accountId": account_id, "amount": 5000,
                                              "bizNo": "HGT" + SUFFIX}, secret, app_id)
    check("fund.topup 冻结方法仍通（返回 payment.id）",
          st == 200 and (topped.get("payment") or {}).get("id"), f"{st} {str(topped)[:200]}")
    bal, deadline = None, time.time() + 30
    while time.time() < deadline:
        st, bal, _, _ = openapi("account.balance", {"accountId": account_id}, secret, app_id)
        if bal == 5000:
            break
        time.sleep(1)
    check("account.balance 裸数字 long 且金额正确", st == 200 and bal == 5000, f"{st} {bal}")
    # 商户懒开户（account.open 幂等）：BALANCE 支付手续费需商户账户存在
    openapi("account.open", {"name": merchant_name, "type": "MERCHANT"}, secret, app_id)
    st, order, _, _ = openapi("trade.create", {"merchantId": merchant_id, "subject": "HG行程",
                                               "amount": 1200, "outTradeNo": "HGO" + SUFFIX}, secret, app_id)
    check("trade.create 冻结方法仍通", st == 200 and order.get("id"), f"{st} {order}")
    st, paid, _, _ = openapi("trade.pay", {"orderId": order["id"], "accountId": account_id,
                                           "method": "BALANCE", "callbackUrl": callback,
                                           "outTradeNo": "HGO" + SUFFIX}, secret, app_id)
    pay_id = (paid.get("payment") or {}).get("id")
    check("trade.pay BALANCE 同步受理仍通（返回 payment.id）", st == 200 and bool(pay_id), f"{st} {str(paid)[:200]}")
    check("BALANCE 受理即同步 SUCCESS（无 WAITING/异步回执）",
          (paid.get("payment") or {}).get("status") == "SUCCESS", str(paid)[:200])
    deadline = time.time() + 30
    status, q = None, {}
    while time.time() < deadline:
        st, q, _, _ = openapi("trade.query", {"orderId": order["id"]}, secret, app_id)
        status = (q.get("payment") or {}).get("status")
        if status in ("SUCCESS", "FAILED"):
            break
        time.sleep(1)
    check("BALANCE 支付同步收敛 SUCCESS（无 WAITING/异步回执）", status == "SUCCESS", str(status))
    check("trade.query 返回订单+支付单（结构不变）", st == 200 and q.get("order") and q.get("payment"))
    # 通知经 outbox/MQ 异步投递，轮询等待回调到达（不放宽断言，只等投递）
    cap, deadline = None, time.time() + 45
    while time.time() < deadline:
        cap = recv.capture_for(pay_id)
        if cap:
            break
        time.sleep(1)
    check("支付成功 http 回调仍可收（PAY_SUCCESS body 冻结四字段）",
          bool(cap) and json.loads(cap["body"]).get("paymentId") == pay_id
          and set(json.loads(cap["body"]).keys()) == {"paymentId", "orderId", "amount", "status"},
          str(cap and cap["body"])[:200])
    check("回调签名头 additive（四要素齐全）",
          bool(cap) and all(cap.get(k) for k in ("sig", "ts", "nonce", "stype")))
    nrec = ok("GET", f"/api/notify/records?bizId={pay_id}")
    check("支付域自动通知登记 PAY_SUCCESS 且带签名要素（平台密钥）",
          isinstance(nrec, list) and len(nrec) >= 1 and nrec[0]["event"] == "PAY_SUCCESS"
          and nrec[0].get("signature") and nrec[0].get("nonce")
          and nrec[0].get("signType") == "HMAC_SHA256", str(nrec)[:250])
    st, bad, _, _ = req("POST", "/openapi/account.balance",
                        {"accountId": account_id, "appId": app_id, "timestamp": int(time.time() * 1000),
                         "sign": "deadbeef"}, relogin=False)
    check("HMAC 验签机制不变（错签 → 400 A0400）", st == 400 and bad.get("code") == "A0400", f"{st} {bad}")


def main():
    print(f"== verify-notify-gw.py == profile={PROFILE} gateway={BASE}")
    if not login():
        print("!! 控制台登录失败，无法继续")
        sys.exit(2)
    merchant = ok("POST", "/api/members", {"name": f"H泳道商户{SUFFIX}", "type": "MERCHANT"})
    secret, app_id, merchant_id = merchant.get("appSecret"), merchant.get("appId"), merchant.get("id")
    if not secret or not app_id:
        print("!! 商户 create 未返回 appId/appSecret")
        sys.exit(2)
    recv = Receiver(secret)
    port = recv.start()
    ride_recv = Receiver(secret, verify=False)
    ride_port = ride_recv.start()
    ride_callback = f"http://127.0.0.1:{ride_port}/cb"
    callback = f"http://127.0.0.1:{port}/cb"        # ride 红线：http 回调
    dead_callback = f"http://127.0.0.1:{port}/dead"  # 独立拒收路径，制造 FAILED 死信
    print(f"  [INFO] 商户回调接收方 http://127.0.0.1:{port}/cb")

    # 先埋一条必失败通知（重试耗尽 ~78s），用后续章节时间摊销等待
    recv.reject_paths.add("/dead")
    failing_biz = "HGDEAD" + SUFFIX
    st, failing, _, _ = send_notify("PAY_SUCCESS", failing_biz, dead_callback, app_id,
                                    {"paymentId": "PD" + SUFFIX, "amount": 777, "status": "SUCCESS",
                                     "bizId": failing_biz})
    failing_id = failing.get("id")
    try:
        ids = section_events(app_id, secret, callback, recv)
        section_query(app_id, secret, ids)
        section_resend(app_id, secret, recv, ids, failing_biz, failing_id)
        section_docs(app_id, secret)
        section_errors(app_id, secret)
        section_multi_instance(app_id, secret, ids)
        section_shedlock(app_id, callback, recv)
        section_ride(app_id, merchant_id, merchant.get("name"), secret, ride_callback, ride_recv)
    finally:
        cleanup()
    print(f"\n结果: {passed} 通过 / {failed} 失败")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()