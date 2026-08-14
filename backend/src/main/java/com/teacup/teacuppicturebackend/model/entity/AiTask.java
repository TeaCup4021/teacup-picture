package com.teacup.teacuppicturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_task")
public class AiTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String idempotencyKey;
    private String taskType;
    private Long modelId;
    private String modelCode;
    private String provider;
    private String providerModel;
    private String prompt;
    private String ratio;
    private String quality;
    private String background;
    private String outputFormat;
    private Integer outputCompression;
    private Long sourcePictureId;
    private Long referencePictureId;
    private String status;
    private String providerTaskId;
    private String providerRequestId;
    private Long resultPictureId;
    private String failureCode;
    private String failureReason;
    private Integer quotaCost;
    private Integer quotaRefunded;
    private Integer quotaSettled;
    private Integer invocationStarted;
    private LocalDateTime createTime;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private LocalDateTime updateTime;
}
