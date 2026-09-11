package com.chengming.kb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EnterpriseAiKbApplication {
    public static void main(String[] args) {
        SpringApplication.run(EnterpriseAiKbApplication.class, args);
    }
}
