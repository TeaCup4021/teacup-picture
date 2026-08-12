package com.teacup.teacuppicturebackend.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.teacup.teacuppicturebackend.api.aliyunai.AliYunAiApi;
import com.teacup.teacuppicturebackend.api.aliyunai.model.CreateOutPaintingTaskRequest;
import com.teacup.teacuppicturebackend.api.aliyunai.model.CreateOutPaintingTaskResponse;
import com.teacup.teacuppicturebackend.exception.BusinessException;
import com.teacup.teacuppicturebackend.exception.ErrorCode;
import com.teacup.teacuppicturebackend.exception.ThrowUtils;
import com.teacup.teacuppicturebackend.manager.CosManager;
import com.teacup.teacuppicturebackend.manager.upload.FilePictureUpload;
import com.teacup.teacuppicturebackend.manager.upload.PictureUploadTemplate;
import com.teacup.teacuppicturebackend.manager.upload.UrlPictureUpload;
import com.teacup.teacuppicturebackend.mapper.PictureMapper;
import com.teacup.teacuppicturebackend.model.dto.file.UploadPictureResult;
import com.teacup.teacuppicturebackend.model.dto.picture.*;
import com.teacup.teacuppicturebackend.model.entity.Picture;
import com.teacup.teacuppicturebackend.model.entity.Space;
import com.teacup.teacuppicturebackend.model.entity.User;
import com.teacup.teacuppicturebackend.model.enums.PictureReviewStatusEnum;
import com.teacup.teacuppicturebackend.model.event.ClearEvent;
import com.teacup.teacuppicturebackend.model.vo.PictureVO;
import com.teacup.teacuppicturebackend.model.vo.UserVO;
import com.teacup.teacuppicturebackend.service.PictureService;
import com.teacup.teacuppicturebackend.service.SpaceService;
import com.teacup.teacuppicturebackend.service.UserService;
import com.teacup.teacuppicturebackend.service.observer.impl.PictureCacheClearObserver;
import com.teacup.teacuppicturebackend.utils.ColorSimilarUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.BatchResult;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.awt.*;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

/**
* @author wolves
* @description 针对表【picture(图片)】的数据库操作Service实现
* @createDate 2025-09-19 17:57:30
*/
@Slf4j
@Service
public class PictureServiceImpl extends ServiceImpl<PictureMapper, Picture>
    implements PictureService {

    @Resource
    private UserService userService;

//    @Resource
//    private FileManager fileManager;

    @Resource
    private SpaceService spaceService;

    @Resource
    private FilePictureUpload filePictureUpload;

    @Resource
    private UrlPictureUpload urlPictureUpload;

    @Resource
    private CosManager cosManager;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private ThreadPoolExecutor customExecutor;

    @Resource
    private AliYunAiApi aliYunAiApi;

    @Resource
    private  PictureCacheClearObserver pictureCacheClearObserver;

    @Override
    public PictureVO uploadPicture(Object inputSource, PictureUploadRequest pictureUploadRequest, User loginUser) {

        //校验参数
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);

        Long spaceId = pictureUploadRequest.getSpaceId();
        if (spaceId != null) {
            Space space = spaceService.getById(spaceId);
            ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");

            // 改为使用统一的权限校验
//            // 校验是否有空间的权限，仅空间管理员才能上传
//            if (!loginUser.getId().equals(space.getUserId())) {
//                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "没有空间权限");
//            }
            // 校验额度
            if (space.getTotalCount() >= space.getMaxCount()) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "空间条数不足");
            }
            if (space.getTotalSize() >= space.getMaxSize()) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "空间大小不足");
            }
        }


        Long pictureId = null;
        if (pictureUploadRequest != null) {
            pictureId = pictureUploadRequest.getId();
        }
        //如果是更新判断图片是否存在
        if (pictureId != null) {
//            boolean exists = this.lambdaQuery()
//                    .eq(Picture::getId, pictureId)
//                    .exists();
//            ThrowUtils.throwIf(!exists, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
            Picture oldPicture = this.getById(pictureId);
            ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");

            if (!oldPicture.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
            }


            if (spaceId == null) {
                if (oldPicture.getSpaceId() != null) {
                    spaceId = oldPicture.getSpaceId();
                }
            } else {

                Space space = spaceService.getById(spaceId);
                ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");

                if (!loginUser.getId().equals(space.getUserId())) {
                    throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "没有空间权限");
                }

                if (space.getTotalCount() >= space.getMaxCount()) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, "空间条数不足");
                }
                if (space.getTotalSize() >= space.getMaxSize()) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, "空间大小不足");
                }
                if (ObjUtil.notEqual(spaceId, oldPicture.getSpaceId())) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间 id 不一致");
                }
            }
        }
        //上传图片
        String uploadPathPrefix;
        if (spaceId == null) {
            uploadPathPrefix = String.format("public/%s", loginUser.getId());
        } else {
            uploadPathPrefix = String.format("space/%s", spaceId);
        }

        //根据inputSource判断上传方式
        PictureUploadTemplate pictureUploadTemplate=filePictureUpload;
        if(inputSource instanceof String){
            pictureUploadTemplate=urlPictureUpload;
        }
        UploadPictureResult uploadPictureResult=pictureUploadTemplate.uploadPicture(inputSource,uploadPathPrefix);

        Picture picture = new Picture();
        picture.setSpaceId(spaceId);
        picture.setUrl(uploadPictureResult.getUrl());
        picture.setThumbnailUrl(uploadPictureResult.getThumbnailUrl());
        String picName = uploadPictureResult.getPicName();
        if (pictureUploadRequest != null && StrUtil.isNotBlank(pictureUploadRequest.getPicName())) {
            picName = pictureUploadRequest.getPicName();
        }

        picture.setName(picName);
        picture.setPicSize(uploadPictureResult.getPicSize());
        picture.setPicWidth(uploadPictureResult.getPicWidth());
        picture.setPicHeight(uploadPictureResult.getPicHeight());
        picture.setPicScale(uploadPictureResult.getPicScale());
        picture.setPicFormat(uploadPictureResult.getPicFormat());
        picture.setPicColor(uploadPictureResult.getPicColor());
        picture.setUserId(loginUser.getId());
        //补充审核参数
        this.fillReviewParams(picture, loginUser);
        //操作数据库
        if (pictureId != null) {
            picture.setId(pictureId);
            picture.setEditTime(new Date());
        }

        //todo 自行实现，如果是更新图片，清理cos中的资源

        Long finalSpaceId = spaceId;
        transactionTemplate.execute(status -> {
            boolean result = this.saveOrUpdate(picture);
            ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "图片上传失败");
            if (finalSpaceId != null) {
                boolean update = spaceService.lambdaUpdate()
                        .eq(Space::getId, finalSpaceId)
                        .setSql("totalSize = totalSize + " + picture.getPicSize())
                        .setSql("totalCount = totalCount + 1")
                        .update();
                ThrowUtils.throwIf(!update, ErrorCode.OPERATION_ERROR, "额度更新失败");
            }
            return picture;
        });

        ClearEvent uploadEvent = ClearEvent.of("UPDATE", "PICTURE", picture.getId());
        if (pictureCacheClearObserver.supports(uploadEvent)) {
            pictureCacheClearObserver.handleClearEvent(uploadEvent);
        }

        return PictureVO.objToVo(picture);

    }
    @Override
    public QueryWrapper<Picture> getQueryWrapper(PictureQueryRequest pictureQueryRequest) {

        QueryWrapper<Picture> queryWrapper = new QueryWrapper<>();
        if (pictureQueryRequest == null) {
            return queryWrapper;
        }
        // 从对象中取值
        Long id = pictureQueryRequest.getId();
        String name = pictureQueryRequest.getName();
        String introduction = pictureQueryRequest.getIntroduction();
        String category = pictureQueryRequest.getCategory();
        List<String> tags = pictureQueryRequest.getTags();
        Long picSize = pictureQueryRequest.getPicSize();
        Integer picWidth = pictureQueryRequest.getPicWidth();
        Integer picHeight = pictureQueryRequest.getPicHeight();
        Double picScale = pictureQueryRequest.getPicScale();
        String picFormat = pictureQueryRequest.getPicFormat();
        String searchText = pictureQueryRequest.getSearchText();
        Long userId = pictureQueryRequest.getUserId();
        Integer reviewStatus = pictureQueryRequest.getReviewStatus();
        String reviewMessage = pictureQueryRequest.getReviewMessage();
        Long reviewerId = pictureQueryRequest.getReviewerId();
        Long spaceId = pictureQueryRequest.getSpaceId();
        Date startEditTime = pictureQueryRequest.getStartEditTime();
        Date endEditTime = pictureQueryRequest.getEndEditTime();
        boolean nullSpaceId = pictureQueryRequest.isNullSpaceId();
        String sortField = pictureQueryRequest.getSortField();
        String sortOrder = pictureQueryRequest.getSortOrder();
        // 从多字段中搜索
        if (StrUtil.isNotBlank(searchText)) {
            // 需要拼接查询条件
            // and (name like "%xxx%" or introduction like "%xxx%")
            queryWrapper.and(
                    qw -> qw.like("name", searchText)
                            .or()
                            .like("introduction", searchText)
            );
        }
        queryWrapper.eq(ObjUtil.isNotEmpty(id), "id", id);
        queryWrapper.eq(ObjUtil.isNotEmpty(userId), "userId", userId);
        queryWrapper.eq(ObjUtil.isNotEmpty(spaceId), "spaceId", spaceId);
        queryWrapper.isNull(nullSpaceId, "spaceId");
        queryWrapper.like(StrUtil.isNotBlank(name), "name", name);
        queryWrapper.like(StrUtil.isNotBlank(introduction), "introduction", introduction);
        queryWrapper.like(StrUtil.isNotBlank(picFormat), "picFormat", picFormat);
        queryWrapper.like(StrUtil.isNotBlank(reviewMessage), "reviewMessage", reviewMessage);
        queryWrapper.eq(StrUtil.isNotBlank(category), "category", category);
        queryWrapper.eq(ObjUtil.isNotEmpty(picWidth), "picWidth", picWidth);
        queryWrapper.eq(ObjUtil.isNotEmpty(picHeight), "picHeight", picHeight);
        queryWrapper.eq(ObjUtil.isNotEmpty(picSize), "picSize", picSize);
        queryWrapper.eq(ObjUtil.isNotEmpty(picScale), "picScale", picScale);
        queryWrapper.eq(ObjUtil.isNotEmpty(reviewStatus), "reviewStatus", reviewStatus);
        queryWrapper.eq(ObjUtil.isNotEmpty(reviewerId), "reviewerId", reviewerId);
        // >= startEditTime
        queryWrapper.ge(ObjUtil.isNotEmpty(startEditTime), "editTime", startEditTime);
        // < endEditTime
        queryWrapper.lt(ObjUtil.isNotEmpty(endEditTime), "editTime", endEditTime);
        // JSON 数组查询
        if (CollUtil.isNotEmpty(tags)) {
            /* and (tag like "%\"Java\"%" and like "%\"Python\"%") */
            for (String tag : tags) {
                queryWrapper.like("tags", "\"" + tag + "\"");
            }
        }
        // 排序
        queryWrapper.orderBy(StrUtil.isNotEmpty(sortField), sortOrder.equals("ascend"), sortField);
        return queryWrapper;
    }

    @Override
    public PictureVO getPictureVO(Picture picture, HttpServletRequest request) {

        PictureVO pictureVO = PictureVO.objToVo(picture);

        Long userId = picture.getUserId();
        if (userId != null && userId > 0) {
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            pictureVO.setUser(userVO);
        }
        return pictureVO;
    }

    @Override
    public Page<PictureVO> getPictureVOPage(Page<Picture> picturePage, HttpServletRequest request) {
        List<Picture> pictureList = picturePage.getRecords();
        Page<PictureVO> pictureVOPage = new Page<>(picturePage.getCurrent(), picturePage.getSize(), picturePage.getTotal());
        if (CollUtil.isEmpty(pictureList)) {
            return pictureVOPage;
        }

        List<PictureVO> pictureVOList = pictureList.stream().map(PictureVO::objToVo).collect(Collectors.toList());

        Set<Long> userIdSet = pictureList.stream().map(Picture::getUserId).collect(Collectors.toSet());
        //用户id到用户列表的映射
        Map<Long, List<User>> userIdUserListMap = userService.listByIds(userIdSet).stream()
                .collect(Collectors.groupingBy(User::getId));

        pictureVOList.forEach(pictureVO -> {
            Long userId = pictureVO.getUserId();
            User user = null;
            if (userIdUserListMap.containsKey(userId)) {
                user = userIdUserListMap.get(userId).get(0);
            }
            pictureVO.setUser(userService.getUserVO(user));
        });
        pictureVOPage.setRecords(pictureVOList);
        return pictureVOPage;
    }

    //参数校验
    @Override
    public void validPicture(Picture picture) {
        ThrowUtils.throwIf(picture == null, ErrorCode.PARAMS_ERROR);

        Long id = picture.getId();
        String url = picture.getUrl();
        String introduction = picture.getIntroduction();

        ThrowUtils.throwIf(ObjUtil.isNull(id), ErrorCode.PARAMS_ERROR, "id 不能为空");
        if (StrUtil.isNotBlank(url)) {
            ThrowUtils.throwIf(url.length() > 1024, ErrorCode.PARAMS_ERROR, "url 过长");
        }
        if (StrUtil.isNotBlank(introduction)) {
            ThrowUtils.throwIf(introduction.length() > 800, ErrorCode.PARAMS_ERROR, "简介过长");
        }
    }

    @Override
    public void doPictureReview(PictureReviewRequest pictureReviewRequest, User loginUser) {
        //1.校验参数
        ThrowUtils.throwIf(pictureReviewRequest==null,ErrorCode.PARAMS_ERROR,"请求参数为空");
        Long id = pictureReviewRequest.getId();
        Integer reviewStatus = pictureReviewRequest.getReviewStatus();
        PictureReviewStatusEnum reviewStatusEnum = PictureReviewStatusEnum.getEnumByValue(reviewStatus);
        if (id == null || reviewStatus == null || PictureReviewStatusEnum.REVIEWING.equals(reviewStatusEnum)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        //2.判断图片是否存在
        Picture byId = this.getById(id);
        if(byId==null){
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,"审核的图片不存在");
        }
        //3.校验审核状态是否重复
        if(byId.getReviewStatus().equals(reviewStatus)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"请勿重复审核");
        }
        //4.数据库操作
        Picture updatePicture = new Picture();
        BeanUtils.copyProperties(pictureReviewRequest, updatePicture);
        updatePicture.setReviewerId(loginUser.getId());
        updatePicture.setReviewTime(new Date());
        boolean result = this.updateById(updatePicture);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
    }

    //补充审核参数
    @Override
    public void fillReviewParams(Picture picture, User loginUser) {
        if (userService.isAdmin(loginUser)) {

            picture.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
            picture.setReviewerId(loginUser.getId());
            picture.setReviewMessage("管理员自动过审");
            picture.setReviewTime(new Date());
        } else {

            picture.setReviewStatus(PictureReviewStatusEnum.REVIEWING.getValue());
        }
    }

    /**
     * 删除照片
     * @param pictureId
     * @param loginUser
     */
    @Override
    public Boolean deletePicture(long pictureId, User loginUser) {
        ThrowUtils.throwIf(pictureId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);

        Picture oldPicture = this.getById(pictureId);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR);

        checkPictureAuth(loginUser, oldPicture);

        transactionTemplate.execute(status -> {
            boolean result = this.removeById(pictureId);
            ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
            Long spaceId = oldPicture.getSpaceId();
            if (spaceId != null) {
                boolean update = spaceService.lambdaUpdate()
                        .eq(Space::getId, spaceId)
                        .setSql("totalSize = totalSize - " + oldPicture.getPicSize())
                        .setSql("totalCount = totalCount - 1")
                        .update();
                ThrowUtils.throwIf(!update, ErrorCode.OPERATION_ERROR, "额度更新失败");
            }
            return true;
        });
        ClearEvent deleteEvent = ClearEvent.of("DELETE", "PICTURE", pictureId);
        if (pictureCacheClearObserver.supports(deleteEvent)) {
            pictureCacheClearObserver.handleClearEvent(deleteEvent);
        }
        this.clearPictureFile(oldPicture);
        return true;

    }

    @Override
    public Boolean editPicture(PictureEditRequest pictureEditRequest, User loginUser) {

        Picture picture = new Picture();
        BeanUtils.copyProperties(pictureEditRequest, picture);

        picture.setTags(JSONUtil.toJsonStr(pictureEditRequest.getTags()));

        picture.setEditTime(new Date());

        this.validPicture(picture);

        long id = pictureEditRequest.getId();
        Picture oldPicture = this.getById(id);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR);

        checkPictureAuth(loginUser, oldPicture);

        this.fillReviewParams(picture, loginUser);

        boolean result = this.updateById(picture);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);

        ClearEvent editEvent = ClearEvent.of("UPDATE", "PICTURE", id);
        if (pictureCacheClearObserver.supports(editEvent)) {
            pictureCacheClearObserver.handleClearEvent(editEvent);
        }
        return result;
    }

    @Override
    public Integer uploadPictureByBatch(PictureUploadByBatchRequest pictureUploadByBatchRequest, User loginUser) {

        String searchText = pictureUploadByBatchRequest.getSearchText();

        Integer count = pictureUploadByBatchRequest.getCount();
        ThrowUtils.throwIf(count > 30, ErrorCode.PARAMS_ERROR, "最多 30 条");

        String fetchUrl = String.format("https://cn.bing.com/images/async?q=%s&mmasync=1", searchText);

        String namePrefix = pictureUploadByBatchRequest.getNamePrefix();
        if (StrUtil.isBlank(namePrefix)) {
            namePrefix = searchText;
        }

        Document document;
        try {
            document = Jsoup.connect(fetchUrl).get();
        } catch (IOException e) {
            log.error("获取页面失败", e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "获取页面失败");
        }
        Element div = document.getElementsByClass("dgControl").first();
        if (ObjUtil.isNull(div)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "获取元素失败");
        }
        Elements imgElementList = div.select("img.mimg");
        int uploadCount = 0;
        for (Element imgElement : imgElementList) {
            String fileUrl = imgElement.attr("src");
            if (StrUtil.isBlank(fileUrl)) {
                log.info("当前链接为空，已跳过: {}", fileUrl);
                continue;
            }
            //处理图片url
            //因为url中包含了查询参数，需要移除这些参数，只保留基本的图片url
            int questionMarkIndex = fileUrl.indexOf("?");
            if (questionMarkIndex > -1) {
                fileUrl = fileUrl.substring(0, questionMarkIndex);
            }

            PictureUploadRequest pictureUploadRequest = new PictureUploadRequest();
            if (StrUtil.isNotBlank(namePrefix)) {

                pictureUploadRequest.setPicName(namePrefix + (uploadCount + 1));
            }
            try {
                PictureVO pictureVO = this.uploadPicture(fileUrl, pictureUploadRequest, loginUser);
                log.info("图片上传成功, id = {}", pictureVO.getId());
                uploadCount++;
            } catch (Exception e) {
                log.error("图片上传失败", e);
                continue;
            }
            if (uploadCount >= count) {
                break;
            }
        }
        return uploadCount;
    }

    @Async
    @Override
    public void clearPictureFile(Picture oldPicture) {
        String pictureUrl = oldPicture.getUrl();
        long count = this.lambdaQuery()
                .eq(Picture::getUrl, pictureUrl)
                .count();

        if (count > 1) {
            return;
        }

        cosManager.deleteObject(oldPicture.getUrl());

        String thumbnailUrl = oldPicture.getThumbnailUrl();
        if (StrUtil.isNotBlank(thumbnailUrl)) {
            cosManager.deleteObject(thumbnailUrl);
        }
    }

    @Override
    public void checkPictureAuth(User loginUser, Picture picture) {
        Long spaceId = picture.getSpaceId();
        Long loginUserId = loginUser.getId();
        if (spaceId == null) {
            // 公共图库，仅本人或管理员可操作
            if (!picture.getUserId().equals(loginUserId) && !userService.isAdmin(loginUser)) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
            }
        } else {
            // 私有空间，仅空间管理员可操作
            if (!picture.getUserId().equals(loginUserId)) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
            }
        }
    }


    @Override
    public List<PictureVO> searchPictureByColor(Long spaceId, String picColor, User loginUser) {
        //1.校验参数
        ThrowUtils.throwIf(spaceId==null||picColor==null,ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser==null,ErrorCode.NO_AUTH_ERROR);
        //2.检验空间权限
        Space byId = spaceService.getById(spaceId);
        if(!loginUser.getId().equals(byId.getUserId())){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }

        //3.查询该空间下的所有图片
        //没有图片返回空列表，将颜色字符串转换为主色调
        List<PictureVO> voList=new ArrayList<>();
        List<Picture> list = this.lambdaQuery()
                .eq(Picture::getSpaceId, byId.getId())
                .isNotNull(Picture::getPicColor)
                .list();
        if(CollUtil.isEmpty(list)){
            return voList;
        }
        // 将16进制的颜色字符串转换为Color对象，eg : RGB (255, 0, 0)
        Color target = Color.decode(picColor);
        //4.计算相似度并排序
        List<Picture> collect = list.stream()
                .sorted(Comparator.comparingDouble(picture -> {

                    String hexColor = picture.getPicColor();

                    if (StrUtil.isBlank(hexColor)) {
                        return Double.MAX_VALUE;
                    }
                    Color pictureColor = Color.decode(hexColor);

                    return -ColorSimilarUtils.calculateSimilarity(target, pictureColor);
                }))
                .limit(12)
                .collect(Collectors.toList());

        //5.返回结果
        return collect.stream()
                .map(PictureVO::objToVo)
                .collect(Collectors.toList());
    }



    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchEditPictureMetadata(PictureEditByBatchRequest request, Long spaceId, Long loginUserId) {


        //从数据库中找到的图片列表
        List<Picture> pictureList = this.lambdaQuery()
                .eq(Picture::getSpaceId, spaceId)
                .in(Picture::getId, request.getPictureIdList())
                .list();

        if (pictureList.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "指定的图片不存在或不属于该空间");
        }


        int batchSize = 100;
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < pictureList.size(); i += batchSize) {
            List<Picture> batch = pictureList.subList(i, Math.min(i + batchSize, pictureList.size()));


            // CompletableFuture.runAsync 创建无返回值的异步任务
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                batch.forEach(picture -> {

                    if (request.getCategory() != null) {
                        picture.setCategory(request.getCategory());
                    }
                    if (request.getTags() != null) {
                        picture.setTags(String.join(",", request.getTags()));
                    }
                });
                boolean result = this.updateBatchById(batch);
                if (!result) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, "批量更新图片失败");
                }
            }, customExecutor);

            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

    }

    /**
     *     方案二：使用 TransactionTemplate（推荐）
     * @Override
     * public void batchEditPictureMetadata(PictureEditByBatchRequest request, Long spaceId, Long loginUserId) {
     *
     *     List<Picture> pictureList = this.lambdaQuery()
     *             .eq(Picture::getSpaceId, spaceId)
     *             .in(Picture::getId, request.getPictureIdList())
     *             .list();
     *
     *     if (pictureList.isEmpty()) {
     *         throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "指定的图片不存在");
     *     }
     *
     *     int batchSize = 100;
     *     List<CompletableFuture<Void>> futures = new ArrayList<>();
     *
     *     for (int i = 0; i < pictureList.size(); i += batchSize) {
     *         List<Picture> batch = pictureList.subList(i, Math.min(i + batchSize, pictureList.size()));
     *
     *         CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
     *     batch.forEach(picture -> {
     *         // 每条记录独立开启事务
     *         transactionTemplate.execute(status -> {
     *             try {
     *                 if (request.getCategory() != null) {
     *                     picture.setCategory(request.getCategory());
     *                 }
     *                 if (request.getTags() != null) {
     *                     picture.setTags(String.join(",", request.getTags()));
     *                 }
     *
     *                 boolean result = this.updateById(picture);  // 单条更新
     *                 if (!result) {
     *                     status.setRollbackOnly();
     *                     log.warn("图片{}更新失败，已回滚", picture.getId());
     *                 }
     *                 return null;
     *             } catch (Exception e) {
     *                 status.setRollbackOnly();
     *                 log.error("图片{}更新异常", picture.getId(), e);
     *                 return null;  // 捕获异常避免中断整个batch
     *             }
     *         });
     *     });
     * }, customExecutor);
     *     }
     *
     *     // 等待所有任务完成
     *     CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
     * }
     *
     *
     */

    /**
     *               方案一
     * // 主方法：不加事务，仅做任务分发
     * @Override
     * public BatchEditResult batchEditPictureMetadata(PictureEditByBatchRequest request, Long spaceId, Long loginUserId) {
     *     List<Picture> pictureList = this.lambdaQuery()
     *             .eq(Picture::getSpaceId, spaceId)
     *             .in(Picture::getId, request.getPictureIdList())
     *             .list();
     *
     *     if (pictureList.isEmpty()) {
     *         throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "指定的图片不存在或不属于该空间");
     *     }
     *
     *     // 记录任务开始（可选：存入任务表）
     *     String taskId = UUID.randomUUID().toString();
     *     taskService.createBatchTask(taskId, pictureList.size());
     *
     *     // 异步处理每个批次
     *     List<CompletableFuture<BatchResult>> futures = Lists.partition(pictureList, 100).stream()
     *             .map(batch -> processBatchAsync(batch, request, taskId))
     *             .collect(Collectors.toList());
     *
     *     // 等待并聚合结果
     *     List<BatchResult> results = futures.stream()
     *             .map(CompletableFuture::join)
     *             .collect(Collectors.toList());
     *
     *     return aggregateResults(results, taskId);
     * }
     *
     * // 异步任务：独立事务
     * @Async("customExecutor")
     * @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
     * public CompletableFuture<BatchResult> processBatchAsync(
     *         List<Picture> batch, PictureEditByBatchRequest request, String taskId) {
     *
     *     try {
     *         // 深拷贝避免修改共享对象
     *         List<Picture> batchCopy = batch.stream()
     *                 .map(p -> BeanUtil.copyProperties(p, Picture.class))
     *                 .collect(Collectors.toList());
     *
     *         batchCopy.forEach(picture -> {
     *             if (request.getCategory() != null) {
     *                 picture.setCategory(request.getCategory());
     *             }
     *             if (request.getTags() != null) {
     *                 picture.setTags(String.join(",", request.getTags()));
     *             }
     *         });
     *
     *         boolean success = this.updateBatchById(batchCopy);
     *
     *         // 更新任务进度
     *         taskService.updateTaskProgress(taskId, batch.size(), success ? 0 : batch.size());
     *
     *         return CompletableFuture.completedFuture(
     *                 new BatchResult(success, batch.size(), null));
     *     } catch (Exception e) {
     *         log.error("批次处理失败", e);
     *         taskService.updateTaskProgress(taskId, 0, batch.size());
     *         return CompletableFuture.completedFuture(
     *                 new BatchResult(false, 0, batch.size()));
     *     }
     * }
     */


    /**
     * 创建扩图任务
     * @param createPictureOutPaintingTaskRequest
     * @param loginUser
     * @return
     */
    @Override
    public CreateOutPaintingTaskResponse createPictureOutPaintingTask(CreatePictureOutPaintingTaskRequest createPictureOutPaintingTaskRequest, User loginUser) {

        // 获取图片ID
        Long pictureId = createPictureOutPaintingTaskRequest.getPictureId();
        // 根据图片ID查询图片信息，如果不存在则抛出业务异常
        Picture picture = Optional.ofNullable(this.getById(pictureId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR));

        // 检查用户对图片的访问权限
        checkPictureAuth(loginUser, picture);

        // 构建创建外绘任务的请求参数
        CreateOutPaintingTaskRequest taskRequest = new CreateOutPaintingTaskRequest();
        CreateOutPaintingTaskRequest.Input input = new CreateOutPaintingTaskRequest.Input();
        input.setImageUrl(picture.getUrl());
        taskRequest.setInput(input);
        // 复制请求参数属性
        BeanUtil.copyProperties(createPictureOutPaintingTaskRequest, taskRequest);

        // 调用阿里云AI接口创建外绘任务并返回结果
        return aliYunAiApi.createOutPaintingTask(taskRequest);

    }

}




