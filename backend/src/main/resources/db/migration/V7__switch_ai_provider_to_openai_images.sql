UPDATE `ai_model`
SET `enabled` = 0
WHERE `provider` = 'aliyun';

INSERT INTO `ai_model` (`code`, `displayName`, `provider`, `providerModel`, `capabilities`,
                        `supportedRatios`, `supportedQualities`, `supportsReference`, `quotaCost`, `enabled`)
VALUES ('openai-image', 'OpenAI Images', 'openai', 'gpt-image-1', '["generate"]',
        '["1:1","3:2","2:3"]', '["standard","hd"]', 0, 1, 1)
ON DUPLICATE KEY UPDATE
    `displayName` = VALUES(`displayName`),
    `provider` = VALUES(`provider`),
    `providerModel` = VALUES(`providerModel`),
    `capabilities` = VALUES(`capabilities`),
    `supportedRatios` = VALUES(`supportedRatios`),
    `supportedQualities` = VALUES(`supportedQualities`),
    `supportsReference` = VALUES(`supportsReference`),
    `quotaCost` = VALUES(`quotaCost`),
    `enabled` = VALUES(`enabled`);
