import { describe, expect, it } from "vitest";
import { ApiError, unwrapApiResponse } from "./client";

describe("unwrapApiResponse", () => {
  it("returns data for a successful envelope", () => {
    expect(
      unwrapApiResponse({ code: 0, data: { id: "123" }, message: "ok", requestId: "req-1" }),
    ).toEqual({ id: "123" });
  });

  it("throws a typed error for a business failure", () => {
    expect(() =>
      unwrapApiResponse({ code: 40901, data: null, message: "状态冲突", requestId: "req-2" }),
    ).toThrow(ApiError);
  });
});
