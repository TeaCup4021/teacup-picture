package com.teacup.teacuppicturebackend.api.aliyunai.model;

import cn.hutool.core.annotation.Alias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 创建扩图任务请求类
 */
@Data
public class CreateOutPaintingTaskRequest implements Serializable {

    
    private String model = "qwen-image-max";

    //输入图像信息
    private Input input;

    //图像处理参数
    private Parameters parameters;

    @Data
    public static class Input {
        
        @Alias("image_url")
        private String imageUrl;
    }

    @Data
    public static class Parameters implements Serializable {
        
        private Integer angle;

        
        @Alias("output_ratio")
        private String outputRatio;

        
        @Alias("x_scale")
        @JsonProperty("xScale")
        private Float xScale;

        
        @Alias("y_scale")
        @JsonProperty("yScale")
        private Float yScale;

        
        @Alias("top_offset")
        private Integer topOffset;

        
        @Alias("bottom_offset")
        private Integer bottomOffset;

        
        @Alias("left_offset")
        private Integer leftOffset;

        
        @Alias("right_offset")
        private Integer rightOffset;

        
        @Alias("best_quality")
        private Boolean bestQuality;

        
        @Alias("limit_image_size")
        private Boolean limitImageSize;

        
        @Alias("add_watermark")
        private Boolean addWatermark = false;
    }
}
