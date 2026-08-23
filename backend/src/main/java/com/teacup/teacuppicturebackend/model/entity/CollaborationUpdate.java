package com.teacup.teacuppicturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("collaboration_update")
public class CollaborationUpdate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long roomId;
    private String operationId;
    private Long serverSeq;
    private Long actorId;
    private String gestureId;
    private String kind;
    private String targetId;
    private String lockToken;
    private String changedFields;
    private String phase;
    private String yjsUpdate;
    private Date createTime;
}
