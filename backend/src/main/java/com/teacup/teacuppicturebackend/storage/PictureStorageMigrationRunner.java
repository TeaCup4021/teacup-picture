package com.teacup.teacuppicturebackend.storage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.teacup.teacuppicturebackend.api.v1.LocalPictureStorage;
import com.teacup.teacuppicturebackend.mapper.PictureMapper;
import com.teacup.teacuppicturebackend.model.entity.Picture;
import com.teacup.teacuppicturebackend.config.PictureStorageConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.List;

@Slf4j
@Component
public class PictureStorageMigrationRunner implements CommandLineRunner {
    private final PictureMapper pictureMapper;
    private final PictureStorage storage;
    private final LocalPictureStorage legacyStorage;
    private final boolean enabled;
    private final String baseUrl;

    public PictureStorageMigrationRunner(PictureMapper pictureMapper, PictureStorage storage,
                                         LocalPictureStorage legacyStorage,
                                         PictureStorageConfig config,
                                         @Value("${teacup.storage.migration.enabled:false}") boolean enabled) {
        this.pictureMapper = pictureMapper; this.storage = storage; this.legacyStorage = legacyStorage; this.enabled = enabled;
        this.baseUrl = config.getPublicBaseUrl().replaceAll("/+$", "");
    }

    @Override
    public void run(String... args) {
        if (!enabled) return;
        List<Picture> pictures = pictureMapper.selectList(new LambdaQueryWrapper<Picture>()
                .eq(Picture::getIsDelete, 0).isNull(Picture::getObjectKey));
        int processed = 0, skipped = 0, failed = 0;
        for (Picture picture : pictures) {
            try {
                migrate(picture);
                processed++;
            } catch (IllegalArgumentException exception) {
                skipped++;
            } catch (RuntimeException exception) {
                failed++;
                log.warn("Picture migration failed, pictureId={}", picture.getId(), exception);
            }
        }
        log.info("Picture migration finished, processed={}, skipped={}, failed={}", processed, skipped, failed);
    }

    @Transactional
    protected void migrate(Picture picture) {
        if (picture.getObjectKey() != null) throw new IllegalArgumentException("already migrated");
        Resource resource = legacyStorage.loadUrl(picture.getUrl());
        String fileName = legacyStorage.fileName(picture.getUrl());
        PictureStorage.StoredPicture stored;
        try (InputStream input = resource.getInputStream()) {
            stored = storage.store(input, fileName, legacyStorage.mediaType(fileName).toString(), picture.getSpaceId() == null ? 0L : picture.getSpaceId());
        } catch (Exception exception) {
            throw new IllegalStateException("legacy resource unavailable", exception);
        }
        try {
            picture.setStorageProvider("minio"); picture.setObjectKey(stored.objectKey());
            picture.setThumbnailObjectKey(stored.thumbnailObjectKey()); picture.setContentType(stored.contentType());
            picture.setChecksum(stored.checksum()); picture.setUrl(baseUrl + "/pictures/" + picture.getId() + "/content?variant=original");
            picture.setThumbnailUrl(baseUrl + "/pictures/" + picture.getId() + "/content?variant=thumbnail");
            if (pictureMapper.updateById(picture) != 1) throw new IllegalStateException("picture metadata update failed");
        } catch (RuntimeException exception) {
            storage.delete(stored.objectKey());
            storage.delete(stored.thumbnailObjectKey());
            throw exception;
        }
    }
}
