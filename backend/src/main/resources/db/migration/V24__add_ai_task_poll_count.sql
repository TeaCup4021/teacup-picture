ALTER TABLE `ai_task`
    ADD COLUMN `pollCount` INT NOT NULL DEFAULT 0 AFTER `attemptCount`;

-- 轮询轮次不消耗「调用上游」的次数上限。
-- 若把异步上游的每次结果查询都算进 attemptCount，一个正常等待中的任务会在上游还没出图时
-- 就被判定为「重试次数用尽」而失败，因此提交次数与轮询次数必须分开计数、各自校验上限。
