ALTER TABLE `ai_task`
    ADD COLUMN `executionToken` BIGINT NOT NULL DEFAULT 0 AFTER `workerId`;

ALTER TABLE `ai_task`
    ADD KEY `idx_ai_task_worker` (`status`, `workerId`),
    ADD KEY `idx_ai_task_quota_pending` (`quotaSettled`, `quotaRefunded`, `createTime`, `id`);

ALTER TABLE `ai_task`
    MODIFY COLUMN `workerId` VARCHAR(160) NULL;

CREATE TABLE `ai_task_quota_audit` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `taskId` BIGINT NOT NULL,
    `userId` BIGINT NOT NULL,
    `taskType` VARCHAR(32) NOT NULL,
    `quotaCost` INT NOT NULL,
    `usageDate` DATE NOT NULL,
    `action` VARCHAR(32) NOT NULL,
    `reason` VARCHAR(128) NOT NULL,
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_ai_task_quota_audit_task` (`taskId`, `id`),
    KEY `idx_ai_task_quota_audit_time` (`createTime`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
