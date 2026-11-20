#!/bin/bash
# payment-pro 全量验收：P0 + P1 全部专项 + 跨系统兼容门禁
# 用法：./accept-all.sh            （栈需已启动：./run.sh --docker）
#       ./accept-all.sh --quick    （只跑核心回归 + 兼容门禁，跳过重型混沌）
#
# 关键顺序（血泪教训，见 DESIGN §20.11）：
#   阶段1 ride 静默窗口：smoke / verify-pro 含全局增量断言，ride 在线会污染 → 先停 ride
#   阶段2 启 ride：跑各域专项（含混沌，脚本自行全栈重启并过稳定性门禁）
#   阶段3 ride 兼容门禁 24/24（最后跑，因其 F 段会停 pp 重启）
# 退出码：任一脚本失败即非 0；末尾打印汇总表。
set -u
cd "$(dirname "$0")"
ROOT=$(pwd)
RIDE="$ROOT/../ride-pro"
export JAVA_HOME="${JAVA_HOME:-/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home}"
QUICK=0
[ "${1:-}" = "--quick" ] && QUICK=1
LOGDIR="$ROOT/run/accept-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$LOGDIR"
SUMMARY="$LOGDIR/summary.txt"
FAILED=0

say() { printf '%s\n' "$*"; }

health_gate() {
  python3 - <<'PY'
import json,sys,time,urllib.request,urllib.error
B='http://127.0.0.1:8080'
def req(m,p,b=None,t=None):
    d=json.dumps(b).encode() if b is not None else None
    r=urllib.request.Request(B+p,data=d,method=m)
    if d: r.add_header('Content-Type','application/json')
    if t: r.add_header('Authorization','Bearer '+t)
    try:
        with urllib.request.urlopen(r,timeout=15) as x: return x.status,x.read().decode()
    except urllib.error.HTTPError as e: return e.code,e.read().decode()
    except Exception as e: return 0,str(e)
streak=0
for i in range(150):
    st,body=req('GET','/api/health'); ok=False
    if st==200:
        try:
            d=json.loads(body); ok=isinstance(d,list) and len(d)==13 and all(x['ok'] for x in d)
        except Exception: pass
    if ok:
        streak+=1
        if streak>=3:
            s2,b2=req('POST','/api/auth/login',{'username':'admin','password':'admin123'})
            tk=json.loads(b2).get('token') if s2==200 else None
            if tk:
                edges=[('GET','/api/members'),('GET','/api/payments'),('GET','/api/accounts'),
                       ('GET','/api/orders'),('GET','/api/settle/records'),('GET','/api/notify/records'),
                       ('GET','/api/disputes'),('GET','/api/recon/latest')]
                if all(req(m,p,t=tk)[0]==200 for m,p in edges):
                    s3,b3=req('GET','/api/accounts',t=tk); aid=None
                    try:
                        a=json.loads(b3)
                        if isinstance(a,list) and a: aid=a[0]['id']
                    except Exception: pass
                    if aid and req('GET',f'/api/accounting/accounts/{aid}/balance',t=tk)[0]==200:
                        print(f'  门禁通过（{i+1} 次轮询，含域间边预热）'); sys.exit(0)
    else: streak=0
    time.sleep(2)
print('  门禁未通过：栈未稳定'); sys.exit(1)
PY
}

run() { # run <标签> <脚本> [额外env...]
  local label=$1 script=$2; shift 2
  local log="$LOGDIR/$label.log"
  say ""
  say "===== $label ====="
  env "$@" PP_PROFILE=docker python3 "$script" > "$log" 2>&1
  local rc=$?
  local line
  line=$(grep -E '结果|PASS=' "$log" | tail -1)
  [ -z "$line" ] && line=$(tail -1 "$log")
  if [ $rc -ne 0 ]; then
    FAILED=$((FAILED+1))
    say "  ✗ $label  exit=$rc  $line"
    grep -E '✗|\[FAIL\]|AssertionError' "$log" | head -5 | sed 's/^/      /'
    printf '%-34s FAIL  %s\n' "$label" "$line" >> "$SUMMARY"
  else
    say "  ✓ $label  $line"
    printf '%-34s PASS  %s\n' "$label" "$line" >> "$SUMMARY"
  fi
}

say "== payment-pro 全量验收 $(date '+%F %T') =="
say "日志目录：$LOGDIR"
say ""
say "[阶段0] 稳定性门禁"
health_gate || { say "栈不稳定，先 ./run.sh --docker 并等 13/13 健康"; exit 1; }

say ""
say "[阶段1] ride 静默窗口（全局增量断言不受并发镜像流量污染）"
if [ -x "$RIDE/stop.sh" ]; then (cd "$RIDE" && ./stop.sh >"$LOGDIR/ride-stop.log" 2>&1); say "  ride 已停"; fi
run "smoke-test(核心79项)" "$ROOT/smoke-test.py"
run "verify-pro(混沌22项)" "$ROOT/verify-pro.py"
if [ -x "$RIDE/run.sh" ]; then
  (cd "$RIDE" && ./run.sh --docker --no-build >"$LOGDIR/ride-run.log" 2>&1)
  say "  ride 已重启"
  python3 - <<'PY'
import json,time,urllib.request
for i in range(90):
    try:
        with urllib.request.urlopen('http://127.0.0.1:8090/api/health',timeout=6) as x: d=json.loads(x.read().decode())
        s=d.get('services') or []
        if s and all(v.get('ok') for v in s) and d.get('registry',{}).get('ok'):
            print(f'  ride 就绪 {len(s)}/{len(s)}'); break
    except Exception: pass
    time.sleep(2)
else: print('  ride 未就绪（兼容门禁可能失败）')
PY
fi

say ""
say "[阶段2] P0 专项"
run "verify-reverse(红冲)" "$ROOT/verify-reverse.py"
run "verify-suspense(挂账)" "$ROOT/verify-suspense.py"
run "verify-notify-sign(通知签名)" "$ROOT/verify-notify-sign.py"
run "verify-dispute(差错平台)" "$ROOT/verify-dispute.py"
run "verify-fund-guard(资金防线)" "$ROOT/verify-fund-guard.py"
run "verify-rbac(认证鉴权审计)" "$ROOT/verify-rbac.py"
run "verify-secret(密钥证书)" "$ROOT/verify-secret.py"
run "verify-metrics(可观测)" "$ROOT/verify-metrics.py"
run "verify-reconverge(补单收敛)" "$ROOT/verify-reconverge.py"
run "verify-config(I-2配置中心)" "$ROOT/verify-config.py"
run "verify-job(I-3调度)" "$ROOT/verify-job.py"
run "verify-sentinel(I-7治理)" "$ROOT/verify-sentinel.py"
run "verify-sharding(I-5a试点)" "$ROOT/verify-sharding.py"
run "verify-sharding-fund(I-5b资金域)" "$ROOT/verify-sharding-fund.py"
if [ $QUICK -eq 0 ]; then
  run "verify-mq(I-4事务消息混沌)" "$ROOT/verify-mq.py"
fi

say ""
say "[阶段2] P1 专项"
run "verify-channel(渠道路由账单)" "$ROOT/verify-channel.py"
run "verify-risk(风控规则限额评分)" "$ROOT/verify-risk.py"
run "verify-member-account(会员账户进件)" "$ROOT/verify-member-account.py"
run "verify-settle(清结算)" "$ROOT/verify-settle.py"
run "verify-accounting(账务科目日切)" "$ROOT/verify-accounting.py"
run "verify-notify-gw(通知网关多实例)" "$ROOT/verify-notify-gw.py"
run "verify-trade(交易产品)" "$ROOT/verify-trade.py"
run "verify-recon(对账体系)" "$ROOT/verify-recon.py"
run "verify-payment-orchestration(支付编排)" "$ROOT/verify-payment-orchestration.py"
run "verify-cashier(收银台+H5)" "$ROOT/verify-cashier.py"

say ""
say "[阶段3] 跨系统兼容门禁（ride-pro 双账本 24 项）"
if [ -f "$RIDE/verify-integration.py" ]; then
  (cd "$RIDE" && PP_PROFILE=docker python3 verify-integration.py > "$LOGDIR/ride-integration.log" 2>&1)
  rc=$?
  line=$(grep -E '结果' "$LOGDIR/ride-integration.log" | tail -1)
  if [ $rc -ne 0 ]; then
    FAILED=$((FAILED+1)); say "  ✗ ride-integration exit=$rc $line"
    grep -E '✗|\[FAIL\]|AssertionError' "$LOGDIR/ride-integration.log" | head -5 | sed 's/^/      /'
    printf '%-34s FAIL  %s\n' "ride-integration(兼容门禁)" "$line" >> "$SUMMARY"
  else
    say "  ✓ ride-integration $line"
    printf '%-34s PASS  %s\n' "ride-integration(兼容门禁)" "$line" >> "$SUMMARY"
  fi
else
  say "  (ride-pro/verify-integration.py 不存在，跳过)"
fi

say ""
say "================ 汇总 ================"
cat "$SUMMARY"
say "======================================"
say "失败套件数：$FAILED"
say "明细日志：$LOGDIR"
[ $FAILED -eq 0 ] && say "结论：全量验收通过 ✅" || say "结论：有失败项，见上方 ✗ 与日志 ❌"
exit $([ $FAILED -eq 0 ] && echo 0 || echo 1)