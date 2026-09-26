package com.teacup.teacuppicturebackend.service.observer.impl;

import com.teacup.teacuppicturebackend.cache.PublicPictureInvalidation;
import com.teacup.teacuppicturebackend.model.event.ClearEvent;
import com.teacup.teacuppicturebackend.service.observer.CacheClearObserver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Compatibility adapter for legacy picture writes. The durable outbox owns invalidation;
 * this class deliberately performs no Redis pattern iteration or direct cache deletion.
 */
@Slf4j
@Component
public class PictureCacheClearObserver implements CacheClearObserver {
    private static final String ENTITY_TYPE = "PICTURE";
    private final PublicPictureInvalidation pictureInvalidation;

    public PictureCacheClearObserver(PublicPictureInvalidation pictureInvalidation) {
        this.pictureInvalidation = pictureInvalidation;
    }
    
    @Override
    public void handleClearEvent(ClearEvent event) {
        try {
            Long pictureId = event.getEntityId();
            if (pictureId == null) {
                log.warn("图片ID为空，无法清理缓存");
                return;
            }
            
            pictureInvalidation.pictureChanged(pictureId);
            log.debug("图片缓存失效事件已入队，pictureId: {}", pictureId);
            
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

}
