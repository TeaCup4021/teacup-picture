package com.teacup.teacuppicturebackend.model.vo.cache;

import lombok.Data;

import java.io.Serializable;

/**
 * 简化用户缓存信息（只保留展示需要的字段）
 */
@Data
public class SimpleUserCacheVO implements Serializable {
    
    private Long id;
    
    private String userName;
    
    private String userAvatar;
    
    private static final long serialVersionUID = 1L;
}
