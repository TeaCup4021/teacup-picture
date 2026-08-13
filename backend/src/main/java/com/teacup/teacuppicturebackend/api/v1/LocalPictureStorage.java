package com.teacup.teacuppicturebackend.api.v1;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Read-only adapter used exclusively by the one-shot MinIO migration runner. */
@Component
public class LocalPictureStorage {
    private final Path root;
    private final String publicBaseUrl;

    public LocalPictureStorage(@Value("${teacup.storage.legacy-local-root:./data/pictures}") String localRoot,
                               @Value("${teacup.storage.legacy-public-base-url:http://127.0.0.1:8123/api/v1/public/assets}") String publicBaseUrl) {
        this.root = Path.of(localRoot).toAbsolutePath().normalize();
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    public Resource loadUrl(String url) {
        return load(fileName(url));
    }

    public String fileName(String url) {
        if (url == null || !url.startsWith(publicBaseUrl + "/")) throw V1Exception.notFound();
        return url.substring((publicBaseUrl + "/").length());
    }

    public MediaType mediaType(String fileName) {
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        if ("png".equals(extension)) return MediaType.IMAGE_PNG;
        if ("webp".equals(extension)) return MediaType.parseMediaType("image/webp");
        return MediaType.IMAGE_JPEG;
    }

    private Resource load(String fileName) {
        if (fileName == null || !fileName.matches("[a-f0-9-]+\\.(jpeg|jpg|png|webp)")) throw V1Exception.notFound();
        try {
            Path file = root.resolve(fileName).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) throw V1Exception.notFound();
            return new UrlResource(file.toUri());
        } catch (IOException exception) {
            throw V1Exception.notFound();
        }
    }
}
