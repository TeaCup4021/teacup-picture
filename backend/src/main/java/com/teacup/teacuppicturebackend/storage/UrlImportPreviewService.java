package com.teacup.teacuppicturebackend.storage;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.teacup.teacuppicturebackend.api.v1.V1Exception;
import com.teacup.teacuppicturebackend.model.entity.User;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class UrlImportPreviewService {
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final long MAX_CACHE_BYTES = 100L * 1024 * 1024;
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(10);

    private final Cache<String, PreviewEntry> previews = Caffeine.newBuilder()
            .maximumWeight(MAX_CACHE_BYTES)
            .weigher((String token, PreviewEntry entry) -> (int) Math.min(entry.size(), Integer.MAX_VALUE))
            .expireAfterWrite(PREVIEW_TTL)
            .scheduler(Scheduler.systemScheduler())
            .removalListener((String token, PreviewEntry entry, RemovalCause cause) -> {
                if (entry != null && !entry.claimed().get()) deleteTemporary(entry.path());
            })
            .build();

    public Preview preview(User user, String value) {
        if (user == null || user.getId() == null) throw V1Exception.unauthorized();
        URI uri = validateUrl(value);
        Path temporary = temporaryFile();
        try {
            String remoteContentType = download(uri, temporary);
            String format = format(uri, normalizedContentType(remoteContentType));
            String contentType = "image/" + format;
            String fileName = fileName(uri, format);
            PictureImageSupport.DecodedImage decoded = PictureImageSupport.readValidated(temporary, format);
            byte[] preview = PictureImageSupport.thumbnailBytes(decoded.image());
            String token = registerPreview(user.getId(), uri.toString(), temporary, fileName, contentType);
            temporary = null;
            return new Preview(new ByteArrayResource(preview), preview.length, "image/jpeg", token);
        } catch (V1Exception exception) {
            throw exception;
        } catch (IOException exception) {
            if ("image too large".equals(exception.getMessage())) throw tooLarge();
            throw V1Exception.badRequest("无法下载图片 URL");
        } finally {
            deleteTemporary(temporary);
        }
    }

    public ClaimedPreview claim(User user, String token, String value) {
        if (user == null || user.getId() == null) throw V1Exception.unauthorized();
        if (token == null || token.isBlank()) throw V1Exception.badRequest("预览已失效，请重新预览图片 URL");
        String normalizedUrl = validateUrl(value).toString();
        AtomicReference<PreviewEntry> claimedEntry = new AtomicReference<>();
        previews.asMap().computeIfPresent(token, (ignored, entry) -> {
            if (entry.userId().equals(user.getId()) && entry.url().equals(normalizedUrl)) {
                entry.claimed().set(true);
                claimedEntry.set(entry);
                return null;
            }
            return entry;
        });
        PreviewEntry entry = claimedEntry.get();
        if (entry == null) {
            PreviewEntry existing = previews.getIfPresent(token);
            if (existing != null) throw V1Exception.badRequest("图片 URL 与预览不一致，请重新预览");
            throw V1Exception.badRequest("预览已失效，请重新预览图片 URL");
        }
        if (!Files.isRegularFile(entry.path())) {
            throw V1Exception.badRequest("预览已失效，请重新预览图片 URL");
        }
        return new ClaimedPreview(entry.path(), entry.fileName(), entry.contentType());
    }

    String registerPreview(Long userId, String url, Path path, String fileName, String contentType) throws IOException {
        String token = UUID.randomUUID().toString();
        previews.put(token, new PreviewEntry(userId, url, path, fileName, contentType, Files.size(path),
                new AtomicBoolean(false)));
        return token;
    }

    @PreDestroy
    void discardPreviews() {
        previews.invalidateAll();
        previews.cleanUp();
    }

    private static URI validateUrl(String value) {
        URI uri;
        try {
            uri = URI.create(value == null ? "" : value.trim());
        } catch (RuntimeException exception) {
            throw V1Exception.badRequest("图片 URL 无效");
        }
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null) {
            throw V1Exception.badRequest("仅支持 HTTP 或 HTTPS 图片 URL");
        }
        rejectPrivateAddress(uri.getHost());
        return uri;
    }

    private static String download(URI uri, Path temporary) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "TeacupPicture/1.0");
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw V1Exception.badRequest("无法下载图片 URL");
        if (connection.getContentLengthLong() > MAX_BYTES) throw tooLarge();
        String contentType = connection.getContentType();
        try (InputStream input = connection.getInputStream()) {
            Files.copy(new LimitedInputStream(input, MAX_BYTES), temporary, StandardCopyOption.REPLACE_EXISTING);
        }
        return contentType;
    }

    private static String fileName(URI uri, String format) {
        String path = uri.getPath();
        int separator = path == null ? -1 : path.lastIndexOf('/');
        String name = separator < 0 ? path : path.substring(separator + 1);
        if (name == null || name.isBlank() || !name.contains(".")) return "import." + format;
        return name;
    }

    private static String format(URI uri, String contentType) {
        try {
            String fromName = MinioPictureStorage.formatFromFileName(uri.getPath());
            return "jpg".equals(fromName) ? "jpeg" : fromName;
        } catch (V1Exception exception) {
            if (contentType != null) return contentType.substring("image/".length());
            throw exception;
        }
    }

    private static String normalizedContentType(String remoteType) {
        String type = remoteType == null ? "" : remoteType.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        if (Set.of("image/jpeg", "image/png", "image/webp").contains(type)) return type;
        return null;
    }

    private static void rejectPrivateAddress(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                    throw V1Exception.badRequest("不允许访问内网图片地址");
                }
            }
        } catch (IOException exception) {
            throw V1Exception.badRequest("无法解析图片地址");
        }
    }

    private static Path temporaryFile() {
        try {
            return Files.createTempFile("teacup-picture-preview-", ".upload");
        } catch (IOException exception) {
            throw new V1Exception(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, 50300, "图片存储暂不可用");
        }
    }

    private static void deleteTemporary(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Expired previews are best-effort cleanup only; no URL or token is logged.
        }
    }

    private static V1Exception tooLarge() {
        return new V1Exception(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, 41300, "图片不能超过 20 MB");
    }

    public record Preview(ByteArrayResource resource, long size, String contentType, String token) {}

    public record ClaimedPreview(Path path, String fileName, String contentType) implements AutoCloseable {
        public InputStream openStream() throws IOException {
            return Files.newInputStream(path);
        }

        @Override
        public void close() {
            deleteTemporary(path);
        }
    }

    private record PreviewEntry(Long userId, String url, Path path, String fileName, String contentType, long size,
                                AtomicBoolean claimed) {}

    private static final class LimitedInputStream extends FilterInputStream {
        private final long maximum;
        private long count;

        private LimitedInputStream(InputStream input, long maximum) {
            super(input);
            this.maximum = maximum;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) increment(1);
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) increment(read);
            return read;
        }

        private void increment(int amount) throws IOException {
            count += amount;
            if (count > maximum) throw new IOException("image too large");
        }
    }
}
