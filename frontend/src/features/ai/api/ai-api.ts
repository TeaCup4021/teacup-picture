import { apiClient, unwrapApiResponse, type ApiEnvelope } from "@/api/client";
import type {
  AiBackground,
  AiModel,
  AiOutputFormat,
  AiQuotaSummary,
  AiTask,
  AiTaskPage,
  AiTaskStatus,
  CreateAiTaskInput,
} from "@/features/ai/model/types";

type ApiAiModel = Omit<AiModel, "backgrounds" | "outputFormats" | "supportsOutputCompression"> & {
  backgrounds?: AiBackground[] | null;
  outputFormats?: AiOutputFormat[] | null;
  supportsOutputCompression?: boolean | null;
};

function normalizeModel(model: ApiAiModel): AiModel {
  return {
    ...model,
    backgrounds: model.backgrounds?.length ? model.backgrounds : ["auto"],
    outputFormats: model.outputFormats?.length ? model.outputFormats : ["png"],
    supportsOutputCompression: model.supportsOutputCompression ?? false,
  };
}

async function get<T>(url: string): Promise<T> {
  return unwrapApiResponse((await apiClient.get<ApiEnvelope<T>>(url)).data);
}

export const aiApi = {
  async models(): Promise<AiModel[]> {
    return (await get<ApiAiModel[]>("/ai/models")).map(normalizeModel);
  },
  quotas: () => get<AiQuotaSummary>("/ai/quotas/me"),
  tasks: (status?: AiTaskStatus) =>
    get<AiTaskPage>(`/ai/tasks?page=1&pageSize=50${status ? `&status=${status}` : ""}`),
  task: (id: string) => get<AiTask>(`/ai/tasks/${id}`),
  async create(input: CreateAiTaskInput): Promise<AiTask> {
    const { idempotencyKey, ...body } = input;
    return unwrapApiResponse(
      (
        await apiClient.post<ApiEnvelope<AiTask>>("/ai/tasks", body, {
          headers: { "Idempotency-Key": idempotencyKey },
        })
      ).data,
    );
  },
  async cancel(id: string): Promise<AiTask> {
    return unwrapApiResponse(
      (await apiClient.post<ApiEnvelope<AiTask>>(`/ai/tasks/${id}/cancel`)).data,
    );
  },
};
