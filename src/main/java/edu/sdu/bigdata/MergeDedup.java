package edu.sdu.bigdata;

import java.io.IOException;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;

/** 多文件合并去重：将整行作为 key，同一 key 只输出一次。 */
public class MergeDedup extends Configured implements Tool {
    public static class LineMapper extends Mapper<LongWritable, Text, Text, NullWritable> {
        @Override protected void map(LongWritable offset, Text value, Context context) throws IOException, InterruptedException {
            String line = value.toString().trim();
            if (!line.isEmpty()) {
                // 题目按字段定义记录，空格宽度不同不应产生“不同”记录。
                context.write(new Text(line.replaceAll("\\s+", "\t")), NullWritable.get());
            }
        }
    }
    public static class UniqueReducer extends Reducer<Text, NullWritable, Text, NullWritable> {
        @Override protected void reduce(Text key, Iterable<NullWritable> values, Context context) throws IOException, InterruptedException {
            context.write(key, NullWritable.get());
        }
    }
    @Override public int run(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: dedup <input-directory> <new-output-directory>"); return 2;
        }
        Job job = Job.getInstance(getConf(), "lab1-merge-dedup");
        job.setJarByClass(MergeDedup.class);
        job.setMapperClass(LineMapper.class);
        job.setCombinerClass(UniqueReducer.class);
        job.setReducerClass(UniqueReducer.class);
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(NullWritable.class);
        job.setNumReduceTasks(1);
        FileInputFormat.addInputPath(job, new Path(args[0]));
        FileOutputFormat.setOutputPath(job, new Path(args[1]));
        return job.waitForCompletion(true) ? 0 : 1;
    }
}
