package com.teacup.teacuppicturebackend.ai;

public interface AiProvider {
    String provider();

    AiProviderResult execute(AiProviderRequest request);
}
