package com.teacup.teacuppicturebackend.model.dto.user;

import com.teacup.teacuppicturebackend.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 用户查询请求
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserQueryRequest extends PageRequest implements Serializable {
    
    /**
     * id
     */
    private Long id;
    
    /**
     * 用户昵称
     */
    private String userName;
    
    /**
     * 账号
     */
    private String userAccount;
    
    /**
     * 简介
     */
    private String userProfile;
    
    /**
     * 用户角色：user/admin/ban
     */
    private String userRole;
    
    /**
     * 序列化版本号
     */
    private static final long serialVersionUID = 1L;
}
