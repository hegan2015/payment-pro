package com.qoder.payment.marketing;

import com.qoder.payment.marketing.config.MarketingDatabase;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 营销域启动类（P2 X-3a/X-3b，第 14 个进程）。
 *
 * <p><b>端口</b>：Dubbo 20893 / actuator+local-api 18093（避开已用 20880-20892、18081-18092、
 * XXL 执行器 9993-9999、Apollo 18070-18072、网关 8080/8443）。
 *
 * <p><b>健康列表红线</b>：本域<b>不纳入</b>网关 HealthService 的 13 进程清单，
 * 既有 {@code ./run.sh} / {@code ./run.sh --docker} 的 13/13 硬断言逐字不变；
 * 营销域由 run.sh 额外拉起（可用 {@code PP_MARKETING_ENABLED=false} 关闭），单独验证。
 *
 * <p>{@link MarketingDatabase#ensureDatabase()} 在 Spring 上下文之前执行（docker profile），
 * 以 additive {@code CREATE DATABASE IF NOT EXISTS pp_marketing} 保证新库存在，不改 ops-pro 部署脚本。
 */
@SpringBootApplication
@EnableScheduling
@EnableDubbo(scanBasePackages = {"com.qoder.payment.marketing", "com.qoder.payment.common"})
public class MarketingApplication {

    public static void main(String[] args) {
        MarketingDatabase.ensureDatabase();
        SpringApplication.run(MarketingApplication.class, args);
    }
}