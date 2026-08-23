package com.teacup.teacuppicturebackend.api.v1;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teacup.teacuppicturebackend.api.v1.model.CollaborationDtos;
import com.teacup.teacuppicturebackend.mapper.CollaborationRoomMapper;
import com.teacup.teacuppicturebackend.mapper.CollaborationSnapshotMapper;
import com.teacup.teacuppicturebackend.mapper.CollaborationUpdateMapper;
import com.teacup.teacuppicturebackend.mapper.PictureMapper;
import com.teacup.teacuppicturebackend.mapper.SpaceMapper;
import com.teacup.teacuppicturebackend.model.entity.CollaborationRoom;
import com.teacup.teacuppicturebackend.model.entity.CollaborationSnapshot;
import com.teacup.teacuppicturebackend.model.entity.CollaborationUpdate;
import com.teacup.teacuppicturebackend.model.entity.Picture;
import com.teacup.teacuppicturebackend.model.entity.Space;
import com.teacup.teacuppicturebackend.model.entity.User;
import com.teacup.teacuppicturebackend.model.enums.SpaceTypeEnum;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class CollaborationService {
    private static final int MAX_UPDATE_CHARS = 350_000;
    private static final int MAX_OPERATION_ID = 128;
    private static final int MAX_KIND = 48;
    private static final int MAX_TARGET_ID = 128;
    private static final int UPDATE_PAGE_SIZE = 500;

    private final PictureMapper pictures;
    private final SpaceMapper spaces;
    private final CollaborationRoomMapper rooms;
    private final CollaborationUpdateMapper updates;
    private final CollaborationSnapshotMapper snapshots;
    private final SpaceAccessService spaceAccess;
    private final M3Service m3Service;
    private final ObjectMapper objectMapper;

    public CollaborationService(PictureMapper pictures, SpaceMapper spaces,
                                CollaborationRoomMapper rooms, CollaborationUpdateMapper updates,
                                CollaborationSnapshotMapper snapshots, SpaceAccessService spaceAccess,
                                M3Service m3Service, ObjectMapper objectMapper) {
        this.pictures = pictures;
        this.spaces = spaces;
        this.rooms = rooms;
        this.updates = updates;
        this.snapshots = snapshots;
        this.spaceAccess = spaceAccess;
        this.m3Service = m3Service;
        this.objectMapper = objectMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public CollaborationDtos.Session getSession(User user, long pictureId) {
        Picture picture = requirePicture(user, pictureId);
        Space space = spaces.selectById(picture.getSpaceId());
        if (space == null || !Integer.valueOf(SpaceTypeEnum.TEAM.getValue()).equals(space.getSpaceType())) {
            return new CollaborationDtos.Session(null, Long.toString(pictureId), null, "0", null,
                    false, false, null, null);
        }
        String role = spaceAccess.roleOf(user, space);
        if (role == null) throw V1Exception.forbidden();
        CollaborationRoom room = ensureRoom(user, pictureId);
        return new CollaborationDtos.Session(Long.toString(room.getId()), Long.toString(pictureId),
                room.getRoomEpoch(), Long.toString(room.getLastSeq()), role, true,
                spaceAccess.canEdit(user, space), "/api/v1/ws/pictures/" + pictureId + "/collaboration",
                parseJson(room.getBaseEditorState()));
    }

    @Transactional(rollbackFor = Exception.class)
    public CollaborationDtos.UpdateResult append(User user, long pictureId,
                                                 CollaborationDtos.UpdateRequest request) {
        Picture picture = requirePicture(user, pictureId);
        Space space = requireTeamSpace(picture);
        if (!spaceAccess.canEdit(user, space)) throw V1Exception.forbidden();
        validateRequest(request);
        CollaborationRoom room = lockRoom(user, pictureId);
        if (!room.getRoomEpoch().equals(request.roomEpoch())) throw V1Exception.conflict("协作房间已切换，请重新连接");
        CollaborationUpdate existing = updates.selectByOperation(room.getId(), request.operationId());
        if (existing != null) return new CollaborationDtos.UpdateResult(toRecord(existing), true);

        long nextSeq = room.getLastSeq() + 1;
        CollaborationUpdate entity = new CollaborationUpdate();
        entity.setRoomId(room.getId());
        entity.setOperationId(request.operationId());
        entity.setServerSeq(nextSeq);
        entity.setActorId(user.getId());
        entity.setGestureId(trimNullable(request.gestureId(), 128));
        entity.setKind(request.kind());
        entity.setTargetId(trimNullable(request.targetId(), MAX_TARGET_ID));
        entity.setLockToken(trimNullable(request.lockToken(), MAX_OPERATION_ID));
        entity.setChangedFields(writeChangedFields(request.changedFields()));
        entity.setPhase(request.phase());
        entity.setYjsUpdate(request.yjsUpdate());
        updates.insert(entity);
        room.setLastSeq(nextSeq);
        rooms.updateById(room);
        return new CollaborationDtos.UpdateResult(toRecord(entity), false);
    }

    public List<CollaborationDtos.UpdateRecord> updatesAfter(User user, long pictureId,
                                                              String roomEpoch, long afterSeq) {
        Picture picture = requirePicture(user, pictureId);
        CollaborationRoom room = lockRoomRead(pictureId);
        if (!room.getRoomEpoch().equals(roomEpoch)) throw V1Exception.conflict("协作房间已切换，请重新连接");
        return updates.selectAfter(room.getId(), Math.max(0, afterSeq), UPDATE_PAGE_SIZE).stream().map(this::toRecord).toList();
    }

    public CollaborationDtos.Bootstrap bootstrap(User user, long pictureId, String roomEpoch, long afterSeq) {
        Picture picture = requirePicture(user, pictureId);
        CollaborationRoom room = lockRoomRead(pictureId);
        if (!room.getRoomEpoch().equals(roomEpoch)) throw V1Exception.conflict("协作房间已切换，请重新连接");
        if (afterSeq < 0 || afterSeq > room.getLastSeq()) throw V1Exception.conflict("协作序号无效，请重新同步");
        CollaborationSnapshot snapshot = snapshots.selectLatest(room.getId());
        String snapshotState = null;
        long startSeq = Math.max(0, afterSeq);
        if (snapshot != null && startSeq < snapshot.getLastSeq()) {
            snapshotState = snapshot.getYjsState();
            startSeq = snapshot.getLastSeq();
        }
        List<CollaborationUpdate> page = updates.selectAfter(room.getId(), startSeq, UPDATE_PAGE_SIZE);
        if (!page.isEmpty() && page.get(0).getServerSeq() != startSeq + 1) {
            throw V1Exception.conflict("协作更新序号存在缺口，请重新获取基线");
        }
        long next = page.isEmpty() ? startSeq : page.get(page.size() - 1).getServerSeq();
        return new CollaborationDtos.Bootstrap(snapshotState, Long.toString(snapshot == null ? 0 : snapshot.getLastSeq()),
                page.stream().map(this::toRecord).toList(), next < room.getLastSeq(), Long.toString(next));
    }

    @Transactional(rollbackFor = Exception.class)
    public CollaborationDtos.CheckpointResult checkpoint(User user, long pictureId,
                                                          CollaborationDtos.CheckpointRequest request) {
        CollaborationDtos.Session session = getSession(user, pictureId);
        if (!session.enabled() || !session.canEdit()) throw V1Exception.forbidden();
        CollaborationRoom room = lockRoom(user, pictureId);
        if (!room.getRoomEpoch().equals(request.roomEpoch())) throw V1Exception.conflict("协作房间已切换，请重新连接");
        long seq = parseLong(request.lastServerSeq(), -1);
        if (seq < 0 || seq != room.getLastSeq()) throw V1Exception.conflict("协作序号不是最新值");
        if (request.yjsState() == null || request.yjsState().length() > MAX_UPDATE_CHARS * 4) {
            throw V1Exception.badRequest("协作快照过大");
        }
        validateCheckpointHashes(request);
        var draft = m3Service.saveDraft(user, pictureId, request.editorState(), parseLong(request.expectedRevision(), null));
        CollaborationSnapshot snapshot = new CollaborationSnapshot();
        snapshot.setRoomId(room.getId());
        snapshot.setLastSeq(seq);
        snapshot.setYjsState(request.yjsState());
        try {
            snapshot.setEditorState(objectMapper.writeValueAsString(request.editorState()));
        } catch (JsonProcessingException exception) {
            throw V1Exception.badRequest("编辑状态格式错误");
        }
        snapshots.insert(snapshot);
        return new CollaborationDtos.CheckpointResult(room.getRoomEpoch(), Long.toString(seq),
                Long.toString(draft.revision()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void rotateRoomEpoch(User user, long pictureId, Object baselineEditorState) {
        Picture picture = requirePicture(user, pictureId);
        Space space = spaces.selectById(picture.getSpaceId());
        if (space == null || !Integer.valueOf(SpaceTypeEnum.TEAM.getValue()).equals(space.getSpaceType())) return;
        if (!spaceAccess.canEdit(user, space)) throw V1Exception.forbidden();
        CollaborationRoom room = lockRoom(user, pictureId);
        if (baselineEditorState == null) {
            CollaborationSnapshot latest = snapshots.selectLatest(room.getId());
            baselineEditorState = latest == null ? null : parseJson(latest.getEditorState());
        }
        room.setRoomEpoch(UUID.randomUUID().toString());
        room.setLastSeq(0L);
        room.setStatus("active");
        room.setBaseEditorState(writeBaseline(user, picture, baselineEditorState));
        rooms.updateById(room);
    }

    public CollaborationRoom roomFor(long pictureId) {
        return lockRoomRead(pictureId);
    }

    private Picture requirePicture(User user, long pictureId) {
        Picture picture = pictures.selectById(pictureId);
        if (picture == null || Integer.valueOf(1).equals(picture.getIsDelete())) throw V1Exception.notFound();
        Space space = spaces.selectById(picture.getSpaceId());
        if (space == null || !spaceAccess.canView(user, space)) throw V1Exception.forbidden();
        return picture;
    }

    private Space requireTeamSpace(Picture picture) {
        Space space = spaces.selectById(picture.getSpaceId());
        if (space == null || !Integer.valueOf(SpaceTypeEnum.TEAM.getValue()).equals(space.getSpaceType())) {
            throw V1Exception.forbidden();
        }
        return space;
    }

    private CollaborationRoom ensureRoom(User user, long pictureId) {
        CollaborationRoom room = rooms.selectOne(new LambdaQueryWrapper<CollaborationRoom>()
                .eq(CollaborationRoom::getPictureId, pictureId));
        if (room != null) {
            if (blank(room.getBaseEditorState())) {
                room.setBaseEditorState(writeBaseline(user, pictures.selectById(pictureId), null));
                rooms.updateById(room);
            }
            return room;
        }
        room = new CollaborationRoom();
        room.setPictureId(pictureId);
        room.setRoomEpoch(UUID.randomUUID().toString());
        room.setLastSeq(0L);
        room.setStatus("active");
        room.setBaseEditorState(writeBaseline(user, pictures.selectById(pictureId), null));
        rooms.insert(room);
        return room;
    }

    private CollaborationRoom lockRoom(User user, long pictureId) {
        CollaborationRoom room = rooms.lockByPictureId(pictureId);
        if (room == null) room = ensureRoom(user, pictureId);
        return room;
    }

    private CollaborationRoom lockRoomRead(long pictureId) {
        CollaborationRoom room = rooms.selectOne(new LambdaQueryWrapper<CollaborationRoom>()
                .eq(CollaborationRoom::getPictureId, pictureId));
        if (room == null) throw V1Exception.notFound();
        return room;
    }

    private void validateRequest(CollaborationDtos.UpdateRequest request) {
        if (request == null || blank(request.roomEpoch()) || blank(request.operationId()) || blank(request.kind())
                || blank(request.phase()) || blank(request.yjsUpdate())) throw V1Exception.badRequest("协作更新参数不完整");
        if (request.operationId().length() > MAX_OPERATION_ID || request.kind().length() > MAX_KIND
                || request.yjsUpdate().length() > MAX_UPDATE_CHARS) throw V1Exception.badRequest("协作更新过大");
        try {
            byte[] decoded = Base64.getDecoder().decode(request.yjsUpdate());
            if (decoded.length == 0 || decoded.length > 256_000) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw V1Exception.badRequest("协作更新编码无效");
        }
    }

    private String writeChangedFields(List<String> fields) {
        try { return objectMapper.writeValueAsString(fields == null ? Collections.emptyList() : fields); }
        catch (JsonProcessingException exception) { throw V1Exception.badRequest("changedFields 格式错误"); }
    }

    private CollaborationDtos.UpdateRecord toRecord(CollaborationUpdate value) {
        List<String> fields;
        try { fields = objectMapper.readValue(value.getChangedFields(), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)); }
        catch (JsonProcessingException exception) { fields = List.of(); }
        return new CollaborationDtos.UpdateRecord(value.getOperationId(), value.getGestureId(), value.getKind(),
                value.getTargetId(), fields, value.getPhase(), value.getLockToken(), value.getYjsUpdate(),
                Long.toString(value.getServerSeq()), Long.toString(value.getActorId()),
                value.getCreateTime() == null ? Instant.now() : value.getCreateTime().toInstant());
    }

    private void validateCheckpointHashes(CollaborationDtos.CheckpointRequest request) {
        if (blank(request.editorStateHash()) || blank(request.yjsStateHash())) {
            throw V1Exception.badRequest("协作快照缺少状态哈希");
        }
        try {
            String editorJson = objectMapper.writeValueAsString(request.editorState());
            if (!sha256(editorJson).equalsIgnoreCase(request.editorStateHash())) {
                throw V1Exception.conflict("编辑状态与校验哈希不一致");
            }
            byte[] state = Base64.getDecoder().decode(request.yjsState());
            if (!sha256(state).equalsIgnoreCase(request.yjsStateHash())) {
                throw V1Exception.conflict("Yjs 状态与校验哈希不一致");
            }
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw V1Exception.badRequest("协作快照格式无效");
        }
    }

    private String writeBaseline(User user, Picture picture, Object value) {
        try {
            if (value != null) return objectMapper.writeValueAsString(value);
            var draft = m3Service.getDraft(user, picture.getId());
            if (draft.editorState() != null) return objectMapper.writeValueAsString(draft.editorState());
        } catch (RuntimeException | JsonProcessingException ignored) {
            // A room must still have a deterministic baseline when no draft exists.
        }
        try {
            var baseline = objectMapper.createObjectNode();
            baseline.put("schemaVersion", 3);
            baseline.set("canvas", objectMapper.createObjectNode().put("width", valueOrOne(picture.getPicWidth())).put("height", valueOrOne(picture.getPicHeight())));
            baseline.set("transform", objectMapper.createObjectNode().put("rotation", 0).put("scale", 1).put("flipX", false).put("flipY", false));
            baseline.putNull("crop");
            var adjustments = baseline.putObject("adjustments");
            for (String key : List.of("exposure", "brightness", "contrast", "highlights", "shadows", "saturation", "vibrance", "temperature", "tint", "sharpness", "fade", "vignette", "enhance", "dehaze")) adjustments.put(key, 0);
            baseline.putArray("layers");
            return objectMapper.writeValueAsString(baseline);
        } catch (JsonProcessingException exception) {
            throw V1Exception.badRequest("协作基线生成失败");
        }
    }

    private Object parseJson(String value) {
        if (blank(value)) return null;
        try { return objectMapper.readTree(value); } catch (JsonProcessingException exception) { return null; }
    }

    private static String sha256(String value) { return sha256(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte part : digest) result.append(String.format("%02x", part));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static String trimNullable(String value, int max) {
        if (value == null || value.isBlank()) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return Long.parseLong(value); } catch (NumberFormatException exception) { return fallback; }
    }

    private static Long parseLong(String value, Long fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return Long.valueOf(value); } catch (NumberFormatException exception) { return fallback; }
    }

    private static int valueOrOne(Integer value) { return value == null || value < 1 ? 1 : value; }
}
