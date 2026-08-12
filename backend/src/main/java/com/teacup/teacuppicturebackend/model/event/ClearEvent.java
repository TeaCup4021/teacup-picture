package com.teacup.teacuppicturebackend.model.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ClearEvent {

    /**
     * 事件类型：DELETE-删除，UPDATE-更新，INSERT-插入
     */
    private String eventType;
    
    /**
     * 实体类型：PICTURE-图片，SPACE-空间等
     */
    private String entityType;
    
    /**
     * 实体ID
     */
    private Long entityId;
    
    /**
     * 相关数据（用于构建缓存键）
     */
    private Object relatedData;
    
    /**
     * 时间戳
     */
    private Long timestamp;
    
    public static ClearEvent of(String eventType, String entityType, Long entityId) {
        return new ClearEvent(eventType, entityType, entityId, null, System.currentTimeMillis());
    }
    
    public static ClearEvent of(String eventType, String entityType, Long entityId, Object relatedData) {
        return new ClearEvent(eventType, entityType, entityId, relatedData, System.currentTimeMillis());
    }
}