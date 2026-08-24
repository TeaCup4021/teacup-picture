export interface ShareView {
  id: string;
  pictureId: string;
  publicId: string;
  sharePath?: string | null;
  passwordProtected: boolean;
  expiresAt?: string | null;
  revokedAt?: string | null;
  createdAt: string;
}

export interface SharedPicture {
  id: string;
  name: string;
  introduction?: string | null;
  category?: string | null;
  tags: string[];
  width: number;
  height: number;
  author: { id: string; name: string; avatarUrl?: string | null };
  imageUrl: string;
  currentVersionId?: string | null;
  canDownload: boolean;
  canComment: boolean;
}

export interface CommentItem {
  id: string;
  pictureId: string;
  pictureVersionId?: string | null;
  kind: "comment" | "annotation" | "reply";
  body: string;
  x?: number | null;
  y?: number | null;
  author: { id?: string | null; name: string; avatarUrl?: string | null };
  deleted: boolean;
  resolved: boolean;
  resolvedBy?: string | null;
  resolvedAt?: string | null;
  createdAt: string;
  updatedAt: string;
  replyToId?: string | null;
  replies: CommentItem[];
  replyCount: number;
  canDelete: boolean;
  canResolve: boolean;
}

export interface CommentPage {
  items: CommentItem[];
  nextCursor?: string | null;
  hasMore: boolean;
  currentVersionId?: string | null;
}

export interface CommentInput {
  pictureId: string;
  kind: "comment" | "annotation";
  body: string;
  pictureVersionId?: string;
  x?: number;
  y?: number;
  mentionedUserIds?: string[];
}

export interface MentionCandidate {
  id: string;
  name: string;
  avatarUrl?: string | null;
}
