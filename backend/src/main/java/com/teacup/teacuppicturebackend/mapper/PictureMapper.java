package com.teacup.teacuppicturebackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.teacup.teacuppicturebackend.model.entity.Picture;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;

/**
* @author wolves
* @description 针对表【picture(图片)】的数据库操作Mapper
* @createDate 2025-09-19 17:57:30
* @Entity com.teacup.teacuppicturebackend.model.entity.Picture
*/
public interface PictureMapper extends BaseMapper<Picture> {

    @Select("SELECT id FROM picture WHERE id = #{pictureId} AND isDelete = 0 FOR UPDATE")
    Long lockPictureForUpdate(@Param("pictureId") long pictureId);

    @Delete("DELETE FROM picture WHERE spaceId = #{spaceId}")
    int purgeBySpaceId(@Param("spaceId") long spaceId);
}
