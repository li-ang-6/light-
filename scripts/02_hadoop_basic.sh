#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
start_log 02_hadoop_basic
need_hadoop
[[ "$(id -un)" == hadoop ]] || { echo '本题要求使用 hadoop 用户，请先 su - hadoop。' >&2; exit 1; }
[[ -d /usr/local/hadoop ]] || { echo '题目要求 /usr/local/hadoop 存在；参见操作步骤中的路径说明。' >&2; exit 1; }
if [[ -e /usr/local/hadoop/test || -L /usr/local/hadoop/test ]]; then
  echo '/usr/local/hadoop/test 已存在，请先检查并备份旧实验结果。' >&2; exit 1
fi
set -x
hdfs dfs -mkdir -p /user/hadoop
hdfs dfs -mkdir -p /user/hadoop/test
hdfs dfs -ls /user/hadoop
hdfs dfs -put -f "$HOME/.bashrc" /user/hadoop/test/.bashrc
hdfs dfs -ls /user/hadoop/test
set +x
if [[ -w /usr/local/hadoop ]]; then
  hdfs dfs -get /user/hadoop/test /usr/local/hadoop/
else
  # 先以 hadoop 身份下载，再只对本地复制使用 sudo，避免换成 HDFS root 身份。
  mkdir -p "$RESULT_DIR/basic-download"
  hdfs dfs -get /user/hadoop/test "$RESULT_DIR/basic-download/"
  sudo cp -a "$RESULT_DIR/basic-download/test" /usr/local/hadoop/
fi
ls -la /usr/local/hadoop/test
echo 'HADOOP BASIC PASS'
