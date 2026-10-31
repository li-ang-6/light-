# 实验二 文档倒排索引

本项目使用 Java Hadoop MapReduce，读取 10 篇公开的 Python 英文技术文档，统一小写、按连续英文字母分词、过滤停词，输出每个单词在各文档中的词频和总词频。项目已包含源码、停词表和下载脚本；10 篇原始文档需要在 Ubuntu 按第 3 步下载，实际结果由你在虚拟机运行后产生。

## 1 上传到原来的 GitHub 仓库

Windows 文件夹：`C:\Users\lenovo\Desktop\大数据作业\lab2-inverted-index`。

1. 在 Windows 浏览器登录 GitHub，进入 https://github.com/li-ang-6/light-/upload/main 。
2. 把 **lab2-inverted-index 整个文件夹**拖进上传区域，保留这一层目录。不要把里面的文件散放到仓库根目录。
3. 确认列表包含 `lab2-inverted-index/src/InvertedIndex.java`、`scripts` 下的 5 个脚本、`data/stopwords.txt`、`data/NOTICE.txt` 和本文档。`data/documents`、`data/sources.tsv` 会在 Ubuntu 执行下载后生成，初次上传时没有这两项是正常的。
4. 填写提交说明“添加实验二文档倒排索引”，点击 Commit changes。原仓库已有实验一，这次新增子目录即可。
5. 提交后进入该目录检查文件齐全，确认仓库显示 Public。报告可写仓库主页地址，也可写 `https://github.com/li-ang-6/light-/tree/main/lab2-inverted-index`。

上传源文件，不要只上传 ZIP。以后本地生成的 `build`、`results` 不需要上传；网页上传不会按 `.gitignore` 自动筛除文件，应自己避开它们。截图可以单独上传，但不是运行代码的前置条件。

GitHub 官方网页上传说明：https://docs.github.com/en/repositories/working-with-files/managing-files/adding-a-file-to-a-repository

## 2 从截图中的登录界面进入

先点 `light` 并输入该账户的登录密码。进入桌面后按 Ctrl+Alt+T 打开终端，输入：

```bash
sudo -iu hadooplab
```

这里询问的是 **light 的密码**。密码输入时不显示字符是正常的。成功后执行：

```bash
whoami
export HADOOP_HOME=/home/hadooplab/hadoop-lab/hadoop-3.5.0
export HADOOP_MAPRED_HOME="$HADOOP_HOME"
export PATH="$HADOOP_HOME/bin:$HADOOP_HOME/sbin:$PATH"
hadoop version
java -version
javac -version
```

`whoami` 应为 `hadooplab`。安装路径沿用上次实验中确认过的位置，如果现在提示不存在，应先确认虚拟机是否更换，不要重新格式化 HDFS。

登录页的 `hadooplab` 是 Linux 用户账户，不是 Hadoop 软件入口。能正常登录它的桌面也可以运行命令；本教程沿用之前成功的 `light` 桌面加终端切换方法。

Linux 终端粘贴是 Ctrl+Shift+V。若 Windows 无法复制到虚拟机，在 VirtualBox 的“设备 → 共享粘贴板”中启用双向；该功能依赖已安装的增强功能。没有共享粘贴板时，可以在 Ubuntu 的 light 桌面浏览器打开 GitHub 上的本文档，然后复制命令到终端。

下面所有 Linux 命令都在切换后的同一个终端执行，不是在 Windows PowerShell 中执行。

## 3 下载刚上传的项目

确认第一步已提交完成，再执行整段：

```bash
LAB2_DOWNLOAD=$(mktemp -d "$HOME/lab2-download-XXXXXX")
export LAB2_DOWNLOAD
python3 - <<'PY'
import io
import os
import urllib.request
import zipfile
url = 'https://codeload.github.com/li-ang-6/light-/zip/refs/heads/main'
with urllib.request.urlopen(url, timeout=60) as response:
    data = response.read()
with zipfile.ZipFile(io.BytesIO(data)) as archive:
    archive.extractall(os.environ['LAB2_DOWNLOAD'])
print('下载完成')
PY
cd "$LAB2_DOWNLOAD/light--main/lab2-inverted-index"
pwd
ls src scripts data
printf '%s\n' "$PWD" > "$HOME/lab2-current-project.txt"
```

源码存进新目录，不覆盖上次实验。无需安装 Git，也不需要在 Linux 输入 GitHub 密码。下载报错时先处理网络问题，不要继续后面的命令。

接下来必须执行以下命令下载 10 篇文档。数据来源是 Python 3.14 官方教程公开源文本；下载成功后会生成 `data/documents/document01.txt` 至 `document10.txt`，以及记录来源、获取时间和校验值的 `data/sources.tsv`。保留原始 RST 标记和代码示例，因此统计包含这些内容中的英文词。

```bash
python3 scripts/download_docs.py
find data/documents -maxdepth 1 -name '*.txt' -type f | wc -l
```

应看到 `Saved 10 UTF-8 documents`，最后数量为 `10`，再继续下一步。若下载失败，保留具体报错，检查 Ubuntu 浏览器能否访问 https://docs.python.org/3.14/ 。未完成下载不能运行后续实验。原始资料本次未在 Windows 下载成功，原因是本机沙箱禁止联网；源码与下载脚本仍已准备好。重新下载后内容可能随官方更新改变，因此应以本次实际运行输出为准。

按附件要求，GitHub 中需要有实验代码；本教程先上传代码和下载脚本，原始文档与来源清单在 Ubuntu 本地生成，用于运行和报告截图。

## 4 启动已有 HDFS 和 YARN

先查看进程：

```bash
jps
```

只有缺少对应进程时才执行对应启动命令：

```bash
hdfs --daemon start namenode
hdfs --daemon start datanode
```

已有进程无需重复启动。确认 HDFS：

```bash
hdfs getconf -confKey fs.defaultFS
hdfs dfs -ls /
hdfs dfsadmin -report | grep -E 'Live datanodes|Dead datanodes'
```

应使用 `hdfs://...`，并至少有 1 个在线 DataNode。`-ls /` 在空文件系统中没有输出也可能是正常情况。不要执行 `hdfs namenode -format`。

本实验要实际提交到 YARN。上次实验一使用过 local 模式，这次需要 ResourceManager 和 NodeManager。下面的脚本为这台个人单机虚拟机设置 shuffle 服务，以及 1536 MB 的 YARN 可调度内存和 512 MB 最小容器；会备份原 `yarn-site.xml`，不改 HDFS。只在个人实验虚拟机使用，不用于学校多人共享集群。

```bash
python3 scripts/configure_yarn.py
yarn --daemon stop nodemanager
yarn --daemon stop resourcemanager
yarn --daemon start resourcemanager
yarn --daemon start nodemanager
sleep 8
jps
yarn node -list
```

停止尚未启动的进程时提示“没有进程”可以忽略；正在运行其他 YARN 作业时应先等它完成。最后应看到 `NameNode`、`DataNode`、`ResourceManager`、`NodeManager`，以及至少一个 `RUNNING` 节点。如果节点还没注册，稍等后再次执行 `yarn node -list`。

如果虚拟机只有约 2 GB 内存，关闭其他大型程序；若出现进程被系统杀死或内存不足，将虚拟机正常关机，在 VirtualBox 设置中分配 4 GB 内存后开机，重新登录并执行环境变量和服务启动命令。

Apache 官方单机 Hadoop/YARN 配置说明：https://hadoop.apache.org/docs/current/hadoop-project-dist/hadoop-common/SingleCluster.html

## 5 编译并运行

在项目目录执行：

```bash
bash scripts/run.sh
```

脚本会检查环境、编译 Java、将输入和停词上传到 HDFS、通过 YARN 执行 MapReduce、取回输出，并用独立 Python 计数核对全部结果。每次使用新目录，保留旧实验记录。

要等终端重新出现可输入命令的提示符，并确认成功信息。出错就停在该步排查，不要用预期结果代替实测输出。完整日志位于 `results`，本轮目录记录在 `results/LATEST.txt`。

## 6 获取实验截图

把终端最大化、适当调大字体。Windows 按 Win+Shift+S 截图；如果快捷键被虚拟机捕获，先按右 Ctrl 释放键盘或点击 Windows 区域再截。截图后点通知打开截图工具，保存 PNG。建议保存在 Windows 的“桌面\大数据作业\实验二截图”文件夹。

截图保留命令和结果，不只截 PASS 字样；一屏放不下可分两张。以下 6 组截图对应报告内容，不是老师硬性指定的数量。

**图 1 环境与集群**

```bash
clear
whoami
grep '^PRETTY_NAME=' /etc/os-release
java -version
hadoop version | head -n 1
jps
hdfs dfsadmin -report | grep -E 'Live datanodes|Dead datanodes'
yarn node -list
```

截版本、4 个服务进程、在线 DataNode 和 RUNNING 节点。

**图 2 输入文档及来源**

```bash
clear
find data/documents -maxdepth 1 -name '*.txt' -type f | sort
find data/documents -maxdepth 1 -name '*.txt' -type f | wc -l
head -n 20 data/documents/document01.txt
cut -f1-3 data/sources.tsv | head -n 5
```

应显示至少 10 个文档和真实英文内容。完整来源保存在 sources.tsv，不必把长网址挤进一张图。

**图 3 停词表和核心代码**

```bash
clear
cat data/stopwords.txt | tr '\n' ' '
printf '\n'
sed -n '25,76p' src/InvertedIndex.java
```

这一张可分成停词表和 Mapper 代码两张；Reducer 的关键逻辑也可以在 GitHub 上打开源码截图。

**图 4 编译、任务提交和完成状态**

```bash
R=$(cat results/LATEST.txt)
clear
cat "$R/build.log"
grep -E 'Submitted application|Running job:|completed successfully|map 100% reduce 100%|Tracking URL' "$R/run.log"
```

需要真实的 `application_...`、`job_...` 和成功日志。仅 `BUILD OK` 说明编译成功，不能证明任务完成。

在 Ubuntu 的 light 桌面浏览器打开 `http://localhost:8088`，找到名称为 `lab2-inverted-index` 的本次作业，确认 State 为 `FINISHED`、FinalStatus 为 `SUCCEEDED`，也可以截图。这个 localhost 指虚拟机，Windows 浏览器直接打开通常不能访问。

**图 5 倒排索引结果**

```bash
R=$(cat results/LATEST.txt)
clear
head -n 15 "$R/actual.txt"
grep -E '^(python|function|class|list|module)[[:space:]]' "$R/actual.txt"
```

每行包含单词、若干 `<文档名,词频>` 以及 `<total,总词频>.`。由于本实验使用 Python 文档，展示 python、function 等词即可，题目中的 hadoop、mapreduce 是格式示例，不要求照抄它们的词频。

**图 6 结果核验与 HDFS 文件**

```bash
R=$(cat results/LATEST.txt)
clear
cat "$R/VERIFY.txt"
HDFS_RUN=$(cat "$R/HDFS_PATH.txt")
hdfs dfs -ls "$HDFS_RUN/output"
```

应有 `CHECK PASS`，以及 HDFS 输出目录中的 `_SUCCESS` 和 `part-r-00000`。核验比较的是所有词、所有文档词频和总频，不只是几个示例。

## 7 常见问题

- `hadoop: command not found`：重新执行第 2 步的三条 export，确认安装路径存在。
- `sudo` 密码不对：在 light 桌面打开的终端里输入 light 密码，不是 GitHub 密码。若已直接登录 hadooplab，就无需再 sudo 切换。
- 下载后 `cd` 提示目录不存在：先看下载是否成功，再确认 GitHub 上确有顶层 `lab2-inverted-index` 文件夹，且已经点 Commit changes。
- HDFS `Connection refused`：启动 NameNode/DataNode；查看 `tail -n 60 "$HADOOP_HOME"/logs/*namenode*.log`。不要格式化。
- YARN 没有 RUNNING 节点：查看 `tail -n 60 "$HADOOP_HOME"/logs/*nodemanager*.log`，检查 JAVA_HOME 和配置脚本是否执行成功。
- 作业一直 ACCEPTED：检查 `yarn node -list`，确认配置脚本完成后重启过 RM/NM；在 8088 查看资源是否被其他任务占满。
- 作业失败：先查看本轮 `run.log`；取得其中的 application ID 后执行 `yarn logs -applicationId application_实际编号`。若日志聚合未启用，可在 8088 页面点本次任务和容器日志，或查看 `$HADOOP_HOME/logs/userlogs`。
- `bad interpreter` 或出现 `$'\r'`：文件被编辑器改成 CRLF，执行 `sed -i 's/\r$//' scripts/*.sh` 后重试。
- 终端关闭后恢复：先按第 2 步切换用户并设置环境，再执行 `cd "$(cat "$HOME/lab2-current-project.txt")"`，即可读取之前的 results。

## 8 算法和提交说明

Map：读一行及其文件名，将连续英文单词统一小写；若不在停词表，输出 `(word, filename + "\t1")`。

Shuffle：框架将相同 word 的记录放到一组。

Reduce：用按文件名排序的映射累计词频，累加得到 total，写出 `word<TAB><file1,count1>;<file2,count2>;<total,sum>.`。

使用一个 Reducer 保证输出文件按单词顺序排列；本实验只统计词频，不保存词在文本中的位置。原文档标题虽介绍了广义倒排索引，具体给出的输出要求是文档词频列表。

报告建议放文档来源简介、停词表、上述伪代码、关键截图、几个实际结果及结论。附件要求 PDF 不超过 8 页、宋体小四、1.25 倍行距。附件命名示例沿用了“实验一”，本次报告应结合教师要求使用实验二名称。GitHub 公开链接放进报告。此项目只准备源码、数据和操作步骤，不代表虚拟机已经运行成功。

Java MapReduce 官方教程：https://hadoop.apache.org/docs/current/hadoop-mapreduce-client/hadoop-mapreduce-client-core/MapReduceTutorial.html
