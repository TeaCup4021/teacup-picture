CREATE TABLE `picture_upload_session` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `userId` BIGINT NOT NULL,
    `spaceId` BIGINT NOT NULL,
    `storagePrefix` VARCHAR(180) NOT NULL,
    `fileName` VARCHAR(255) NOT NULL,
    `contentType` VARCHAR(100) NULL,
    `name` VARCHAR(80) NULL,
    `introduction` VARCHAR(300) NULL,
    `category` VARCHAR(80) NULL,
    `tags` TEXT NULL,
    `totalSize` BIGINT NOT NULL,
    `chunkSize` INT NOT NULL,
    `totalParts` INT NOT NULL,
    `fileChecksum` CHAR(64) NULL,
    `status` VARCHAR(20) NOT NULL,
    `expiresAt` DATETIME NOT NULL,
    `completedPictureId` BIGINT NULL,
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_picture_upload_session_user_status` (`userId`, `status`, `expiresAt`),
    KEY `idx_picture_upload_session_expires` (`status`, `expiresAt`),
    CONSTRAINT `fk_picture_upload_session_user` FOREIGN KEY (`userId`) REFERENCES `user` (`id`),
    CONSTRAINT `fk_picture_upload_session_space` FOREIGN KEY (`spaceId`) REFERENCES `space` (`id`)
);

CREATE TABLE `picture_upload_part` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `sessionId` BIGINT NOT NULL,
    `partNumber` INT NOT NULL,
    `etag` VARCHAR(255) NOT NULL,
    `size` BIGINT NOT NULL,
    `checksum` CHAR(64) NULL,
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_picture_upload_part_session_number` (`sessionId`, `partNumber`),
    CONSTRAINT `fk_picture_upload_part_session` FOREIGN KEY (`sessionId`) REFERENCES `picture_upload_session` (`id`) ON DELETE CASCADE
);
