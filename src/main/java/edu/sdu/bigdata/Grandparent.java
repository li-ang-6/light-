package edu.sdu.bigdata;

import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
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

/** child-parent 自连接；第二个作业对通过不同中间人得到的相同祖孙关系去重。 */
public class Grandparent extends Configured implements Tool {
    public static class JoinMapper extends Mapper<LongWritable, Text, Text, Text> {
        @Override protected void map(LongWritable offset, Text value, Context context) throws IOException, InterruptedException {
            String line = value.toString().trim();
            if (line.isEmpty()) return;
            String[] fields = line.split("\\s+");
            if (fields.length != 2) throw new IOException("Expected child parent: " + line);
            if (fields[0].equalsIgnoreCase("child") && fields[1].equalsIgnoreCase("parent")) return;
            context.write(new Text(fields[1]), new Text("C\t" + fields[0]));
            context.write(new Text(fields[0]), new Text("P\t" + fields[1]));
        }
    }
    public static class JoinReducer extends Reducer<Text, Text, Text, NullWritable> {
        @Override protected void reduce(Text middle, Iterable<Text> values, Context context) throws IOException, InterruptedException {
            Set<String> children = new TreeSet<>();
            Set<String> parents = new TreeSet<>();
            for (Text value : values) {
                String s = value.toString();
                if (s.startsWith("C\t")) children.add(s.substring(2));
                else if (s.startsWith("P\t")) parents.add(s.substring(2));
            }
            for (String child : children) for (String parent : parents) {
                context.write(new Text(child + "\t" + parent), NullWritable.get());
            }
        }
    }
    public static class ResultReducer extends MergeDedup.UniqueReducer {
        @Override protected void setup(Context context) throws IOException, InterruptedException {
            context.write(new Text("grandchild\tgrandparent"), NullWritable.get());
        }
    }
    @Override public int run(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: relations <input-directory> <new-output-directory>"); return 2;
        }
        Path output = new Path(args[1]);
        FileSystem fs = output.getFileSystem(getConf());
        if (fs.exists(output)) throw new IOException("Output already exists: " + output);
        Path intermediate = new Path(args[1] + ".join-" + UUID.randomUUID());
        try {
            Job join = Job.getInstance(getConf(), "lab1-grandparent-self-join");
            join.setJarByClass(Grandparent.class);
            join.setMapperClass(JoinMapper.class);
            join.setMapOutputKeyClass(Text.class);
            join.setMapOutputValueClass(Text.class);
            join.setReducerClass(JoinReducer.class);
            join.setOutputKeyClass(Text.class);
            join.setOutputValueClass(NullWritable.class);
            join.setNumReduceTasks(1);
            FileInputFormat.addInputPath(join, new Path(args[0]));
            FileOutputFormat.setOutputPath(join, intermediate);
            if (!join.waitForCompletion(true)) return 1;

            Job unique = Job.getInstance(getConf(), "lab1-grandparent-unique");
            unique.setJarByClass(Grandparent.class);
            unique.setMapperClass(MergeDedup.LineMapper.class);
            unique.setCombinerClass(MergeDedup.UniqueReducer.class);
            unique.setReducerClass(ResultReducer.class);
            unique.setOutputKeyClass(Text.class);
            unique.setOutputValueClass(NullWritable.class);
            unique.setNumReduceTasks(1);
            FileInputFormat.addInputPath(unique, intermediate);
            FileOutputFormat.setOutputPath(unique, output);
            return unique.waitForCompletion(true) ? 0 : 1;
        } finally {
            // 只清理本次生成的随机中间目录，保留最终输出供截图与检查。
            if (fs.exists(intermediate)) fs.delete(intermediate, true);
        }
    }
}
