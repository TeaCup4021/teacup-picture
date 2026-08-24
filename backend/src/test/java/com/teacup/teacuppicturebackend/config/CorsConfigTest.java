package com.teacup.teacuppicturebackend.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {

    @Test
    void allowsAllHttpMethodsUsedByTheBrowserApiClient() {
        InspectableCorsRegistry registry = new InspectableCorsRegistry();
        new CorsConfig().addCorsMappings(registry);

        CorsConfiguration configuration = registry.configurations().get("/**");

        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedMethods())
                .contains("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    }

    private static final class InspectableCorsRegistry extends CorsRegistry {
        private Map<String, CorsConfiguration> configurations() {
            return getCorsConfigurations();
        }
    }
}
