package com.teacup.teacuppicturebackend.api.v1;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.teacup.teacuppicturebackend.api.v1.model.M1Dtos;
import com.teacup.teacuppicturebackend.api.v1.model.M3Dtos;
import com.teacup.teacuppicturebackend.mapper.PictureDraftMapper;
import com.teacup.teacuppicturebackend.mapper.PictureMapper;
import com.teacup.teacuppicturebackend.mapper.PictureVersionMapper;
import com.teacup.teacuppicturebackend.mapper.PublishRequestMapper;
import com.teacup.teacuppicturebackend.mapper.SpaceMapper;
import com.teacup.teacuppicturebackend.mapper.UserMapper;
import com.teacup.teacuppicturebackend.model.entity.Picture;
import com.teacup.teacuppicturebackend.model.entity.PictureDraft;
import com.teacup.teacuppicturebackend.model.entity.PictureVersion;
import com.teacup.teacuppicturebackend.model.entity.PublishRequest;
import com.teacup.teacuppicturebackend.model.entity.Space;
import com.teacup.teacuppicturebackend.model.entity.User;
import com.teacup.teacuppicturebackend.service.UserService;
import com.teacup.teacuppicturebackend.storage.PictureAssetService;
import com.teacup.teacuppicturebackend.storage.PictureStorage;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class M3Service {
    private static final int MAX_EDITOR_STATE_CHARS = 4_000_000;
    private static final int MAX_LAYERS = 500;
    private static final int MAX_PATH_COMMANDS = 20_000;
    private static final int EDITOR_SCHEMA_VERSION = 3;
    private static final Set<String> SOURCE_TYPES = Set.of("original", "user_save", "restore", "ai_generate", "ai_outpaint", "team_confirm");
    private static final Set<String> DOCUMENT_FIELDS = Set.of("schemaVersion", "canvas", "transform", "crop", "adjustments", "layers");
    private static final Set<String> ADJUSTMENT_FIELDS = Set.of("exposure", "brightness", "contrast", "highlights", "shadows",
            "saturation", "vibrance", "temperature", "tint", "sharpness", "fade", "vignette", "enhance", "dehaze");
    private static final Set<String> NON_NEGATIVE_ADJUSTMENTS = Set.of("fade", "enhance", "dehaze");
    private static final Set<String> TEXT_LAYER_FIELDS = Set.of("id", "type", "text", "left", "top", "fontSize", "width", "color",
            "fontFamily", "fontWeight", "angle", "scaleX", "scaleY", "flipX", "flipY");
    private static final Set<String> DRAWING_LAYER_FIELDS = Set.of("id", "type", "tool", "color", "size", "opacity", "path",
            "left", "top", "scaleX", "scaleY", "flipX", "flipY", "angle");
    private static final Set<String> DRAWING_TOOLS = Set.of("pen", "marker", "eraser");

    private final PictureMapper pictureMapper;
    private final PictureDraftMapper draftMapper;
    private final PictureVersionMapper versionMapper;
    private final PublishRequestMapper publishRequestMapper;
    private final SpaceMapper spaceMapper;
    private final UserMapper userMapper;
    private final UserService userService;
    private final PictureStorage storage;
    private final PictureAssetService assets;
    private final ObjectMapper objectMapper;
    private final SpaceAccessService spaceAccess;

    @Autowired
    public M3Service(PictureMapper pictureMapper, PictureDraftMapper draftMapper, PictureVersionMapper versionMapper,
                     PublishRequestMapper publishRequestMapper, SpaceMapper spaceMapper,
                     UserMapper userMapper, UserService userService, PictureStorage storage,
                     PictureAssetService assets, ObjectMapper objectMapper, SpaceAccessService spaceAccess) {
        this.pictureMapper = pictureMapper;
        this.draftMapper = draftMapper;
        this.versionMapper = versionMapper;
        this.publishRequestMapper = publishRequestMapper;
        this.spaceMapper = spaceMapper;
        this.userMapper = userMapper;
        this.userService = userService;
        this.storage = storage;
        this.assets = assets;
        this.objectMapper = objectMapper;
        this.spaceAccess = spaceAccess;
    }

    /** Kept for pre-M4 focused unit tests; runtime injection uses SpaceAccessService. */
    public M3Service(PictureMapper pictureMapper, PictureDraftMapper draftMapper, PictureVersionMapper versionMapper,
                     PublishRequestMapper publishRequestMapper, SpaceMapper spaceMapper,
                     UserMapper userMapper, UserService userService, PictureStorage storage,
                     PictureAssetService assets, ObjectMapper objectMapper) {
        this(pictureMapper, draftMapper, versionMapper, publishRequestMapper, spaceMapper, userMapper,
                userService, storage, assets, objectMapper, null);
    }

    public M3Dtos.EditorStateView getDraft(User user, long pictureId) {
        requirePicture(user, pictureId, false);
        PictureDraft draft = findDraft(pictureId);
        return new M3Dtos.EditorStateView(
                draft == null ? null : parseEditorState(draft.getEditorState()),
                draft == null ? null : instant(draft.getUpdateTime()),
                draft == null ? null : draft.getRevision());
    }

    @Transactional(rollbackFor = Exception.class)
    public M3Dtos.EditorStateView saveDraft(User user, long pictureId, Object editorState, Long expectedRevision) {
        requirePicture(user, pictureId, true);
        String json = normalizeEditorState(editorState);
        lockPicture(pictureId);
        PictureDraft saved = saveDraftInternal(pictureId, json, user.getId(), expectedRevision, true);
        return new M3Dtos.EditorStateView(parseEditorState(json), Instant.now(), saved.getRevision());
    }

    @Transactional(rollbackFor = Exception.class)
    public M3Dtos.EditorStateView deleteDraft(User user, long pictureId, Long expectedRevision) {
        requirePicture(user, pictureId, true);
        lockPicture(pictureId);
        PictureDraft draft = findDraft(pictureId);
        if (draft == null) {
            if (expectedRevision != null) throw draftConflict();
            return new M3Dtos.EditorStateView(null, null, null);
        }
        if (!Objects.equals(expectedRevision, draft.getRevision())) throw draftConflict();
        draftMapper.deleteById(draft.getId());
        return new M3Dtos.EditorStateView(null, null, null);
    }

    public M3Dtos.VersionList listVersions(User user, long pictureId) {
        requirePicture(user, pictureId, false);
        List<PictureVersion> versions = versionMapper.selectList(new LambdaQueryWrapper<PictureVersion>()
                .eq(PictureVersion::getPictureId, pictureId)
                .orderByDesc(PictureVersion::getVersionNumber)
                .orderByDesc(PictureVersion::getId));
        return new M3Dtos.VersionList(versions.stream().map(this::summary).toList());
    }

    public M3Dtos.VersionDetail getVersion(User user, long pictureId, long versionId) {
        requirePicture(user, pictureId, false);
        return detail(requireVersion(pictureId, versionId));
    }

    @Transactional(rollbackFor = Exception.class)
    public M3Dtos.VersionDetail createVersion(User user, long pictureId, MultipartFile preview,
                                              String editorState, String name, String note, String sourceType) {
        Picture picture = requirePicture(user, pictureId, true);
        if (preview == null || preview.isEmpty()) throw V1Exception.badRequest("版本预览图不能为空");
        if (picture.getSpaceId() == null) throw V1Exception.badRequest("图片尚未绑定可用空间");
        String json = normalizeEditorStateJson(editorState);
        String normalizedSource = normalizeSourceType(sourceType);
        lockPicture(pictureId);

        PictureStorage.StoredPicture stored = storage.store(preview, picture.getSpaceId());
        PictureVersion version = new PictureVersion();
        version.setPictureId(pictureId);
        version.setVersionNumber(versionMapper.selectMaxVersionNumber(pictureId) + 1);
        version.setName(name == null || name.isBlank() ? "版本 " + version.getVersionNumber() : name.trim());
        version.setNote(blankToNull(note));
        version.setSourceType(normalizedSource);
        version.setParentVersionId(null);
        version.setEditorState(json);
        version.setSchemaVersion(EDITOR_SCHEMA_VERSION);
        version.setAssetObjectKey(stored.objectKey());
        version.setThumbnailObjectKey(stored.thumbnailObjectKey());
        version.setContentType(stored.contentType());
        version.setWidth(stored.width());
        version.setHeight(stored.height());
        version.setSize(stored.size());
        version.setCreatorId(user.getId());
        try {
            versionMapper.insert(version);
        } catch (RuntimeException exception) {
            storage.delete(stored.objectKey());
            storage.delete(stored.thumbnailObjectKey());
            throw exception;
        }
        return detail(version);
    }

    @Transactional(rollbackFor = Exception.class)
    public M3Dtos.EditorSaveResult saveEditorResult(User user, long pictureId, MultipartFile image,
                                                     String mode, String name, Long expectedRevision) {
        Picture picture = requirePicture(user, pictureId, true);
        if (image == null || image.isEmpty()) throw V1Exception.badRequest("保存图片不能为空");
        String normalizedMode = normalizeSaveMode(mode);
        if (picture.getSpaceId() == null) throw V1Exception.badRequest("图片尚未绑定可用空间");
        lockPicture(pictureId);
        requireDraftRevision(pictureId, expectedRevision);
        Space space = requireSpace(picture.getSpaceId());
        PictureStorage.StoredPicture stored = storage.store(image, picture.getSpaceId());
        try {
            if ("replace".equals(normalizedMode)) {
                replaceCurrentPicture(user, picture, space, stored);
                clearDraft(pictureId);
                return new M3Dtos.EditorSaveResult("replace", id(pictureId));
            }
            Picture copy = savePictureCopy(user, picture, space, stored, name);
            clearDraft(pictureId);
            return new M3Dtos.EditorSaveResult("copy", id(copy.getId()));
        } catch (RuntimeException exception) {
            storage.delete(stored.objectKey());
            storage.delete(stored.thumbnailObjectKey());
            throw exception;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public M3Dtos.VersionDetail restoreVersion(User user, long pictureId, long versionId, Long expectedRevision) {
        Picture picture = requirePicture(user, pictureId, true);
        lockPicture(pictureId);
        requireDraftRevision(pictureId, expectedRevision);
        PictureVersion source = requireVersion(pictureId, versionId);
        PictureVersion restored = new PictureVersion();
        restored.setPictureId(pictureId);
        restored.setVersionNumber(versionMapper.selectMaxVersionNumber(pictureId) + 1);
        String baseName = source.getName() == null || source.getName().isBlank()
                ? "版本 " + source.getVersionNumber() : source.getName();
        restored.setName(baseName + " 恢复");
        restored.setNote("恢复自 v" + source.getVersionNumber());
        restored.setSourceType("restore");
        restored.setParentVersionId(source.getId());
        String restoredState = emptyEditorStateJson(valueOrOne(source.getWidth()), valueOrOne(source.getHeight()));
        restored.setEditorState(restoredState);
        restored.setSchemaVersion(EDITOR_SCHEMA_VERSION);
        restored.setAssetObjectKey(source.getAssetObjectKey());
        restored.setThumbnailObjectKey(source.getThumbnailObjectKey());
        restored.setContentType(source.getContentType());
        restored.setWidth(source.getWidth());
        restored.setHeight(source.getHeight());
        restored.setSize(source.getSize());
        restored.setCreatorId(user.getId());
        versionMapper.insert(restored);
        if (picture.getSpaceId() == null) throw V1Exception.badRequest("图片尚未绑定可用空间");
        Space space = requireSpace(picture.getSpaceId());
        long sizeDelta = nz(source.getSize()) - nz(picture.getPicSize());
        ensureReplacementCapacity(space, sizeDelta);
        applyVersionToPicture(picture, source);
        resetPublication(picture);
        pictureMapper.updateById(picture);
        updateSpaceSize(space.getId(), sizeDelta);
        clearDraft(pictureId);
        return detail(restored);
    }

    public PictureStorage.StoredObject loadVersionContent(User user, long pictureId, long versionId, String variant) {
        requirePicture(user, pictureId, false);
        PictureVersion version = requireVersion(pictureId, versionId);
        String key = "thumbnail".equals(normalizedVariant(variant)) && version.getThumbnailObjectKey() != null
                ? version.getThumbnailObjectKey() : version.getAssetObjectKey();
        if (key == null || key.isBlank()) throw V1Exception.notFound();
        return storage.load(key);
    }

    private void replaceCurrentPicture(User user, Picture picture, Space space,
                                       PictureStorage.StoredPicture stored) {
        long sizeDelta = stored.size() - nz(picture.getPicSize());
        ensureReplacementCapacity(space, sizeDelta);
        int nextVersion = versionMapper.selectMaxVersionNumber(picture.getId()) + 1;
        if (nextVersion == 1) {
            if (picture.getObjectKey() == null || picture.getObjectKey().isBlank()) {
                throw V1Exception.badRequest("当前图片缺少可保存的原始文件");
            }
            insertVersionSnapshot(picture.getId(), nextVersion++, "原始图片", "首次替换前自动保存",
                    "original", null, emptyEditorStateJson(valueOrOne(picture.getPicWidth()),
                            valueOrOne(picture.getPicHeight())), picture.getObjectKey(),
                    picture.getThumbnailObjectKey(), picture.getContentType(), valueOrOne(picture.getPicWidth()),
                    valueOrOne(picture.getPicHeight()), nz(picture.getPicSize()), user.getId());
        }
        insertVersionSnapshot(picture.getId(), nextVersion, "保存结果", "替换当前图片",
                "user_save", null, emptyEditorStateJson(stored.width(), stored.height()), stored.objectKey(),
                stored.thumbnailObjectKey(), stored.contentType(), stored.width(), stored.height(),
                stored.size(), user.getId());
        applyStoredPicture(picture, stored);
        resetPublication(picture);
        pictureMapper.updateById(picture);
        updateSpaceSize(space.getId(), sizeDelta);
    }

    private Picture savePictureCopy(User user, Picture source, Space space,
                                    PictureStorage.StoredPicture stored, String name) {
        if (nz(space.getTotalCount()) >= nz(space.getMaxCount())
                || nz(space.getTotalSize()) + stored.size() > nz(space.getMaxSize())) {
            throw V1Exception.conflict("个人空间容量不足");
        }
        Picture copy = new Picture();
        copy.setId(IdWorker.getId());
        copy.setUrl(assets.privateUrl(copy.getId(), "original"));
        copy.setThumbnailUrl(assets.privateUrl(copy.getId(), "thumbnail"));
        copy.setStorageProvider("minio");
        applyStoredPicture(copy, stored);
        copy.setName(copyName(source.getName(), name));
        copy.setIntroduction(source.getIntroduction());
        copy.setCategory(source.getCategory());
        copy.setTags(source.getTags());
        copy.setUserId(user.getId());
        copy.setSpaceId(space.getId());
        copy.setVisibility("private");
        copy.setPublishStatus("not_requested");
        copy.setReviewStatus(0);
        copy.setIsDelete(0);
        pictureMapper.insert(copy);
        spaceMapper.update(null, new UpdateWrapper<Space>()
                .eq("id", space.getId())
                .setSql("totalSize = totalSize + " + stored.size())
                .setSql("totalCount = totalCount + 1"));
        return copy;
    }

    private void insertVersionSnapshot(long pictureId, int versionNumber, String name, String note,
                                       String sourceType, Long parentVersionId, String editorState,
                                       String objectKey, String thumbnailObjectKey, String contentType,
                                       int width, int height, long size, long creatorId) {
        PictureVersion version = new PictureVersion();
        version.setPictureId(pictureId);
        version.setVersionNumber(versionNumber);
        version.setName(name);
        version.setNote(note);
        version.setSourceType(sourceType);
        version.setParentVersionId(parentVersionId);
        version.setEditorState(editorState);
        version.setSchemaVersion(EDITOR_SCHEMA_VERSION);
        version.setAssetObjectKey(objectKey);
        version.setThumbnailObjectKey(thumbnailObjectKey);
        version.setContentType(contentType);
        version.setWidth(width);
        version.setHeight(height);
        version.setSize(size);
        version.setCreatorId(creatorId);
        versionMapper.insert(version);
    }

    private void applyStoredPicture(Picture picture, PictureStorage.StoredPicture stored) {
        picture.setObjectKey(stored.objectKey());
        picture.setThumbnailObjectKey(stored.thumbnailObjectKey());
        picture.setContentType(stored.contentType());
        picture.setChecksum(stored.checksum());
        picture.setPicSize(stored.size());
        picture.setPicWidth(stored.width());
        picture.setPicHeight(stored.height());
        picture.setPicScale(Math.round(stored.width() * 100.0 / stored.height()) / 100.0);
        picture.setPicFormat(stored.format());
        picture.setPicColor(null);
        picture.setEditTime(new Date());
        picture.setUpdateTime(new Date());
    }

    private void applyVersionToPicture(Picture picture, PictureVersion version) {
        int width = valueOrOne(version.getWidth());
        int height = valueOrOne(version.getHeight());
        picture.setObjectKey(version.getAssetObjectKey());
        picture.setThumbnailObjectKey(version.getThumbnailObjectKey());
        picture.setContentType(version.getContentType());
        picture.setChecksum(null);
        picture.setPicSize(nz(version.getSize()));
        picture.setPicWidth(width);
        picture.setPicHeight(height);
        picture.setPicScale(Math.round(width * 100.0 / height) / 100.0);
        picture.setPicFormat(formatFromContentType(version.getContentType()));
        picture.setPicColor(null);
        picture.setEditTime(new Date());
        picture.setUpdateTime(new Date());
    }

    private void resetPublication(Picture picture) {
        publishRequestMapper.update(null, new UpdateWrapper<PublishRequest>()
                .eq("pictureId", picture.getId())
                .eq("status", "pending")
                .set("status", "withdrawn")
                .set("decisionReason", "图片内容已替换，请重新申请公开")
                .set("reviewTime", LocalDateTime.now()));
        picture.setVisibility("private");
        picture.setPublishStatus("not_requested");
        picture.setPublishedAt(null);
        picture.setReviewStatus(0);
        picture.setReviewMessage(null);
        picture.setReviewerId(null);
        picture.setReviewTime(null);
    }

    private void ensureReplacementCapacity(Space space, long sizeDelta) {
        if (nz(space.getTotalSize()) + sizeDelta > nz(space.getMaxSize())) {
            throw V1Exception.conflict("个人空间容量不足");
        }
    }

    private void updateSpaceSize(long spaceId, long sizeDelta) {
        if (sizeDelta == 0) return;
        spaceMapper.update(null, new UpdateWrapper<Space>()
                .eq("id", spaceId)
                .setSql("totalSize = GREATEST(0, totalSize + (" + sizeDelta + "))"));
    }

    private Space requireSpace(long spaceId) {
        Space space = spaceMapper.selectById(spaceId);
        if (space == null || Integer.valueOf(1).equals(space.getIsDelete())) throw V1Exception.notFound();
        return space;
    }

    private void clearDraft(long pictureId) {
        PictureDraft draft = findDraft(pictureId);
        if (draft != null) draftMapper.deleteById(draft.getId());
    }

    private String emptyEditorStateJson(int width, int height) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", EDITOR_SCHEMA_VERSION);
        ObjectNode canvas = root.putObject("canvas");
        canvas.put("width", Math.max(1, width));
        canvas.put("height", Math.max(1, height));
        ObjectNode transform = root.putObject("transform");
        transform.put("rotation", 0);
        transform.put("scale", 1);
        transform.put("flipX", false);
        transform.put("flipY", false);
        root.putNull("crop");
        ObjectNode adjustments = root.putObject("adjustments");
        for (String field : ADJUSTMENT_FIELDS) adjustments.put(field, 0);
        root.putArray("layers");
        return root.toString();
    }

    private PictureDraft saveDraftInternal(long pictureId, String editorState, long updatedBy,
                                            Long expectedRevision, boolean enforceRevision) {
        PictureDraft draft = findDraft(pictureId);
        if (draft == null) {
            if (enforceRevision && expectedRevision != null) throw draftConflict();
            draft = new PictureDraft();
            draft.setPictureId(pictureId);
            draft.setEditorState(editorState);
            draft.setSchemaVersion(EDITOR_SCHEMA_VERSION);
            draft.setRevision(1L);
            draft.setUpdatedBy(updatedBy);
            draftMapper.insert(draft);
            return draft;
        }
        long currentRevision = draft.getRevision() == null ? 1L : draft.getRevision();
        if (enforceRevision && !Objects.equals(expectedRevision, currentRevision)) throw draftConflict();
        draft.setEditorState(editorState);
        draft.setSchemaVersion(EDITOR_SCHEMA_VERSION);
        draft.setRevision(currentRevision + 1);
        draft.setUpdatedBy(updatedBy);
        draftMapper.updateById(draft);
        return draft;
    }

    private static V1Exception draftConflict() {
        return V1Exception.conflict("草稿已在其他窗口更新，请刷新后重试");
    }

    private void requireDraftRevision(long pictureId, Long expectedRevision) {
        PictureDraft draft = findDraft(pictureId);
        Long actualRevision = draft == null ? null : draft.getRevision();
        if (!Objects.equals(expectedRevision, actualRevision)) throw draftConflict();
    }

    private PictureDraft findDraft(long pictureId) {
        return draftMapper.selectOne(new LambdaQueryWrapper<PictureDraft>()
                .eq(PictureDraft::getPictureId, pictureId).last("LIMIT 1"));
    }

    private Picture requirePicture(User user, long pictureId, boolean editable) {
        Picture picture = pictureMapper.selectById(pictureId);
        if (picture == null || Integer.valueOf(1).equals(picture.getIsDelete())) throw V1Exception.notFound();
        if (spaceAccess == null) {
            if (!Objects.equals(picture.getUserId(), user.getId()) && !userService.isAdmin(user)) throw V1Exception.notFound();
            return picture;
        }
        Space space = spaceMapper.selectById(picture.getSpaceId());
        if (space == null || (editable ? !spaceAccess.canEdit(user, space) : !spaceAccess.canView(user, space))) {
            throw V1Exception.notFound();
        }
        return picture;
    }

    private PictureVersion requireVersion(long pictureId, long versionId) {
        PictureVersion version = versionMapper.selectById(versionId);
        if (version == null || !Objects.equals(version.getPictureId(), pictureId)) throw V1Exception.notFound();
        return version;
    }

    private void lockPicture(long pictureId) {
        if (pictureMapper.lockPictureForUpdate(pictureId) == null) throw V1Exception.notFound();
    }

    private M3Dtos.VersionSummary summary(PictureVersion version) {
        return new M3Dtos.VersionSummary(
                id(version.getId()), version.getVersionNumber(), version.getName(), version.getNote(),
                version.getSourceType(), id(version.getParentVersionId()),
                valueOrZero(version.getWidth()), valueOrZero(version.getHeight()),
                assets.versionContentUrl(version.getPictureId(), version.getId(), "thumbnail"),
                author(version.getCreatorId()), instant(version.getCreateTime()));
    }

    private M3Dtos.VersionDetail detail(PictureVersion version) {
        return new M3Dtos.VersionDetail(
                id(version.getId()), version.getVersionNumber(), version.getName(), version.getNote(),
                version.getSourceType(), id(version.getParentVersionId()),
                valueOrZero(version.getWidth()), valueOrZero(version.getHeight()),
                assets.versionContentUrl(version.getPictureId(), version.getId(), "original"),
                author(version.getCreatorId()), instant(version.getCreateTime()),
                parseEditorState(version.getEditorState()));
    }

    private M1Dtos.AuthorSummary author(long userId) {
        User user = userMapper.selectById(userId);
        return new M1Dtos.AuthorSummary(id(userId), user == null ? "未知用户" : user.getUserName(), user == null ? null : user.getUserAvatar());
    }

    private String normalizeEditorState(Object editorState) {
        if (editorState == null) throw V1Exception.badRequest("编辑器状态不能为空");
        try {
            JsonNode node = migrateEditorStateNode(objectMapper.valueToTree(editorState));
            validateEditorStateNode(node);
            String json = objectMapper.writeValueAsString(node);
            if (json.length() > MAX_EDITOR_STATE_CHARS) throw V1Exception.badRequest("编辑器状态过大");
            return json;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw V1Exception.badRequest("编辑器状态格式无效");
        }
    }

    private String normalizeEditorStateJson(String editorState) {
        if (editorState == null || editorState.isBlank()) throw V1Exception.badRequest("编辑器状态不能为空");
        try {
            JsonNode node = migrateEditorStateNode(objectMapper.readTree(editorState));
            validateEditorStateNode(node);
            String json = objectMapper.writeValueAsString(node);
            if (json.length() > MAX_EDITOR_STATE_CHARS) throw V1Exception.badRequest("编辑器状态过大");
            return json;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw V1Exception.badRequest("编辑器状态格式无效");
        }
    }

    private JsonNode parseEditorState(String editorState) {
        if (editorState == null || editorState.isBlank()) throw V1Exception.badRequest("编辑器状态为空");
        try {
            JsonNode node = migrateEditorStateNode(objectMapper.readTree(editorState));
            validateEditorStateNode(node);
            return node;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw V1Exception.badRequest("编辑器状态格式无效");
        }
    }

    private void validateEditorStateNode(JsonNode node) {
        if (!node.isObject()) throw V1Exception.badRequest("编辑器状态必须是对象");
        requireExactFields(node, DOCUMENT_FIELDS, "编辑器状态字段无效");
        JsonNode schemaVersion = node.get("schemaVersion");
        if (schemaVersion == null || !schemaVersion.canConvertToInt() || schemaVersion.intValue() != EDITOR_SCHEMA_VERSION) {
            throw V1Exception.badRequest("编辑器状态版本无效，请重新打开编辑器");
        }
        JsonNode canvas = requireObject(node, "canvas");
        requireExactFields(canvas, Set.of("width", "height"), "画布字段无效");
        int canvasWidth = requireInteger(canvas, "width", 1, 32_768);
        int canvasHeight = requireInteger(canvas, "height", 1, 32_768);

        JsonNode transform = requireObject(node, "transform");
        requireExactFields(transform, Set.of("rotation", "scale", "flipX", "flipY"), "变换字段无效");
        requireNumber(transform, "rotation", -360, 360);
        requireNumber(transform, "scale", 0.25, 4);
        requireBoolean(transform, "flipX");
        requireBoolean(transform, "flipY");

        JsonNode crop = node.get("crop");
        if (crop == null || (!crop.isNull() && !crop.isObject())) throw V1Exception.badRequest("裁切区域无效");
        if (!crop.isNull()) {
            requireExactFields(crop, Set.of("x", "y", "width", "height"), "裁切字段无效");
            double x = requireNumber(crop, "x", 0, canvasWidth);
            double y = requireNumber(crop, "y", 0, canvasHeight);
            double width = requireNumber(crop, "width", Double.MIN_VALUE, canvasWidth);
            double height = requireNumber(crop, "height", Double.MIN_VALUE, canvasHeight);
            if (x + width > canvasWidth || y + height > canvasHeight) throw V1Exception.badRequest("裁切区域超出画布");
        }

        JsonNode adjustments = requireObject(node, "adjustments");
        requireExactFields(adjustments, ADJUSTMENT_FIELDS, "图片调节字段无效");
        for (String field : ADJUSTMENT_FIELDS) {
            requireNumber(adjustments, field, NON_NEGATIVE_ADJUSTMENTS.contains(field) ? 0 : -100, 100);
        }

        JsonNode layers = node.get("layers");
        if (layers == null || !layers.isArray() || layers.size() > MAX_LAYERS) throw V1Exception.badRequest("图层列表无效");
        Set<String> layerIds = new HashSet<>();
        for (JsonNode layer : layers) validateLayer(layer, layerIds);
    }

    private void validateLayer(JsonNode layer, Set<String> layerIds) {
        if (!layer.isObject()) throw V1Exception.badRequest("图层格式无效");
        String type = requireText(layer, "type", 1, 16);
        Set<String> fields = "text".equals(type) ? TEXT_LAYER_FIELDS : "drawing".equals(type) ? DRAWING_LAYER_FIELDS : null;
        if (fields == null) throw V1Exception.badRequest("图层类型无效");
        requireExactFields(layer, fields, "图层字段无效");
        String id = requireText(layer, "id", 1, 128);
        if (!layerIds.add(id)) throw V1Exception.badRequest("图层 ID 重复");
        requireNumber(layer, "left", -1_000_000, 1_000_000);
        requireNumber(layer, "top", -1_000_000, 1_000_000);
        requireNumber(layer, "angle", -36_000, 36_000);
        requireNumber(layer, "scaleX", 0.01, 100);
        requireNumber(layer, "scaleY", 0.01, 100);
        requireBoolean(layer, "flipX");
        requireBoolean(layer, "flipY");
        if ("text".equals(type)) {
            requireText(layer, "text", 0, 2_000);
            requireNumber(layer, "fontSize", 8, 512);
            requireNumber(layer, "width", 1, 32_768);
            requireColor(layer, "color");
            requireText(layer, "fontFamily", 1, 128);
            requireText(layer, "fontWeight", 1, 32);
            return;
        }
        String tool = requireText(layer, "tool", 1, 16);
        if (!DRAWING_TOOLS.contains(tool)) throw V1Exception.badRequest("绘画工具无效");
        requireColor(layer, "color");
        requireNumber(layer, "size", 1, 100);
        requireNumber(layer, "opacity", 0, 1);
        validatePath(layer.get("path"));
    }

    private void validatePath(JsonNode path) {
        if (path == null || !path.isArray() || path.size() > MAX_PATH_COMMANDS) throw V1Exception.badRequest("绘画路径无效");
        for (JsonNode command : path) {
            if (!command.isArray() || command.isEmpty() || !command.get(0).isTextual()) throw V1Exception.badRequest("绘画路径指令无效");
            String name = command.get(0).textValue();
            int expectedSize = switch (name) {
                case "M", "L" -> 3;
                case "Q" -> 5;
                case "C" -> 7;
                case "Z" -> 1;
                default -> -1;
            };
            if (command.size() != expectedSize) throw V1Exception.badRequest("绘画路径指令无效");
            for (int index = 1; index < command.size(); index++) {
                if (!command.get(index).isNumber() || !Double.isFinite(command.get(index).doubleValue())) {
                    throw V1Exception.badRequest("绘画路径坐标无效");
                }
            }
        }
    }

    private static JsonNode requireObject(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) throw V1Exception.badRequest(field + " 格式无效");
        return value;
    }

    private static int requireInteger(JsonNode parent, String field, int min, int max) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw V1Exception.badRequest(field + " 必须是整数");
        int number = value.intValue();
        if (number < min || number > max) throw V1Exception.badRequest(field + " 超出范围");
        return number;
    }

    private static double requireNumber(JsonNode parent, String field, double min, double max) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isNumber()) throw V1Exception.badRequest(field + " 必须是数字");
        double number = value.doubleValue();
        if (!Double.isFinite(number) || number < min || number > max) throw V1Exception.badRequest(field + " 超出范围");
        return number;
    }

    private static boolean requireBoolean(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isBoolean()) throw V1Exception.badRequest(field + " 必须是布尔值");
        return value.booleanValue();
    }

    private JsonNode migrateEditorStateNode(JsonNode source) {
        if (source == null || !source.isObject()) return source;
        JsonNode version = source.get("schemaVersion");
        if (version == null || !version.canConvertToInt() || version.intValue() != 2) return source;

        ObjectNode migrated = source.deepCopy();
        migrated.put("schemaVersion", EDITOR_SCHEMA_VERSION);
        JsonNode transformValue = migrated.get("transform");
        if (transformValue instanceof ObjectNode transform) {
            transform.put("flipX", false);
            transform.put("flipY", false);
        }
        JsonNode layersValue = migrated.get("layers");
        if (layersValue instanceof ArrayNode layers) {
            for (JsonNode value : layers) {
                if (!(value instanceof ObjectNode layer)) continue;
                migrateLayerScale(layer, "scaleX", "flipX");
                migrateLayerScale(layer, "scaleY", "flipY");
                if ("text".equals(layer.path("type").asText())) {
                    double fontSize = layer.path("fontSize").isNumber() ? layer.path("fontSize").doubleValue() : 32;
                    int characters = Math.max(1, layer.path("text").asText("").codePointCount(0, layer.path("text").asText("").length()));
                    layer.put("width", Math.max(120, Math.min(32_768, characters * fontSize * 0.62)));
                }
            }
        }
        return migrated;
    }

    private static void migrateLayerScale(ObjectNode layer, String scaleField, String flipField) {
        double scale = layer.path(scaleField).isNumber() ? layer.path(scaleField).doubleValue() : 1;
        layer.put(scaleField, Math.max(0.01, Math.min(100, Math.abs(scale))));
        layer.put(flipField, scale < 0);
    }

    private static String requireText(JsonNode parent, String field, int minLength, int maxLength) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual()) throw V1Exception.badRequest(field + " 必须是字符串");
        String text = value.textValue();
        if (text.length() < minLength || text.length() > maxLength) throw V1Exception.badRequest(field + " 长度无效");
        return text;
    }

    private static void requireColor(JsonNode parent, String field) {
        String value = requireText(parent, field, 7, 9);
        if (!value.matches("^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$")) throw V1Exception.badRequest(field + " 颜色无效");
    }

    private static void requireExactFields(JsonNode node, Set<String> expected, String message) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw V1Exception.badRequest(message);
    }

    private static String normalizeSourceType(String sourceType) {
        String value = sourceType == null || sourceType.isBlank() ? "user_save" : sourceType;
        if (!SOURCE_TYPES.contains(value)) throw V1Exception.badRequest("版本来源类型无效");
        return value;
    }

    private static String normalizeSaveMode(String mode) {
        if ("replace".equals(mode) || "copy".equals(mode)) return mode;
        throw V1Exception.badRequest("保存方式无效");
    }

    private static String copyName(String sourceName, String requestedName) {
        if (requestedName != null && !requestedName.isBlank()) {
            String value = requestedName.trim();
            if (value.length() > 128) throw V1Exception.badRequest("图片名称不能超过 128 个字符");
            return value;
        }
        String suffix = " - 副本";
        String base = sourceName == null || sourceName.isBlank() ? "未命名图片" : sourceName.trim();
        return base.substring(0, Math.min(base.length(), 128 - suffix.length())) + suffix;
    }

    private static String formatFromContentType(String contentType) {
        if (contentType == null) return null;
        return switch (contentType.toLowerCase()) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> null;
        };
    }

    private static String normalizedVariant(String variant) {
        if (variant == null || variant.isBlank() || "original".equals(variant)) return "original";
        if ("thumbnail".equals(variant)) return "thumbnail";
        throw V1Exception.badRequest("图片规格无效");
    }

    private static String id(Long value) { return value == null ? null : value.toString(); }
    private static long nz(Long value) { return value == null ? 0 : value; }
    private static int valueOrZero(Integer value) { return value == null ? 0 : value; }
    private static int valueOrOne(Integer value) { return value == null || value < 1 ? 1 : value; }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static Instant instant(Date value) { return value == null ? null : value.toInstant(); }
}
