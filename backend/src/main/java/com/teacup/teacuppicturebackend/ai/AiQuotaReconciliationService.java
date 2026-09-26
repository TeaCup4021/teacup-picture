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
 * <p>
 * 本服务只有一个年龄门槛（僵尸年龄），同时用于候选筛选与僵尸判定，两者必须取同一个值：
 * 候选门槛大于它，僵尸判定就成了死条件（筛出来的永远超龄）；小于它，又会把仍在正常重试的
 * 任务扫进来。历史上这两个值分别配成 360 与 120 分钟，实际生效的是 360 —— 用户为一笔悬挂
 * 预占最长要等 6 小时，而任务的最长合法生命周期只有约 10 分 35 秒。故收敛为单一配置项，
 * 默认值见 {@code teacup.ai.quota.reconcile-zombie-age-minutes}。
 * <p>
 * 该门槛按任务的创建时间判定，不是按最近一次状态变化时间：抢占失败重投会不断刷新后者，
 * 一旦任务陷入重投循环就永远不满足条件、预占永久悬挂。按创建时间判定能保证任何任务
 * 在创建后 zombieAgeMinutes 内必然被处理一次。
 */
@Slf4j
@Component
public class AiQuotaReconciliationService {
    private final AiTaskMapper taskMapper;
    private final AiTaskService taskService;
    private final long zombieAgeMinutes;
    private final int batchSize;

    public AiQuotaReconciliationService(AiTaskMapper taskMapper, AiTaskService taskService,
                                        @Value("${teacup.ai.quota.reconcile-zombie-age-minutes:11}") long zombieAgeMinutes,
                                        @Value("${teacup.ai.quota.reconcile-batch-size:200}") int batchSize) {
        this.taskMapper = taskMapper;
        this.taskService = taskService;
        this.zombieAgeMinutes = Math.max(1, zombieAgeMinutes);
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(initialDelayString = "${teacup.ai.quota.reconcile-initial-delay-millis:60000}",
            fixedDelayString = "${teacup.ai.quota.reconcile-millis:600000}")
    public void reconcile() {
        long startedAt = System.currentTimeMillis();
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(zombieAgeMinutes);
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
