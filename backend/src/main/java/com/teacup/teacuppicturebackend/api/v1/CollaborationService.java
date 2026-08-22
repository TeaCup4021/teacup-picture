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

@Service
public class CollaborationService {
    private static final int MAX_UPDATE_CHARS = 350_000;
    private static final int MAX_OPERATION_ID = 128;
    private static final int MAX_KIND = 48;
    private static final int MAX_TARGET_ID = 128;

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
                    false, false, null);
        }
        String role = spaceAccess.roleOf(user, space);
        if (role == null) throw V1Exception.forbidden();
        CollaborationRoom room = ensureRoom(pictureId);
        return new CollaborationDtos.Session(Long.toString(room.getId()), Long.toString(pictureId),
                room.getRoomEpoch(), Long.toString(room.getLastSeq()), role, true,
                spaceAccess.canEdit(user, space), "/api/v1/ws/pictures/" + pictureId + "/collaboration");
    }

    @Transactional(rollbackFor = Exception.class)
    public CollaborationDtos.UpdateResult append(User user, long pictureId,
                                                 CollaborationDtos.UpdateRequest request) {
        Picture picture = requirePicture(user, pictureId);
        Space space = requireTeamSpace(picture);
        if (!spaceAccess.canEdit(user, space)) throw V1Exception.forbidden();
        validateRequest(request);
        CollaborationRoom room = lockRoom(pictureId);
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
        return updates.selectAfter(room.getId(), Math.max(0, afterSeq)).stream().map(this::toRecord).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public CollaborationDtos.CheckpointResult checkpoint(User user, long pictureId,
                                                          CollaborationDtos.CheckpointRequest request) {
        CollaborationDtos.Session session = getSession(user, pictureId);
        if (!session.enabled() || !session.canEdit()) throw V1Exception.forbidden();
        if (!session.roomEpoch().equals(request.roomEpoch())) throw V1Exception.conflict("协作房间已切换，请重新连接");
        long seq = parseLong(request.lastServerSeq(), -1);
        if (seq < 0 || seq > parseLong(session.lastServerSeq(), 0)) throw V1Exception.conflict("协作序号无效");
        if (request.yjsState() == null || request.yjsState().length() > MAX_UPDATE_CHARS * 4) {
            throw V1Exception.badRequest("协作快照过大");
        }
        var draft = m3Service.saveDraft(user, pictureId, request.editorState(), parseLong(request.expectedRevision(), null));
        CollaborationRoom room = lockRoom(pictureId);
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

    private CollaborationRoom ensureRoom(long pictureId) {
        CollaborationRoom room = rooms.selectOne(new LambdaQueryWrapper<CollaborationRoom>()
                .eq(CollaborationRoom::getPictureId, pictureId));
        if (room != null) return room;
        room = new CollaborationRoom();
        room.setPictureId(pictureId);
        room.setRoomEpoch(UUID.randomUUID().toString());
        room.setLastSeq(0L);
        room.setStatus("active");
        rooms.insert(room);
        return room;
    }

    private CollaborationRoom lockRoom(long pictureId) {
        CollaborationRoom room = rooms.lockByPictureId(pictureId);
        if (room == null) room = ensureRoom(pictureId);
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
                value.getTargetId(), fields, value.getPhase(), value.getYjsUpdate(),
                Long.toString(value.getServerSeq()), Long.toString(value.getActorId()),
                value.getCreateTime() == null ? Instant.now() : value.getCreateTime().toInstant());
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
}
