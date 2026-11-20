package com.qoder.payment.payment.config;

import com.qoder.payment.payment.service.PaymentCompletionListener;
import com.qoder.tcc.TccCoordinator;
import com.qoder.tcc.TccParticipant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;
import java.util.Set;

@Configuration
public class PaymentConfig {

    @Bean
    public TccCoordinator tccCoordinator(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                                         TccParticipants participants, PaymentCompletionListener listener) {
        // I-6 自注册：传活视图而非启动期快照。协调器只按名 get(group)，按需解析参与者引用，
        // 新参与者域上线后无需改代码/重启即被纳入；包装器按 group 缓存避免重复建对象。
        Map<String, TccParticipant> wrappers = new java.util.concurrent.ConcurrentHashMap<>();
        Map<String, TccParticipant> live = new java.util.AbstractMap<String, TccParticipant>() {
            @Override
            public TccParticipant get(Object key) {
                String group = String.valueOf(key);
                return wrappers.computeIfAbsent(group, g -> new DubboTccParticipant(participants.facade(g)));
            }

            @Override
            public Set<Entry<String, TccParticipant>> entrySet() {
                Map<String, TccParticipant> snapshot = new java.util.LinkedHashMap<>();
                for (String g : participants.asMap().keySet()) {
                    snapshot.put(g, get(g));
                }
                return snapshot.entrySet();
            }
        };
        return new TccCoordinator(jdbc, txManager, live, listener);
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