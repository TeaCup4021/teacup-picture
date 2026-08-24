package com.teacup.teacuppicturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("comment_mention")
public class CommentMention {
    private Long commentId;
    private Long userId;
    private Date createTime;
}
