"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { aiApi } from "@/features/ai/api/ai-api";
import type { AiTaskStatus } from "@/features/ai/model/types";

const keys = {
  root: ["ai"] as const,
  models: ["ai", "models"] as const,
  quotas: ["ai", "quotas"] as const,
  tasks: (status?: AiTaskStatus) => ["ai", "tasks", status ?? "all"] as const,
};

export function useAiModels(enabled = true) {
  return useQuery({ queryKey: keys.models, queryFn: aiApi.models, enabled });
}

export function useAiQuotas(enabled = true) {
  return useQuery({ queryKey: keys.quotas, queryFn: aiApi.quotas, enabled });
}

export function useAiTasks(status?: AiTaskStatus, enabled = true) {
  return useQuery({
    queryKey: keys.tasks(status),
    queryFn: () => aiApi.tasks(status),
    enabled,
    refetchInterval: (query) =>
      query.state.data?.items.some((task) => task.status === "queued" || task.status === "running")
        ? 3_000
        : false,
  });
}

function useRefreshAi() {
  const client = useQueryClient();
  return () => client.invalidateQueries({ queryKey: keys.root });
}

export function useCreateAiTask() {
  const refresh = useRefreshAi();
  return useMutation({ mutationFn: aiApi.create, onSuccess: refresh });
}

export function useCancelAiTask() {
  const refresh = useRefreshAi();
  return useMutation({ mutationFn: aiApi.cancel, onSuccess: refresh });
}
