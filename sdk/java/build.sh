#!/usr/bin/env bash
# G-4b Java SDK 编译（零第三方依赖，JDK 11+；产物 sdk/java/out）
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home}"
OUT="$ROOT/sdk/java/out"
mkdir -p "$OUT"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$OUT" \
  "$ROOT"/sdk/java/src/com/qoder/payment/sdk/*.java
echo "Java SDK 编译完成: $OUT"