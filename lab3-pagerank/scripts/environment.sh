#!/usr/bin/env bash
# Reuse the environment confirmed in the user's original Ubuntu VM.
if [[ -z "${LAB3_ROOT:-}" ]]; then
  export LAB3_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
fi
source "$HOME/hadoop-lab/env.sh"
export HBASE_HOME="${HBASE_HOME:-$HOME/hadoop-lab/lab3-tools/hbase-2.6.7}"
export SPARK_HOME="${SPARK_HOME:-$HOME/hadoop-lab/spark-4.0.5-bin-hadoop3}"
export HBASE_CONF_DIR="$LAB3_ROOT/.runtime/hbase-conf"
export HBASE_PID_DIR="$HOME/hadoop-lab/lab3-runtime/pids"
export HBASE_LOG_DIR="$HOME/hadoop-lab/lab3-runtime/logs"
export HBASE_DISABLE_HADOOP_CLASSPATH_LOOKUP=true
export HBASE_CLASSPATH="$HADOOP_CONF_DIR${HBASE_CLASSPATH:+:$HBASE_CLASSPATH}"
export HADOOP_HEAPSIZE_MAX=256
export PATH="$HBASE_HOME/bin:$SPARK_HOME/bin:$PATH"
