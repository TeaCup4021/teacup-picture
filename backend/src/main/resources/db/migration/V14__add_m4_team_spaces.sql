ALTER TABLE `space`
    ADD COLUMN `ownerId` BIGINT NULL AFTER `userId`;

UPDATE `space` SET `ownerId` = `userId` WHERE `ownerId` IS NULL;

ALTER TABLE `space`
    MODIFY COLUMN `ownerId` BIGINT NOT NULL,
    ADD KEY `idx_space_owner_type` (`ownerId`, `spaceType`, `isDelete`),
    ADD CONSTRAINT `fk_space_owner` FOREIGN KEY (`ownerId`) REFERENCES `user` (`id`);

INSERT INTO `space_user` (`spaceId`, `userId`, `spaceRole`)
SELECT s.`id`, s.`ownerId`, 'owner'
FROM `space` s
WHERE s.`spaceType` = 1
  AND s.`isDelete` = 0
  AND NOT EXISTS (
      SELECT 1 FROM `space_user` su
      WHERE su.`spaceId` = s.`id` AND su.`userId` = s.`ownerId`
  );

CREATE TABLE `space_invitation` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `spaceId` BIGINT NOT NULL,
    `inviterId` BIGINT NOT NULL,
    `inviteeId` BIGINT NOT NULL,
    `spaceRole` VARCHAR(32) NOT NULL,
    `status` VARCHAR(16) NOT NULL,
    `expiresAt` DATETIME NOT NULL,
    `respondedAt` DATETIME NULL,
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `activePendingInviteeId` BIGINT GENERATED ALWAYS AS (
        CASE WHEN `status` = 'pending' THEN `inviteeId` ELSE NULL END
    ) STORED,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_space_invitation_active_pending` (`spaceId`, `activePendingInviteeId`),
    KEY `idx_space_invitation_invitee_status` (`inviteeId`, `status`, `createTime`, `id`),
    KEY `idx_space_invitation_space_status` (`spaceId`, `status`, `createTime`, `id`),
    CONSTRAINT `fk_space_invitation_space` FOREIGN KEY (`spaceId`) REFERENCES `space` (`id`),
    CONSTRAINT `fk_space_invitation_inviter` FOREIGN KEY (`inviterId`) REFERENCES `user` (`id`),
    CONSTRAINT `fk_space_invitation_invitee` FOREIGN KEY (`inviteeId`) REFERENCES `user` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE `notification` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `userId` BIGINT NOT NULL,
    `type` VARCHAR(48) NOT NULL,
    `actorId` BIGINT NULL,
    `resourceType` VARCHAR(48) NOT NULL,
    `resourceId` BIGINT NULL,
    `payload` JSON NOT NULL,
    `readAt` DATETIME NULL,
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_notification_user_read_created` (`userId`, `readAt`, `createTime`, `id`),
    CONSTRAINT `fk_notification_user` FOREIGN KEY (`userId`) REFERENCES `user` (`id`),
    CONSTRAINT `fk_notification_actor` FOREIGN KEY (`actorId`) REFERENCES `user` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
