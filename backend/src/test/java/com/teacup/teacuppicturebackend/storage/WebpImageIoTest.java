package com.teacup.teacuppicturebackend.storage;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WebpImageIoTest {
    @Test
    void webpReaderIsAvailableForThumbnailGeneration() {
        assertTrue(ImageIO.getImageReadersByMIMEType("image/webp").hasNext());
    }
}
