import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;

public class HBaseReady {
    public static void main(String[] args) throws Exception {
        Configuration conf = HBaseConfiguration.create();
        conf.setInt("hbase.rpc.timeout", 5000);
        conf.setInt("hbase.client.operation.timeout", 10000);
        conf.setInt("hbase.client.retries.number", 1);
        try (Connection connection = ConnectionFactory.createConnection(conf);
             Admin admin = connection.getAdmin()) {
            if (admin.getClusterMetrics().getLiveServerMetrics().isEmpty()) {
                throw new IllegalStateException("Waiting for a live RegionServer");
            }
            admin.listTableNames();
            System.out.println("HBASE_READY");
        }
    }
}
