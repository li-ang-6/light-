#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export LAB3_ROOT="$PWD"
source scripts/environment.sh
echo '=== ENVIRONMENT ==='
whoami
java -version 2>&1 | head -n 1
hadoop version | head -n 1
jps
hdfs getconf -confKey fs.defaultFS
yarn node -list
if [[ -f results/HBASE_LATEST.txt ]]; then
  hb="$(cat results/HBASE_LATEST.txt)"
  echo '=== HBASE JAVA ==='
  grep -E '^(SOURCE DATA NOTE|SC includes|COUNT|PASS:|HBASE JAVA ALL PASS)' "$hb/java.log"
  echo '=== HBASE SHELL ==='
  tail -n 18 "$hb/shell.log"
fi
if [[ -f results/LATEST.txt ]]; then
  result="$(cat results/LATEST.txt)"
  echo '=== PAGERANK CHECK ==='
  cat "$result/STATUS.txt"
  [[ ! -f "$result/VERIFY.txt" ]] || cat "$result/VERIFY.txt"
  [[ ! -f "$result/summary.txt" ]] || cat "$result/summary.txt"
fi
