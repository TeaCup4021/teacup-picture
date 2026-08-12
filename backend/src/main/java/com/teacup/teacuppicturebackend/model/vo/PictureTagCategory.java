package com.teacup.teacuppicturebackend.model.vo;

import lombok.Data;

import java.util.List;

//图片标签分类
@Data
public class PictureTagCategory {

    //
    private List<String> tagList;

    //分类列表
    private List<String> categoryList;

}
