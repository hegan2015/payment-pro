package com.qoder.payment.accounting;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableDubbo(scanBasePackages = {"com.qoder.payment.accounting", "com.qoder.payment.common"})
// P1-E AC-3：本地 @Scheduled 兜底（默认 accounting.daycut.auto-enabled=false ⇒ 静默不跑批）
@EnableScheduling
public class AccountingApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccountingApplication.class, args);
    }
}