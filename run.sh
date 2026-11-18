#!/bin/bash
# 一键编译并启动全部服务：注册中心 → 11 个域服务 → 网关
set -e
cd "$(dirname "$0")"

if [ -d "/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home" ]; then
  export JAVA_HOME="/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home"
else
  export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
fi
echo "JAVA_HOME=$JAVA_HOME"

mkdir -p run logs data config

PROFILE="local"
if [ "$1" = "--docker" ]; then PROFILE="docker"; shift; fi
if [ "$PROFILE" = "docker" ]; then
  if [ -f "$HOME/.config/payment-pro/docker.env" ]; then
    set -a; . "$HOME/.config/payment-pro/docker.env"; set +a
  else
    echo "!! 缺少 ~/.config/payment-pro/docker.env（MySQL 口令等），参见 config/docker.env.example"; exit 1
  fi
  echo "==> Docker profile：MySQL pp_* 多库 + Docker ZooKeeper 2181（跳过内嵌注册中心）"
fi

if [ ! -f config/payment-pro.p12 ]; then
  echo "==> 生成 OpenAPI 自签证书（HTTPS ${TLS_PORT:-8443}）..."
  "$JAVA_HOME/bin/keytool" -genkeypair -alias payment-pro -keyalg RSA -keysize 2048 \
    -storetype PKCS12 -keystore config/payment-pro.p12 -storepass changeit \
    -dname "CN=localhost" -ext "SAN=dns:localhost,ip:127.0.0.1" -validity 3650 >/dev/null 2>&1
  "$JAVA_HOME/bin/keytool" -exportcert -rfc -alias payment-pro \
    -keystore config/payment-pro.p12 -storepass changeit -file config/payment-pro-cert.pem >/dev/null 2>&1
fi

if [ "$1" != "--no-build" ]; then
  echo "==> 编译打包（Maven Wrapper）..."
  ./mvnw package -DskipTests -q
fi

start() {
  local name=$1 module=$2
  if [ -f "run/$name.pid" ] && kill -0 "$(cat run/$name.pid)" 2>/dev/null; then
    echo "==> $name 已在运行 (pid $(cat run/$name.pid))，跳过"
    return
  fi
  local profileArg=""
  [ "$PROFILE" = "docker" ] && profileArg="-Dspring.profiles.active=docker"
  nohup "$JAVA_HOME/bin/java" $profileArg -jar "$module/target/$module-1.0.0.jar" > "logs/$name.log" 2>&1 &
  echo $! > "run/$name.pid"
  echo "==> 启动 $name (pid $!)"
}

wait_http() {
  local url=$1 name=$2 timeout=${3:-90}
  for ((i=0; i<timeout; i++)); do
    if curl -sf -o /dev/null --max-time 2 "$url"; then
      echo "==> $name 就绪"
      return 0
    fi
    sleep 1
  done
  echo "!! $name 在 ${timeout}s 内未就绪，查看 logs/$name.log"
  return 1
}

if [ "$PROFILE" = "docker" ]; then
  echo "==> 探测 Docker ZooKeeper 127.0.0.1:2181..."
  zk_ok=""
  for ((i=0; i<30; i++)); do
    if (exec 3<>/dev/tcp/127.0.0.1/2181) 2>/dev/null; then exec 3>&- 3<&-; zk_ok=1; break; fi
    sleep 1
  done
  if [ -z "$zk_ok" ]; then echo "!! Docker ZooKeeper 2181 不可达，请先在 ops-pro 启动 zookeeper"; exit 1; fi
  echo "==> Docker ZooKeeper 就绪"
else
  start registry payment-registry
  wait_http "http://127.0.0.1:${ZK_HTTP_PORT:-8092}/health" registry 60
fi

for svc in member account accounting trade channel risk cashier payment settle notify recon; do
  start "$svc" "payment-$svc"
done

start gateway payment-gateway
wait_http "http://127.0.0.1:${GATEWAY_PORT:-8080}/api/health" gateway 90

echo "==> 等待全部域服务注册并通过健康检查..."
for ((i=0; i<120; i++)); do
  down=$(curl -sf --max-time 5 "http://127.0.0.1:${GATEWAY_PORT:-8080}/api/health" \
    | python3 -c 'import sys,json; print(" ".join(h["service"] for h in json.load(sys.stdin) if not h["ok"]))' 2>/dev/null || echo "gateway")
  if [ -z "$down" ]; then
    echo "==> 全部 13 个进程健康 ✔"
    echo "==> 控制台: http://127.0.0.1:${GATEWAY_PORT:-8080}/"
    exit 0
  fi
  sleep 2
done
echo "!! 仍有服务未就绪: $down（域服务可能仍在启动，可稍后刷新 /api/health）"
exit 1