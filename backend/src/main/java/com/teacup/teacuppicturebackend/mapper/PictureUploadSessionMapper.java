package com.teacup.teacuppicturebackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.teacup.teacuppicturebackend.model.entity.PictureUploadSession;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface PictureUploadSessionMapper extends BaseMapper<PictureUploadSession> {
    @Select("SELECT * FROM picture_upload_session WHERE id = #{id} FOR UPDATE")
    PictureUploadSession lockById(@Param("id") long id);

    @Select("SELECT * FROM picture_upload_session WHERE status = 'active' AND expiresAt < NOW()")
    List<PictureUploadSession> selectExpiredActive();
}
