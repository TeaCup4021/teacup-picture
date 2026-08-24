import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiClient } from "@/api/client";
import { absoluteApiUrl, interactionsApi } from "./api";

vi.mock("@/api/client", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/api/client")>();
  return {
    ...original,
    apiClient: {
      defaults: { baseURL: "http://localhost:8123/api/v1" },
      request: vi.fn(),
      get: vi.fn(),
    },
  };
});

const envelope = <T,>(data: T) => ({ data: { code: 0, data, message: "", requestId: "req-m6" } });

describe("M6 interactions API", () => {
  beforeEach(() => vi.clearAllMocks());

  it("resolves both API-prefixed resource paths and endpoint-relative paths", () => {
    expect(absoluteApiUrl("/api/v1/public/shares/demo/content")).toBe(
      "http://localhost:8123/api/v1/public/shares/demo/content",
    );
    expect(absoluteApiUrl("/public/shares/demo/content")).toBe(
      "http://localhost:8123/api/v1/public/shares/demo/content",
    );
  });

  it("submits normalized annotation data and structured mentions", async () => {
    vi.mocked(apiClient.request).mockResolvedValue(envelope({ id: "91" }));

    await interactionsApi.createComment({
      pictureId: "31",
      kind: "annotation",
      body: "杯把高光过强",
      pictureVersionId: "51",
      x: 0.75,
      y: 0.4,
      mentionedUserIds: ["11", "12"],
    });

    expect(apiClient.request).toHaveBeenCalledWith({
      method: "post",
      url: "/pictures/31/comments",
      data: {
        kind: "annotation",
        body: "杯把高光过强",
        pictureVersionId: "51",
        x: 0.75,
        y: 0.4,
        mentionedUserIds: ["11", "12"],
      },
    });
  });

  it("sends the fragment secret only in the share access body", async () => {
    vi.mocked(apiClient.request).mockResolvedValue(envelope({ granted: true }));

    await interactionsApi.accessShare("public-id", "fragment-secret", "review-123");

    expect(apiClient.request).toHaveBeenCalledWith({
      method: "post",
      url: "/public/shares/public-id/access",
      data: { secret: "fragment-secret", password: "review-123" },
    });
  });
});
