package com.qoder.payment.channel.sign;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * CH-6 本地事务边界（channel 库）：协议状态 + 扣款单 + 审计 + 事件 outbox 原子落地。
 *
 * <p>编程式事务（避免同类自调用绕过 @Transactional 代理）；<b>RPC 一律在事务外</b>：
 * 渠道 SPI / payment 受理 / notify 投递绝不持有数据库行锁跨网络调用。
 */
@Component
@RequiredArgsConstructor
public class SignTx {

    private final PlatformTransactionManager txManager;

    public <T> T execute(Supplier<T> action) {
        TransactionTemplate template = new TransactionTemplate(txManager);
        return template.execute(status -> action.get());
    }

    public void run(Runnable action) {
        execute(() -> {
            action.run();
            return null;
        });
    }
}