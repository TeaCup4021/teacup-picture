package com.teacup.teacuppicturebackend.model.dto.picture;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class PictureEditByBatchRequest implements Serializable {

    
    private List<Long> pictureIdList;

    
    private Long spaceId;

    
    private String category;

    
    private List<String> tags;

    private static final long serialVersionUID = 1L;
}