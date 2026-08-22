CREATE TABLE `collaboration_room` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `pictureId` BIGINT NOT NULL,
    `baseVersionId` BIGINT NULL,
    `roomEpoch` VARCHAR(64) NOT NULL,
    `lastSeq` BIGINT NOT NULL DEFAULT 0,
    `status` VARCHAR(16) NOT NULL DEFAULT 'active',
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_collaboration_room_picture` (`pictureId`),
    KEY `idx_collaboration_room_epoch` (`roomEpoch`),
    CONSTRAINT `fk_collaboration_room_picture` FOREIGN KEY (`pictureId`) REFERENCES `picture` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE `collaboration_update` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `roomId` BIGINT NOT NULL,
    `operationId` VARCHAR(128) NOT NULL,
    `serverSeq` BIGINT NOT NULL,
    `actorId` BIGINT NOT NULL,
    `gestureId` VARCHAR(128) NULL,
    `kind` VARCHAR(48) NOT NULL,
    `targetId` VARCHAR(128) NULL,
    `changedFields` JSON NOT NULL,
    `phase` VARCHAR(16) NOT NULL,
    `yjsUpdate` MEDIUMTEXT NOT NULL,
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_collaboration_update_operation` (`roomId`, `operationId`),
    UNIQUE KEY `uk_collaboration_update_sequence` (`roomId`, `serverSeq`),
    KEY `idx_collaboration_update_room_seq` (`roomId`, `serverSeq`),
    CONSTRAINT `fk_collaboration_update_room` FOREIGN KEY (`roomId`) REFERENCES `collaboration_room` (`id`),
    CONSTRAINT `fk_collaboration_update_actor` FOREIGN KEY (`actorId`) REFERENCES `user` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE `collaboration_snapshot` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `roomId` BIGINT NOT NULL,
    `lastSeq` BIGINT NOT NULL,
    `yjsState` MEDIUMTEXT NOT NULL,
    `editorState` LONGTEXT NULL,
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_collaboration_snapshot_room_seq` (`roomId`, `lastSeq`),
    KEY `idx_collaboration_snapshot_room_created` (`roomId`, `createTime`),
    CONSTRAINT `fk_collaboration_snapshot_room` FOREIGN KEY (`roomId`) REFERENCES `collaboration_room` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
