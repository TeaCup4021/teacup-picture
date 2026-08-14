export type AiTaskType = "generate" | "outpaint";
export type AiTaskStatus = "queued" | "running" | "succeeded" | "failed" | "cancelled";
export type AiBackground = "auto" | "opaque" | "transparent";
export type AiOutputFormat = "png" | "jpeg" | "webp";

export interface AiModel {
  id: string;
  code: string;
  name: string;
  capabilities: AiTaskType[];
  ratios: string[];
  qualities: string[];
  backgrounds: AiBackground[];
  outputFormats: AiOutputFormat[];
  supportsOutputCompression: boolean;
  supportsReference: boolean;
  quotaCost: number;
  enabled: boolean;
}

export interface AiQuota {
  taskType: AiTaskType;
  dailyLimit: number;
  used: number;
  reserved: number;
  remaining: number;
}

export interface AiQuotaSummary {
  date: string;
  quotas: AiQuota[];
}

export interface AiPictureRef {
  id: string;
  name: string;
  thumbnailUrl: string;
  url?: string | null;
}

export interface AiTask {
  id: string;
  type: AiTaskType;
  model: AiModel;
  prompt: string;
  ratio: string;
  quality: string;
  background: AiBackground;
  outputFormat: AiOutputFormat;
  outputCompression?: number | null;
  status: AiTaskStatus;
  sourcePicture?: AiPictureRef | null;
  referencePicture?: AiPictureRef | null;
  resultPicture?: AiPictureRef | null;
  failureCode?: string | null;
  failureReason?: string | null;
  quotaRefunded: boolean;
  quotaSettled: boolean;
  createdAt: string;
  startedAt?: string | null;
  finishedAt?: string | null;
  downloadUrl?: string | null;
}

export interface CreateAiTaskInput {
  type: AiTaskType;
  modelCode: string;
  prompt: string;
  ratio: string;
  quality: string;
  background?: AiBackground;
  outputFormat?: AiOutputFormat;
  outputCompression?: number;
  sourcePictureId?: string;
  referencePictureId?: string;
  idempotencyKey: string;
}

export interface AiTaskPage {
  items: AiTask[];
  page: { page: number; pageSize: number; total: number; totalPages: number };
}
