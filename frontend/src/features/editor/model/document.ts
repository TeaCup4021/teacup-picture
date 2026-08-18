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

export const MIN_LAYER_SCALE = 0.01;
export const MAX_LAYER_SCALE = 100;

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
    schemaVersion: 3,
    canvas: {
      width: Math.max(1, Math.round(width)),
      height: Math.max(1, Math.round(height)),
    },
    transform: { rotation: 0, scale: 1, flipX: false, flipY: false },
    crop: null,
    adjustments: createDefaultAdjustments(),
    layers: [],
  };
}

export function cloneDocument(document: EditorDocument): EditorDocument {
  return JSON.parse(JSON.stringify(document)) as EditorDocument;
}

export function normalizeEditorDocument(value: unknown): EditorDocument {
  const source = value as Partial<EditorDocument> & Record<string, unknown>;
  const canvasSource = source.canvas as Partial<EditorDocument["canvas"]> | undefined;
  const width = clampInteger(canvasSource?.width, 1, 32_768, 1);
  const height = clampInteger(canvasSource?.height, 1, 32_768, 1);
  const transformSource = source.transform as Partial<EditorDocument["transform"]> | undefined;
  const adjustmentsSource = source.adjustments as Partial<EditorAdjustments> | undefined;
  const layersSource = Array.isArray(source.layers) ? source.layers : [];

  const adjustments = createDefaultAdjustments();
  for (const key of ADJUSTMENT_KEYS) {
    adjustments[key] = clamp(
      numberOr(adjustmentsSource?.[key], 0),
      adjustmentRange(key).min,
      adjustmentRange(key).max,
    );
  }

  return {
    schemaVersion: 3,
    canvas: { width, height },
    transform: {
      rotation: clamp(numberOr(transformSource?.rotation, 0), -360, 360),
      scale: clamp(numberOr(transformSource?.scale, 1), 0.25, 4),
      flipX: Boolean(transformSource?.flipX),
      flipY: Boolean(transformSource?.flipY),
    },
    crop: source.crop ? normalizeCrop(source.crop, width, height) : null,
    adjustments,
    layers: layersSource.flatMap((layer) => normalizeLayer(layer)),
  };
}

export function addLayer(document: EditorDocument, layer: EditorLayer): EditorDocument {
  return { ...document, layers: [...document.layers, layer] };
}

export function updateLayer(
  document: EditorDocument,
  id: string,
  patch: Partial<EditorLayer>,
): EditorDocument {
  return {
    ...document,
    layers: document.layers.map((layer) =>
      layer.id === id ? ({ ...layer, ...patch } as EditorLayer) : layer,
    ),
  };
}

export function removeLayer(document: EditorDocument, id: string): EditorDocument {
  return { ...document, layers: document.layers.filter((layer) => layer.id !== id) };
}

export function setAdjustment(
  document: EditorDocument,
  key: AdjustmentKey,
  value: number,
): EditorDocument {
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

export function flipDocument(
  document: EditorDocument,
  axis: "horizontal" | "vertical",
): EditorDocument {
  return {
    ...document,
    transform: {
      ...document.transform,
      [axis === "horizontal" ? "flipX" : "flipY"]:
        !document.transform[axis === "horizontal" ? "flipX" : "flipY"],
    },
  };
}

export function setCrop(document: EditorDocument, crop: CropRect | null): EditorDocument {
  return {
    ...document,
    crop: crop ? normalizeCrop(crop, document.canvas.width, document.canvas.height) : null,
  };
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

function normalizeLayer(value: unknown): EditorLayer[] {
  if (!value || typeof value !== "object") return [];
  const layer = value as Partial<EditorLayer> & Record<string, unknown>;
  if (layer.type !== "text" && layer.type !== "drawing") return [];
  const rawScaleX = numberOr(layer.scaleX, 1);
  const rawScaleY = numberOr(layer.scaleY, 1);
  const common = {
    id: typeof layer.id === "string" && layer.id ? layer.id : createLayerId(),
    left: clamp(numberOr(layer.left, 0), -1_000_000, 1_000_000),
    top: clamp(numberOr(layer.top, 0), -1_000_000, 1_000_000),
    scaleX: clamp(Math.abs(rawScaleX), MIN_LAYER_SCALE, MAX_LAYER_SCALE),
    scaleY: clamp(Math.abs(rawScaleY), MIN_LAYER_SCALE, MAX_LAYER_SCALE),
    flipX: Boolean(layer.flipX) !== rawScaleX < 0,
    flipY: Boolean(layer.flipY) !== rawScaleY < 0,
    angle: clamp(numberOr(layer.angle, 0), -36_000, 36_000),
  };
  if (layer.type === "text") {
    const text = typeof layer.text === "string" ? layer.text.slice(0, 2_000) : "";
    const fontSize = clamp(numberOr(layer.fontSize, 32), 8, 512);
    return [
      {
        ...common,
        type: "text",
        text,
        fontSize,
        width: clamp(numberOr(layer.width, estimateTextWidth(text, fontSize)), 1, 32_768),
        color: normalizeColor(layer.color, "#ffffff"),
        fontFamily:
          typeof layer.fontFamily === "string" && layer.fontFamily
            ? layer.fontFamily.slice(0, 128)
            : "Inter, PingFang SC, Microsoft YaHei, sans-serif",
        fontWeight:
          typeof layer.fontWeight === "string" && layer.fontWeight
            ? layer.fontWeight.slice(0, 32)
            : "600",
      },
    ];
  }
  const tool = layer.tool === "marker" || layer.tool === "eraser" ? layer.tool : "pen";
  return [
    {
      ...common,
      type: "drawing",
      tool,
      color: normalizeColor(layer.color, "#3370ff"),
      size: clamp(numberOr(layer.size, 4), 1, 100),
      opacity: clamp(numberOr(layer.opacity, tool === "marker" ? 0.35 : 1), 0, 1),
      path: Array.isArray(layer.path) ? layer.path : [],
    },
  ];
}

function estimateTextWidth(text: string, fontSize: number): number {
  return Math.max(120, Math.min(32_768, Array.from(text || "输入文字").length * fontSize * 0.62));
}

function numberOr(value: unknown, fallback: number): number {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

function clampInteger(value: unknown, min: number, max: number, fallback: number): number {
  return Math.round(clamp(numberOr(value, fallback), min, max));
}

function normalizeColor(value: unknown, fallback: string): string {
  return typeof value === "string" && /^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$/.test(value)
    ? value
    : fallback;
}

function createLayerId(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) return crypto.randomUUID();
  return `layer-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

// Transitional aliases while individual UI modules move to layer terminology.
export const addObject = addLayer;
export const updateObject = updateLayer;
export const removeObject = removeLayer;
