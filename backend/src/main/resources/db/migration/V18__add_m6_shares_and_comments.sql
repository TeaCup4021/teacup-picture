CREATE TABLE `picture_share` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `pictureId` BIGINT NOT NULL,
    `creatorId` BIGINT NOT NULL,
    `publicId` CHAR(22) NOT NULL,
    `secretHash` CHAR(64) NOT NULL,
    `passwordHash` VARCHAR(100) NULL,
    `expiresAt` DATETIME NULL,
    `revokedAt` DATETIME NULL,
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `activePictureId` BIGINT GENERATED ALWAYS AS (
        CASE WHEN `revokedAt` IS NULL THEN `pictureId` ELSE NULL END
    ) STORED,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_picture_share_public_id` (`publicId`),
    UNIQUE KEY `uk_picture_share_active_picture` (`activePictureId`),
    KEY `idx_picture_share_picture_created` (`pictureId`, `createTime`, `id`),
    CONSTRAINT `fk_picture_share_picture` FOREIGN KEY (`pictureId`) REFERENCES `picture` (`id`),
    CONSTRAINT `fk_picture_share_creator` FOREIGN KEY (`creatorId`) REFERENCES `user` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE `picture_comment` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `pictureId` BIGINT NOT NULL,
    `pictureVersionId` BIGINT NULL,
    `authorId` BIGINT NOT NULL,
    `rootId` BIGINT NULL,
    `replyToId` BIGINT NULL,
    `kind` VARCHAR(16) NOT NULL,
    `body` VARCHAR(2000) NOT NULL,
    `positionX` DECIMAL(9,8) NULL,
    `positionY` DECIMAL(9,8) NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'active',
    `resolvedAt` DATETIME NULL,
    `resolvedBy` BIGINT NULL,
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_picture_comment_picture_root_created` (`pictureId`, `rootId`, `createTime`, `id`),
    KEY `idx_picture_comment_root_created` (`rootId`, `createTime`, `id`),
    KEY `idx_picture_comment_version` (`pictureVersionId`, `id`),
    CONSTRAINT `fk_picture_comment_picture` FOREIGN KEY (`pictureId`) REFERENCES `picture` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_picture_comment_version` FOREIGN KEY (`pictureVersionId`) REFERENCES `picture_version` (`id`),
    CONSTRAINT `fk_picture_comment_author` FOREIGN KEY (`authorId`) REFERENCES `user` (`id`),
    CONSTRAINT `fk_picture_comment_root` FOREIGN KEY (`rootId`) REFERENCES `picture_comment` (`id`),
    CONSTRAINT `fk_picture_comment_reply_to` FOREIGN KEY (`replyToId`) REFERENCES `picture_comment` (`id`),
    CONSTRAINT `fk_picture_comment_resolver` FOREIGN KEY (`resolvedBy`) REFERENCES `user` (`id`),
    CONSTRAINT `chk_picture_comment_position` CHECK (
        (`kind` = 'comment' AND `positionX` IS NULL AND `positionY` IS NULL)
        OR (`kind` = 'annotation' AND `positionX` BETWEEN 0 AND 1 AND `positionY` BETWEEN 0 AND 1)
        OR (`kind` = 'reply' AND `positionX` IS NULL AND `positionY` IS NULL)
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE `comment_mention` (
    `commentId` BIGINT NOT NULL,
    `userId` BIGINT NOT NULL,
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`commentId`, `userId`),
    KEY `idx_comment_mention_user` (`userId`, `createTime`, `commentId`),
    CONSTRAINT `fk_comment_mention_comment` FOREIGN KEY (`commentId`) REFERENCES `picture_comment` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_comment_mention_user` FOREIGN KEY (`userId`) REFERENCES `user` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
