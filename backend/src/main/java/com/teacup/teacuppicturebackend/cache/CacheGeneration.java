package com.teacup.teacuppicturebackend.cache;

import javax.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 共享版本号。它不保存业务数据，只提供一个命名空间前缀：版本号变化时，所有键自动改算新键，
 * 旧键不再被访问，等同于整体失效，且不需要遍历或枚举任何键。
 *
 * 多实例之间不做事件推送，各实例独立按固定间隔读取同一个计数键：
 * 写入方只需递增，读取方各自跟进，扩容与缩容都不需要任何协调。
 *
 * 版本号本身不保证一致性，只保证失效能够生效；它同样依赖缓存有效期兜底，
 * 因此读取失败时沿用上一次的值，绝不退回 0。
 */
@Slf4j
@Component
public class CacheGeneration {

    private final StringRedisTemplate redis;

    /** 当前生效的版本号。读取路径只用这一个字段，不产生网络调用。 */
    private volatile long catalog = 0L;

    public CacheGeneration(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 启动时先读一次，避免应用刚起来时用 0 号命名空间白跑一轮回源。 */
    @PostConstruct
    void syncOnStartup() {
        sync();
    }

    @Scheduled(initialDelayString = "${teacup.cache.generation.sync-millis:500}",
            fixedDelayString = "${teacup.cache.generation.sync-millis:500}")
    public void sync() {
        String raw;
        try {
            raw = redis.opsForValue().get(PublicPictureCacheKeys.CATALOG_GENERATION_KEY);
        } catch (RuntimeException unavailable) {
            // 共享缓存抖动时沿用上一次的值。绝不退回 0：那会切回一个早已废弃的命名空间，
            // 而那个命名空间下的旧键可能仍然存在，于是读到极旧的数据。
            return;
        }
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            if (parsed >= 0) {
                catalog = parsed;
            }
        } catch (NumberFormatException ignored) {
            // 计数键被外部污染，保持现有值，等下一轮同步
        }
    }

    /** 当前命名空间前缀。纯内存读取。 */
    public long catalog() {
        return catalog;
    }

    /**
     * 递增版本号，必须由业务写事务提交之后调用。
     * 递增是「至少一次」语义：并发递增或重复递增只会让版本号多跳几位，不会产生错误，
     * 因此不需要恰好一次投递，也就不需要发件箱。
     */
    public void bumpCatalog() {
        try {
            Long next = redis.opsForValue().increment(PublicPictureCacheKeys.CATALOG_GENERATION_KEY);
            if (next != null && next > catalog) {
                catalog = next;
            }
        } catch (RuntimeException unavailable) {
            log.warn("共享缓存版本号递增失败，列表与本地缓存将依靠自身有效期收敛");
        }
    }
}
