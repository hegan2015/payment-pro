package com.qoder.payment.payment.config;

import com.qoder.payment.dtx.TccCoordinator;
import com.qoder.payment.payment.service.PaymentCompletionListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class PaymentConfig {

    @Bean
    public TccCoordinator tccCoordinator(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                                         TccParticipants participants, PaymentCompletionListener listener) {
        return new TccCoordinator(jdbc, txManager, participants.asMap(), listener);
    }

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("payment-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}