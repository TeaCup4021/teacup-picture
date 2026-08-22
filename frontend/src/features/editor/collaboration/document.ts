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
  target.set("id", layer.id);
  target.set("type", layer.type);
  target.set("left", layer.left); target.set("top", layer.top);
  target.set("scaleX", layer.scaleX); target.set("scaleY", layer.scaleY);
  target.set("flipX", layer.flipX); target.set("flipY", layer.flipY); target.set("angle", layer.angle);
  if (layer.type === "text") {
    const text = target.get("text") instanceof Y.Text ? target.get("text") as Y.Text : new Y.Text();
    if (text.toString() !== layer.text) { text.delete(0, text.length); text.insert(0, layer.text); }
    target.set("text", text);
    const style = target.get("style") instanceof Y.Map ? target.get("style") as YMap : new Y.Map<unknown>();
    style.set("fontSize", layer.fontSize); style.set("width", layer.width); style.set("color", layer.color);
    style.set("fontFamily", layer.fontFamily); style.set("fontWeight", layer.fontWeight);
    target.set("style", style);
  } else {
    target.set("tool", layer.tool); target.set("color", layer.color); target.set("size", layer.size); target.set("opacity", layer.opacity);
    const path = target.get("path") instanceof Y.Array ? target.get("path") as Y.Array<unknown> : new Y.Array<unknown>();
    path.delete(0, path.length); path.insert(0, layer.path as unknown[]);
    target.set("path", path);
  }
  layers.set(layer.id, target);
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
  return { ...common, type: "drawing", tool: value.get("tool") === "marker" || value.get("tool") === "eraser" ? value.get("tool") : "pen",
    color: String(value.get("color") ?? "#3370ff"), size: numberValue(value.get("size"), 4), opacity: numberValue(value.get("opacity"), 1),
    path: path as SerializablePath };
}

function ensureMap(doc: Y.Doc, name: string): YMap {
  return doc.getMap(name);
}

function numberValue(value: unknown, fallback: number): number {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}
