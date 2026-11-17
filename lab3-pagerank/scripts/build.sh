#!/usr/bin/env bash
set -euo pipefail
LAB3_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$LAB3_ROOT"
for tool in hadoop javac jar; do
  command -v "$tool" >/dev/null || { echo "Missing command: $tool; load the existing environment." >&2; exit 1; }
done
if [[ -z "${HADOOP_HOME:-}" || ! -d "$HADOOP_HOME/share/hadoop/mapreduce" ]]; then
  echo 'HADOOP_HOME must point to the existing Hadoop installation.' >&2
  exit 1
fi
if [[ -z "${SPARK_HOME:-}" || ! -d "$SPARK_HOME/jars" ]]; then
  echo 'Set SPARK_HOME to the Spark installation (containing bin/spark-submit and jars).' >&2
  exit 1
fi
mkdir -p build/mr-classes build/spark-classes
LAB3_HADOOP_CP="$(hadoop classpath --glob)"
javac -J-Xmx256m --release 17 -encoding UTF-8 -cp "$LAB3_HADOOP_CP" \
  -d build/mr-classes src/PageRankMR.java
jar cfe build/pagerank-mr.jar PageRankMR -C build/mr-classes .
javac -J-Xmx256m --release 17 -encoding UTF-8 -cp "$SPARK_HOME/jars/*" \
  -d build/spark-classes src/PageRankSpark.java
jar cfe build/pagerank-spark.jar PageRankSpark -C build/spark-classes .
printf '%s\n' 'PAGERANK_BUILD_PASS'
ls -lh build/pagerank-mr.jar build/pagerank-spark.jar
