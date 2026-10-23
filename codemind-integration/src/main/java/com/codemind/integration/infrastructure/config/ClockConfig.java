package com.codemind.integration.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 全模块共用的时钟（M9 起）。单独一个配置类，免得各功能配置各定义一份撞 bean 名。测试用 {@code @Primary} 覆盖。 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
