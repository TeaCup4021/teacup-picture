package com.teacup.teacuppicturebackend.storage;

import com.teacup.teacuppicturebackend.api.v1.V1Exception;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;

final class PictureImageSupport {
    static final int MAX_DIMENSION = 16_384;
    static final long MAX_PIXELS = 40_000_000L;

    private PictureImageSupport() {
    }

    static DecodedImage readValidated(Path file, String format) {
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) throw unsupported("无法解析图片");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw unsupported("当前服务无法解析该图片格式");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (!normalizeFormat(reader.getFormatName()).equals(normalizeFormat(format))) {
                    throw unsupported("图片扩展名与实际格式不一致");
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateDimensions(width, height);
                ImageReadParam parameters = reader.getDefaultReadParam();
                int sample = Math.max(1, Math.max(width, height) / 640);
                parameters.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage image = reader.read(0, parameters);
                if (image == null) throw unsupported("无法解析图片");
                return new DecodedImage(image, width, height);
            } finally {
                reader.dispose();
            }
        } catch (V1Exception exception) {
            throw exception;
        } catch (IOException exception) {
            throw unsupported("无法解析图片");
        }
    }

    private static String normalizeFormat(String format) {
        String normalized = format.toLowerCase(java.util.Locale.ROOT);
        return "jpg".equals(normalized) ? "jpeg" : normalized;
    }

    static byte[] thumbnailBytes(BufferedImage image) throws IOException {
        int max = Math.max(image.getWidth(), image.getHeight());
        int targetWidth = max <= 640 ? image.getWidth() : Math.max(1, image.getWidth() * 640 / max);
        int targetHeight = max <= 640 ? image.getHeight() : Math.max(1, image.getHeight() * 640 / max);
        BufferedImage thumbnail = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = thumbnail.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(thumbnail, "jpeg", output)) throw new IOException("thumbnail encoder unavailable");
        return output.toByteArray();
    }

    static void validateDimensions(int width, int height) {
        if (width < 1 || height < 1) throw unsupported("无法解析图片");
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw tooLarge("图片最长边不能超过 " + MAX_DIMENSION + " 像素");
        }
        if ((long) width * height > MAX_PIXELS) {
            throw tooLarge("图片总像素不能超过 4000 万");
        }
    }

    private static V1Exception unsupported(String message) {
        return new V1Exception(org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE, 41500, message);
    }

    private static V1Exception tooLarge(String message) {
        return new V1Exception(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, 41300, message);
    }

    record DecodedImage(BufferedImage image, int width, int height) {}
}
