#!/usr/bin/env bash
set -euo pipefail
LAB3_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
export LAB3_ROOT
cd "$LAB3_ROOT"
[[ "$(id -un)" == hadooplab ]] || { echo 'Run sudo -iu hadooplab before starting.' >&2; exit 1; }
source scripts/environment.sh
mkdir -p results "$HBASE_PID_DIR" "$HBASE_LOG_DIR"
trap 'echo "LAB3_STOPPED at line $LINENO; preserve the output and send the last screen." >&2' ERR
has_process() { jps | awk '{print $2}' | grep -qx "$1"; }
start_hdfs() {
  has_process NameNode || hdfs --daemon start namenode
  has_process DataNode || hdfs --daemon start datanode
  for attempt in {1..20}; do
    if hdfs dfsadmin -report 2>/dev/null | grep -Eq 'Live datanodes \([1-9][0-9]*\)'; then
      hdfs dfs -ls /
      return
    fi
    sleep 2
  done
  echo 'HDFS has no live DataNode; see the existing Hadoop logs.' >&2
  return 1
}
start_hbase() {
  mkdir -p build/ready
  local cp
  cp="$("$HBASE_HOME/bin/hbase" classpath)"
  javac -J-Xmx128m -encoding UTF-8 -cp "$cp" -d build/ready src/HBaseReady.java
  for item in 'zookeeper:128' 'master:256' 'regionserver:384'; do
    local daemon="${item%:*}" heap="${item#*:}"
    local pidfile="$HBASE_PID_DIR/hbase-$(id -un)-$daemon.pid"
    if [[ -s "$pidfile" ]] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
      echo "REUSE_HBASE_DAEMON $daemon"
      continue
    fi
    HBASE_HEAPSIZE="$heap" "$HBASE_HOME/bin/hbase-daemon.sh" --config "$HBASE_CONF_DIR" start "$daemon"
  done
  for attempt in {1..8}; do
    echo "Waiting for HBase ($attempt/8)..."
    if timeout 25s env HBASE_HEAPSIZE=128 HBASE_CLASSPATH="$PWD/build/ready:$HBASE_CLASSPATH" \
        "$HBASE_HOME/bin/hbase" HBaseReady > results/hbase-readiness.log 2>&1; then
      cat results/hbase-readiness.log
      return
    fi
    sleep 3
  done
  tail -n 45 results/hbase-readiness.log
  for logfile in "$HBASE_LOG_DIR"/*master*.log "$HBASE_LOG_DIR"/*regionserver*.log; do
    [[ ! -f "$logfile" ]] || tail -n 35 "$logfile"
  done
  return 1
}
stop_hbase() {
  for daemon in regionserver master zookeeper; do
    "$HBASE_HOME/bin/hbase-daemon.sh" --config "$HBASE_CONF_DIR" stop "$daemon"
  done
}
start_yarn() {
  if has_process ResourceManager; then
    local applications
    applications="$(yarn application -list 2>/dev/null)"
    if grep -Eq '^[[:space:]]*application_[0-9]+_[0-9]+' <<< "$applications"; then
      echo "$applications"
      echo 'An existing YARN job is still running; let it finish before retrying.' >&2
      return 1
    fi
  fi
  python3 scripts/configure_yarn.py
  yarn --daemon stop nodemanager || true
  yarn --daemon stop resourcemanager || true
  export YARN_HEAPSIZE=256
  yarn --daemon start resourcemanager
  yarn --daemon start nodemanager
  for attempt in {1..30}; do
    if timeout 15s yarn node -list 2>/dev/null | grep -q RUNNING; then return; fi
    sleep 2
  done
  jps
  echo 'No RUNNING YARN node; inspect Hadoop logs.' >&2
  return 1
}
stage="${1:-all}"
case "$stage" in
  all|setup)
    python3 scripts/prepare_tools.py
    start_hdfs
    ;;
  hbase|pagerank|stop-hbase) ;;
  *) echo 'Usage: bash scripts/run_all.sh [all|setup|hbase|pagerank|stop-hbase]' >&2; exit 2 ;;
esac
if [[ "$stage" == all || "$stage" == hbase ]]; then
  start_hdfs
  start_hbase
  bash scripts/run_hbase.sh
  stop_hbase
fi
if [[ "$stage" == stop-hbase ]]; then stop_hbase; fi
if [[ "$stage" == all || "$stage" == pagerank ]]; then
  start_hdfs
  start_yarn
  bash scripts/run_pagerank.sh
fi
if [[ "$stage" == all ]]; then
  tar -czf "$HOME/lab3-results-$(date +%Y%m%d-%H%M%S).tar.gz" results
  echo 'LAB3_ALL_PASS'
  echo "Project: $PWD"
  echo 'Screenshot: HBASE JAVA AND SHELL CHECK PASSED, PAGERANK_ALL_PASS, final PR values.'
fi
