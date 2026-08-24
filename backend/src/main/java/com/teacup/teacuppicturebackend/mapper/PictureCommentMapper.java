package com.teacup.teacuppicturebackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.teacup.teacuppicturebackend.model.entity.PictureComment;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PictureCommentMapper extends BaseMapper<PictureComment> {}
