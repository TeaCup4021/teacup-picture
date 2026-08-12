package com.teacup.teacuppicturebackend.service.impl;

import com.teacup.teacuppicturebackend.exception.BusinessException;
import com.teacup.teacuppicturebackend.mapper.SpaceMapper;
import com.teacup.teacuppicturebackend.model.entity.Space;
import com.teacup.teacuppicturebackend.model.enums.SpaceLevelEnum;
import com.teacup.teacuppicturebackend.model.enums.SpaceTypeEnum;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonalSpaceServiceImplTest {

    @Test
    void returnsExistingPersonalSpace() {
        SpaceMapper spaceMapper = mock(SpaceMapper.class);
        Space existingSpace = new Space().setId(11L).setUserId(7L);
        when(spaceMapper.selectOne(any())).thenReturn(existingSpace);

        Space result = new PersonalSpaceServiceImpl(spaceMapper).getOrCreatePersonalSpace(7L);

        assertSame(existingSpace, result);
        verify(spaceMapper, never()).insert(any(Space.class));
    }

    @Test
    void createsCommonPersonalSpaceWhenMissing() {
        SpaceMapper spaceMapper = mock(SpaceMapper.class);
        when(spaceMapper.selectOne(any())).thenReturn(null);
        when(spaceMapper.insert(any(Space.class))).thenAnswer(invocation -> {
            Space space = invocation.getArgument(0);
            space.setId(12L);
            return 1;
        });

        Space result = new PersonalSpaceServiceImpl(spaceMapper).getOrCreatePersonalSpace(7L);

        assertEquals(12L, result.getId());
        assertEquals(7L, result.getUserId());
        assertEquals(SpaceTypeEnum.PRIVATE.getValue(), result.getSpaceType());
        assertEquals(SpaceLevelEnum.COMMON.getValue(), result.getSpaceLevel());
        assertEquals(SpaceLevelEnum.COMMON.getMaxSize(), result.getMaxSize());
        assertEquals(SpaceLevelEnum.COMMON.getMaxCount(), result.getMaxCount());
    }

    @Test
    void returnsSpaceCreatedByConcurrentRequest() {
        SpaceMapper spaceMapper = mock(SpaceMapper.class);
        Space concurrentlyCreatedSpace = new Space().setId(13L).setUserId(7L);
        when(spaceMapper.selectOne(any())).thenReturn(null, concurrentlyCreatedSpace);
        when(spaceMapper.insert(any(Space.class))).thenThrow(new DuplicateKeyException("duplicate"));

        Space result = new PersonalSpaceServiceImpl(spaceMapper).getOrCreatePersonalSpace(7L);

        assertSame(concurrentlyCreatedSpace, result);
    }

    @Test
    void rejectsInvalidUserId() {
        SpaceMapper spaceMapper = mock(SpaceMapper.class);
        PersonalSpaceServiceImpl service = new PersonalSpaceServiceImpl(spaceMapper);

        assertThrows(BusinessException.class, () -> service.getOrCreatePersonalSpace(null));
        assertThrows(BusinessException.class, () -> service.getOrCreatePersonalSpace(0L));
    }
}
