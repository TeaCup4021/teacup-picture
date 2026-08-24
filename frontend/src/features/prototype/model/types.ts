export type PrototypeRole = "user" | "admin";

export type PublishStatus = "not_requested" | "pending" | "approved" | "rejected" | "withdrawn";

export interface PrototypeUser {
  id: string;
  account: string;
  displayName: string;
  role: PrototypeRole;
  avatarText: string;
}

export interface PrototypePicture {
  id: string;
  title: string;
  description: string;
  imageUrl: string;
  width: number;
  height: number;
  authorId: string;
  authorName: string;
  spaceId: string;
  category: string;
  tags: string[];
  createdAt: string;
  views: number;
  likes: number;
  publishStatus: PublishStatus;
  reviewNote?: string;
  reviewRequestId?: string;
  currentVersionId?: string;
  permissions: string[];
  visibility: "private" | "public";
}

export interface PrototypeDatabase {
  users: PrototypeUser[];
  pictures: PrototypePicture[];
  sessionUserId: string | null;
}

export interface UploadPictureInput {
  title: string;
  description: string;
  file?: File;
  imageUrl?: string;
  category: string;
  tags: string[];
  spaceId?: string;
}

export interface RegisterInput {
  account: string;
  password: string;
  passwordConfirmation: string;
}

export interface LoginInput {
  account: string;
  password: string;
}
