package com.teacup.teacuppicturebackend.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 公开读模型的缓存入口：本地缓存（进程内）加共享缓存（跨实例）。
 *
 * 分层规则刻意不对称：
 *  本地键一律携带版本号。本地缓存无法被其它实例精确清理，带上版本号后，
 *  版本号一变旧键就再也读不到，等于整体失效，且不需要遍历清理。
 *  共享键中详情不带版本号（键有界、可精确删除，还省掉读取时取版本号的一次往返），
 *  列表带版本号（键无上界、无法枚举删除，只能整体切换）。
 *
 * 一致性立场是有界最终一致：本地缓存与共享缓存都设有效期，
 * 任何失效动作失败都不会造成永久陈旧，只会延长陈旧时间。
 */
@Slf4j
@Component
public class PublicReadCache {

    /** 负值哨兵：本地缓存用它表示「资源不存在」，Caffeine 不接受 null 值。 */
    private static final Object ABSENT = new Object();

    /** 共享缓存中的负值标记。正常值一定是 JSON 对象，不会与之冲突。 */
    private static final String NEGATIVE_MARKER = "@absent";

    private final Cache<String, Object> local;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final CacheGeneration generation;
    private final MeterRegistry metrics;
    private final long detailTtlSeconds;
    private final long listTtlSeconds;
    private final long negativeTtlSeconds;

    public PublicReadCache(@Qualifier("publicReadLocalCache") Cache<String, Object> local,
                           StringRedisTemplate redis,
                           ObjectMapper json,
                           CacheGeneration generation,
                           MeterRegistry metrics,
                           @Value("${teacup.cache.shared.detail-ttl-seconds:60}") long detailTtlSeconds,
                           @Value("${teacup.cache.shared.list-ttl-seconds:30}") long listTtlSeconds,
                           @Value("${teacup.cache.shared.negative-ttl-seconds:15}") long negativeTtlSeconds) {
        this.local = local;
        this.redis = redis;
        this.json = json;
        this.generation = generation;
        this.metrics = metrics;
        this.detailTtlSeconds = Math.max(1L, detailTtlSeconds);
        this.listTtlSeconds = Math.max(1L, listTtlSeconds);
        this.negativeTtlSeconds = Math.max(1L, negativeTtlSeconds);
    }

    /**
     * 读图片详情。加载函数返回 null 表示资源不存在或不可公开，该结果会被记为负值缓存，
     * 避免同一个编号被反复穿透到数据库。
     */
    public <T> T detail(long pictureId, Class<T> type, Supplier<T> loader) {
        String key = PublicPictureCacheKeys.localKey(generation.catalog(),
                PublicPictureCacheKeys.DETAIL_CACHE_NAME, "id=" + pictureId);
        Object cached = local.getIfPresent(key);
        if (cached != null) {
            count("local_hit");
        } else {
            // 加载函数在同一进程内同一键只会被执行一次，其余并发线程挂在同一个结果上。
            cached = local.get(key, ignored -> loadDetail(pictureId, type, loader));
        }
        return cached == ABSENT ? null : type.cast(cached);
    }

    /** 读图库列表。加载函数不应返回 null。 */
    public <T> T list(String identity, Class<T> type, Supplier<T> loader) {
        long catalog = generation.catalog();
        String key = PublicPictureCacheKeys.localKey(catalog, PublicPictureCacheKeys.LIST_CACHE_NAME, identity);
        Object cached = local.getIfPresent(key);
        if (cached != null) {
            count("local_hit");
        } else {
            cached = local.get(key, ignored -> loadList(catalog, identity, type, loader));
        }
        return cached == ABSENT ? null : type.cast(cached);
    }

    private <T> Object loadDetail(long pictureId, Class<T> type, Supplier<T> loader) {
        String sharedKey = PublicPictureCacheKeys.detailSharedKey(pictureId);
        String raw = readShared(sharedKey);
        if (NEGATIVE_MARKER.equals(raw)) {
            count("negative");
            return ABSENT;
        }
        T fromShared = deserialize(raw, type);
        if (fromShared != null) {
            count("shared_hit");
            return fromShared;
        }
        T loaded = loader.get();
        if (loaded == null) {
            writeShared(sharedKey, NEGATIVE_MARKER, negativeTtlSeconds);
            count("negative");
            return ABSENT;
        }
        writeShared(sharedKey, serialize(loaded), detailTtlSeconds);
        count("load");
        return loaded;
    }

    private <T> Object loadList(long catalog, String identity, Class<T> type, Supplier<T> loader) {
        String sharedKey = PublicPictureCacheKeys.listSharedKey(catalog, identity);
        T fromShared = deserialize(readShared(sharedKey), type);
        if (fromShared != null) {
            count("shared_hit");
            return fromShared;
        }
        T loaded = loader.get();
        if (loaded == null) {
            count("negative");
            return ABSENT;
        }
        writeShared(sharedKey, serialize(loaded), listTtlSeconds);
        count("load");
        return loaded;
    }

    /** 共享缓存读失败一律按未命中处理，直接回源，不返回陈旧值。 */
    private String readShared(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (RuntimeException unavailable) {
            count("shared_error");
            return null;
        }
    }

    private void writeShared(String key, String value, long ttlSeconds) {
        if (value == null) {
            return;
        }
        try {
            // 随机抖动只用于打散自然过期时间，不是失效手段
            long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, ttlSeconds / 5));
            redis.opsForValue().set(key, value, ttlSeconds + jitter, TimeUnit.SECONDS);
        } catch (RuntimeException unavailable) {
            count("shared_error");
        }
    }

    private String serialize(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception unserializable) {
            log.warn("缓存值序列化失败，该次读取直接回源");
            return null;
        }
    }

    private <T> T deserialize(String raw, Class<T> type) {
        if (raw == null || NEGATIVE_MARKER.equals(raw)) {
            return null;
        }
        try {
            return json.readValue(raw, type);
        } catch (Exception unreadable) {
            // 结构升级或数据损坏时按未命中处理，自然回源覆盖，不需要手工清库
            count("corrupt");
            return null;
        }
    }

    private void count(String result) {
        metrics.counter("teacup.cache.reads", "result", result).increment();
    }
}
