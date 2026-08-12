package com.teacup.teacuppicturebackend.model.dto.picture;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

@Data
public class PictureReviewRequest implements Serializable {
  
      
    private Long id;


    /**
     * 审核状态：0待审核，1通过，2拒绝
     */
    private Integer reviewStatus;

    /**
     * 审核信息
     */
    private String reviewMessage;

  
    private static final long serialVersionUID = 1L;  
}