package com.teacup.teacuppicturebackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.teacup.teacuppicturebackend.model.entity.PictureUploadPart;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface PictureUploadPartMapper extends BaseMapper<PictureUploadPart> {
    @Select("SELECT * FROM picture_upload_part WHERE sessionId = #{sessionId} ORDER BY partNumber")
    List<PictureUploadPart> selectBySessionId(@Param("sessionId") long sessionId);
}
