package com.teacup.teacuppicturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("picture_version")
public class PictureVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long pictureId;
    private Integer versionNumber;
    private String name;
    private String note;
    private String sourceType;
    private Long parentVersionId;
    private String editorState;
    private Integer schemaVersion;
    private String assetObjectKey;
    private String thumbnailObjectKey;
    private String contentType;
    private Integer width;
    private Integer height;
    private Long size;
    private Long creatorId;
    private Date createTime;
}
