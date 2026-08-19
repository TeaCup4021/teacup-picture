package com.teacup.teacuppicturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("space_invitation")
public class SpaceInvitation {
    @TableId(type = IdType.AUTO) private Long id;
    private Long spaceId;
    private Long inviterId;
    private Long inviteeId;
    private String spaceRole;
    private String status;
    private Date expiresAt;
    private Date respondedAt;
    private Date createTime;
    private Date updateTime;
}
