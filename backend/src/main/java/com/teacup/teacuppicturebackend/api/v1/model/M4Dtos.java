package com.teacup.teacuppicturebackend.api.v1.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class M4Dtos {
    private M4Dtos() {}

    public record CreateSpaceRequest(String name) {}
    public record UpdateSpaceRequest(String name) {}
    public record SpaceView(String id, String name, String type, String role, String ownerId,
                            long maxSize, long maxCount, long totalSize, long totalCount,
                            int memberCount, List<String> permissions, Instant createdAt, Instant updatedAt) {}
    public record SpaceList(List<SpaceView> items) {}
    public record MemberView(String id, String userId, String name, String avatarUrl, String accountMasked,
                             String role, Instant joinedAt) {}
    public record MemberList(List<MemberView> items) {}
    public record UpdateMemberRequest(String role) {}
    public record TransferOwnershipRequest(String memberId) {}
    public record DeleteSpaceRequest(String confirmationName) {}
    public record UserSearchResult(String id, String name, String avatarUrl, String accountMasked,
                                   String relationship) {}
    public record UserSearchPage(List<UserSearchResult> items) {}
    public record CreateInvitationRequest(String inviteeId, String role) {}
    public record InvitationView(String id, SpaceView space, MemberView inviter, String role, String status,
                                 Instant expiresAt, Instant createdAt, Instant respondedAt) {}
    public record InvitationPage(List<InvitationView> items, M1Dtos.PageMeta page) {}
    public record MarkNotificationsReadRequest(List<String> notificationIds, boolean all) {}
    public record NotificationView(String id, String type, MemberView actor, String resourceType, String resourceId,
                                   Map<String, Object> payload, Instant readAt, Instant createdAt) {}
    public record NotificationPage(List<NotificationView> items, M1Dtos.PageMeta page, long unreadCount) {}
}
