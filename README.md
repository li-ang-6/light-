# 大数据系统基本实验一

对应《实验一 大数据系统基本实验-2026.docx》，包括 Linux/Hadoop 基础操作、10 项 HDFS Java API 与 Shell 操作，以及 3 项 MapReduce 程序。

**已有 Linux 和 Hadoop：先看 [完整操作步骤](docs/操作步骤.md)。** 报告可参照 [报告填写模板](docs/报告填写模板.md)，填入自己的环境、截图、运行结果和 GitHub 链接。

## 快速开始

以下在 Linux 的 `hadoop` 用户下、项目根目录中执行；先确保 Hadoop 已启动。

```bash
bash scripts/00_check.sh
bash scripts/build.sh
bash scripts/01_linux.sh
bash scripts/02_hadoop_basic.sh
bash scripts/03_hdfs_demo.sh java
bash scripts/03_hdfs_demo.sh shell
bash scripts/04_mapreduce.sh local
```

`local` 表示 Hadoop LocalJobRunner 执行 MapReduce，输入输出仍在真实 HDFS。若课程要求 YARN，启动 YARN 后把最后一个参数改为 `yarn`。报告中的执行模式应与实际一致。

首次完整运行也可以执行 `bash scripts/run_all.sh local`。Linux 练习会使用题目指定的 `/usr`、`/tmp`、`/test` 等路径，并请求 `sudo`；遇到已有同名文件会停止，请查看完整步骤。`more` 界面按 `q` 后继续。

## 文件说明

- `src/main/java/edu/sdu/bigdata/HdfsTool.java`：HDFS 全部 10 项功能。
- `MergeDedup.java`：文件合并与去重，按字段归一化空白。
- `NumberSort.java`：整数升序排序和连续位次；保留重复整数。
- `Grandparent.java`：child-parent 自连接求祖孙关系，再去除重复关系。
- `scripts/hdfs_shell.sh`：与 Java 操作对应的 Hadoop Shell 实现。
- `scripts/00_check.sh` 至 `04_mapreduce.sh`：检查、演示和结果验证。
- `data/`：题目原始样例及 HDFS 文本。
- `expected/`：题目样例的预期输出，Tab 分隔。
- `tests/test_local_mapreduce.sh`：真实 MapReduce 的样例及负数、重复数、重复关系测试。
- `pom.xml`：可选 Maven 构建；默认 Hadoop 3.3.6，支持 `-Dhadoop.version=你的版本`。

`build.sh` 使用本机 Hadoop 类路径直接编译，不依赖 Maven 下载。源码目标 Java 8，使用 Hadoop 3.x API；运行依赖由 `hadoop jar` 提供。

交付前已通过 Hadoop 3.3.6 API 的 Java 编译检查、全部 Bash 脚本的语法检查，以及题目样例和预期结果核对。当前 Windows 环境没有连接你的 Linux/HDFS，完整运行和截图需要在你的虚拟机上按步骤完成；以脚本的实际输出为准。

## 结果与判定

每次运行生成独立的 HDFS 实验目录和 `results/日期时间-进程号/`，一般不需要删除旧输出。`03_hdfs_demo.sh` 会验证追加、覆盖、自动重命名、头尾追加和目录删除限制；`04_mapreduce.sh` 用 `diff` 校验三个完整结果。

预期：去重 9 条；排序 11 条；祖孙关系 12 条，另有 1 行表头。祖孙关系按姓名排序，与题目展示顺序不同，但关系集合一致。输出是程序计算得到的，不是读取 `expected/` 复制而来。

HDFS `FileStatus` 提供修改时间和访问时间，没有独立创建时间字段。程序明确标记创建时间不可用，并输出修改时间；报告中请说明这一限制。

## 官方参考

- [Hadoop 单节点及伪分布式运行](https://hadoop.apache.org/docs/r3.3.6/hadoop-project-dist/hadoop-common/SingleCluster.html)
- [Hadoop 文件系统 Shell](https://hadoop.apache.org/docs/r3.3.6/hadoop-project-dist/hadoop-common/FileSystemShell.html)
- [MapReduce 编程教程](https://hadoop.apache.org/docs/r3.3.6/hadoop-mapreduce-client/hadoop-mapreduce-client-core/MapReduceTutorial.html)
- [FileStatus Java API](https://hadoop.apache.org/docs/r3.3.6/api/org/apache/hadoop/fs/FileStatus.html)
