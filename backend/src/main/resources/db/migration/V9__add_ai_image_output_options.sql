ALTER TABLE `ai_model`
    ADD COLUMN `supportedBackgrounds` VARCHAR(128) NOT NULL DEFAULT '["auto"]' AFTER `supportedQualities`,
    ADD COLUMN `supportedOutputFormats` VARCHAR(128) NOT NULL DEFAULT '["png"]' AFTER `supportedBackgrounds`,
    ADD COLUMN `supportsOutputCompression` TINYINT NOT NULL DEFAULT 0 AFTER `supportedOutputFormats`;

ALTER TABLE `ai_task`
    ADD COLUMN `background` VARCHAR(16) NOT NULL DEFAULT 'auto' AFTER `quality`,
    ADD COLUMN `outputFormat` VARCHAR(16) NOT NULL DEFAULT 'png' AFTER `background`,
    ADD COLUMN `outputCompression` INT NULL AFTER `outputFormat`;

UPDATE `ai_model`
SET `supportedBackgrounds` = '["auto","opaque","transparent"]',
    `supportedOutputFormats` = '["png","jpeg","webp"]',
    `supportsOutputCompression` = 1
WHERE `code` = 'openai-image'
  AND `provider` = 'openai';
