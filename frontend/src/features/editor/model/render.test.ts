import { describe, expect, it } from "vitest";
import { createDefaultAdjustments } from "@/features/editor/model/document";
import { applyColorAdjustments } from "@/features/editor/model/render";
import type { AdjustmentKey } from "@/features/editor/model/types";

function adjustedPixel(key: AdjustmentKey, value: number, source: number[]): number[] {
  const data = new Uint8ClampedArray([...source, 255]);
  applyColorAdjustments(data, { ...createDefaultAdjustments(), [key]: value });
  return Array.from(data.slice(0, 3));
}

describe("editor color pipeline", () => {
  it.each<AdjustmentKey>([
    "exposure",
    "brightness",
    "contrast",
    "saturation",
    "vibrance",
    "temperature",
    "tint",
    "fade",
    "enhance",
    "dehaze",
  ])("applies %s instead of keeping a no-op control", (key) => {
    const source = [72, 126, 188];
    expect(adjustedPixel(key, 60, source)).not.toEqual(source);
  });

  it("targets highlights and shadows at opposite luminance ranges", () => {
    const highlight = [224, 214, 204];
    const shadow = [28, 38, 48];
    expect(adjustedPixel("highlights", 60, highlight)).not.toEqual(highlight);
    expect(adjustedPixel("shadows", 60, shadow)).not.toEqual(shadow);
    expect(adjustedPixel("highlights", 60, shadow)).toEqual(shadow);
    expect(adjustedPixel("shadows", 60, highlight)).toEqual(highlight);
  });
});
