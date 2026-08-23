package com.teacup.teacuppicturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("collaboration_room")
public class CollaborationRoom {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long pictureId;
    private Long baseVersionId;
    private String baseEditorState;
    private String roomEpoch;
    private Long lastSeq;
    private String status;
    private Date createTime;
    private Date updateTime;
}
