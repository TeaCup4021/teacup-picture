package com.teacup.teacuppicturebackend.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.teacup.teacuppicturebackend.mapper.AiTaskMapper;
import com.teacup.teacuppicturebackend.model.entity.AiTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
public class AiTaskRecoveryService {
    private final AiTaskMapper taskMapper;
    private final AiTaskOutboxService outboxService;
    private final long legacyTimeoutMinutes;

    public AiTaskRecoveryService(AiTaskMapper taskMapper, AiTaskOutboxService outboxService,
                                 @Value("${teacup.ai.running-timeout-minutes:15}") long legacyTimeoutMinutes) {
        this.taskMapper = taskMapper;
        this.outboxService = outboxService;
        this.legacyTimeoutMinutes = legacyTimeoutMinutes;
    }

    @Scheduled(initialDelay = 30_000L, fixedDelayString = "${teacup.ai.worker.recovery-poll-millis:30000}")
    public void recoverExpiredLeases() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime legacyDeadline = now.minusMinutes(Math.max(1, legacyTimeoutMinutes));
        List<AiTask> expired = taskMapper.selectList(new LambdaQueryWrapper<AiTask>()
                .eq(AiTask::getStatus, "running")
                .and(query -> query.le(AiTask::getLeaseUntil, now)
                        .or(legacy -> legacy.isNull(AiTask::getLeaseUntil)
                                .le(AiTask::getStartTime, legacyDeadline)))
                .orderByAsc(AiTask::getId).last("LIMIT 50"));
        expired.forEach(task -> {
            if (outboxService.recoverExpiredTask(task.getId(), now, legacyDeadline)) {
                log.warn("Recovered expired AI task lease, taskId={}", task.getId());
            }
        });
    }
}
