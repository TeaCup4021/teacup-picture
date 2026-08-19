export type TeamRole = "owner" | "admin" | "editor" | "viewer";

export interface TeamSpace {
  id: string;
  name: string;
  type: "team";
  role: TeamRole;
  ownerId: string;
  maxSize: number;
  maxCount: number;
  totalSize: number;
  totalCount: number;
  memberCount: number;
  permissions: string[];
  createdAt: string;
  updatedAt: string;
}

export interface TeamMember {
  id: string;
  userId: string;
  name: string;
  avatarUrl?: string | null;
  accountMasked: string;
  role: TeamRole;
  joinedAt: string;
}

export interface UserSearchResult {
  id: string;
  name: string;
  avatarUrl?: string | null;
  accountMasked: string;
  relationship: "member" | "pending" | "none";
}

export interface Invitation {
  id: string;
  space: TeamSpace;
  inviter: TeamMember;
  role: Exclude<TeamRole, "owner" | "admin">;
  status: "pending" | "accepted" | "rejected" | "expired";
  expiresAt: string;
  createdAt: string;
  respondedAt?: string | null;
}

export interface NotificationItem {
  id: string;
  type: string;
  actor?: TeamMember | null;
  resourceType: string;
  resourceId?: string | null;
  payload: Record<string, string>;
  readAt?: string | null;
  createdAt: string;
}

export interface PageMeta {
  page: number;
  pageSize: number;
  total: number;
  totalPages: number;
}
