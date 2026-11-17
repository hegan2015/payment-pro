package com.qoder.payment.dtx;

import com.qoder.tcc.TccException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.function.Supplier;

/**
 * 幂等模板薄适配：继承 tcc-core 通用模板，仅把组件抛出的 TccException
 * 转回 BizException（CONFLICT → A0409），保证使用方（结算/交易/账务/通知）
 * 经 FacadeSupport 转出的 Result 与组件化前完全一致。幂等语义零改动。
 */
public class IdempotentTemplate extends com.qoder.tcc.IdempotentTemplate {

    public IdempotentTemplate(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        super(jdbc, txManager);
    }

    @Override
    public <T> T execute(String bizType, String bizNo, Class<T> resultType, Supplier<T> action) {
        try {
            return super.execute(bizType, bizNo, resultType, action);
        } catch (TccException e) {
            throw TccExceptions.toBiz(e);
        }
    }
}