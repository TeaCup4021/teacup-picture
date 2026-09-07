package com.teacup.teacuppicturebackend.storage;

import java.io.InputStream;
import java.util.List;

public interface ResumablePictureStorage {
    String createUpload(long spaceId);

    String uploadPart(String storagePrefix, int partNumber, InputStream input, long size, String checksum);

    PictureStorage.StoredPicture completeUpload(String storagePrefix, List<Part> parts, String fileName,
                                                String contentType, long spaceId);

    void abortUpload(String storagePrefix, List<Integer> partNumbers);

    record Part(int partNumber, String etag, long size) {}
}
