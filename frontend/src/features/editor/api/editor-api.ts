import { apiClient, unwrapApiResponse, type ApiEnvelope } from "@/api/client";
import type {
  EditorDocument,
  EditorDraft,
  EditorSaveMode,
  EditorSaveResult,
  PictureVersionDetail,
  PictureVersionSummary,
  VersionListResponse,
} from "@/features/editor/model/types";
import { normalizeEditorDocument } from "@/features/editor/model/document";
import type { CollaborationSession } from "@/features/editor/collaboration/types";

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
  async getCollaborationSession(pictureId: string): Promise<CollaborationSession> {
    return get<CollaborationSession>(`/pictures/${pictureId}/collaboration/session`);
  },

  async checkpointCollaboration(
    pictureId: string,
    input: { roomEpoch: string; lastServerSeq: string; yjsState: string; editorStateHash: string; yjsStateHash: string; editorState: EditorDocument; expectedRevision: string | null },
  ): Promise<{ roomEpoch: string; lastServerSeq: string; revision: string }> {
    return post(`/pictures/${pictureId}/collaboration/checkpoint`, input);
  },

  async getDraft(pictureId: string): Promise<EditorDraft | null> {
    const value = await get<ApiEditorStateView>(`/pictures/${pictureId}/editor-state`);
    return value.editorState && value.revision !== null
      ? {
          editorState: normalizeEditorDocument(value.editorState),
          updatedAt: value.updatedAt,
          revision: value.revision,
        }
      : null;
  },

  async saveDraft(
    pictureId: string,
    editorState: EditorDocument,
    expectedRevision: string | null,
  ): Promise<EditorDraft> {
    const value = await put<ApiEditorStateView>(`/pictures/${pictureId}/editor-state`, {
      editorState: normalizeEditorDocument(editorState),
      expectedRevision,
    });
    if (value.revision === null) throw new Error("草稿保存响应缺少 revision");
    return {
      editorState: normalizeEditorDocument(value.editorState ?? editorState),
      updatedAt: value.updatedAt,
      revision: value.revision,
    };
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
    const version = await get<PictureVersionDetail>(`/pictures/${pictureId}/versions/${versionId}`);
    return { ...version, editorState: normalizeEditorDocument(version.editorState) };
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
    body.append("editorState", JSON.stringify(normalizeEditorDocument(editorState)));
    body.append("name", name);
    body.append("note", note);
    return unwrapApiResponse(
      (
        await apiClient.post<ApiEnvelope<PictureVersionDetail>>(
          `/pictures/${pictureId}/versions`,
          body,
        )
      ).data,
    );
  },

  async saveEditorResult(
    pictureId: string,
    preview: Blob,
    mode: EditorSaveMode,
    name: string,
    expectedRevision: string | null,
    collaborationEditorState?: EditorDocument,
  ): Promise<EditorSaveResult> {
    const body = new FormData();
    body.append("file", preview, "edited.png");
    body.append("mode", mode);
    if (mode === "copy") body.append("name", name);
    if (expectedRevision !== null) body.append("expectedRevision", expectedRevision);
    if (collaborationEditorState) body.append("collaborationEditorState", JSON.stringify(normalizeEditorDocument(collaborationEditorState)));
    return unwrapApiResponse(
      (
        await apiClient.post<ApiEnvelope<EditorSaveResult>>(
          `/pictures/${pictureId}/editor-saves`,
          body,
        )
      ).data,
    );
  },

  async restoreVersion(
    pictureId: string,
    versionId: string,
    expectedRevision: string | null,
  ): Promise<PictureVersionDetail> {
    const version = await post<PictureVersionDetail>(
      `/pictures/${pictureId}/versions/${versionId}/restore`,
      {
        expectedRevision,
      },
    );
    return { ...version, editorState: normalizeEditorDocument(version.editorState) };
  },

  async loadPictureContent(pictureId: string): Promise<Blob> {
    return (
      await apiClient.get<Blob>(`/pictures/${pictureId}/content?variant=original`, {
        responseType: "blob",
      })
    ).data;
  },

  async loadVersionContent(
    pictureId: string,
    versionId: string,
    variant: "original" | "thumbnail",
  ): Promise<Blob> {
    return (
      await apiClient.get<Blob>(
        `/pictures/${pictureId}/versions/${versionId}/content?variant=${variant}`,
        {
          responseType: "blob",
        },
      )
    ).data;
  },
};
