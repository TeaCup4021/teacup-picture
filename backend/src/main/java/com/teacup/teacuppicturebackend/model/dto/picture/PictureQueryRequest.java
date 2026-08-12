package com.teacup.teacuppicturebackend.model.dto.picture;

import com.teacup.teacuppicturebackend.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
public class PictureQueryRequest extends PageRequest implements Serializable {
  
      
    private Long id;  

    private String name;  

    private String introduction;  

    private String category;  

    private List<String> tags;
  
      
    private Long picSize;  
  
      
    private Integer picWidth;  
  
      
    private Integer picHeight;  
  
      
    private Double picScale;  
  
      
    private String picFormat;


    /**
     * 搜索文本
     */
    private String searchText;  
  
      
    private Long userId;

    private Long spaceId;


    private boolean nullSpaceId;


    /**
     * 审核状态：0待审核，1通过，2拒绝
     */
    private Integer reviewStatus;

    /**
     * 审核信息
     */
    private String reviewMessage;

    /**
     * 审核人ID
     */
    private Long reviewerId;

    /**
     * 审核时间
     */
    private Date reviewTime;

    /**
     * 开始编辑时间
     */
    private Date startEditTime;

    /**
     * 结束编辑时间
     */
    private Date endEditTime;
  
    private static final long serialVersionUID = 1L;  
}