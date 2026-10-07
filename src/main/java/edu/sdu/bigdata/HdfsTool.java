package edu.sdu.bigdata;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Options;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.util.Tool;

/** 实验二的 10 项 HDFS 功能；所有路径由命令行传入。 */
public class HdfsTool extends Configured implements Tool {
    private static void usage() {
        System.err.println("HDFS commands:\n"
            + "  upload <local-file> <hdfs-file> <append|overwrite>\n"
            + "  download <hdfs-file> <local-directory>\n"
            + "  cat|stat|lsr|create|delete|mkdir|rmdir <hdfs-path>\n"
            + "  append <hdfs-file> <local-text-file> <head|tail>\n"
            + "  move <hdfs-source> <hdfs-destination-file>\n"
            + "Generic options (before command): -fs hdfs://localhost:9000");
    }

    private static void count(String[] args, int expected) {
        if (args.length != expected) throw new IllegalArgumentException("Wrong number of arguments");
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
    }

    private static void requireFile(FileSystem fs, Path path) throws IOException {
        if (!fs.getFileStatus(path).isFile()) throw new IOException("Not a file: " + path);
    }

    private static void parents(FileSystem fs, Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null && !fs.mkdirs(parent)) throw new IOException("Cannot create parent: " + parent);
    }

    private static void upload(FileSystem fs, String local, Path destination, String mode) throws IOException {
        if (!mode.equals("append") && !mode.equals("overwrite")) {
            throw new IllegalArgumentException("Mode must be append or overwrite");
        }
        // 先打开本地文件，避免本地输入不存在时破坏远端文件。
        try (InputStream in = Files.newInputStream(Paths.get(local))) {
            boolean exists = fs.exists(destination);
            if (exists) requireFile(fs, destination);
            parents(fs, destination);
            try (OutputStream out = exists && mode.equals("append")
                    ? fs.append(destination) : fs.create(destination, mode.equals("overwrite"))) {
                copy(in, out);
            }
        }
        System.out.println("Uploaded (" + mode + "): " + destination);
    }

    private static void download(FileSystem fs, Path source, String directory) throws IOException {
        requireFile(fs, source);
        java.nio.file.Path dir = Paths.get(directory);
        Files.createDirectories(dir);
        String name = source.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        // CREATE_NEW 防止覆盖；重名时依次尝试 file_1.txt、file_2.txt。
        for (int index = 0; ; index++) {
            java.nio.file.Path target = dir.resolve(index == 0 ? name : stem + "_" + index + ext);
            OutputStream out;
            try { out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); }
            catch (FileAlreadyExistsException e) { continue; }
            try (OutputStream destination = out; InputStream in = fs.open(source)) {
                copy(in, destination);
            } catch (IOException e) {
                Files.deleteIfExists(target);
                throw e;
            }
            System.out.println("Downloaded: " + target.toAbsolutePath());
            return;
        }
    }

    private static void stat(FileStatus status) {
        // FileStatus 没有创建时间字段；不得把 modificationTime 冒充创建时间。
        System.out.printf("%s%s\t%s:%s\t%d bytes\tmtime=%s\tcreationTime=unavailable\t%s%n",
            status.isDirectory() ? "d" : "-", status.getPermission(), status.getOwner(), status.getGroup(),
            status.getLen(), Instant.ofEpochMilli(status.getModificationTime()), status.getPath());
    }

    private void prepend(FileSystem fs, Path destination, String local) throws IOException {
        requireFile(fs, destination);
        FileStatus original = fs.getFileStatus(destination);
        Path qualified = fs.makeQualified(destination);
        Path temporary = new Path(qualified.getParent(), "." + qualified.getName() + ".prepend-" + UUID.randomUUID());
        // HDFS 不能原地向文件头插入：先流式写入临时文件，再在同一文件系统内原子替换。
        try {
            try (InputStream prefix = Files.newInputStream(Paths.get(local));
                 InputStream old = fs.open(destination);
                 OutputStream out = fs.create(temporary, original.getPermission(), false, 65536,
                     original.getReplication(), original.getBlockSize(), null)) {
                copy(prefix, out);
                copy(old, out);
            }
            fs.setPermission(temporary, original.getPermission());
            FileContext.getFileContext(fs.getUri(), getConf()).rename(temporary, qualified, Options.Rename.OVERWRITE);
        } finally {
            if (fs.exists(temporary)) fs.delete(temporary, false);
        }
        System.out.println("Prepended: " + destination);
    }

    @Override public int run(String[] args) throws Exception {
        if (args.length == 0) { usage(); return 2; }
        try (FileSystem fs = FileSystem.get(getConf())) {
            if (!"hdfs".equalsIgnoreCase(fs.getUri().getScheme())) {
                throw new IOException("Default filesystem is " + fs.getUri()
                    + "; set HADOOP_CONF_DIR or pass -fs hdfs://HOST:PORT before the HDFS command.");
            }
            switch (args[0]) {
                case "upload":
                    count(args, 4); upload(fs, args[1], new Path(args[2]), args[3]); break;
                case "download":
                    count(args, 3); download(fs, new Path(args[1]), args[2]); break;
                case "cat":
                    count(args, 2); requireFile(fs, new Path(args[1]));
                    try (InputStream in = fs.open(new Path(args[1]))) { copy(in, System.out); }
                    System.out.flush(); break;
                case "stat":
                    count(args, 2); stat(fs.getFileStatus(new Path(args[1]))); break;
                case "lsr":
                    count(args, 2);
                    Path directory = new Path(args[1]);
                    if (!fs.getFileStatus(directory).isDirectory()) throw new IOException("Not a directory: " + directory);
                    RemoteIterator<LocatedFileStatus> files = fs.listFiles(directory, true);
                    while (files.hasNext()) stat(files.next());
                    break;
                case "create":
                    count(args, 2); Path file = new Path(args[1]); parents(fs, file);
                    try (OutputStream ignored = fs.create(file, false)) { /* 新建空文件，不覆盖。 */ }
                    System.out.println("Created: " + file); break;
                case "delete":
                    count(args, 2); Path removed = new Path(args[1]); requireFile(fs, removed);
                    if (!fs.delete(removed, false)) throw new IOException("Delete failed: " + removed);
                    System.out.println("Deleted file: " + removed); break;
                case "mkdir":
                    count(args, 2);
                    if (!fs.mkdirs(new Path(args[1]))) throw new IOException("mkdir failed: " + args[1]);
                    System.out.println("Created directory: " + args[1]); break;
                case "rmdir":
                    count(args, 2); Path dir = new Path(args[1]);
                    if (!fs.getFileStatus(dir).isDirectory()) throw new IOException("Not a directory: " + dir);
                    if (fs.listStatus(dir).length != 0) throw new IOException("Refusing non-empty directory: " + dir);
                    if (!fs.delete(dir, false)) throw new IOException("rmdir failed: " + dir);
                    System.out.println("Removed empty directory: " + dir); break;
                case "append":
                    count(args, 4); Path dst = new Path(args[1]); requireFile(fs, dst);
                    if (args[3].equals("head")) prepend(fs, dst, args[2]);
                    else if (args[3].equals("tail")) upload(fs, args[2], dst, "append");
                    else throw new IllegalArgumentException("Position must be head or tail");
                    break;
                case "move":
                    count(args, 3); Path src = new Path(args[1]); Path to = new Path(args[2]);
                    requireFile(fs, src);
                    if (fs.exists(to)) throw new IOException("Destination already exists: " + to);
                    parents(fs, to);
                    FileContext.getFileContext(fs.getUri(), getConf()).rename(fs.makeQualified(src), fs.makeQualified(to), Options.Rename.NONE);
                    System.out.println("Moved: " + src + " -> " + to); break;
                default: usage(); return 2;
            }
        } catch (IllegalArgumentException | IOException e) {
            System.err.println("ERROR: " + e.getMessage()); return 1;
        }
        return 0;
    }
}
