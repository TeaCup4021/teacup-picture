package com.teacup.teacuppicturebackend.model.vo.space.analyze;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SpaceCategoryAnalyzeResponse implements Serializable {

    
    private String category;

    
    private Long count;

    
    private Long totalSize;

    private static final long serialVersionUID = 1L;
}