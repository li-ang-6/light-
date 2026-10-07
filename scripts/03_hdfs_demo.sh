#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
mode="${1:-java}"
[[ "$mode" == java || "$mode" == shell ]] || { echo 'Usage: 03_hdfs_demo.sh java|shell' >&2; exit 2; }
start_log "03_hdfs_$mode"
need_hadoop
if [[ "$mode" == java ]]; then need_jar; fi
base="$HDFS_BASE/hdfs-$mode"
downloads="$RESULT_DIR/downloads-$mode"
if hdfs dfs -test -e "$base"; then echo "已存在：$base，请使用新的 RUN_ID。" >&2; exit 1; fi
tool() {
  echo "+ $mode HDFS $*"
  if [[ "$mode" == java ]]; then hadoop jar "$JAR" hdfs "$@"; else bash scripts/hdfs_shell.sh "$@"; fi
}
expect_failure() {
  if tool "$@"; then echo 'FAIL: 操作应当被拒绝' >&2; exit 1; else echo 'PASS: 按预期拒绝该操作'; fi
}
check_content() {
  hdfs dfs -cat "$base/data.txt" > "$RESULT_DIR/hdfs-$mode-actual.txt"
  diff -u "$1" "$RESULT_DIR/hdfs-$mode-actual.txt"
  echo 'PASS: 文件内容与预期一致'
}
echo '=== (1) 上传、同名追加、同名覆盖 ==='
tool upload data/hdfs/original.txt "$base/data.txt" overwrite
tool upload data/hdfs/tail.txt "$base/data.txt" append
cat data/hdfs/original.txt data/hdfs/tail.txt > "$RESULT_DIR/expected-append-$mode.txt"
check_content "$RESULT_DIR/expected-append-$mode.txt"
tool upload data/hdfs/original.txt "$base/data.txt" overwrite
check_content data/hdfs/original.txt
echo '=== (2) 下载同名文件，自动重命名 ==='
tool download "$base/data.txt" "$downloads"
tool download "$base/data.txt" "$downloads"
diff -u data/hdfs/original.txt "$downloads/data.txt"
diff -u data/hdfs/original.txt "$downloads/data_1.txt"
ls -l "$downloads"
echo '=== (3)(4) 显示内容和元数据 ==='
tool cat "$base/data.txt"
tool stat "$base/data.txt"
echo '=== (6) 自动创建父目录及空文件；拒绝覆盖已有文件 ==='
tool create "$base/nested/a/b/empty.txt"
expect_failure create "$base/data.txt"
echo '=== (5) 递归列出文件 ==='
tool lsr "$base"
echo '=== (7) 创建目录，只删除空目录 ==='
tool mkdir "$base/dirs/a/b"
expect_failure rmdir "$base/dirs/a"
hdfs dfs -test -d "$base/dirs/a/b"
tool rmdir "$base/dirs/a/b"
tool rmdir "$base/dirs/a"
echo '=== (8) 文件头和文件尾追加 ==='
tool append "$base/data.txt" data/hdfs/head.txt head
tool append "$base/data.txt" data/hdfs/tail.txt tail
cat data/hdfs/head.txt data/hdfs/original.txt data/hdfs/tail.txt > "$RESULT_DIR/expected-both-$mode.txt"
check_content "$RESULT_DIR/expected-both-$mode.txt"
tool cat "$base/data.txt"
echo '=== (10) 移动文件 ==='
tool move "$base/data.txt" "$base/moved/data.txt"
hdfs dfs -test -f "$base/moved/data.txt"
if hdfs dfs -test -e "$base/data.txt"; then echo 'FAIL: 源文件仍存在' >&2; exit 1; fi
expect_failure move "$base/moved/data.txt" "$base/nested/a/b/empty.txt"
echo '=== (6)(9) 删除文件；拒绝用文件删除命令删除目录 ==='
tool delete "$base/nested/a/b/empty.txt"
tool create "$base/delete-me.txt"
tool delete "$base/delete-me.txt"
expect_failure delete "$base/moved"
tool lsr "$base"
echo "HDFS $mode ALL PASS"
