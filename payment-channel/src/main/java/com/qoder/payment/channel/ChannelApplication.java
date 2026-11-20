package com.qoder.payment.channel;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableDubbo(scanBasePackages = {"com.qoder.payment.channel", "com.qoder.payment.common"})
@EnableScheduling
public class ChannelApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChannelApplication.class, args);
    }
}