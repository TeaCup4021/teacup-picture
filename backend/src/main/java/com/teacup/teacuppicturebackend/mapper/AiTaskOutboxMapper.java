package com.teacup.teacuppicturebackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.teacup.teacuppicturebackend.model.entity.AiTaskOutbox;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface AiTaskOutboxMapper extends BaseMapper<AiTaskOutbox> {
    @Update("UPDATE ai_task_outbox SET lockOwner = #{owner}, lockUntil = #{lockUntil} "
            + "WHERE id = #{id} AND status IN ('pending', 'failed') "
            + "AND nextAttemptAt <= #{now} AND (lockUntil IS NULL OR lockUntil <= #{now})")
    int claim(@Param("id") long id, @Param("owner") String owner,
              @Param("now") LocalDateTime now, @Param("lockUntil") LocalDateTime lockUntil);
}
