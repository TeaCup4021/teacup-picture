package com.teacup.teacuppicturebackend.api.v1;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teacup.teacuppicturebackend.mapper.PictureDraftMapper;
import com.teacup.teacuppicturebackend.mapper.PictureMapper;
import com.teacup.teacuppicturebackend.mapper.PictureVersionMapper;
import com.teacup.teacuppicturebackend.mapper.UserMapper;
import com.teacup.teacuppicturebackend.model.entity.Picture;
import com.teacup.teacuppicturebackend.model.entity.PictureDraft;
import com.teacup.teacuppicturebackend.model.entity.PictureVersion;
import com.teacup.teacuppicturebackend.model.entity.User;
import com.teacup.teacuppicturebackend.service.UserService;
import com.teacup.teacuppicturebackend.storage.PictureAssetService;
import com.teacup.teacuppicturebackend.storage.PictureStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class M3ServiceTest {
    private static final String V2_STATE = """
            {"schemaVersion":2,"canvas":{"width":800,"height":600},"transform":{"rotation":0,"scale":1},
            "crop":null,"adjustments":{"exposure":0,"brightness":0,"contrast":0,"highlights":0,"shadows":0,
            "saturation":0,"vibrance":0,"temperature":0,"tint":0,"sharpness":0,"fade":0,"vignette":0,
            "enhance":0,"dehaze":0},"layers":[]}
            """;
    private final PictureMapper pictures = mock(PictureMapper.class);
    private final PictureDraftMapper drafts = mock(PictureDraftMapper.class);
    private final PictureVersionMapper versions = mock(PictureVersionMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final UserService userService = mock(UserService.class);
    private final PictureStorage storage = mock(PictureStorage.class);
    private final PictureAssetService assets = mock(PictureAssetService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private M3Service service;
    private User user;
    private Picture picture;

    @BeforeEach
    void setUp() {
        service = new M3Service(pictures, drafts, versions, users, userService, storage, assets, objectMapper);
        user = new User();
        user.setId(11L);
        user.setUserName("编辑者");
        picture = new Picture();
        picture.setId(100L);
        picture.setUserId(11L);
        picture.setSpaceId(200L);
        picture.setIsDelete(0);
        when(pictures.selectById(100L)).thenReturn(picture);
        when(pictures.lockPictureForUpdate(100L)).thenReturn(100L);
        when(users.selectById(11L)).thenReturn(user);
        when(assets.versionContentUrl(eq(100L), anyLong(), any())).thenAnswer(invocation ->
                "http://localhost/api/v1/pictures/100/versions/" + invocation.getArgument(1) + "/content?variant=" + invocation.getArgument(2));
    }

    @Test
    void saveDraftCreatesNewDraft() throws Exception {
        when(drafts.selectOne(any(Wrapper.class))).thenReturn(null);
        when(drafts.insert(any(PictureDraft.class))).thenAnswer(invocation -> {
            ((PictureDraft) invocation.getArgument(0)).setId(1L);
            return 1;
        });

        var result = service.saveDraft(user, 100L, objectMapper.readTree(V2_STATE), null);

        assertNotNull(result.editorState());
        assertEquals(1L, result.revision());
        assertEquals(1, result.editorState().toString().length() > 0 ? 1 : 0);
        verify(drafts).insert(any(PictureDraft.class));
    }

    @Test
    void saveDraftRejectsNonObjectEditorState() {
        V1Exception exception = assertThrows(V1Exception.class,
                () -> service.saveDraft(user, 100L, "not-an-object", null));
        assertEquals(40000, exception.getCode());
        verify(drafts, never()).insert(any(PictureDraft.class));
    }

    @Test
    void saveDraftRejectsV1AndIncompleteV2State() {
        assertEquals(40000, assertThrows(V1Exception.class,
                () -> service.saveDraft(user, 100L, Map.of("schemaVersion", 1), null)).getCode());
        assertEquals(40000, assertThrows(V1Exception.class,
                () -> service.saveDraft(user, 100L, Map.of("schemaVersion", 2, "layers", java.util.List.of()), null)).getCode());
        verify(drafts, never()).insert(any(PictureDraft.class));
    }

    @Test
    void saveDraftHidesOtherUsersPicture() {
        picture.setUserId(12L);
        assertEquals(40400, assertThrows(V1Exception.class,
                () -> service.saveDraft(user, 100L, Map.of(), null)).getCode());
    }

    @Test
    void saveDraftRejectsStaleRevision() throws Exception {
        PictureDraft current = new PictureDraft();
        current.setId(1L);
        current.setPictureId(100L);
        current.setEditorState(V2_STATE);
        current.setRevision(3L);
        when(drafts.selectOne(any(Wrapper.class))).thenReturn(current);

        V1Exception exception = assertThrows(V1Exception.class,
                () -> service.saveDraft(user, 100L, objectMapper.readTree(V2_STATE), 2L));

        assertEquals(40901, exception.getCode());
        verify(drafts, never()).updateById(any(PictureDraft.class));
    }

    @Test
    void deleteDraftRequiresCurrentRevision() {
        PictureDraft current = new PictureDraft();
        current.setId(1L);
        current.setPictureId(100L);
        current.setRevision(4L);
        when(drafts.selectOne(any(Wrapper.class))).thenReturn(current);

        assertEquals(40901, assertThrows(V1Exception.class,
                () -> service.deleteDraft(user, 100L, 3L)).getCode());
        service.deleteDraft(user, 100L, 4L);

        verify(drafts).deleteById(1L);
    }

    @Test
    void createVersionStoresPreviewAndPersistsVersion() {
        MultipartFile preview = mock(MultipartFile.class);
        when(preview.isEmpty()).thenReturn(false);
        when(versions.selectMaxVersionNumber(100L)).thenReturn(0);
        when(versions.insert(any(PictureVersion.class))).thenAnswer(invocation -> {
            ((PictureVersion) invocation.getArgument(0)).setId(51L);
            return 1;
        });
        PictureStorage.StoredPicture stored = new PictureStorage.StoredPicture(
                "spaces/200/pictures/uuid/original.png",
                "spaces/200/pictures/uuid/thumbnail.jpeg",
                1024L, 800, 600, "png", "image/png", "sha256");
        when(storage.store(preview, 200L)).thenReturn(stored);

        var result = service.createVersion(user, 100L, preview,
                V2_STATE, "正式版", "首次保存", "user_save");

        assertEquals("51", result.id());
        assertEquals(1, result.versionNumber());
        assertEquals("user_save", result.sourceType());
        assertEquals("http://localhost/api/v1/pictures/100/versions/51/content?variant=original", result.previewUrl());
        verify(storage).store(preview, 200L);
        verify(versions).insert(any(PictureVersion.class));
    }

    @Test
    void createVersionCompensatesStorageWhenInsertFails() {
        MultipartFile preview = mock(MultipartFile.class);
        when(preview.isEmpty()).thenReturn(false);
        when(versions.selectMaxVersionNumber(100L)).thenReturn(0);
        PictureStorage.StoredPicture stored = new PictureStorage.StoredPicture(
                "spaces/200/pictures/uuid/original.png",
                "spaces/200/pictures/uuid/thumbnail.jpeg",
                1024L, 800, 600, "png", "image/png", "sha256");
        when(storage.store(preview, 200L)).thenReturn(stored);
        when(versions.insert(any(PictureVersion.class))).thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class,
                () -> service.createVersion(user, 100L, preview,
                        V2_STATE, null, null, "user_save"));
        verify(storage).delete("spaces/200/pictures/uuid/original.png");
        verify(storage).delete("spaces/200/pictures/uuid/thumbnail.jpeg");
    }

    @Test
    void restoreVersionCopiesStateAndBumpsNumber() {
        PictureVersion source = new PictureVersion();
        source.setId(51L);
        source.setPictureId(100L);
        source.setVersionNumber(2);
        source.setName("草稿");
        source.setEditorState(V2_STATE);
        source.setSchemaVersion(2);
        source.setAssetObjectKey("spaces/200/pictures/uuid/original.png");
        source.setThumbnailObjectKey("spaces/200/pictures/uuid/thumbnail.jpeg");
        source.setContentType("image/png");
        source.setWidth(800);
        source.setHeight(600);
        source.setSize(1024L);
        when(versions.selectById(51L)).thenReturn(source);
        when(versions.selectMaxVersionNumber(100L)).thenReturn(3);
        when(versions.insert(any(PictureVersion.class))).thenAnswer(invocation -> {
            ((PictureVersion) invocation.getArgument(0)).setId(52L);
            return 1;
        });
        when(drafts.selectOne(any(Wrapper.class))).thenReturn(null);
        when(drafts.insert(any(PictureDraft.class))).thenAnswer(invocation -> {
            ((PictureDraft) invocation.getArgument(0)).setId(1L);
            return 1;
        });

        var result = service.restoreVersion(user, 100L, 51L, null);

        assertEquals("52", result.id());
        assertEquals(4, result.versionNumber());
        assertEquals("restore", result.sourceType());
        assertEquals("51", result.parentVersionId());
        verify(drafts).insert(any(PictureDraft.class));
    }

    @Test
    void hiddenVersionUsesNotFoundContract() {
        PictureVersion source = new PictureVersion();
        source.setId(51L);
        source.setPictureId(999L);
        when(versions.selectById(51L)).thenReturn(source);
        assertEquals(40400, assertThrows(V1Exception.class,
                () -> service.getVersion(user, 100L, 51L)).getCode());
    }

    @Test
    void loadVersionContentUsesThumbnailVariant() {
        PictureVersion version = new PictureVersion();
        version.setId(51L);
        version.setPictureId(100L);
        version.setAssetObjectKey("spaces/200/pictures/uuid/original.png");
        version.setThumbnailObjectKey("spaces/200/pictures/uuid/thumbnail.jpeg");
        when(versions.selectById(51L)).thenReturn(version);
        PictureStorage.StoredObject object = new PictureStorage.StoredObject(
                new org.springframework.core.io.ByteArrayResource(new byte[]{1}), 1L, "image/jpeg", "thumbnail.jpeg");
        when(storage.load("spaces/200/pictures/uuid/thumbnail.jpeg")).thenReturn(object);

        PictureStorage.StoredObject loaded = service.loadVersionContent(user, 100L, 51L, "thumbnail");

        assertSame(object, loaded);
        verify(storage).load("spaces/200/pictures/uuid/thumbnail.jpeg");
    }
}
