#!/bin/bash
# 只读 H2 → MySQL 数据迁移：把本地 data/*.mv.db 的业务数据搬进 Docker MySQL pp_* 多库。
# 原 H2 库文件绝不修改（复制到临时目录只读打开）；MySQL 侧 INSERT IGNORE 去重，可重复执行。
# 序列按 H2 BASE_VALUE 回填 MySQL sequences.value（GREATEST 取大）。
#
# 用法:
#   ./migrate-h2-to-mysql.sh --dry-run     # 只统计待迁移行数/序列，不写 MySQL
#   ./migrate-h2-to-mysql.sh               # 实际迁移（需 ~/.config/payment-pro/docker.env）
#
# 前置: Docker MySQL 已起、pp_* 库与 schema-mysql.sql 已建（ops-pro/deploy/payment-databases.sql）。
set -e
cd "$(dirname "$0")"

if [ -d "/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home" ]; then
  export JAVA_HOME="/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home"
else
  export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
fi

DRY=""
if [ "$1" = "--dry-run" ]; then DRY="--dry-run"; fi

if [ -f "$HOME/.config/payment-pro/docker.env" ]; then
  set -a; . "$HOME/.config/payment-pro/docker.env"; set +a
else
  echo "!! 缺少 ~/.config/payment-pro/docker.env（MySQL 口令等），参见 config/docker.env.example"; exit 1
fi

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"

H2_JAR="$(find "$HOME/.m2/repository/com/h2database/h2" -name 'h2-2.2.224.jar' 2>/dev/null | head -1)"
MY_JAR="$(find "$HOME/.m2/repository/com/mysql/mysql-connector-j" -name 'mysql-connector-j-*.jar' 2>/dev/null | grep -v sources | head -1)"
if [ -z "$H2_JAR" ] || [ -z "$MY_JAR" ]; then
  echo "!! 未找到 H2/MySQL 驱动 jar，请先 ./mvnw package 让 Maven 下载依赖"; exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
"$JAVA_HOME/bin/javac" -d "$WORK" tools/MigrateH2ToMySQL.java
"$JAVA_HOME/bin/java" -cp "$WORK:$H2_JAR:$MY_JAR" MigrateH2ToMySQL \
  "$(pwd)/data" "$MYSQL_HOST" "$MYSQL_PORT" "$MYSQL_USER" "$MYSQL_PASSWORD" $DRY