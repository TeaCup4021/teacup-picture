package com.teacup.teacuppicturebackend.api.v1;

import com.teacup.teacuppicturebackend.api.v1.model.M1Dtos;
import com.teacup.teacuppicturebackend.model.entity.User;
import com.teacup.teacuppicturebackend.storage.PictureAssetService;
import com.teacup.teacuppicturebackend.storage.PictureStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class M1ControllerTest {
    MockMvc mockMvc;
    M1Service service = mock(M1Service.class);
    PictureAssetService assets = mock(PictureAssetService.class);

    @BeforeEach
    void setUp() throws Exception {
        RequestIdFilter filter = new RequestIdFilter();
        filter.init(new MockFilterConfig());
        mockMvc = MockMvcBuilders.standaloneSetup(new M1Controller(service, assets))
                .setControllerAdvice(new V1ExceptionHandler())
                .addFilters(filter)
                .build();
    }

    @Test
    void registerReturnsContractResponseAndRequestId() throws Exception {
        when(service.register(any())).thenReturn(new M1Dtos.RegistrationResult("101", "201"));
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"tester\",\"password\":\"password1\",\"passwordConfirmation\":\"password1\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value("101"))
                .andExpect(jsonPath("$.data.personalSpaceId").value("201"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void loginUsesConfiguredSessionCookie() throws Exception {
        User user = new User(); user.setId(101L);
        when(service.login(any(), any())).thenReturn(user);
        when(service.currentUser(user)).thenReturn(new M1Dtos.CurrentUser("101", "tester", "Tester", null, null, "user", Instant.now()));
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"tester\",\"password\":\"password1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("101"));
    }

    @Test
    void unauthenticatedRequestUses401Contract() throws Exception {
        when(service.requireUser(any())).thenThrow(V1Exception.unauthorized());
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void logoutIsIdempotentAndExpiresSessionCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("TEACUP_SESSION=;")))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
    }

    @Test
    void privateContentRequiresLogin() throws Exception {
        when(service.requireUser(any())).thenThrow(V1Exception.unauthorized());
        mockMvc.perform(get("/api/v1/pictures/31/content"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void publicContentReturnsNotFoundWhenPictureIsNotPublic() throws Exception {
        when(assets.loadPublic(31L, "original")).thenThrow(V1Exception.notFound());
        mockMvc.perform(get("/api/v1/public/pictures/31/content"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void privateContentReturnsObjectMetadata() throws Exception {
        User user = new User(); user.setId(11L);
        PictureStorage.StoredObject object = new PictureStorage.StoredObject(
                new org.springframework.core.io.ByteArrayResource(new byte[]{1, 2, 3}), 3, "image/png", "original.png");
        when(service.requireUser(any())).thenReturn(user);
        when(assets.loadPrivate(user, 31L, "original")).thenReturn(object);
        mockMvc.perform(get("/api/v1/pictures/31/content"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Content-Length", "3"));
    }
}
