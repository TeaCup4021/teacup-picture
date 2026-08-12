package com.teacup.teacuppicturebackend.model.dto.space;

import com.teacup.teacuppicturebackend.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

@EqualsAndHashCode(callSuper = true)
@Data
public class SpaceQueryRequest extends PageRequest implements Serializable {

    
    private Long id;

    
    private Long userId;

    
    private String spaceName;

    
    private Integer spaceLevel;

    private Integer spaceType;


    private static final long serialVersionUID = 1L;
}