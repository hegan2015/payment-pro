package com.qoder.payment.risk;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableDubbo(scanBasePackages = {"com.qoder.payment.risk", "com.qoder.payment.common"})
// R-6 AML 监测作业（I-3 JobHandler）本地 @Scheduled 兜底触发；默认 auto-scan=false 不跑批
@EnableScheduling
public class RiskApplication {

    public static void main(String[] args) {
        SpringApplication.run(RiskApplication.class, args);
    }
}