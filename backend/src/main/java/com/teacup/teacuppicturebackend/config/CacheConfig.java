package com.teacup.teacuppicturebackend.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 公开读模型的本地缓存配置。
 *
 * 有效期只作为兜底：正常情况下版本号切换已经让旧键不再被任何请求访问，
 * 残留的旧键依靠有效期与容量上限回收。
 *
 * 不设权重计算器——缓存值都是几百字节量级的图片元数据，用条目数上限足以控制内存，
 * 按对象大小计权反而要在每次写入时多跑一次序列化。
 */
@Configuration
public class CacheConfig {

    @Bean("publicReadLocalCache")
    public Cache<String, Object> publicReadLocalCache(
            @Value("${teacup.cache.local.max-size:20000}") long maxSize,
            @Value("${teacup.cache.local.ttl-seconds:60}") long ttlSeconds) {
        return Caffeine.newBuilder()
                .maximumSize(Math.max(1L, maxSize))
                .expireAfterWrite(Math.max(1L, ttlSeconds), TimeUnit.SECONDS)
                .recordStats()
                .build();
    }

}
