#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${HBASE_HOME:?Set HBASE_HOME to the existing or prepared HBase installation first}"
[[ -x "$HBASE_HOME/bin/hbase" ]] || { echo "Cannot execute $HBASE_HOME/bin/hbase" >&2; exit 1; }
command -v javac >/dev/null || { echo 'javac is required' >&2; exit 1; }
run_id="hbase-$(date +%Y%m%d-%H%M%S)-$$"
result_dir="$PWD/results/$run_id"
mkdir -p "$result_dir" build/hbase
printf '%s\n' "$result_dir" > results/HBASE_LATEST.txt
echo "HBase result directory: $result_dir"
"$HBASE_HOME/bin/hbase" version > "$result_dir/version.log" 2>&1
hbase_classpath=$("$HBASE_HOME/bin/hbase" classpath)
javac -encoding UTF-8 -cp "$hbase_classpath" -d build/hbase src/HBaseLab.java \
  2>&1 | tee "$result_dir/build.log"
export HBASE_CLASSPATH="$PWD/build/hbase${HBASE_CLASSPATH:+:$HBASE_CLASSPATH}"
echo 'Running HBase Java API demonstrations...'
"$HBASE_HOME/bin/hbase" HBaseLab 2>&1 | tee "$result_dir/java.log"
grep -q '^HBASE JAVA ALL PASS$' "$result_dir/java.log"
echo 'Running equivalent HBase Shell commands...'
"$HBASE_HOME/bin/hbase" shell -n data/hbase-shell.txt </dev/null 2>&1 | tee "$result_dir/shell.log"
grep -q '^HBASE SHELL ALL PASS$' "$result_dir/shell.log"
echo "HBASE JAVA AND SHELL CHECK PASSED: $result_dir"
