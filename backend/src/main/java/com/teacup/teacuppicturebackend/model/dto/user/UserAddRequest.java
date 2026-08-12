package com.teacup.teacuppicturebackend.model.dto.user;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户添加请求
 */
@Data
public class UserAddRequest implements Serializable {
    
    /**
     * 用户昵称
     */
    private String userName;
    
    /**
     * 账号
     */
    private String userAccount;
    
    /**
     * 用户头像
     */
    private String userAvatar;
    
    /**
     * 用户简介
     */
    private String userProfile;
    
    /**
     * 用户角色：user, admin
     */
    private String userRole;
    
    /**
     * 序列化版本号
     */
    private static final long serialVersionUID = 1L;
}
