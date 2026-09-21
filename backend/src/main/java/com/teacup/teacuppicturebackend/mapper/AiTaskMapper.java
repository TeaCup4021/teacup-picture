package com.teacup.teacuppicturebackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.teacup.teacuppicturebackend.model.entity.AiTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface AiTaskMapper extends BaseMapper<AiTask> {
    @Update("UPDATE ai_task SET status = 'running', invocationStarted = 1, "
            + "attemptCount = attemptCount + 1, executionToken = executionToken + 1, "
            + "workerId = #{workerId}, leaseUntil = #{leaseUntil}, "
            + "startTime = COALESCE(startTime, #{now}) "
            + "WHERE id = #{taskId} AND attemptCount < #{maxAttempts} "
            + "AND ((status = 'queued' AND (nextAttemptAt IS NULL OR nextAttemptAt <= #{now})) "
            + "OR (status = 'running' AND leaseUntil IS NOT NULL AND leaseUntil <= #{now}))")
    int claimForExecution(@Param("taskId") long taskId, @Param("workerId") String workerId,
                          @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil,
                          @Param("maxAttempts") int maxAttempts);

    @Update("UPDATE ai_task SET leaseUntil = #{leaseUntil} "
            + "WHERE id = #{taskId} AND status = 'running' "
            + "AND workerId = #{workerId} AND executionToken = #{executionToken}")
    int renewLease(@Param("taskId") long taskId, @Param("workerId") String workerId,
                   @Param("executionToken") long executionToken,
                   @Param("leaseUntil") LocalDateTime leaseUntil);

    /**
     * 按实例前缀批量续租本实例持有的全部执行中任务。
     * workerId 形如「实例UUID:线程名」，实例 UUID 固定 36 字符，前缀匹配可走 idx_ai_task_worker。
     */
    @Update("UPDATE ai_task SET leaseUntil = #{leaseUntil} "
            + "WHERE status = 'running' AND workerId LIKE CONCAT(#{instanceId}, ':%')")
    int renewLeasesByInstance(@Param("instanceId") String instanceId,
                              @Param("leaseUntil") LocalDateTime leaseUntil);
}
