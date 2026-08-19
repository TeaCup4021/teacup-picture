package com.teacup.teacuppicturebackend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.teacup.teacuppicturebackend.exception.BusinessException;
import com.teacup.teacuppicturebackend.exception.ErrorCode;
import com.teacup.teacuppicturebackend.mapper.SpaceMapper;
import com.teacup.teacuppicturebackend.model.entity.Space;
import com.teacup.teacuppicturebackend.model.enums.SpaceLevelEnum;
import com.teacup.teacuppicturebackend.model.enums.SpaceTypeEnum;
import com.teacup.teacuppicturebackend.service.PersonalSpaceService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class PersonalSpaceServiceImpl implements PersonalSpaceService {

    private static final String DEFAULT_SPACE_NAME = "个人空间";

    private final SpaceMapper spaceMapper;

    public PersonalSpaceServiceImpl(SpaceMapper spaceMapper) {
        this.spaceMapper = spaceMapper;
    }

    @Override
    public Space getOrCreatePersonalSpace(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户 ID 无效");
        }

        Space existingSpace = findPersonalSpace(userId);
        if (existingSpace != null) {
            return existingSpace;
        }

        SpaceLevelEnum level = SpaceLevelEnum.COMMON;
        Space personalSpace = new Space()
                .setSpaceName(DEFAULT_SPACE_NAME)
                .setSpaceLevel(level.getValue())
                .setSpaceType(SpaceTypeEnum.PRIVATE.getValue())
                .setMaxSize(level.getMaxSize())
                .setMaxCount(level.getMaxCount())
                .setTotalSize(0L)
                .setTotalCount(0L)
                .setUserId(userId)
                .setOwnerId(userId);

        try {
            if (spaceMapper.insert(personalSpace) != 1) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "创建个人空间失败");
            }
            return personalSpace;
        } catch (DuplicateKeyException exception) {
            Space concurrentlyCreatedSpace = findPersonalSpace(userId);
            if (concurrentlyCreatedSpace != null) {
                return concurrentlyCreatedSpace;
            }
            throw exception;
        }
    }

    private Space findPersonalSpace(Long userId) {
        return spaceMapper.selectOne(new LambdaQueryWrapper<Space>()
                .eq(Space::getUserId, userId)
                .eq(Space::getSpaceType, SpaceTypeEnum.PRIVATE.getValue())
                .eq(Space::getIsDelete, 0)
                .last("LIMIT 1"));
    }
}
