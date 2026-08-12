"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { prototypeApi } from "@/features/prototype/api/mock-api";

const keys = {
  root: ["prototype"] as const,
  session: ["prototype", "session"] as const,
  publicPictures: ["prototype", "pictures", "public"] as const,
  personalPictures: ["prototype", "pictures", "personal"] as const,
  picture: (id: string) => ["prototype", "pictures", id] as const,
  reviews: ["prototype", "reviews"] as const,
};

export function usePrototypeSession() {
  return useQuery({ queryKey: keys.session, queryFn: prototypeApi.getSession });
}

export function usePublicPictures() {
  return useQuery({ queryKey: keys.publicPictures, queryFn: prototypeApi.getPublicPictures });
}

export function usePersonalPictures(enabled = true) {
  return useQuery({
    queryKey: keys.personalPictures,
    queryFn: prototypeApi.getPersonalPictures,
    enabled,
  });
}

export function usePrototypePicture(pictureId: string) {
  return useQuery({
    queryKey: keys.picture(pictureId),
    queryFn: () => prototypeApi.getPicture(pictureId),
  });
}

export function usePendingReviews(enabled = true) {
  return useQuery({
    queryKey: keys.reviews,
    queryFn: prototypeApi.getPendingReviews,
    enabled,
  });
}

function useRefreshPrototype() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: keys.root });
}

export function usePrototypeLogin() {
  const refresh = useRefreshPrototype();
  return useMutation({ mutationFn: prototypeApi.login, onSuccess: refresh });
}

export function usePrototypeLogout() {
  const refresh = useRefreshPrototype();
  return useMutation({ mutationFn: prototypeApi.logout, onSuccess: refresh });
}

export function usePrototypeUpload() {
  const refresh = useRefreshPrototype();
  return useMutation({ mutationFn: prototypeApi.uploadPicture, onSuccess: refresh });
}

export function useSubmitReview() {
  const refresh = useRefreshPrototype();
  return useMutation({ mutationFn: prototypeApi.submitReview, onSuccess: refresh });
}

export function useDecideReview() {
  const refresh = useRefreshPrototype();
  return useMutation({ mutationFn: prototypeApi.decideReview, onSuccess: refresh });
}

export function useResetPrototype() {
  const queryClient = useQueryClient();
  return () => {
    prototypeApi.reset();
    void queryClient.invalidateQueries({ queryKey: keys.root });
  };
}
