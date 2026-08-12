package com.teacup.teacuppicturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("publish_request")
public class PublishRequest {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long pictureId;
    private Long requesterId;
    private String status;
    private Long reviewerId;
    private String decisionReason;
    private LocalDateTime createTime;
    private LocalDateTime reviewTime;
}
