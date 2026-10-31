#!/usr/bin/env bash
# 上传输入并提交到 YARN；每次运行都保留独立记录。
set -euo pipefail
LAB_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$LAB_ROOT"
LAB_RUN_ID="run-$(date +%Y%m%d-%H%M%S)-$$"
LAB_RESULT="$LAB_ROOT/results/$LAB_RUN_ID"
mkdir -p "$LAB_RESULT"
printf '%s\n' "$LAB_RESULT" > results/LATEST.txt
printf '%s\n' RUNNING > "$LAB_RESULT/STATUS.txt"
trap 'LAB_EXIT=$?; printf "FAILED (exit %s, line %s)\n" "$LAB_EXIT" "$LINENO" > "$LAB_RESULT/STATUS.txt"; echo "运行失败，请查看：$LAB_RESULT" >&2; exit "$LAB_EXIT"' ERR

for tool in hadoop hdfs yarn java javac jar python3; do
  if ! command -v "$tool" >/dev/null; then
    echo "找不到 $tool，请先加载 Hadoop 和 JDK 环境。" >&2
    false
  fi
done
if [[ -z "${HADOOP_HOME:-}" || ! -d "$HADOOP_HOME/share/hadoop/mapreduce" ]]; then
  echo '请先将 HADOOP_HOME 设置为已有 Hadoop 安装目录。' >&2
  false
fi
shopt -s nullglob
LAB_DOCS=(data/documents/*.txt)
if (( ${#LAB_DOCS[@]} < 10 )); then
  echo "英文文档不足 10 个，请先执行 python3 scripts/download_docs.py。" >&2
  false
fi
if [[ ! -s data/stopwords.txt ]]; then
  echo '缺少 data/stopwords.txt。' >&2
  false
fi

{
  date -Is
  whoami
  grep '^PRETTY_NAME=' /etc/os-release
  java -version
  javac -version
  hadoop version
  jps
  echo 'Execution mode: YARN + HDFS'
} 2>&1 | tee "$LAB_RESULT/environment.txt"
LAB_DEFAULT_FS="$(hdfs getconf -confKey fs.defaultFS)"
printf 'fs.defaultFS=%s\n' "$LAB_DEFAULT_FS" | tee -a "$LAB_RESULT/environment.txt"
if [[ "$LAB_DEFAULT_FS" != hdfs://* ]]; then
  echo '当前 fs.defaultFS 不是 hdfs:// 地址，请加载原有 HDFS 配置再运行。' >&2
  false
fi
hdfs dfs -ls / >> "$LAB_RESULT/environment.txt" 2>&1
hdfs dfsadmin -report >> "$LAB_RESULT/environment.txt" 2>&1
if ! grep -Eq 'Live datanodes \([1-9][0-9]*\)' "$LAB_RESULT/environment.txt"; then
  echo 'HDFS 没有在线 DataNode，请检查服务状态后再运行。' >&2
  false
fi
yarn node -list 2>&1 | tee "$LAB_RESULT/yarn-nodes.txt"
if ! grep -q 'RUNNING' "$LAB_RESULT/yarn-nodes.txt"; then
  echo '未发现 RUNNING 的 YARN 节点，请先启动 ResourceManager 和 NodeManager。' >&2
  false
fi

bash scripts/build.sh 2>&1 | tee "$LAB_RESULT/build.log"
LAB_HDFS="/user/$(id -un)/lab2-inverted-index/$LAB_RUN_ID"
printf '%s\n' "$LAB_HDFS" > "$LAB_RESULT/HDFS_PATH.txt"
hdfs dfs -mkdir -p "$LAB_HDFS/input"
hdfs dfs -put "${LAB_DOCS[@]}" "$LAB_HDFS/input/"
hdfs dfs -put data/stopwords.txt "$LAB_HDFS/stopwords.txt"
{
  printf 'Input documents: %s\n' "${#LAB_DOCS[@]}"
  hdfs dfs -ls "$LAB_HDFS/input"
  echo 'Stopwords:'
  cat data/stopwords.txt
  echo 'First document (first 20 lines):'
  head -n 20 "${LAB_DOCS[0]}"
} 2>&1 | tee "$LAB_RESULT/input.txt"

echo "提交 YARN 作业，结果目录：$LAB_RESULT"
hadoop jar build/inverted-index.jar InvertedIndex \
  -Dmapreduce.framework.name=yarn \
  -Dmapreduce.application.classpath="$HADOOP_HOME/share/hadoop/mapreduce/*:$HADOOP_HOME/share/hadoop/mapreduce/lib/*" \
  -Dyarn.app.mapreduce.am.env="HADOOP_MAPRED_HOME=$HADOOP_HOME" \
  -Dmapreduce.map.env="HADOOP_MAPRED_HOME=$HADOOP_HOME" \
  -Dmapreduce.reduce.env="HADOOP_MAPRED_HOME=$HADOOP_HOME" \
  -Dyarn.app.mapreduce.am.resource.mb=512 \
  -Dyarn.app.mapreduce.am.command-opts=-Xmx256m \
  -Dmapreduce.map.memory.mb=512 \
  -Dmapreduce.map.java.opts=-Xmx256m \
  -Dmapreduce.reduce.memory.mb=512 \
  -Dmapreduce.reduce.java.opts=-Xmx256m \
  -Dmapreduce.map.speculative=false \
  -Dmapreduce.reduce.speculative=false \
  "$LAB_HDFS/input" "$LAB_HDFS/output" "$LAB_HDFS/stopwords.txt" \
  2>&1 | tee "$LAB_RESULT/run.log"

hdfs dfs -test -e "$LAB_HDFS/output/_SUCCESS"
hdfs dfs -cat "$LAB_HDFS/output/part-r-*" > "$LAB_RESULT/actual.txt"
python3 scripts/verify.py "$LAB_RESULT/actual.txt" 2>&1 | tee "$LAB_RESULT/VERIFY.txt"
printf '%s\n' SUCCESS > "$LAB_RESULT/STATUS.txt"
echo 'YARN JOB AND CHECK PASSED'
printf '结果和日志：%s\n' "$LAB_RESULT"
printf 'HDFS 路径：%s\n' "$LAB_HDFS"
