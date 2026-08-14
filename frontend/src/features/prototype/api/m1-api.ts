import { ApiError, apiClient, unwrapApiResponse, type ApiEnvelope } from "@/api/client";
import type {
  LoginInput,
  PrototypePicture,
  PrototypeUser,
  RegisterInput,
  UploadPictureInput,
} from "@/features/prototype/model/types";

interface ApiUser { id: string; account: string; name: string; role: "user" | "admin" }
interface ApiAuthor { id: string; name: string }
interface ApiPicture {
  id: string; spaceId?: string; thumbnailUrl: string; url?: string; name: string;
  introduction?: string | null; category?: string | null; tags: string[]; width: number;
  height: number; publishStatus?: PrototypePicture["publishStatus"]; author: ApiAuthor;
  createdAt?: string; publishedAt?: string; rejectionReason?: string | null;
}
interface ApiPublishRequest { id: string; picture: ApiPicture; decisionReason?: string | null }
interface ApiPage<T> { items: T[] }

function user(value: ApiUser): PrototypeUser {
  return { id: value.id, account: value.account, displayName: value.name, role: value.role, avatarText: value.name.slice(0, 1) };
}

function picture(value: ApiPicture, requestId?: string): PrototypePicture {
  return {
    id: value.id,
    title: value.name,
    description: value.introduction ?? "暂无描述",
    imageUrl: value.url ?? value.thumbnailUrl,
    width: value.width,
    height: value.height,
    authorId: value.author.id,
    authorName: value.author.name,
    spaceId: value.spaceId ?? "public",
    category: value.category ?? "未分类",
    tags: value.tags,
    createdAt: value.createdAt ?? value.publishedAt ?? new Date().toISOString(),
    views: 0,
    likes: 0,
    publishStatus: value.publishStatus ?? "approved",
    reviewNote: value.rejectionReason ?? undefined,
    reviewRequestId: requestId,
  };
}

async function get<T>(url: string): Promise<T> {
  return unwrapApiResponse((await apiClient.get<ApiEnvelope<T>>(url)).data);
}

async function post<T>(url: string, body?: unknown): Promise<T> {
  return unwrapApiResponse((await apiClient.post<ApiEnvelope<T>>(url, body)).data);
}

async function postForm<T>(url: string, fields: Record<string, string | string[] | undefined>): Promise<T> {
  const body = new URLSearchParams();
  for (const [key, value] of Object.entries(fields)) {
    if (Array.isArray(value)) value.forEach((item) => body.append(key, item));
    else if (value !== undefined) body.append(key, value);
  }
  return unwrapApiResponse((await apiClient.post<ApiEnvelope<T>>(url, body, {
    headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
  })).data);
}

export const m1Api = {
  async previewPictureUrl(url: string): Promise<{ src: string; width: number; height: number }> {
    const response = await apiClient.get<Blob>("/pictures/url-preview", {
      params: { url },
      responseType: "blob",
    });
    const src = URL.createObjectURL(response.data);
    try {
      const dimensions = await new Promise<{ width: number; height: number }>((resolve, reject) => {
        const image = new window.Image();
        image.onerror = () => reject(new Error("图片 URL 无法加载或不是可识别的图片"));
        image.onload = () => resolve({ width: image.naturalWidth, height: image.naturalHeight });
        image.src = src;
      });
      return { src, ...dimensions };
    } catch (error) {
      URL.revokeObjectURL(src);
      throw error;
    }
  },
  async register(input: RegisterInput): Promise<{ userId: string; personalSpaceId: string }> {
    return post("/auth/register", input);
  },
  async login(input: LoginInput): Promise<PrototypeUser> {
    return user(await post<ApiUser>("/auth/login", input));
  },
  async logout(): Promise<void> { await post<boolean>("/auth/logout"); },
  async getSession(): Promise<PrototypeUser | null> {
    try { return user(await get<ApiUser>("/auth/me")); }
    catch (error) { if (error instanceof ApiError && error.status === 401) return null; throw error; }
  },
  async getPublicPictures(): Promise<PrototypePicture[]> {
    const page = await get<ApiPage<ApiPicture>>("/public/pictures?limit=50");
    return page.items.map((item) => picture(item));
  },
  async getPicture(pictureId: string): Promise<PrototypePicture | null> {
    try { return picture(await get<ApiPicture>(`/pictures/${pictureId}`)); }
    catch (error) {
      if (!(error instanceof ApiError) || ![401, 404].includes(error.status ?? 0)) throw error;
      try { return picture(await get<ApiPicture>(`/public/pictures/${pictureId}`)); }
      catch (publicError) { if (publicError instanceof ApiError && publicError.status === 404) return null; throw publicError; }
    }
  },
  async getPersonalPictures(): Promise<PrototypePicture[]> {
    const page = await get<ApiPage<ApiPicture>>("/pictures?page=1&pageSize=100");
    return page.items.map((item) => picture(item));
  },
  async uploadPicture(input: UploadPictureInput): Promise<PrototypePicture> {
    let result: ApiPicture;
    if (input.file) {
      const data = new FormData(); data.append("file", input.file); data.append("name", input.title);
      data.append("introduction", input.description); data.append("category", input.category);
      input.tags.forEach((tag) => data.append("tags", tag));
      result = unwrapApiResponse((await apiClient.post<ApiEnvelope<ApiPicture>>("/pictures/uploads", data)).data);
    } else {
      result = await postForm<ApiPicture>("/pictures/url-imports", {
        url: input.imageUrl,
        name: input.title,
        introduction: input.description,
        category: input.category,
        tags: input.tags,
      });
    }
    return picture(result);
  },
  async submitReview(pictureId: string): Promise<PrototypePicture> {
    const result = await post<ApiPublishRequest>(`/pictures/${pictureId}/publish-requests`);
    return picture(result.picture, result.id);
  },
  async getPendingReviews(): Promise<PrototypePicture[]> {
    const page = await get<ApiPage<ApiPublishRequest>>("/admin/publish-requests?page=1&pageSize=100&status=pending");
    return page.items.map((item) => picture(item.picture, item.id));
  },
  async decideReview(input: { pictureId: string; requestId?: string; decision: "approve" | "reject"; note?: string }): Promise<PrototypePicture> {
    if (!input.requestId) throw new Error("缺少审核申请 ID");
    const suffix = input.decision === "approve" ? "approve" : "reject";
    const body = input.decision === "approve" ? {} : { reason: input.note };
    const result = await post<ApiPublishRequest>(`/admin/publish-requests/${input.requestId}/${suffix}`, body);
    return picture(result.picture, result.id);
  },
};
