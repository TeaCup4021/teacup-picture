package com.teacup.teacuppicturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("picture_comment")
public class PictureComment {
    @TableId(type = IdType.AUTO) private Long id;
    private Long pictureId;
    private Long pictureVersionId;
    private Long authorId;
    private Long rootId;
    private Long replyToId;
    private String kind;
    private String body;
    private BigDecimal positionX;
    private BigDecimal positionY;
    private String status;
    private Date resolvedAt;
    private Long resolvedBy;
    private Date createTime;
    private Date updateTime;
}
