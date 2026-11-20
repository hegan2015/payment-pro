package com.qoder.payment.common.db;

import javax.sql.DataSource;

/**
 * I-5b 分片 DataSource 创建<b>之前</b>的资金域前置初始化钩子（additive，不影响 I-5a 试点域）。
 *
 * <p>为什么必须在 ShardingSphere DataSource 创建之前：
 * <ul>
 *   <li>新增的<b>单库不分片</b>支撑表（幂等索引表 / 路由状态表）必须先存在，
 *       否则 ShardingSphere 元数据加载 SINGLE 规则会失败；</li>
 *   <li>资金域含<b>可变行</b>（余额/状态），I-5a 的「只 INSERT 复制」不足以让两侧逐列一致，
 *       需要在此按权威侧做全行同步（只 UPDATE 影子副本，绝不 DELETE/DROP 任何行）。</li>
 * </ul>
 *
 * <p>实现方只允许依赖 {@code shardingSingleDataSource}（原单库）与 {@link ShardRuntime}，
 * <b>不得依赖 {@code @Primary} 路由 DataSource</b>（那正是本钩子参与创建的 Bean，会成环）。
 *
 * <p>未提供实现时（settle/recon 试点域）{@link ShardingAutoConfiguration} 零额外动作，
 * I-5a 行为逐字节不变。
 */
public interface ShardPreBootstrap {

    /**
     * @param raw     原单库 DataSource（分片前的连接池，也是回滚目标）
     * @param runtime 分片运行态（此时 {@link ShardRuntime#isActive()} 已按 initial-active 置好）
     */
    void preBootstrap(DataSource raw, ShardRuntime runtime);
}