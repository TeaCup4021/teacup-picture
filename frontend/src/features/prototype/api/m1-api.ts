import { ApiError, apiClient, unwrapApiResponse, type ApiEnvelope } from "@/api/client";
import type {
  LoginInput,
  PrototypePicture,
  PrototypeUser,
  RegisterInput,
  UploadPictureInput,
} from "@/features/prototype/model/types";

const UPLOAD_TIMEOUT_MS = 5 * 60 * 1000;
const RESUMABLE_THRESHOLD = 8 * 1024 * 1024;
const RESUMABLE_CHUNK_SIZE = 5 * 1024 * 1024;

interface ApiUser { id: string; account: string; name: string; role: "user" | "admin" }
interface ApiAuthor { id: string; name: string }
interface ApiPicture {
  id: string; spaceId?: string; thumbnailUrl: string; url?: string; name: string;
  introduction?: string | null; category?: string | null; tags: string[]; width: number;
  height: number; publishStatus?: PrototypePicture["publishStatus"]; author: ApiAuthor;
  createdAt?: string; publishedAt?: string; rejectionReason?: string | null;
  currentVersionId?: string | null; permissions?: string[]; visibility?: "private" | "public";
}
interface ApiPublishRequest { id: string; picture: ApiPicture; decisionReason?: string | null }
interface ApiPage<T> { items: T[] }
interface UploadSession {
  id: string;
  chunkSize: number;
  totalParts: number;
  totalSize: number;
  uploadedParts: number[];
  expiresAt: string;
  status: string;
}

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
    currentVersionId: value.currentVersionId ?? undefined,
    permissions: value.permissions ?? [],
    visibility: value.visibility ?? (value.publishStatus === "approved" ? "public" : "private"),
  };
}

async function get<T>(url: string): Promise<T> {
  return unwrapApiResponse((await apiClient.get<ApiEnvelope<T>>(url)).data);
}

async function post<T>(url: string, body?: unknown): Promise<T> {
  return unwrapApiResponse((await apiClient.post<ApiEnvelope<T>>(url, body)).data);
}

async function postForm<T>(
  url: string,
  fields: Record<string, string | string[] | undefined>,
  options?: { signal?: AbortSignal },
): Promise<T> {
  const body = new URLSearchParams();
  for (const [key, value] of Object.entries(fields)) {
    if (Array.isArray(value)) value.forEach((item) => body.append(key, item));
    else if (value !== undefined) body.append(key, value);
  }
  return unwrapApiResponse((await apiClient.post<ApiEnvelope<T>>(url, body, {
    headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
    signal: options?.signal,
    timeout: UPLOAD_TIMEOUT_MS,
  })).data);
}

export const m1Api = {
  async previewPictureUrl(url: string): Promise<{ src: string; width: number; height: number; previewToken: string }> {
    const response = await apiClient.get<Blob>("/pictures/url-preview", {
      params: { url },
      responseType: "blob",
      timeout: 60_000,
    });
    const src = URL.createObjectURL(response.data);
    try {
      const dimensions = await new Promise<{ width: number; height: number }>((resolve, reject) => {
        const image = new window.Image();
        image.onerror = () => reject(new Error("图片 URL 无法加载或不是可识别的图片"));
        image.onload = () => resolve({ width: image.naturalWidth, height: image.naturalHeight });
        image.src = src;
      });
      const previewToken = response.headers["x-teacup-preview-token"];
      if (typeof previewToken !== "string" || !previewToken) {
        throw new Error("图片 URL 预览已失效，请重新预览");
      }
      return { src, ...dimensions, previewToken };
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
  async getPersonalPictures(spaceId?: string): Promise<PrototypePicture[]> {
    const query = new URLSearchParams({ page: "1", pageSize: "100" });
    if (spaceId) query.set("spaceId", spaceId);
    const page = await get<ApiPage<ApiPicture>>(`/pictures?${query.toString()}`);
    return page.items.map((item) => picture(item));
  },
  async uploadPicture(input: UploadPictureInput): Promise<PrototypePicture> {
    let result: ApiPicture;
    if (input.file) {
      if (input.file.size > RESUMABLE_THRESHOLD) {
        result = await resumableUpload(input);
        return picture(result);
      }
      const data = new FormData(); data.append("file", input.file); data.append("name", input.title);
      data.append("introduction", input.description); data.append("category", input.category);
      if (input.spaceId) data.append("spaceId", input.spaceId);
      input.tags.forEach((tag) => data.append("tags", tag));
      result = unwrapApiResponse((await apiClient.post<ApiEnvelope<ApiPicture>>("/pictures/uploads", data, {
        signal: input.signal,
        timeout: UPLOAD_TIMEOUT_MS,
        onUploadProgress: (event) => input.onUploadProgress?.({ loaded: event.loaded, total: event.total }),
      })).data);
    } else {
      result = await postForm<ApiPicture>("/pictures/url-imports", {
        url: input.imageUrl,
        previewToken: input.previewToken,
        name: input.title,
        introduction: input.description,
        category: input.category,
        tags: input.tags,
        spaceId: input.spaceId,
      }, { signal: input.signal });
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

async function sha256(blob: Blob): Promise<string> {
  const buffer = await blob.arrayBuffer();
  const digest = await crypto.subtle.digest("SHA-256", buffer);
  return [...new Uint8Array(digest)].map((value) => value.toString(16).padStart(2, "0")).join("");
}

async function resumableUpload(input: UploadPictureInput): Promise<ApiPicture> {
  const file = input.file;
  if (!file) throw new Error("请选择图片文件");
  const fingerprint = `${file.name}:${file.size}:${file.lastModified}`;
  const resumeKey = `teacup-upload:${fingerprint}`;
  let session: UploadSession;
  try {
    const cached = typeof window !== "undefined" ? window.sessionStorage.getItem(resumeKey) : null;
    session = cached ? JSON.parse(cached) as UploadSession : await createUploadSession(input, file);
    if (session.status !== "active") throw new Error("上传会话已结束");
    const current = await get<UploadSession>(`/picture-upload-sessions/${session.id}`);
    session = current;
  } catch {
    session = await createUploadSession(input, file);
  }
  if (typeof window !== "undefined") window.sessionStorage.setItem(resumeKey, JSON.stringify(session));
  try {
    const uploaded = new Set(session.uploadedParts);
    const totalParts = Math.ceil(file.size / session.chunkSize);
    for (let partNumber = 1; partNumber <= totalParts; partNumber += 1) {
      if (uploaded.has(partNumber)) {
        input.onUploadProgress?.({ loaded: Math.min(file.size, partNumber * session.chunkSize), total: file.size });
        continue;
      }
      const start = (partNumber - 1) * session.chunkSize;
      const chunk = file.slice(start, Math.min(file.size, start + session.chunkSize));
      const checksum = await sha256(chunk);
      const response = await apiClient.put<ApiEnvelope<UploadSession>>(
        `/picture-upload-sessions/${session.id}/parts/${partNumber}`, chunk,
        { signal: input.signal, timeout: UPLOAD_TIMEOUT_MS, headers: { "Content-Type": "application/octet-stream", "X-Chunk-SHA256": checksum } },
      );
      session = unwrapApiResponse(response.data);
      uploaded.add(partNumber);
      input.onUploadProgress?.({ loaded: Math.min(file.size, partNumber * session.chunkSize), total: file.size });
      if (typeof window !== "undefined") window.sessionStorage.setItem(resumeKey, JSON.stringify(session));
    }
    const result = unwrapApiResponse((await apiClient.post<ApiEnvelope<ApiPicture>>(
      `/picture-upload-sessions/${session.id}/complete`, {
        fileName: file.name, contentType: file.type, totalSize: file.size, chunkSize: session.chunkSize,
        totalParts, spaceId: input.spaceId, name: input.title, introduction: input.description,
        category: input.category, tags: input.tags,
      }, { signal: input.signal, timeout: UPLOAD_TIMEOUT_MS },
    )).data);
    if (typeof window !== "undefined") window.sessionStorage.removeItem(resumeKey);
    input.onUploadProgress?.({ loaded: file.size, total: file.size });
    return result;
  } catch (error) {
    if (input.signal?.aborted) {
      await apiClient.delete(`/picture-upload-sessions/${session.id}`).catch(() => undefined);
      if (typeof window !== "undefined") window.sessionStorage.removeItem(resumeKey);
    }
    throw error;
  }
}

async function createUploadSession(input: UploadPictureInput, file: File): Promise<UploadSession> {
  return post<UploadSession>("/picture-upload-sessions", {
    fileName: file.name, contentType: file.type, totalSize: file.size,
    chunkSize: RESUMABLE_CHUNK_SIZE, spaceId: input.spaceId,
    name: input.title, introduction: input.description, category: input.category, tags: input.tags,
  });
}
