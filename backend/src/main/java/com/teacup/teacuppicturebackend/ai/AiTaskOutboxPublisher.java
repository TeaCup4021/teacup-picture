package com.teacup.teacuppicturebackend.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.teacup.teacuppicturebackend.mapper.AiTaskOutboxMapper;
import com.teacup.teacuppicturebackend.model.entity.AiTaskOutbox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class AiTaskOutboxPublisher {
    private final AiTaskOutboxMapper outboxMapper;
    private final AiMessagePublisher publisher;
    private final int retentionDays;
    private final String publisherId = UUID.randomUUID().toString();

    public AiTaskOutboxPublisher(AiTaskOutboxMapper outboxMapper, AiMessagePublisher publisher,
                                 @Value("${teacup.ai.worker.outbox-retention-days:7}") int retentionDays) {
        this.outboxMapper = outboxMapper;
        this.publisher = publisher;
        this.retentionDays = Math.max(1, retentionDays);
    }

    @Scheduled(initialDelayString = "${teacup.ai.worker.outbox-poll-millis:1000}",
            fixedDelayString = "${teacup.ai.worker.outbox-poll-millis:1000}")
    public void publishDue() {
        LocalDateTime now = LocalDateTime.now();
        // 不要按 id 排序：该索引以 status 为前导列，不可能产出全局 id 序，
        // ORDER BY id 会让优化器在积压时放弃索引改走主键扫描（实测慢约 200 倍）。
        // 投递不要求 FIFO，取到哪 50 条都可以。
        List<AiTaskOutbox> rows = outboxMapper.selectList(new LambdaQueryWrapper<AiTaskOutbox>()
                .in(AiTaskOutbox::getStatus, "pending", "failed")
                .le(AiTaskOutbox::getNextAttemptAt, now)
                .and(query -> query.isNull(AiTaskOutbox::getLockUntil).or().le(AiTaskOutbox::getLockUntil, now))
                .last("LIMIT 50"));
        rows.forEach(row -> publish(row, now));
    }

    @Scheduled(initialDelayString = "${teacup.ai.worker.outbox-cleanup-millis:3600000}",
            fixedDelayString = "${teacup.ai.worker.outbox-cleanup-millis:3600000}")
    public void cleanupPublished() {
        outboxMapper.delete(new LambdaQueryWrapper<AiTaskOutbox>()
                .eq(AiTaskOutbox::getStatus, "published")
                .lt(AiTaskOutbox::getPublishedAt, LocalDateTime.now().minusDays(retentionDays))
                .last("LIMIT 1000"));
    }

    private void publish(AiTaskOutbox row, LocalDateTime now) {
        if (outboxMapper.claim(row.getId(), publisherId, now, now.plusSeconds(30)) != 1) return;
        try {
            publisher.publishTask(new AiTaskMessage(row.getEventId(), row.getTaskId().toString()));
            row.setStatus("published");
            row.setPublishedAt(LocalDateTime.now());
            row.setLastError(null);
            row.setLockOwner(null);
            row.setLockUntil(null);
            outboxMapper.updateById(row);
        } catch (RuntimeException exception) {
            int attempts = row.getAttemptCount() == null ? 0 : row.getAttemptCount();
            row.setAttemptCount(attempts + 1);
            row.setStatus("failed");
            row.setLastError(trim(exception.getMessage(), 500));
            row.setNextAttemptAt(LocalDateTime.now().plusSeconds(Math.min(60, 1L << Math.min(attempts, 6))));
            row.setLockOwner(null);
            row.setLockUntil(null);
            outboxMapper.updateById(row);
            log.warn("AI task outbox publish failed, outboxId={}, taskId={}", row.getId(), row.getTaskId());
        }
    }

    private static String trim(String value, int max) {
        if (value == null || value.isBlank()) return "unknown";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
