#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
start_log 00_check
echo '=== Linux / Java / Hadoop ==='
uname -a
id
java -version
javac -version
hadoop version
echo '=== Hadoop configuration ==='
echo "HADOOP_HOME=${HADOOP_HOME:-not-set}"
echo "HADOOP_CONF_DIR=${HADOOP_CONF_DIR:-default}"
hdfs getconf -confKey fs.defaultFS
jps
need_hadoop
echo '=== HDFS DataNode report ==='
hdfs dfsadmin -report
echo 'CHECK OK: 请确认至少有 1 个 Live datanode。'
