package com.teacup.teacuppicturebackend.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestWrapperTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesUtf8JsonAcrossRepeatedReads() throws Exception {
        String json = "{\"prompt\":\"一只白色茶杯，暖色光\"}";
        byte[] utf8Body = json.getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(utf8Body);

        RequestWrapper wrapper = new RequestWrapper(request);

        assertArrayEquals(utf8Body, wrapper.getInputStream().readAllBytes());
        assertArrayEquals(utf8Body, wrapper.getInputStream().readAllBytes());
        assertEquals(json, wrapper.getBody());
        JsonNode parsed = objectMapper.readTree(wrapper.getInputStream());
        assertEquals("一只白色茶杯，暖色光", parsed.path("prompt").asText());
    }
}
