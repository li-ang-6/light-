#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
mode="${1:-local}"
[[ "$mode" == local || "$mode" == yarn ]] || { echo 'Usage: run_all.sh local|yarn' >&2; exit 2; }
echo "本轮全部结果保存到：$RESULT_DIR"
bash scripts/00_check.sh
bash scripts/build.sh
bash scripts/01_linux.sh
bash scripts/02_hadoop_basic.sh
bash scripts/03_hdfs_demo.sh java
bash scripts/03_hdfs_demo.sh shell
bash scripts/04_mapreduce.sh "$mode"
echo "ALL DONE: $RESULT_DIR"
