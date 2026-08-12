package com.teacup.teacuppicturebackend.model.dto.space;

import lombok.Data;

import java.io.Serializable;

@Data
public class SpaceAddRequest implements Serializable {

    
    private String spaceName;

    
    private Integer spaceLevel;

    private Integer spaceType;
    private static final long serialVersionUID = 1L;
}