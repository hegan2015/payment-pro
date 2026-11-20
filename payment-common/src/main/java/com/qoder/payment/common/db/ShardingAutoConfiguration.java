package com.qoder.payment.common.db;

import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * I-5a ShardingSphere-JDBC 试点自动装配（<b>仅 docker、仅非资金域 settle/recon</b>）。
 *
 * <p><b>双 profile 红线</b>：{@code payment.sharding.enabled=true} 才装配（默认 false）。
 * local / 未开启的域<b>一个 ShardingSphere Bean 都不加载、一条分片连接都不建</b>，
 * 直连 H2 单库单表、取号仍走 {@code next_value_for}，行为逐字节不变。
 *
 * <p><b>方案选型：单库分表</b>（{@code pp_settle.settle_record_0..3} / {@code pp_recon.recon_report_0..3}），
 * 不新增 pp_settle_0..3 库。理由：
 * <ul>
 *   <li>幂等表 {@code idempotent_record} 与业务写仍在<b>同一库同一本地事务</b>，五层幂等原子性不变
 *       （多库会让「幂等占位 + 业务写」跨库，破坏 IdempotentTemplate 的原子语义）；</li>
 *   <li>ShedLock / dispute_order / sequences 等支撑表保持单库单表，调度互斥与差错平台零改动；</li>
 *   <li>不新建库、不动既有连接串与权限，回滚只需切 DataSource，风险面最小。</li>
 * </ul>
 *
 * <p><b>启动顺序</b>：先建分片表 + additive 双向复制（{@link ShardTableBootstrap}），再创建
 * ShardingSphere DataSource（否则其元数据加载会因物理表缺失失败）；任一步失败 fail-fast，
 * 绝不静默降级到「分片表缺行」的错误状态。
 *
 * <p>装配早于 {@link DataSourceAutoConfiguration}，使 Spring Boot 默认单 DataSource 装配自动退让；
 * {@code @Primary} 为可回滚路由 DataSource，MyBatis / 幂等 JdbcTemplate / ShedLock 共用同一连接与事务。
 */
@AutoConfiguration(before = DataSourceAutoConfiguration.class)
@ConditionalOnClass(name = "org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory")
@ConditionalOnProperty(prefix = "payment.sharding", name = "enabled", havingValue = "true")
public class ShardingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ShardingAutoConfiguration.class);

    private static final String WRITE_DS = "ds_write";
    private static final String READ_DS = "ds_read";
    private static final String RW_DS = "rw_ds";

    @Bean
    public ShardRuntime paymentShardRuntime(Environment env) {
        String app = env.getProperty("spring.application.name", "app");
        int shardCount = env.getProperty("payment.sharding.shard-count", Integer.class, 4);
        Map<String, String> tables = Binder.get(env)
                .bind("payment.sharding.tables", Bindable.mapOf(String.class, String.class))
                .orElseGet(LinkedHashMap::new);
        if (tables.isEmpty()) {
            throw new IllegalStateException("I-5a payment.sharding.enabled=true 但未配置 payment.sharding.tables");
        }
        boolean rw = env.getProperty("payment.sharding.readwrite-splitting", Boolean.class, true);
        boolean mirror = env.getProperty("payment.sharding.mirror-legacy", Boolean.class, true);
        // I-5b（additive，默认 true ⇒ I-5a 试点域行为逐字节不变）：资金域可置 false，
        // 装配分片 Bean 但启动即单库观察态，切流由 /actuator/ppshard 显式触发（持久化于 shard_route_state）。
        boolean initialActive = env.getProperty("payment.sharding.initial-active", Boolean.class, true);
        log.info("I-5a 分片试点装配 app={} tables={} shardCount={} 读写分离={} 影子写回原单表={} 初始路由={}",
                app, tables, shardCount, rw, mirror, initialActive ? "SHARDED" : "SINGLE");
        ShardRuntime runtime = new ShardRuntime(app, tables, shardCount, rw, mirror);
        runtime.setActive(initialActive);
        return runtime;
    }

    @Bean
    public IdGenerator paymentIdGenerator(Environment env) {
        long epoch = env.getProperty("payment.idgen.epoch", Long.class, IdGenerator.DEFAULT_EPOCH);
        int configured = env.getProperty("payment.idgen.worker-id", Integer.class, -1);
        String zk = env.getProperty("dubbo.registry.address", "");
        if (zk.startsWith("zookeeper://")) {
            zk = zk.substring("zookeeper://".length());
            int q = zk.indexOf('?');
            if (q > 0) {
                zk = zk.substring(0, q);
            }
        } else {
            zk = "";
        }
        String path = env.getProperty("payment.idgen.zk-path", "/payment-pro/idgen/workers");
        int port = env.getProperty("server.port", Integer.class, 0);
        String app = env.getProperty("spring.application.name", "app");
        String instanceKey = app + "@" + port + "#" + ProcessHandle.current().pid();
        IdGenerator[] holder = new IdGenerator[1];
        final String zkAddress = zk;
        IdGenerator generator = new IdGenerator(epoch,
                () -> new WorkerIdAssigner(holder[0], configured, zkAddress, path, port, instanceKey).assign());
        holder[0] = generator;
        return generator;
    }

    /** 原单库连接池：分片前的 DataSource，也是回滚目标 + 影子写/物理探针通道。 */
    @Bean(name = "shardingSingleDataSource")
    public DataSource shardingSingleDataSource(Environment env) {
        return buildPool(env, "single");
    }

    /** 读库连接池：共享栈 MySQL 为单实例（无只读副本），读池指向同实例，结构上完成读写分离装配。 */
    @Bean(name = "shardingReadDataSource")
    public DataSource shardingReadDataSource(Environment env) {
        return buildPool(env, "read");
    }

    private static DataSource buildPool(Environment env, String role) {
        DataSourceProperties props = Binder.get(env)
                .bind("spring.datasource", Bindable.of(DataSourceProperties.class))
                .orElseGet(DataSourceProperties::new);
        DataSource ds = props.initializeDataSourceBuilder().build();
        Binder.get(env).bind("payment.sharding.pool", Bindable.ofInstance(ds));
        log.info("I-5a {} 连接池就绪 url={}", role, props.determineUrl());
        return ds;
    }

    @Bean
    @Primary
    public DataSource dataSource(@Qualifier("shardingSingleDataSource") DataSource single,
                                 @Qualifier("shardingReadDataSource") DataSource read,
                                 ShardRuntime runtime, Environment env,
                                 ObjectProvider<ShardPreBootstrap> preBootstraps) {
        // ① 先建分片表 + additive 双向复制（原表原行全部保留）
        runtime.bootstrapStats(ShardTableBootstrap.bootstrap(single, runtime.tables(), runtime.shardCount()));
        // ①b I-5b（additive）：资金域前置初始化——建单库支撑表（幂等索引/路由状态）、恢复持久化路由、
        //     按权威侧全行同步影子副本。未提供实现的域（settle/recon）此步为空操作，I-5a 行为不变。
        preBootstraps.orderedStream().forEach(pre -> pre.preBootstrap(single, runtime));
        // ② 再造 ShardingSphere DataSource
        Map<String, DataSource> pools = new LinkedHashMap<>();
        pools.put(WRITE_DS, single);
        boolean rw = runtime.readwriteSplitting();
        if (rw) {
            pools.put(READ_DS, read);
        }
        String yaml = buildYaml(runtime, env, rw);
        DataSource sharded;
        try {
            sharded = YamlShardingSphereDataSourceFactory.createDataSource(pools, yaml.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("I-5a ShardingSphere-JDBC 初始化失败（docker fail-fast，不静默降级）: "
                    + e.getMessage(), e);
        }
        log.info("I-5a ShardingSphere-JDBC 就绪（单库分表 + 读写分离={}）", rw);
        return new ShardingToggleDataSource(single, sharded, runtime);
    }

    @Bean
    public ShardMirror paymentShardMirror(@Qualifier("shardingSingleDataSource") DataSource single, ShardRuntime runtime) {
        return new ShardMirror(single, runtime);
    }

    @Bean
    public ShardProbeSupport paymentShardProbeSupport(@Qualifier("shardingSingleDataSource") DataSource single,
                                                      DataSource routed, ShardRuntime runtime, IdGenerator idGenerator) {
        return new ShardProbeSupport(single, routed, runtime, idGenerator);
    }

    /** 运行期生成 YAML（口令只在内存，不落仓库）。 */
    private static String buildYaml(ShardRuntime runtime, Environment env, boolean rw) {
        String logicDs = rw ? RW_DS : WRITE_DS;
        String database = runtime.appName().replaceAll("[^A-Za-z0-9_]", "_") + "_logic";
        boolean sqlShow = env.getProperty("payment.sharding.sql-show", Boolean.class, true);
        List<String> singleTables = Binder.get(env)
                .bind("payment.sharding.single-tables", Bindable.listOf(String.class))
                .orElseGet(ArrayList::new);
        StringBuilder sb = new StringBuilder();
        sb.append("databaseName: ").append(database).append('\n');
        sb.append("mode:\n  type: Standalone\n  repository:\n    type: JDBC\n");
        sb.append("rules:\n");
        if (rw) {
            // 5.5.0 起读写分离配置扁平化（无 staticStrategy 层）；
            // transactionalReadQueryStrategy=PRIMARY：事务内读强制走主库（资金/结算读己之写的第一道保障）
            sb.append("- !READWRITE_SPLITTING\n  dataSources:\n    ").append(RW_DS).append(":\n")
                    .append("      writeDataSourceName: ").append(WRITE_DS).append('\n')
                    .append("      readDataSourceNames:\n        - ").append(READ_DS).append('\n')
                    .append("      transactionalReadQueryStrategy: PRIMARY\n")
                    .append("      loadBalancerName: pp_lb\n")
                    .append("  loadBalancers:\n    pp_lb:\n      type: ROUND_ROBIN\n");
        }
        sb.append("- !SHARDING\n  tables:\n");
        StringBuilder algorithms = new StringBuilder("  shardingAlgorithms:\n");
        for (Map.Entry<String, String> entry : runtime.tables().entrySet()) {
            String logic = entry.getKey();
            String column = entry.getValue();
            String algorithm = "pp_" + logic + "_crc32";
            sb.append("    ").append(logic).append(":\n      actualDataNodes: ");
            for (int k = 0; k < runtime.shardCount(); k++) {
                sb.append(k == 0 ? "" : ",").append(logicDs).append('.').append(ShardKeys.physicalTable(logic, k));
            }
            sb.append("\n      tableStrategy:\n        standard:\n")
                    .append("          shardingColumn: ").append(column).append('\n')
                    .append("          shardingAlgorithmName: ").append(algorithm).append('\n');
            algorithms.append("    ").append(algorithm).append(":\n      type: CLASS_BASED\n      props:\n")
                    .append("        strategy: STANDARD\n")
                    .append("        algorithmClassName: ").append(Crc32ModShardingAlgorithm.class.getName()).append('\n')
                    .append("        sharding-count: ").append(runtime.shardCount()).append('\n');
        }
        sb.append(algorithms);
        sb.append("- !SINGLE\n  tables:\n");
        // 单表必须挂在「逻辑数据源」上：读写分离启用后 ds_write/ds_read 被 rw_ds 包裹，
        // 直接用物理名会报 "Data source ds_write does not exist in database"（5.5.0 实测）。
        for (String table : singleTables) {
            sb.append("    - \"").append(logicDs).append('.').append(table).append("\"\n");
        }
        sb.append("  defaultDataSource: ").append(logicDs).append('\n');
        sb.append("props:\n  sql-show: ").append(sqlShow).append('\n');
        return sb.toString();
    }
}