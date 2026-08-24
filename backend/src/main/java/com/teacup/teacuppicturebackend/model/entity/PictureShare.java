package com.teacup.teacuppicturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("picture_share")
public class PictureShare {
    @TableId(type = IdType.AUTO) private Long id;
    private Long pictureId;
    private Long creatorId;
    private String publicId;
    private String secretHash;
    private String passwordHash;
    private Date expiresAt;
    private Date revokedAt;
    private Date createTime;
    private Date updateTime;
}
