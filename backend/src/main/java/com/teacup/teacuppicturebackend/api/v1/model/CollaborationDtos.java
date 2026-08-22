package com.teacup.teacuppicturebackend.api.v1.model;

import java.time.Instant;
import java.util.List;

public final class CollaborationDtos {
    private CollaborationDtos() {}

    public record Session(String roomId, String pictureId, String roomEpoch, String lastServerSeq,
                          String role, boolean enabled, boolean canEdit, String wsPath) {}

    public record UpdateRequest(String roomEpoch, String operationId, String gestureId, String kind,
                                String targetId, List<String> changedFields, String phase,
                                String yjsUpdate) {}

    public record UpdateRecord(String operationId, String gestureId, String kind, String targetId,
                               List<String> changedFields, String phase, String yjsUpdate,
                               String serverSeq, String actorId, Instant createdAt) {}

    public record UpdateResult(UpdateRecord record, boolean duplicate) {}

    public record CheckpointRequest(String roomEpoch, String lastServerSeq, String yjsState,
                                    Object editorState, String expectedRevision) {}

    public record CheckpointResult(String roomEpoch, String lastServerSeq, String revision) {}
}
