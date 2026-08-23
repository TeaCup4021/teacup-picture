ALTER TABLE `collaboration_room`
    ADD COLUMN `baseEditorState` LONGTEXT NULL AFTER `baseVersionId`;

ALTER TABLE `collaboration_update`
    ADD COLUMN `lockToken` VARCHAR(128) NULL AFTER `targetId`;
