package com.teacup.teacuppicturebackend.storage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.teacup.teacuppicturebackend.mapper.StorageDeleteOutboxMapper;
import com.teacup.teacuppicturebackend.model.entity.Picture;
import com.teacup.teacuppicturebackend.model.entity.StorageDeleteOutbox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class PictureStorageDeleteService {
    @Resource private StorageDeleteOutboxMapper outboxMapper;
    @Resource private PictureStorage storage;

    @Transactional
    public void enqueue(Picture picture) {
        if (picture == null) return;
        enqueueAssets(picture.getId(), picture.getObjectKey(), picture.getThumbnailObjectKey());
    }

    /** Inserts a durable cleanup task in the caller's transaction. */
    @Transactional
    public void enqueueAssets(Long pictureId, String objectKey, String thumbnailObjectKey) {
        if (objectKey == null || objectKey.isBlank()) return;
        StorageDeleteOutbox row = new StorageDeleteOutbox();
        row.setPictureId(pictureId); row.setObjectKey(objectKey);
        row.setThumbnailObjectKey(thumbnailObjectKey); row.setStatus("pending");
        row.setRetryCount(0); row.setNextAttemptAt(new Date()); outboxMapper.insert(row);
    }

    @Scheduled(fixedDelay = 30000L)
    public void processDue() {
        List<StorageDeleteOutbox> rows = outboxMapper.selectList(new LambdaQueryWrapper<StorageDeleteOutbox>()
                .in(StorageDeleteOutbox::getStatus, "pending", "failed")
                .le(StorageDeleteOutbox::getNextAttemptAt, new Date())
                .orderByAsc(StorageDeleteOutbox::getId).last("LIMIT 50"));
        rows.forEach(this::process);
    }

    private void process(StorageDeleteOutbox row) {
        try {
            storage.deleteStrict(row.getObjectKey()); storage.deleteStrict(row.getThumbnailObjectKey());
            row.setStatus("done"); row.setLastError(null); outboxMapper.updateById(row);
        } catch (RuntimeException exception) {
            int retry = row.getRetryCount() == null ? 0 : row.getRetryCount(); row.setRetryCount(retry + 1);
            row.setStatus("failed"); row.setLastError(trim(exception.getMessage(), 500));
            long delay = Math.min(3600L, 30L * (1L << Math.min(retry, 7)));
            row.setNextAttemptAt(new Date(System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(delay)));
            outboxMapper.updateById(row); log.warn("Picture storage delete retry scheduled, outboxId={}", row.getId());
        }
    }
    private static String trim(String value, int max) { if (value == null) return "unknown"; return value.length() <= max ? value : value.substring(0, max); }
}
