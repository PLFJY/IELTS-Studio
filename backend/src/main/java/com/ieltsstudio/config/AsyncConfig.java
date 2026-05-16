package com.ieltsstudio.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 自定义异步线程池配置，替代默认 SimpleAsyncTaskExecutor。
 * 核心线程 4（匹配 DeepSeek API 实际并行能力 3~4），最大线程 8，队列 20。
 * 超出后使用 CallerRunsPolicy，由调用线程同步执行，起到天然限流作用。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("asyncParseExecutor")
    public Executor asyncParseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("parse-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
