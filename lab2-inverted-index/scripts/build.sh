#!/usr/bin/env bash
# 在已有 Hadoop 环境中编译，无需 Maven 或另行下载依赖。
set -euo pipefail
LAB_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$LAB_ROOT"

for tool in hadoop javac jar; do
  command -v "$tool" >/dev/null || { echo "找不到 $tool，请先加载 Hadoop 和 JDK 环境。" >&2; exit 1; }
done
mkdir -p build/classes
LAB_CLASSPATH="$(hadoop classpath --glob)"
javac -encoding UTF-8 -cp "$LAB_CLASSPATH" -d build/classes src/InvertedIndex.java
jar cfe build/inverted-index.jar InvertedIndex -C build/classes .
echo "BUILD OK"
ls -lh build/inverted-index.jar
