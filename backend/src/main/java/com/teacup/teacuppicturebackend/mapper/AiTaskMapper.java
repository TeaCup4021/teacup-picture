package com.teacup.teacuppicturebackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.teacup.teacuppicturebackend.model.entity.AiTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface AiTaskMapper extends BaseMapper<AiTask> {
    @Update("UPDATE ai_task SET status = 'running', invocationStarted = 1, startTime = #{startTime} "
            + "WHERE id = #{taskId} AND status = 'queued'")
    int claimQueued(@Param("taskId") long taskId, @Param("startTime") LocalDateTime startTime);
}
