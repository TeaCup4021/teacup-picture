package com.teacup.teacuppicturebackend.api.v1;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.servlet.http.HttpServletRequest;

@Slf4j
@Order(0)
@RestControllerAdvice(basePackages = "com.teacup.teacuppicturebackend.api.v1")
public class V1ExceptionHandler {
    @ExceptionHandler(V1Exception.class)
    public ResponseEntity<V1Response<Void>> handle(V1Exception exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.getStatus())
                .body(V1Response.error(exception.getCode(), exception.getMessage(), RequestIdFilter.get(request)));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<V1Response<Void>> duplicate(DuplicateKeyException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(V1Response.error(40901, "资源状态冲突", RequestIdFilter.get(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<V1Response<Void>> unexpected(Exception exception, HttpServletRequest request) {
        log.error("Unexpected v1 request failure, requestId={}", RequestIdFilter.get(request), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(V1Response.error(50000, "系统内部异常", RequestIdFilter.get(request)));
    }
}
