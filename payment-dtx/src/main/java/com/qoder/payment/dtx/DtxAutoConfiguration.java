package com.qoder.payment.dtx;

import com.qoder.tcc.TccAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 引入 payment-dtx 即自动装配适配版参与者模板与幂等模板。
 *
 * <p>声明 before = TccAutoConfiguration：适配 Bean（组件类的子类）先注册，
 * tcc-core 的 @ConditionalOnMissingBean 按类型命中后自动退让，全容器仅一套模板。
 */
@AutoConfiguration(before = TccAutoConfiguration.class)
@ConditionalOnClass(JdbcTemplate.class)
public class DtxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TccParticipantTemplate tccParticipantTemplate(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        return new TccParticipantTemplate(jdbc, txManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotentTemplate idempotentTemplate(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        return new IdempotentTemplate(jdbc, txManager);
    }
}