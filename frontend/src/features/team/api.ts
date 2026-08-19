import { apiClient, unwrapApiResponse, type ApiEnvelope } from "@/api/client";
import type { PrototypePicture } from "@/features/prototype/model/types";
import type { Invitation, NotificationItem, PageMeta, TeamMember, TeamRole, TeamSpace, UserSearchResult } from "./model/types";

interface Page<T> { items: T[]; page?: PageMeta; unreadCount?: number }

async function request<T>(method: "get" | "post" | "patch" | "delete", url: string, body?: unknown): Promise<T> {
  const response = await apiClient.request<ApiEnvelope<T>>({ method, url, data: body });
  return unwrapApiResponse(response.data);
}

function toPicture(value: { id: string; spaceId?: string; thumbnailUrl: string; url?: string; name: string; introduction?: string | null; category?: string | null; tags: string[]; width: number; height: number; publishStatus?: PrototypePicture["publishStatus"]; author: { id: string; name: string }; createdAt?: string; updatedAt?: string }): PrototypePicture {
  return { id: value.id, title: value.name, description: value.introduction ?? "暂无描述", imageUrl: value.url ?? value.thumbnailUrl, width: value.width, height: value.height, authorId: value.author.id, authorName: value.author.name, spaceId: value.spaceId ?? "", category: value.category ?? "未分类", tags: value.tags, createdAt: value.createdAt ?? new Date().toISOString(), views: 0, likes: 0, publishStatus: value.publishStatus ?? "not_requested" };
}

export const teamApi = {
  listSpaces: () => request<{ items: TeamSpace[] }>("get", "/spaces"),
  createSpace: (name: string) => request<TeamSpace>("post", "/spaces", { name }),
  getSpace: (spaceId: string) => request<TeamSpace>("get", `/spaces/${spaceId}`),
  updateSpace: (spaceId: string, name: string) => request<TeamSpace>("patch", `/spaces/${spaceId}`, { name }),
  deleteSpace: (spaceId: string, confirmationName: string) => request<void>("delete", `/spaces/${spaceId}`, { confirmationName }),
  listMembers: (spaceId: string) => request<{ items: TeamMember[] }>("get", `/spaces/${spaceId}/members`),
  updateMember: (spaceId: string, memberId: string, role: TeamRole) => request<TeamMember>("patch", `/spaces/${spaceId}/members/${memberId}`, { role }),
  removeMember: (spaceId: string, memberId: string) => request<void>("delete", `/spaces/${spaceId}/members/${memberId}`),
  transferOwnership: (spaceId: string, memberId: string) => request<TeamSpace>("post", `/spaces/${spaceId}/transfer-ownership`, { memberId }),
  searchUsers: (spaceId: string, q: string) => request<{ items: UserSearchResult[] }>("get", `/users/search?spaceId=${encodeURIComponent(spaceId)}&q=${encodeURIComponent(q)}`),
  invite: (spaceId: string, inviteeId: string, role: "viewer" | "editor") => request<Invitation>("post", `/spaces/${spaceId}/invitations`, { inviteeId, role }),
  listInvitations: () => request<Page<Invitation>>("get", "/invitations/me?page=1&pageSize=50"),
  acceptInvitation: (id: string) => request<Invitation>("post", `/invitations/${id}/accept`),
  rejectInvitation: (id: string) => request<Invitation>("post", `/invitations/${id}/reject`),
  listNotifications: () => request<Page<NotificationItem>>("get", "/notifications?page=1&pageSize=50"),
  markNotificationsRead: (ids?: string[]) => request<number>("post", "/notifications/read", ids?.length ? { notificationIds: ids, all: false } : { notificationIds: [], all: true }),
  listPictures: async (spaceId: string) => {
    const result = await request<{ items: Array<Parameters<typeof toPicture>[0]> }>("get", `/pictures?page=1&pageSize=100&spaceId=${encodeURIComponent(spaceId)}`);
    return result.items.map(toPicture);
  },
};
