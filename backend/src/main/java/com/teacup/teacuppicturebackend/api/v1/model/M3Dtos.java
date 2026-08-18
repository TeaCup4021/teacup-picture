package com.teacup.teacuppicturebackend.api.v1.model;

import java.time.Instant;
import java.util.List;

public final class M3Dtos {
    private M3Dtos() {
    }

    public record SaveDraftRequest(Object editorState, Long expectedRevision) {}

    public record DeleteDraftRequest(Long expectedRevision) {}

    public record RestoreVersionRequest(Long expectedRevision) {}

    public record EditorSaveResult(String mode, String pictureId) {}

    public record EditorStateView(Object editorState, Instant updatedAt, Long revision) {}

    public record VersionSummary(String id, int versionNumber, String name, String note, String sourceType,
                                 String parentVersionId, int width, int height, String thumbnailUrl,
                                 M1Dtos.AuthorSummary creator, Instant createdAt) {}

    public record VersionDetail(String id, int versionNumber, String name, String note, String sourceType,
                                String parentVersionId, int width, int height, String previewUrl,
                                M1Dtos.AuthorSummary creator, Instant createdAt, Object editorState) {}

    public record VersionList(List<VersionSummary> items) {}
}
