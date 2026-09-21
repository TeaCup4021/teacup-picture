package com.teacup.teacuppicturebackend.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.teacup.teacuppicturebackend.mapper.AiTaskMapper;
import com.teacup.teacuppicturebackend.mapper.AiTaskOutboxMapper;
import com.teacup.teacuppicturebackend.model.entity.AiTask;
import com.teacup.teacuppicturebackend.model.entity.AiTaskOutbox;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AiTaskOutboxService {
    private final AiTaskOutboxMapper outboxMapper;
    private final AiTaskMapper taskMapper;

    public AiTaskOutboxService(AiTaskOutboxMapper outboxMapper, AiTaskMapper taskMapper) {
        this.outboxMapper = outboxMapper;
        this.taskMapper = taskMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void enqueue(long taskId) {
        AiTaskOutbox outbox = new AiTaskOutbox();
        outbox.setEventId(UUID.randomUUID().toString());
        outbox.setTaskId(taskId);
        outbox.setStatus("pending");
        outbox.setAttemptCount(0);
        outbox.setNextAttemptAt(LocalDateTime.now());
        outboxMapper.insert(outbox);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean recoverExpiredTask(long taskId, LocalDateTime now, LocalDateTime legacyDeadline) {
        AiTask task = taskMapper.selectOne(new LambdaQueryWrapper<AiTask>()
                .eq(AiTask::getId, taskId).last("FOR UPDATE"));
        if (task == null || !"running".equals(task.getStatus())) return false;
        boolean expiredLease = task.getLeaseUntil() != null && !task.getLeaseUntil().isAfter(now);
        boolean expiredLegacyTask = task.getLeaseUntil() == null && task.getStartTime() != null
                && !task.getStartTime().isAfter(legacyDeadline);
        if (!expiredLease && !expiredLegacyTask) return false;
        task.setStatus("queued");
        task.setWorkerId(null);
        task.setLeaseUntil(null);
        task.setNextAttemptAt(now);
        task.setFailureCode("worker_lease_expired");
        task.setFailureReason("AI worker lease expired; task was queued for recovery");
        taskMapper.updateById(task);
        enqueue(taskId);
        return true;
    }
}
