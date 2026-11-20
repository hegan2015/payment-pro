#!/usr/bin/env python3
"""verify-rbac.py — wave2 S-2/G-1 控制台认证 + RBAC + 审计验证。
跑法: python3 verify-rbac.py  （payment-pro local/docker 栈需已启动，gateway 8080）

覆盖:
  - 开放面红线: /api/health、gateway/member actuator、静态控制台免鉴权；/openapi/** 无 Bearer HMAC 可通；
  - /api/** 未登录 401（A0401）、垃圾 token 401；
  - admin 登录（PP_ADMIN_PASSWORD，默认 admin123）/me/错口令 401；
  - RBACTEST 前缀用户 OPERATOR/AUDITOR/READONLY 权限矩阵：越权 403（A0403）、授权内 200；
  - 审计可追溯: who/what(方法+路径+bizNo)/结果/IP，LOGIN/LOGIN_FAIL/403 均入 audit_log，分页过滤；
  - 登录失败限流: 同用户名+IP 5 次失败后 429（正确口令也拒）；
  - logout 后 token 失效；refresh 换新 token 旧 token 失效；
  - E-1 不回退: 会员列表 appSecret 脱敏。
测试数据独立前缀 RBACTEST + uuid，可重复跑。
"""
import hashlib
import hmac
import json
import os
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


def req(method, path, body=None, token=None, base=None, accept="application/json"):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request((base or BASE) + path, data=data, method=method)
    r.add_header("Accept", accept)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
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


def login(username, password):
    return req("POST", "/api/auth/login", {"username": username, "password": password})


def ok(method, path, body=None, token=None):
    st, resp = req(method, path, body, token)
    assert st == 200, f"{method} {path} 期望 200 实际 {st}: {resp}"
    return resp


def openapi_sign(params, secret):
    items = sorted((k, v) for k, v in params.items() if v is not None and k != "sign")
    canonical = "&".join(f"{k}={v}" for k, v in items)
    return hmac.new(secret.encode(), canonical.encode(), hashlib.sha256).hexdigest()


print("== 1. 开放面红线（免鉴权） ==")
st, health = req("GET", "/api/health")
check("GET /api/health 无 token 200", st == 200)
check("health 13/13 服务健康", isinstance(health, list) and len(health) == 13 and all(h["ok"] for h in health),
      str(health)[:200])
st, _ = req("GET", "/actuator/health")
check("gateway actuator 无 token 200", st == 200)
st, _ = req("GET", "/actuator/health", base="http://127.0.0.1:18081")
check("member actuator(18081) 无 token 200", st == 200)
st, _ = req("GET", "/", accept="text/html")
check("静态控制台无 token 200", st == 200, f"st={st}")

print("== 2. 未登录 401 ==")
st, resp = req("GET", "/api/members")
check("GET /api/members 无 token 401+A0401", st == 401 and resp.get("code") == "A0401", f"{st} {resp}")
st, resp = req("POST", "/api/accounting/internal/topup", {"accountId": "X", "amount": 1, "bizNo": "X"})
check("POST topup 无 token 401", st == 401, str(st))
st, _ = req("GET", "/api/members", token="garbage-token-" + SUFFIX)
check("垃圾 Bearer token 401", st == 401, str(st))

print("== 3. admin 登录 / me ==")
st, resp = login("admin", "wrong-pass-" + SUFFIX)
check("admin 错口令 401", st == 401, f"{st} {resp}")
st, resp = login("admin", ADMIN_PASS)
check("admin 登录 200 返回 token/expiresIn/user", st == 200 and resp.get("token") and resp.get("expiresIn"),
      f"{st} {str(resp)[:200]}")
ADMIN = resp["token"] if st == 200 else None
user = (resp or {}).get("user") or {}
check("admin 角色 ADMIN 且权限含通配 *", "ADMIN" in (user.get("roles") or []) and "*" in (user.get("permissions") or []),
      str(user))
st, me = req("GET", "/api/auth/me", token=ADMIN)
check("GET /api/auth/me 200 admin", st == 200 and me.get("username") == "admin", f"{st} {me}")
st, _ = req("GET", "/api/members", token=ADMIN)
check("admin GET /api/members 200", st == 200, str(st))

print("== 4. 创建 RBACTEST 用户（console:admin） ==")
U = {r: f"rbactest_{r}_{SUFFIX}" for r in ("op", "aud", "ro", "lim")}
ROLE = {"op": "OPERATOR", "aud": "AUDITOR", "ro": "READONLY", "lim": "READONLY"}
PWD = "Passw0rd-" + SUFFIX
for k, name in U.items():
    st, resp = req("POST", "/api/console/users", {"username": name, "password": PWD, "roles": [ROLE[k]]}, token=ADMIN)
    check(f"创建用户 {ROLE[k]} 200", st == 200 and resp.get("username") == name, f"{st} {resp}")
TOK = {}
for k in ("op", "aud", "ro"):
    st, resp = login(U[k], PWD)
    TOK[k] = resp.get("token") if st == 200 else None
    check(f"{ROLE[k]} 登录 200", st == 200 and bool(TOK[k]), f"{st} {resp}")

print("== 5. 权限矩阵（越权 403 / 授权内 200） ==")
st, _ = req("GET", "/api/members", token=TOK["ro"])
check("READONLY 读会员 200", st == 200, str(st))
st, resp = req("POST", "/api/members", {"name": "RBACTEST-denied-" + SUFFIX, "type": "USER"}, token=TOK["ro"])
check("READONLY 建会员 403+A0403", st == 403 and resp.get("code") == "A0403", f"{st} {resp}")
st, _ = req("POST", "/api/accounting/internal/topup", {"accountId": "X", "amount": 1, "bizNo": "X"}, token=TOK["ro"])
check("READONLY 充值 403", st == 403, str(st))
st, _ = req("POST", "/api/members", {"name": "RBACTEST-denied2-" + SUFFIX, "type": "USER"}, token=TOK["aud"])
check("AUDITOR 建会员 403", st == 403, str(st))
st, _ = req("GET", "/api/audit", token=TOK["op"])
check("OPERATOR 查审计 403", st == 403, str(st))
st, _ = req("GET", "/api/audit", token=TOK["ro"])
check("READONLY 查审计 403", st == 403, str(st))
st, resp = req("GET", "/api/audit?page=1&size=5", token=TOK["aud"])
check("AUDITOR 查审计 200 分页", st == 200 and isinstance(resp.get("items"), list), f"{st} {str(resp)[:150]}")
st, _ = req("POST", "/api/console/users", {"username": "rbactest_x_" + SUFFIX, "password": PWD}, token=TOK["op"])
check("OPERATOR 建控制台用户 403", st == 403, str(st))

# OPERATOR 充值 200：admin 建 RBACTEST 会员+账户
m = ok("POST", "/api/members", {"name": "RBACTEST-acct-" + SUFFIX, "type": "USER"}, token=ADMIN)
a = ok("POST", "/api/accounts", {"memberId": m["id"]}, token=ADMIN)
BIZ = "RBACTEST-TOPUP-" + SUFFIX
st, resp = req("POST", "/api/accounting/internal/topup", {"accountId": a["id"], "amount": 100, "bizNo": BIZ}, token=TOK["op"])
check("OPERATOR 充值 200（accounting:topup）", st == 200, f"{st} {resp}")
st, _ = req("GET", f"/api/members/{m['id']}/secrets", token=TOK["ro"])
check("READONLY 查密钥版本 403（member:secret）", st == 403, str(st))
st, _ = req("GET", "/api/certs/expiring?days=30", token=TOK["ro"])
check("READONLY 查证书到期 200（member:read）", st == 200, str(st))

print("== 6. 审计可追溯（who/what/bizNo/结果/IP） ==")
time.sleep(1)
st, resp = req("GET", f"/api/audit?username={U['op']}&action=POST&path=/api/accounting/internal/topup&size=50", token=ADMIN)
rows = (resp or {}).get("items") or []
hit = next((r for r in rows if r.get("bizNo") == BIZ), None)
check("审计含 OPERATOR 充值记录（who+路径）", st == 200 and hit is not None, str(resp)[:300])
check("审计记录 bizNo/结果200/IP 齐全", bool(hit) and hit.get("result") == "200" and bool(hit.get("ip"))
      and hit.get("method") == "POST", str(hit))
st, resp = req("GET", "/api/audit?action=LOGIN_FAIL&username=admin&size=50", token=ADMIN)
check("审计含 admin LOGIN_FAIL", st == 200 and any(r.get("result") == "401" for r in (resp.get("items") or [])),
      str(resp)[:200])
st, resp = req("GET", "/api/audit?username=" + U["ro"] + "&size=50", token=ADMIN)
check("审计含 READONLY 403 越权记录", st == 200 and any(r.get("result") == "403" for r in (resp.get("items") or [])),
      str(resp)[:200])
st, resp = req("GET", "/api/audit?action=LOGIN&username=" + U["op"] + "&size=10", token=ADMIN)
check("审计含 OPERATOR LOGIN", st == 200 and len(resp.get("items") or []) >= 1, str(resp)[:200])

print("== 7. ADMIN 全通 + 控制台管理 ==")
st, resp = req("GET", "/api/console/users", token=ADMIN)
check("admin 列用户 200 含新用户", st == 200 and any(u.get("username") == U["op"] for u in resp), str(st))
st, resp = req("GET", "/api/console/roles", token=ADMIN)
codes = {r.get("code") for r in (resp or [])}
check("admin 列角色 200 含 4 内置角色", st == 200 and {"ADMIN", "OPERATOR", "AUDITOR", "READONLY"} <= codes, str(codes))
st, resp = req("GET", f"/api/members/{m['id']}/secrets", token=ADMIN)
check("admin 查密钥版本 200", st == 200, str(st))

print("== 8. 登录失败限流（5 次失败后 429） ==")
codes5 = [login(U["lim"], "bad-" + str(i))[0] for i in range(5)]
check("专用用户 5 次错口令均 401", codes5 == [401] * 5, str(codes5))
st, resp = login(U["lim"], "bad-6")
check("第 6 次 429+A0429", st == 429 and resp.get("code") == "A0429", f"{st} {resp}")
st, _ = login(U["lim"], PWD)
check("限流期内正确口令也 429", st == 429, str(st))

print("== 9. 会话生命周期（logout / refresh） ==")
st, resp = login(U["ro"], PWD)
ro_tok = resp.get("token")
st, _ = req("POST", "/api/auth/logout", token=ro_tok)
check("logout 200", st == 200, str(st))
st, _ = req("GET", "/api/members", token=ro_tok)
check("logout 后旧 token 401", st == 401, str(st))
st, resp = req("POST", "/api/auth/refresh", token=TOK["op"])
new_tok = (resp or {}).get("token")
check("refresh 200 换新 token", st == 200 and new_tok and new_tok != TOK["op"], f"{st} {str(resp)[:150]}")
st, me = req("GET", "/api/auth/me", token=new_tok)
check("新 token me 200", st == 200 and me.get("username") == U["op"], str(st))
st, _ = req("GET", "/api/auth/me", token=TOK["op"])
check("refresh 后旧 token 401", st == 401, str(st))
TOK["op"] = new_tok

print("== 10. /openapi/** 红线（无 Bearer，HMAC 独立验签） ==")
st, merchant = req("POST", "/api/members", {"name": "RBACTEST-mer-" + SUFFIX, "type": "MERCHANT"}, token=ADMIN)
check("admin 建商户 200 一次性返回密钥", st == 200 and merchant.get("appId") and merchant.get("appSecret"),
      f"{st} {str(merchant)[:150]}")
params = {"appId": merchant["appId"], "timestamp": int(time.time() * 1000),
          "name": "RBACTEST-open-" + SUFFIX, "type": "USER"}
params["sign"] = openapi_sign(params, merchant["appSecret"])
st, resp = req("POST", "/openapi/account.open", params)  # 无 Authorization
check("openapi 无 Bearer HMAC 验签通过 200", st == 200 and resp.get("accountId"), f"{st} {resp}")
bad = dict(params, sign="0" * 64, timestamp=int(time.time() * 1000))
st, resp = req("POST", "/openapi/account.open", bad)
check("openapi 错签 400（不因缺 Bearer 401）", st == 400, f"{st} {resp}")
st, members = req("GET", "/api/members", token=ADMIN)
leak = [x for x in (members or []) if x.get("id") == merchant["id"] and x.get("appSecret")]
check("E-1 不回退：列表 appSecret 脱敏", st == 200 and not leak, str(leak)[:150])

print()
print(f"verify-rbac: {passed} passed, {failed} failed (共 {passed + failed} 项)")
sys.exit(1 if failed else 0)