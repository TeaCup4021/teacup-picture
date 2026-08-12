package com.teacup.teacuppicturebackend.service.impl;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.ObjectUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.teacup.teacuppicturebackend.auth.StpKit;
import com.teacup.teacuppicturebackend.constant.UserConstant;
import com.teacup.teacuppicturebackend.exception.BusinessException;
import com.teacup.teacuppicturebackend.exception.ErrorCode;
import com.teacup.teacuppicturebackend.model.dto.user.UserQueryRequest;
import com.teacup.teacuppicturebackend.model.dto.user.UserRegisterRequest;
import com.teacup.teacuppicturebackend.model.entity.User;
import com.teacup.teacuppicturebackend.model.enums.UserRoleEnum;
import com.teacup.teacuppicturebackend.model.vo.LoginUserVO;
import com.teacup.teacuppicturebackend.model.vo.UserVO;
import com.teacup.teacuppicturebackend.service.UserService;
import com.teacup.teacuppicturebackend.mapper.UserMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.DigestUtils;

import javax.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.teacup.teacuppicturebackend.constant.UserConstant.ADMIN_ROLE;
import static com.teacup.teacuppicturebackend.constant.UserConstant.USER_LOGIN_STATE;

/**
* @author wolves
* @description 针对表【user(用户)】的数据库操作Service实现
* @createDate 2025-09-14 21:20:40
*/
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
    implements UserService{

    @Override
    public long userRegister(UserRegisterRequest userRegisterRequest) {

        //1.校验
        String userAccount = userRegisterRequest.getUserAccount();
        String userPassword = userRegisterRequest.getUserPassword();
        String checkPassword = userRegisterRequest.getCheckPassword();
        if (StrUtil.hasBlank(userAccount, userPassword, checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }

        if (userAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号过短");
        }

        if (userPassword.length() < 6 || checkPassword.length() < 6) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码过短");
        }

        if (!userPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }

        //2.检查数据库中是否有重复的数据
        QueryWrapper<User> queryWrapper=new QueryWrapper<>();
        queryWrapper.eq("userAccount",userAccount);
        long amount=baseMapper.selectCount(queryWrapper);
        if(amount>0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"账号重复");
        }
        //3.加密
        String encryptPassword = getEncryptPassword(userPassword);
        //4.插入数据
        User user=new User();
        user.setUserAccount(userAccount);
        user.setUserPassword(encryptPassword);
        user.setUserName("无");
        user.setUserRole(UserRoleEnum.USER.getValue());
        boolean save = this.save(user);
        if(!save){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,"注册失败，数据库错误");
        }

        return user.getId();
    }

    @Override
    public LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request) {

        //1.校验
        if (StrUtil.hasBlank(userAccount, userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }

        if (userAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号过短");
        }

        if (userPassword.length() < 8 ) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码过短");
        }

        //2.加密
        String encryptPassword = getEncryptPassword(userPassword);
        //查询用户是否存在
        QueryWrapper<User> queryWrapper=new QueryWrapper<>();
        queryWrapper.eq("userAccount",userAccount);
        queryWrapper.eq("userPassword",encryptPassword);
        User user = this.baseMapper.selectOne(queryWrapper);
        //用户不存在
        if(user==null){
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,"用户不存在或密码错误");
        }
        //3.记录用户的登录态
        request.getSession().setAttribute(USER_LOGIN_STATE,user);

        StpKit.SPACE.login(user.getId());
        StpKit.SPACE.getSession().set(USER_LOGIN_STATE, user);


        return this.getLoginUserVO(user);


    }

    @Override
    public User getLoginUser(HttpServletRequest request) {

        //先判断是否已经登录
        Object userLoginState = request.getSession().getAttribute(USER_LOGIN_STATE);
        User user=(User) userLoginState;
        if(user==null||user.getId()==null){
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,"用户未登录");
        }
        //从数据库中查询用户信息
        User userById = this.getById(user.getId());
        return userById;
    }

    //密码加盐
    public String getEncryptPassword(String userPassword) {
        //盐值，用来混淆密码
        final String salt="teacup";
        return DigestUtils.md5DigestAsHex((salt+userPassword).getBytes());
    }
    public LoginUserVO getLoginUserVO(User user){
        if(user==null){
            return null;
        }
        LoginUserVO vo=new LoginUserVO();
        BeanUtil.copyProperties(user,vo);
        return vo;
    }

    @Override
    public boolean userLogout(HttpServletRequest request) {
        //先判断是否为登录状态
        User loginUser = getLoginUser(request);
        if(loginUser==null){
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"当前未登录");
        }
        //移除登录状态
        request.getSession().removeAttribute(USER_LOGIN_STATE);
        return true;
    }

    @Override
    public UserVO getUserVO(User user){
        if(user==null){
            return null;
        }

        UserVO userVO=new UserVO();
        BeanUtil.copyProperties(user,userVO);
        return userVO;

    }

    @Override
    public List<UserVO> getUserVOList(List<User> userList){
        if(CollectionUtils.isEmpty(userList)){
            return new ArrayList<>();
        }

        return userList.stream().map(this::getUserVO).collect(Collectors.toList());
    }

    @Override
    public QueryWrapper<User> getQueryWrapper(UserQueryRequest userQueryRequest) {
        if (userQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }


        Long id = userQueryRequest.getId();
        String userAccount = userQueryRequest.getUserAccount();
        String userName = userQueryRequest.getUserName();
        String userProfile = userQueryRequest.getUserProfile();
        String userRole = userQueryRequest.getUserRole();
        String sortField = userQueryRequest.getSortField();
        String sortOrder = userQueryRequest.getSortOrder();

        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(ObjectUtils.isNotEmpty(id), "id", id);
        queryWrapper.like(StrUtil.isNotBlank(userAccount), "userAccount", userAccount);
        queryWrapper.like(StrUtil.isNotBlank(userName), "userName", userName);
        queryWrapper.like(StrUtil.isNotBlank(userProfile), "userProfile", userProfile);
        queryWrapper.eq(StrUtil.isNotBlank(userRole), "userRole", userRole);
        queryWrapper.orderBy(StrUtil.isNotEmpty(sortField), sortOrder.equals("ascend"), sortField);

        return queryWrapper;
    }

    @Override
    public boolean isAdmin(User user) {

        if(user==null){
            return false;
        }

        if(!UserRoleEnum.ADMIN.getValue().equals(user.getUserRole())){
            return false;
        }

        return true;
    }


}




