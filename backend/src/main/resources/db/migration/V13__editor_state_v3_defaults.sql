-- EditorState v3 adds explicit flip flags and persistent text-box widths.
-- Existing v2 JSON remains readable and is upgraded by M3Service on access.
ALTER TABLE `picture_draft`
    MODIFY COLUMN `schemaVersion` INT NOT NULL DEFAULT 3;

ALTER TABLE `picture_version`
    MODIFY COLUMN `schemaVersion` INT NOT NULL DEFAULT 3;
