package com.qoder.payment.settle;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableDubbo(scanBasePackages = {"com.qoder.payment.settle", "com.qoder.payment.common"})
public class SettleApplication {

    public static void main(String[] args) {
        SpringApplication.run(SettleApplication.class, args);
    }
}