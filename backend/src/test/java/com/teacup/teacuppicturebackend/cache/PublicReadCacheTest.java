package com.teacup.teacuppicturebackend.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 覆盖简化方案的四条关键语义：本地命中不触碰共享缓存、版本号切换即整体失效、
 * 详情负值缓存、共享缓存异常时降级回源。
 */
class PublicReadCacheTest {

    private final Map<String, String> store = new HashMap<>();
    private Cache<String, Object> local;
    private CacheGeneration generation;
    private PublicReadCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        store.clear();
        local = Caffeine.newBuilder().maximumSize(100).build();
        generation = mock(CacheGeneration.class);
        when(generation.catalog()).thenReturn(108L);

        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(values.get(anyString())).thenAnswer(invocation -> store.get(invocation.getArgument(0, String.class)));
        doAnswer(invocation -> {
            store.put(invocation.getArgument(0, String.class), invocation.getArgument(1, String.class));
            return null;
        }).when(values).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(values);

        cache = new PublicReadCache(local, redis, new ObjectMapper(), generation,
                new SimpleMeterRegistry(), 60L, 30L, 15L);
    }

    @Test
    void secondReadIsServedByLocalCacheWithoutTouchingSharedCache() {
        AtomicInteger loads = new AtomicInteger();

        String first = cache.detail(123L, String.class, () -> "value" + loads.incrementAndGet());
        String second = cache.detail(123L, String.class, () -> "value" + loads.incrementAndGet());

        assertEquals("value1", first);
        assertEquals("value1", second);
        assertEquals(1, loads.get());
        // 共享缓存里只写过一次，第二次完全由本地缓存命中
        assertEquals(1, store.size());
    }

    @Test
    void versionBumpDoesNotRefreshSharedDetailBecauseDetailKeyHasNoCatalog() {
        AtomicInteger loads = new AtomicInteger();
        String first = cache.detail(123L, String.class, () -> "v" + loads.incrementAndGet());
        assertEquals("v1", first);

        when(generation.catalog()).thenReturn(109L);
        String afterBump = cache.detail(123L, String.class, () -> "v" + loads.incrementAndGet());

        // 详情共享键不含版本号，所以抬版本号只让本地缓存换键，
        // 共享缓存中的那一份仍然命中。这正是公开图片失效必须删除详情键的原因，
        // 也说明两个失效动作各管一层、缺一不可。
        assertEquals("v1", afterBump);
        assertEquals(1, loads.get());
    }

    @Test
    void sharedCacheHitIsBackfilledIntoLocalCache() {
        store.put(PublicPictureCacheKeys.detailSharedKey(123L), "\"fromShared\"");
        AtomicInteger loads = new AtomicInteger();

        String value = cache.detail(123L, String.class, () -> {
            loads.incrementAndGet();
            return "fromLoader";
        });

        assertEquals("fromShared", value);
        assertEquals(0, loads.get());
    }

    @Test
    void missingDetailIsCachedAsNegativeForBothLayers() {
        AtomicInteger loads = new AtomicInteger();
        String first = cache.detail(404L, String.class, () -> {
            loads.incrementAndGet();
            return null;
        });
        String second = cache.detail(404L, String.class, () -> {
            loads.incrementAndGet();
            return null;
        });

        assertNull(first);
        assertNull(second);
        assertEquals(1, loads.get());
        assertEquals("@absent", store.get(PublicPictureCacheKeys.detailSharedKey(404L)));
    }

    @Test
    void invalidPayloadFallsBackToLoaderInsteadOfFailing() {
        store.put(PublicPictureCacheKeys.detailSharedKey(123L), "{ this is not json");
        AtomicInteger loads = new AtomicInteger();

        String value = cache.detail(123L, String.class, () -> "reloaded" + loads.incrementAndGet());

        assertEquals("reloaded1", value);
        assertEquals(1, loads.get());
    }

    @Test
    void listKeyCarriesCatalogSoBumpSwitchesNamespace() {
        AtomicInteger loads = new AtomicInteger();
        cache.list("cursor=first:limit=20", String.class, () -> "page" + loads.incrementAndGet());
        assertEquals(1, store.size());
        assertEquals("page1", store.get(PublicPictureCacheKeys.listSharedKey(108L, "cursor=first:limit=20"))
                .replace("\"", ""));

        when(generation.catalog()).thenReturn(109L);
        String afterBump = cache.list("cursor=first:limit=20", String.class, () -> "page" + loads.incrementAndGet());

        assertEquals("page2", afterBump);
        assertEquals(2, store.size());
    }
}
