import type {
  AdjustmentKey,
  CropRect,
  EditorAdjustments,
  EditorDocument,
  EditorLayer,
} from "@/features/editor/model/types";

export const ADJUSTMENT_KEYS: AdjustmentKey[] = [
  "exposure",
  "brightness",
  "contrast",
  "highlights",
  "shadows",
  "saturation",
  "vibrance",
  "temperature",
  "tint",
  "sharpness",
  "fade",
  "vignette",
  "enhance",
  "dehaze",
];

export function createDefaultAdjustments(): EditorAdjustments {
  return {
    exposure: 0,
    brightness: 0,
    contrast: 0,
    highlights: 0,
    shadows: 0,
    saturation: 0,
    vibrance: 0,
    temperature: 0,
    tint: 0,
    sharpness: 0,
    fade: 0,
    vignette: 0,
    enhance: 0,
    dehaze: 0,
  };
}

export function createEmptyDocument(width: number, height: number): EditorDocument {
  return {
    schemaVersion: 2,
    canvas: {
      width: Math.max(1, Math.round(width)),
      height: Math.max(1, Math.round(height)),
    },
    transform: { rotation: 0, scale: 1 },
    crop: null,
    adjustments: createDefaultAdjustments(),
    layers: [],
  };
}

export function cloneDocument(document: EditorDocument): EditorDocument {
  return JSON.parse(JSON.stringify(document)) as EditorDocument;
}

export function addLayer(document: EditorDocument, layer: EditorLayer): EditorDocument {
  return { ...document, layers: [...document.layers, layer] };
}

export function updateLayer(document: EditorDocument, id: string, patch: Partial<EditorLayer>): EditorDocument {
  return {
    ...document,
    layers: document.layers.map((layer) => (layer.id === id ? ({ ...layer, ...patch } as EditorLayer) : layer)),
  };
}

export function removeLayer(document: EditorDocument, id: string): EditorDocument {
  return { ...document, layers: document.layers.filter((layer) => layer.id !== id) };
}

export function setAdjustment(document: EditorDocument, key: AdjustmentKey, value: number): EditorDocument {
  return {
    ...document,
    adjustments: {
      ...document.adjustments,
      [key]: clamp(value, adjustmentRange(key).min, adjustmentRange(key).max),
    },
  };
}

export function rotateDocument(document: EditorDocument, deltaDegrees: number): EditorDocument {
  return {
    ...document,
    transform: {
      ...document.transform,
      rotation: (document.transform.rotation + deltaDegrees + 360) % 360,
    },
  };
}

export function scaleDocument(document: EditorDocument, nextScale: number): EditorDocument {
  return {
    ...document,
    transform: { ...document.transform, scale: clamp(nextScale, 0.25, 4) },
  };
}

export function setCrop(document: EditorDocument, crop: CropRect | null): EditorDocument {
  return { ...document, crop: crop ? normalizeCrop(crop, document.canvas.width, document.canvas.height) : null };
}

export function normalizeCrop(crop: CropRect, width: number, height: number): CropRect {
  const x = clamp(Math.round(crop.x), 0, Math.max(0, width - 1));
  const y = clamp(Math.round(crop.y), 0, Math.max(0, height - 1));
  const maxWidth = Math.max(1, width - x);
  const maxHeight = Math.max(1, height - y);
  return {
    x,
    y,
    width: clamp(Math.round(crop.width), 1, maxWidth),
    height: clamp(Math.round(crop.height), 1, maxHeight),
  };
}

export function adjustmentRange(key: AdjustmentKey): { min: number; max: number } {
  if (key === "fade" || key === "enhance" || key === "dehaze") return { min: 0, max: 100 };
  return { min: -100, max: 100 };
}

export function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, Number.isFinite(value) ? value : 0));
}

// Transitional aliases while individual UI modules move to layer terminology.
export const addObject = addLayer;
export const updateObject = updateLayer;
export const removeObject = removeLayer;
