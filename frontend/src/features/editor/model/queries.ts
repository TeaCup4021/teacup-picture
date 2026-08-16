"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { editorApi } from "@/features/editor/api/editor-api";
import { m1Api } from "@/features/prototype";
import type { EditorDocument, RestoreVersionResult } from "@/features/editor/model/types";

export interface SaveDraftInput {
  document: EditorDocument;
  expectedRevision: string | null;
}

export interface RestoreVersionInput {
  versionId: string;
  expectedRevision: string | null;
}

const keys = {
  root: ["editor"] as const,
  picture: (id: string) => ["editor", "picture", id] as const,
  baseImage: (id: string) => ["editor", "base-image", id] as const,
  draft: (id: string) => ["editor", "draft", id] as const,
  versions: (id: string) => ["editor", "versions", id] as const,
};

export function useEditorPicture(pictureId: string, enabled = true) {
  return useQuery({
    queryKey: keys.picture(pictureId),
    queryFn: () => m1Api.getPicture(pictureId),
    enabled,
  });
}

export function useEditorBaseImage(pictureId: string, enabled = true) {
  return useQuery({
    queryKey: keys.baseImage(pictureId),
    queryFn: async () => {
      const blob = await editorApi.loadPictureContent(pictureId);
      const url = URL.createObjectURL(blob);
      try {
        return await loadHtmlImage(url);
      } finally {
        URL.revokeObjectURL(url);
      }
    },
    enabled,
  });
}

export function useEditorDraft(pictureId: string, enabled = true) {
  return useQuery({
    queryKey: keys.draft(pictureId),
    queryFn: () => editorApi.getDraft(pictureId),
    enabled,
  });
}

export function useEditorVersions(pictureId: string, enabled = true) {
  return useQuery({
    queryKey: keys.versions(pictureId),
    queryFn: () => editorApi.listVersions(pictureId),
    enabled,
  });
}

export function useSaveEditorDraft(pictureId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: SaveDraftInput) =>
      editorApi.saveDraft(pictureId, input.document, input.expectedRevision),
    onSuccess: (value) => queryClient.setQueryData(keys.draft(pictureId), value),
  });
}

export function useDeleteEditorDraft(pictureId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (expectedRevision: string | null) => editorApi.deleteDraft(pictureId, expectedRevision),
    onSuccess: () => queryClient.setQueryData(keys.draft(pictureId), null),
  });
}

export function useCreatePictureVersion(pictureId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { document: EditorDocument; preview: Blob; name: string; note: string }) =>
      editorApi.createVersion(pictureId, input.document, input.preview, input.name, input.note),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.versions(pictureId) }),
  });
}

export function useRestorePictureVersion(pictureId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: RestoreVersionInput): Promise<RestoreVersionResult> => {
      const version = await editorApi.restoreVersion(
        pictureId,
        input.versionId,
        input.expectedRevision,
      );
      const draft = await editorApi.getDraft(pictureId);
      if (!draft) throw new Error("恢复版本后未读取到草稿");
      return { version, draft };
    },
    onSuccess: (value) => {
      queryClient.setQueryData(keys.draft(pictureId), value.draft);
      return Promise.all([
        queryClient.invalidateQueries({ queryKey: keys.versions(pictureId) }),
      ]);
    },
  });
}

function loadHtmlImage(url: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error("图片加载失败"));
    image.src = url;
  });
}
