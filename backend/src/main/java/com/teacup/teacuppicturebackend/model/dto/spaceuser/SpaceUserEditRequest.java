package com.teacup.teacuppicturebackend.model.dto.spaceuser;

import lombok.Data;

import java.io.Serializable;

/**
 * 编辑空间成员
 */
@Data
 public class SpaceUserEditRequest implements Serializable {
    private Long id;
    private String spaceRole;
    private static final long serialVersionUID = 1L;
 }