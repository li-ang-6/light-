#!/usr/bin/env bash
# 由其他脚本 source；不修改系统或 Hadoop 配置。
set -euo pipefail
LAB_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$LAB_ROOT"
export RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)-$$}"
[[ "$RUN_ID" =~ ^[a-zA-Z0-9_-]+$ ]] || { echo 'RUN_ID 只能含字母、数字、下划线、连字符' >&2; exit 1; }
RESULT_DIR="$LAB_ROOT/results/$RUN_ID"
HDFS_BASE="/user/$(id -un)/lab1/$RUN_ID"
JAR="$LAB_ROOT/target/bigdata-lab1.jar"
mkdir -p "$RESULT_DIR"

need_hadoop() {
  command -v hadoop >/dev/null || { echo '找不到 hadoop，请设置 HADOOP_HOME 和 PATH。' >&2; exit 1; }
  command -v hdfs >/dev/null || { echo '找不到 hdfs，请设置 PATH。' >&2; exit 1; }
  local uri
  uri="$(hdfs getconf -confKey fs.defaultFS)"
  [[ "$uri" == hdfs://* ]] || { echo "fs.defaultFS=$uri，不是 HDFS；请检查 HADOOP_CONF_DIR。" >&2; exit 1; }
  hdfs dfs -ls / >/dev/null
}

need_jar() {
  [[ -f "$JAR" ]] || { echo '请先运行 bash scripts/build.sh' >&2; exit 1; }
}

start_log() {
  exec > >(tee "$RESULT_DIR/$1.log") 2>&1
  echo "RUN_ID=$RUN_ID"
  echo "RESULT_DIR=$RESULT_DIR"
  echo "HDFS_BASE=$HDFS_BASE"
}
