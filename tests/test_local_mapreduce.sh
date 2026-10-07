#!/usr/bin/env bash
# 真正运行 Hadoop MapReduce；本测试用本地文件系统，与正式 HDFS 演示分开。
set -euo pipefail
cd "$(dirname "$0")/.."
repo="$(pwd)"
jar_path="$repo/target/bigdata-lab1.jar"
[[ -f "$jar_path" ]] || bash scripts/build.sh
work="$repo/results/local-tests-$(date +%Y%m%d-%H%M%S)-$$"
mkdir -p "$work"
run_job() {
  hadoop jar "$jar_path" "$1" -Dmapreduce.framework.name=local -Dfs.defaultFS=file:/// "$2" "$3"
}
for task in dedup sort relations; do
  run_job "$task" "$repo/data/$task" "$work/sample-$task"
  diff -u "expected/$task.txt" "$work/sample-$task/part-r-00000"
  echo "PASS: sample $task"
done
mkdir -p "$work/edge-sort" "$work/edge-relations" "$work/edge-dedup"
printf '%s\n' 10 -2 10 0 -2 9223372036854775807 > "$work/edge-sort/numbers.txt"
printf '1\t-2\n2\t-2\n3\t0\n4\t10\n5\t10\n6\t9223372036854775807\n' > "$work/sort-expected.txt"
run_job sort "$work/edge-sort" "$work/sort-output"
diff -u "$work/sort-expected.txt" "$work/sort-output/part-r-00000"
echo 'PASS: numeric ordering, negatives, duplicates and long boundary'

# 同一祖孙对通过两个不同中间人得到，并包含重复输入，应只输出一次。
printf 'child parent\nA B\nA C\nB D\nC D\nA B\n' > "$work/edge-relations/relations.txt"
printf 'grandchild\tgrandparent\nA\tD\n' > "$work/relations-expected.txt"
run_job relations "$work/edge-relations" "$work/relations-output"
diff -u "$work/relations-expected.txt" "$work/relations-output/part-r-00000"
echo 'PASS: duplicate edges and duplicate grandparents across joins'

printf '20170101  x\n20170101\tx\n\n20170101 y\n' > "$work/edge-dedup/records.txt"
printf '20170101\tx\n20170101\ty\n' > "$work/dedup-expected.txt"
run_job dedup "$work/edge-dedup" "$work/dedup-output"
diff -u "$work/dedup-expected.txt" "$work/dedup-output/part-r-00000"
echo 'PASS: field whitespace normalization and blank lines'
echo "LOCAL MAPREDUCE TESTS ALL PASS: $work"
