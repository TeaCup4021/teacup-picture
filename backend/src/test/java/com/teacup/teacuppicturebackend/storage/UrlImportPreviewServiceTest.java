package com.teacup.teacuppicturebackend.storage;

import com.teacup.teacuppicturebackend.api.v1.V1Exception;
import com.teacup.teacuppicturebackend.model.entity.User;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlImportPreviewServiceTest {
    @Test
    void previewTokenIsBoundToTheUserAndUrlAndCanOnlyBeClaimedOnce() throws Exception {
        UrlImportPreviewService service = new UrlImportPreviewService();
        Path file = Files.createTempFile("url-preview-test-", ".png");
        Files.write(file, new byte[]{1, 2, 3});
        User owner = user(11L);
        String url = "https://example.com/photo.png";
        String token = service.registerPreview(owner.getId(), url, file, "photo.png", "image/png");

        assertEquals(40000, assertThrows(V1Exception.class,
                () -> service.claim(user(12L), token, url)).getCode());
        assertEquals(40000, assertThrows(V1Exception.class,
                () -> service.claim(owner, token, "https://example.com/other.png")).getCode());

        try (UrlImportPreviewService.ClaimedPreview claimed = service.claim(owner, token, url)) {
            assertTrue(Files.exists(file));
            try (java.io.InputStream input = claimed.openStream()) {
                assertEquals(3, input.readAllBytes().length);
            }
        }
        assertFalse(Files.exists(file));
        assertEquals(40000, assertThrows(V1Exception.class,
                () -> service.claim(owner, token, url)).getCode());
        service.discardPreviews();
    }

    private static User user(long id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
