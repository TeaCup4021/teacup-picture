package com.teacup.teacuppicturebackend.common;

import com.teacup.teacuppicturebackend.exception.ErrorCode;
import com.teacup.teacuppicturebackend.common.BaseResponse;

/**
 * 结果工具类
 */
public class ResultUtils {

    /**
     * 成功响应
     *
     * @param data 数据
     * @param <T>  数据类型
     * @return 响应结果
     */
    public static <T> BaseResponse<T> success(T data) {
        return new BaseResponse<>(0, data, "ok");
    }

    /**
     * 失败响应
     *
     * @param errorCode 错误码
     * @return 响应结果
     */
    public static BaseResponse<?> error(ErrorCode errorCode) {
        return new BaseResponse<>(errorCode);
    }

    /**
     * 失败响应
     *
     * @param code    错误码
     * @param message 错误信息
     * @return 响应结果
     */
    public static BaseResponse<?> error(int code, String message) {
        return new BaseResponse<>(code, null, message);
    }

    /**
     * 失败响应
     *
     * @param errorCode 错误码
     * @param message   错误信息
     * @return 响应结果
     */
    public static BaseResponse<?> error(ErrorCode errorCode, String message) {
        return new BaseResponse<>(errorCode.getCode(), null, message);
    }
}
