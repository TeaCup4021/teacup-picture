package com.teacup.teacuppicturebackend.model.dto.file;

import lombok.Data;

@Data
public class UploadPictureResult {  
  
      
    private String url;

    private String thumbnailUrl;
      
    private String picName;  
  
      
    private Long picSize;  
  
      
    private int picWidth;  
  
      
    private int picHeight;  
  
      
    private Double picScale;  
  
      
    private String picFormat;

    private String picColor;
  
}
