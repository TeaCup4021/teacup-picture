package com.teacup.teacuppicturebackend.api.v1;

import com.teacup.teacuppicturebackend.api.v1.model.M6Dtos;
import com.teacup.teacuppicturebackend.model.entity.User;
import com.teacup.teacuppicturebackend.storage.PictureStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class M6ControllerTest {
    private MockMvc mockMvc;
    private final M1Service auth = mock(M1Service.class);
    private final M6Service service = mock(M6Service.class);
    private final User user = new User();

    @BeforeEach
    void setUp() throws Exception {
        user.setId(11L);
        RequestIdFilter filter = new RequestIdFilter();
        filter.init(new MockFilterConfig());
        mockMvc = MockMvcBuilders.standaloneSetup(new M6Controller(auth, service))
                .setControllerAdvice(new V1ExceptionHandler()).addFilters(filter).build();
    }

    @Test
    void shareManagementRequiresAuthentication() throws Exception {
        when(auth.requireUser(any())).thenThrow(V1Exception.unauthorized());

        mockMvc.perform(get("/api/v1/pictures/100/shares"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void shareManagementReturnsForbiddenContract() throws Exception {
        when(auth.requireUser(any())).thenReturn(user);
        when(service.createShare(eq(user), eq(100L), any(), eq(false))).thenThrow(V1Exception.forbidden());

        mockMvc.perform(post("/api/v1/pictures/100/shares")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    void protectedShareUsesDedicatedPasswordCode() throws Exception {
        when(service.grantShare(eq("public-id"), any(), any())).thenThrow(V1Exception.passwordRequired());

        mockMvc.perform(post("/api/v1/public/shares/public-id/access")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"secret\":\"fragment-secret\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40102));
    }

    @Test
    void successfulShareAccessIsNeverCached() throws Exception {
        when(service.grantShare(eq("public-id"), any(), any())).thenReturn(
                new M6Dtos.ShareAccessResult(true, false, Instant.now().plusSeconds(3600)));

        mockMvc.perform(post("/api/v1/public/shares/public-id/access")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"secret\":\"fragment-secret\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.data.granted").value(true));
    }

    @Test
    void grantedAnonymousShareContentReturnsPrivateAssetWithoutAttachmentHeader() throws Exception {
        when(auth.requireUser(any())).thenThrow(V1Exception.unauthorized());
        PictureStorage.StoredObject object = new PictureStorage.StoredObject(
                new ByteArrayResource(new byte[]{1, 2, 3}), 3L, "image/png", "cup.png");
        when(service.shareContent(eq("public-id"), any(), eq(false), isNull())).thenReturn(
                new M6Service.Download(object, "cup.png", MediaType.IMAGE_PNG));

        mockMvc.perform(get("/api/v1/public/shares/public-id/content"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    @Test
    void shareDownloadRequiresLogin() throws Exception {
        when(auth.requireUser(any())).thenThrow(V1Exception.unauthorized());

        mockMvc.perform(get("/api/v1/public/shares/public-id/download"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void focusedCommentThreadRequiresAuthentication() throws Exception {
        when(auth.requireUser(any())).thenThrow(V1Exception.unauthorized());

        mockMvc.perform(get("/api/v1/comments/50"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void focusedCommentThreadValidatesTheRootId() throws Exception {
        when(auth.requireUser(any())).thenReturn(user);

        mockMvc.perform(get("/api/v1/comments/not-an-id"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void focusedCommentThreadHidesMissingOrInaccessibleResources() throws Exception {
        when(auth.requireUser(any())).thenReturn(user);
        when(service.commentThread(eq(user), eq(50L), any())).thenThrow(V1Exception.notFound());

        mockMvc.perform(get("/api/v1/comments/50"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void focusedCommentThreadIsNeverCached() throws Exception {
        when(auth.requireUser(any())).thenReturn(user);

        mockMvc.perform(get("/api/v1/comments/50"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(service).commentThread(eq(user), eq(50L), any());
    }
}
