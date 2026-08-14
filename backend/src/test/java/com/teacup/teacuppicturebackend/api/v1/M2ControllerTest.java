package com.teacup.teacuppicturebackend.api.v1;

import com.teacup.teacuppicturebackend.ai.AiTaskService;
import com.teacup.teacuppicturebackend.api.v1.model.M1Dtos;
import com.teacup.teacuppicturebackend.api.v1.model.M2Dtos;
import com.teacup.teacuppicturebackend.model.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class M2ControllerTest {
    private MockMvc mockMvc;
    private final M1Service auth = mock(M1Service.class);
    private final AiTaskService tasks = mock(AiTaskService.class);
    private final User user = new User();

    @BeforeEach
    void setUp() throws Exception {
        user.setId(11L);
        RequestIdFilter filter = new RequestIdFilter();
        filter.init(new MockFilterConfig());
        mockMvc = MockMvcBuilders.standaloneSetup(new M2Controller(auth, tasks))
                .setControllerAdvice(new V1ExceptionHandler()).addFilters(filter).build();
    }

    @Test
    void modelsRequireAuthentication() throws Exception {
        when(auth.requireUser(any())).thenThrow(V1Exception.unauthorized());
        mockMvc.perform(get("/api/v1/ai/models"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void quotasReturnIndependentUsage() throws Exception {
        when(auth.requireUser(any())).thenReturn(user);
        when(tasks.quotas(user)).thenReturn(new M2Dtos.AiQuotaSummary(LocalDate.of(2026, 8, 13), List.of(
                new M2Dtos.AiQuotaView("generate", 100, 3, 1, 96),
                new M2Dtos.AiQuotaView("outpaint", 100, 2, 0, 98))));
        mockMvc.perform(get("/api/v1/ai/quotas/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quotas[0].remaining").value(96))
                .andExpect(jsonPath("$.data.quotas[1].taskType").value("outpaint"));
    }

    @Test
    void createRequiresIdempotencyKey() throws Exception {
        when(auth.requireUser(any())).thenReturn(user);
        mockMvc.perform(post("/api/v1/ai/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"generate\",\"modelCode\":\"openai-image\",\"prompt\":\"tea\",\"ratio\":\"1:1\",\"quality\":\"standard\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createReturnsStringIdAndCreatedStatus() throws Exception {
        when(auth.requireUser(any())).thenReturn(user);
        M2Dtos.AiModelView model = new M2Dtos.AiModelView("1", "openai-image", "OpenAI Images",
                List.of("generate"), List.of("1:1"), List.of("standard"), List.of("auto"),
                List.of("png"), true, false, 1, true);
        M2Dtos.AiTaskView task = new M2Dtos.AiTaskView("9007199254740993", "generate", model,
                "tea", "1:1", "standard", "auto", "png", null, "queued",
                null, null, null, null, null,
                false, false, Instant.now(), null, null, null);
        when(tasks.create(eq(user), any(), eq("request-12345678")))
                .thenReturn(new AiTaskService.CreateResult(task, true));
        mockMvc.perform(post("/api/v1/ai/tasks").header("Idempotency-Key", "request-12345678")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"generate\",\"modelCode\":\"openai-image\",\"prompt\":\"tea\",\"ratio\":\"1:1\",\"quality\":\"standard\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value("9007199254740993"))
                .andExpect(jsonPath("$.data.outputFormat").value("png"));
    }

    @Test
    void hiddenTaskUsesNotFoundContract() throws Exception {
        when(auth.requireUser(any())).thenReturn(user);
        when(tasks.get(user, 99L)).thenThrow(V1Exception.notFound());
        mockMvc.perform(get("/api/v1/ai/tasks/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }
}
