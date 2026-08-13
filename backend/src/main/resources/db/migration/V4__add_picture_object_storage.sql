ALTER TABLE `picture`
    ADD COLUMN `storageProvider` VARCHAR(32) NULL AFTER `thumbnailUrl`,
    ADD COLUMN `objectKey` VARCHAR(512) NULL AFTER `storageProvider`,
    ADD COLUMN `thumbnailObjectKey` VARCHAR(512) NULL AFTER `objectKey`,
    ADD COLUMN `contentType` VARCHAR(128) NULL AFTER `thumbnailObjectKey`,
    ADD COLUMN `checksum` VARCHAR(64) NULL AFTER `contentType`;

CREATE UNIQUE INDEX `uk_picture_object_key`
    ON `picture` (`objectKey`);
