import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiClient } from "@/api/client";
import { m1Api } from "@/features/prototype/api/m1-api";

vi.mock("@/api/client", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/api/client")>();
  return { ...original, apiClient: { get: vi.fn(), post: vi.fn() } };
});

const envelope = <T,>(data: T) => ({ data: { code: 0, data, message: "", requestId: "req-1" } });

describe("M1 API adapter", () => {
  beforeEach(() => vi.clearAllMocks());

  it("maps public contract pictures to the existing view model", async () => {
    vi.mocked(apiClient.get).mockResolvedValue(envelope({ items: [{
      id: "31", thumbnailUrl: "http://asset/31.png", name: "流程图片", tags: ["测试"],
      width: 1200, height: 800, author: { id: "11", name: "测试用户" },
      publishedAt: "2026-08-12T08:00:00Z",
    }], hasMore: false, nextCursor: null }));

    const pictures = await m1Api.getPublicPictures();
    expect(apiClient.get).toHaveBeenCalledWith("/public/pictures?limit=50");
    expect(pictures[0]).toMatchObject({ id: "31", title: "流程图片", publishStatus: "approved" });
  });

  it("submits a publish request through the v1 resource path", async () => {
    vi.mocked(apiClient.post).mockResolvedValue(envelope({
      id: "41",
      picture: { id: "31", spaceId: "21", thumbnailUrl: "http://asset/31.png", name: "流程图片",
        tags: [], width: 10, height: 10, publishStatus: "pending", author: { id: "11", name: "测试用户" } },
    }));

    const submitted = await m1Api.submitReview("31");
    expect(apiClient.post).toHaveBeenCalledWith("/pictures/31/publish-requests", undefined);
    expect(submitted).toMatchObject({ id: "31", publishStatus: "pending", reviewRequestId: "41" });
  });
});
