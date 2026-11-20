package com.qoder.payment.payment.mq;

import com.qoder.payment.payment.dataobject.OutboxDO;
import com.qoder.payment.payment.mapper.OutboxMapper;
import com.qoder.tcc.TccContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * I-4 事务消息本地事务判定 + 回查（outbox 仍是唯一事实来源，红线）。
 *
 * <p>时序：TCC 终态 + outbox 落库在同一本地事务先行提交（TccCoordinator#driveConfirm，
 * 与升级前逐字节一致）→ afterCommit 发半消息 → 本监听器按 outbox 行判定 commit/rollback：
 * <ul>
 *   <li>行存在（PENDING/DELIVERED/DEAD）→ 本地事务已提交 → COMMIT；
 *       DELIVERED/DEAD 行由消费端 {@code deliverByEventBizId} 幂等跳过，双投不重结算/不重通知。</li>
 *   <li>行不存在 → 本地事务未提交/已回滚 → ROLLBACK。</li>
 *   <li>DB 异常 → UNKNOWN（绝不臆断 ROLLBACK），交 broker 回查重试。</li>
 * </ul>
 *
 * <p>回查（checkLocalTransaction）解决「本地事务成功但 commit 半消息丢失」（进程在半消息发出后、
 * endTransaction 前崩溃）：broker 按 producerGroup 回查到重启后的实例，按同一判据 COMMIT。
 * 回查与 3s 轮询兜底可能双投 → 接收端 IdempotentTemplate（SETTLE_PAY/SETTLE_REFUND/NOTIFY 键不变）吸收。
 */
@Slf4j
@RequiredArgsConstructor
public class OutboxTransactionListener implements TransactionListener {

    private final OutboxMapper outboxMapper;
    private final MqProperties props;

    @Override
    public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        MqTxCtx ctx = arg instanceof MqTxCtx c ? c : parseCtx(msg.getBody(), msg.getTags(), msg.getKeys());
        // 混沌注入（默认 0 不生效）：制造「半消息已发、commit 未达」崩溃窗口，仅 verify-mq.py 使用
        if (props.getTxChaosDelayMs() > 0) {
            try {
                Thread.sleep(props.getTxChaosDelayMs());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        LocalTransactionState state = decide(ctx.event, ctx.bizId, ctx);
        ctx.decision = halfState(state);
        log.info("outbox 半消息本地事务判定 event={} bizId={} state={}", ctx.event, ctx.bizId, ctx.decision);
        return state;
    }

    @Override
    public LocalTransactionState checkLocalTransaction(MessageExt msg) {
        MqTxCtx ctx = parseCtx(msg.getBody(), msg.getTags(), msg.getKeys());
        LocalTransactionState state = decide(ctx.event, ctx.bizId, ctx);
        ctx.decision = halfState(state);
        log.info("outbox 事务回查 event={} bizId={} state={} msgId={}", ctx.event, ctx.bizId, ctx.decision, msg.getMsgId());
        // 回查判定留痕（additive 元数据，绝不动 status/attempts/next_retry_at）；失败不影响判定
        if (ctx.outboxId != null) {
            try {
                outboxMapper.updateMqMeta(ctx.outboxId, msg.getMsgId(), ctx.decision);
            } catch (Exception e) {
                log.warn("outbox 回查留痕失败 id={}: {}", ctx.outboxId, e.getMessage());
            }
        }
        return state;
    }

    /** half_state 列口径：COMMIT/ROLLBACK/UNKNOWN（RocketMQ 枚举 UNKNOW 归一为 UNKNOWN）。 */
    private static String halfState(LocalTransactionState state) {
        return switch (state) {
            case COMMIT_MESSAGE -> "COMMIT";
            case ROLLBACK_MESSAGE -> "ROLLBACK";
            default -> "UNKNOWN";
        };
    }

    /** 唯一判据：outbox 行存在 ⇔ 本地事务（TCC 终态+outbox）已原子提交。 */
    private LocalTransactionState decide(String event, String bizId, MqTxCtx ctx) {
        try {
            OutboxDO row = outboxMapper.selectByEventBizId(event, bizId);
            if (ctx != null) {
                ctx.outboxId = row == null ? null : row.getId();
            }
            if (row == null) {
                return LocalTransactionState.ROLLBACK_MESSAGE;
            }
            // PENDING/DELIVERED/DEAD 均证明本地事务已提交；非 PENDING 行消费端幂等跳过
            return LocalTransactionState.COMMIT_MESSAGE;
        } catch (Exception e) {
            log.warn("outbox 本地事务判定异常（留 UNKNOWN 待回查）event={} bizId={}: {}", event, bizId, e.getMessage());
            return LocalTransactionState.UNKNOW;
        }
    }

    @SuppressWarnings("unchecked")
    private static MqTxCtx parseCtx(byte[] body, String tags, String keys) {
        String event = tags;
        String bizId = keys;
        try {
            Map<String, String> m = TccContextUtil.fromJson(new String(body, StandardCharsets.UTF_8), Map.class);
            if (m.get("event") != null) {
                event = m.get("event");
            }
            if (m.get("bizId") != null) {
                bizId = m.get("bizId");
            }
        } catch (Exception ignore) {
            // body 解析失败退回 tags/keys（生产端恒写 tags=event、keys=bizId）
        }
        return new MqTxCtx(event, bizId);
    }
}