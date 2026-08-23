"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { editorApi } from "@/features/editor/api/editor-api";
import { m1Api } from "@/features/prototype";
import type {
  EditorDocument,
  EditorSaveMode,
  PictureVersionDetail,
} from "@/features/editor/model/types";

export interface SaveDraftInput {
  document: EditorDocument;
  expectedRevision: string | null;
}

export interface RestoreVersionInput {
  versionId: string;
  expectedRevision: string | null;
}

export interface SaveEditorResultInput {
  preview: Blob;
  mode: EditorSaveMode;
  name: string;
  expectedRevision: string | null;
  collaborationEditorState?: EditorDocument;
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
    mutationFn: (expectedRevision: string | null) =>
      editorApi.deleteDraft(pictureId, expectedRevision),
    onSuccess: () => queryClient.setQueryData(keys.draft(pictureId), null),
  });
}

export function useSaveEditorResult(pictureId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: SaveEditorResultInput) =>
      editorApi.saveEditorResult(
        pictureId,
        input.preview,
        input.mode,
        input.name,
        input.expectedRevision,
        input.collaborationEditorState,
      ),
    onSuccess: async () => {
      queryClient.setQueryData(keys.draft(pictureId), null);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: keys.picture(pictureId) }),
        queryClient.invalidateQueries({ queryKey: keys.baseImage(pictureId) }),
        queryClient.invalidateQueries({ queryKey: keys.versions(pictureId) }),
        queryClient.invalidateQueries({ queryKey: ["prototype"] }),
      ]);
    },
  });
}

export function useRestorePictureVersion(pictureId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: RestoreVersionInput): Promise<PictureVersionDetail> =>
      editorApi.restoreVersion(pictureId, input.versionId, input.expectedRevision),
    onSuccess: async () => {
      queryClient.setQueryData(keys.draft(pictureId), null);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: keys.picture(pictureId) }),
        queryClient.invalidateQueries({ queryKey: keys.baseImage(pictureId) }),
        queryClient.invalidateQueries({ queryKey: keys.versions(pictureId) }),
        queryClient.invalidateQueries({ queryKey: ["prototype"] }),
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
