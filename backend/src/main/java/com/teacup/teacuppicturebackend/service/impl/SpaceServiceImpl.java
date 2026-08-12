package com.teacup.teacuppicturebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.teacup.teacuppicturebackend.exception.BusinessException;
import com.teacup.teacuppicturebackend.exception.ErrorCode;
import com.teacup.teacuppicturebackend.exception.ThrowUtils;
import com.teacup.teacuppicturebackend.mapper.SpaceMapper;
import com.teacup.teacuppicturebackend.mapper.SpaceUserMapper;
import com.teacup.teacuppicturebackend.model.dto.picture.PictureEditByBatchRequest;
import com.teacup.teacuppicturebackend.model.dto.picture.PictureEditRequest;
import com.teacup.teacuppicturebackend.model.dto.space.SpaceAddRequest;
import com.teacup.teacuppicturebackend.model.dto.space.SpaceQueryRequest;
import com.teacup.teacuppicturebackend.model.entity.Picture;
import com.teacup.teacuppicturebackend.model.entity.SpaceUser;
import com.teacup.teacuppicturebackend.model.entity.User;
import com.teacup.teacuppicturebackend.model.enums.SpaceLevelEnum;
import com.teacup.teacuppicturebackend.model.enums.SpaceRoleEnum;
import com.teacup.teacuppicturebackend.model.enums.SpaceTypeEnum;
import com.teacup.teacuppicturebackend.model.enums.UserRoleEnum;
import com.teacup.teacuppicturebackend.model.vo.SpaceVO;
import com.teacup.teacuppicturebackend.service.ISpaceUserService;
import com.teacup.teacuppicturebackend.service.SpaceService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.teacup.teacuppicturebackend.service.UserService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import com.teacup.teacuppicturebackend.model.entity.Space;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.locks.Lock;
import java.util.stream.Collectors;

/**
 * <p>
 * 空间 服务实现类
 * </p>
 *
 * @author wolves
 * @since 2025-09-27
 */
@Service
public class SpaceServiceImpl extends ServiceImpl<SpaceMapper, Space> implements SpaceService {



    @Resource
    private UserService userService;

    //事务管理工具类
    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private SpaceUserMapper spaceUserMapper;


    /**
     * 校验数据
     * @param space
     * @param add
     */
    @Override
    public void validSpace(Space space, boolean add) {
        ThrowUtils.throwIf(space == null, ErrorCode.PARAMS_ERROR);

        Integer spaceType = space.getSpaceType();
        SpaceTypeEnum enumByValue = SpaceTypeEnum.getEnumByValue(spaceType);
        if(add){

            ThrowUtils.throwIf(spaceType==null,ErrorCode.PARAMS_ERROR);

        }
        ThrowUtils.throwIf(spaceType!=null&&enumByValue==null,ErrorCode.PARAMS_ERROR,"未正确输入空间类型");

        String spaceName = space.getSpaceName();
        Integer spaceLevel = space.getSpaceLevel();
        SpaceLevelEnum spaceLevelEnum = SpaceLevelEnum.getEnumByValue(spaceLevel);

        if (add) {
            if (StrUtil.isBlank(spaceName)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间名称不能为空");
            }
            if (spaceLevel == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间级别不能为空");
            }
        }

        if (spaceLevel != null && spaceLevelEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间级别不存在");
        }
        if (StrUtil.isNotBlank(spaceName) && spaceName.length() > 30) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间名称过长");
        }
    }

    /**
     * 根据空间级别填充限额数据
     * @param space
     */
    @Override
    public void fillSpaceBySpaceLevel(Space space) {

        Integer spaceLevel = space.getSpaceLevel();
        SpaceLevelEnum enumByValue = SpaceLevelEnum.getEnumByValue(spaceLevel);

        if(enumByValue!=null){
            if(space.getMaxCount()==null) {
                space.setMaxCount(enumByValue.getMaxCount());
            }
            if(space.getMaxSize()==null){
                space.setMaxSize(enumByValue.getMaxSize());
            }
        }

    }

    /**
     * 增加空间
     * @param spaceAddRequest
     * @param loginUser
     * @return
     */
    @Override
    public long addSpace(SpaceAddRequest spaceAddRequest, User loginUser) {

        //创建空间时为空间类型指定默认值
        if (StrUtil.isBlank(spaceAddRequest.getSpaceName())) {
            spaceAddRequest.setSpaceName("默认空间");
        }
        if (spaceAddRequest.getSpaceLevel() == null) {
            spaceAddRequest.setSpaceLevel(SpaceLevelEnum.COMMON.getValue());
        }
        if (spaceAddRequest.getSpaceType() == null) {
            spaceAddRequest.setSpaceType(SpaceTypeEnum.PRIVATE.getValue());
        }
        //1.填充默认参数值
        //转换实体类和DTO
        Space space=new Space();
        BeanUtils.copyProperties(spaceAddRequest,space);
        this.fillSpaceBySpaceLevel(space);
        //填充容量和大小
        Integer spaceLevel = space.getSpaceLevel();
        if(spaceLevel==null){
            space.setSpaceLevel(spaceAddRequest.getSpaceLevel());
        }
        if(space.getMaxSize()==null){
            space.setMaxSize(SpaceLevelEnum.getEnumByValue(spaceLevel).getMaxSize());
        }
        if(space.getMaxCount()==null){
            space.setMaxCount(SpaceLevelEnum.getEnumByValue(spaceLevel).getMaxCount());
        }
        //2.校验参数
        validSpace(space, true);
        //3.校验权限，非管理员只能创建普通级别的空间
        Long userId = loginUser.getId();
        space.setUserId(userId);
        if (SpaceLevelEnum.COMMON.getValue() != spaceAddRequest.getSpaceLevel() && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限创建指定级别的空间");
        }
        //4.控制同一用户只能创建一个私有空间
        String lock = String.valueOf(userId).intern();
        synchronized (lock){
            Long newSpaceId = transactionTemplate.execute(status -> {
                //判断是否已有空间
                boolean exists = this.lambdaQuery()
                        .eq(Space::getUserId, userId)
                        .eq(Space::getSpaceType,spaceAddRequest.getSpaceType())
                        .exists();
                //如果已有则不能创建
                if (exists) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, "每个用户仅能创建一个私有或团队空间");
                }
                //创建
                if(SpaceTypeEnum.TEAM.getValue()==spaceAddRequest.getSpaceType()){



                    SpaceUser spaceUser=new SpaceUser();
                    spaceUser.setSpaceId(space.getId());
                    spaceUser.setUserId(userId);
                    spaceUser.setSpaceRole(SpaceRoleEnum.ADMIN.getValue());
                    boolean save = spaceUserMapper.save(spaceUser);
                    ThrowUtils.throwIf(!save,ErrorCode.OPERATION_ERROR,"创建团队空间失败");
                    return space.getId();
                }else {

                    boolean save = this.save(space);
                    ThrowUtils.throwIf(!save, ErrorCode.OPERATION_ERROR, "创建私有空间失败");
                    //返回新写入的数据id
                    return space.getId();
                }
            });

            return Optional.ofNullable(newSpaceId).orElse(-1L);
        }


    }

    @Override
    public SpaceVO getSpaceVO(Space space, HttpServletRequest request) {


        SpaceVO spaceVO=new SpaceVO();
        BeanUtils.copyProperties(space,spaceVO);

        ThrowUtils.throwIf(spaceVO==null,ErrorCode.OPERATION_ERROR,"获取vo对象失败");

        return spaceVO;
    }

    @Override
    public Wrapper<Space> getQueryWrapper(SpaceQueryRequest spaceQueryRequest) {
        QueryWrapper<Space> queryWrapper = new QueryWrapper<>();
        if (spaceQueryRequest == null) {
            return queryWrapper;
        }


        // 从对象中取值
        Long id = spaceQueryRequest.getId();
        Long userId = spaceQueryRequest.getUserId();
        String spaceName = spaceQueryRequest.getSpaceName();
        Integer spaceLevel = spaceQueryRequest.getSpaceLevel();
        Integer spaceType = spaceQueryRequest.getSpaceType();
        String sortField = spaceQueryRequest.getSortField();
        String sortOrder = spaceQueryRequest.getSortOrder();
        // 拼接查询条件
        queryWrapper.eq(ObjUtil.isNotEmpty(id), "id", id);
        queryWrapper.eq(ObjUtil.isNotEmpty(userId), "userId", userId);
        queryWrapper.like(StrUtil.isNotBlank(spaceName), "spaceName", spaceName);
        queryWrapper.eq(ObjUtil.isNotEmpty(spaceLevel), "spaceLevel", spaceLevel);
        queryWrapper.eq(ObjUtil.isNotEmpty(spaceType), "spaceType", spaceType);
        // 排序
        queryWrapper.orderBy(StrUtil.isNotEmpty(sortField), sortOrder.equals("ascend"), sortField);
        return queryWrapper;
    }

    @Override
    public Page<SpaceVO> getSpaceVOPage(Page<Space> spacePage, HttpServletRequest request) {
        List<Space> spaceList = spacePage.getRecords();
        Page<SpaceVO> spaceVOPage = new Page<>(spacePage.getCurrent(), spacePage.getSize(), spacePage.getTotal());
        if (CollUtil.isEmpty(spaceList)) {
            return spaceVOPage;
        }
        // 对象列表 => 封装对象列表
        List<SpaceVO> spaceVOList = spaceList.stream()
                .map(SpaceVO::objToVo)
                .collect(Collectors.toList());
        // 1. 关联查询用户信息
        // 1,2,3,4
        Set<Long> userIdSet = spaceList.stream().map(Space::getUserId).collect(Collectors.toSet());
        // 1 => user1, 2 => user2
        Map<Long, List<User>> userIdUserListMap = userService.listByIds(userIdSet).stream()
                .collect(Collectors.groupingBy(User::getId));
        // 2. 填充信息
        spaceVOList.forEach(spaceVO -> {
            Long userId = spaceVO.getUserId();
            User user = null;
            if (userIdUserListMap.containsKey(userId)) {
                user = userIdUserListMap.get(userId).get(0);
            }
            spaceVO.setUser(userService.getUserVO(user));
        });
        spaceVOPage.setRecords(spaceVOList);
        return spaceVOPage;
    }
    @Override
    public void checkSpaceAuth(User loginUser, Space oldSpace) {
        Long oldSpaceUserId = oldSpace.getUserId();

        if (!oldSpaceUserId.equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR,"您没有权限");
        }

    }






}
