package com.teacup.teacuppicturebackend.model.vo.cache;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 图片缓存专用 VO（精简版，用于 Redis 缓存）
 * 去除不必要的字段，减少 JSON 大小
 */
@Data
public class PictureCacheVO implements Serializable {
    
    private Long id;
    
    private String url;
    
    private String thumbnailUrl;
    
    private String name;
    
    private String category;
    
    private Long picSize;
    
    private Integer picWidth;
    
    private Integer picHeight;
    
    private String picFormat;
    
    private String picColor;
    
    private List<String> tags;
    
    /**
     * 简化用户信息（只保留必要字段）
     */
    private SimpleUserCacheVO user;
    
    private Date createTime;
    
    private static final long serialVersionUID = 1L;
}
