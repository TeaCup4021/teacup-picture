package com.teacup.teacuppicturebackend.model.dto.spaceuser;

import lombok.Data;

import java.io.Serializable;

/**
 * 添加空间成员
 */
@Data
 public class SpaceUserAddRequest implements Serializable {
    private Long spaceId;
    private Long userId;
    private String spaceRole;
    private static final long serialVersionUID = 1L;
 }