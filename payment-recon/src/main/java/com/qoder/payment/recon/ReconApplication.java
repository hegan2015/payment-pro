package com.qoder.payment.recon;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableDubbo(scanBasePackages = {"com.qoder.payment.recon", "com.qoder.payment.common"})
public class ReconApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReconApplication.class, args);
    }
}