package com.teacup.teacuppicturebackend.service.observer.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.teacup.teacuppicturebackend.model.entity.Picture;
import com.teacup.teacuppicturebackend.model.event.ClearEvent;
import com.teacup.teacuppicturebackend.service.observer.CacheClearObserver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.HashSet;
import java.util.Set;

/**
 * 图片缓存清理观察者
 */
@Slf4j
@Component
public class PictureCacheClearObserver implements CacheClearObserver {
    
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    
    @Resource(name = "pictureLocalCache")
    private Cache<String, String> pictureLocalCache;
    
    private static final String ENTITY_TYPE = "PICTURE";
    private static final String CACHE_KEY_PREFIX = "teacuppicture:";
    
    @Override
    public void handleClearEvent(ClearEvent event) {
        try {
            Long pictureId = event.getEntityId();
            if (pictureId == null) {
                log.warn("图片ID为空，无法清理缓存");
                return;
            }
            
            // 清理具体图片缓存
            clearSpecificPictureCache(pictureId);
            
            // 清理相关分页缓存
            clearRelatedPageCache();
            
            // 清理搜索相关缓存
            clearSearchCache();
            
            log.info("图片缓存清理完成，pictureId: {}", pictureId);
            
        } catch (Exception e) {
            log.error("清理图片缓存失败，pictureId: {}", event.getEntityId(), e);
            // 缓存清理失败不影响主业务流程
        }
    }
    
    @Override
    public boolean supports(ClearEvent event) {
        return ENTITY_TYPE.equals(event.getEntityType()) &&
               ("DELETE".equals(event.getEventType()) || "UPDATE".equals(event.getEventType()));
    }

    private Set<String> scanKeys(String pattern) {
        return stringRedisTemplate.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> keySet = new HashSet<>();
            try (Cursor<byte[]> cursor = connection.scan(
                    ScanOptions.scanOptions().match(pattern).count(200).build())) {
                while (cursor.hasNext()) {
                    keySet.add(new String(cursor.next()));
                }
            } catch (Exception e) {
                log.warn("SCAN keys异常, pattern: {}", pattern, e);
            }
            return keySet;
        });
    }
    
    /**
     * 清理具体图片缓存
     */
    private void clearSpecificPictureCache(Long pictureId) {
        String pictureKey = CACHE_KEY_PREFIX + "picture:" + pictureId;
        stringRedisTemplate.delete(pictureKey);
        pictureLocalCache.invalidate(pictureKey);
        log.debug("已清理图片缓存: {}", pictureKey);
    }
    
    /**
     * 清理相关分页缓存
     * 注意：由于分页缓存键是基于查询条件MD5生成的，使用通配符*匹配所有分页缓存
     */
    private void clearRelatedPageCache() {
        try {
            // 匹配所有分页查询缓存
            Set<String> keys = scanKeys(CACHE_KEY_PREFIX + "listPictureVOByPage:*");
            if (keys != null && !keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
                // 清理本地缓存中对应的分页数据
                keys.forEach(pictureLocalCache::invalidate);
                log.debug("已清理分页缓存，数量: {}", keys.size());
            }
        } catch (Exception e) {
            log.warn("清理分页缓存时出现异常", e);
        }
    }
    
    /**
     * 清理搜索相关缓存
     */
    private void clearSearchCache() {
        try {
            // 清理颜色搜索缓存
            Set<String> colorKeys = scanKeys(CACHE_KEY_PREFIX + "search:color:*");
            if (colorKeys != null && !colorKeys.isEmpty()) {
                stringRedisTemplate.delete(colorKeys);
                colorKeys.forEach(pictureLocalCache::invalidate);
            }
            
            // 清理标签搜索缓存
            Set<String> tagKeys = scanKeys(CACHE_KEY_PREFIX + "search:tag:*");
            if (tagKeys != null && !tagKeys.isEmpty()) {
                stringRedisTemplate.delete(tagKeys);
                tagKeys.forEach(pictureLocalCache::invalidate);
            }
            
            log.debug("已清理搜索相关缓存");
        } catch (Exception e) {
            log.warn("清理搜索缓存时出现异常", e);
        }
    }
    
    /**
     * 精准清理分页缓存
     * 基于被删除图片的属性，重新构建可能受影响的查询条件
     */
    private void clearPrecisePageCache(Picture picture) {
        try {
            // TODO: 这里可以根据图片属性构建可能的查询条件
            // 例如：根据spaceId、userId、category等构建查询请求
            // 然后重新生成对应的缓存键进行精准删除
            
            log.debug("精准缓存清理待实现，图片信息: spaceId={}, userId={}, category={}", 
                     picture.getSpaceId(), picture.getUserId(), picture.getCategory());
        } catch (Exception e) {
            log.warn("精准清理分页缓存时出现异常", e);
        }
    }
}
