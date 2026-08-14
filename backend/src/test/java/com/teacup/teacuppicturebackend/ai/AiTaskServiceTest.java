package com.teacup.teacuppicturebackend.ai;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.teacup.teacuppicturebackend.api.v1.V1Exception;
import com.teacup.teacuppicturebackend.api.v1.model.M2Dtos;
import com.teacup.teacuppicturebackend.mapper.*;
import com.teacup.teacuppicturebackend.model.entity.*;
import com.teacup.teacuppicturebackend.service.UserService;
import com.teacup.teacuppicturebackend.storage.PictureStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AiTaskServiceTest {
    private final AiModelMapper models = mock(AiModelMapper.class);
    private final AiQuotaUsageMapper quotas = mock(AiQuotaUsageMapper.class);
    private final AiTaskMapper tasks = mock(AiTaskMapper.class);
    private final PictureMapper pictures = mock(PictureMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final UserService userService = mock(UserService.class);
    private final PictureStorage storage = mock(PictureStorage.class);
    private AiTaskService service;
    private User user;
    private AiModel model;
    private AiQuotaUsage quota;

    @BeforeEach
    void setUp() {
        service = new AiTaskService(models, quotas, tasks, pictures, users, userService, storage,
                100, 100, "Asia/Shanghai");
        user = new User(); user.setId(11L);
        model = new AiModel(); model.setId(1L); model.setCode("openai-image"); model.setDisplayName("OpenAI Images");
        model.setProvider("openai"); model.setProviderModel("gpt-image-1"); model.setCapabilities("[\"generate\"]");
        model.setSupportedRatios("[\"1:1\"]"); model.setSupportedQualities("[\"standard\"]");
        model.setSupportedBackgrounds("[\"auto\",\"opaque\",\"transparent\"]");
        model.setSupportedOutputFormats("[\"png\",\"jpeg\",\"webp\"]");
        model.setSupportsOutputCompression(1);
        model.setSupportsReference(0); model.setQuotaCost(1); model.setEnabled(1);
        quota = new AiQuotaUsage(); quota.setId(2L); quota.setUserId(11L); quota.setUsageDate(LocalDate.now());
        quota.setTaskType("generate"); quota.setUsedCount(0); quota.setReservedCount(0);
        when(models.selectOne(any(Wrapper.class))).thenReturn(model);
        when(models.selectById(1L)).thenReturn(model);
        when(users.lockById(11L)).thenReturn(11L);
        when(quotas.selectForUpdate(eq(11L), any(), eq("generate"))).thenReturn(quota);
        when(tasks.insert(any(AiTask.class))).thenAnswer(invocation -> { ((AiTask) invocation.getArgument(0)).setId(31L); return 1; });
    }

    @Test
    void invalidPromptIsRejectedBeforeQuotaReservation() {
        M2Dtos.CreateAiTaskRequest request = new M2Dtos.CreateAiTaskRequest(
                "generate", "openai-image", " ", "1:1", "standard",
                "auto", "png", null, null, null);
        assertEquals(40000, assertThrows(V1Exception.class,
                () -> service.create(user, request, "request-12345678")).getCode());
        verify(quotas, never()).updateById(any(AiQuotaUsage.class));
    }

    @Test
    void exhaustedQuotaReturnsConflict() {
        quota.setUsedCount(99); quota.setReservedCount(1);
        M2Dtos.CreateAiTaskRequest request = validRequest();
        assertEquals(40901, assertThrows(V1Exception.class,
                () -> service.create(user, request, "request-12345678")).getCode());
        verify(tasks, never()).insert(any(AiTask.class));
    }

    @Test
    void transparentBackgroundRejectsJpegBeforeQuotaReservation() {
        M2Dtos.CreateAiTaskRequest request = new M2Dtos.CreateAiTaskRequest(
                "generate", "openai-image", "tea", "1:1", "standard",
                "transparent", "jpeg", 90, null, null);

        assertEquals(40000, assertThrows(V1Exception.class,
                () -> service.create(user, request, "request-12345678")).getCode());
        verify(quotas, never()).updateById(any(AiQuotaUsage.class));
    }

    @Test
    void pngRejectsLossyCompressionBeforeQuotaReservation() {
        M2Dtos.CreateAiTaskRequest request = new M2Dtos.CreateAiTaskRequest(
                "generate", "openai-image", "tea", "1:1", "standard",
                "auto", "png", 90, null, null);

        assertEquals(40000, assertThrows(V1Exception.class,
                () -> service.create(user, request, "request-12345678")).getCode());
        verify(quotas, never()).updateById(any(AiQuotaUsage.class));
    }

    @Test
    void modelCapabilityRejectsUnsupportedOutputFormatBeforeQuotaReservation() {
        model.setSupportedOutputFormats("[\"png\"]");
        M2Dtos.CreateAiTaskRequest request = new M2Dtos.CreateAiTaskRequest(
                "generate", "openai-image", "tea", "1:1", "standard",
                "auto", "webp", 90, null, null);

        assertEquals(40000, assertThrows(V1Exception.class,
                () -> service.create(user, request, "request-12345678")).getCode());
        verify(quotas, never()).updateById(any(AiQuotaUsage.class));
    }

    @Test
    void queuedCancellationReleasesReservation() {
        AiTask task = task("queued"); task.setInvocationStarted(0);
        when(tasks.selectOne(any(Wrapper.class))).thenReturn(task);
        service.cancel(user, 31L);
        assertEquals("cancelled", task.getStatus());
        assertEquals(1, task.getQuotaRefunded());
        assertEquals(0, quota.getReservedCount());
    }

    @Test
    void runningCancellationSettlesConsumedQuota() {
        quota.setReservedCount(1);
        AiTask task = task("running"); task.setInvocationStarted(1);
        when(tasks.selectOne(any(Wrapper.class))).thenReturn(task);
        service.cancel(user, 31L);
        assertEquals(1, task.getQuotaSettled());
        assertEquals(1, quota.getUsedCount());
        assertEquals(0, quota.getReservedCount());
    }

    @Test
    void otherUsersTaskIsHidden() {
        AiTask task = task("queued"); task.setUserId(12L);
        when(tasks.selectOne(any(Wrapper.class))).thenReturn(task);
        assertEquals(40400, assertThrows(V1Exception.class, () -> service.cancel(user, 31L)).getCode());
    }

    @Test
    void providerPermissionFailureDisablesModel() {
        service.disableModel(1L);

        assertEquals(0, model.getEnabled());
        verify(models).updateById(model);
    }

    private M2Dtos.CreateAiTaskRequest validRequest() {
        return new M2Dtos.CreateAiTaskRequest("generate", "openai-image", "tea", "1:1", "standard",
                "auto", "png", null, null, null);
    }

    private AiTask task(String status) {
        AiTask task = new AiTask(); task.setId(31L); task.setUserId(11L); task.setTaskType("generate");
        task.setModelId(1L); task.setModelCode("openai-image"); task.setPrompt("tea"); task.setRatio("1:1");
        task.setQuality("standard"); task.setBackground("auto"); task.setOutputFormat("png");
        task.setStatus(status); task.setQuotaCost(1); task.setQuotaRefunded(0);
        task.setQuotaSettled(0); return task;
    }
}
