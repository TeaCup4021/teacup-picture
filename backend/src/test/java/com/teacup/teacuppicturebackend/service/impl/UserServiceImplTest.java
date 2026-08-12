package com.teacup.teacuppicturebackend.service.impl;

import com.teacup.teacuppicturebackend.exception.BusinessException;
import com.teacup.teacuppicturebackend.mapper.UserMapper;
import com.teacup.teacuppicturebackend.model.dto.user.UserRegisterRequest;
import com.teacup.teacuppicturebackend.model.entity.User;
import com.teacup.teacuppicturebackend.service.PersonalSpaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceImplTest {

    private UserMapper userMapper;
    private PersonalSpaceService personalSpaceService;
    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        personalSpaceService = mock(PersonalSpaceService.class);
        userService = new UserServiceImpl(personalSpaceService);
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
    }

    @Test
    void registerCreatesUserAndPersonalSpace() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(42L);
            return 1;
        });

        long userId = userService.userRegister(registerRequest());

        assertEquals(42L, userId);
        verify(personalSpaceService).getOrCreatePersonalSpace(42L);
    }

    @Test
    void registerPropagatesPersonalSpaceFailureForTransactionRollback() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(42L);
            return 1;
        });
        doThrow(new BusinessException(50001, "创建个人空间失败"))
                .when(personalSpaceService).getOrCreatePersonalSpace(42L);

        assertThrows(BusinessException.class, () -> userService.userRegister(registerRequest()));
    }

    @Test
    void registerRejectsPasswordShorterThanContract() {
        UserRegisterRequest request = registerRequest();
        request.setUserPassword("1234567");
        request.setCheckPassword("1234567");

        assertThrows(BusinessException.class, () -> userService.userRegister(request));
    }

    private UserRegisterRequest registerRequest() {
        UserRegisterRequest request = new UserRegisterRequest();
        request.setUserAccount("teacup-user");
        request.setUserPassword("password123");
        request.setCheckPassword("password123");
        return request;
    }
}
