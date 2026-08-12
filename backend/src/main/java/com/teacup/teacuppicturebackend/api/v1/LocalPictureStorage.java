package com.teacup.teacuppicturebackend.api.v1;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
public class LocalPictureStorage {
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final Set<String> FORMATS = Set.of("jpeg", "jpg", "png", "webp");
    private final Path root;
    private final String publicBaseUrl;

    public LocalPictureStorage(@Value("${teacup.storage.local-root:./data/pictures}") String localRoot,
                               @Value("${teacup.storage.public-base-url:http://127.0.0.1:8123/api/v1/assets}") String publicBaseUrl) {
        this.root = Path.of(localRoot).toAbsolutePath().normalize();
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    public StoredPicture store(MultipartFile file) {
        if (file == null || file.isEmpty()) throw V1Exception.badRequest("请选择图片文件");
        if (file.getSize() > MAX_BYTES) {
            throw new V1Exception(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, 41300, "图片不能超过 20 MB");
        }
        try (InputStream input = file.getInputStream()) {
            String format = verifyFormat(input, file.getOriginalFilename(), file.getContentType());
            return persist(file.getInputStream(), format, file.getSize());
        } catch (IOException exception) {
            throw V1Exception.badRequest("无法读取图片文件");
        }
    }

    public StoredPicture importUrl(String value) {
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
            connection.setRequestProperty("User-Agent", "TeacupPicture/1.0");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw V1Exception.badRequest("无法下载图片 URL");
            long declaredSize = connection.getContentLengthLong();
            if (declaredSize > MAX_BYTES) {
                throw new V1Exception(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, 41300, "图片不能超过 20 MB");
            }
            Path temporary = Files.createTempFile("teacup-import-", ".image");
            try (InputStream input = connection.getInputStream()) {
                Files.copy(new LimitedInputStream(input, MAX_BYTES), temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            long size = Files.size(temporary);
            try (InputStream input = Files.newInputStream(temporary)) {
                String format = verifyFormat(input, uri.getPath(), connection.getContentType());
                try (InputStream savedInput = Files.newInputStream(temporary)) {
                    return persist(savedInput, format, size);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (V1Exception exception) {
            throw exception;
        } catch (IOException exception) {
            throw V1Exception.badRequest("无法下载图片 URL");
        }
    }

    public Resource load(String fileName) {
        if (fileName == null || !fileName.matches("[a-f0-9-]+\\.(jpeg|jpg|png|webp)")) throw V1Exception.notFound();
        try {
            Path file = root.resolve(fileName).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) throw V1Exception.notFound();
            return new UrlResource(file.toUri());
        } catch (IOException exception) {
            throw V1Exception.notFound();
        }
    }

    public MediaType mediaType(String fileName) {
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1);
        if (extension.equals("png")) return MediaType.IMAGE_PNG;
        if (extension.equals("webp")) return MediaType.parseMediaType("image/webp");
        return MediaType.IMAGE_JPEG;
    }

    private StoredPicture persist(InputStream input, String format, long size) throws IOException {
        Files.createDirectories(root);
        String normalizedFormat = format.equals("jpg") ? "jpeg" : format;
        String fileName = UUID.randomUUID() + "." + normalizedFormat;
        Path target = root.resolve(fileName);
        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        try (InputStream verify = Files.newInputStream(target)) {
            int[] dimensions = dimensions(verify, normalizedFormat);
            return new StoredPicture(publicBaseUrl + "/" + fileName, size, dimensions[0], dimensions[1], normalizedFormat);
        }
    }

    private String verifyFormat(InputStream input, String fileName, String contentType) throws IOException {
        byte[] header = input.readNBytes(32);
        String extension = "";
        if (fileName != null && fileName.contains(".")) extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!FORMATS.contains(extension)) {
            if ("image/png".equalsIgnoreCase(contentType)) extension = "png";
            else if ("image/webp".equalsIgnoreCase(contentType)) extension = "webp";
            else if ("image/jpeg".equalsIgnoreCase(contentType)) extension = "jpeg";
        }
        if (!FORMATS.contains(extension)) {
            throw new V1Exception(org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE, 41500, "仅支持 JPEG、PNG 和 WebP");
        }
        boolean jpeg = header.length >= 3 && (header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8 && (header[2] & 0xff) == 0xff;
        boolean png = header.length >= 8 && header[0] == (byte) 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G';
        boolean webp = header.length >= 12 && ascii(header, 0, "RIFF") && ascii(header, 8, "WEBP");
        if ((extension.equals("png") && !png) || (extension.equals("webp") && !webp)
                || ((extension.equals("jpeg") || extension.equals("jpg")) && !jpeg)) {
            throw new V1Exception(org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE, 41500, "图片内容与格式不匹配");
        }
        return extension;
    }

    private int[] dimensions(InputStream input, String format) throws IOException {
        byte[] bytes = input.readAllBytes();
        if (!"webp".equals(format)) {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) throw new V1Exception(org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE, 41500, "无法解析图片");
            return new int[]{image.getWidth(), image.getHeight()};
        }
        if (bytes.length < 30) throw new V1Exception(org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE, 41500, "无法解析 WebP 图片");
        String chunk = new String(bytes, 12, 4, StandardCharsets.US_ASCII);
        if ("VP8X".equals(chunk)) return new int[]{1 + le24(bytes, 24), 1 + le24(bytes, 27)};
        if ("VP8L".equals(chunk) && bytes.length >= 25) {
            int bits = (bytes[21] & 0xff) | ((bytes[22] & 0xff) << 8) | ((bytes[23] & 0xff) << 16) | ((bytes[24] & 0xff) << 24);
            return new int[]{(bits & 0x3fff) + 1, ((bits >> 14) & 0x3fff) + 1};
        }
        if ("VP8 ".equals(chunk) && bytes.length >= 30) {
            return new int[]{((bytes[27] & 0xff) << 8 | (bytes[26] & 0xff)) & 0x3fff,
                    ((bytes[29] & 0xff) << 8 | (bytes[28] & 0xff)) & 0x3fff};
        }
        throw new V1Exception(org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE, 41500, "无法解析 WebP 图片");
    }

    private static boolean ascii(byte[] bytes, int offset, String value) {
        if (bytes.length < offset + value.length()) return false;
        for (int i = 0; i < value.length(); i++) if (bytes[offset + i] != (byte) value.charAt(i)) return false;
        return true;
    }

    private static int le24(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8) | ((bytes[offset + 2] & 0xff) << 16);
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

    public record StoredPicture(String url, long size, int width, int height, String format) {}

    private static final class LimitedInputStream extends java.io.FilterInputStream {
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
