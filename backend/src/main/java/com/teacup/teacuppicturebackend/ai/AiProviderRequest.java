package com.teacup.teacuppicturebackend.ai;

public record AiProviderRequest(String type, String providerModel, String prompt, String ratio,
                                String quality, String background, String outputFormat,
                                Integer outputCompression, String sourceUrl, String referenceUrl) {
}
