#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
mode="${1:-local}"
[[ "$mode" == local || "$mode" == yarn ]] || { echo 'Usage: 04_mapreduce.sh local|yarn' >&2; exit 2; }
start_log "04_mapreduce_$mode"
need_hadoop
need_jar
base="$HDFS_BASE/mapreduce-$mode"
if hdfs dfs -test -e "$base"; then echo "已存在：$base，请使用新的 RUN_ID。" >&2; exit 1; fi
echo "使用真实 HDFS 输入输出，MapReduce 执行模式：$mode"
set -x
hdfs dfs -mkdir -p "$base/input"
hdfs dfs -put data/dedup "$base/input/"
hdfs dfs -put data/sort "$base/input/"
hdfs dfs -put data/relations "$base/input/"
set +x
for task in dedup sort relations; do
  echo "=== Run $task ==="
  hadoop jar "$JAR" "$task" "-Dmapreduce.framework.name=$mode" "$base/input/$task" "$base/output/$task"
  hdfs dfs -test -e "$base/output/$task/_SUCCESS"
  hdfs dfs -cat "$base/output/$task/part-r-*" > "$RESULT_DIR/$task.txt"
  cat "$RESULT_DIR/$task.txt"
  diff -u "expected/$task.txt" "$RESULT_DIR/$task.txt"
  echo "PASS: $task 与题目预期一致"
done
echo "MAPREDUCE ALL PASS ($mode)"
echo "结果：$RESULT_DIR/{dedup,sort,relations}.txt"
