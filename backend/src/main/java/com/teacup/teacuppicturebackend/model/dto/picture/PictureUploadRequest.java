package com.teacup.teacuppicturebackend.model.dto.picture;

import lombok.Data;

import java.io.Serializable;

@Data
public class PictureUploadRequest implements Serializable {


    private Long id;

    private String fileUrl;

    private Long spaceId;

    private String picName;

    private static final long serialVersionUID = 1L;
}
