package com.teacup.teacuppicturebackend.api.aliyunai.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 查询扩图任务响应类
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GetOutPaintingTaskResponse {

    
    private String requestId;

    
    private Output output;

    
    @Data
    public static class Output {

        
        private String taskId;

        
        private String taskStatus;

        
        private String submitTime;

        
        private String scheduledTime;

        
        private String endTime;

        
        private String outputImageUrl;

        
        private String code;

        
        private String message;

        
        private TaskMetrics taskMetrics;
    }

    
    @Data
    public static class TaskMetrics {

        
        private Integer total;

        
        private Integer succeeded;

        
        private Integer failed;
    }
}
