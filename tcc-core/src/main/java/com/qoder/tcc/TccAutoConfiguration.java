package com.qoder.tcc;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/** 引入 tcc-core 即自动装配参与者模板与幂等模板（接入方可用自定义子类 Bean 覆盖）。无数据源的应用自动跳过。 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnSingleCandidate(DataSource.class)
public class TccAutoConfiguration {

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