package com.teacup.teacuppicturebackend.api.v1.model;

import java.time.Instant;
import java.util.List;

public final class M6Dtos {
    private M6Dtos() {}

    public record CreateShareRequest(Instant expiresAt, String password) {}
    public record ShareView(String id, String pictureId, String publicId, String sharePath,
                            boolean passwordProtected, Instant expiresAt, Instant revokedAt,
                            Instant createdAt) {}
    public record ShareAccessRequest(String secret, String password) {}
    public record ShareAccessResult(boolean granted, boolean passwordProtected, Instant expiresAt) {}
    public record SharedPicture(String id, String name, String introduction, String category,
                                List<String> tags, int width, int height, M1Dtos.AuthorSummary author,
                                String imageUrl, String currentVersionId, boolean canDownload,
                                boolean canComment) {}

    public record CreateCommentRequest(String kind, String body, String pictureVersionId,
                                       Double x, Double y, List<String> mentionedUserIds) {}
    public record CreateReplyRequest(String body, String replyToId, List<String> mentionedUserIds) {}
    public record UpdateThreadRequest(Boolean resolved) {}
    public record CommentAuthor(String id, String name, String avatarUrl) {}
    public record MentionCandidate(String id, String name, String avatarUrl) {}
    public record CommentView(String id, String pictureId, String pictureVersionId, String kind,
                              String body, Double x, Double y, CommentAuthor author,
                              boolean deleted, boolean resolved, String resolvedBy,
                              Instant resolvedAt, Instant createdAt, Instant updatedAt,
                              String replyToId, List<CommentView> replies, long replyCount,
                              boolean canDelete, boolean canResolve) {}
    public record CommentPage(List<CommentView> items, String nextCursor, boolean hasMore,
                              String currentVersionId) {}
}
