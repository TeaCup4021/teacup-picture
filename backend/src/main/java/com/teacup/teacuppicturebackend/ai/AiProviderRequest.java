package com.teacup.teacuppicturebackend.ai;

public record AiProviderRequest(String type, String providerModel, String prompt, String ratio,
                                String quality, String sourceUrl, String referenceUrl) {
}
