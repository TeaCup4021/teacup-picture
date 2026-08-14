package com.teacup.teacuppicturebackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.teacup.teacuppicturebackend.model.entity.AiQuotaUsage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

public interface AiQuotaUsageMapper extends BaseMapper<AiQuotaUsage> {
    @Insert("INSERT IGNORE INTO ai_quota_usage(userId, usageDate, taskType, usedCount, reservedCount) VALUES(#{userId}, #{usageDate}, #{taskType}, 0, 0)")
    int ensureRow(@Param("userId") long userId, @Param("usageDate") LocalDate usageDate,
                  @Param("taskType") String taskType);

    @Select("SELECT * FROM ai_quota_usage WHERE userId = #{userId} AND usageDate = #{usageDate} AND taskType = #{taskType} FOR UPDATE")
    AiQuotaUsage selectForUpdate(@Param("userId") long userId, @Param("usageDate") LocalDate usageDate,
                                 @Param("taskType") String taskType);
}
