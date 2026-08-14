import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiClient } from "@/api/client";
import { aiApi } from "@/features/ai/api/ai-api";

vi.mock("@/api/client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/api/client")>();
  return { ...actual, apiClient: { get: vi.fn(), post: vi.fn() } };
});

describe("aiApi", () => {
  beforeEach(() => vi.clearAllMocks());

  it("sends the idempotency key outside the request body", async () => {
    vi.mocked(apiClient.post).mockResolvedValue({
      data: { code: 0, data: { id: "9007199254740993" }, message: "ok", requestId: "r1" },
    });
    await aiApi.create({
      type: "generate",
      modelCode: "wanx-create",
      prompt: "tea",
      ratio: "1:1",
      quality: "standard",
      idempotencyKey: "request-12345678",
    });
    expect(apiClient.post).toHaveBeenCalledWith(
      "/ai/tasks",
      {
        type: "generate",
        modelCode: "wanx-create",
        prompt: "tea",
        ratio: "1:1",
        quality: "standard",
      },
      { headers: { "Idempotency-Key": "request-12345678" } },
    );
  });

  it("loads the real server task history", async () => {
    vi.mocked(apiClient.get).mockResolvedValue({
      data: {
        code: 0,
        data: { items: [], page: { page: 1, pageSize: 50, total: 0, totalPages: 0 } },
        message: "ok",
        requestId: "r1",
      },
    });
    await aiApi.tasks("failed");
    expect(apiClient.get).toHaveBeenCalledWith("/ai/tasks?page=1&pageSize=50&status=failed");
  });

  it("uses safe output defaults for models returned by an older backend", async () => {
    vi.mocked(apiClient.get).mockResolvedValue({
      data: {
        code: 0,
        data: [
          {
            id: "1",
            code: "openai-image",
            name: "OpenAI Images",
            capabilities: ["generate"],
            ratios: ["1:1"],
            qualities: ["standard"],
            supportsReference: false,
            quotaCost: 1,
            enabled: true,
          },
        ],
        message: "ok",
        requestId: "r1",
      },
    });

    await expect(aiApi.models()).resolves.toEqual([
      expect.objectContaining({
        backgrounds: ["auto"],
        outputFormats: ["png"],
        supportsOutputCompression: false,
      }),
    ]);
  });
});
