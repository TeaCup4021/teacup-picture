import axios, { AxiosError, type AxiosInstance } from "axios";

export interface ApiEnvelope<T> {
  code: number;
  data: T;
  message: string;
  requestId: string;
}

export interface ApiFieldError {
  field: string;
  reason: string;
}

export class ApiError extends Error {
  readonly code: number;
  readonly requestId?: string;
  readonly status?: number;
  readonly errors?: ApiFieldError[];

  constructor(options: {
    message: string;
    code: number;
    requestId?: string;
    status?: number;
    errors?: ApiFieldError[];
  }) {
    super(options.message);
    this.name = "ApiError";
    this.code = options.code;
    this.requestId = options.requestId;
    this.status = options.status;
    this.errors = options.errors;
  }
}

export function unwrapApiResponse<T>(response: ApiEnvelope<T>): T {
  if (response.code !== 0) {
    throw new ApiError({
      code: response.code,
      message: response.message || "请求失败",
      requestId: response.requestId,
    });
  }

  return response.data;
}

function createApiClient(): AxiosInstance {
  const client = axios.create({
    baseURL: process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://127.0.0.1:8123/api/v1",
    headers: {
      Accept: "application/json",
    },
    timeout: 15_000,
    withCredentials: true,
  });

  client.interceptors.response.use(
    (response) => response,
    (error: AxiosError) => {
      const body = error.response?.data as
        (Partial<ApiEnvelope<null>> & { errors?: ApiFieldError[] }) | undefined;

      throw new ApiError({
        code: body?.code ?? 50000,
        message: body?.message ?? "网络请求失败",
        requestId: body?.requestId ?? error.response?.headers["x-request-id"],
        status: error.response?.status,
        errors: body?.errors,
      });
    },
  );

  return client;
}

export const apiClient = createApiClient();
