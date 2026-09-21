package com.teacup.teacuppicturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_task_outbox")
public class AiTaskOutbox {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private Long taskId;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextAttemptAt;
    private String lockOwner;
    private LocalDateTime lockUntil;
    private LocalDateTime publishedAt;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
