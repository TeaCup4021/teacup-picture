package com.teacup.teacuppicturebackend.storage;

import com.teacup.teacuppicturebackend.api.v1.V1Exception;
import com.teacup.teacuppicturebackend.config.PictureStorageConfig;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.ComposeObjectArgs;
import io.minio.ComposeSource;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class MinioPictureStorage implements PictureStorage, ResumablePictureStorage {
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final Set<String> FORMATS = Set.of("jpeg", "jpg", "png", "webp");

    private final MinioClient client;
    private final PictureStorageConfig config;

    public MinioPictureStorage(MinioClient client, PictureStorageConfig config) {
        this.client = client;
        this.config = config;
    }

    @Override
    public StoredPicture store(MultipartFile file, long spaceId) {
        if (file == null || file.isEmpty()) throw V1Exception.badRequest("请选择图片文件");
        if (file.getSize() > MAX_BYTES) throw tooLarge();
        Path temporary = temporaryFile();
        try (InputStream input = file.getInputStream()) {
            Files.copy(new LimitedInputStream(input, MAX_BYTES), temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return validateAndUpload(temporary, file.getOriginalFilename(), spaceId);
        } catch (V1Exception exception) {
            throw exception;
        } catch (IOException exception) {
            if ("image too large".equals(exception.getMessage())) throw tooLarge();
            throw V1Exception.badRequest("无法读取图片文件");
        } finally {
            deleteTemporary(temporary);
        }
    }

    @Override
    public StoredPicture importUrl(String value, long spaceId) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException exception) {
            throw V1Exception.badRequest("图片 URL 无效");
        }
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null) {
            throw V1Exception.badRequest("仅支持 HTTP 或 HTTPS 图片 URL");
        }
        rejectPrivateAddress(uri.getHost());
        Path temporary = temporaryFile();
        try {
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "TeacupPicture/1.0");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw V1Exception.badRequest("无法下载图片 URL");
            if (connection.getContentLengthLong() > MAX_BYTES) throw tooLarge();
            try (InputStream input = connection.getInputStream()) {
                Files.copy(new LimitedInputStream(input, MAX_BYTES), temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return validateAndUpload(temporary, uri.getPath(), spaceId);
        } catch (V1Exception exception) {
            throw exception;
        } catch (IOException exception) {
            if ("image too large".equals(exception.getMessage())) throw tooLarge();
            throw V1Exception.badRequest("无法下载图片 URL");
        } finally {
            deleteTemporary(temporary);
        }
    }

    @Override
    public StoredObject previewUrl(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException exception) {
            throw V1Exception.badRequest("图片 URL 无效");
        }
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null) {
            throw V1Exception.badRequest("仅支持 HTTP 或 HTTPS 图片 URL");
        }
        rejectPrivateAddress(uri.getHost());
        try {
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (TeacupPicture URL Preview)");
            connection.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/jpeg,image/png,image/*,*/*;q=0.8");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw V1Exception.badRequest("无法下载图片 URL");
            if (connection.getContentLengthLong() > MAX_BYTES) throw tooLarge();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                new LimitedInputStream(input, MAX_BYTES).transferTo(output);
            }
            byte[] bytes = output.toByteArray();
            String contentType = previewContentType(connection.getContentType(), uri.getPath());
            if (ImageIO.read(new ByteArrayInputStream(bytes)) == null) {
                throw V1Exception.badRequest("图片 URL 返回的内容不是有效图片");
            }
            String extension = contentType.substring("image/".length());
            return new StoredObject(new ByteArrayResource(bytes), bytes.length, contentType, "preview." + extension);
        } catch (V1Exception exception) {
            throw exception;
        } catch (IOException exception) {
            if ("image too large".equals(exception.getMessage())) throw tooLarge();
            throw V1Exception.badRequest("无法下载图片 URL");
        }
    }

    private static String previewContentType(String remoteType, String path) {
        String type = remoteType == null ? "" : remoteType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (Set.of("image/jpeg", "image/png", "image/webp").contains(type)) return type;
        String format = formatFromFileName(path);
        return "image/" + ("jpg".equals(format) ? "jpeg" : format);
    }

    @Override
    public StoredPicture store(InputStream input, String fileName, String contentType, long spaceId) {
        Path temporary = temporaryFile();
        try (InputStream source = input) {
            Files.copy(new LimitedInputStream(source, MAX_BYTES), temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return validateAndUpload(temporary, fileName, spaceId);
        } catch (V1Exception exception) {
            throw exception;
        } catch (IOException exception) {
            if ("image too large".equals(exception.getMessage())) throw tooLarge();
            throw V1Exception.badRequest("鏃犳硶璇诲彇鍥剧墖鏂囦欢");
        } finally {
            deleteTemporary(temporary);
        }
    }

    @Override
    public StoredObject load(String objectKey) {
        validateObjectKey(objectKey);
        try {
            StatObjectResponse stat = client.statObject(StatObjectArgs.builder()
                    .bucket(config.getBucket()).object(objectKey).build());
            GetObjectResponse response = client.getObject(GetObjectArgs.builder()
                    .bucket(config.getBucket()).object(objectKey).build());
            String contentType = stat.contentType() == null ? "application/octet-stream" : stat.contentType();
            return new StoredObject(new InputStreamResource(response), stat.size(), contentType, fileName(objectKey));
        } catch (Exception exception) {
            log.warn("Unable to load picture object key={}", objectKey, exception);
            throw V1Exception.notFound();
        }
    }

    @Override
    public String temporaryUrl(String objectKey) {
        validateObjectKey(objectKey);
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET).bucket(config.getBucket()).object(objectKey).expiry(15 * 60).build());
        } catch (Exception exception) {
            log.error("Unable to create temporary picture URL key={}", objectKey, exception);
            throw new V1Exception(HttpStatus.SERVICE_UNAVAILABLE, 50300, "图片存储暂不可用");
        }
    }

    @Override
    public void delete(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return;
        validateObjectKey(objectKey);
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(config.getBucket()).object(objectKey).build());
        } catch (Exception exception) {
            log.warn("Unable to delete picture object key={}", objectKey, exception);
        }
    }

    @Override
    public void deleteStrict(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return;
        validateObjectKey(objectKey);
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(config.getBucket()).object(objectKey).build());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to delete picture object", exception);
        }
    }

    @Override
    public String createUpload(long spaceId) {
        if (spaceId <= 0) throw V1Exception.badRequest("空间无效");
        return "upload-sessions/" + UUID.randomUUID() + "/";
    }

    @Override
    public String uploadPart(String storagePrefix, int partNumber, InputStream input, long size, String checksum) {
        validateUploadPrefix(storagePrefix);
        if (partNumber < 1 || size < 1 || size > 5L * 1024 * 1024) throw V1Exception.badRequest("分片参数无效");
        String objectKey = storagePrefix + "part-" + partNumber;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream source = new java.security.DigestInputStream(new LimitedInputStream(input, 5L * 1024 * 1024), digest)) {
                client.putObject(PutObjectArgs.builder().bucket(config.getBucket()).object(objectKey)
                        .contentType("application/octet-stream").stream(source, size, -1).build());
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (checksum != null && !checksum.isBlank() && !actual.equalsIgnoreCase(checksum)) {
                delete(objectKey);
                throw V1Exception.badRequest("分片校验失败");
            }
            return actual;
        } catch (V1Exception exception) {
            throw exception;
        } catch (Exception exception) {
            delete(objectKey);
            log.error("Unable to store resumable upload part", exception);
            throw new V1Exception(HttpStatus.SERVICE_UNAVAILABLE, 50300, "图片存储暂不可用");
        }
    }

    @Override
    public StoredPicture completeUpload(String storagePrefix, List<ResumablePictureStorage.Part> parts,
                                        String fileName, String contentType, long spaceId) {
        validateUploadPrefix(storagePrefix);
        if (parts == null || parts.isEmpty()) throw V1Exception.badRequest("上传分片不能为空");
        String assembledKey = storagePrefix + "assembled";
        try {
            List<ComposeSource> sources = parts.stream()
                    .map(part -> ComposeSource.builder().bucket(config.getBucket())
                            .object(storagePrefix + "part-" + part.partNumber()).build())
                    .toList();
            client.composeObject(ComposeObjectArgs.builder().bucket(config.getBucket()).object(assembledKey)
                    .sources(sources).build());
            try (GetObjectResponse input = client.getObject(GetObjectArgs.builder()
                    .bucket(config.getBucket()).object(assembledKey).build())) {
                return store(input, fileName, contentType, spaceId);
            }
        } catch (V1Exception exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("Unable to complete resumable upload", exception);
            throw new V1Exception(HttpStatus.SERVICE_UNAVAILABLE, 50300, "图片存储暂不可用");
        } finally {
            delete(assembledKey);
            abortUpload(storagePrefix, parts.stream().map(ResumablePictureStorage.Part::partNumber).toList());
        }
    }

    @Override
    public void abortUpload(String storagePrefix, List<Integer> partNumbers) {
        validateUploadPrefix(storagePrefix);
        if (partNumbers != null) {
            for (Integer partNumber : partNumbers) {
                if (partNumber != null && partNumber > 0) delete(storagePrefix + "part-" + partNumber);
            }
        }
        delete(storagePrefix + "assembled");
    }

    private StoredPicture validateAndUpload(Path file, String fileName, long spaceId) {
        String objectKey = null;
        String thumbnailObjectKey = null;
        try {
            long size = Files.size(file);
            if (size > MAX_BYTES) throw tooLarge();
            String format;
            format = formatFromFileName(fileName);
            String normalizedFormat = "jpg".equals(format) ? "jpeg" : format;
            PictureImageSupport.DecodedImage decoded = PictureImageSupport.readValidated(file, normalizedFormat);
            int width = decoded.width();
            int height = decoded.height();
            byte[] thumbnail = PictureImageSupport.thumbnailBytes(decoded.image());
            String prefix = "spaces/" + spaceId + "/pictures/" + UUID.randomUUID() + "/";
            objectKey = prefix + "original." + normalizedFormat;
            String thumbnailFormat = "jpeg";
            thumbnailObjectKey = prefix + "thumbnail.jpeg";
            String normalizedContentType = "image/" + normalizedFormat;
            try (InputStream input = Files.newInputStream(file)) {
                client.putObject(PutObjectArgs.builder().bucket(config.getBucket()).object(objectKey)
                        .contentType(normalizedContentType).stream(input, size, -1).build());
            }
            try (InputStream input = new ByteArrayInputStream(thumbnail)) {
                client.putObject(PutObjectArgs.builder().bucket(config.getBucket()).object(thumbnailObjectKey)
                        .contentType("image/" + thumbnailFormat).stream(input, thumbnail.length, -1).build());
            }
            return new StoredPicture(objectKey, thumbnailObjectKey, size, width, height, normalizedFormat,
                    normalizedContentType, checksum(file));
        } catch (V1Exception exception) {
            delete(objectKey);
            delete(thumbnailObjectKey);
            throw exception;
        } catch (Exception exception) {
            delete(objectKey);
            delete(thumbnailObjectKey);
            log.error("Unable to store picture in MinIO", exception);
            throw new V1Exception(HttpStatus.SERVICE_UNAVAILABLE, 50300, "图片存储暂不可用");
        }
    }

    static String formatFromFileName(String fileName) {
        String extension = "";
        if (fileName != null && fileName.contains(".")) extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!FORMATS.contains(extension)) throw unsupported("仅支持 JPEG、PNG 和 WebP");
        return extension;
    }

    private String checksum(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void rejectPrivateAddress(String host) {
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

    private void validateObjectKey(String objectKey) {
        if (objectKey == null || !(objectKey.matches("spaces/[0-9]+/pictures/[a-f0-9-]+/(original|thumbnail)\\.(jpeg|png|webp)")
                || objectKey.matches("upload-sessions/[a-f0-9-]+/(part-[0-9]+|assembled)"))) {
            throw V1Exception.notFound();
        }
    }

    private static Path temporaryFile() {
        try {
            return Files.createTempFile("teacup-picture-", ".upload");
        } catch (IOException exception) {
            throw new V1Exception(HttpStatus.SERVICE_UNAVAILABLE, 50300, "图片存储暂不可用");
        }
    }

    private static void deleteTemporary(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            log.warn("Unable to delete temporary picture file {}", file, exception);
        }
    }

    private static String fileName(String objectKey) { return objectKey.substring(objectKey.lastIndexOf('/') + 1); }
    private static void validateUploadPrefix(String prefix) {
        if (prefix == null || !prefix.matches("upload-sessions/[a-f0-9-]+/")) throw V1Exception.notFound();
    }
    private static V1Exception tooLarge() { return new V1Exception(HttpStatus.PAYLOAD_TOO_LARGE, 41300, "图片不能超过 20 MB"); }
    private static V1Exception unsupported(String message) { return new V1Exception(HttpStatus.UNSUPPORTED_MEDIA_TYPE, 41500, message); }
    private static final class LimitedInputStream extends FilterInputStream {
        private final long maximum;
        private long count;
        private LimitedInputStream(InputStream input, long maximum) { super(input); this.maximum = maximum; }
        @Override public int read() throws IOException { int value = super.read(); if (value >= 0) increment(1); return value; }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length); if (read > 0) increment(read); return read;
        }
        private void increment(int amount) throws IOException { count += amount; if (count > maximum) throw new IOException("image too large"); }
    }
}
