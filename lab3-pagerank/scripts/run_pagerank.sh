#!/usr/bin/env bash
# Run both implementations on the existing single-node YARN/HDFS cluster.
# An optional existing results directory resumes completed work without deleting it.
set -euo pipefail
LAB3_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$LAB3_ROOT"
if [[ "$(id -un)" != hadooplab ]]; then
  echo 'Use the existing hadooplab account: sudo -iu hadooplab' >&2
  exit 1
fi
if [[ -z "${HADOOP_HOME:-}" && -f "$HOME/hadoop-lab/env.sh" ]]; then
  source "$HOME/hadoop-lab/env.sh"
fi
export HADOOP_MAPRED_HOME="${HADOOP_MAPRED_HOME:-${HADOOP_HOME:-}}"
export HADOOP_CONF_DIR="${HADOOP_CONF_DIR:-${HADOOP_HOME:-}/etc/hadoop}"
export PATH="${HADOOP_HOME:-}/bin:${HADOOP_HOME:-}/sbin:$PATH"
export HADOOP_HEAPSIZE_MAX=256
if [[ -z "${SPARK_HOME:-}" && -d "$HOME/hadoop-lab/spark-4.0.5-bin-hadoop3" ]]; then
  export SPARK_HOME="$HOME/hadoop-lab/spark-4.0.5-bin-hadoop3"
fi
for tool in hadoop hdfs yarn java javac jar python3 timeout; do
  command -v "$tool" >/dev/null || { echo "Missing command: $tool" >&2; exit 1; }
done
if [[ -z "${SPARK_HOME:-}" || ! -x "$SPARK_HOME/bin/spark-submit" ]]; then
  echo 'SPARK_HOME must point to the installed Spark directory.' >&2
  exit 1
fi
if [[ ! -s data/graph.tsv ]]; then
  echo 'Missing input data/graph.tsv.' >&2
  exit 1
fi
mkdir -p results
if (( $# > 1 )); then
  echo 'Usage: bash scripts/run_pagerank.sh [existing-results-directory]' >&2
  exit 2
fi
if (( $# == 1 )); then
  LAB3_RESULT="$(realpath -- "$1")"
  case "$LAB3_RESULT" in "$LAB3_ROOT"/results/*) ;; *) echo 'Resume directory must be inside this project results/.' >&2; exit 2;; esac
  [[ -f "$LAB3_RESULT/HDFS_PATH.txt" && -f "$LAB3_RESULT/graph.tsv" ]] || { echo 'This is not a recorded PageRank run.' >&2; exit 2; }
  LAB3_HDFS="$(cat "$LAB3_RESULT/HDFS_PATH.txt")"
  case "$LAB3_HDFS" in /user/hadooplab/lab3-pagerank/*) ;; *) echo 'Unexpected recorded HDFS path.' >&2; exit 2;; esac
else
  LAB3_RUN_ID="run-$(date +%Y%m%d-%H%M%S)-$$"
  LAB3_RESULT="$LAB3_ROOT/results/$LAB3_RUN_ID"
  LAB3_HDFS="/user/$(id -un)/lab3-pagerank/$LAB3_RUN_ID"
  mkdir -p "$LAB3_RESULT"
  printf '%s\n' "$LAB3_HDFS" > "$LAB3_RESULT/HDFS_PATH.txt"
  cp data/graph.tsv "$LAB3_RESULT/graph.tsv"
fi
printf '%s\n' "$LAB3_RESULT" > results/LATEST.txt
printf '%s\n' RUNNING > "$LAB3_RESULT/STATUS.txt"
trap 'LAB3_EXIT=$?; printf "FAILED (exit %s, line %s)\n" "$LAB3_EXIT" "$LINENO" > "$LAB3_RESULT/STATUS.txt"; echo "FAILED. Logs: $LAB3_RESULT" >&2; exit "$LAB3_EXIT"' ERR
{
  date -Is
  whoami
  printf 'HADOOP_HOME=%s\nHADOOP_CONF_DIR=%s\nSPARK_HOME=%s\n' "$HADOOP_HOME" "$HADOOP_CONF_DIR" "$SPARK_HOME"
  java -version
  javac -version
  hadoop version
  "$SPARK_HOME/bin/spark-submit" --version
  jps -l
  free -h
  df -h .
} 2>&1 | tee "$LAB3_RESULT/environment.txt"
if jps | grep -Eq ' (HMaster|HRegionServer|HQuorumPeer)( |$)'; then
  echo 'Stop HBase before running PageRank on this 2GB VM, then retry.' >&2
  false
fi
LAB3_FS="$(hdfs getconf -confKey fs.defaultFS)"
printf 'fs.defaultFS=%s\nExecution=HDFS+YARN\n' "$LAB3_FS" | tee -a "$LAB3_RESULT/environment.txt"
[[ "$LAB3_FS" == hdfs://* ]] || { echo 'This run requires the existing HDFS configuration.' >&2; false; }
timeout 30s hdfs dfs -ls / >> "$LAB3_RESULT/environment.txt" 2>&1
timeout 30s hdfs dfsadmin -report > "$LAB3_RESULT/hdfs-report.txt" 2>&1
grep -Eq 'Live datanodes \([1-9][0-9]*\)' "$LAB3_RESULT/hdfs-report.txt" || { echo 'No live HDFS DataNode.' >&2; false; }
timeout 30s yarn node -list 2>&1 | tee "$LAB3_RESULT/yarn-nodes.txt"
grep -q RUNNING "$LAB3_RESULT/yarn-nodes.txt" || { echo 'No RUNNING YARN node. Start ResourceManager/NodeManager first.' >&2; false; }
timeout 30s yarn application -list > "$LAB3_RESULT/active-applications.txt" 2>&1
if grep -Eq '^[[:space:]]*application_[0-9]+_[0-9]+' "$LAB3_RESULT/active-applications.txt"; then
  cat "$LAB3_RESULT/active-applications.txt"
  echo 'Another YARN application is active. Let it finish before using this small VM.' >&2
  false
fi
bash scripts/build.sh 2>&1 | tee "$LAB3_RESULT/build.log"
if ! hdfs dfs -test -e "$LAB3_HDFS/input/graph.tsv"; then
  hdfs dfs -mkdir -p "$LAB3_HDFS/input"
  hdfs dfs -put "$LAB3_RESULT/graph.tsv" "$LAB3_HDFS/input/graph.tsv"
fi
hdfs dfs -cat "$LAB3_HDFS/input/graph.tsv" > "$LAB3_RESULT/hdfs-input.tsv"
cmp "$LAB3_RESULT/graph.tsv" "$LAB3_RESULT/hdfs-input.tsv"

# Successful HDFS output from an interrupted terminal can be reused. Failed
# attempts get a new path, preserving their files for diagnosis.
choose_output() {
  local kind="$1" record="$LAB3_RESULT/${1^^}_HDFS_PATH.txt" previous=''
  if [[ -f "$record" ]]; then previous="$(cat "$record")"; fi
  if [[ -n "$previous" ]] && hdfs dfs -test -e "$previous/final/_SUCCESS" \
      && hdfs dfs -test -e "$previous/state-10/_SUCCESS"; then
    printf '%s\n' "$previous"
  else
    local output="$LAB3_HDFS/$kind"
    if hdfs dfs -test -e "$output"; then output="$output-attempt-$(date +%Y%m%d-%H%M%S)-$$"; fi
    printf '%s\n' "$output" > "$record"
    printf '%s\n' "$output"
  fi
}
LAB3_MR_HDFS="$(choose_output mr)"
if ! hdfs dfs -test -e "$LAB3_MR_HDFS/final/_SUCCESS"; then
  LAB3_START="$(date +%s)"
  hadoop jar build/pagerank-mr.jar \
    -Dmapreduce.framework.name=yarn \
    -Dmapreduce.application.classpath="$HADOOP_HOME/share/hadoop/mapreduce/*:$HADOOP_HOME/share/hadoop/mapreduce/lib/*" \
    -Dyarn.app.mapreduce.am.env="HADOOP_MAPRED_HOME=$HADOOP_HOME" \
    -Dmapreduce.map.env="HADOOP_MAPRED_HOME=$HADOOP_HOME" \
    -Dmapreduce.reduce.env="HADOOP_MAPRED_HOME=$HADOOP_HOME" \
    -Dyarn.app.mapreduce.am.resource.mb=512 \
    -Dyarn.app.mapreduce.am.command-opts=-Xmx256m \
    -Dmapreduce.map.memory.mb=512 -Dmapreduce.map.java.opts=-Xmx256m \
    -Dmapreduce.reduce.memory.mb=512 -Dmapreduce.reduce.java.opts=-Xmx256m \
    -Dmapreduce.map.speculative=false -Dmapreduce.reduce.speculative=false \
    "$LAB3_HDFS/input/graph.tsv" "$LAB3_MR_HDFS" 10 \
    2>&1 | tee "$LAB3_RESULT/mapreduce.log"
  printf 'MapReduce elapsed seconds: %s\n' "$(( $(date +%s) - LAB3_START ))" | tee "$LAB3_RESULT/mapreduce-time.txt"
else
  echo "Reusing completed MapReduce output: $LAB3_MR_HDFS"
fi
hdfs dfs -test -e "$LAB3_MR_HDFS/final/_SUCCESS"
hdfs dfs -test -e "$LAB3_MR_HDFS/state-10/_SUCCESS"
printf '%s\n' MR_YARN_OUTPUT_PASS

LAB3_SPARK_HDFS="$(choose_output spark)"
if ! hdfs dfs -test -e "$LAB3_SPARK_HDFS/final/_SUCCESS"; then
  LAB3_START="$(date +%s)"
  "$SPARK_HOME/bin/spark-submit" \
    --class PageRankSpark --master yarn --deploy-mode client \
    --driver-memory 512m --executor-memory 512m --executor-cores 1 --num-executors 1 \
    --conf spark.dynamicAllocation.enabled=false \
    --conf spark.executor.memoryOverhead=128m \
    --conf spark.yarn.am.memory=256m --conf spark.yarn.am.memoryOverhead=128m \
    --conf spark.default.parallelism=1 --conf spark.sql.shuffle.partitions=1 \
    --conf spark.ui.enabled=false \
    --conf spark.yarn.maxAppAttempts=1 \
    --conf spark.speculation=false \
    build/pagerank-spark.jar "$LAB3_HDFS/input/graph.tsv" "$LAB3_SPARK_HDFS" 10 \
    2>&1 | tee "$LAB3_RESULT/spark.log"
  printf 'Spark elapsed seconds: %s\n' "$(( $(date +%s) - LAB3_START ))" | tee "$LAB3_RESULT/spark-time.txt"
else
  echo "Reusing completed Spark output: $LAB3_SPARK_HDFS"
fi
hdfs dfs -test -e "$LAB3_SPARK_HDFS/final/_SUCCESS"
hdfs dfs -test -e "$LAB3_SPARK_HDFS/state-10/_SUCCESS"
printf '%s\n' SPARK_YARN_OUTPUT_PASS

LAB3_MR_LOCAL="$LAB3_RESULT/mr"
LAB3_SPARK_LOCAL="$LAB3_RESULT/spark"
if [[ -e "$LAB3_MR_LOCAL" ]]; then LAB3_MR_LOCAL="$LAB3_RESULT/mr-fetch-$(date +%s)-$$"; fi
if [[ -e "$LAB3_SPARK_LOCAL" ]]; then LAB3_SPARK_LOCAL="$LAB3_RESULT/spark-fetch-$(date +%s)-$$"; fi
hdfs dfs -get "$LAB3_MR_HDFS" "$LAB3_MR_LOCAL"
hdfs dfs -get "$LAB3_SPARK_HDFS" "$LAB3_SPARK_LOCAL"
printf '%s\n' "$LAB3_MR_LOCAL" > "$LAB3_RESULT/MR_LOCAL_PATH.txt"
printf '%s\n' "$LAB3_SPARK_LOCAL" > "$LAB3_RESULT/SPARK_LOCAL_PATH.txt"
python3 scripts/verify_pagerank.py --graph "$LAB3_RESULT/graph.tsv" \
  --mr "$LAB3_MR_LOCAL/final" --spark "$LAB3_SPARK_LOCAL/final" \
  --mr-states "$LAB3_MR_LOCAL" --spark-states "$LAB3_SPARK_LOCAL" \
  2>&1 | tee "$LAB3_RESULT/VERIFY.txt"
{
  printf 'MapReduce final output:\n'
  cat "$LAB3_MR_LOCAL"/final/part-*
  printf 'Spark final output:\n'
  cat "$LAB3_SPARK_LOCAL"/final/part-*
  printf 'YARN application status after both runs:\n'
  yarn application -list -appStates ALL
} 2>&1 | tee "$LAB3_RESULT/summary.txt"
printf '%s\n' SUCCESS > "$LAB3_RESULT/STATUS.txt"
printf '%s\n' PAGERANK_ALL_PASS
printf 'Results: %s\nHDFS: %s\n' "$LAB3_RESULT" "$LAB3_HDFS"
