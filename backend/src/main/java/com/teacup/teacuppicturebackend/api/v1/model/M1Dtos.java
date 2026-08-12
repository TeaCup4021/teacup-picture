package com.teacup.teacuppicturebackend.api.v1.model;

import java.time.Instant;
import java.util.List;

public final class M1Dtos {
    private M1Dtos() {
    }

    public record RegisterRequest(String account, String password, String passwordConfirmation) {}
    public record LoginRequest(String account, String password) {}
    public record RegistrationResult(String userId, String personalSpaceId) {}
    public record CurrentUser(String id, String account, String name, String avatarUrl, String profile,
                              String role, Instant createdAt) {}
    public record AuthorSummary(String id, String name, String avatarUrl) {}
    public record PersonalSpace(String id, String name, String type, String level, long maxSize,
                                long maxCount, long totalSize, long totalCount,
                                List<String> permissions, Instant createdAt, Instant updatedAt) {}
    public record UrlImportRequest(String url, String spaceId, String name, String introduction,
                                   String category, List<String> tags) {}
    public record PictureSummary(String id, String spaceId, String thumbnailUrl, String name,
                                 String introduction, String category, List<String> tags, long size,
                                 int width, int height, String format, String dominantColor,
                                 String visibility, String publishStatus, AuthorSummary author,
                                 Instant createdAt, Instant updatedAt) {}
    public record PictureDetail(String id, String spaceId, String thumbnailUrl, String name,
                                String introduction, String category, List<String> tags, long size,
                                int width, int height, String format, String dominantColor,
                                String visibility, String publishStatus, AuthorSummary author,
                                Instant createdAt, Instant updatedAt, String url,
                                List<String> permissions, String rejectionReason, Instant reviewedAt) {}
    public record PublicPictureSummary(String id, String thumbnailUrl, String name,
                                       String introduction, String category, List<String> tags,
                                       int width, int height, String dominantColor,
                                       AuthorSummary author, Instant publishedAt) {}
    public record PublicPictureDetail(String id, String thumbnailUrl, String name,
                                      String introduction, String category, List<String> tags,
                                      int width, int height, String dominantColor,
                                      AuthorSummary author, Instant publishedAt, String url,
                                      long size, String format) {}
    public record PublishRequestView(String id, PictureSummary picture, AuthorSummary requester,
                                     String status, AuthorSummary reviewer, String decisionReason,
                                     Instant createdAt, Instant reviewedAt) {}
    public record DecisionRequest(String note, String reason) {}
    public record PageMeta(int page, int pageSize, long total, long totalPages) {}
    public record PicturePage(List<PictureSummary> items, PageMeta page) {}
    public record PublishRequestPage(List<PublishRequestView> items, PageMeta page) {}
    public record PublicPictureCursorPage(List<PublicPictureSummary> items, String nextCursor,
                                          boolean hasMore) {}
}
