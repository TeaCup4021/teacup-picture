package com.teacup.teacuppicturebackend.ai;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class OpenAiImagesProvider implements AiProvider {
    private final String apiKey;
    private final String generationsUrl;
    private final int timeoutMs;

    public OpenAiImagesProvider(@Value("${teacup.ai.openai.api-key:}") String apiKey,
                                @Value("${teacup.ai.openai.base-url:https://claudenb.com}") String baseUrl,
                                @Value("${teacup.ai.openai.timeout-ms:120000}") int timeoutMs) {
        this.apiKey = apiKey;
        this.generationsUrl = generationsUrl(baseUrl);
        this.timeoutMs = timeoutMs;
    }

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public AiProviderResult execute(AiProviderRequest request) {
        if (StrUtil.isBlank(apiKey)) throw new AiProviderException("provider_not_configured", "OpenAI Images API 尚未配置");
        if (!"generate".equals(request.type())) {
            throw new AiProviderException("provider_capability_unsupported", "当前 OpenAI Images 模型不支持扩图任务");
        }

        JSONObject body = new JSONObject();
        body.set("model", request.providerModel());
        body.set("prompt", request.prompt());
        body.set("n", 1);
        body.set("size", size(request.ratio()));
        body.set("quality", quality(request.quality()));
        body.set("background", request.background());
        body.set("output_format", request.outputFormat());
        if (request.outputCompression() != null) {
            body.set("output_compression", request.outputCompression());
        }

        try (HttpResponse response = HttpRequest.post(generationsUrl)
                .header(Header.AUTHORIZATION, "Bearer " + apiKey)
                .header(Header.CONTENT_TYPE, ContentType.JSON.getValue())
                .header(Header.ACCEPT, ContentType.JSON.getValue())
                .body(body.toString())
                .timeout(timeoutMs)
                .execute()) {
            JSONObject responseBody = parseResponse(response);
            if (!response.isOk()) throw providerError(responseBody, response.getStatus());
            JSONArray data = responseBody.getJSONArray("data");
            if (data == null || data.isEmpty()) {
                throw new AiProviderException("provider_missing_output", "OpenAI Images API 未返回图片");
            }
            JSONObject image = JSONUtil.parseObj(data.get(0));
            String requestId = first(response.header("x-request-id"), responseBody.getStr("id"));
            String taskId = StrUtil.blankToDefault(responseBody.getStr("id"), UUID.randomUUID().toString());
            String base64 = image.getStr("b64_json");
            if (StrUtil.isNotBlank(base64)) {
                return AiProviderResult.base64(taskId, requestId, base64, contentType(request.outputFormat()));
            }
            String url = image.getStr("url");
            if (StrUtil.isNotBlank(url)) return AiProviderResult.url(taskId, requestId, url);
            throw new AiProviderException("provider_missing_output", "OpenAI Images API 未返回图片数据");
        } catch (AiProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AiProviderException("provider_network_error", "OpenAI Images API 网络异常", exception);
        }
    }

    private static JSONObject parseResponse(HttpResponse response) {
        try {
            return JSONUtil.parseObj(new String(response.bodyBytes(), StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            throw new AiProviderException("provider_invalid_response", "OpenAI Images API 返回了无效响应");
        }
    }

    private static AiProviderException providerError(JSONObject body, int status) {
        JSONObject error = body == null ? null : body.getJSONObject("error");
        String code = error == null ? null : error.getStr("code");
        String message = error == null ? null : error.getStr("message");
        return new AiProviderException(StrUtil.blankToDefault(code, errorCode(status)),
                StrUtil.blankToDefault(message, "OpenAI Images API 请求失败"));
    }

    private static String errorCode(int status) {
        return switch (status) {
            case 401 -> "provider_auth_failed";
            case 403 -> "provider_permission_denied";
            case 408, 504 -> "provider_timeout";
            case 429 -> "provider_rate_limited";
            default -> "provider_http_" + status;
        };
    }

    static String generationsUrl(String baseUrl) {
        String value = StrUtil.blankToDefault(baseUrl, "https://claudenb.com").trim().replaceAll("/+$", "");
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("teacup.ai.openai.base-url is invalid", exception);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || StrUtil.isBlank(uri.getHost())) {
            throw new IllegalArgumentException("teacup.ai.openai.base-url must be an HTTP(S) URL");
        }
        if (value.endsWith("/v1/images/generations")) return value;
        if (value.endsWith("/v1")) return value + "/images/generations";
        return value + "/v1/images/generations";
    }

    static String size(String ratio) {
        return switch (ratio) {
            case "1:1" -> "1024x1024";
            case "3:2" -> "1536x1024";
            case "2:3" -> "1024x1536";
            default -> throw new AiProviderException("provider_parameter_unsupported", "当前模型不支持该图片比例");
        };
    }

    static String quality(String quality) {
        return "hd".equals(quality) ? "high" : "medium";
    }

    static String contentType(String outputFormat) {
        return switch (outputFormat) {
            case "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            default -> "image/png";
        };
    }

    private static String first(String... values) {
        for (String value : values) if (StrUtil.isNotBlank(value)) return value;
        return null;
    }
}
