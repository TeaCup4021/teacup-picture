ALTER TABLE `picture`
    ADD COLUMN `visibility` VARCHAR(16) NOT NULL DEFAULT 'private' AFTER `isDelete`,
    ADD COLUMN `publishStatus` VARCHAR(32) NOT NULL DEFAULT 'not_requested' AFTER `visibility`,
    ADD COLUMN `publishedAt` DATETIME NULL AFTER `publishStatus`;

CREATE INDEX `idx_picture_public_cursor`
    ON `picture` (`visibility`, `publishStatus`, `isDelete`, `publishedAt`, `id`);

CREATE TABLE `publish_request` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `pictureId` BIGINT NOT NULL,
    `requesterId` BIGINT NOT NULL,
    `status` VARCHAR(32) NOT NULL,
    `reviewerId` BIGINT NULL,
    `decisionReason` VARCHAR(500) NULL,
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `reviewTime` DATETIME NULL,
    `activePendingPictureId` BIGINT GENERATED ALWAYS AS (
        CASE WHEN `status` = 'pending' THEN `pictureId` ELSE NULL END
    ) STORED,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_publish_request_active_pending` (`activePendingPictureId`),
    KEY `idx_publish_request_status_created` (`status`, `createTime`, `id`),
    KEY `idx_publish_request_picture_created` (`pictureId`, `createTime`, `id`),
    CONSTRAINT `fk_publish_request_picture` FOREIGN KEY (`pictureId`) REFERENCES `picture` (`id`),
    CONSTRAINT `fk_publish_request_requester` FOREIGN KEY (`requesterId`) REFERENCES `user` (`id`),
    CONSTRAINT `fk_publish_request_reviewer` FOREIGN KEY (`reviewerId`) REFERENCES `user` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
