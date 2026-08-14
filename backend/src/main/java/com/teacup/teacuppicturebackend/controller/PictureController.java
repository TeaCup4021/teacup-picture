package com.teacup.teacuppicturebackend.controller;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import com.teacup.teacuppicturebackend.annotation.AuthCheck;
import com.teacup.teacuppicturebackend.auth.StpInterfaceImpl;
import com.teacup.teacuppicturebackend.auth.StpKit;
import com.teacup.teacuppicturebackend.auth.annotation.SaSpaceCheckPermission;
import com.teacup.teacuppicturebackend.auth.model.SpaceUserAuthManager;
import com.teacup.teacuppicturebackend.auth.model.SpaceUserPermissionConstant;
import com.teacup.teacuppicturebackend.common.BaseResponse;
import com.teacup.teacuppicturebackend.common.DeleteRequest;
import com.teacup.teacuppicturebackend.common.ResultUtils;
import com.teacup.teacuppicturebackend.constant.UserConstant;
import com.teacup.teacuppicturebackend.exception.BusinessException;
import com.teacup.teacuppicturebackend.exception.ErrorCode;
import com.teacup.teacuppicturebackend.exception.ThrowUtils;
import com.teacup.teacuppicturebackend.model.dto.picture.*;
import com.teacup.teacuppicturebackend.model.entity.Picture;
import com.teacup.teacuppicturebackend.model.entity.Space;
import com.teacup.teacuppicturebackend.model.entity.SpaceLevel;
import com.teacup.teacuppicturebackend.model.enums.PictureReviewStatusEnum;
import com.teacup.teacuppicturebackend.model.enums.SpaceLevelEnum;
import com.teacup.teacuppicturebackend.model.vo.PictureTagCategory;
import com.teacup.teacuppicturebackend.model.vo.PictureVO;
import com.teacup.teacuppicturebackend.service.PictureService;
import com.teacup.teacuppicturebackend.service.SpaceService;
import com.teacup.teacuppicturebackend.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.teacup.teacuppicturebackend.model.entity.User;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/picture")
public class PictureController {


    @Resource
    private UserService userService;


    @Resource
    private PictureService pictureService;

    @Resource
    private SpaceService spaceService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource(name = "pictureLocalCache")
    private Cache<String, String> localCache;
    @Resource
    private SpaceUserAuthManager spaceUserAuthManager;


    /**
     * 通过url上传图片
     * @param
     * @param pictureUploadRequest
     * @param request
     * @return
     */
    @PostMapping("/upload/url")
    @SaSpaceCheckPermission(value = SpaceUserPermissionConstant.PICTURE_UPLOAD)
//    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<PictureVO> uploadPictureByUrl(
            @RequestBody PictureUploadRequest pictureUploadRequest,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        String fileUrl = pictureUploadRequest.getFileUrl();
        PictureVO pictureVO = pictureService.uploadPicture(fileUrl, pictureUploadRequest, loginUser);
        return ResultUtils.success(pictureVO);
    }

    /**
     * 上传图片
     * @param multipartFile 上传的文件数据
     * @param pictureUploadRequest JSON格式的请求体数据
     * @param request 请求对象
     * @return
     */
    @PostMapping("/upload")
    @SaSpaceCheckPermission(value = SpaceUserPermissionConstant.PICTURE_UPLOAD)
    public BaseResponse<PictureVO> uploadPicture(@RequestPart("file") MultipartFile multipartFile,
                                                 PictureUploadRequest pictureUploadRequest,
                                                 HttpServletRequest request) {
        User loginUser=userService.getLoginUser(request);
        PictureVO pictureVO = pictureService.uploadPicture(multipartFile, pictureUploadRequest, loginUser);
        return ResultUtils.success(pictureVO);


    }





    @PostMapping("/delete")
    @SaSpaceCheckPermission(value = SpaceUserPermissionConstant.PICTURE_DELETE)
    public BaseResponse<Boolean> deletePicture(@RequestBody DeleteRequest deleteRequest, HttpServletRequest httpServletRequest) {
        Long id = deleteRequest.getId();
        if(deleteRequest==null||deleteRequest.getId()<0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"给出需要删除的图片的参数");
        }
        //校验权限是否足够（当前用户为图片上传者，当前用户具有管理员权限）
        User loginUser = userService.getLoginUser(httpServletRequest);
        Picture pic = pictureService.getById(id);
        Long userId = pic.getUserId();
        if(loginUser.getId()!=userId){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR,"当前权限不足");
        }
        if(!userService.isAdmin(loginUser)){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR,"当前权限不足");
        }
        boolean result=pictureService.deletePicture(pic.getId(),loginUser);
        ThrowUtils.throwIf(!result,ErrorCode.OPERATION_ERROR);

        return ResultUtils.success(true);
    }

    @GetMapping("/get/vo")
    public BaseResponse<PictureVO> getPictureVOById(long id, HttpServletRequest request) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        // 先查缓存
        String cacheKey = "teacuppicture:picture:" + id;
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        Picture picture;
        if ("null".equals(cached)) {
            // 命中空值缓存，直接返回 404，防止缓存穿透
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        } else if (cached != null) {
            picture = JSONUtil.toBean(cached, Picture.class);
        } else {
            picture = pictureService.getById(id);
            if (picture == null) {
                // 缓存空值（短TTL），防止不存在的ID反复穿透到DB
                stringRedisTemplate.opsForValue().set(cacheKey, "null", 2, TimeUnit.MINUTES);
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
            }
            stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(picture), 5, TimeUnit.MINUTES);
        }
        //空间权限校验
        Long spaceId = picture.getSpaceId();
        Space space = null;
        if (spaceId != null) {
            boolean hasPermission = StpKit.SPACE.hasPermission(SpaceUserPermissionConstant.PICTURE_VIEW);
            ThrowUtils.throwIf(!hasPermission, ErrorCode.NO_AUTH_ERROR);
            space = spaceService.getById(spaceId);
            ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        }
        // 获取权限列表（用户相关，不缓存）
        User loginUser = userService.getLoginUser(request);
        List<String> permissionList = spaceUserAuthManager.getPermissionList(space, loginUser);
        PictureVO pictureVO = pictureService.getPictureVO(picture, request);
        pictureVO.setPermissionList(permissionList);
        // 获取封装类
        return ResultUtils.success(pictureVO);
    }

    @PostMapping("/edit")
    @SaSpaceCheckPermission(value = SpaceUserPermissionConstant.PICTURE_EDIT)
    public BaseResponse<Boolean> editPicture(@RequestBody PictureEditRequest pictureEditRequest, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);

        boolean result = pictureService.editPicture(pictureEditRequest,loginUser);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updatePicture(@RequestBody PictureUpdateRequest pictureUpdateRequest,HttpServletRequest httpServletRequest) {
        if (pictureUpdateRequest == null || pictureUpdateRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }

        Picture picture = new Picture();
        BeanUtils.copyProperties(pictureUpdateRequest, picture);

        picture.setTags(JSONUtil.toJsonStr(pictureUpdateRequest.getTags()));

        pictureService.validPicture(picture);

        long id = pictureUpdateRequest.getId();
        Picture oldPicture = pictureService.getById(id);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR);

        //补充审核参数
        pictureService.fillReviewParams(picture, userService.getLoginUser(httpServletRequest));

        boolean result = pictureService.updateById(picture);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }



    @PostMapping("/list/page")
//    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<Picture>> listPictureByPage(@RequestBody PictureQueryRequest pictureQueryRequest) {
        long current = pictureQueryRequest.getCurrent();
        long size = pictureQueryRequest.getPageSize();

 // 只查询审核通过的图片

        Page<Picture> picturePage = pictureService.page(new Page<>(current, size),
                pictureService.getQueryWrapper(pictureQueryRequest));
        return ResultUtils.success(picturePage);
    }


    /**
     * 分页获取图片VO列表
     * <p>
     * 根据查询条件分页获取图片列表，支持空间权限校验和审核状态过滤。
     * 未指定空间ID时，仅返回公共空间（spaceId为空）且审核通过的图片；
     * 指定空间ID时，需校验用户是否具有该空间的访问权限。
     * </p>
     *
     * @param pictureQueryRequest 图片查询请求对象，包含分页参数、搜索条件和空间ID等信息
     * @param request             HTTP请求对象，用于获取当前登录用户信息
     * @return 分页封装的图片VO列表，包含图片详细信息和用户权限列表
     */
    @PostMapping("/list/page/vo")
    public BaseResponse<Page<PictureVO>> listPictureVOByPage(@RequestBody PictureQueryRequest pictureQueryRequest,
                                                             HttpServletRequest request) {
        long current = pictureQueryRequest.getCurrent();
        long size = pictureQueryRequest.getPageSize();

        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);

        Long spaceId = pictureQueryRequest.getSpaceId();

        // 根据是否指定空间ID进行不同的权限处理
        if (spaceId == null) {
            // 未指定空间ID：查询公共空间且审核通过的图片
            pictureQueryRequest.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
            pictureQueryRequest.setNullSpaceId(true);
        } else {
            // 指定空间ID：校验用户是否具有该空间的访问权限
            User loginUser = userService.getLoginUser(request);
            Space space = spaceService.getById(spaceId);
            ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
            if (!loginUser.getId().equals(space.getUserId())) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "没有空间权限");
            }
        }

        // 普通用户只能看到审核通过的数据
        pictureQueryRequest.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());

        Page<Picture> picturePage = pictureService.page(new Page<>(current, size),
                pictureService.getQueryWrapper(pictureQueryRequest));

        return ResultUtils.success(pictureService.getPictureVOPage(picturePage, request));
    }

    /**
     * 分页获取图片列表（封装类，有缓存）
     * @param pictureQueryRequest
     * @param request
     * @return
     */
    @PostMapping("/list/page/vo/cache")
//    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<PictureVO>> listPictureVOByPageWithCache(@RequestBody PictureQueryRequest pictureQueryRequest,
                                                             HttpServletRequest request) {
        long current = pictureQueryRequest.getCurrent();
        long size = pictureQueryRequest.getPageSize();

        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);

        pictureQueryRequest.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());

        //生成缓存的键值
        //转换为标准的数据交换格式：JSON字符串
        String queryCondition = JSONUtil.toJsonStr(pictureQueryRequest);
        //MD5算法生成hashkey
        String hashKey = DigestUtils.md5DigestAsHex(queryCondition.getBytes());
        String cacheKey = "teacuppicture:listPictureVOByPage:" + hashKey;

        //获取Redis中操作字符串类型数据的工具类实例
        ValueOperations<String, String> valueOps = stringRedisTemplate.opsForValue();
        //先查本地缓存
        String cachedValue = localCache.getIfPresent(cacheKey);
        if (cachedValue != null) {

            Page<PictureVO> cachedPage = JSONUtil.toBean(cachedValue, Page.class);
            return ResultUtils.success(cachedPage);
        }

        //查询分布式redis缓存
        cachedValue = valueOps.get(cacheKey);
        if (cachedValue != null) {

            localCache.put(cacheKey, cachedValue);
            Page<PictureVO> cachedPage = JSONUtil.toBean(cachedValue, Page.class);
            return ResultUtils.success(cachedPage);
        }

        // 分布式锁防击穿：热点key过期时只允许一个线程查DB
        String lockKey = cacheKey + ":lock";
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", 10, TimeUnit.SECONDS);
        Page<PictureVO> pictureVOPage = null;
        try {
            if (!Boolean.TRUE.equals(locked)) {
                // 未拿到锁，等待持锁线程写入缓存后重试
                try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                cachedValue = valueOps.get(cacheKey);
                if (cachedValue != null) {
                    localCache.put(cacheKey, cachedValue);
                    return ResultUtils.success(JSONUtil.toBean(cachedValue, Page.class));
                }
            }
            // 持锁线程查DB（或重试后缓存仍为空时的兜底）
            Page<Picture> picturePage = pictureService.page(new Page<>(current, size),
                    pictureService.getQueryWrapper(pictureQueryRequest));
            pictureVOPage = pictureService.getPictureVOPage(picturePage, request);
            String cacheValue = JSONUtil.toJsonStr(pictureVOPage);
            int cacheExpireTime = 300 + RandomUtil.randomInt(0, 300);
            valueOps.set(cacheKey, cacheValue, cacheExpireTime, TimeUnit.SECONDS);
            localCache.put(cacheKey, cacheValue);
        } finally {
            if (Boolean.TRUE.equals(locked)) {
                stringRedisTemplate.delete(lockKey);
            }
        }
        return ResultUtils.success(pictureVOPage);
    }



    private static final PictureTagCategory TAG_CATEGORY;
    static {
        TAG_CATEGORY = new PictureTagCategory();
        TAG_CATEGORY.setTagList(Arrays.asList("热门", "搞笑", "生活", "高清", "艺术", "校园", "背景", "简历", "创意"));
        TAG_CATEGORY.setCategoryList(Arrays.asList("模板", "电商", "表情包", "素材", "海报"));
    }

    @GetMapping("/tag_category")
    public BaseResponse<PictureTagCategory> listPictureTagCategory() {
        return ResultUtils.success(TAG_CATEGORY);
    }

    /**
     * 图片审核
     * @param pictureReviewRequest
     * @param request
     * @return
     */
    @PostMapping("/review")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> doPictureReview(@RequestBody PictureReviewRequest pictureReviewRequest,
                                                 HttpServletRequest request) {
        ThrowUtils.throwIf(pictureReviewRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        pictureService.doPictureReview(pictureReviewRequest, loginUser);
        return ResultUtils.success(true);
    }

    /**
     * 批量获取和抓取图片
     * @param pictureUploadByBatchRequest
     * @param request
     * @return
     */
    @PostMapping("/upload/batch")
//    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Integer> uploadPictureByBatch(
            @RequestBody PictureUploadByBatchRequest pictureUploadByBatchRequest,
            HttpServletRequest request
    ) {
        ThrowUtils.throwIf(pictureUploadByBatchRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        int uploadCount = pictureService.uploadPictureByBatch(pictureUploadByBatchRequest, loginUser);
        return ResultUtils.success(uploadCount);
    }

    @GetMapping("/list/level")
    public BaseResponse<List<SpaceLevel>> listSpaceLevel() {
        List<SpaceLevel> spaceLevelList = Arrays.stream(SpaceLevelEnum.values())
                .map(spaceLevelEnum -> new SpaceLevel(
                        spaceLevelEnum.getValue(),
                        spaceLevelEnum.getText(),
                        spaceLevelEnum.getMaxCount(),
                        spaceLevelEnum.getMaxSize()))
                .collect(Collectors.toList());
        return ResultUtils.success(spaceLevelList);
    }


    /**
     * 图片搜索 - 颜色搜索
     * @param searchPictureByColorRequest
     * @param request
     * @return
     */
    @PostMapping("/search/color")
    @SaSpaceCheckPermission(value = SpaceUserPermissionConstant.PICTURE_VIEW)
    public BaseResponse<List<PictureVO>> searchPictureByColor(@RequestBody SearchPictureByColorRequest searchPictureByColorRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(searchPictureByColorRequest == null, ErrorCode.PARAMS_ERROR);
        String picColor = searchPictureByColorRequest.getPicColor();
        Long spaceId = searchPictureByColorRequest.getSpaceId();
        User loginUser = userService.getLoginUser(request);
        List<PictureVO> result = pictureService.searchPictureByColor(spaceId, picColor, loginUser);
        return ResultUtils.success(result);
    }


    /**
     * 批量编辑图片
     * @param pictureEditByBatchRequest
     * @param request
     * @return
     */
    @PostMapping("/edit/batch")
    @SaSpaceCheckPermission(value = SpaceUserPermissionConstant.PICTURE_EDIT)
    public BaseResponse<Boolean> editPictureByBatch(@RequestBody PictureEditByBatchRequest pictureEditByBatchRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(pictureEditByBatchRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        List<Space> list = spaceService.lambdaQuery()
                .eq(Space::getUserId, loginUser.getId())
                .list();
        Space space=list.get(0);
        pictureService.batchEditPictureMetadata(pictureEditByBatchRequest, space.getId(),loginUser.getId());
        return ResultUtils.success(true);
    }


}
