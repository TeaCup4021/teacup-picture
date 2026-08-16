ALTER TABLE `picture_draft`
    ADD COLUMN `revision` BIGINT NOT NULL DEFAULT 1 AFTER `schemaVersion`;
