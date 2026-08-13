package com.teacup.teacuppicturebackend.ai;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class AiProviderRegistry {
    private final Map<String, AiProvider> providers;

    public AiProviderRegistry(List<AiProvider> providers) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(AiProvider::provider, Function.identity()));
    }

    public AiProvider require(String provider) {
        AiProvider value = providers.get(provider);
        if (value == null) throw new AiProviderException("provider_unavailable", "AI 服务暂不可用");
        return value;
    }
}
