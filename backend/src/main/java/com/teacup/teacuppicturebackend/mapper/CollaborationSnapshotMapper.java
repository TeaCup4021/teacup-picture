package com.teacup.teacuppicturebackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.teacup.teacuppicturebackend.model.entity.CollaborationSnapshot;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface CollaborationSnapshotMapper extends BaseMapper<CollaborationSnapshot> {
    @Select("SELECT * FROM collaboration_snapshot WHERE roomId = #{roomId} ORDER BY lastSeq DESC LIMIT 1")
    CollaborationSnapshot selectLatest(@Param("roomId") long roomId);
}
