UPDATE `ai_model`
SET `providerModel` = 'gpt-image-2'
WHERE `code` = 'openai-image'
  AND `provider` = 'openai'
  AND `providerModel` = 'gpt-image-1';
