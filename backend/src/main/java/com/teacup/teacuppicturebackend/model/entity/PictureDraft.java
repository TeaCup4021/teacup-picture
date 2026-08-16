package com.teacup.teacuppicturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("picture_draft")
public class PictureDraft {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long pictureId;
    private String editorState;
    private Integer schemaVersion;
    private Long revision;
    private Long updatedBy;
    private Date createTime;
    private Date updateTime;
}
