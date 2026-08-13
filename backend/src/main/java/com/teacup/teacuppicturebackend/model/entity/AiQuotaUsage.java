package com.teacup.teacuppicturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("ai_quota_usage")
public class AiQuotaUsage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private LocalDate usageDate;
    private String taskType;
    private Integer usedCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
