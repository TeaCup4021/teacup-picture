package com.teacup.teacuppicturebackend.mapper;

import com.teacup.teacuppicturebackend.model.entity.Space;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 * 空间 Mapper 接口
 * </p>
 *
 * @author wolves
 * @since 2025-09-27
 */
public interface SpaceMapper extends BaseMapper<Space> {
    @Select("SELECT id FROM space WHERE id = #{spaceId} AND isDelete = 0 FOR UPDATE")
    Long lockActiveById(@Param("spaceId") long spaceId);

    @Delete("DELETE FROM space WHERE id = #{spaceId}")
    int purgeById(@Param("spaceId") long spaceId);

}
