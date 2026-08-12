package com.teacup.teacuppicturebackend.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    @Bean("pictureLocalCache")
    public Cache<String, String> pictureLocalCache() {
        return Caffeine.newBuilder()
                .maximumSize(1000)  // 最大缓存1000个元素
                .expireAfterWrite(30, TimeUnit.MINUTES)  // 写入后30分钟过期
                .build();
    }
}