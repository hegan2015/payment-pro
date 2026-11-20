package com.qoder.payment.cashier;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableDubbo(scanBasePackages = {"com.qoder.payment.cashier", "com.qoder.payment.common"})
@MapperScan("com.qoder.payment.cashier.mapper")
public class CashierApplication {

    public static void main(String[] args) {
        SpringApplication.run(CashierApplication.class, args);
    }
}