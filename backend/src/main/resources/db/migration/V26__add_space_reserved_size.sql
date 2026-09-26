-- 空间额度增加「在途预留」维度。
--
-- 分片上传的额度在完成阶段才计入 totalSize，而上传会话从创建到完成之间可能持续数小时。
-- 只判断 totalSize 的话，这段时间额度没有被占住：用户可以同时开出多个会话，
-- 每个都在建会话时通过校验，全部完成之后就超额占用空间。
--
-- 预留约束为 totalSize + reservedSize + 本次声明大小 <= maxSize，
-- 于是任何时刻「已用 + 在途 + 本次」都不会超过上限。
ALTER TABLE `space`
    ADD COLUMN `reservedSize` BIGINT NOT NULL DEFAULT 0 AFTER `totalCount`;

-- 回填：当前仍处于活跃状态的上传会话已经占用了额度（它们完成时会写入 totalSize），
-- 必须计入预留，否则存量会话会绕过预留校验继续造成超额。
UPDATE `space` s
LEFT JOIN (
    SELECT `spaceId`, SUM(`totalSize`) AS `reserved`
      FROM `picture_upload_session`
     WHERE `status` = 'active'
     GROUP BY `spaceId`
) r ON r.`spaceId` = s.`id`
SET s.`reservedSize` = COALESCE(r.`reserved`, 0);
