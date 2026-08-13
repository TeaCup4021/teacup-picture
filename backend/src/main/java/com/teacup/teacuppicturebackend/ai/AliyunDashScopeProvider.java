package com.teacup.teacuppicturebackend.ai;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class AliyunDashScopeProvider implements AiProvider {
    private static final String GENERATE_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis";
    private static final String OUTPAINT_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/image2image/out-painting";
    private static final String TASK_URL = "https://dashscope.aliyuncs.com/api/v1/tasks/%s";
    private final String apiKey;
    private final long pollIntervalMs;
    private final int maxPolls;

    public AliyunDashScopeProvider(@Value("${aliYunAi.apiKey:}") String apiKey,
                                   @Value("${teacup.ai.aliyun.poll-interval-ms:2000}") long pollIntervalMs,
                                   @Value("${teacup.ai.aliyun.max-polls:90}") int maxPolls) {
        this.apiKey = apiKey;
        this.pollIntervalMs = pollIntervalMs;
        this.maxPolls = maxPolls;
    }

    @Override
    public String provider() {
        return "aliyun";
    }

    @Override
    public AiProviderResult execute(AiProviderRequest request) {
        if (StrUtil.isBlank(apiKey)) throw new AiProviderException("provider_not_configured", "AI 服务尚未配置");
        JSONObject body = "generate".equals(request.type()) ? generationBody(request) : outpaintBody(request);
        JSONObject submitted = request(HttpRequest.post("generate".equals(request.type()) ? GENERATE_URL : OUTPAINT_URL)
                .header(Header.AUTHORIZATION, "Bearer " + apiKey)
                .header("X-DashScope-Async", "enable")
                .header(Header.CONTENT_TYPE, ContentType.JSON.getValue())
                .body(body.toString()));
        JSONObject output = submitted.getJSONObject("output");
        String taskId = output == null ? null : first(output, "task_id", "taskId");
        if (StrUtil.isBlank(taskId)) throw providerError(submitted, "provider_submit_failed", "AI 任务提交失败");
        String requestId = first(submitted, "request_id", "requestId");
        for (int attempt = 0; attempt < maxPolls; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new AiProviderException("provider_interrupted", "AI 任务已中断");
            JSONObject result = request(HttpRequest.get(String.format(TASK_URL, taskId))
                    .header(Header.AUTHORIZATION, "Bearer " + apiKey));
            JSONObject taskOutput = result.getJSONObject("output");
            String status = taskOutput == null ? null : first(taskOutput, "task_status", "taskStatus");
            if ("SUCCEEDED".equalsIgnoreCase(status)) {
                String imageUrl = outputUrl(taskOutput);
                if (StrUtil.isBlank(imageUrl)) throw new AiProviderException("provider_missing_output", "AI 服务未返回图片");
                return new AiProviderResult(taskId, first(result, "request_id", "requestId"), imageUrl);
            }
            if ("FAILED".equalsIgnoreCase(status) || "CANCELED".equalsIgnoreCase(status)) {
                throw providerError(taskOutput, "provider_task_failed", "AI 任务执行失败");
            }
            sleep();
        }
        throw new AiProviderException("provider_timeout", "AI 任务等待超时");
    }

    private JSONObject generationBody(AiProviderRequest request) {
        JSONObject body = new JSONObject();
        body.set("model", request.providerModel());
        body.set("input", Map.of("prompt", request.prompt()));
        body.set("parameters", Map.of("size", size(request.ratio(), request.quality()), "n", 1, "watermark", false));
        return body;
    }

    private JSONObject outpaintBody(AiProviderRequest request) {
        JSONObject body = new JSONObject();
        body.set("model", request.providerModel());
        JSONObject input = new JSONObject();
        input.set("image_url", request.sourceUrl());
        if (StrUtil.isNotBlank(request.prompt())) input.set("prompt", request.prompt());
        body.set("input", input);
        body.set("parameters", Map.of("output_ratio", request.ratio(), "best_quality", "hd".equals(request.quality()),
                "limit_image_size", true, "add_watermark", false));
        return body;
    }

    private JSONObject request(HttpRequest request) {
        try (HttpResponse response = request.timeout(15_000).execute()) {
            JSONObject body = JSONUtil.parseObj(response.body());
            if (!response.isOk() || StrUtil.isNotBlank(body.getStr("code"))) {
                throw providerError(body, "provider_request_failed", "AI 服务请求失败");
            }
            return body;
        } catch (AiProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AiProviderException("provider_network_error", "AI 服务网络异常");
        }
    }

    private void sleep() {
        try {
            Thread.sleep(pollIntervalMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiProviderException("provider_interrupted", "AI 任务已中断");
        }
    }

    private static String outputUrl(JSONObject output) {
        String direct = first(output, "output_image_url", "outputImageUrl");
        if (StrUtil.isNotBlank(direct)) return direct;
        if (output.getJSONArray("results") != null && !output.getJSONArray("results").isEmpty()) {
            Object firstResult = output.getJSONArray("results").get(0);
            if (firstResult instanceof JSONObject object) return first(object, "url", "image_url");
            return JSONUtil.parseObj(firstResult).getStr("url");
        }
        return null;
    }

    private static AiProviderException providerError(JSONObject body, String fallbackCode, String fallbackMessage) {
        if (body == null) return new AiProviderException(fallbackCode, fallbackMessage);
        String code = first(body, "code", "error_code");
        String message = first(body, "message", "error_message");
        return new AiProviderException(StrUtil.blankToDefault(code, fallbackCode), StrUtil.blankToDefault(message, fallbackMessage));
    }

    private static String first(JSONObject value, String... keys) {
        if (value == null) return null;
        for (String key : keys) {
            String result = value.getStr(key);
            if (StrUtil.isNotBlank(result)) return result;
        }
        return null;
    }

    private static String size(String ratio, String quality) {
        int longSide = "hd".equals(quality) ? 1440 : 1024;
        return switch (ratio) {
            case "4:3" -> longSide + "*" + longSide * 3 / 4;
            case "3:4" -> longSide * 3 / 4 + "*" + longSide;
            case "16:9" -> longSide + "*" + longSide * 9 / 16;
            case "9:16" -> longSide * 9 / 16 + "*" + longSide;
            default -> longSide + "*" + longSide;
        };
    }
}
