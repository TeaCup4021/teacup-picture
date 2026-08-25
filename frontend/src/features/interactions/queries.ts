"use client";

import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { interactionsApi } from "./api";
import type { CommentInput, CommentPage } from "./model/types";

const keys = {
  comments: (id: string, channel: string) => ["interactions", "comments", channel, id] as const,
  commentThread: (id: string) => ["interactions", "comment-thread", id] as const,
  share: (id: string) => ["interactions", "share", id] as const,
  sharedPicture: (id: string) => ["interactions", "shared-picture", id] as const,
};

export function usePictureComments(pictureId: string, authenticated: boolean) {
  return useInfiniteQuery({
    queryKey: keys.comments(pictureId, authenticated ? "private" : "public"),
    queryFn: ({ pageParam }) => interactionsApi.comments(pictureId, authenticated, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.hasMore ? lastPage.nextCursor ?? undefined : undefined,
    enabled: Boolean(pictureId),
    select: flattenCommentPages,
  });
}

export function useShareComments(publicId: string, enabled: boolean) {
  return useInfiniteQuery({
    queryKey: keys.comments(publicId, "share"),
    queryFn: ({ pageParam }) => interactionsApi.shareComments(publicId, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.hasMore ? lastPage.nextCursor ?? undefined : undefined,
    enabled,
    select: flattenCommentPages,
  });
}

export function useCommentThread(rootId: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: keys.commentThread(rootId ?? ""),
    queryFn: () => interactionsApi.commentThread(rootId!),
    enabled: enabled && Boolean(rootId),
  });
}

function flattenCommentPages(data: { pages: CommentPage[]; pageParams: unknown[] }): CommentPage {
  const first = data.pages[0];
  const last = data.pages[data.pages.length - 1];
  return {
    items: data.pages.flatMap((page) => page.items),
    currentVersionId: first?.currentVersionId,
    nextCursor: last?.nextCursor,
    hasMore: last?.hasMore ?? false,
  };
}

export function useActiveShare(pictureId: string, enabled: boolean) {
  return useQuery({ queryKey: keys.share(pictureId), queryFn: () => interactionsApi.activeShare(pictureId), enabled });
}

export function useSharedPicture(publicId: string, enabled: boolean) {
  return useQuery({ queryKey: keys.sharedPicture(publicId), queryFn: () => interactionsApi.sharedPicture(publicId), enabled });
}

export function useMentionCandidates(pictureId: string, enabled: boolean) {
  return useQuery({
    queryKey: ["interactions", "mention-candidates", pictureId],
    queryFn: () => interactionsApi.mentionCandidates(pictureId),
    enabled: enabled && Boolean(pictureId),
    staleTime: 30_000,
  });
}

export function useCommentActions(refreshKey: readonly unknown[]) {
  const client = useQueryClient();
  const refresh = () => client.invalidateQueries({ queryKey: refreshKey });
  return {
    create: useMutation({ mutationFn: (input: CommentInput) => interactionsApi.createComment(input), onSuccess: refresh }),
    reply: useMutation({
      mutationFn: ({ rootId, replyToId, body, mentionedUserIds }: { rootId: string; replyToId: string; body: string; mentionedUserIds?: string[] }) =>
        interactionsApi.reply(rootId, replyToId, body, mentionedUserIds),
      onSuccess: refresh,
    }),
    resolve: useMutation({ mutationFn: ({ rootId, resolved }: { rootId: string; resolved: boolean }) => interactionsApi.setResolved(rootId, resolved), onSuccess: refresh }),
    remove: useMutation({ mutationFn: interactionsApi.deleteComment, onSuccess: refresh }),
  };
}

export function useShareActions(pictureId: string) {
  const client = useQueryClient(); const refresh = () => client.invalidateQueries({ queryKey: keys.share(pictureId) });
  return {
    create: useMutation({ mutationFn: ({ expiresAt, password }: { expiresAt?: string; password?: string }) => interactionsApi.createShare(pictureId, expiresAt, password), onSuccess: refresh }),
    regenerate: useMutation({ mutationFn: ({ expiresAt, password }: { expiresAt?: string; password?: string }) => interactionsApi.regenerateShare(pictureId, expiresAt, password), onSuccess: refresh }),
    revoke: useMutation({ mutationFn: (shareId: string) => interactionsApi.revokeShare(pictureId, shareId), onSuccess: refresh }),
  };
}

export { keys as interactionKeys };
