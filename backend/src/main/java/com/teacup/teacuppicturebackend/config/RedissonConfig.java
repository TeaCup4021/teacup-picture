package com.teacup.teacuppicturebackend.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisProperties properties) {
        Config config = new Config();
        String address = "redis://" + properties.getHost() + ":" + properties.getPort();
        var server = config.useSingleServer()
                .setAddress(address)
                .setDatabase(properties.getDatabase());
        if (properties.getPassword() != null && !properties.getPassword().isBlank()) {
            server.setPassword(properties.getPassword());
        }
        return Redisson.create(config);
    }
}
