import * as Y from "yjs";
import { normalizeEditorDocument } from "@/features/editor/model/document";
import type { AdjustmentKey, EditorDocument, EditorLayer, SerializablePath } from "@/features/editor/model/types";

export const LOCAL_ORIGIN = "teacup-local";
export const REMOTE_ORIGIN = "teacup-remote";

type YMap = Y.Map<unknown>;

const adjustmentKeys: AdjustmentKey[] = [
  "exposure", "brightness", "contrast", "highlights", "shadows", "saturation", "vibrance",
  "temperature", "tint", "sharpness", "fade", "vignette", "enhance", "dehaze",
];

export function createCollaborativeDocument(initial: EditorDocument): Y.Doc {
  const doc = new Y.Doc();
  writeEditorDocument(doc, initial, LOCAL_ORIGIN);
  return doc;
}

export function writeEditorDocument(doc: Y.Doc, value: EditorDocument, origin: unknown = LOCAL_ORIGIN): void {
  const normalized = normalizeEditorDocument(value);
  doc.transact(() => {
    const metadata = doc.getMap("metadata");
    metadata.set("schemaVersion", 3);
    const canvas = ensureMap(doc, "canvas");
    canvas.set("width", normalized.canvas.width);
    canvas.set("height", normalized.canvas.height);

    const transform = ensureMap(doc, "transform");
    for (const key of ["rotation", "scale", "flipX", "flipY"] as const) transform.set(key, normalized.transform[key]);

    const crop = ensureMap(doc, "crop");
    crop.set("value", normalized.crop);

    const adjustments = ensureMap(doc, "adjustments");
    for (const key of adjustmentKeys) adjustments.set(key, normalized.adjustments[key]);

    const layers = ensureMap(doc, "layers");
    const known = new Set(normalized.layers.map((layer) => layer.id));
    layers.forEach((_value, key) => { if (!known.has(key)) layers.delete(key); });
    for (const layer of normalized.layers) writeLayer(layers, layer);

    const order = doc.getArray<string>("layerOrder");
    order.delete(0, order.length);
    order.insert(0, normalized.layers.map((layer) => layer.id));
  }, origin);
}

/** Apply only changed fields so concurrent edits to different layers do not overwrite a stale snapshot. */
export function patchEditorDocument(doc: Y.Doc, next: EditorDocument, origin: unknown = LOCAL_ORIGIN): void {
  const current = readEditorDocument(doc);
  const normalized = normalizeEditorDocument(next);
  doc.transact(() => {
    const canvas = ensureMap(doc, "canvas");
    setIfChanged(canvas, "width", normalized.canvas.width, current.canvas.width);
    setIfChanged(canvas, "height", normalized.canvas.height, current.canvas.height);

    const transform = ensureMap(doc, "transform");
    for (const key of ["rotation", "scale", "flipX", "flipY"] as const) setIfChanged(transform, key, normalized.transform[key], current.transform[key]);

    const crop = ensureMap(doc, "crop");
    if (JSON.stringify(current.crop) !== JSON.stringify(normalized.crop)) crop.set("value", normalized.crop);

    const adjustments = ensureMap(doc, "adjustments");
    for (const key of adjustmentKeys) setIfChanged(adjustments, key, normalized.adjustments[key], current.adjustments[key]);

    const currentById = new Map(current.layers.map((layer) => [layer.id, layer]));
    const layers = ensureMap(doc, "layers");
    const nextIds = new Set(normalized.layers.map((layer) => layer.id));
    layers.forEach((_value, key) => { if (!nextIds.has(key)) layers.delete(key); });
    for (const layer of normalized.layers) patchLayer(layers, layer, currentById.get(layer.id));

    if (JSON.stringify(current.layers.map((layer) => layer.id)) !== JSON.stringify(normalized.layers.map((layer) => layer.id))) {
      const order = doc.getArray<string>("layerOrder");
      order.delete(0, order.length);
      order.insert(0, normalized.layers.map((layer) => layer.id));
    }
  }, origin);
}

export function readEditorDocument(doc: Y.Doc): EditorDocument {
  const canvas = doc.getMap("canvas");
  const transform = doc.getMap("transform");
  const crop = doc.getMap("crop");
  const adjustments = doc.getMap("adjustments");
  const layers = doc.getMap<YMap>("layers");
  const order = doc.getArray<string>("layerOrder").toArray();
  const values = new Map<string, EditorLayer>();
  layers.forEach((value, key) => values.set(key, readLayer(value)));
  const orderedLayers = order.map((id) => values.get(id)).filter((layer): layer is EditorLayer => Boolean(layer));
  values.forEach((layer, id) => { if (!order.includes(id)) orderedLayers.push(layer); });
  return normalizeEditorDocument({
    schemaVersion: 3,
    canvas: { width: numberValue(canvas.get("width"), 1), height: numberValue(canvas.get("height"), 1) },
    transform: {
      rotation: numberValue(transform.get("rotation"), 0), scale: numberValue(transform.get("scale"), 1),
      flipX: Boolean(transform.get("flipX")), flipY: Boolean(transform.get("flipY")),
    },
    crop: (crop.get("value") as EditorDocument["crop"] | null | undefined) ?? null,
    adjustments: Object.fromEntries(adjustmentKeys.map((key) => [key, numberValue(adjustments.get(key), 0)])),
    layers: orderedLayers,
  });
}

function writeLayer(layers: YMap, layer: EditorLayer): void {
  const target = layers.get(layer.id) instanceof Y.Map ? layers.get(layer.id) as YMap : new Y.Map<unknown>();
  layers.set(layer.id, target);
  target.set("id", layer.id);
  target.set("type", layer.type);
  target.set("left", layer.left); target.set("top", layer.top);
  target.set("scaleX", layer.scaleX); target.set("scaleY", layer.scaleY);
  target.set("flipX", layer.flipX); target.set("flipY", layer.flipY); target.set("angle", layer.angle);
  if (layer.type === "text") {
    const text = target.get("text") instanceof Y.Text ? target.get("text") as Y.Text : new Y.Text();
    target.set("text", text);
    if (text.toString() !== layer.text) { text.delete(0, text.length); text.insert(0, layer.text); }
    const style = target.get("style") instanceof Y.Map ? target.get("style") as YMap : new Y.Map<unknown>();
    target.set("style", style);
    style.set("fontSize", layer.fontSize); style.set("width", layer.width); style.set("color", layer.color);
    style.set("fontFamily", layer.fontFamily); style.set("fontWeight", layer.fontWeight);
  } else {
    target.set("tool", layer.tool); target.set("color", layer.color); target.set("size", layer.size); target.set("opacity", layer.opacity);
    const path = target.get("path") instanceof Y.Array ? target.get("path") as Y.Array<unknown> : new Y.Array<unknown>();
    target.set("path", path);
    path.delete(0, path.length); path.insert(0, layer.path as unknown[]);
  }
}

function patchLayer(layers: YMap, layer: EditorLayer, previous?: EditorLayer): void {
  if (!previous || previous.type !== layer.type) {
    writeLayer(layers, layer);
    return;
  }
  const target = layers.get(layer.id) as YMap;
  for (const key of ["left", "top", "scaleX", "scaleY", "flipX", "flipY", "angle"] as const) {
    setIfChanged(target, key, layer[key], previous[key]);
  }
  if (layer.type === "text" && previous.type === "text") {
    const text = target.get("text") as Y.Text;
    patchYText(text, previous.text, layer.text);
    const style = target.get("style") as YMap;
    for (const key of ["fontSize", "width", "color", "fontFamily", "fontWeight"] as const) setIfChanged(style, key, layer[key], previous[key]);
  } else if (layer.type === "drawing" && previous.type === "drawing") {
    for (const key of ["tool", "color", "size", "opacity"] as const) setIfChanged(target, key, layer[key], previous[key]);
    if (JSON.stringify(previous.path) !== JSON.stringify(layer.path)) patchYPath(target.get("path") as Y.Array<unknown>, previous.path, layer.path);
  }
}

function patchYText(text: Y.Text, previous: string, next: string): void {
  let prefix = 0;
  while (prefix < previous.length && prefix < next.length && previous[prefix] === next[prefix]) prefix += 1;
  let previousEnd = previous.length;
  let nextEnd = next.length;
  while (previousEnd > prefix && nextEnd > prefix && previous[previousEnd - 1] === next[nextEnd - 1]) {
    previousEnd -= 1;
    nextEnd -= 1;
  }
  if (previousEnd > prefix) text.delete(prefix, previousEnd - prefix);
  if (nextEnd > prefix) text.insert(prefix, next.slice(prefix, nextEnd));
}

function patchYPath(path: Y.Array<unknown>, previous: SerializablePath, next: SerializablePath): void {
  let prefix = 0;
  while (prefix < previous.length && prefix < next.length && JSON.stringify(previous[prefix]) === JSON.stringify(next[prefix])) prefix += 1;
  if (prefix === previous.length && next.length >= previous.length) {
    if (next.length > prefix) path.insert(prefix, next.slice(prefix) as unknown[]);
    return;
  }
  path.delete(0, path.length);
  path.insert(0, next as unknown[]);
}

function setIfChanged(target: YMap, key: string, next: unknown, previous: unknown): void {
  if (next !== previous) target.set(key, next);
}

function readLayer(value: YMap): EditorLayer {
  const common = {
    id: String(value.get("id") ?? "layer-invalid"), left: numberValue(value.get("left"), 0), top: numberValue(value.get("top"), 0),
    scaleX: numberValue(value.get("scaleX"), 1), scaleY: numberValue(value.get("scaleY"), 1), flipX: Boolean(value.get("flipX")),
    flipY: Boolean(value.get("flipY")), angle: numberValue(value.get("angle"), 0),
  };
  if (value.get("type") === "text") {
    const style = value.get("style") instanceof Y.Map ? value.get("style") as YMap : new Y.Map<unknown>();
    const text = value.get("text") instanceof Y.Text ? (value.get("text") as Y.Text).toString() : String(value.get("text") ?? "");
    return { ...common, type: "text", text, fontSize: numberValue(style.get("fontSize"), 32), width: numberValue(style.get("width"), 120),
      color: String(style.get("color") ?? "#ffffff"), fontFamily: String(style.get("fontFamily") ?? "Inter, sans-serif"),
      fontWeight: String(style.get("fontWeight") ?? "600") };
  }
  const path = value.get("path") instanceof Y.Array ? (value.get("path") as Y.Array<unknown>).toArray() : [];
  const rawTool = value.get("tool");
  const tool = rawTool === "marker" || rawTool === "eraser" ? rawTool : "pen";
  return { ...common, type: "drawing", tool,
    color: String(value.get("color") ?? "#3370ff"), size: numberValue(value.get("size"), 4), opacity: numberValue(value.get("opacity"), 1),
    path: path as SerializablePath };
}

function ensureMap(doc: Y.Doc, name: string): YMap {
  return doc.getMap(name);
}

function numberValue(value: unknown, fallback: number): number {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}
