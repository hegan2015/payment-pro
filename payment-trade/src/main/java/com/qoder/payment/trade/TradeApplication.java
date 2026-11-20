package com.qoder.payment.trade;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableDubbo(scanBasePackages = {"com.qoder.payment.trade", "com.qoder.payment.common"})
// T-1/T-4 本地调度兜底（默认开关关闭，不自动跑批；见 TradeJobScheduler）
@EnableScheduling
public class TradeApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradeApplication.class, args);
    }
}