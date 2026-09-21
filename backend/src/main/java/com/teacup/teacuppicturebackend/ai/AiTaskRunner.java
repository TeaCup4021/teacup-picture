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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
    private final TransactionTemplate transactionTemplate;
    private final AiExecutionLimiter limiter;
    private final long leaseSeconds;
    private final int maxAttempts;
    private final String workerInstanceId = UUID.randomUUID().toString();

    public AiTaskRunner(AiTaskMapper taskMapper, PictureMapper pictureMapper, UserMapper userMapper,
                         AiProviderRegistry providerRegistry, PictureStorage storage,
                         PersonalSpaceService personalSpaceService, M1Service m1Service, AiTaskService taskService,
                         TransactionTemplate transactionTemplate,
                         AiExecutionLimiter limiter,
                         @Value("${teacup.ai.worker.lease-seconds:300}") long leaseSeconds,
                         @Value("${teacup.ai.worker.max-attempts:4}") int maxAttempts) {
        this.taskMapper = taskMapper;
        this.pictureMapper = pictureMapper;
        this.userMapper = userMapper;
        this.providerRegistry = providerRegistry;
        this.storage = storage;
        this.personalSpaceService = personalSpaceService;
        this.m1Service = m1Service;
        this.taskService = taskService;
        this.transactionTemplate = transactionTemplate;
        this.limiter = limiter;
        this.leaseSeconds = leaseSeconds;
        this.maxAttempts = maxAttempts;
    }

    public ExecutionResult run(long taskId) {
        AiTask snapshot = taskMapper.selectById(taskId);
        if (snapshot == null || terminal(snapshot.getStatus())) return ExecutionResult.ack();
        if ("queued".equals(snapshot.getStatus()) && snapshot.getNextAttemptAt() != null
                && snapshot.getNextAttemptAt().isAfter(LocalDateTime.now())) {
            long remaining = java.time.Duration.between(LocalDateTime.now(), snapshot.getNextAttemptAt()).getSeconds();
            return ExecutionResult.retry(retryBucket(remaining), "retry_not_due");
        }
        if ("running".equals(snapshot.getStatus()) && snapshot.getLeaseUntil() != null
                && snapshot.getLeaseUntil().isAfter(LocalDateTime.now())) {
            return ExecutionResult.retry(5, "task_already_claimed");
        }
        Optional<AiExecutionLimiter.Permit> acquired;
        try {
            acquired = limiter.tryAcquire(snapshot.getProvider());
        } catch (AiExecutionLimiter.AiLimiterUnavailableException exception) {
            return ExecutionResult.retry(5, "limiter_unavailable");
        }
        if (acquired.isEmpty()) return ExecutionResult.retry(5, "provider_capacity_exhausted");
        String workerId = workerInstanceId + ":" + Thread.currentThread().getName();
        try (AiExecutionLimiter.Permit permit = acquired.get()) {
            AiTask task = markRunning(taskId, workerId);
            if (task == null) {
                AiTask current = taskMapper.selectById(taskId);
                return current == null || terminal(current.getStatus())
                        ? ExecutionResult.ack() : ExecutionResult.retry(5, "task_claim_conflict");
            }
            Picture source = task.getSourcePictureId() == null ? null : pictureMapper.selectById(task.getSourcePictureId());
            Picture reference = task.getReferencePictureId() == null ? null : pictureMapper.selectById(task.getReferencePictureId());
            AiProviderResult result = providerRegistry.require(task.getProvider()).execute(new AiProviderRequest(
                    task.getTaskType(), task.getProviderModel(), task.getPrompt(), task.getRatio(), task.getQuality(),
                    task.getBackground(), task.getOutputFormat(), task.getOutputCompression(),
                    providerUrl(source), providerUrl(reference)));
            renewLease(taskId, workerId);
            if (taskService.isCancelled(taskId)) return ExecutionResult.ack();
            User user = userMapper.selectById(task.getUserId());
            long spaceId = personalSpaceService.getOrCreatePersonalSpace(user.getId()).getId();
            PictureStorage.StoredPicture stored = storeResult(result, spaceId);
            Picture picture = m1Service.saveGeneratedPicture(user, stored,
                    "AI " + ("generate".equals(task.getTaskType()) ? "绘图" : "扩图") + " " + task.getId(),
                    task.getPrompt(), List.of("AI", "generate".equals(task.getTaskType()) ? "绘图" : "扩图"));
            if (!complete(taskId, workerId, result, picture.getId())) {
                m1Service.discardGeneratedPicture(picture);
            }
            return ExecutionResult.ack();
        } catch (AiProviderException exception) {
            AiTask task = taskMapper.selectById(taskId);
            if ("provider_permission_denied".equals(exception.getCode()) && task != null && task.getModelId() != null) {
                taskService.disableModel(task.getModelId());
            }
            if (task != null && retryable(exception.getCode()) && attempts(task) < maxAttempts
                    && taskService.retry(taskId, workerId, exception.getCode(), exception.getMessage(),
                    retryDelay(attempts(task)))) {
                return ExecutionResult.retry(retryDelay(attempts(task)), exception.getCode());
            }
            String code = "provider_network_error".equals(exception.getCode())
                    ? "provider_outcome_unknown" : exception.getCode();
            taskService.fail(taskId, workerId, code, exception.getMessage());
            return ExecutionResult.ack();
        } catch (RuntimeException exception) {
            taskService.fail(taskId, workerId, "result_persistence_failed", "AI 结果保存失败");
            return ExecutionResult.ack();
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

    private String providerUrl(Picture picture) {
        if (picture == null) return null;
        if (picture.getObjectKey() != null && !picture.getObjectKey().isBlank()) return storage.temporaryUrl(picture.getObjectKey());
        return picture.getUrl();
    }

    public AiTask markRunning(long taskId, String workerId) {
        return transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            if (taskMapper.claimForExecution(taskId, workerId, now, now.plusSeconds(leaseSeconds)) != 1) return null;
            return taskMapper.selectById(taskId);
        });
    }

    private void renewLease(long taskId, String workerId) {
        LocalDateTime leaseUntil = LocalDateTime.now().plusSeconds(leaseSeconds);
        if (taskMapper.renewLease(taskId, workerId, leaseUntil) != 1) {
            throw new IllegalStateException("AI task execution lease was lost");
        }
    }

    public boolean complete(long taskId, String workerId, AiProviderResult result, long pictureId) {
        Boolean completed = transactionTemplate.execute(status -> {
            AiTask task = taskMapper.selectOne(new LambdaQueryWrapper<AiTask>()
                    .eq(AiTask::getId, taskId).last("FOR UPDATE"));
            if (task == null || !"running".equals(task.getStatus())
                    || !java.util.Objects.equals(workerId, task.getWorkerId())) return false;
            task.setStatus("succeeded");
            task.setProviderTaskId(result.providerTaskId());
            task.setProviderRequestId(result.providerRequestId());
            task.setResultPictureId(pictureId);
            taskService.settleQuota(task);
            task.setFinishTime(LocalDateTime.now());
            task.setWorkerId(null);
            task.setLeaseUntil(null);
            taskMapper.updateById(task);
            return true;
        });
        return Boolean.TRUE.equals(completed);
    }

    private static boolean retryable(String code) {
        return "provider_rate_limited".equals(code) || "provider_timeout".equals(code)
                || (code != null && code.startsWith("provider_http_5"));
    }

    private static int attempts(AiTask task) {
        return task == null || task.getAttemptCount() == null ? 0 : task.getAttemptCount();
    }

    private static int retryDelay(int attempts) {
        return attempts <= 1 ? 5 : attempts == 2 ? 30 : 120;
    }

    private static int retryBucket(long remainingSeconds) {
        return remainingSeconds <= 5 ? 5 : remainingSeconds <= 30 ? 30 : 120;
    }

    private static boolean terminal(String status) {
        return "succeeded".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }

    public record ExecutionResult(boolean retry, int delaySeconds, String reason) {
        public static ExecutionResult ack() {
            return new ExecutionResult(false, 0, null);
        }

        public static ExecutionResult retry(int delaySeconds, String reason) {
            return new ExecutionResult(true, delaySeconds, reason);
        }
    }
}
