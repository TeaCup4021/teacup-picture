ALTER TABLE `ai_task`
    ADD COLUMN `attemptCount` INT NOT NULL DEFAULT 0 AFTER `invocationStarted`,
    ADD COLUMN `workerId` VARCHAR(128) NULL AFTER `attemptCount`,
    ADD COLUMN `leaseUntil` DATETIME NULL AFTER `workerId`,
    ADD COLUMN `nextAttemptAt` DATETIME NULL AFTER `leaseUntil`,
    ADD KEY `idx_ai_task_execution_lease` (`status`, `leaseUntil`, `id`);

UPDATE `ai_task`
SET `nextAttemptAt` = CURRENT_TIMESTAMP
WHERE `status` = 'queued' AND `nextAttemptAt` IS NULL;

UPDATE `ai_task`
SET `leaseUntil` = DATE_ADD(COALESCE(`startTime`, `updateTime`), INTERVAL 15 MINUTE)
WHERE `status` = 'running' AND `leaseUntil` IS NULL;

CREATE TABLE `ai_task_outbox` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `eventId` CHAR(36) NOT NULL,
    `taskId` BIGINT NOT NULL,
    `status` VARCHAR(20) NOT NULL,
    `attemptCount` INT NOT NULL DEFAULT 0,
    `nextAttemptAt` DATETIME NOT NULL,
    `lockOwner` VARCHAR(128) NULL,
    `lockUntil` DATETIME NULL,
    `publishedAt` DATETIME NULL,
    `lastError` VARCHAR(500) NULL,
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ai_task_outbox_event` (`eventId`),
    KEY `idx_ai_task_outbox_due` (`status`, `nextAttemptAt`, `lockUntil`, `id`),
    KEY `idx_ai_task_outbox_task` (`taskId`, `id`),
    CONSTRAINT `fk_ai_task_outbox_task` FOREIGN KEY (`taskId`) REFERENCES `ai_task` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

INSERT INTO `ai_task_outbox` (`eventId`, `taskId`, `status`, `attemptCount`, `nextAttemptAt`)
SELECT UUID(), `id`, 'pending', 0, CURRENT_TIMESTAMP
FROM `ai_task`
WHERE `status` = 'queued';
