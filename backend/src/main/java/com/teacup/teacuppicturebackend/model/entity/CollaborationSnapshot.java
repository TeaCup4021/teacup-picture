package com.teacup.teacuppicturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("collaboration_snapshot")
public class CollaborationSnapshot {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long roomId;
    private Long lastSeq;
    private String yjsState;
    private String editorState;
    private Date createTime;
}
