#!/usr/bin/env bash
# 用已安装 Hadoop 的依赖编译，不需要 Maven 下载依赖。
set -euo pipefail
cd "$(dirname "$0")/.."
command -v hadoop >/dev/null || { echo '找不到 hadoop，请先配置 PATH。' >&2; exit 1; }
command -v javac >/dev/null || { echo '找不到 javac，请使用 JDK 而不只是 JRE。' >&2; exit 1; }
mkdir -p target/classes
find src/main/java -name '*.java' -print > target/sources.txt
javac -encoding UTF-8 -source 8 -target 8 -classpath "$(hadoop classpath --glob)" -d target/classes @target/sources.txt
jar cfe target/bigdata-lab1.jar edu.sdu.bigdata.Main -C target/classes .
echo 'BUILD OK: target/bigdata-lab1.jar'
