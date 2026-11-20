package com.qoder.payment.common.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * I-5a 影子写：<b>对称 additive 双写</b>，让「分片 ⇄ 单库」两个方向都随时结果一致。
 *
 * <ul>
 *   <li>分片态：业务写落分片表 → 镜像回<b>原单表</b>（回滚 enabled=false 后结果与切前一致）；</li>
 *   <li>单库态（分片 Bean 已装配但开关切回 false）：业务写落原单表 → 镜像进<b>对应分片表</b>
 *       （再切回分片后不丢行，杜绝「单库期新增行在分片侧不可见」）。</li>
 * </ul>
 *
 * <p>时机：业务事务 {@code afterCommit} 之后才镜像（事务回滚则不镜像，绝不留多余行）；
 * 无事务时立即镜像。镜像走<b>原单库 DataSource</b>（不经 ShardingSphere），只 INSERT，
 * 主键/唯一键冲突即跳过 —— <b>不 UPDATE、不 DELETE 任何既有行</b>。
 *
 * <p>镜像失败只记 ERROR + 计数（不影响已提交的业务写入）；重启时
 * {@link ShardTableBootstrap} 的双向补偿复制会把缺行补齐，最终一致。
 */
public class ShardMirror {

    private static final Logger log = LoggerFactory.getLogger(ShardMirror.class);

    private final DataSource raw;
    private final ShardRuntime runtime;

    public ShardMirror(DataSource raw, ShardRuntime runtime) {
        this.raw = raw;
        this.runtime = runtime;
    }

    /** 登记一行镜像：目标表按当前路由态取「另一侧」，保证两侧最终一致。 */
    public void insert(String logicTable, Map<String, Object> row) {
        if (!runtime.mirrorLegacy()) {
            return;
        }
        String shardColumn = runtime.tables().get(logicTable);
        if (shardColumn == null) {
            return;
        }
        String target;
        if (runtime.isActive()) {
            target = logicTable;
        } else {
            Object key = row.get(shardColumn);
            target = ShardKeys.physicalTable(logicTable,
                    ShardKeys.shardOf(key == null ? null : String.valueOf(key), runtime.shardCount()));
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doInsert(target, row);
                }
            });
        } else {
            doInsert(target, row);
        }
    }

    private void doInsert(String table, Map<String, Object> row) {
        List<String> cols = new ArrayList<>(row.keySet());
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append('(');
        for (int i = 0; i < cols.size(); i++) {
            sql.append(i == 0 ? "" : ",").append(cols.get(i));
        }
        sql.append(") VALUES(");
        for (int i = 0; i < cols.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
        }
        sql.append(')');
        try (Connection con = raw.getConnection(); PreparedStatement ps = con.prepareStatement(sql.toString())) {
            con.setAutoCommit(true);
            for (int i = 0; i < cols.size(); i++) {
                ps.setObject(i + 1, row.get(cols.get(i)));
            }
            ps.executeUpdate();
            runtime.mirrorOk();
        } catch (SQLException e) {
            if (ShardTableBootstrap.isDuplicate(e)) {
                runtime.mirrorOk();
                return;
            }
            runtime.mirrorFailed();
            log.error("I-5a 影子写回原单表失败（不影响已提交业务写，重启补偿复制会补齐） table={} err={}", table, e.toString());
        }
    }
}