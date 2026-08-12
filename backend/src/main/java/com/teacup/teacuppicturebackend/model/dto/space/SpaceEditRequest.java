package com.teacup.teacuppicturebackend.model.dto.space;

import lombok.Data;

import java.io.Serializable;

@Data
public class SpaceEditRequest implements Serializable {

    
    private Long id;

    
    private String spaceName;

    private static final long serialVersionUID = 1L;
}