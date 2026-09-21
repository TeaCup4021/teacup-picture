package com.teacup.teacuppicturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AI 配额流转审计记录。
 * action 取值：settled（结算）| released（归还预占）| repaired（对账修正）。
 */
@Data
@TableName("ai_task_quota_audit")
public class AiTaskQuotaAudit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Long userId;
    private String taskType;
    private Integer quotaCost;
    private LocalDate usageDate;
    private String action;
    private String reason;
    private LocalDateTime createTime;
}
