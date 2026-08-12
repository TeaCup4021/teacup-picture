package com.teacup.teacuppicturebackend.auth.model;

import lombok.Data;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.List;

/**
 * 空间成员角色
 */

@Component
@Data
 public class SpaceUserRole implements Serializable {
    private String key;
    private String name;
    private List<String> permissions;
    private String description;
    private static final long serialVersionUID = 1L;
 }