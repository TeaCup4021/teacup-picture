-- M3-R replaces the draft/version document contract with EditorState v2.
-- Existing rows are intentionally not rewritten here; the service rejects v1
-- payloads so an old draft can never be silently mixed into a v2 edit session.
ALTER TABLE `picture_draft`
    MODIFY COLUMN `schemaVersion` INT NOT NULL DEFAULT 2;

ALTER TABLE `picture_version`
    MODIFY COLUMN `schemaVersion` INT NOT NULL DEFAULT 2;
