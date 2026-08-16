package com.teacup.teacuppicturebackend.api.v1;

import com.teacup.teacuppicturebackend.api.v1.model.M1Dtos;
import com.teacup.teacuppicturebackend.api.v1.model.M3Dtos;
import com.teacup.teacuppicturebackend.model.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterConfig;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class M3ControllerTest {
    private MockMvc mockMvc;
    private final M1Service auth = mock(M1Service.class);
    private final M3Service service = mock(M3Service.class);
    private final User user = new User();

    @BeforeEach
    void setUp() throws Exception {
        user.setId(11L);
        RequestIdFilter filter = new RequestIdFilter();
        filter.init(new MockFilterConfig());
        mockMvc = MockMvcBuilders.standaloneSetup(new M3Controller(auth, service))
                .setControllerAdvice(new V1ExceptionHandler()).addFilters(filter).build();
    }

    @Test
    void draftRequiresAuthentication() throws Exception {
        when(auth.requireUser(any())).thenThrow(V1Exception.unauthorized());
        mockMvc.perform(get("/api/v1/pictures/100/editor-state"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void saveDraftReturnsEditorState() throws Exception {
        when(auth.requireUser(any())).thenReturn(user);
        when(service.saveDraft(eq(user), eq(100L), any(), eq(7L))).thenReturn(
                new M3Dtos.EditorStateView(Map.of("schemaVersion", 2, "layers", List.of()), Instant.now(), 8L));

        mockMvc.perform(put("/api/v1/pictures/100/editor-state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"editorState\":{\"schemaVersion\":2,\"layers\":[]},\"expectedRevision\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.editorState.schemaVersion").value(2))
                .andExpect(jsonPath("$.data.revision").value(8));
    }

    @Test
    void deleteDraftReturnsEmptyState() throws Exception {
        when(auth.requireUser(any())).thenReturn(user);
        when(service.deleteDraft(user, 100L, 8L)).thenReturn(
                new M3Dtos.EditorStateView(null, null, null));

        mockMvc.perform(delete("/api/v1/pictures/100/editor-state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":8}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.editorState").doesNotExist())
                .andExpect(jsonPath("$.data.revision").doesNotExist());
    }

    @Test
    void listVersionsReturnsStringIds() throws Exception {
        when(auth.requireUser(any())).thenReturn(user);
        when(service.listVersions(user, 100L)).thenReturn(new M3Dtos.VersionList(List.of(
                new M3Dtos.VersionSummary("51", 1, "正式版", "首次保存", "user_save",
                        null, 800, 600, "http://localhost/api/v1/pictures/100/versions/51/content?variant=thumbnail",
                        new M1Dtos.AuthorSummary("11", "编辑者", null), Instant.now()))));

        mockMvc.perform(get("/api/v1/pictures/100/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("51"))
                .andExpect(jsonPath("$.data.items[0].versionNumber").value(1));
    }

    @Test
    void createVersionReturnsCreatedStatus() throws Exception {
        when(auth.requireUser(any())).thenReturn(user);
        when(service.createVersion(eq(user), eq(100L), any(), eq("{\"schemaVersion\":2,\"layers\":[]}"), eq("正式版"), eq("说明"), eq("user_save")))
                .thenReturn(new M3Dtos.VersionDetail("51", 1, "正式版", "说明", "user_save",
                        null, 800, 600, "http://localhost/api/v1/pictures/100/versions/51/content?variant=original",
                        new M1Dtos.AuthorSummary("11", "编辑者", null), Instant.now(), Map.of("schemaVersion", 2, "layers", List.of())));
        MockMultipartFile preview = new MockMultipartFile("file", "preview.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/v1/pictures/100/versions")
                        .file(preview)
                        .param("editorState", "{\"schemaVersion\":2,\"layers\":[]}")
                        .param("name", "正式版")
                        .param("note", "说明"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value("51"))
                .andExpect(jsonPath("$.data.editorState.schemaVersion").value(2));
    }

    @Test
    void restoreVersionReturnsCreatedStatus() throws Exception {
        when(auth.requireUser(any())).thenReturn(user);
        when(service.restoreVersion(user, 100L, 51L, 9L))
                .thenReturn(new M3Dtos.VersionDetail("52", 2, "正式版 恢复", "恢复自 v1", "restore",
                        "51", 800, 600, "http://localhost/api/v1/pictures/100/versions/52/content?variant=original",
                        new M1Dtos.AuthorSummary("11", "编辑者", null), Instant.now(), Map.of("schemaVersion", 2, "layers", List.of())));

        mockMvc.perform(post("/api/v1/pictures/100/versions/51/restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":9}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.parentVersionId").value("51"))
                .andExpect(jsonPath("$.data.sourceType").value("restore"));
    }

    @Test
    void versionContentUsesNoStoreCache() throws Exception {
        when(auth.requireUser(any())).thenReturn(user);
        when(service.loadVersionContent(user, 100L, 51L, "original"))
                .thenReturn(new com.teacup.teacuppicturebackend.storage.PictureStorage.StoredObject(
                        new ByteArrayResource(new byte[]{1, 2, 3}), 3L, "image/png", "preview.png"));

        mockMvc.perform(get("/api/v1/pictures/100/versions/51/content"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    @Test
    void invalidPictureIdReturnsBadRequest() throws Exception {
        when(auth.requireUser(any())).thenReturn(user);
        mockMvc.perform(get("/api/v1/pictures/not-a-number/versions"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }
}
