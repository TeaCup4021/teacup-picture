package com.teacup.teacuppicturebackend.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;
import java.io.InputStream;

public interface PictureStorage {
    StoredPicture store(MultipartFile file, long spaceId);

    StoredPicture importUrl(String url, long spaceId);

    StoredObject previewUrl(String url);

    StoredPicture store(InputStream input, String fileName, String contentType, long spaceId);

    StoredObject load(String objectKey);

    String temporaryUrl(String objectKey);

    void delete(String objectKey);
    default void deleteStrict(String objectKey) { delete(objectKey); }

    record StoredPicture(String objectKey, String thumbnailObjectKey, long size, int width, int height, String format,
                         String contentType, String checksum) {
        public StoredPicture(String objectKey, long size, int width, int height, String format,
                             String contentType, String checksum) {
            this(objectKey, null, size, width, height, format, contentType, checksum);
        }
    }

    record StoredObject(Resource resource, long size, String contentType, String fileName) {}
}
