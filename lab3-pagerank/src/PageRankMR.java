import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

/** Ten MapReduce PageRank rounds; each round preserves the graph structure. */
public class PageRankMR extends Configured implements Tool {
    private static final int ITERATIONS = 10;
    private static final double DAMPING = 0.85;

    public static class RankMapper extends Mapper<LongWritable, Text, Text, Text> {
        @Override
        protected void map(LongWritable offset, Text value, Context context)
                throws java.io.IOException, InterruptedException {
            String[] fields = value.toString().split("\t", -1);
            if (fields.length != 3) throw new java.io.IOException("Invalid state: " + value);
            String node = fields[0];
            double rank = Double.parseDouble(fields[1]);
            String adjacency = fields[2];
            // S carries the structure, including an empty adjacency list.
            context.write(new Text(node), new Text("S\t" + adjacency));
            if (!adjacency.isEmpty()) {
                String[] targets = adjacency.split(",");
                double share = rank / targets.length;
                for (String target : targets) {
                    context.write(new Text(target), new Text("C\t" + share));
                }
            }
        }
    }

    public static class RankReducer extends Reducer<Text, Text, Text, Text> {
        @Override
        protected void reduce(Text node, Iterable<Text> values, Context context)
                throws java.io.IOException, InterruptedException {
            String adjacency = null;
            double incoming = 0.0;
            for (Text value : values) {
                String item = value.toString();
                if (item.startsWith("S\t")) adjacency = item.substring(2);
                else if (item.startsWith("C\t")) incoming += Double.parseDouble(item.substring(2));
                else throw new java.io.IOException("Unknown value: " + item);
            }
            if (adjacency == null) throw new java.io.IOException("Missing graph node: " + node);
            Configuration conf = context.getConfiguration();
            int count = conf.getInt("pr.node.count", 0);
            double dangling = conf.getDouble("pr.dangling.mass", 0.0);
            double rank = (1.0 - DAMPING) / count
                    + DAMPING * (incoming + dangling / count);
            context.write(node, new Text(Double.toString(rank) + "\t" + adjacency));
        }
    }

    private static class State {
        final double rank;
        final String adjacency;
        State(double rank, String adjacency) { this.rank = rank; this.adjacency = adjacency; }
    }

    private static List<Path> dataFiles(FileSystem fs, Path path) throws java.io.IOException {
        List<Path> files = new ArrayList<>();
        if (fs.isFile(path)) files.add(path);
        else {
            for (FileStatus item : fs.listStatus(path)) {
                String name = item.getPath().getName();
                if (item.isFile() && !name.startsWith("_") && !name.startsWith("."))
                    files.add(item.getPath());
            }
        }
        files.sort(Comparator.comparing(Path::toString));
        return files;
    }

    private static Map<String, TreeSet<String>> readGraph(FileSystem fs, Path input)
            throws java.io.IOException {
        Map<String, TreeSet<String>> graph = new TreeMap<>();
        for (Path path : dataFiles(fs, input)) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    fs.open(path), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty() || line.startsWith("#")) continue;
                    String[] fields = line.split("\t", -1);
                    if (fields.length > 2 || fields[0].trim().isEmpty())
                        throw new java.io.IOException("Expected node TAB comma-separated links: " + line);
                    String node = fields[0].trim();
                    graph.computeIfAbsent(node, ignored -> new TreeSet<>());
                    if (fields.length == 2 && !fields[1].trim().isEmpty()) {
                        for (String item : fields[1].split(",")) {
                            String target = item.trim();
                            if (target.isEmpty()) continue;
                            graph.get(node).add(target);
                            // Targets absent as source rows are dangling nodes too.
                            graph.computeIfAbsent(target, ignored -> new TreeSet<>());
                        }
                    }
                }
            }
        }
        if (graph.isEmpty()) throw new java.io.IOException("Input graph is empty");
        return graph;
    }

    private static Map<String, State> readState(FileSystem fs, Path path)
            throws java.io.IOException {
        Map<String, State> states = new TreeMap<>();
        for (Path file : dataFiles(fs, path)) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    fs.open(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] fields = line.split("\t", -1);
                    if (fields.length != 3) throw new java.io.IOException("Invalid state: " + line);
                    if (states.put(fields[0], new State(Double.parseDouble(fields[1]), fields[2])) != null)
                        throw new java.io.IOException("Duplicate state for " + fields[0]);
                }
            }
        }
        return states;
    }

    @Override
    public int run(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3
                || (args.length == 3 && Integer.parseInt(args[2]) != ITERATIONS)) {
            System.err.println("Usage: PageRankMR <input> <new-output-root> [10]");
            return 2;
        }
        Configuration conf = getConf();
        String framework = conf.get("mapreduce.framework.name", "local");
        if (!"yarn".equals(framework)) {
            throw new IllegalArgumentException("Use -Dmapreduce.framework.name=yarn for this experiment");
        }
        Path input = new Path(args[0]);
        Path output = new Path(args[1]);
        FileSystem inputFs = input.getFileSystem(conf);
        FileSystem outputFs = output.getFileSystem(conf);
        if (outputFs.exists(output)) throw new java.io.IOException("Output already exists: " + output);
        Map<String, TreeSet<String>> graph = readGraph(inputFs, input);
        int count = graph.size();
        outputFs.mkdirs(output);
        Path state = new Path(output, "state-00");
        outputFs.mkdirs(state);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                outputFs.create(new Path(state, "part-00000"), false), StandardCharsets.UTF_8))) {
            for (Map.Entry<String, TreeSet<String>> node : graph.entrySet()) {
                writer.write(node.getKey() + "\t" + (1.0 / count)
                        + "\t" + String.join(",", node.getValue()));
                writer.newLine();
            }
        }
        for (int iteration = 1; iteration <= ITERATIONS; iteration++) {
            // The small-graph driver only totals dangling ranks; all link
            // contributions and new ranks are computed by the MapReduce job.
            Map<String, State> previous = readState(outputFs, state);
            if (previous.size() != count) throw new java.io.IOException("Node count changed");
            double dangling = 0.0;
            for (State value : previous.values()) {
                if (value.adjacency.isEmpty()) dangling += value.rank;
            }
            Configuration round = new Configuration(conf);
            round.setInt("pr.node.count", count);
            round.setDouble("pr.dangling.mass", dangling);
            round.setBoolean("mapreduce.output.fileoutputformat.compress", false);
            Job job = Job.getInstance(round, "lab3-pagerank-round-" + iteration);
            job.setJarByClass(PageRankMR.class);
            job.setMapperClass(RankMapper.class);
            job.setReducerClass(RankReducer.class);
            job.setMapOutputKeyClass(Text.class);
            job.setMapOutputValueClass(Text.class);
            job.setOutputKeyClass(Text.class);
            job.setOutputValueClass(Text.class);
            job.setNumReduceTasks(1);
            FileInputFormat.addInputPath(job, state);
            Path next = new Path(output, String.format(Locale.ROOT, "state-%02d", iteration));
            FileOutputFormat.setOutputPath(job, next);
            if (!job.waitForCompletion(true)) return 1;
            state = next;
            System.out.println("MR_ITERATION=" + iteration + " JOB=" + job.getJobID());
        }
        Map<String, State> finalState = readState(outputFs, state);
        List<Map.Entry<String, State>> ranking = new ArrayList<>(finalState.entrySet());
        ranking.sort(Comparator.<Map.Entry<String, State>>comparingDouble(e -> -e.getValue().rank)
                .thenComparing(Map.Entry::getKey));
        Path finalDir = new Path(output, "final");
        outputFs.mkdirs(finalDir);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                outputFs.create(new Path(finalDir, "part-00000"), false), StandardCharsets.UTF_8))) {
            for (Map.Entry<String, State> item : ranking) {
                String line = String.format(Locale.ROOT, "(%s,%.10f)", item.getKey(), item.getValue().rank);
                writer.write(line);
                writer.newLine();
                System.out.println(line);
            }
        }
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                outputFs.create(new Path(output, "metadata.properties"), false), StandardCharsets.UTF_8))) {
            writer.write("implementation=mapreduce\nframework=" + framework + "\niterations=10\n"
                    + "damping=0.85\ninitial_rank=1/N\nnodes=" + count + "\n");
        }
        try (java.io.OutputStream marker = outputFs.create(new Path(finalDir, "_SUCCESS"), false)) {
            // Written only after final output and runtime metadata are complete.
        }
        System.out.println("MAPREDUCE_PAGERANK_10_ROUNDS_OK " + finalDir);
        return 0;
    }

    public static void main(String[] args) throws Exception {
        System.exit(ToolRunner.run(new Configuration(), new PageRankMR(), args));
    }
}
