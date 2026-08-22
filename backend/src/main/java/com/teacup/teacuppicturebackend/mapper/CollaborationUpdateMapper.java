package com.teacup.teacuppicturebackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.teacup.teacuppicturebackend.model.entity.CollaborationUpdate;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface CollaborationUpdateMapper extends BaseMapper<CollaborationUpdate> {
    @Select("SELECT * FROM collaboration_update WHERE roomId = #{roomId} AND serverSeq > #{afterSeq} ORDER BY serverSeq ASC LIMIT 1000")
    List<CollaborationUpdate> selectAfter(@Param("roomId") long roomId, @Param("afterSeq") long afterSeq);

    @Select("SELECT * FROM collaboration_update WHERE roomId = #{roomId} AND operationId = #{operationId} LIMIT 1")
    CollaborationUpdate selectByOperation(@Param("roomId") long roomId, @Param("operationId") String operationId);
}
