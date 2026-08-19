package com.teacup.teacuppicturebackend.mapper;

import com.teacup.teacuppicturebackend.model.entity.SpaceUser;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 * 空间用户关联 Mapper 接口
 * </p>
 *
 * @author wolves
 * @since 2025-10-15
 */
public interface SpaceUserMapper extends BaseMapper<SpaceUser> {

    Boolean save(SpaceUser spaceUser);

    @Delete("DELETE FROM space_user WHERE spaceId = #{spaceId}")
    int deleteBySpaceId(@Param("spaceId") long spaceId);
}
