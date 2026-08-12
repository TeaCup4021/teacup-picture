import { beforeEach, describe, expect, it } from "vitest";
import { prototypeApi } from "@/features/prototype/api/mock-api";

describe("prototype review workflow", () => {
  beforeEach(() => {
    prototypeApi.reset();
  });

  it("moves an uploaded picture from personal space into the public gallery", async () => {
    const user = await prototypeApi.login({ account: "muyi", password: "demo123" });
    expect(user.role).toBe("user");

    const uploaded = await prototypeApi.uploadPicture({
      title: "流程测试图片",
      description: "用于验证原型审核状态机",
      imageUrl: "/mock-images/gallery-06.jpg",
      width: 1200,
      height: 800,
      category: "静物",
      tags: ["测试"],
    });
    expect(uploaded.publishStatus).toBe("not_requested");

    const submitted = await prototypeApi.submitReview(uploaded.id);
    expect(submitted.publishStatus).toBe("pending");

    await prototypeApi.logout();
    await prototypeApi.login({ account: "admin", password: "admin123" });
    expect(
      (await prototypeApi.getPendingReviews()).some((picture) => picture.id === uploaded.id),
    ).toBe(true);

    const approved = await prototypeApi.decideReview({
      pictureId: uploaded.id,
      decision: "approve",
    });
    expect(approved.publishStatus).toBe("approved");
    expect(
      (await prototypeApi.getPublicPictures()).some((picture) => picture.id === uploaded.id),
    ).toBe(true);
  });

  it("rejects invalid credentials", async () => {
    await expect(prototypeApi.login({ account: "muyi", password: "wrong" })).rejects.toThrow(
      "账号或密码错误",
    );
  });
});
