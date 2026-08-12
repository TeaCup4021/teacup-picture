package com.teacup.teacuppicturebackend.auth.model;

import com.teacup.teacuppicturebackend.manager.auth.model.SpaceUserPermission;
import com.teacup.teacuppicturebackend.manager.auth.model.SpaceUserRole;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 权限配置类
 */
@Data
 public class SpaceUserAuthConfig implements Serializable {

    private List<SpaceUserPermission> permissions;
    private List<SpaceUserRole> roles;
    private static final long serialVersionUID = 1L;
 }