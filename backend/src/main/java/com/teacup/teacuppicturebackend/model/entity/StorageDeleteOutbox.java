package com.teacup.teacuppicturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("storage_delete_outbox")
public class StorageDeleteOutbox {
    @TableId(type = IdType.AUTO) private Long id;
    private Long pictureId;
    private String objectKey;
    private String thumbnailObjectKey;
    private String status;
    private Integer retryCount;
    private String lastError;
    private Date nextAttemptAt;
    private Date createTime;
    private Date updateTime;
}
