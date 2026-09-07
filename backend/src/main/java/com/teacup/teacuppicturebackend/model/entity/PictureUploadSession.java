package com.teacup.teacuppicturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("picture_upload_session")
public class PictureUploadSession {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long spaceId;
    private String storagePrefix;
    private String fileName;
    private String contentType;
    private String name;
    private String introduction;
    private String category;
    private String tags;
    private Long totalSize;
    private Integer chunkSize;
    private Integer totalParts;
    private String fileChecksum;
    private String status;
    private LocalDateTime expiresAt;
    private Long completedPictureId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
