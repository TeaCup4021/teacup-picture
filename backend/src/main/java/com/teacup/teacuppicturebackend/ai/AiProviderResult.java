package com.teacup.teacuppicturebackend.ai;

public record AiProviderResult(String providerTaskId, String providerRequestId, String imageUrl,
                               String imageBase64, String imageContentType) {
    public static AiProviderResult url(String taskId, String requestId, String imageUrl) {
        return new AiProviderResult(taskId, requestId, imageUrl, null, null);
    }

    public static AiProviderResult base64(String taskId, String requestId, String imageBase64, String contentType) {
        return new AiProviderResult(taskId, requestId, null, imageBase64, contentType);
    }
}
