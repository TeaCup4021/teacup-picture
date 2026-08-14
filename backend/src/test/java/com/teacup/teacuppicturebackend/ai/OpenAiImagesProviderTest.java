package com.teacup.teacuppicturebackend.ai;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiImagesProviderTest {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsOpenAiCompatibleRequestAndReadsBase64Result() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JSONObject> requestBody = new AtomicReference<>();
        start(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(JSONUtil.parseObj(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            respond(exchange, 200, "{\"id\":\"img-1\",\"data\":[{\"b64_json\":\"aGVsbG8=\"}]}");
        });

        OpenAiImagesProvider provider = provider();
        AiProviderResult result = provider.execute(request("3:2", "hd", "auto", "png", null));

        assertEquals("Bearer test-key", authorization.get());
        assertEquals("gpt-image-2", requestBody.get().getStr("model"));
        assertEquals("1536x1024", requestBody.get().getStr("size"));
        assertEquals("high", requestBody.get().getStr("quality"));
        assertEquals("auto", requestBody.get().getStr("background"));
        assertEquals("png", requestBody.get().getStr("output_format"));
        assertNull(requestBody.get().get("output_compression"));
        assertEquals("aGVsbG8=", result.imageBase64());
        assertEquals("image/png", result.imageContentType());
        assertNull(result.imageUrl());
    }

    @Test
    void readsUrlResultFromCompatibleEndpoint() throws Exception {
        start(exchange -> respond(exchange, 200, "{\"data\":[{\"url\":\"https://example.test/image.png\"}]}"));
        AiProviderResult result = provider().execute(request("1:1", "standard", "auto", "png", null));
        assertEquals("https://example.test/image.png", result.imageUrl());
        assertNull(result.imageBase64());
    }

    @Test
    void rejectsUnsupportedOutpaintWithoutCallingEndpoint() throws Exception {
        start(exchange -> respond(exchange, 500, "{}"));
        AiProviderRequest request = new AiProviderRequest("outpaint", "gpt-image-1", "extend", "1:1",
                "standard", "auto", "png", null, null, null);
        assertEquals("provider_capability_unsupported",
                assertThrows(AiProviderException.class, () -> provider().execute(request)).getCode());
    }

    @Test
    void mapsForbiddenResponseToPermissionDenied() throws Exception {
        start(exchange -> respond(exchange, 403,
                "{\"error\":{\"message\":\"Image generation is not enabled for this group.\"}}"));

        AiProviderException exception = assertThrows(AiProviderException.class,
                () -> provider().execute(request("1:1", "standard")));

        assertEquals("provider_permission_denied", exception.getCode());
        assertEquals("Image generation is not enabled for this group.", exception.getMessage());
    }

    @Test
    void sendsWebpCompressionAndMarksBase64ContentType() throws Exception {
        AtomicReference<JSONObject> requestBody = new AtomicReference<>();
        start(exchange -> {
            requestBody.set(JSONUtil.parseObj(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            respond(exchange, 200, "{\"data\":[{\"b64_json\":\"aGVsbG8=\"}]}");
        });

        AiProviderResult result = provider().execute(request("2:3", "hd", "transparent", "webp", 92));

        assertEquals("1024x1536", requestBody.get().getStr("size"));
        assertEquals("transparent", requestBody.get().getStr("background"));
        assertEquals("webp", requestBody.get().getStr("output_format"));
        assertEquals(92, requestBody.get().getInt("output_compression"));
        assertEquals("image/webp", result.imageContentType());
    }

    @Test
    void rejectsApproximateAspectRatio() throws Exception {
        start(exchange -> respond(exchange, 500, "{}"));

        AiProviderException exception = assertThrows(AiProviderException.class,
                () -> provider().execute(request("16:9", "hd", "auto", "png", null)));

        assertEquals("provider_parameter_unsupported", exception.getCode());
    }

    @Test
    void normalizesConfiguredBaseUrl() {
        assertEquals("https://claudenb.com/v1/images/generations", OpenAiImagesProvider.generationsUrl("https://claudenb.com/"));
        assertEquals("https://claudenb.com/v1/images/generations", OpenAiImagesProvider.generationsUrl("https://claudenb.com/v1"));
    }

    @Test
    void rejectsQuotedBaseUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> OpenAiImagesProvider.generationsUrl("\"https://claudenb.com\""));
    }

    private OpenAiImagesProvider provider() {
        return new OpenAiImagesProvider("test-key", "http://127.0.0.1:" + server.getAddress().getPort(), 5000);
    }

    private static AiProviderRequest request(String ratio, String quality) {
        return request(ratio, quality, "auto", "png", null);
    }

    private static AiProviderRequest request(String ratio, String quality, String background,
                                             String outputFormat, Integer outputCompression) {
        return new AiProviderRequest("generate", "gpt-image-2", "a teacup", ratio, quality,
                background, outputFormat, outputCompression, null, null);
    }

    private void start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generations", exchange -> handler.handle(exchange));
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
