#!/usr/bin/env bash
# 10 项功能的 Hadoop Shell 对应实现，参数与 Java HdfsTool 保持一致。
set -euo pipefail
usage() {
  echo 'Usage: bash scripts/hdfs_shell.sh upload LOCAL HDFS append|overwrite'
  echo '       download HDFS LOCAL_DIR; cat|stat|lsr|create|delete|mkdir|rmdir HDFS'
  echo '       append HDFS LOCAL head|tail; move SOURCE DESTINATION'
}
[[ $# -ge 1 ]] || { usage; exit 2; }
op=$1
shift
need_args() { [[ "$#" -eq "$expected" ]] || { usage >&2; exit 2; }; }
parent_of() { dirname -- "$1"; }
require_file() { hdfs dfs -test -f "$1" || { echo "Not a file: $1" >&2; exit 1; }; }
case "$op" in
  upload)
    expected=3; need_args "$@"
    [[ -f "$1" ]] || { echo "No local file: $1" >&2; exit 1; }
    [[ "$3" == append || "$3" == overwrite ]] || { echo 'Expected append|overwrite' >&2; exit 2; }
    hdfs dfs -mkdir -p "$(parent_of "$2")"
    if hdfs dfs -test -e "$2"; then
      require_file "$2"
      if [[ "$3" == append ]]; then hdfs dfs -appendToFile "$1" "$2"; else hdfs dfs -put -f "$1" "$2"; fi
    else
      hdfs dfs -put "$1" "$2"
    fi
    ;;
  download)
    expected=2; need_args "$@"; require_file "$1"; mkdir -p "$2"
    name="$(basename -- "$1")"; stem="$name"; ext=''
    if [[ "$name" == *.* && "$name" != .* ]]; then stem="${name%.*}"; ext=".${name##*.}"; fi
    target="$2/$name"; index=0
    while [[ -e "$target" || -L "$target" ]]; do
      index=$((index + 1)); target="$2/${stem}_${index}${ext}"
    done
    # 不使用 -f，保留本地已有文件。
    hdfs dfs -get "$1" "$target"
    echo "Downloaded: $target"
    ;;
  cat)
    expected=1; need_args "$@"; require_file "$1"; hdfs dfs -cat "$1" ;;
  stat)
    expected=1; need_args "$@"
    hdfs dfs -ls -d "$1"
    hdfs dfs -stat 'bytes=%b modification_time_UTC=%y name=%n' "$1"
    echo 'creationTime=unavailable (HDFS FileStatus does not expose creation time)'
    ;;
  lsr)
    expected=1; need_args "$@"
    hdfs dfs -test -d "$1" || { echo "Not a directory: $1" >&2; exit 1; }
    hdfs dfs -ls -R "$1"
    echo 'ls 时间列为修改时间；HDFS 不提供独立创建时间。'
    ;;
  create)
    expected=1; need_args "$@"
    if hdfs dfs -test -e "$1"; then echo "Already exists: $1" >&2; exit 1; fi
    hdfs dfs -mkdir -p "$(parent_of "$1")"
    hdfs dfs -touchz "$1"
    ;;
  delete)
    expected=1; need_args "$@"; require_file "$1"; hdfs dfs -rm "$1" ;;
  mkdir)
    expected=1; need_args "$@"; hdfs dfs -mkdir -p "$1" ;;
  rmdir)
    expected=1; need_args "$@"; hdfs dfs -rmdir "$1" ;;
  append)
    expected=3; need_args "$@"; require_file "$1"
    [[ -f "$2" ]] || { echo "No local file: $2" >&2; exit 1; }
    if [[ "$3" == tail ]]; then
      hdfs dfs -appendToFile "$2" "$1"
    elif [[ "$3" == head ]]; then
      temporary_dir="$(mktemp -d)"
      trap 'rm -f -- "$temporary_dir/old" "$temporary_dir/new"; rmdir -- "$temporary_dir"' EXIT
      hdfs dfs -cat "$1" > "$temporary_dir/old"
      cat -- "$2" "$temporary_dir/old" > "$temporary_dir/new"
      hdfs dfs -put -f "$temporary_dir/new" "$1"
    else
      echo 'Expected head|tail' >&2; exit 2
    fi
    ;;
  move)
    expected=2; need_args "$@"; require_file "$1"
    if hdfs dfs -test -e "$2"; then echo "Destination exists: $2" >&2; exit 1; fi
    hdfs dfs -mkdir -p "$(parent_of "$2")"
    hdfs dfs -mv "$1" "$2"
    ;;
  *) usage >&2; exit 2 ;;
esac
