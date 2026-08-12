package com.teacup.teacuppicturebackend.api.v1;

public record V1Response<T>(int code, T data, String message, String requestId) {
    public static <T> V1Response<T> success(T data, String requestId) {
        return new V1Response<>(0, data, "", requestId);
    }

    public static V1Response<Void> error(int code, String message, String requestId) {
        return new V1Response<>(code, null, message, requestId);
    }
}
