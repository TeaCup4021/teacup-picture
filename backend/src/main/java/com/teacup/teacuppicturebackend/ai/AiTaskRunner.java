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
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

    public AiTaskRunner(AiTaskMapper taskMapper, PictureMapper pictureMapper, UserMapper userMapper,
                         AiProviderRegistry providerRegistry, PictureStorage storage,
                         PersonalSpaceService personalSpaceService, M1Service m1Service, @Lazy AiTaskService taskService) {
        this.taskMapper = taskMapper;
        this.pictureMapper = pictureMapper;
        this.userMapper = userMapper;
        this.providerRegistry = providerRegistry;
        this.storage = storage;
        this.personalSpaceService = personalSpaceService;
        this.m1Service = m1Service;
        this.taskService = taskService;
    }

    @Async("customExecutor")
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
                    providerUrl(source), providerUrl(reference)));
            User user = userMapper.selectById(task.getUserId());
            long spaceId = personalSpaceService.getOrCreatePersonalSpace(user.getId()).getId();
            PictureStorage.StoredPicture stored = storage.importUrl(result.imageUrl(), spaceId);
            Picture picture = m1Service.saveGeneratedPicture(user, stored,
                    "AI " + ("generate".equals(task.getTaskType()) ? "绘图" : "扩图") + " " + task.getId(),
                    task.getPrompt(), List.of("AI", "generate".equals(task.getTaskType()) ? "绘图" : "扩图"));
            complete(taskId, result, picture.getId());
        } catch (AiProviderException exception) {
            taskService.fail(taskId, exception.getCode(), exception.getMessage());
        } catch (RuntimeException exception) {
            taskService.fail(taskId, "result_persistence_failed", "AI 结果保存失败");
        }
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
    public void complete(long taskId, AiProviderResult result, long pictureId) {
        AiTask task = taskMapper.selectOne(new LambdaQueryWrapper<AiTask>().eq(AiTask::getId, taskId).last("FOR UPDATE"));
        if (task == null) return;
        if ("cancelled".equals(task.getStatus())) return;
        task.setStatus("succeeded");
        task.setProviderTaskId(result.providerTaskId());
        task.setProviderRequestId(result.providerRequestId());
        task.setResultPictureId(pictureId);
        task.setFinishTime(LocalDateTime.now());
        taskMapper.updateById(task);
    }
}
