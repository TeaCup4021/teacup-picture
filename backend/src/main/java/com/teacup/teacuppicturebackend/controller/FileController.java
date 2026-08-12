package com.teacup.teacuppicturebackend.controller;


import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectInputStream;
import com.qcloud.cos.utils.IOUtils;
import com.teacup.teacuppicturebackend.annotation.AuthCheck;
import com.teacup.teacuppicturebackend.common.BaseResponse;
import com.teacup.teacuppicturebackend.common.ResultUtils;
import com.teacup.teacuppicturebackend.constant.UserConstant;
import com.teacup.teacuppicturebackend.exception.BusinessException;
import com.teacup.teacuppicturebackend.exception.ErrorCode;
import com.teacup.teacuppicturebackend.manager.CosManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/api/file")
public class FileController {

    @Resource
    private CosManager cosManager;

    //测试文件上传
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @PostMapping("/test/upload")
    public BaseResponse<String> testUploadFile(@RequestPart("file") MultipartFile multipartFile) {

        String filename = multipartFile.getOriginalFilename();
        String filepath = String.format("/test/%s", filename);
        File file = null;
        try {
            //在tomcat中创建一个空文件
            file = File.createTempFile(filepath, null);
            //将前端传的文件传递到空文件中
            multipartFile.transferTo(file);
            cosManager.putObject(filepath, file);

            return ResultUtils.success(filepath);
        } catch (Exception e) {
            log.error("file upload error, filepath = " + filepath, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "上传失败");
        } finally {
            if (file != null) {
                //删除临时文件
                boolean delete = file.delete();
                if (!delete) {
                    log.error("file delete error, filepath = {}", filepath);
                }
            }
        }
    }

    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @GetMapping("/test/download/")
    public void testDownloadFile(String filepath, HttpServletResponse response) throws IOException {
        COSObjectInputStream cosObjectInput = null;
        try {
            //调用cosManager的getObject方法，根据文件路径从腾讯云对象存储中获取对应的COS对象。
            COSObject cosObject = cosManager.getObject(filepath);
            //从COS对象中获取文件内容的输入流，并赋值给cosObjectInput变量。
            cosObjectInput = cosObject.getObjectContent();
            //使用IOUtils工具类将输入流转换为字节数组，以便后续传输。
            byte[] bytes = IOUtils.toByteArray(cosObjectInput);
            //设置HTTP响应的内容类型为二进制流，并指定字符编码为UTF-8。
            response.setContentType("application/octet-stream;charset=UTF-8");
            //设置响应头，指定浏览器将响应作为附件下载，并设置下载文件的默认文件名。
            response.setHeader("Content-Disposition", "attachment; filename=" + filepath);
            //将文件的字节数组写入HTTP响应的输出流中。
            response.getOutputStream().write(bytes);
            //刷新输出流，确保所有数据都被发送出去。
            response.getOutputStream().flush();
        } catch (Exception e) {
            log.error("file download error, filepath = " + filepath, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "下载失败");
        } finally {
            if (cosObjectInput != null) {
                cosObjectInput.close();
            }
        }
    }
}
