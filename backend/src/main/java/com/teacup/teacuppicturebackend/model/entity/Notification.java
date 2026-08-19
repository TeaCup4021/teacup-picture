package com.teacup.teacuppicturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("notification")
public class Notification {
    @TableId(type = IdType.AUTO) private Long id;
    private Long userId;
    private String type;
    private Long actorId;
    private String resourceType;
    private Long resourceId;
    private String payload;
    private Date readAt;
    private Date createTime;
}
