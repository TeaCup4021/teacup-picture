package com.teacup.teacuppicturebackend.model.dto.space.analyze;

import lombok.Data;

import java.io.Serializable;

@Data
public class SpaceRankAnalyzeRequest implements Serializable {

    
    private Integer topN = 10;

    private static final long serialVersionUID = 1L;
}