"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useSyncExternalStore } from "react";
import { m1Api } from "@/features/prototype/api/m1-api";

const keys = {
  root: ["prototype"] as const,
  session: ["prototype", "session"] as const,
  publicPictures: ["prototype", "pictures", "public"] as const,
  personalPictures: ["prototype", "pictures", "personal"] as const,
  picture: (id: string) => ["prototype", "pictures", id] as const,
  reviews: ["prototype", "reviews"] as const,
};

const subscribeToHydration = () => () => undefined;

export function usePrototypeSession() {
  const isHydrated = useSyncExternalStore(
    subscribeToHydration,
    () => true,
    () => false,
  );
  const query = useQuery({
    queryKey: keys.session,
    queryFn: m1Api.getSession,
    enabled: isHydrated,
  });

  return {
    ...query,
    data: isHydrated ? query.data : undefined,
    isLoading: !isHydrated || query.isLoading,
  };
}

export function usePublicPictures() {
  return useQuery({ queryKey: keys.publicPictures, queryFn: m1Api.getPublicPictures });
}

export function usePersonalPictures(enabled = true) {
  return useQuery({
    queryKey: keys.personalPictures,
    queryFn: m1Api.getPersonalPictures,
    enabled,
  });
}

export function usePrototypePicture(pictureId: string) {
  return useQuery({
    queryKey: keys.picture(pictureId),
    queryFn: () => m1Api.getPicture(pictureId),
  });
}

export function usePendingReviews(enabled = true) {
  return useQuery({
    queryKey: keys.reviews,
    queryFn: m1Api.getPendingReviews,
    enabled,
  });
}

function useRefreshPrototype() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: keys.root });
}

export function usePrototypeLogin() {
  const refresh = useRefreshPrototype();
  return useMutation({ mutationFn: m1Api.login, onSuccess: refresh });
}

export function usePrototypeRegister() {
  return useMutation({ mutationFn: m1Api.register });
}

export function usePrototypeLogout() {
  const refresh = useRefreshPrototype();
  return useMutation({ mutationFn: m1Api.logout, onSuccess: refresh });
}

export function usePrototypeUpload() {
  const refresh = useRefreshPrototype();
  return useMutation({ mutationFn: m1Api.uploadPicture, onSuccess: refresh });
}

export function useSubmitReview() {
  const refresh = useRefreshPrototype();
  return useMutation({ mutationFn: m1Api.submitReview, onSuccess: refresh });
}

export function useDecideReview() {
  const refresh = useRefreshPrototype();
  return useMutation({ mutationFn: m1Api.decideReview, onSuccess: refresh });
}
