import { describe, expect, it } from "vitest";
import { normalizeAnnotationPosition } from "./annotation-position";

describe("normalizeAnnotationPosition", () => {
  it("stores the point relative to the image content box", () => {
    expect(normalizeAnnotationPosition(610, 421, {
      left: 100,
      top: 20,
      width: 1020,
      height: 802,
      border: 10,
    })).toEqual({ x: 0.5, y: 0.5 });
  });

  it("clamps clicks outside the image to normalized bounds", () => {
    expect(normalizeAnnotationPosition(-10, 900, {
      left: 0,
      top: 0,
      width: 400,
      height: 300,
      border: 0,
    })).toEqual({ x: 0, y: 1 });
  });
});
