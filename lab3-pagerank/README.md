# 实验三 HBase 与 PageRank

沿用 VirtualBox Ubuntu 的 `light` 桌面和 `hadooplab` 实验用户，以及现有 Hadoop 3.5.0、Java 17。所有 Linux 命令在 Ubuntu 的 `hadooplab` 终端执行。

## 快速执行

在 `light` 终端先单独执行 `sudo -iu hadooplab`，密码为 light 的登录密码。将本文件夹放到该用户可访问的目录，进入本目录后执行：

```bash
bash scripts/run_all.sh all
```

脚本依次校验与解压安装包、准备独立 HBase 配置、检查已有 HDFS、启动 HBase 并执行 Java/Shell 演示、关闭本次 HBase、配置并启动 YARN、编译并运行 MapReduce 与 Spark、独立核对结果。看到 `LAB3_ALL_PASS` 才表示本轮全部完成。

已有下载包复用 `$HOME/hadoop-lab/downloads`。缺少包时从 Apache 下载。HBase 放在 `$HOME/hadoop-lab/lab3-tools/hbase-2.6.7`，Spark 放在 `$HOME/hadoop-lab/spark-4.0.5-bin-hadoop3`。HBase 配置在项目 `.runtime/hbase-conf`，使用独立 ZooKeeper 端口 2183 和 HDFS 路径 `/user/hadooplab/lab3-hbase`。原 Hadoop 继续使用；不会格式化 HDFS。修改 YARN/MapReduce 配置前自动备份原文件。

这台虚拟机约 2GB 内存，HBase 与计算阶段分开运行。发生报错时发送终端最后一屏及 `results` 中相应日志，不要重复创建新环境。

## 分阶段与重试

```bash
bash scripts/run_all.sh setup
bash scripts/run_all.sh hbase
bash scripts/run_all.sh pagerank
```

若计算阶段中断，已启动服务仍在时可以复用成功的 MR 输出重试：

```bash
source scripts/environment.sh
bash scripts/run_pagerank.sh "$(cat results/LATEST.txt)"
```

单独执行上面环境脚本前先 `export LAB3_ROOT="$PWD"`。也可以重新执行 `bash scripts/run_all.sh pagerank`，它会生成新结果目录并保留旧结果。

## HBase 内容与数据说明

`HBaseLab.java` 实现列出全部表、扫描、添加删除列族/单元格、清空、计数，以及指定的 createTable/addRecord/scanColumn/modifyData/deleteRow。`scanColumn` 保留缺列的已有行并输出 null；列族参数会展开各限定符。createTable 对同名实验表先删除后重建。修改只允许 `lab3_*` 表。

原题 Student 把 Mary 与 Lisi 的学号都写为 2015003，而 SC 中使用 2015002。程序保留并展示原始记录，同时为成绩演示明确假定 Mary 的学号为 2015002；该假定不作为原题事实。原题 modifyData 三参数签名缺少新值，本实现保留三参数方法，通过属性/交互获得新值，并提供四参数重载。

Java 与 Shell 各自使用专用实验表，演示删除、truncate 不影响已有无关表。完整数据、假定和日志均保留。

## PageRank 内容

输入是 `网页<TAB>逗号分隔的出链`。`data/graph.tsv` 含 A-F 六个网页，E 显式无出链，F 只出现在目标集合；两种程序都会补齐 F 并保留悬挂节点。

初值为 1/N，阻尼系数 d=0.85，更新恰好 10 次。每轮把所有无出链节点的 PR 总量均分给全部节点：

`newPR(v) = (1-d)/N + d * (incoming(v) + danglingMass/N)`

MapReduce 的每一轮由真实 YARN 作业计算贡献与新 PR；小图驱动端只汇总悬挂质量。Spark 使用 Java RDD 的 join、flatMapToPair、reduceByKey、leftOuterJoin 迭代，在 YARN 上执行，不以 local 模式冒充伪分布式。两程序保存 state-00 到 state-10，以及 final 中形如 `(A,0.2789145374)` 的十位小数结果。

`verify_pagerank.py` 用独立 Python 参考算法检查实际输出、节点完整性、每轮结构、十轮状态、总质量和两实现误差。参考数学结果不等于虚拟机实际运行记录。

## 截图与提交

```bash
bash scripts/print_summary.sh
```

集中截取：环境与 HDFS/YARN 状态、HBase Java/Shell 的检查结果、两套最终 PR 值与独立核验信息。完整日志在 `results/HBASE_LATEST.txt` 和 `results/LATEST.txt` 指向的目录。批量脚本最后会把结果打包到个人目录的 `lab3-results-*.tar.gz`。

代码上传到原仓库的新目录 `lab3-pagerank`：

- 保留 README.md、src、scripts、data、.gitignore。
- 不上传 build、results、.runtime 或下载的 Hadoop/HBase/Spark 安装包。
- 仓库示例地址：`https://github.com/li-ang-6/light-/tree/main/lab3-pagerank`。只有实际提交后才能把它作为已经公开的代码地址。

报告要求不超过 8 页，宋体小四、1.25 倍行距。报告中的截图和通过结论以本次真实输出为准。

## 官方来源

- https://hbase.apache.org/docs/configuration/basic-prerequisites/
- https://dlcdn.apache.org/hbase/2.6.7/
- https://spark.apache.org/docs/4.0.5/running-on-yarn.html
- https://dlcdn.apache.org/spark/spark-4.0.5/
- https://hadoop.apache.org/docs/r3.5.0/hadoop-project-dist/hadoop-common/SingleCluster.html

HBase 2.6 支持 Java 17；官方 Hadoop 兼容表未单独明确验证 Hadoop 3.5。本实验采用 HBase hadoop3 包并隔离其客户端 JAR，是否能正常连接现有 HDFS 以真实运行检查为准，不把安装成功当作实验成功。
