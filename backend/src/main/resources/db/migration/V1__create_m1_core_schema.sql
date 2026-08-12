CREATE TABLE `user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `userAccount` VARCHAR(256) NOT NULL,
    `userPassword` VARCHAR(512) NOT NULL,
    `userName` VARCHAR(256) NOT NULL,
    `userAvatar` VARCHAR(1024) NULL,
    `userProfile` VARCHAR(512) NULL,
    `userRole` VARCHAR(32) NOT NULL DEFAULT 'user',
    `editTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `isDelete` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_account` (`userAccount`),
    KEY `idx_user_name` (`userName`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE `space` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `spaceName` VARCHAR(30) NOT NULL,
    `spaceLevel` INT NOT NULL DEFAULT 0,
    `spaceType` INT NOT NULL DEFAULT 0,
    `maxSize` BIGINT NOT NULL,
    `maxCount` BIGINT NOT NULL,
    `totalSize` BIGINT NOT NULL DEFAULT 0,
    `totalCount` BIGINT NOT NULL DEFAULT 0,
    `userId` BIGINT NOT NULL,
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `editTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `isDelete` TINYINT NOT NULL DEFAULT 0,
    `activePersonalOwnerId` BIGINT GENERATED ALWAYS AS (
        CASE WHEN `spaceType` = 0 AND `isDelete` = 0 THEN `userId` ELSE NULL END
    ) STORED,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_space_active_personal_owner` (`activePersonalOwnerId`),
    KEY `idx_space_user_type` (`userId`, `spaceType`),
    CONSTRAINT `fk_space_user` FOREIGN KEY (`userId`) REFERENCES `user` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE `space_user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `spaceId` BIGINT NOT NULL,
    `userId` BIGINT NOT NULL,
    `spaceRole` VARCHAR(32) NOT NULL,
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_space_user` (`spaceId`, `userId`),
    KEY `idx_space_user_user` (`userId`),
    CONSTRAINT `fk_space_user_space` FOREIGN KEY (`spaceId`) REFERENCES `space` (`id`),
    CONSTRAINT `fk_space_user_member` FOREIGN KEY (`userId`) REFERENCES `user` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE `picture` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `url` VARCHAR(1024) NOT NULL,
    `thumbnailUrl` VARCHAR(1024) NULL,
    `name` VARCHAR(128) NOT NULL,
    `introduction` VARCHAR(512) NULL,
    `category` VARCHAR(64) NULL,
    `tags` VARCHAR(512) NULL,
    `picSize` BIGINT NULL,
    `picWidth` INT NULL,
    `picHeight` INT NULL,
    `picScale` DOUBLE NULL,
    `picFormat` VARCHAR(32) NULL,
    `picColor` VARCHAR(32) NULL,
    `userId` BIGINT NOT NULL,
    `spaceId` BIGINT NULL,
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `editTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `isDelete` TINYINT NOT NULL DEFAULT 0,
    `reviewStatus` INT NOT NULL DEFAULT 0,
    `reviewMessage` VARCHAR(512) NULL,
    `reviewerId` BIGINT NULL,
    `reviewTime` DATETIME NULL,
    PRIMARY KEY (`id`),
    KEY `idx_picture_name` (`name`),
    KEY `idx_picture_category` (`category`),
    KEY `idx_picture_user` (`userId`),
    KEY `idx_picture_space_created` (`spaceId`, `createTime`, `id`),
    KEY `idx_picture_public_review` (`reviewStatus`, `isDelete`, `createTime`, `id`),
    CONSTRAINT `fk_picture_user` FOREIGN KEY (`userId`) REFERENCES `user` (`id`),
    CONSTRAINT `fk_picture_space` FOREIGN KEY (`spaceId`) REFERENCES `space` (`id`),
    CONSTRAINT `fk_picture_reviewer` FOREIGN KEY (`reviewerId`) REFERENCES `user` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
