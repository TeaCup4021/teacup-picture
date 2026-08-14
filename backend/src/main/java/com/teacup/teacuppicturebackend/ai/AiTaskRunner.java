package com.teacup.teacuppicturebackend.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.teacup.teacuppicturebackend.api.v1.M1Service;
import com.teacup.teacuppicturebackend.mapper.AiTaskMapper;
import com.teacup.teacuppicturebackend.mapper.PictureMapper;
import com.teacup.teacuppicturebackend.mapper.UserMapper;
import com.teacup.teacuppicturebackend.model.entity.AiTask;
import com.teacup.teacuppicturebackend.model.entity.Picture;
import com.teacup.teacuppicturebackend.model.entity.User;
import com.teacup.teacuppicturebackend.service.PersonalSpaceService;
import com.teacup.teacuppicturebackend.storage.PictureStorage;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;

@Component
public class AiTaskRunner {
    private final AiTaskMapper taskMapper;
    private final PictureMapper pictureMapper;
    private final UserMapper userMapper;
    private final AiProviderRegistry providerRegistry;
    private final PictureStorage storage;
    private final PersonalSpaceService personalSpaceService;
    private final M1Service m1Service;
    private final AiTaskService taskService;
    private final long runningTimeoutMinutes;

    public AiTaskRunner(AiTaskMapper taskMapper, PictureMapper pictureMapper, UserMapper userMapper,
                         AiProviderRegistry providerRegistry, PictureStorage storage,
                         PersonalSpaceService personalSpaceService, M1Service m1Service, @Lazy AiTaskService taskService,
                         @Value("${teacup.ai.running-timeout-minutes:15}") long runningTimeoutMinutes) {
        this.taskMapper = taskMapper;
        this.pictureMapper = pictureMapper;
        this.userMapper = userMapper;
        this.providerRegistry = providerRegistry;
        this.storage = storage;
        this.personalSpaceService = personalSpaceService;
        this.m1Service = m1Service;
        this.taskService = taskService;
        this.runningTimeoutMinutes = runningTimeoutMinutes;
    }

    @Async("aiTaskExecutor")
    public void runAsync(long taskId) {
        run(taskId);
    }

    public void run(long taskId) {
        AiTask task = markRunning(taskId);
        if (task == null) return;
        try {
            Picture source = task.getSourcePictureId() == null ? null : pictureMapper.selectById(task.getSourcePictureId());
            Picture reference = task.getReferencePictureId() == null ? null : pictureMapper.selectById(task.getReferencePictureId());
            AiProviderResult result = providerRegistry.require(task.getProvider()).execute(new AiProviderRequest(
                    task.getTaskType(), task.getProviderModel(), task.getPrompt(), task.getRatio(), task.getQuality(),
                    task.getBackground(), task.getOutputFormat(), task.getOutputCompression(),
                    providerUrl(source), providerUrl(reference)));
            if (taskService.isCancelled(taskId)) return;
            User user = userMapper.selectById(task.getUserId());
            long spaceId = personalSpaceService.getOrCreatePersonalSpace(user.getId()).getId();
            PictureStorage.StoredPicture stored = storeResult(result, spaceId);
            Picture picture = m1Service.saveGeneratedPicture(user, stored,
                    "AI " + ("generate".equals(task.getTaskType()) ? "绘图" : "扩图") + " " + task.getId(),
                    task.getPrompt(), List.of("AI", "generate".equals(task.getTaskType()) ? "绘图" : "扩图"));
            if (!complete(taskId, result, picture.getId())) {
                m1Service.discardGeneratedPicture(picture);
            }
        } catch (AiProviderException exception) {
            if ("provider_permission_denied".equals(exception.getCode()) && task.getModelId() != null) {
                taskService.disableModel(task.getModelId());
            }
            taskService.fail(taskId, exception.getCode(), exception.getMessage());
        } catch (RuntimeException exception) {
            taskService.fail(taskId, "result_persistence_failed", "AI 结果保存失败");
        }
    }

    PictureStorage.StoredPicture storeResult(AiProviderResult result, long spaceId) {
        if (result.imageBase64() != null && !result.imageBase64().isBlank()) {
            try {
                String value = result.imageBase64();
                int separator = value.indexOf(',');
                if (value.startsWith("data:") && separator >= 0) value = value.substring(separator + 1);
                byte[] bytes = Base64.getDecoder().decode(value);
                String contentType = result.imageContentType() == null ? "image/png" : result.imageContentType();
                String extension = "image/jpeg".equals(contentType) ? "jpeg" : "image/webp".equals(contentType) ? "webp" : "png";
                return storage.store(new ByteArrayInputStream(bytes), "generated." + extension, contentType, spaceId);
            } catch (IllegalArgumentException exception) {
                throw new AiProviderException("provider_invalid_image", "AI 服务返回的图片数据无效");
            }
        }
        if (result.imageUrl() != null && !result.imageUrl().isBlank()) return storage.importUrl(result.imageUrl(), spaceId);
        throw new AiProviderException("provider_missing_output", "AI 服务未返回图片");
    }

    @Scheduled(initialDelay = 5_000L, fixedDelay = 60_000L)
    public void recoverInterruptedTasks() {
        taskMapper.selectList(new LambdaQueryWrapper<AiTask>().eq(AiTask::getStatus, "queued")
                        .orderByAsc(AiTask::getId).last("LIMIT 50"))
                .forEach(task -> runAsync(task.getId()));
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(Math.max(1, runningTimeoutMinutes));
        taskMapper.selectList(new LambdaQueryWrapper<AiTask>().eq(AiTask::getStatus, "running")
                        .le(AiTask::getStartTime, deadline).orderByAsc(AiTask::getId).last("LIMIT 50"))
                .forEach(task -> taskService.fail(task.getId(), "task_timeout", "AI 任务执行超时"));
    }

    private String providerUrl(Picture picture) {
        if (picture == null) return null;
        if (picture.getObjectKey() != null && !picture.getObjectKey().isBlank()) return storage.temporaryUrl(picture.getObjectKey());
        return picture.getUrl();
    }

    @Transactional(rollbackFor = Exception.class)
    public AiTask markRunning(long taskId) {
        AiTask task = taskMapper.selectOne(new LambdaQueryWrapper<AiTask>().eq(AiTask::getId, taskId).last("FOR UPDATE"));
        if (task == null || !"queued".equals(task.getStatus())) return null;
        task.setStatus("running");
        task.setInvocationStarted(1);
        task.setStartTime(LocalDateTime.now());
        taskMapper.updateById(task);
        return task;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean complete(long taskId, AiProviderResult result, long pictureId) {
        AiTask task = taskMapper.selectOne(new LambdaQueryWrapper<AiTask>().eq(AiTask::getId, taskId).last("FOR UPDATE"));
        if (task == null || "cancelled".equals(task.getStatus())) return false;
        task.setStatus("succeeded");
        task.setProviderTaskId(result.providerTaskId());
        task.setProviderRequestId(result.providerRequestId());
        task.setResultPictureId(pictureId);
        taskService.settleQuota(task);
        task.setFinishTime(LocalDateTime.now());
        taskMapper.updateById(task);
        return true;
    }
}
