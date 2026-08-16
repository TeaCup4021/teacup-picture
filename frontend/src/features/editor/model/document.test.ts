import { describe, expect, it } from "vitest";
import {
  ADJUSTMENT_KEYS,
  addObject,
  adjustmentRange,
  createEmptyDocument,
  normalizeCrop,
  removeObject,
  rotateDocument,
  scaleDocument,
  setAdjustment,
  updateObject,
} from "@/features/editor/model/document";

describe("editor document model", () => {
  it("creates an empty document with default adjustments", () => {
    const document = createEmptyDocument(800, 600);

    expect(document.canvas.width).toBe(800);
    expect(document.canvas.height).toBe(600);
    expect(document.layers).toEqual([]);
    expect(document.adjustments.brightness).toBe(0);
    expect(document.schemaVersion).toBe(2);
  });

  it("adds, updates and removes objects without mutating the original", () => {
    const base = createEmptyDocument(800, 600);
    const drawing = addObject(base, {
      id: "stroke-1",
      type: "drawing",
      tool: "pen",
      color: "#ff0000",
      size: 4,
      opacity: 1,
      path: [["M", 0, 0], ["L", 1, 2]],
      left: 0,
      top: 0,
      scaleX: 1,
      scaleY: 1,
      angle: 0,
    });

    expect(drawing.layers).toHaveLength(1);
    expect(base.layers).toHaveLength(0);

    const updated = updateObject(drawing, "stroke-1", { color: "#00ff00" });
    expect(updated.layers[0]).toMatchObject({ color: "#00ff00" });
    expect(drawing.layers[0]).toMatchObject({ color: "#ff0000" });

    const removed = removeObject(updated, "stroke-1");
    expect(removed.layers).toHaveLength(0);
  });

  it("clamps adjustments and scales", () => {
    const document = createEmptyDocument(800, 600);
    expect(setAdjustment(document, "brightness", 999).adjustments.brightness).toBe(100);
    expect(scaleDocument(document, 0.1).transform.scale).toBe(0.25);
    expect(scaleDocument(document, 10).transform.scale).toBe(4);
  });

  it("normalizes crop rectangles to the source canvas", () => {
    expect(normalizeCrop({ x: -20, y: 590, width: 900, height: 50 }, 800, 600)).toEqual({
      x: 0,
      y: 590,
      width: 800,
      height: 10,
    });
    expect(normalizeCrop({ x: 799.7, y: 599.8, width: 0, height: -4 }, 800, 600)).toEqual({
      x: 799,
      y: 599,
      width: 1,
      height: 1,
    });
  });

  it("defines and enforces every PRD adjustment range", () => {
    const document = createEmptyDocument(800, 600);
    expect(ADJUSTMENT_KEYS).toHaveLength(14);
    for (const key of ADJUSTMENT_KEYS) {
      const range = adjustmentRange(key);
      expect(setAdjustment(document, key, range.min - 1).adjustments[key]).toBe(range.min);
      expect(setAdjustment(document, key, range.max + 1).adjustments[key]).toBe(range.max);
    }
  });

  it("rotates within 360 degrees", () => {
    const document = createEmptyDocument(800, 600);
    expect(rotateDocument(document, 270).transform.rotation).toBe(270);
    expect(rotateDocument(rotateDocument(document, 180), 180).transform.rotation).toBe(0);
  });
});
