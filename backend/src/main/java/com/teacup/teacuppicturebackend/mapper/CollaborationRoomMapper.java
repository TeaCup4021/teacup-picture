package com.teacup.teacuppicturebackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.teacup.teacuppicturebackend.model.entity.CollaborationRoom;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface CollaborationRoomMapper extends BaseMapper<CollaborationRoom> {
    @Select("SELECT * FROM collaboration_room WHERE pictureId = #{pictureId} FOR UPDATE")
    CollaborationRoom lockByPictureId(@Param("pictureId") long pictureId);
}
