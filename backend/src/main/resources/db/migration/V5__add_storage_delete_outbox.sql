CREATE TABLE `storage_delete_outbox` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `pictureId` BIGINT NULL,
    `objectKey` VARCHAR(512) NOT NULL,
    `thumbnailObjectKey` VARCHAR(512) NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'pending',
    `retryCount` INT NOT NULL DEFAULT 0,
    `lastError` VARCHAR(500) NULL,
    `nextAttemptAt` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_storage_delete_outbox_due` (`status`, `nextAttemptAt`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
