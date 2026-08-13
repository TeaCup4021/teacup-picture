package com.teacup.teacuppicturebackend.api.v1;

import com.teacup.teacuppicturebackend.api.v1.model.M1Dtos;
import com.teacup.teacuppicturebackend.mapper.PictureMapper;
import com.teacup.teacuppicturebackend.mapper.PublishRequestMapper;
import com.teacup.teacuppicturebackend.mapper.UserMapper;
import com.teacup.teacuppicturebackend.model.entity.Picture;
import com.teacup.teacuppicturebackend.model.entity.PublishRequest;
import com.teacup.teacuppicturebackend.model.entity.Space;
import com.teacup.teacuppicturebackend.model.entity.User;
import com.teacup.teacuppicturebackend.service.PersonalSpaceService;
import com.teacup.teacuppicturebackend.service.SpaceService;
import com.teacup.teacuppicturebackend.service.UserService;
import com.teacup.teacuppicturebackend.storage.PictureAssetService;
import com.teacup.teacuppicturebackend.storage.PictureStorage;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class M1ServiceTest {
    UserService userService = mock(UserService.class);
    PersonalSpaceService personalSpaceService = mock(PersonalSpaceService.class);
    SpaceService spaceService = mock(SpaceService.class);
    PictureMapper pictureMapper = mock(PictureMapper.class);
    PublishRequestMapper publishRequestMapper = mock(PublishRequestMapper.class);
    UserMapper userMapper = mock(UserMapper.class);
    PictureStorage storage = mock(PictureStorage.class);
    PictureAssetService assets = mock(PictureAssetService.class);
    M1Service service;
    User user;
    Space space;

    @BeforeEach
    void setUp() {
        service = new M1Service(userService, personalSpaceService, spaceService, pictureMapper, publishRequestMapper, userMapper, storage, assets);
        user = new User(); user.setId(11L); user.setUserName("Tester"); user.setUserRole("user");
        space = new Space().setId(21L).setUserId(11L).setMaxCount(100L).setMaxSize(1000000L).setTotalCount(0L).setTotalSize(0L);
        when(personalSpaceService.getOrCreatePersonalSpace(11L)).thenReturn(space);
        when(pictureMapper.insert(any(Picture.class))).thenAnswer(invocation -> { ((Picture) invocation.getArgument(0)).setId(31L); return 1; });
        when(assets.privateUrl(any(Long.class), any())).thenAnswer(invocation -> "http://asset/" + invocation.getArgument(0));
        @SuppressWarnings("unchecked")
        LambdaUpdateChainWrapper<Space> update = mock(LambdaUpdateChainWrapper.class);
        when(spaceService.lambdaUpdate()).thenReturn(update);
        when(update.eq(any(), any())).thenReturn(update);
        when(update.setSql(any())).thenReturn(update);
        when(update.update()).thenReturn(true);
    }

    @Test
    void uploadDefaultsToPersonalSpaceAndPrivateState() {
        when(storage.store(any(), any(Long.class))).thenReturn(new PictureStorage.StoredPicture("spaces/21/pictures/abc/original.png", 100, 10, 20, "png", "image/png", "checksum"));
        M1Dtos.PictureDetail result = service.upload(user, new MockMultipartFile("file", "a.png", "image/png", new byte[]{1}), null, "A", null, null, List.of());
        assertEquals("21", result.spaceId());
        assertEquals("private", result.visibility());
        assertEquals("not_requested", result.publishStatus());
        verify(personalSpaceService).getOrCreatePersonalSpace(11L);
    }

    @Test
    void pendingPictureCannotBeSubmittedTwice() {
        Picture picture = new Picture(); picture.setId(31L); picture.setUserId(11L); picture.setPublishStatus("pending");
        when(pictureMapper.selectById(31L)).thenReturn(picture);
        V1Exception exception = assertThrows(V1Exception.class, () -> service.requestPublication(user, 31L));
        assertEquals(40901, exception.getCode());
    }

    @Test
    void nonAdminCannotReview() {
        PublishRequest request = new PublishRequest(); request.setId(41L); request.setPictureId(31L); request.setStatus("pending");
        when(publishRequestMapper.selectById(41L)).thenReturn(request);
        when(userService.isAdmin(user)).thenReturn(false);
        assertEquals(40101, assertThrows(V1Exception.class, () -> service.decide(user, 41L, true, null)).getCode());
    }
}
