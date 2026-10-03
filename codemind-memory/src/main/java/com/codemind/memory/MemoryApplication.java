package com.codemind.memory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@EnableDiscoveryClient
@SpringBootApplication
public class MemoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(MemoryApplication.class, args);
    }
}
