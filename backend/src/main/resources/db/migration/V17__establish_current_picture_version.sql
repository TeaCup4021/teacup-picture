ALTER TABLE `picture`
    ADD COLUMN `currentVersionId` BIGINT NULL AFTER `spaceId`,
    ADD KEY `idx_picture_current_version` (`currentVersionId`);

INSERT INTO `picture_version` (
    `pictureId`, `versionNumber`, `name`, `note`, `sourceType`, `parentVersionId`,
    `editorState`, `schemaVersion`, `assetObjectKey`, `thumbnailObjectKey`,
    `contentType`, `width`, `height`, `size`, `creatorId`
)
SELECT
    p.`id`,
    COALESCE((SELECT MAX(vn.`versionNumber`) FROM `picture_version` vn WHERE vn.`pictureId` = p.`id`), 0) + 1,
    '当前图片基线',
    'M6 版本绑定基线',
    'original',
    NULL,
    JSON_OBJECT(
        'schemaVersion', 3,
        'canvas', JSON_OBJECT('width', GREATEST(COALESCE(p.`picWidth`, 1), 1), 'height', GREATEST(COALESCE(p.`picHeight`, 1), 1)),
        'transform', JSON_OBJECT('rotation', 0, 'scale', 1, 'flipX', FALSE, 'flipY', FALSE),
        'crop', NULL,
        'adjustments', JSON_OBJECT(
            'exposure', 0, 'brightness', 0, 'contrast', 0, 'highlights', 0, 'shadows', 0,
            'saturation', 0, 'vibrance', 0, 'temperature', 0, 'tint', 0, 'sharpness', 0,
            'fade', 0, 'vignette', 0, 'enhance', 0, 'dehaze', 0
        ),
        'layers', JSON_ARRAY()
    ),
    3,
    p.`objectKey`,
    p.`thumbnailObjectKey`,
    p.`contentType`,
    GREATEST(COALESCE(p.`picWidth`, 1), 1),
    GREATEST(COALESCE(p.`picHeight`, 1), 1),
    COALESCE(p.`picSize`, 0),
    p.`userId`
FROM `picture` p
WHERE p.`isDelete` = 0
  AND p.`objectKey` IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM `picture_version` existing
      WHERE existing.`pictureId` = p.`id`
        AND existing.`assetObjectKey` = p.`objectKey`
  );

UPDATE `picture` p
JOIN (
    SELECT pv.`pictureId`, MAX(pv.`id`) AS `currentVersionId`
    FROM `picture_version` pv
    JOIN `picture` source
      ON source.`id` = pv.`pictureId`
     AND source.`objectKey` = pv.`assetObjectKey`
    WHERE source.`isDelete` = 0
    GROUP BY pv.`pictureId`
) resolved ON resolved.`pictureId` = p.`id`
SET p.`currentVersionId` = resolved.`currentVersionId`;
