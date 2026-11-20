package com.qoder.payment.common.db;

import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * I-5a 可回滚路由 DataSource：按 {@link ShardRuntime#isActive()} 在
 * 「ShardingSphere 分片 DataSource」与「原单库 DataSource」之间切换。
 *
 * <p>它是 {@code @Primary} DataSource，MyBatis / JdbcTemplate（幂等表）/ ShedLock 全部经它取连接，
 * 因此<b>同一事务内连接一致</b>：分片期业务写与幂等占位仍在同一本地事务里（原子性不变）。
 *
 * <p>切换只影响后续 {@code getConnection()}，进行中的事务沿用原连接直至提交，不会半途换库。
 */
public class ShardingToggleDataSource extends AbstractDataSource {

    private final DataSource single;
    private final DataSource sharded;
    private final ShardRuntime runtime;

    public ShardingToggleDataSource(DataSource single, DataSource sharded, ShardRuntime runtime) {
        this.single = single;
        this.sharded = sharded;
        this.runtime = runtime;
    }

    private DataSource target() {
        return runtime.isActive() ? sharded : single;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return target().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return target().getConnection(username, password);
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return target().unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || target().isWrapperFor(iface);
    }

    public DataSource singleDataSource() {
        return single;
    }

    public DataSource shardedDataSource() {
        return sharded;
    }

    /** 容器销毁：关闭 ShardingSphere 元数据/连接池（原单库池由各自 @Bean 销毁方法关闭）。 */
    public void close() {
        if (sharded instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // 关闭失败不阻断停机
            }
        }
    }
}