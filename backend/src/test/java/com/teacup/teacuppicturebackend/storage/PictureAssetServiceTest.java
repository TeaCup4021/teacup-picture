package com.teacup.teacuppicturebackend.storage;

import com.teacup.teacuppicturebackend.api.v1.V1Exception;
import com.teacup.teacuppicturebackend.config.PictureStorageConfig;
import com.teacup.teacuppicturebackend.mapper.PictureMapper;
import com.teacup.teacuppicturebackend.model.entity.Picture;
import com.teacup.teacuppicturebackend.model.entity.User;
import com.teacup.teacuppicturebackend.service.UserService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PictureAssetServiceTest {
    @Test
    void doesNotFallbackToLegacyLocalFileWhenObjectKeyIsMissing() {
        PictureMapper mapper = mock(PictureMapper.class);
        Picture picture = new Picture();
        picture.setId(31L); picture.setUserId(7L); picture.setIsDelete(0);
        picture.setUrl("http://127.0.0.1:8123/api/v1/public/assets/legacy.jpeg");
        when(mapper.selectById(31L)).thenReturn(picture);
        PictureStorageConfig config = new PictureStorageConfig();
        config.setPublicBaseUrl("http://127.0.0.1:8123/api/v1");
        PictureAssetService service = new PictureAssetService(mapper, mock(UserService.class), mock(PictureStorage.class), config);
        User owner = new User(); owner.setId(7L);

        V1Exception error = assertThrows(V1Exception.class, () -> service.loadPrivate(owner, 31L, "original"));

        assertEquals(404, error.getStatus().value());
    }
}
