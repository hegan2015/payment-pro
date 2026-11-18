import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 只读 H2 → MySQL 数据迁移。
 * 把 data/<domain>.mv.db 复制到临时目录后以 ACCESS_MODE_DATA=r 打开（绝不写原 H2 库），
 * 逐表 INSERT IGNORE 进 pp_<domain>（按主键/唯一键去重，可重复执行），
 * 并按 H2 序列 BASE_VALUE 回填 MySQL sequences.value（GREATEST 取大，避免取号回退碰撞）。
 * 用法见 migrate-h2-to-mysql.sh；--dry-run 只统计不写 MySQL。
 */
public class MigrateH2ToMySQL {

    private static final String[] DOMAINS = {
            "member", "account", "accounting", "trade", "channel",
            "settle", "notify", "recon", "payment"
    };

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("用法: MigrateH2ToMySQL <dataDir> <host> <port> <user> <password> [--dry-run]");
            System.exit(2);
        }
        String dataDir = args[0], host = args[1], port = args[2], user = args[3], pass = args[4];
        boolean dry = args.length > 5 && "--dry-run".equals(args[5]);

        Class.forName("org.h2.Driver");
        Class.forName("com.mysql.cj.jdbc.Driver");

        Path tmp = Files.createTempDirectory("pp-h2-migrate-");
        System.out.println("==> 只读迁移 H2 → MySQL" + (dry ? "（DRY-RUN，不写 MySQL）" : ""));
        System.out.println("==> H2 副本临时目录: " + tmp + "（原 data/ 库文件不被打开/修改）");

        long grandRows = 0;
        int grandSeqs = 0;
        for (String d : DOMAINS) {
            Path src = Paths.get(dataDir, d + ".mv.db");
            if (!Files.exists(src)) {
                System.out.println("[skip] " + d + ": 无 H2 文件 " + src);
                continue;
            }
            Path copy = tmp.resolve(d + ".mv.db");
            Files.copy(src, copy, StandardCopyOption.REPLACE_EXISTING);
            String h2Base = copy.toAbsolutePath().toString();
            h2Base = h2Base.substring(0, h2Base.length() - ".mv.db".length());
            String h2Url = "jdbc:h2:file:" + h2Base + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;MODE=MySQL;DATABASE_TO_LOWER=TRUE";
            String myUrl = "jdbc:mysql://" + host + ":" + port + "/pp_" + d
                    + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                    + "&useSSL=false&allowPublicKeyRetrieval=true";

            System.out.println("\n== pp_" + d + " ==");
            try (Connection h2 = DriverManager.getConnection(h2Url, "sa", "");
                 Connection my = DriverManager.getConnection(myUrl, user, pass)) {
                my.setAutoCommit(false);
                try {
                    long rows = migrateTables(h2, my, d, dry);
                    int seqs = migrateSequences(h2, my, d, dry);
                    if (dry) {
                        my.rollback();
                    } else {
                        my.commit();
                    }
                    grandRows += rows;
                    grandSeqs += seqs;
                } catch (Exception e) {
                    my.rollback();
                    throw e;
                }
            }
        }
        System.out.println("\n==> 完成：迁移数据行 " + grandRows + "，序列 " + grandSeqs
                + (dry ? "（DRY-RUN）" : ""));
        System.out.println("==> 原 H2 库文件保持不变；临时副本可自行删除: " + tmp);
    }

    private static long migrateTables(Connection h2, Connection my, String domain, boolean dry) throws Exception {
        List<String> tables = new ArrayList<>();
        try (ResultSet rs = h2.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                if (schema != null && schema.equalsIgnoreCase("information_schema")) {
                    continue;
                }
                tables.add(rs.getString("TABLE_NAME"));
            }
        }
        Set<String> myTables = new HashSet<>();
        try (ResultSet rs = my.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                myTables.add(rs.getString("TABLE_NAME").toLowerCase());
            }
        }
        long total = 0;
        for (String t : tables) {
            if (!myTables.contains(t.toLowerCase())) {
                System.out.println("  [warn] pp_" + domain + " 缺表 " + t + "，跳过（请先执行 schema-mysql.sql）");
                continue;
            }
            long n = copyTable(h2, my, t, dry);
            total += n;
            System.out.println("  " + t + ": " + n + " 行" + (dry ? "（待迁移）" : " 已写入"));
        }
        return total;
    }

    private static long copyTable(Connection h2, Connection my, String table, boolean dry) throws Exception {
        String sel = "SELECT * FROM \"" + table + "\"";
        try (Statement st = h2.createStatement(); ResultSet rs = st.executeQuery(sel)) {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            StringBuilder colList = new StringBuilder();
            StringBuilder placeholders = new StringBuilder();
            for (int i = 1; i <= cols; i++) {
                if (i > 1) {
                    colList.append(',');
                    placeholders.append(',');
                }
                colList.append('`').append(md.getColumnName(i)).append('`');
                placeholders.append('?');
            }
            String ins = "INSERT IGNORE INTO `" + table + "` (" + colList + ") VALUES (" + placeholders + ")";
            long count = 0;
            if (dry) {
                while (rs.next()) {
                    count++;
                }
                return count;
            }
            try (PreparedStatement ps = my.prepareStatement(ins)) {
                while (rs.next()) {
                    for (int i = 1; i <= cols; i++) {
                        ps.setString(i, rs.getString(i));
                    }
                    ps.addBatch();
                    count++;
                    if (count % 500 == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
            }
            return count;
        }
    }

    private static int migrateSequences(Connection h2, Connection my, String domain, boolean dry) throws Exception {
        boolean hasSeqTable;
        try (ResultSet rs = my.getMetaData().getTables(null, null, "sequences", new String[]{"TABLE"})) {
            hasSeqTable = rs.next();
        }
        List<String[]> seqs = new ArrayList<>();
        try (Statement st = h2.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT SEQUENCE_NAME, BASE_VALUE FROM INFORMATION_SCHEMA.SEQUENCES")) {
            while (rs.next()) {
                seqs.add(new String[]{rs.getString(1).toLowerCase(), rs.getString(2)});
            }
        }
        if (seqs.isEmpty()) {
            return 0;
        }
        if (!hasSeqTable) {
            System.out.println("  [warn] pp_" + domain + " 无 sequences 表，跳过序列回填");
            return 0;
        }
        int n = 0;
        for (String[] s : seqs) {
            long val = Long.parseLong(s[1]);
            System.out.println("  序列 " + s[0] + " → value=" + val + (dry ? "（待回填）" : " 已回填"));
            if (!dry) {
                try (PreparedStatement ps = my.prepareStatement(
                        "INSERT INTO sequences(name,value) VALUES(?,?) "
                                + "ON DUPLICATE KEY UPDATE value=GREATEST(value, VALUES(value))")) {
                    ps.setString(1, s[0]);
                    ps.setLong(2, val);
                    ps.executeUpdate();
                }
            }
            n++;
        }
        return n;
    }
}