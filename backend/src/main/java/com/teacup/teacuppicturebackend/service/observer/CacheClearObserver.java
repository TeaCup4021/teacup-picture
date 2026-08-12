package com.teacup.teacuppicturebackend.service.observer;

import com.teacup.teacuppicturebackend.model.event.ClearEvent;

/**
 * 缓存清理观察者接口
 */
public interface CacheClearObserver {
    
    /**
     * 处理缓存清理事件
     * @param event 清理事件
     */
    void handleClearEvent(ClearEvent event);
    
    /**
     * 是否支持处理该事件
     * @param event 清理事件
     * @return 是否支持
     */
    boolean supports(ClearEvent event);
}