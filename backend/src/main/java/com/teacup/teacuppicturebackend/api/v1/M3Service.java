package com.teacup.teacuppicturebackend.api.v1;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teacup.teacuppicturebackend.api.v1.model.M1Dtos;
import com.teacup.teacuppicturebackend.api.v1.model.M3Dtos;
import com.teacup.teacuppicturebackend.mapper.PictureDraftMapper;
import com.teacup.teacuppicturebackend.mapper.PictureMapper;
import com.teacup.teacuppicturebackend.mapper.PictureVersionMapper;
import com.teacup.teacuppicturebackend.mapper.UserMapper;
import com.teacup.teacuppicturebackend.model.entity.Picture;
import com.teacup.teacuppicturebackend.model.entity.PictureDraft;
import com.teacup.teacuppicturebackend.model.entity.PictureVersion;
import com.teacup.teacuppicturebackend.model.entity.User;
import com.teacup.teacuppicturebackend.service.UserService;
import com.teacup.teacuppicturebackend.storage.PictureAssetService;
import com.teacup.teacuppicturebackend.storage.PictureStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
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
    private static final int EDITOR_SCHEMA_VERSION = 2;
    private static final Set<String> SOURCE_TYPES = Set.of("user_save", "restore", "ai_generate", "ai_outpaint", "team_confirm");
    private static final Set<String> DOCUMENT_FIELDS = Set.of("schemaVersion", "canvas", "transform", "crop", "adjustments", "layers");
    private static final Set<String> ADJUSTMENT_FIELDS = Set.of("exposure", "brightness", "contrast", "highlights", "shadows",
            "saturation", "vibrance", "temperature", "tint", "sharpness", "fade", "vignette", "enhance", "dehaze");
    private static final Set<String> NON_NEGATIVE_ADJUSTMENTS = Set.of("fade", "enhance", "dehaze");
    private static final Set<String> TEXT_LAYER_FIELDS = Set.of("id", "type", "text", "left", "top", "fontSize", "color",
            "fontFamily", "fontWeight", "angle", "scaleX", "scaleY");
    private static final Set<String> DRAWING_LAYER_FIELDS = Set.of("id", "type", "tool", "color", "size", "opacity", "path",
            "left", "top", "scaleX", "scaleY", "angle");
    private static final Set<String> DRAWING_TOOLS = Set.of("pen", "marker", "eraser");

    private final PictureMapper pictureMapper;
    private final PictureDraftMapper draftMapper;
    private final PictureVersionMapper versionMapper;
    private final UserMapper userMapper;
    private final UserService userService;
    private final PictureStorage storage;
    private final PictureAssetService assets;
    private final ObjectMapper objectMapper;

    public M3Service(PictureMapper pictureMapper, PictureDraftMapper draftMapper, PictureVersionMapper versionMapper,
                     UserMapper userMapper, UserService userService, PictureStorage storage,
                     PictureAssetService assets, ObjectMapper objectMapper) {
        this.pictureMapper = pictureMapper;
        this.draftMapper = draftMapper;
        this.versionMapper = versionMapper;
        this.userMapper = userMapper;
        this.userService = userService;
        this.storage = storage;
        this.assets = assets;
        this.objectMapper = objectMapper;
    }

    public M3Dtos.EditorStateView getDraft(User user, long pictureId) {
        requireOwnedPicture(user, pictureId);
        PictureDraft draft = findDraft(pictureId);
        return new M3Dtos.EditorStateView(
                draft == null ? null : parseEditorState(draft.getEditorState()),
                draft == null ? null : instant(draft.getUpdateTime()),
                draft == null ? null : draft.getRevision());
    }

    @Transactional(rollbackFor = Exception.class)
    public M3Dtos.EditorStateView saveDraft(User user, long pictureId, Object editorState, Long expectedRevision) {
        requireOwnedPicture(user, pictureId);
        String json = normalizeEditorState(editorState);
        lockPicture(pictureId);
        PictureDraft saved = saveDraftInternal(pictureId, json, user.getId(), expectedRevision, true);
        return new M3Dtos.EditorStateView(parseEditorState(json), Instant.now(), saved.getRevision());
    }

    @Transactional(rollbackFor = Exception.class)
    public M3Dtos.EditorStateView deleteDraft(User user, long pictureId, Long expectedRevision) {
        requireOwnedPicture(user, pictureId);
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
        requireOwnedPicture(user, pictureId);
        List<PictureVersion> versions = versionMapper.selectList(new LambdaQueryWrapper<PictureVersion>()
                .eq(PictureVersion::getPictureId, pictureId)
                .orderByDesc(PictureVersion::getVersionNumber)
                .orderByDesc(PictureVersion::getId));
        return new M3Dtos.VersionList(versions.stream().map(this::summary).toList());
    }

    public M3Dtos.VersionDetail getVersion(User user, long pictureId, long versionId) {
        requireOwnedPicture(user, pictureId);
        return detail(requireVersion(pictureId, versionId));
    }

    @Transactional(rollbackFor = Exception.class)
    public M3Dtos.VersionDetail createVersion(User user, long pictureId, MultipartFile preview,
                                              String editorState, String name, String note, String sourceType) {
        Picture picture = requireOwnedPicture(user, pictureId);
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
    public M3Dtos.VersionDetail restoreVersion(User user, long pictureId, long versionId, Long expectedRevision) {
        requireOwnedPicture(user, pictureId);
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
        restored.setEditorState(source.getEditorState());
        restored.setSchemaVersion(EDITOR_SCHEMA_VERSION);
        restored.setAssetObjectKey(source.getAssetObjectKey());
        restored.setThumbnailObjectKey(source.getThumbnailObjectKey());
        restored.setContentType(source.getContentType());
        restored.setWidth(source.getWidth());
        restored.setHeight(source.getHeight());
        restored.setSize(source.getSize());
        restored.setCreatorId(user.getId());
        versionMapper.insert(restored);
        saveDraftInternal(pictureId, source.getEditorState(), user.getId(), null, false);
        return detail(restored);
    }

    public PictureStorage.StoredObject loadVersionContent(User user, long pictureId, long versionId, String variant) {
        requireOwnedPicture(user, pictureId);
        PictureVersion version = requireVersion(pictureId, versionId);
        String key = "thumbnail".equals(normalizedVariant(variant)) && version.getThumbnailObjectKey() != null
                ? version.getThumbnailObjectKey() : version.getAssetObjectKey();
        if (key == null || key.isBlank()) throw V1Exception.notFound();
        return storage.load(key);
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

    private Picture requireOwnedPicture(User user, long pictureId) {
        Picture picture = pictureMapper.selectById(pictureId);
        if (picture == null || Integer.valueOf(1).equals(picture.getIsDelete())) throw V1Exception.notFound();
        if (!Objects.equals(picture.getUserId(), user.getId()) && !userService.isAdmin(user)) {
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
            JsonNode node = objectMapper.valueToTree(editorState);
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
            JsonNode node = objectMapper.readTree(editorState);
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
            JsonNode node = objectMapper.readTree(editorState);
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
        requireExactFields(transform, Set.of("rotation", "scale"), "变换字段无效");
        requireNumber(transform, "rotation", -360, 360);
        requireNumber(transform, "scale", 0.25, 4);

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
        requireNumber(layer, "scaleX", Double.MIN_VALUE, 100);
        requireNumber(layer, "scaleY", Double.MIN_VALUE, 100);
        if ("text".equals(type)) {
            requireText(layer, "text", 0, 2_000);
            requireNumber(layer, "fontSize", 8, 512);
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

    private static String normalizedVariant(String variant) {
        if (variant == null || variant.isBlank() || "original".equals(variant)) return "original";
        if ("thumbnail".equals(variant)) return "thumbnail";
        throw V1Exception.badRequest("图片规格无效");
    }

    private static String id(Long value) { return value == null ? null : value.toString(); }
    private static int valueOrZero(Integer value) { return value == null ? 0 : value; }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static Instant instant(Date value) { return value == null ? null : value.toInstant(); }
}
