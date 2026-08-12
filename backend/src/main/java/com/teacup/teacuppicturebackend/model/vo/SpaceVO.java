package com.teacup.teacuppicturebackend.model.vo;

import com.teacup.teacuppicturebackend.model.entity.Space;
import lombok.Data;
import org.springframework.beans.BeanUtils;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

@Data
public class SpaceVO implements Serializable {
    
    private Long id;

    
    private String spaceName;

    
    private Integer spaceLevel;

    
    private Long maxSize;

    
    private Long maxCount;

    
    private Long totalSize;

    
    private Long totalCount;

    
    private Long userId;

    
    private Date createTime;

    
    private Date editTime;

    
    private Date updateTime;

    
    private UserVO user;

    private Integer spaceType;

    private static final long serialVersionUID = 1L;

    
    public static Space voToObj(SpaceVO spaceVO) {
        if (spaceVO == null) {
            return null;
        }
        Space space = new Space();
        BeanUtils.copyProperties(spaceVO, space);
        return space;
    }

    
    public static SpaceVO objToVo(Space space) {
        if (space == null) {
            return null;
        }
        SpaceVO spaceVO = new SpaceVO();
        BeanUtils.copyProperties(space, spaceVO);
        return spaceVO;
    }

    public void setPermissionList(List<String> permissionList) {
    }
}