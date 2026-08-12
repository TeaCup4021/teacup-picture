package com.teacup.teacuppicturebackend.model.dto.picture;

import lombok.Data;

import java.io.Serializable;

@Data
public class PictureUploadByBatchRequest implements Serializable {

    /**
     * 图片 搜索词
     * */
    private String searchText;

    /**
     * 抓取数据
     */
    private String namePrefix;

    /**
     *
     */
    private Integer count = 10;

    private static final long serialVersionUID=1L;
}