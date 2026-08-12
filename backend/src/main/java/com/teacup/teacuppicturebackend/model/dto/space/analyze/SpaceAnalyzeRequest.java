package com.teacup.teacuppicturebackend.model.dto.space.analyze;

import lombok.Data;

import java.io.Serializable;

/**
 * 图片分析请求封装类
 */
@Data
public class SpaceAnalyzeRequest implements Serializable {

    
    private Long spaceId;

    
    private boolean queryPublic;

    
    private boolean queryAll;

    private static final long serialVersionUID = 1L;
}
