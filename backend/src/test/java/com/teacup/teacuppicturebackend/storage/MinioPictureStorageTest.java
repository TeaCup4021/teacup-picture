package com.teacup.teacuppicturebackend.storage;

import com.teacup.teacuppicturebackend.api.v1.V1Exception;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MinioPictureStorageTest {
    @Test
    void determinesFormatOnlyFromFileNameSuffix() {
        assertEquals("png", MinioPictureStorage.formatFromFileName("photo.PNG"));
        assertEquals("jpeg", MinioPictureStorage.formatFromFileName("photo.jpeg"));
        assertEquals("jpg", MinioPictureStorage.formatFromFileName("photo.jpg"));
        assertEquals("webp", MinioPictureStorage.formatFromFileName("photo.webp"));
    }

    @Test
    void rejectsMissingOrUnsupportedFileNameSuffix() {
        assertThrows(V1Exception.class, () -> MinioPictureStorage.formatFromFileName("photo"));
        assertThrows(V1Exception.class, () -> MinioPictureStorage.formatFromFileName("photo.gif"));
    }
}
