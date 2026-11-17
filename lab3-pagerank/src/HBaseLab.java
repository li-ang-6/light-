import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptorBuilder;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.TableDescriptorBuilder;
import org.apache.hadoop.hbase.filter.FirstKeyOnlyFilter;
import org.apache.hadoop.hbase.util.Bytes;

/** HBase 2.6 Java API exercise. This program only changes tables named lab3_*. */
public class HBaseLab implements Closeable {
    private final Connection connection;
    private final Admin admin;

    public HBaseLab() throws IOException {
        Configuration conf = HBaseConfiguration.create();
        conf.setInt("hbase.client.retries.number", 3);
        conf.setInt("hbase.rpc.timeout", 30000);
        conf.setInt("hbase.client.operation.timeout", 120000);
        connection = ConnectionFactory.createConnection(conf);
        admin = connection.getAdmin();
    }

    private static byte[] b(String text) { return Bytes.toBytes(text); }
    private static String text(byte[] bytes) { return bytes == null ? null : Bytes.toString(bytes); }

    private static TableName writableTable(String name) {
        if (name == null || !name.matches("lab3_[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("This exercise only modifies lab3_* tables: " + name);
        }
        return TableName.valueOf(name);
    }

    private static String[] splitColumn(String column) {
        String[] parts = column.split(":", 2);
        if (parts.length == 0 || parts[0].isEmpty()) {
            throw new IllegalArgumentException("Empty column family");
        }
        return new String[] {parts[0], parts.length == 2 ? parts[1] : ""};
    }

    /** Existing tables are disabled and dropped before rebuilding, as required. */
    public void createTable(String tableName, String[] fields) throws IOException {
        TableName name = writableTable(tableName);
        if (fields == null || fields.length == 0) {
            throw new IllegalArgumentException("At least one column family is required");
        }
        Set<String> families = new LinkedHashSet<String>();
        for (String field : fields) families.add(splitColumn(field)[0]);
        TableDescriptorBuilder builder = TableDescriptorBuilder.newBuilder(name);
        for (String family : families) {
            builder.setColumnFamily(ColumnFamilyDescriptorBuilder.of(family));
        }
        if (admin.tableExists(name)) {
            if (admin.isTableEnabled(name)) admin.disableTable(name);
            admin.deleteTable(name);
        }
        admin.createTable(builder.build());
        System.out.println("CREATE " + tableName + " families=" + families);
    }

    /** Fields may be either a family (empty qualifier) or family:qualifier. */
    public void addRecord(String tableName, String row, String[] fields, String[] values)
            throws IOException {
        if (row == null || row.isEmpty() || fields == null || values == null
                || fields.length != values.length || fields.length == 0) {
            throw new IllegalArgumentException("Nonempty row and equally sized fields/values required");
        }
        Put put = new Put(b(row));
        for (int i = 0; i < fields.length; i++) {
            String[] col = splitColumn(fields[i]);
            put.addColumn(b(col[0]), b(col[1]), b(values[i]));
        }
        try (Table table = connection.getTable(writableTable(tableName))) { table.put(put); }
    }

    public List<String> listTables() throws IOException {
        List<String> names = new ArrayList<String>();
        System.out.println("=== All user tables ===");
        for (TableName name : admin.listTableNames()) {
            names.add(name.getNameAsString());
            System.out.println(name.getNameAsString());
        }
        return names;
    }

    public void scanTable(String tableName) throws IOException {
        System.out.println("=== SCAN " + tableName + " ===");
        try (Table table = connection.getTable(TableName.valueOf(tableName));
             ResultScanner scanner = table.getScanner(new Scan())) {
            for (Result result : scanner) {
                for (Cell cell : result.rawCells()) {
                    System.out.println(text(result.getRow()) + "\t"
                            + text(CellUtil.cloneFamily(cell)) + ":"
                            + text(CellUtil.cloneQualifier(cell)) + "="
                            + text(CellUtil.cloneValue(cell)));
                }
            }
        }
    }

    /**
     * Scan every existing row, even when its requested column is missing.
     * Each row maps to qualifier/value pairs, or null if the family/cell is absent.
     * A column-filtered Scan would drop missing rows, so it is deliberately not used.
     */
    public Map<String, Map<String, String>> scanColumn(String tableName, String column)
            throws IOException {
        String[] col = splitColumn(column);
        boolean exactCell = column.indexOf(':') >= 0;
        Map<String, Map<String, String>> output = new LinkedHashMap<String, Map<String, String>>();
        System.out.println("=== SCAN COLUMN " + tableName + " " + column + " ===");
        try (Table table = connection.getTable(TableName.valueOf(tableName));
             ResultScanner scanner = table.getScanner(new Scan())) {
            for (Result result : scanner) {
                String row = text(result.getRow());
                Map<String, String> found = new LinkedHashMap<String, String>();
                if (exactCell) {
                    byte[] value = result.getValue(b(col[0]), b(col[1]));
                    if (value != null) found.put(column, text(value));
                } else {
                    NavigableMap<byte[], byte[]> family = result.getFamilyMap(b(col[0]));
                    if (family != null) {
                        for (Map.Entry<byte[], byte[]> cell : family.entrySet()) {
                            found.put(col[0] + ":" + text(cell.getKey()), text(cell.getValue()));
                        }
                    }
                }
                output.put(row, found.isEmpty() ? null : found);
                System.out.println(row + "\t" + (found.isEmpty() ? "null" : found));
            }
        }
        return output;
    }

    public void modifyData(String tableName, String row, String column, String value)
            throws IOException {
        addRecord(tableName, row, new String[] {column}, new String[] {value});
    }

    /** The handout omits the new-value argument; use a property or prompt for it. */
    public void modifyData(String tableName, String row, String column) throws IOException {
        String value = System.getProperty("lab3.modify.value");
        if (value == null) {
            System.out.print("New value for " + tableName + "/" + row + "/" + column + ": ");
            value = new BufferedReader(new InputStreamReader(System.in, "UTF-8")).readLine();
            if (value == null) throw new IOException("No new value supplied");
        }
        modifyData(tableName, row, column, value);
    }

    public void deleteRow(String tableName, String row) throws IOException {
        try (Table table = connection.getTable(writableTable(tableName))) {
            table.delete(new Delete(b(row)));
        }
    }

    public void deleteColumn(String tableName, String row, String column) throws IOException {
        String[] col = splitColumn(column);
        if (column.indexOf(':') < 0) throw new IllegalArgumentException("Use family:qualifier");
        try (Table table = connection.getTable(writableTable(tableName))) {
            table.delete(new Delete(b(row)).addColumns(b(col[0]), b(col[1])));
        }
    }

    public void addFamily(String tableName, String family) throws IOException {
        TableName name = writableTable(tableName);
        boolean enabled = admin.isTableEnabled(name);
        if (enabled) admin.disableTable(name);
        try { admin.addColumnFamily(name, ColumnFamilyDescriptorBuilder.of(family)); }
        finally { if (enabled) admin.enableTable(name); }
    }

    public void deleteFamily(String tableName, String family) throws IOException {
        TableName name = writableTable(tableName);
        boolean enabled = admin.isTableEnabled(name);
        if (enabled) admin.disableTable(name);
        try { admin.deleteColumnFamily(name, b(family)); }
        finally { if (enabled) admin.enableTable(name); }
    }

    public void clearTable(String tableName) throws IOException {
        TableName name = writableTable(tableName);
        if (admin.isTableEnabled(name)) admin.disableTable(name);
        admin.truncateTable(name, false);
    }

    public long countRows(String tableName) throws IOException {
        long count = 0;
        Scan scan = new Scan().setFilter(new FirstKeyOnlyFilter()).setCacheBlocks(false);
        try (Table table = connection.getTable(TableName.valueOf(tableName));
             ResultScanner scanner = table.getScanner(scan)) {
            for (Result ignored : scanner) count++;
        }
        System.out.println("COUNT " + tableName + " = " + count);
        return count;
    }

    private String value(String tableName, String row, String column) throws IOException {
        String[] col = splitColumn(column);
        try (Table table = connection.getTable(TableName.valueOf(tableName))) {
            return text(table.get(new Get(b(row))).getValue(b(col[0]), b(col[1])));
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new IllegalStateException("FAIL: " + message);
        System.out.println("PASS: " + message);
    }

    private void loadData() throws IOException {
        // Original Student data, preserved verbatim (the duplicate ID is in the handout):
        // 2015001 Zhangsan male 23; 2015003 Mary female 22; 2015003 Lisi male 24.
        // SC uses 2015002. For this consistent demonstration ONLY, assume Mary=2015002.
        System.out.println("SOURCE DATA NOTE: handout repeats 2015003 for Mary and Lisi;");
        System.out.println("SC includes 2015002. Demonstration assumption: Mary S_No=2015002.");
        System.out.println("Original: 2015001 Zhangsan male 23; 2015003 Mary female 22; 2015003 Lisi male 24.");
        createTable("lab3_Student_original", new String[] {"info"});
        String[][] originalStudents = {{"2015001", "Zhangsan", "male", "23"},
                {"2015003", "Mary", "female", "22"}, {"2015003", "Lisi", "male", "24"}};
        for (String[] s : originalStudents) {
            addRecord("lab3_Student_original", s[1],
                    new String[] {"info:S_No", "info:S_Name", "info:S_Sex", "info:S_Age"}, s);
        }
        check(countRows("lab3_Student_original") == 3, "Original Student records preserved");
        scanTable("lab3_Student_original");
        createTable("lab3_Student", new String[] {"info", "Score"});
        createTable("lab3_Course", new String[] {"info"});
        createTable("lab3_SC", new String[] {"info"});
        String[][] students = {{"2015001", "Zhangsan", "male", "23"},
                {"2015002", "Mary", "female", "22"}, {"2015003", "Lisi", "male", "24"}};
        for (String[] s : students) {
            addRecord("lab3_Student", s[1],
                    new String[] {"info:S_No", "info:S_Name", "info:S_Sex", "info:S_Age"}, s);
        }
        String[][] courses = {{"123001", "Math", "2.0"},
                {"123002", "Computer Science", "5.0"}, {"123003", "English", "3.0"}};
        for (String[] c : courses) {
            addRecord("lab3_Course", c[0],
                    new String[] {"info:C_No", "info:C_Name", "info:C_Credit"}, c);
        }
        String[][] sc = {{"2015001", "123001", "86"}, {"2015001", "123003", "69"},
                {"2015002", "123002", "77"}, {"2015002", "123003", "99"},
                {"2015003", "123001", "98"}, {"2015003", "123002", "95"}};
        for (String[] item : sc) {
            addRecord("lab3_SC", item[0] + "_" + item[1],
                    new String[] {"info:SC_Sno", "info:SC_Cno", "info:SC_Score"}, item);
            String studentName = students[Integer.parseInt(item[0]) - 2015001][1];
            String courseName = courses[Integer.parseInt(item[1]) - 123001][1];
            addRecord("lab3_Student", studentName,
                    new String[] {"Score:" + courseName}, new String[] {item[2]});
        }
        check(countRows("lab3_Student") == 3, "Student rows = 3");
        check(countRows("lab3_Course") == 3, "Course rows = 3");
        check(countRows("lab3_SC") == 6, "SC rows = 6");
        check("77".equals(value("lab3_Student", "Mary", "Score:Computer Science")),
                "Student scores mapped by student ID and course ID");
    }

    private void demonstration() throws IOException {
        loadData();
        List<String> tables = listTables();
        check(tables.contains("lab3_Student") && tables.contains("lab3_Course")
                && tables.contains("lab3_SC"), "list all user tables");
        scanTable("lab3_Student");
        scanTable("lab3_Course");
        scanTable("lab3_SC");
        Map<String, Map<String, String>> family = scanColumn("lab3_Student", "Score");
        check(family.size() == 3 && family.get("Mary").size() == 2,
                "scanColumn expands every qualifier in a family");
        Map<String, Map<String, String>> math = scanColumn("lab3_Student", "Score:Math");
        check(math.size() == 3 && math.containsKey("Mary") && math.get("Mary") == null
                && "86".equals(math.get("Zhangsan").get("Score:Math")),
                "scanColumn keeps missing cells as null");

        String demo = "lab3_demo";
        createTable(demo, new String[] {"info"});
        addRecord(demo, "old", new String[] {"info:value"}, new String[] {"old"});
        createTable(demo, new String[] {"info"});
        check(countRows(demo) == 0, "createTable replaces an existing table");
        addRecord(demo, "row1", new String[] {"info:age", "info:temp"},
                new String[] {"23", "remove-me"});
        addRecord(demo, "row2", new String[] {"info:age"}, new String[] {"22"});
        addFamily(demo, "Extra");
        addRecord(demo, "row1", new String[] {"Extra:note"}, new String[] {"added"});
        check(admin.getDescriptor(TableName.valueOf(demo)).hasColumnFamily(b("Extra"))
                && "added".equals(value(demo, "row1", "Extra:note")), "add column family and cell");
        deleteColumn(demo, "row1", "info:temp");
        check(value(demo, "row1", "info:temp") == null, "delete a column (all versions)");
        deleteFamily(demo, "Extra");
        check(!admin.getDescriptor(TableName.valueOf(demo)).hasColumnFamily(b("Extra")),
                "delete column family");
        System.setProperty("lab3.modify.value", "24");
        try { modifyData(demo, "row1", "info:age"); }
        finally { System.clearProperty("lab3.modify.value"); }
        check("24".equals(value(demo, "row1", "info:age")), "modifyData 23 -> 24");
        scanTable(demo);
        deleteRow(demo, "row2");
        check(countRows(demo) == 1, "deleteRow removes row2");
        clearTable(demo);
        check(countRows(demo) == 0, "truncate clears all records and preserves table schema");
        check(admin.getDescriptor(TableName.valueOf(demo)).hasColumnFamily(b("info")),
                "column family survives truncate");
        System.out.println("HBASE JAVA ALL PASS");
    }

    @Override public void close() throws IOException {
        try { admin.close(); } finally { connection.close(); }
    }

    public static void main(String[] args) throws Exception {
        try (HBaseLab lab = new HBaseLab()) { lab.demonstration(); }
    }
}
