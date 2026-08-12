package com.teacup.teacuppicturebackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.teacup.teacuppicturebackend.model.dto.user.UserQueryRequest;
import com.teacup.teacuppicturebackend.model.dto.user.UserRegisterRequest;
import com.baomidou.mybatisplus.extension.service.IService;
import com.teacup.teacuppicturebackend.model.entity.User;
import com.teacup.teacuppicturebackend.model.vo.LoginUserVO;
import com.teacup.teacuppicturebackend.model.vo.UserVO;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
* @author wolves
* @description 针对表【user(用户)】的数据库操作Service
* @createDate 2025-09-14 21:20:40
*/
public interface UserService extends IService<User> {


    long userRegister(UserRegisterRequest userRegisterRequest);

    LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request);

    /* 获取当前登录用户 */
    User getLoginUser(HttpServletRequest request);

    LoginUserVO getLoginUserVO(User loginUser);

    /* 用户注销（移除登录态） */
    boolean userLogout(HttpServletRequest request);

    String getEncryptPassword(String password);

    UserVO getUserVO(User user);

    List<UserVO> getUserVOList(List<User> userList);

    //将请求转换为wrapper
    QueryWrapper<User> getQueryWrapper(UserQueryRequest userQueryRequest);

    //判断当前用户是否为管理员
    boolean isAdmin(User user);
}
