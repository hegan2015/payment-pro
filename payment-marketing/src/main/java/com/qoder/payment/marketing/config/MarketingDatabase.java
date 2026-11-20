package com.qoder.payment.marketing.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * docker profile 新库 additive 引导：{@code CREATE DATABASE IF NOT EXISTS pp_marketing}。
 *
 * <p>在 {@code SpringApplication.run} <b>之前</b>执行（DataSource 尚未创建），因此新库一定先于连接池存在。
 * 只创建库，绝不 DROP/ALTER 任何既有库表；既有 pp_* 库与数据零影响，也不改 ops-pro 部署脚本。
 *
 * <p><b>口令红线</b>：口令只从环境变量读取并直接交给 {@link DriverManager}，
 * 绝不拼进命令行 argv、绝不写日志（异常信息只输出 SQLState/ErrorCode 与主机端口）。
 *
 * <p>local profile 直接返回（H2 文件库由 JDBC URL 自动创建，零依赖）。
 */
public final class MarketingDatabase {

    private static final Logger log = LoggerFactory.getLogger(MarketingDatabase.class);

    public static final String DATABASE = "pp_marketing";

    public static void ensureDatabase() {
        if (!isDockerProfile()) {
            return;
        }
        String host = env("MYSQL_HOST", "127.0.0.1");
        String port = env("MYSQL_PORT", "3306");
        String user = env("MYSQL_USER", "root");
        String password = System.getenv("MYSQL_PASSWORD") == null ? "" : System.getenv("MYSQL_PASSWORD");
        String url = "jdbc:mysql://" + host + ":" + port
                + "/?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=15000";
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("营销域启动失败：classpath 缺 MySQL 驱动", e);
        }
        try (Connection con = DriverManager.getConnection(url, user, password)) {
            if (exists(con)) {
                log.info("营销域库 {} 已存在（additive 引导跳过，不动既有数据）", DATABASE);
                return;
            }
            try (Statement st = con.createStatement()) {
                st.execute("CREATE DATABASE IF NOT EXISTS " + DATABASE
                        + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            }
            log.info("营销域库 {} 已 additive 创建（utf8mb4_unicode_ci）", DATABASE);
        } catch (SQLException e) {
            // 无 CREATE 权限但库已存在时不阻断启动；库确实缺失则 fail-fast，避免静默连错库
            if (existsQuietly(url, user, password)) {
                log.warn("营销域库引导语句未执行（SQLState={} ErrorCode={}），但库 {} 已存在，继续启动",
                        e.getSQLState(), e.getErrorCode(), DATABASE);
                return;
            }
            throw new IllegalStateException("营销域启动失败：库 " + DATABASE + " 不存在且无法创建（SQLState="
                    + e.getSQLState() + " ErrorCode=" + e.getErrorCode() + "）", e);
        }
    }

    private static boolean exists(Connection con) throws SQLException {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SHOW DATABASES LIKE '" + DATABASE + "'")) {
            return rs.next();
        }
    }

    private static boolean existsQuietly(String url, String user, String password) {
        try (Connection con = DriverManager.getConnection(url, user, password)) {
            return exists(con);
        } catch (SQLException e) {
            return false;
        }
    }

    private static boolean isDockerProfile() {
        String profiles = System.getProperty("spring.profiles.active");
        if (profiles == null || profiles.isBlank()) {
            profiles = System.getenv("SPRING_PROFILES_ACTIVE");
        }
        return profiles != null && profiles.contains("docker");
    }

    private static String env(String key, String def) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? def : value;
    }

    private MarketingDatabase() {
    }
}