package com.ieltsstudio.config;

import com.ieltsstudio.infra.RedisOps;
import com.ieltsstudio.infra.RedisOpsNoopImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisOpsFallbackConfig {

    @Bean
    @ConditionalOnMissingBean(RedisOps.class)
    public RedisOps redisOpsFallback() {
        return new RedisOpsNoopImpl();
    }
}
