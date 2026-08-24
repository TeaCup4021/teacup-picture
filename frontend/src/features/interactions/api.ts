import { apiClient, unwrapApiResponse, type ApiEnvelope } from "@/api/client";
import type { CommentInput, CommentItem, CommentPage, MentionCandidate, SharedPicture, ShareView } from "./model/types";

async function request<T>(method: "get" | "post" | "patch" | "delete", url: string, body?: unknown): Promise<T> {
  return unwrapApiResponse((await apiClient.request<ApiEnvelope<T>>({ method, url, data: body })).data);
}

export function absoluteApiUrl(path: string): string {
  if (/^https?:\/\//.test(path)) return path;
  const base = new URL(String(apiClient.defaults.baseURL));
  const basePath = base.pathname.replace(/\/$/, "");
  const resourcePath = path.startsWith("/") ? path : `/${path}`;
  const resolvedPath =
    resourcePath === basePath || resourcePath.startsWith(`${basePath}/`)
      ? resourcePath
      : `${basePath}${resourcePath}`;
  return new URL(resolvedPath, base.origin).toString();
}

async function download(url: string, fallbackName: string) {
  const response = await apiClient.get<Blob>(url, { responseType: "blob" });
  const objectUrl = URL.createObjectURL(response.data);
  const disposition = String(response.headers["content-disposition"] ?? "");
  const match = disposition.match(/filename\*=UTF-8''([^;]+)/i);
  const anchor = document.createElement("a");
  anchor.href = objectUrl;
  anchor.download = match?.[1] ? decodeURIComponent(match[1]) : fallbackName;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(objectUrl);
}

export const interactionsApi = {
  activeShare: (pictureId: string) => request<ShareView | null>("get", `/pictures/${pictureId}/shares`),
  createShare: (pictureId: string, expiresAt?: string, password?: string) =>
    request<ShareView>("post", `/pictures/${pictureId}/shares`, { expiresAt: expiresAt ?? null, password: password || null }),
  regenerateShare: (pictureId: string, expiresAt?: string, password?: string) =>
    request<ShareView>("post", `/pictures/${pictureId}/shares/regenerate`, { expiresAt: expiresAt ?? null, password: password || null }),
  revokeShare: (pictureId: string, shareId: string) => request<void>("delete", `/pictures/${pictureId}/shares/${shareId}`),
  accessShare: (publicId: string, secret: string, password?: string) =>
    request<{ granted: boolean; passwordProtected: boolean; expiresAt?: string | null }>("post", `/public/shares/${publicId}/access`, { secret, password: password || null }),
  sharedPicture: (publicId: string) => request<SharedPicture>("get", `/public/shares/${publicId}`),
  comments: (pictureId: string, authenticated: boolean, cursor?: string) => request<CommentPage>("get", `${authenticated ? "" : "/public"}/pictures/${pictureId}/comments${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ""}`),
  mentionCandidates: (pictureId: string, query = "") => request<MentionCandidate[]>("get", `/pictures/${pictureId}/comment-mention-candidates${query ? `?q=${encodeURIComponent(query)}` : ""}`),
  shareComments: (publicId: string, cursor?: string) => request<CommentPage>("get", `/public/shares/${publicId}/comments${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ""}`),
  createComment: (input: CommentInput) => request<CommentItem>("post", `/pictures/${input.pictureId}/comments`, {
    kind: input.kind, body: input.body, pictureVersionId: input.pictureVersionId ?? null,
    x: input.x ?? null, y: input.y ?? null, mentionedUserIds: input.mentionedUserIds ?? [],
  }),
  reply: (rootId: string, body: string, mentionedUserIds: string[] = []) => request<CommentItem>("post", `/comments/${rootId}/replies`, { body, replyToId: rootId, mentionedUserIds }),
  setResolved: (rootId: string, resolved: boolean) => request<CommentItem>("patch", `/comments/${rootId}`, { resolved }),
  deleteComment: (commentId: string) => request<void>("delete", `/comments/${commentId}`),
  withdrawPublication: (pictureId: string) => request<unknown>("delete", `/pictures/${pictureId}/publication`),
  downloadPicture: (pictureId: string, isPublic: boolean, name: string) => download(`${isPublic ? "/public" : ""}/pictures/${pictureId}/download`, name),
  downloadShare: (publicId: string, name: string) => download(`/public/shares/${publicId}/download`, name),
};
