package com.teacup.teacuppicturebackend.auth.model;

import lombok.Data;

import java.io.Serializable;

/**
 * 空间成员权限
 */
@Data
 public class SpaceUserPermission implements Serializable {
    private String key;
    private String name;
    private String description;
    private static final long serialVersionUID = 1L;
 }