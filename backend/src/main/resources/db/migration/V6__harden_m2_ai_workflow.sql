ALTER TABLE `ai_quota_usage`
    ADD COLUMN `reservedCount` INT NOT NULL DEFAULT 0 AFTER `usedCount`;

ALTER TABLE `ai_task`
    ADD COLUMN `idempotencyKey` VARCHAR(128) NULL AFTER `userId`,
    ADD COLUMN `quotaSettled` TINYINT NOT NULL DEFAULT 0 AFTER `quotaRefunded`,
    ADD UNIQUE KEY `uk_ai_task_user_idempotency` (`userId`, `idempotencyKey`);
