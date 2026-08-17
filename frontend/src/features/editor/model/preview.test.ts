import { describe, expect, it } from "vitest";
import { calculateEditorPreviewScale } from "@/features/editor/model/preview";

describe("editor preview sizing", () => {
  it("keeps small images at source resolution", () => {
    expect(calculateEditorPreviewScale(400, 300, 800, 600)).toBe(1);
  });

  it("limits preview work to the visible workspace", () => {
    const scale = calculateEditorPreviewScale(4000, 3000, 800, 600);
    expect(4000 * 3000 * scale * scale).toBeCloseTo(720_000);
    expect(4000 * scale).toBeLessThanOrEqual(1000);
    expect(3000 * scale).toBeLessThanOrEqual(750);
  });

  it("enforces a pixel budget without stage dimensions", () => {
    const scale = calculateEditorPreviewScale(6000, 4000);
    expect(6000 * 4000 * scale * scale).toBeCloseTo(720_000);
  });
});
