package com.teacup.teacuppicturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_model")
public class AiModel {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String displayName;
    private String provider;
    private String providerModel;
    private String capabilities;
    private String supportedRatios;
    private String supportedQualities;
    private String supportedBackgrounds;
    private String supportedOutputFormats;
    private Integer supportsOutputCompression;
    private Integer supportsReference;
    private Integer quotaCost;
    private Integer enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
