package com.teacup.teacuppicturebackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.teacup.teacuppicturebackend.model.entity.PictureVersion;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface PictureVersionMapper extends BaseMapper<PictureVersion> {

    @Select("SELECT COALESCE(MAX(versionNumber), 0) FROM picture_version WHERE pictureId = #{pictureId}")
    int selectMaxVersionNumber(@Param("pictureId") long pictureId);
}
