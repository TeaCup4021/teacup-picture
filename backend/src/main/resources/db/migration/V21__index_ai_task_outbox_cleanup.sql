ALTER TABLE `ai_task_outbox`
    ADD KEY `idx_ai_task_outbox_published` (`status`, `publishedAt`, `id`);
