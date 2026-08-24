package com.teacup.teacuppicturebackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.teacup.teacuppicturebackend.model.entity.CommentMention;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CommentMentionMapper extends BaseMapper<CommentMention> {}
