"use client";

import { CheckOutlined, CloseOutlined } from "@ant-design/icons";
import { Button, Space, Tooltip } from "antd";
import { Canvas, FabricImage, IText, Path, PencilBrush, Rect } from "fabric";
import { useEffect, useRef, useState } from "react";
import { normalizeCrop } from "@/features/editor/model/document";
import { renderAdjustedImage } from "@/features/editor/model/render";
import type { CropRect, EditorDocument, EditorLayer, EditorTool, SerializablePath } from "@/features/editor/model/types";

interface EditorCanvasProps {
  document: EditorDocument;
  image: HTMLImageElement | null;
  tool: EditorTool;
  strokeColor: string;
  strokeSize: number;
  textColor: string;
  fontSize: number;
  selectedLayerId: string | null;
  onSelectLayer: (id: string | null) => void;
  onDocumentChange: (document: EditorDocument) => void;
  onCropApply: (crop: CropRect) => void;
  onCropCancel: () => void;
}

type FabricCanvasObject = {
  kind?: "base" | "layer" | "crop";
  layerId?: string;
  layerType?: "text" | "drawing";
  drawingTool?: "pen" | "marker" | "eraser";
};

export function EditorCanvas({
  document,
  image,
  tool,
  strokeColor,
  strokeSize,
  textColor,
  fontSize,
  selectedLayerId,
  onSelectLayer,
  onDocumentChange,
  onCropApply,
  onCropCancel,
}: EditorCanvasProps) {
  const elementRef = useRef<HTMLCanvasElement | null>(null);
  const stageRef = useRef<HTMLDivElement | null>(null);
  const fabricRef = useRef<Canvas | null>(null);
  const applyingRef = useRef(false);
  const toolRef = useRef(tool);
  const documentRef = useRef(document);
  const cropDraftRef = useRef<CropRect | null>(null);
  const onDocumentChangeRef = useRef(onDocumentChange);
  const onSelectLayerRef = useRef(onSelectLayer);
  const textColorRef = useRef(textColor);
  const fontSizeRef = useRef(fontSize);
  const [draftCrop, setDraftCrop] = useState<CropRect | null>(null);

  useEffect(() => {
    onDocumentChangeRef.current = onDocumentChange;
    onSelectLayerRef.current = onSelectLayer;
    documentRef.current = document;
    toolRef.current = tool;
    textColorRef.current = textColor;
    fontSizeRef.current = fontSize;
  }, [document, fontSize, onDocumentChange, onSelectLayer, textColor, tool]);

  useEffect(() => {
    const element = elementRef.current;
    if (!element) return;
    const canvas = new Canvas(element, {
      preserveObjectStacking: true,
      selection: true,
      stopContextMenu: true,
      fireRightClick: false,
    });
    fabricRef.current = canvas;
    let textCommitTimer: number | null = null;
    const resizeObserver = new ResizeObserver(() => fitCanvasToStage(canvas, stageRef.current));
    if (stageRef.current) resizeObserver.observe(stageRef.current);

    const emitDocument = () => {
      if (applyingRef.current) return;
      onDocumentChangeRef.current(serializeCanvas(canvas, documentRef.current, cropDraftRef.current));
    };

    const scheduleTextDocument = (delay: number) => {
      if (applyingRef.current) return;
      const next = serializeCanvas(canvas, documentRef.current, cropDraftRef.current);
      if (textCommitTimer !== null) window.clearTimeout(textCommitTimer);
      textCommitTimer = window.setTimeout(() => {
        textCommitTimer = null;
        onDocumentChangeRef.current(next);
      }, delay);
    };

    canvas.on("selection:created", ({ selected }) => {
      const object = selected?.[0] as (FabricCanvasObject & { layerId?: string }) | undefined;
      onSelectLayerRef.current(object?.layerId ?? null);
    });
    canvas.on("selection:updated", ({ selected }) => {
      const object = selected?.[0] as (FabricCanvasObject & { layerId?: string }) | undefined;
      onSelectLayerRef.current(object?.layerId ?? null);
    });
    canvas.on("selection:cleared", () => onSelectLayerRef.current(null));
    canvas.on("object:modified", (event) => {
      const object = event.target as FabricCanvasObject & { left?: number; top?: number; width?: number; height?: number; scaleX?: number; scaleY?: number };
      if (object.kind === "crop") {
        cropDraftRef.current = rectToCrop(object, documentRef.current);
        setDraftCrop(cropDraftRef.current);
        canvas.requestRenderAll();
        return;
      }
      emitDocument();
    });
    canvas.on("text:changed", () => scheduleTextDocument(400));
    canvas.on("text:editing:exited", () => scheduleTextDocument(0));
    canvas.on("path:created", (event) => {
      const path = event.path as FabricCanvasObject & { set: (key: string, value: unknown) => void };
      path.set("kind", "layer");
      path.set("layerType", "drawing");
      path.set("layerId", createObjectId());
      path.set("drawingTool", toolRef.current === "eraser" ? "eraser" : toolRef.current === "marker" ? "marker" : "pen");
      if (toolRef.current === "marker") path.set("opacity", 0.35);
      if (toolRef.current === "eraser") {
        (path as unknown as { globalCompositeOperation: string }).globalCompositeOperation = "destination-out";
      }
      emitDocument();
    });
    canvas.on("mouse:down", (event) => {
      if (toolRef.current !== "text") return;
      const target = event.target as FabricCanvasObject | undefined;
      if (target?.kind === "layer") return;
      const point = canvas.getScenePoint(event.e);
      const text = new IText("输入文字", {
        left: point.x,
        top: point.y,
        fill: textColorRef.current,
        fontSize: fontSizeRef.current,
        fontFamily: "Inter, PingFang SC, Microsoft YaHei, sans-serif",
        fontWeight: "600",
        padding: 8,
      });
      const metadata = text as unknown as FabricCanvasObject & { set: (key: string, value: unknown) => void };
      metadata.set("kind", "layer");
      metadata.set("layerType", "text");
      metadata.set("layerId", createObjectId());
      canvas.add(text);
      canvas.setActiveObject(text);
      text.enterEditing();
      text.selectAll();
    });

    return () => {
      if (textCommitTimer !== null) window.clearTimeout(textCommitTimer);
      resizeObserver.disconnect();
      fabricRef.current = null;
      void canvas.dispose();
    };
  }, []);

  useEffect(() => {
    const canvas = fabricRef.current;
    if (!canvas) return;
    applyingRef.current = true;
    try {
      rebuildCanvas(canvas, document, image, tool, cropDraftRef, stageRef.current);
      setDraftCrop(cropDraftRef.current);
    } finally {
      applyingRef.current = false;
    }
  }, [document, image, tool]);

  useEffect(() => {
    const canvas = fabricRef.current;
    if (!canvas) return;
    canvas.isDrawingMode = tool === "pen" || tool === "marker" || tool === "eraser";
    canvas.selection = tool === "select";
    if (canvas.isDrawingMode) {
      const brush = new PencilBrush(canvas);
      brush.width = strokeSize;
      brush.color = strokeColor;
      brush.decimate = 0.5;
      canvas.freeDrawingBrush = brush;
    }
    canvas.getObjects().forEach((object) => {
      const metadata = object as unknown as FabricCanvasObject;
      if (metadata.kind === "base" || metadata.kind === "crop") return;
      object.selectable = tool === "select";
      object.evented = tool === "select";
    });
    canvas.requestRenderAll();
  }, [tool, strokeColor, strokeSize]);

  useEffect(() => {
    const canvas = fabricRef.current;
    if (!canvas) return;
    if (!selectedLayerId) {
      canvas.discardActiveObject();
      canvas.requestRenderAll();
      return;
    }
    const object = canvas.getObjects().find((item) => (item as unknown as FabricCanvasObject).layerId === selectedLayerId);
    if (object) {
      canvas.setActiveObject(object);
      canvas.requestRenderAll();
    }
  }, [document, selectedLayerId]);

  const cropMode = tool === "crop";
  return (
    <div ref={stageRef} className="editor-canvas-stage">
      <canvas ref={elementRef} className="editor-canvas" aria-label="图片编辑器画布" />
      {!image ? <span className="editor-canvas-empty">正在加载原图…</span> : null}
      {cropMode && draftCrop ? (
        <div className="editor-crop-actions">
          <Space size={6}>
            <Tooltip title="取消裁切">
              <Button size="small" icon={<CloseOutlined />} onClick={onCropCancel} aria-label="取消裁切" />
            </Tooltip>
            <Button size="small" type="primary" icon={<CheckOutlined />} onClick={() => onCropApply(draftCrop)}>
              应用裁切
            </Button>
          </Space>
        </div>
      ) : null}
    </div>
  );
}

function rebuildCanvas(
  canvas: Canvas,
  document: EditorDocument,
  image: HTMLImageElement | null,
  tool: EditorTool,
  cropDraftRef: { current: CropRect | null },
  stage: HTMLDivElement | null,
): void {
  if (tool !== "crop") cropDraftRef.current = null;
  const crop = tool === "crop" ? null : document.crop;
  const viewCrop = crop ?? { x: 0, y: 0, width: document.canvas.width, height: document.canvas.height };
  const rotation = tool === "crop" ? 0 : document.transform.rotation;
  const zoom = tool === "crop" ? 1 : document.transform.scale;
  const rotationRadians = (rotation * Math.PI) / 180;
  const outputWidth = Math.max(1, Math.ceil((Math.abs(Math.cos(rotationRadians)) * viewCrop.width + Math.abs(Math.sin(rotationRadians)) * viewCrop.height) * zoom));
  const outputHeight = Math.max(1, Math.ceil((Math.abs(Math.sin(rotationRadians)) * viewCrop.width + Math.abs(Math.cos(rotationRadians)) * viewCrop.height) * zoom));
  canvas.clear();
  canvas.setDimensions({ width: outputWidth, height: outputHeight });
  const background = renderAdjustedImage(document, image, viewCrop);
  if (image) {
    const base = new FabricImage(background);
    const metadata = base as unknown as FabricCanvasObject;
    metadata.kind = "base";
    base.set({ left: 0, top: 0, originX: "left", originY: "top", selectable: false, evented: false });
    canvas.add(base);
    canvas.sendObjectToBack(base);
  }

  const offsetX = viewCrop.x;
  const offsetY = viewCrop.y;
  for (const layer of document.layers) {
    const object = createFabricLayer(layer, offsetX, offsetY);
    if (!object) continue;
    canvas.add(object);
  }

  if (tool === "crop") {
    const draft = cropDraftRef.current ?? document.crop ?? defaultCrop(document.canvas.width, document.canvas.height);
    cropDraftRef.current = draft;
    const overlay = new Rect({
      left: draft.x,
      top: draft.y,
      width: draft.width,
      height: draft.height,
      originX: "left",
      originY: "top",
      fill: "rgba(51,112,255,0.10)",
      stroke: "#3370ff",
      strokeWidth: 2,
      strokeDashArray: [8, 6],
      transparentCorners: false,
      cornerColor: "#ffffff",
      cornerStrokeColor: "#3370ff",
      cornerSize: 10,
      lockRotation: true,
    });
    const metadata = overlay as unknown as FabricCanvasObject;
    metadata.kind = "crop";
    canvas.add(overlay);
    canvas.setActiveObject(overlay);
  }

  const angle = rotationRadians;
  const cosine = Math.cos(angle) * zoom;
  const sine = Math.sin(angle) * zoom;
  const translateX = outputWidth / 2 - (cosine * viewCrop.width) / 2 + (sine * viewCrop.height) / 2;
  const translateY = outputHeight / 2 - (sine * viewCrop.width) / 2 - (cosine * viewCrop.height) / 2;
  canvas.setViewportTransform([cosine, sine, -sine, cosine, translateX, translateY]);
  fitCanvasToStage(canvas, stage);
  canvas.requestRenderAll();
}

function fitCanvasToStage(canvas: Canvas, stage: HTMLDivElement | null): void {
  if (!stage) return;
  const computed = window.getComputedStyle(stage);
  const horizontalPadding = Number.parseFloat(computed.paddingLeft) + Number.parseFloat(computed.paddingRight);
  const verticalPadding = Number.parseFloat(computed.paddingTop) + Number.parseFloat(computed.paddingBottom);
  const availableWidth = Math.max(1, stage.clientWidth - horizontalPadding);
  const availableHeight = Math.max(1, stage.clientHeight - verticalPadding);
  const naturalWidth = Math.max(1, canvas.getWidth());
  const naturalHeight = Math.max(1, canvas.getHeight());
  const fit = Math.min(1, availableWidth / naturalWidth, availableHeight / naturalHeight);
  const displayWidth = Math.max(1, Math.floor(naturalWidth * fit));
  const displayHeight = Math.max(1, Math.floor(naturalHeight * fit));
  const lowerCanvas = canvas.getElement();
  const wrapper = lowerCanvas.parentElement;
  if (!wrapper) return;
  wrapper.style.width = `${displayWidth}px`;
  wrapper.style.height = `${displayHeight}px`;
  for (const element of wrapper.querySelectorAll("canvas")) {
    element.style.width = `${displayWidth}px`;
    element.style.height = `${displayHeight}px`;
  }
}

function createFabricLayer(layer: EditorLayer, offsetX: number, offsetY: number): IText | Path | null {
  if (layer.type === "text") {
    const text = new IText(layer.text, {
      left: layer.left - offsetX,
      top: layer.top - offsetY,
      fill: layer.color,
      fontSize: layer.fontSize,
      fontFamily: layer.fontFamily,
      fontWeight: layer.fontWeight,
      angle: layer.angle,
      scaleX: layer.scaleX,
      scaleY: layer.scaleY,
      padding: 8,
    });
    const metadata = text as unknown as FabricCanvasObject;
    metadata.kind = "layer";
    metadata.layerType = "text";
    metadata.layerId = layer.id;
    return text;
  }

  const path = new Path(layer.path as never, {
    left: layer.left - offsetX,
    top: layer.top - offsetY,
    fill: "",
    stroke: layer.color,
    strokeWidth: layer.size,
    opacity: layer.opacity,
    angle: layer.angle,
    scaleX: layer.scaleX,
    scaleY: layer.scaleY,
    strokeLineCap: "round",
    strokeLineJoin: "round",
  });
  const metadata = path as unknown as FabricCanvasObject;
  metadata.kind = "layer";
  metadata.layerType = "drawing";
  metadata.layerId = layer.id;
  metadata.drawingTool = layer.tool;
  if (layer.tool === "eraser") {
    (path as unknown as { globalCompositeOperation: string }).globalCompositeOperation = "destination-out";
  }
  return path;
}

function serializeCanvas(canvas: Canvas, document: EditorDocument, cropDraft: CropRect | null): EditorDocument {
  const crop = document.crop;
  const offsetX = crop?.x ?? 0;
  const offsetY = crop?.y ?? 0;
  const layers: EditorLayer[] = [];
  for (const object of canvas.getObjects()) {
    const metadata = object as unknown as FabricCanvasObject & {
      left?: number;
      top?: number;
      scaleX?: number;
      scaleY?: number;
      angle?: number;
      text?: string;
      fontSize?: number;
      fill?: string;
      fontFamily?: string;
      fontWeight?: string | number;
      path?: SerializablePath;
      stroke?: string;
      strokeWidth?: number;
      opacity?: number;
    };
    if (metadata.kind !== "layer" || !metadata.layerId) continue;
    if (metadata.layerType === "text") {
      layers.push({
        id: metadata.layerId,
        type: "text",
        text: metadata.text ?? "",
        left: (metadata.left ?? 0) + offsetX,
        top: (metadata.top ?? 0) + offsetY,
        fontSize: metadata.fontSize ?? 32,
        color: typeof metadata.fill === "string" ? metadata.fill : "#ffffff",
        fontFamily: metadata.fontFamily ?? "Inter, PingFang SC, Microsoft YaHei, sans-serif",
        fontWeight: String(metadata.fontWeight ?? "600"),
        angle: metadata.angle ?? 0,
        scaleX: metadata.scaleX ?? 1,
        scaleY: metadata.scaleY ?? 1,
      });
    } else if (metadata.layerType === "drawing" && metadata.path) {
      const path = normalizePath(metadata.path);
      layers.push({
        id: metadata.layerId,
        type: "drawing",
        tool: metadata.drawingTool ?? "pen",
        color: typeof metadata.stroke === "string" ? metadata.stroke : "#3370ff",
        size: metadata.strokeWidth ?? 4,
        opacity: metadata.opacity ?? 1,
        path,
        left: (metadata.left ?? 0) + offsetX,
        top: (metadata.top ?? 0) + offsetY,
        scaleX: metadata.scaleX ?? 1,
        scaleY: metadata.scaleY ?? 1,
        angle: metadata.angle ?? 0,
      });
    }
  }
  return {
    ...document,
    layers,
    crop: cropDraft && !document.crop ? normalizeCrop(cropDraft, document.canvas.width, document.canvas.height) : document.crop,
  };
}

function normalizePath(path: SerializablePath): SerializablePath {
  let minX = 0;
  let minY = 0;
  let initialized = false;
  for (const command of path) {
    for (let index = 1; index < command.length; index += 2) {
      const x = Number(command[index]);
      const y = Number(command[index + 1]);
      if (!Number.isFinite(x) || !Number.isFinite(y)) continue;
      if (!initialized) {
        minX = x;
        minY = y;
        initialized = true;
      } else {
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
      }
    }
  }
  if (!initialized || (minX === 0 && minY === 0)) return path;
  return path.map((command) => command.map((value, index) => {
    if (index === 0 || typeof value === "string") return value;
    const number = Number(value);
    return number - (index % 2 === 1 ? minX : minY);
  }));
}

function rectToCrop(
  object: { left?: number; top?: number; width?: number; height?: number; scaleX?: number; scaleY?: number },
  document: EditorDocument,
): CropRect {
  return normalizeCrop(
    {
      x: object.left ?? 0,
      y: object.top ?? 0,
      width: (object.width ?? document.canvas.width) * (object.scaleX ?? 1),
      height: (object.height ?? document.canvas.height) * (object.scaleY ?? 1),
    },
    document.canvas.width,
    document.canvas.height,
  );
}

function defaultCrop(width: number, height: number): CropRect {
  const insetX = width * 0.08;
  const insetY = height * 0.08;
  return { x: insetX, y: insetY, width: width - insetX * 2, height: height - insetY * 2 };
}

function createObjectId(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) return crypto.randomUUID();
  return `layer-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}
