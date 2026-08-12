package com.teacup.teacuppicturebackend.mapper;

import com.teacup.teacuppicturebackend.model.entity.SpaceUser;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

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
}
