import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.GenericOptionsParser;

/** 文档倒排索引：统计每个单词在各文档中的次数及总次数。 */
public class InvertedIndex {
    public static class IndexMapper extends Mapper<LongWritable, Text, Text, Text> {
        private static final Pattern WORD = Pattern.compile("[a-z]+");
        private final Set<String> stopwords = new HashSet<>();
        private final Text outputWord = new Text();
        private final Text outputDocument = new Text();

        @Override
        protected void setup(Context context) throws IOException {
            // Hadoop 会将缓存文件下载到任务工作目录，名称为 stopwords.txt。
            try (BufferedReader reader = Files.newBufferedReader(
                    Paths.get("stopwords.txt"), StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim().toLowerCase(Locale.ROOT);
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        for (String word : line.split("\\s+")) {
                            stopwords.add(word);
                        }
                    }
                }
            }
            String filename = ((FileSplit) context.getInputSplit()).getPath().getName();
            outputDocument.set(filename + "\t1");
        }

        @Override
        protected void map(LongWritable key, Text value, Context context)
                throws IOException, InterruptedException {
            // 统一小写，以连续英文字母作为单词，跳过停词。
            Matcher matcher = WORD.matcher(value.toString().toLowerCase(Locale.ROOT));
            while (matcher.find()) {
                String word = matcher.group();
                if (!stopwords.contains(word)) {
                    outputWord.set(word);
                    context.write(outputWord, outputDocument);
                }
            }
        }
    }

    public static class IndexReducer extends Reducer<Text, Text, Text, Text> {
        private final Text outputPostings = new Text();

        @Override
        protected void reduce(Text word, Iterable<Text> values, Context context)
                throws IOException, InterruptedException {
            // TreeMap 按文件名排序，同一文档的多个词项会累加。
            Map<String, Long> counts = new TreeMap<>();
            long total = 0;
            for (Text value : values) {
                String entry = value.toString();
                int separator = entry.lastIndexOf('\t');
                String filename = entry.substring(0, separator);
                long count = Long.parseLong(entry.substring(separator + 1));
                counts.put(filename, counts.getOrDefault(filename, 0L) + count);
                total += count;
            }

            StringBuilder postings = new StringBuilder();
            for (Map.Entry<String, Long> entry : counts.entrySet()) {
                postings.append('<').append(entry.getKey()).append(',')
                        .append(entry.getValue()).append(">;");
            }
            postings.append("<total,").append(total).append(">.");
            outputPostings.set(postings.toString());
            context.write(word, outputPostings);
        }
    }

    public static void main(String[] args) throws Exception {
        Configuration conf = new Configuration();
        String[] paths = new GenericOptionsParser(conf, args).getRemainingArgs();
        if (paths.length != 3) {
            System.err.println("Usage: InvertedIndex [generic options] <input> <output> <stopwords>");
            System.exit(2);
        }

        Job job = Job.getInstance(conf, "lab2-inverted-index");
        job.setJarByClass(InvertedIndex.class);
        job.setMapperClass(IndexMapper.class);
        job.setReducerClass(IndexReducer.class);
        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(Text.class);
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(Text.class);
        job.setInputFormatClass(TextInputFormat.class);
        job.setOutputFormatClass(TextOutputFormat.class);
        // 本实验数据量较小，一个 Reducer 可输出按单词排序的单个结果文件。
        job.setNumReduceTasks(1);

        Path stopwordPath = new Path(paths[2]);
        URI source = stopwordPath.getFileSystem(conf).makeQualified(stopwordPath).toUri();
        job.addCacheFile(new URI(source.getScheme(), source.getAuthority(),
                source.getPath(), source.getQuery(), "stopwords.txt"));
        FileInputFormat.addInputPath(job, new Path(paths[0]));
        FileOutputFormat.setOutputPath(job, new Path(paths[1]));

        System.exit(job.waitForCompletion(true) ? 0 : 1);
    }
}
