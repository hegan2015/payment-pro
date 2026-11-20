package com.qoder.payment.payment.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * S-7 大额复核配置（I-2 Apollo 未落地前的 @ConfigurationProperties + 环境变量载体）。
 * local/docker 双份 yml 同构：payment.review.enabled / payment.review.threshold。
 */
@Data
@Component
@ConfigurationProperties(prefix = "payment.review")
public class ReviewProperties {

    /** 复核闸门总开关（默认开）。关闭仅影响 /api 控制台闸门，OpenAPI 路径本就不经过闸门。 */
    private boolean enabled = true;

    /** 大额阈值（分），默认 1000000=1 万元；≥阈值触发双人复核。 */
    private long threshold = 1_000_000L;
}