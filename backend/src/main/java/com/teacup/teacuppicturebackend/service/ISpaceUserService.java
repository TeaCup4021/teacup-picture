package com.teacup.teacuppicturebackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.teacup.teacuppicturebackend.model.dto.spaceuser.SpaceUserAddRequest;
import com.teacup.teacuppicturebackend.model.dto.spaceuser.SpaceUserQueryRequest;
import com.teacup.teacuppicturebackend.model.entity.SpaceUser;
import com.baomidou.mybatisplus.extension.service.IService;
import com.teacup.teacuppicturebackend.model.entity.SpaceUser;
import com.teacup.teacuppicturebackend.model.vo.spaceuser.SpaceUserVO;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * <p>
 * 空间用户关联 服务类
 * </p>
 *
 * @author wolves
 * @since 2025-10-15
 */
public interface ISpaceUserService extends IService<SpaceUser> {

    long addSpaceUser(SpaceUserAddRequest spaceUserAddRequest);

    void validSpaceUser(SpaceUser spaceUser, boolean add);

    QueryWrapper<SpaceUser> getQueryWrapper(SpaceUserQueryRequest spaceUserQueryRequest);

    SpaceUserVO getSpaceUserVO(SpaceUser spaceUser, HttpServletRequest request);

    List<SpaceUserVO> getSpaceUserVOList(List<SpaceUser> spaceUserList, HttpServletRequest request);
}
