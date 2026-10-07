#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
start_log 01_linux
[[ -f "$HOME/.bashrc" ]] || { echo '当前用户没有 ~/.bashrc。' >&2; exit 1; }
# 题目使用固定系统路径，开始前拒绝碰已有同名文件，避免误覆盖。
for item in /tmp/a /tmp/a1 /tmp/test /tmp/hello /usr/bashrc1 /usr/test /usr/test2 /test /test.tar.gz; do
  if [[ -e "$item" || -L "$item" ]]; then
    echo "停止：$item 已存在。先查看它是不是你的旧实验文件，按 docs/操作步骤.md 手动处理。" >&2
    exit 1
  fi
done
sudo -v
set -x
cd /usr/local
pwd
cd ..
pwd
cd ~
pwd
ls -la /usr
cd /tmp
mkdir a
ls -l /tmp
mkdir -p a1/a2/a3/a4
rmdir a
rmdir -p a1/a2/a3/a4
ls -l /tmp
sudo cp "$HOME/.bashrc" /usr/bashrc1
mkdir /tmp/test
sudo cp -r /tmp/test /usr/
sudo mv /usr/bashrc1 /usr/test/
sudo mv /usr/test /usr/test2
sudo rm /usr/test2/bashrc1
sudo rm -r /usr/test2
cat "$HOME/.bashrc"
tac "$HOME/.bashrc"
set +x
if [[ -t 0 ]]; then
  echo 'more 翻页：空格下一页，q 退出后脚本继续。'
  # 日志通过 tee 保存；分页器直接写终端，否则 more 会退化为连续输出。
  more "$HOME/.bashrc" > /dev/tty
else
  echo '当前无交互终端；请另行运行 more ~/.bashrc 并截图。'
fi
set -x
head -n 20 "$HOME/.bashrc"
head -n -50 "$HOME/.bashrc"
tail -n 20 "$HOME/.bashrc"
tail -n +51 "$HOME/.bashrc"
touch /tmp/hello
stat /tmp/hello
touch -d '5 days ago' /tmp/hello
stat /tmp/hello
sudo chown root /tmp/hello
ls -l /tmp/hello
find "$HOME" -type f -name .bashrc
sudo mkdir /test
sudo tar -czf /test.tar.gz -C / test
sudo tar -xzf /test.tar.gz -C /tmp
ls -ld /test /tmp/test
ls -l /test.tar.gz
set +x
if grep -n 'examples' "$HOME/.bashrc"; then
  echo 'grep 找到了 examples。'
else
  echo 'grep 没有找到 examples：取决于你的 .bashrc 内容，如实记录即可。'
fi
echo 'LINUX DONE：保留 /tmp/hello、/tmp/test、/test 和 /test.tar.gz 供截图。'
