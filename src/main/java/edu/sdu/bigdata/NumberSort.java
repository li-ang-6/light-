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

/** 使用数值 key 排序；单 Reducer 保证全局顺序及连续位次，保留重复整数。 */
public class NumberSort extends Configured implements Tool {
    public static class NumberMapper extends Mapper<LongWritable, Text, LongWritable, NullWritable> {
        @Override protected void map(LongWritable offset, Text value, Context context) throws IOException, InterruptedException {
            String text = value.toString().trim();
            if (text.isEmpty()) return;
            try { context.write(new LongWritable(Long.parseLong(text)), NullWritable.get()); }
            catch (NumberFormatException e) { throw new IOException("Invalid integer: " + text, e); }
        }
    }
    public static class RankReducer extends Reducer<LongWritable, NullWritable, LongWritable, LongWritable> {
        private long rank = 0;
        @Override protected void reduce(LongWritable number, Iterable<NullWritable> values, Context context) throws IOException, InterruptedException {
            for (NullWritable ignored : values) context.write(new LongWritable(++rank), number);
        }
    }
    @Override public int run(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: sort <input-directory> <new-output-directory>"); return 2;
        }
        Job job = Job.getInstance(getConf(), "lab1-number-sort");
        job.setJarByClass(NumberSort.class);
        job.setMapperClass(NumberMapper.class);
        job.setMapOutputKeyClass(LongWritable.class);
        job.setMapOutputValueClass(NullWritable.class);
        job.setReducerClass(RankReducer.class);
        job.setOutputKeyClass(LongWritable.class);
        job.setOutputValueClass(LongWritable.class);
        job.setNumReduceTasks(1);
        FileInputFormat.addInputPath(job, new Path(args[0]));
        FileOutputFormat.setOutputPath(job, new Path(args[1]));
        return job.waitForCompletion(true) ? 0 : 1;
    }
}
