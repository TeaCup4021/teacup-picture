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

/**
 * 配额对账兜底。
 * 所有归还路径（结算 / 归还预占）都是事件驱动的，进程被强制杀死或消息进入死信后，
 * 预占会永久悬挂。本服务是唯一不依赖事件、能在这种情况下修复状态的手段。
 */
@Slf4j
@Component
public class AiQuotaReconciliationService {
    private final AiTaskMapper taskMapper;
    private final AiTaskService taskService;
    private final long minAgeMinutes;
    private final long zombieAgeMinutes;
    private final int batchSize;

    public AiQuotaReconciliationService(AiTaskMapper taskMapper, AiTaskService taskService,
                                        @Value("${teacup.ai.quota.reconcile-min-age-minutes:360}") long minAgeMinutes,
                                        @Value("${teacup.ai.quota.reconcile-zombie-age-minutes:120}") long zombieAgeMinutes,
                                        @Value("${teacup.ai.quota.reconcile-batch-size:200}") int batchSize) {
        this.taskMapper = taskMapper;
        this.taskService = taskService;
        this.minAgeMinutes = Math.max(1, minAgeMinutes);
        this.zombieAgeMinutes = Math.max(1, zombieAgeMinutes);
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(initialDelayString = "${teacup.ai.quota.reconcile-initial-delay-millis:60000}",
            fixedDelayString = "${teacup.ai.quota.reconcile-millis:600000}")
    public void reconcile() {
        long startedAt = System.currentTimeMillis();
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(minAgeMinutes);
        List<AiTask> candidates;
        try {
            candidates = taskMapper.selectList(new LambdaQueryWrapper<AiTask>()
                    .eq(AiTask::getQuotaSettled, 0)
                    .eq(AiTask::getQuotaRefunded, 0)
                    .lt(AiTask::getCreateTime, deadline)
                    .orderByAsc(AiTask::getId)
                    .last("LIMIT " + batchSize));
        } catch (RuntimeException exception) {
            log.warn("AI quota reconciliation scan failed", exception);
            return;
        }
        int repaired = 0;
        for (AiTask candidate : candidates) {
            try {
                if (taskService.reconcile(candidate.getId(), zombieAgeMinutes)) repaired++;
            } catch (RuntimeException exception) {
                log.warn("AI quota reconciliation failed for taskId={}", candidate.getId(), exception);
            }
        }
        // 修正数长期不为 0 意味着事件驱动链路存在缺陷，应被监控告警捕获。
        log.info("AI quota reconciliation finished: scanned={}, repaired={}, elapsedMs={}",
                candidates.size(), repaired, System.currentTimeMillis() - startedAt);
    }
}
