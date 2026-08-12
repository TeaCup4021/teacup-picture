package com.teacup.teacuppicturebackend.manager.upload;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpStatus;
import cn.hutool.http.HttpUtil;
import cn.hutool.http.Method;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.persistence.CIObject;
import com.qcloud.cos.model.ciModel.persistence.ImageInfo;
import com.qcloud.cos.model.ciModel.persistence.ProcessResults;
import com.teacup.teacuppicturebackend.config.CosClientConfig;
import com.teacup.teacuppicturebackend.exception.BusinessException;
import com.teacup.teacuppicturebackend.exception.ErrorCode;
import com.teacup.teacuppicturebackend.exception.ThrowUtils;
import com.teacup.teacuppicturebackend.manager.CosManager;
import com.teacup.teacuppicturebackend.model.dto.file.UploadPictureResult;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.Date;
import java.util.List;


@Slf4j
public abstract class PictureUploadTemplate {

    @Resource
    protected CosManager cosManager;

    @Resource
    protected CosClientConfig cosClientConfig;

    /**
     * 上传图片
     * @param inputSource
     * @param uploadPathPrefix
     * @return
     */

    public final UploadPictureResult uploadPicture(Object inputSource, String uploadPathPrefix) {
        //校验图片文件
        validPicture(inputSource);
        //生成一个长度为16位的随机字符串，用于确保文件名唯一性。
        String uuid = RandomUtil.randomString(16);
        //获取上传文件的原始文件名。
        String originFilename = getOriginFilename(inputSource);
        /**
        //构建新的文件名，格式为"日期_随机字符串.原文件后缀"，例如"2023-04-01_a1b2c3d4e5f6g7h8.jpg"。
        String uploadFilename = String.format("%s_%s.%s", DateUtil.formatDate(new Date()), uuid,
                FileUtil.getSuffix(originFilename));
         **/
        String uploadFilename;
        String suffix = FileUtil.getSuffix(originFilename);

         if (StrUtil.isBlank(suffix)) {
         // 如果没有扩展名，尝试通过URL获取Content-Type来判断图片格
                try {
            HttpResponse response = HttpUtil.createGet(originFilename).execute();
            String contentType = response.header("Content-Type");

            // 根据常见的图片MIME类型设置扩展名
            if (contentType != null) {
                if (contentType.contains("image/jpeg") || contentType.contains("image/jpg")) {
                suffix = "jpg";
                } else if (contentType.contains("image/png")) {
                suffix = "png";
                } else if (contentType.contains("image/gif")) {
                suffix = "gif";
                } else if (contentType.contains("image/webp")) {
                suffix = "webp";
                } else if (contentType.contains("image/bmp")) {
                suffix = "bmp";
                } else {
                // 如果无法识别，使用默认的jpg扩展名
                suffix = "jpg";
                }
            } else {
                // 如果无法获取Content-Type，使用默认的jpg扩展名
                suffix = "jpg";
            }
        } catch (Exception e) {
            // 如果发生异常，使用默认的jpg扩展名
            suffix = "jpg";
            }
        }
        uploadFilename = String.format("%s_%s.%s", DateUtil.formatDate(new Date()), uuid, suffix);

        //构建完整的上传路径，格式为"/路径前缀/新文件名"。
        String uploadPath = String.format("/%s/%s", uploadPathPrefix, uploadFilename);
        //声明一个File对象并初始化为null，用于后续创建临时文件。
        File file = null;
        try {
            //创建一个临时文件，使用uploadPath作为前缀，后缀为null。
            file = File.createTempFile(uploadPath, null);
            //将上传的MultipartFile内容传输到临时文件中。
            processFile(inputSource, file);

            //调用cosManager的putPictureObject方法将临时文件上传到腾讯云对象存储(COS)，返回上传结果。
            PutObjectResult putObjectResult = cosManager.putPictureObject(uploadPath, file);
            ImageInfo imageInfo = putObjectResult.getCiUploadResult().getOriginalInfo().getImageInfo();
            ProcessResults processResults = putObjectResult.getCiUploadResult().getProcessResults();
            List<CIObject> objectList = processResults.getObjectList();
            if (CollUtil.isNotEmpty(objectList)) {
                CIObject compressedCiObject = objectList.get(0);

                CIObject thumbnailCiObject = compressedCiObject;

                if (objectList.size() > 1) {
                    thumbnailCiObject = objectList.get(1);
                }

                return buildResult(originFilename, compressedCiObject, thumbnailCiObject,imageInfo);
            }
            return buildResult(originFilename, file, uploadPath, imageInfo);
        } catch (Exception e) {
            log.error("图片上传到对象存储失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "上传失败");
        } finally {

            deleteTempFile(file);
        }
    }

    private UploadPictureResult buildResult(String originFilename, CIObject compressedCiObject,CIObject thumbnailCiObject,ImageInfo imageInfo) {
        UploadPictureResult uploadPictureResult = new UploadPictureResult();
        int picWidth = compressedCiObject.getWidth();
        int picHeight = compressedCiObject.getHeight();
        double picScale = NumberUtil.round(picWidth * 1.0 / picHeight, 2).doubleValue();
        uploadPictureResult.setPicName(FileUtil.mainName(originFilename));
        uploadPictureResult.setPicWidth(picWidth);
        uploadPictureResult.setPicHeight(picHeight);
        uploadPictureResult.setPicScale(picScale);
        uploadPictureResult.setPicFormat(compressedCiObject.getFormat());
        uploadPictureResult.setPicColor(imageInfo.getAve());
        uploadPictureResult.setPicSize(compressedCiObject.getSize().longValue());



        uploadPictureResult.setUrl(cosClientConfig.getHost() + "/" + compressedCiObject.getKey());
        uploadPictureResult.setThumbnailUrl(cosClientConfig.getHost() + "/" + thumbnailCiObject.getKey());
        return uploadPictureResult;
    }


    /**
     * 校验输入流
     * @param inputSource
     */
    protected abstract void validPicture(Object inputSource);

    /**
     * 获取输入流的原始文件名
     * @param inputSource
     * @return
     */

    protected abstract String getOriginFilename(Object inputSource);

    /**
     * 处理输入源并生成本地原始文件
     * @param inputSource
     * @param file
     * @throws Exception
     */
    protected abstract void processFile(Object inputSource, File file) throws Exception;


    private UploadPictureResult buildResult(String originFilename, File file, String uploadPath, ImageInfo imageInfo) {
        UploadPictureResult uploadPictureResult = new UploadPictureResult();
        int picWidth = imageInfo.getWidth();
        int picHeight = imageInfo.getHeight();
        double picScale = NumberUtil.round(picWidth * 1.0 / picHeight, 2).doubleValue();
        uploadPictureResult.setPicName(FileUtil.mainName(originFilename));
        uploadPictureResult.setPicWidth(picWidth);
        uploadPictureResult.setPicHeight(picHeight);
        uploadPictureResult.setPicScale(picScale);
        uploadPictureResult.setPicFormat(imageInfo.getFormat());
        uploadPictureResult.setPicColor(imageInfo.getAve());
        uploadPictureResult.setPicSize(FileUtil.size(file));
        uploadPictureResult.setUrl(cosClientConfig.getHost() + "/" + uploadPath);
        return uploadPictureResult;
    }



    public void deleteTempFile(File file) {
        if (file == null) {
            return;
        }
        boolean deleteResult = file.delete();
        if (!deleteResult) {
            log.error("file delete error, filepath = {}", file.getAbsolutePath());
        }
    }
}