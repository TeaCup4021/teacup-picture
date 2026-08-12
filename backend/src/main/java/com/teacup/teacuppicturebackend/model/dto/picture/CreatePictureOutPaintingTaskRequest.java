package com.teacup.teacuppicturebackend.model.dto.picture;

import com.teacup.teacuppicturebackend.api.aliyunai.model.CreateOutPaintingTaskRequest;
import lombok.Data;

import java.io.Serializable;

/**
 * AI扩图请求类
 */
@Data
public class CreatePictureOutPaintingTaskRequest implements Serializable {


    /**
     * 图片id
     */
    private Long pictureId;

    /**
     * 扩图参数
     */
    private CreateOutPaintingTaskRequest.Parameters parameters;

    private static final long serialVersionUID = 1L;
}