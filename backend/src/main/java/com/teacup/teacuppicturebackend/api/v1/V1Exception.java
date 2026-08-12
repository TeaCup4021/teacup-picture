package com.teacup.teacuppicturebackend.api.v1;

import org.springframework.http.HttpStatus;

public class V1Exception extends RuntimeException {
    private final HttpStatus status;
    private final int code;

    public V1Exception(HttpStatus status, int code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public int getCode() { return code; }

    public static V1Exception badRequest(String message) {
        return new V1Exception(HttpStatus.BAD_REQUEST, 40000, message);
    }
    public static V1Exception unauthorized() {
        return new V1Exception(HttpStatus.UNAUTHORIZED, 40100, "未登录或会话已失效");
    }
    public static V1Exception forbidden() {
        return new V1Exception(HttpStatus.FORBIDDEN, 40101, "无权限执行此操作");
    }
    public static V1Exception notFound() {
        return new V1Exception(HttpStatus.NOT_FOUND, 40400, "资源不存在或不可见");
    }
    public static V1Exception conflict(String message) {
        return new V1Exception(HttpStatus.CONFLICT, 40901, message);
    }
}
