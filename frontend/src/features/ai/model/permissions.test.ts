import { describe, expect, it } from "vitest";
import { canUseAi } from "@/features/ai/model/permissions";

describe("canUseAi", () => {
  it.each(["user", "admin"] as const)("allows the %s role", (role) => {
    expect(canUseAi(role)).toBe(true);
  });

  it.each([undefined, null])("rejects an unauthenticated session (%s)", (role) => {
    expect(canUseAi(role)).toBe(false);
  });
});
