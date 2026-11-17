import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.storage.StorageLevel;
import scala.Tuple2;

/** PageRank with Java RDD transformations, not a call to a PageRank library. */
public class PageRankSpark {
    private static final int ITERATIONS = 10;
    private static final double DAMPING = 0.85;

    private static List<Tuple2<String, List<String>>> parseLine(String line) {
        String[] fields = line.split("\t", -1);
        if (fields.length > 2 || fields[0].trim().isEmpty())
            throw new IllegalArgumentException("Expected node TAB comma-separated links: " + line);
        String node = fields[0].trim();
        TreeSet<String> unique = new TreeSet<>();
        if (fields.length == 2 && !fields[1].trim().isEmpty()) {
            for (String target : fields[1].split(",")) {
                if (!target.trim().isEmpty()) unique.add(target.trim());
            }
        }
        List<Tuple2<String, List<String>>> result = new ArrayList<>();
        result.add(new Tuple2<>(node, new ArrayList<>(unique)));
        for (String target : unique) result.add(new Tuple2<>(target, Collections.emptyList()));
        return result;
    }

    private static void saveState(JavaPairRDD<String, List<String>> links,
            JavaPairRDD<String, Double> ranks, String path) {
        links.join(ranks).sortByKey(true, 1).map(item -> item._1() + "\t" + item._2()._2()
                + "\t" + String.join(",", item._2()._1())).saveAsTextFile(path);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3
                || (args.length == 3 && Integer.parseInt(args[2]) != ITERATIONS)) {
            throw new IllegalArgumentException("Usage: PageRankSpark <input> <new-output-root> [10]");
        }
        SparkConf conf = new SparkConf().setAppName("Lab3 PageRank 10 rounds");
        try (JavaSparkContext sc = new JavaSparkContext(conf)) {
            sc.setLogLevel("WARN");
            if (sc.master().startsWith("local")) {
                throw new IllegalArgumentException("Use a YARN or Spark standalone cluster, not local mode");
            }
            Path output = new Path(args[1]);
            FileSystem fs = output.getFileSystem(sc.hadoopConfiguration());
            if (fs.exists(output)) throw new java.io.IOException("Output already exists: " + output);
            fs.mkdirs(output);
            JavaRDD<String> lines = sc.textFile(args[0], 1)
                    .filter(line -> !line.trim().isEmpty() && !line.startsWith("#"));
            JavaPairRDD<String, List<String>> links = lines.flatMapToPair(line -> parseLine(line).iterator())
                    .reduceByKey((left, right) -> {
                        TreeSet<String> merged = new TreeSet<>(left);
                        merged.addAll(right);
                        return new ArrayList<>(merged);
                    }, 1).persist(StorageLevel.MEMORY_ONLY());
            final long count = links.count();
            if (count == 0) throw new IllegalArgumentException("Input graph is empty");
            final double initial = 1.0 / count;
            JavaPairRDD<String, Double> ranks = links.mapValues(neighbors -> initial)
                    .persist(StorageLevel.MEMORY_ONLY());
            saveState(links, ranks, new Path(output, "state-00").toString());
            for (int iteration = 1; iteration <= ITERATIONS; iteration++) {
                final double dangling = links.join(ranks)
                        .filter(item -> item._2()._1().isEmpty())
                        .values().map(item -> item._2()).fold(0.0, (a, b) -> a + b);
                JavaPairRDD<String, Double> incoming = links.join(ranks).values()
                        .flatMapToPair(item -> {
                            List<Tuple2<String, Double>> contributions = new ArrayList<>();
                            List<String> targets = item._1();
                            if (!targets.isEmpty()) {
                                double share = item._2() / targets.size();
                                for (String target : targets) contributions.add(new Tuple2<>(target, share));
                            }
                            return contributions.iterator();
                        }).reduceByKey((a, b) -> a + b, 1);
                JavaPairRDD<String, Double> next = links.leftOuterJoin(incoming)
                        .mapValues(item -> (1.0 - DAMPING) / count
                                + DAMPING * (item._2().orElse(0.0) + dangling / count))
                        .persist(StorageLevel.MEMORY_ONLY());
                saveState(links, next,
                        new Path(output, String.format(Locale.ROOT, "state-%02d", iteration)).toString());
                ranks.unpersist(false);
                ranks = next;
                System.out.println("SPARK_ITERATION=" + iteration);
            }
            JavaRDD<Tuple2<String, Double>> sorted = ranks.map(item -> item)
                    .sortBy(item -> item._2(), false, 1);
            sorted.map(item -> String.format(Locale.ROOT, "(%s,%.10f)", item._1(), item._2()))
                    .saveAsTextFile(new Path(output, "final").toString());
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    fs.create(new Path(output, "metadata.properties"), false), StandardCharsets.UTF_8))) {
                writer.write("implementation=spark-rdd\nmaster=" + sc.master() + "\niterations=10\n"
                        + "damping=0.85\ninitial_rank=1/N\nnodes=" + count
                        + "\napplication_id=" + sc.sc().applicationId() + "\n");
            }
            for (Tuple2<String, Double> item : sorted.collect())
                System.out.printf(Locale.ROOT, "(%s,%.10f)%n", item._1(), item._2());
            System.out.println("SPARK_PAGERANK_10_ROUNDS_OK " + new Path(output, "final"));
            ranks.unpersist(false);
            links.unpersist(false);
        }
    }
}
