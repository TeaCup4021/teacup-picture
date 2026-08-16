import { apiClient, unwrapApiResponse, type ApiEnvelope } from "@/api/client";
import type {
  EditorDocument,
  EditorDraft,
  PictureVersionDetail,
  PictureVersionSummary,
  VersionListResponse,
} from "@/features/editor/model/types";

interface ApiEditorStateView {
  editorState: EditorDocument | null;
  updatedAt: string | null;
  revision: string | null;
}

async function get<T>(url: string): Promise<T> {
  return unwrapApiResponse((await apiClient.get<ApiEnvelope<T>>(url)).data);
}

async function post<T>(url: string, body?: unknown): Promise<T> {
  return unwrapApiResponse((await apiClient.post<ApiEnvelope<T>>(url, body)).data);
}

async function put<T>(url: string, body?: unknown): Promise<T> {
  return unwrapApiResponse((await apiClient.put<ApiEnvelope<T>>(url, body)).data);
}

export const editorApi = {
  async getDraft(pictureId: string): Promise<EditorDraft | null> {
    const value = await get<ApiEditorStateView>(`/pictures/${pictureId}/editor-state`);
    return value.editorState && value.revision !== null
      ? { editorState: value.editorState, updatedAt: value.updatedAt, revision: value.revision }
      : null;
  },

  async saveDraft(
    pictureId: string,
    editorState: EditorDocument,
    expectedRevision: string | null,
  ): Promise<EditorDraft> {
    const value = await put<ApiEditorStateView>(`/pictures/${pictureId}/editor-state`, {
      editorState,
      expectedRevision,
    });
    if (value.revision === null) throw new Error("草稿保存响应缺少 revision");
    return { editorState: value.editorState ?? editorState, updatedAt: value.updatedAt, revision: value.revision };
  },

  async deleteDraft(pictureId: string, expectedRevision: string | null): Promise<void> {
    await apiClient.delete<ApiEnvelope<ApiEditorStateView>>(`/pictures/${pictureId}/editor-state`, {
      data: { expectedRevision },
    });
  },

  async listVersions(pictureId: string): Promise<PictureVersionSummary[]> {
    const value = await get<VersionListResponse>(`/pictures/${pictureId}/versions`);
    return value.items;
  },

  async getVersion(pictureId: string, versionId: string): Promise<PictureVersionDetail> {
    return get<PictureVersionDetail>(`/pictures/${pictureId}/versions/${versionId}`);
  },

  async createVersion(
    pictureId: string,
    editorState: EditorDocument,
    preview: Blob,
    name: string,
    note: string,
  ): Promise<PictureVersionDetail> {
    const body = new FormData();
    body.append("file", preview, "preview.png");
    body.append("editorState", JSON.stringify(editorState));
    body.append("name", name);
    body.append("note", note);
    return unwrapApiResponse(
      (await apiClient.post<ApiEnvelope<PictureVersionDetail>>(`/pictures/${pictureId}/versions`, body)).data,
    );
  },

  async restoreVersion(
    pictureId: string,
    versionId: string,
    expectedRevision: string | null,
  ): Promise<PictureVersionDetail> {
    return post<PictureVersionDetail>(`/pictures/${pictureId}/versions/${versionId}/restore`, {
      expectedRevision,
    });
  },

  async loadPictureContent(pictureId: string): Promise<Blob> {
    return (await apiClient.get<Blob>(`/pictures/${pictureId}/content?variant=original`, { responseType: "blob" })).data;
  },

  async loadVersionContent(pictureId: string, versionId: string, variant: "original" | "thumbnail"): Promise<Blob> {
    return (
      await apiClient.get<Blob>(`/pictures/${pictureId}/versions/${versionId}/content?variant=${variant}`, {
        responseType: "blob",
      })
    ).data;
  },
};
