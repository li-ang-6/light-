package edu.sdu.bigdata;

import java.util.Arrays;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

/** 统一入口。使用 hadoop jar 加载虚拟机现有的 Hadoop 配置和依赖。 */
public final class Main {
    private Main() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: hadoop jar target/bigdata-lab1.jar <hdfs|dedup|sort|relations> [args]");
            System.exit(2);
        }
        Tool tool;
        switch (args[0]) {
            case "hdfs": tool = new HdfsTool(); break;
            case "dedup": tool = new MergeDedup(); break;
            case "sort": tool = new NumberSort(); break;
            case "relations": tool = new Grandparent(); break;
            default: throw new IllegalArgumentException("Unknown task: " + args[0]);
        }
        System.exit(ToolRunner.run(new Configuration(), tool, Arrays.copyOfRange(args, 1, args.length)));
    }
}
