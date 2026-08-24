"use client";

import { CheckOutlined, CloseOutlined } from "@ant-design/icons";
import { Button, Space, Tooltip } from "antd";
import { Canvas, Path, PencilBrush, Rect, Textbox } from "fabric";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { normalizeCrop, normalizeEditorDocument } from "@/features/editor/model/document";
import { calculateEditorPreviewScale } from "@/features/editor/model/preview";
import { renderAdjustedImage } from "@/features/editor/model/render";
import type {
  CropRect,
  EditorDocument,
  EditorLayer,
  EditorTool,
  SerializablePath,
} from "@/features/editor/model/types";

interface EditorCanvasProps {
  document: EditorDocument;
  image: HTMLImageElement | null;
  tool: EditorTool;
  strokeColor: string;
  strokeSize: number;
  textColor: string;
  fontSize: number;
  viewZoom: number;
  readOnly: boolean;
  selectedLayerId: string | null;
  onSelectLayer: (id: string | null) => void;
  onDocumentChange: (document: EditorDocument) => void;
  onStrokeChunk: (document: EditorDocument, layerId: string) => void;
  onCropApply: (crop: CropRect) => void;
  onCropCancel: () => void;
}

type FabricCanvasObject = {
  kind?: "layer" | "crop";
  layerId?: string;
  layerType?: "text" | "drawing";
  drawingTool?: "pen" | "marker" | "eraser";
};

type CanvasRenderState = {
  document: EditorDocument;
  image: HTMLImageElement | null;
  tool: EditorTool;
};

export function EditorCanvas({
  document,
  image,
  tool,
  strokeColor,
  strokeSize,
  textColor,
  fontSize,
  viewZoom,
  readOnly,
  selectedLayerId,
  onSelectLayer,
  onDocumentChange,
  onStrokeChunk,
  onCropApply,
  onCropCancel,
}: EditorCanvasProps) {
  const elementRef = useRef<HTMLCanvasElement | null>(null);
  const baseElementRef = useRef<HTMLCanvasElement | null>(null);
  const stageRef = useRef<HTMLDivElement | null>(null);
  const stackRef = useRef<HTMLDivElement | null>(null);
  const fabricRef = useRef<Canvas | null>(null);
  const applyingRef = useRef(false);
  const renderStateRef = useRef<CanvasRenderState | null>(null);
  const previewFrameRef = useRef<number | null>(null);
  const toolRef = useRef(tool);
  const documentRef = useRef(document);
  const cropDraftRef = useRef<CropRect | null>(null);
  const onDocumentChangeRef = useRef(onDocumentChange);
  const onSelectLayerRef = useRef(onSelectLayer);
  const textColorRef = useRef(textColor);
  const fontSizeRef = useRef(fontSize);
  const selectedLayerIdRef = useRef(selectedLayerId);
  const viewZoomRef = useRef(viewZoom);
  const readOnlyRef = useRef(readOnly);
  const strokePointsRef = useRef<Array<{ x: number; y: number }>>([]);
  const strokeLayerIdRef = useRef<string | null>(null);
  const strokeLastEmitRef = useRef(0);
  const onStrokeChunkRef = useRef(onStrokeChunk);
  const strokeColorRef = useRef(strokeColor);
  const strokeSizeRef = useRef(strokeSize);
  const interactionActiveRef = useRef(false);
  const [draftCrop, setDraftCrop] = useState<CropRect | null>(null);
  const [eraserCursor, setEraserCursor] = useState<{ x: number; y: number } | null>(null);

  useLayoutEffect(() => {
    onDocumentChangeRef.current = onDocumentChange;
    onSelectLayerRef.current = onSelectLayer;
    documentRef.current = document;
    toolRef.current = tool;
    textColorRef.current = textColor;
    fontSizeRef.current = fontSize;
    selectedLayerIdRef.current = selectedLayerId;
    viewZoomRef.current = viewZoom;
    readOnlyRef.current = readOnly;
    onStrokeChunkRef.current = onStrokeChunk;
    strokeColorRef.current = strokeColor;
    strokeSizeRef.current = strokeSize;
  }, [
    document,
    fontSize,
    onDocumentChange,
    onSelectLayer,
    selectedLayerId,
    textColor,
    tool,
    viewZoom,
    readOnly,
    onStrokeChunk,
    strokeColor,
    strokeSize,
  ]);

  useEffect(() => {
    const element = elementRef.current;
    if (!element) return;
    const canvas = new Canvas(element, {
      preserveObjectStacking: true,
      selection: true,
      stopContextMenu: true,
      fireRightClick: false,
      enableRetinaScaling: false,
    });
    fabricRef.current = canvas;
    const resizeObserver = new ResizeObserver(() =>
      fitCanvasToStage(canvas, stageRef.current, stackRef.current, viewZoomRef.current),
    );
    if (stageRef.current) resizeObserver.observe(stageRef.current);

    const emitDocument = () => {
      if (applyingRef.current) return;
      onDocumentChangeRef.current(
        serializeCanvas(canvas, documentRef.current, cropDraftRef.current),
      );
    };
    let lastLiveEmit = 0;
    const emitLiveDocument = () => {
      if (applyingRef.current) return;
      const now = Date.now();
      if (now - lastLiveEmit < 50) return;
      lastLiveEmit = now;
      emitDocument();
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
      const object = event.target as FabricCanvasObject & {
        left?: number;
        top?: number;
        width?: number;
        height?: number;
        scaleX?: number;
        scaleY?: number;
      };
      if (object.kind === "crop") {
        cropDraftRef.current = rectToCrop(object, documentRef.current);
        setDraftCrop(cropDraftRef.current);
        interactionActiveRef.current = false;
        canvas.requestRenderAll();
        return;
      }
      interactionActiveRef.current = false;
      emitDocument();
    });
    canvas.on("object:moving", () => { interactionActiveRef.current = true; emitLiveDocument(); });
    canvas.on("object:scaling", () => { interactionActiveRef.current = true; emitLiveDocument(); });
    canvas.on("object:rotating", () => { interactionActiveRef.current = true; emitLiveDocument(); });
    canvas.on("text:editing:exited", () => {
      interactionActiveRef.current = false;
      emitDocument();
    });
    canvas.on("text:changed", emitLiveDocument);
    canvas.on("path:created", (event) => {
      const path = event.path as FabricCanvasObject & {
        set: (key: string, value: unknown) => void;
      };
      path.set("kind", "layer");
      path.set("layerType", "drawing");
      path.set("layerId", strokeLayerIdRef.current ?? createObjectId());
      path.set(
        "drawingTool",
        toolRef.current === "eraser" ? "eraser" : toolRef.current === "marker" ? "marker" : "pen",
      );
      if (toolRef.current === "marker") path.set("opacity", 0.35);
      if (toolRef.current === "eraser") {
        (path as unknown as { globalCompositeOperation: string }).globalCompositeOperation =
          "destination-out";
      }
      emitDocument();
      strokePointsRef.current = [];
      strokeLayerIdRef.current = null;
      interactionActiveRef.current = false;
    });
    canvas.on("mouse:down", (event) => {
      if (readOnlyRef.current || !canvas.isDrawingMode) return;
      const point = canvas.getScenePoint(event.e);
      strokePointsRef.current = [point];
      strokeLayerIdRef.current = createObjectId();
      strokeLastEmitRef.current = 0;
      interactionActiveRef.current = true;
    });
    canvas.on("mouse:move", (event) => {
      if (readOnlyRef.current || !canvas.isDrawingMode || !strokeLayerIdRef.current) return;
      const point = canvas.getScenePoint(event.e);
      strokePointsRef.current.push(point);
      const now = Date.now();
      if (now - strokeLastEmitRef.current < 33) return;
      strokeLastEmitRef.current = now;
      const current = documentRef.current;
      const layer: EditorLayer = {
        id: strokeLayerIdRef.current,
        type: "drawing",
        tool: toolRef.current === "marker" ? "marker" : toolRef.current === "eraser" ? "eraser" : "pen",
        color: strokeColorRef.current,
        size: strokeSizeRef.current,
        opacity: toolRef.current === "marker" ? 0.35 : 1,
        path: strokePointsRef.current.map((value, index) => index === 0 ? ["M", value.x, value.y] : ["L", value.x, value.y]),
        left: 0, top: 0, scaleX: 1, scaleY: 1, flipX: false, flipY: false, angle: 0,
      };
      onStrokeChunkRef.current({ ...current, layers: [...current.layers.filter((item) => item.id !== layer.id), layer] }, layer.id);
    });
    canvas.on("mouse:down", (event) => {
      if (readOnlyRef.current || toolRef.current !== "text") return;
      const target = event.target as FabricCanvasObject | undefined;
      if (target?.kind === "layer") return;
      const point = canvas.getScenePoint(event.e);
      const text = new Textbox("输入文字", {
        left: point.x,
        top: point.y,
        width: Math.min(320, Math.max(120, documentRef.current.canvas.width - point.x)),
        fill: textColorRef.current,
        fontSize: fontSizeRef.current,
        fontFamily: "Inter, PingFang SC, Microsoft YaHei, sans-serif",
        fontWeight: "600",
        padding: 8,
        borderColor: "#3370ff",
        cornerColor: "#ffffff",
        cornerStrokeColor: "#3370ff",
        cornerSize: 12,
        transparentCorners: false,
        lockScalingFlip: true,
        minScaleLimit: 0.01,
      });
      text.setControlsVisibility({
        tl: false,
        tr: false,
        bl: false,
        br: false,
        mt: false,
        mb: false,
        ml: true,
        mr: true,
        mtr: true,
      });
      const metadata = text as unknown as FabricCanvasObject & {
        set: (key: string, value: unknown) => void;
      };
      metadata.set("kind", "layer");
      metadata.set("layerType", "text");
      metadata.set("layerId", createObjectId());
      canvas.add(text);
      canvas.setActiveObject(text);
      text.enterEditing();
      interactionActiveRef.current = true;
      text.selectAll();
      canvas.requestRenderAll();
    });

    return () => {
      if (previewFrameRef.current !== null) window.cancelAnimationFrame(previewFrameRef.current);
      resizeObserver.disconnect();
      fabricRef.current = null;
      void canvas.dispose();
    };
  }, []);

  useLayoutEffect(() => {
    const canvas = fabricRef.current;
    if (!canvas) return;
    const previous = renderStateRef.current;
    const active = canvas.getActiveObject();
    if (active instanceof Textbox && active.isEditing && previous && previous.tool !== tool) {
      active.exitEditing();
      return;
    }
    if (interactionActiveRef.current) return;
    renderStateRef.current = { document, image, tool };

    if (isAdjustmentOnlyUpdate(previous, document, image, tool)) {
      if (previewFrameRef.current !== null) window.cancelAnimationFrame(previewFrameRef.current);
      previewFrameRef.current = window.requestAnimationFrame(() => {
        previewFrameRef.current = null;
        updateAdjustedBase(canvas, baseElementRef.current, document, image, tool, stageRef.current);
      });
      return;
    }

    if (previewFrameRef.current !== null) {
      window.cancelAnimationFrame(previewFrameRef.current);
      previewFrameRef.current = null;
    }
    applyingRef.current = true;
    try {
      rebuildCanvas(
        canvas,
        baseElementRef.current,
        document,
        image,
        tool,
        cropDraftRef,
        stageRef.current,
        stackRef.current,
        viewZoomRef.current,
      );
      restoreSelection(canvas, selectedLayerIdRef.current);
      setDraftCrop(cropDraftRef.current);
    } finally {
      applyingRef.current = false;
    }
  }, [document, image, tool]);

  useEffect(() => {
    const canvas = fabricRef.current;
    if (!canvas) return;
    canvas.isDrawingMode = !readOnly && (tool === "pen" || tool === "marker" || tool === "eraser");
    canvas.selection = !readOnly && tool === "select";
    if (canvas.isDrawingMode) {
      const brush = new PencilBrush(canvas);
      brush.width = strokeSize;
      brush.color = tool === "eraser" ? "#aeb6c3" : strokeColor;
      brush.decimate = 0.5;
      canvas.freeDrawingBrush = brush;
    }
    canvas.getObjects().forEach((object) => {
      const metadata = object as unknown as FabricCanvasObject;
      if (metadata.kind === "crop") return;
      object.selectable = !readOnly && tool === "select";
      object.evented = !readOnly && tool === "select";
    });
    canvas.requestRenderAll();
  }, [readOnly, tool, strokeColor, strokeSize]);

  useEffect(() => {
    const canvas = fabricRef.current;
    if (!canvas) return;
    if (!selectedLayerId) {
      canvas.discardActiveObject();
      canvas.requestRenderAll();
      return;
    }
    const object = canvas
      .getObjects()
      .find((item) => (item as unknown as FabricCanvasObject).layerId === selectedLayerId);
    if (object) {
      canvas.setActiveObject(object);
      canvas.requestRenderAll();
    }
  }, [selectedLayerId]);

  useEffect(() => {
    const canvas = fabricRef.current;
    if (!canvas) return;
    fitCanvasToStage(canvas, stageRef.current, stackRef.current, viewZoom);
  }, [viewZoom]);

  const cropMode = tool === "crop";
  return (
    <div
      ref={stageRef}
      className={tool === "eraser" ? "editor-canvas-stage is-erasing" : "editor-canvas-stage"}
      onPointerMove={(event) => {
        if (tool !== "eraser") return;
        const bounds = event.currentTarget.getBoundingClientRect();
        setEraserCursor({
          x: event.clientX - bounds.left + event.currentTarget.scrollLeft,
          y: event.clientY - bounds.top + event.currentTarget.scrollTop,
        });
      }}
      onPointerLeave={() => setEraserCursor(null)}
    >
      <div ref={stackRef} className="editor-canvas-stack">
        <canvas ref={baseElementRef} className="editor-base-canvas" aria-hidden="true" />
        <canvas ref={elementRef} className="editor-canvas" aria-label="图片编辑器画布" />
      </div>
      {!image ? <span className="editor-canvas-empty">正在加载原图…</span> : null}
      {tool === "eraser" && eraserCursor ? (
        <span
          className="editor-eraser-cursor"
          aria-hidden="true"
          style={{
            left: eraserCursor.x,
            top: eraserCursor.y,
            width: Math.max(12, Math.min(80, strokeSize * viewZoom)),
            height: Math.max(12, Math.min(80, strokeSize * viewZoom)),
          }}
        />
      ) : null}
      {cropMode && draftCrop ? (
        <div className="editor-crop-actions">
          <Space size={6}>
            <Tooltip title="取消裁切">
              <Button
                size="small"
                icon={<CloseOutlined />}
                onClick={onCropCancel}
                aria-label="取消裁切"
              />
            </Tooltip>
            <Button
              size="small"
              type="primary"
              icon={<CheckOutlined />}
              onClick={() => onCropApply(draftCrop)}
            >
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
  baseCanvas: HTMLCanvasElement | null,
  document: EditorDocument,
  image: HTMLImageElement | null,
  tool: EditorTool,
  cropDraftRef: { current: CropRect | null },
  stage: HTMLDivElement | null,
  stack: HTMLDivElement | null,
  viewZoom: number,
): void {
  if (tool !== "crop") cropDraftRef.current = null;
  const geometry = previewGeometry(document, tool, stage);
  const { outputWidth, outputHeight, previewScale, viewCrop } = geometry;
  canvas.clear();
  const canvasWidth = Math.max(1, Math.ceil(outputWidth * previewScale));
  const canvasHeight = Math.max(1, Math.ceil(outputHeight * previewScale));
  canvas.setDimensions({ width: canvasWidth, height: canvasHeight });

  const offsetX = viewCrop.x;
  const offsetY = viewCrop.y;
  const orderedLayers = [
    ...document.layers.filter((layer) => layer.type === "drawing"),
    ...document.layers.filter((layer) => layer.type === "text"),
  ];
  for (const layer of orderedLayers) {
    const object = createFabricLayer(layer, offsetX, offsetY);
    if (!object) continue;
    canvas.add(object);
  }

  if (tool === "crop") {
    const draft =
      cropDraftRef.current ??
      document.crop ??
      defaultCrop(document.canvas.width, document.canvas.height);
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

  const transform = viewportTransform(document, geometry);
  canvas.setViewportTransform(transform);
  drawBaseCanvas(baseCanvas, document, image, geometry, transform, canvasWidth, canvasHeight);
  fitCanvasToStage(canvas, stage, stack, viewZoom);
  canvas.requestRenderAll();
}

function updateAdjustedBase(
  canvas: Canvas,
  baseCanvas: HTMLCanvasElement | null,
  document: EditorDocument,
  image: HTMLImageElement | null,
  tool: EditorTool,
  stage: HTMLDivElement | null,
): void {
  const geometry = previewGeometry(document, tool, stage);
  const transform = viewportTransform(document, geometry);
  drawBaseCanvas(
    baseCanvas,
    document,
    image,
    geometry,
    transform,
    canvas.getWidth(),
    canvas.getHeight(),
  );
}

function viewportTransform(
  document: EditorDocument,
  geometry: ReturnType<typeof previewGeometry>,
): [number, number, number, number, number, number] {
  const { outputWidth, outputHeight, previewScale, rotationRadians, viewCrop } = geometry;
  const scaleX = document.transform.flipX ? -1 : 1;
  const scaleY = document.transform.flipY ? -1 : 1;
  const cosine = Math.cos(rotationRadians);
  const sine = Math.sin(rotationRadians);
  const a = cosine * scaleX;
  const b = sine * scaleX;
  const c = -sine * scaleY;
  const d = cosine * scaleY;
  const translateX = outputWidth / 2 - (a * viewCrop.width) / 2 - (c * viewCrop.height) / 2;
  const translateY = outputHeight / 2 - (b * viewCrop.width) / 2 - (d * viewCrop.height) / 2;
  return [
    a * previewScale,
    b * previewScale,
    c * previewScale,
    d * previewScale,
    translateX * previewScale,
    translateY * previewScale,
  ];
}

function drawBaseCanvas(
  baseCanvas: HTMLCanvasElement | null,
  document: EditorDocument,
  image: HTMLImageElement | null,
  geometry: ReturnType<typeof previewGeometry>,
  transform: [number, number, number, number, number, number],
  width: number,
  height: number,
): void {
  if (!baseCanvas) return;
  baseCanvas.width = width;
  baseCanvas.height = height;
  const context = baseCanvas.getContext("2d");
  if (!context) return;
  context.clearRect(0, 0, width, height);
  if (!image) return;
  const background = renderAdjustedImage(document, image, geometry.viewCrop, geometry.previewScale);
  context.save();
  context.setTransform(...transform);
  context.drawImage(background, 0, 0, geometry.viewCrop.width, geometry.viewCrop.height);
  context.restore();
}

function previewGeometry(
  document: EditorDocument,
  tool: EditorTool,
  stage: HTMLDivElement | null,
): {
  outputWidth: number;
  outputHeight: number;
  previewScale: number;
  rotationRadians: number;
  viewCrop: CropRect;
} {
  const crop = tool === "crop" ? null : document.crop;
  const viewCrop = crop ?? {
    x: 0,
    y: 0,
    width: document.canvas.width,
    height: document.canvas.height,
  };
  const rotation = tool === "crop" ? 0 : document.transform.rotation;
  const rotationRadians = (rotation * Math.PI) / 180;
  const outputWidth = Math.max(
    1,
    Math.ceil(
      (Math.abs(Math.cos(rotationRadians)) * viewCrop.width +
        Math.abs(Math.sin(rotationRadians)) * viewCrop.height) *
        1,
    ),
  );
  const outputHeight = Math.max(
    1,
    Math.ceil(
      (Math.abs(Math.sin(rotationRadians)) * viewCrop.width +
        Math.abs(Math.cos(rotationRadians)) * viewCrop.height) *
        1,
    ),
  );
  const available = availableStageSize(stage);
  const previewScale = calculateEditorPreviewScale(
    outputWidth,
    outputHeight,
    available?.width,
    available?.height,
  );
  return { outputWidth, outputHeight, previewScale, rotationRadians, viewCrop };
}

function availableStageSize(
  stage: HTMLDivElement | null,
): { width: number; height: number } | null {
  if (!stage) return null;
  const computed = window.getComputedStyle(stage);
  return {
    width: Math.max(
      1,
      stage.clientWidth -
        Number.parseFloat(computed.paddingLeft) -
        Number.parseFloat(computed.paddingRight),
    ),
    height: Math.max(
      1,
      stage.clientHeight -
        Number.parseFloat(computed.paddingTop) -
        Number.parseFloat(computed.paddingBottom),
    ),
  };
}

function isAdjustmentOnlyUpdate(
  previous: CanvasRenderState | null,
  document: EditorDocument,
  image: HTMLImageElement | null,
  tool: EditorTool,
): boolean {
  if (!previous || previous.image !== image || previous.tool !== tool) return false;
  return (
    previous.document.canvas === document.canvas &&
    previous.document.transform === document.transform &&
    previous.document.crop === document.crop &&
    previous.document.layers === document.layers &&
    previous.document.adjustments !== document.adjustments
  );
}

function restoreSelection(canvas: Canvas, selectedLayerId: string | null): void {
  if (!selectedLayerId) return;
  const object = canvas
    .getObjects()
    .find((item) => (item as unknown as FabricCanvasObject).layerId === selectedLayerId);
  if (object) canvas.setActiveObject(object);
}

function fitCanvasToStage(
  canvas: Canvas,
  stage: HTMLDivElement | null,
  stack: HTMLDivElement | null,
  viewZoom: number,
): void {
  if (!stage || !stack) return;
  const computed = window.getComputedStyle(stage);
  const horizontalPadding =
    Number.parseFloat(computed.paddingLeft) + Number.parseFloat(computed.paddingRight);
  const verticalPadding =
    Number.parseFloat(computed.paddingTop) + Number.parseFloat(computed.paddingBottom);
  const availableWidth = Math.max(1, stage.clientWidth - horizontalPadding);
  const availableHeight = Math.max(1, stage.clientHeight - verticalPadding);
  const naturalWidth = Math.max(1, canvas.getWidth());
  const naturalHeight = Math.max(1, canvas.getHeight());
  const fit = Math.min(1, availableWidth / naturalWidth, availableHeight / naturalHeight);
  const displayWidth = Math.max(1, Math.floor(naturalWidth * fit * viewZoom));
  const displayHeight = Math.max(1, Math.floor(naturalHeight * fit * viewZoom));
  const lowerCanvas = canvas.getElement();
  const wrapper = lowerCanvas.parentElement;
  if (!wrapper) return;
  stack.style.width = `${displayWidth}px`;
  stack.style.height = `${displayHeight}px`;
  wrapper.style.width = `${displayWidth}px`;
  wrapper.style.height = `${displayHeight}px`;
  for (const element of wrapper.querySelectorAll("canvas")) {
    element.style.width = `${displayWidth}px`;
    element.style.height = `${displayHeight}px`;
  }
}

function createFabricLayer(
  layer: EditorLayer,
  offsetX: number,
  offsetY: number,
): Textbox | Path | null {
  if (layer.type === "text") {
    const text = new Textbox(layer.text, {
      left: layer.left - offsetX,
      top: layer.top - offsetY,
      width: layer.width,
      fill: layer.color,
      fontSize: layer.fontSize,
      fontFamily: layer.fontFamily,
      fontWeight: layer.fontWeight,
      angle: layer.angle,
      scaleX: layer.scaleX,
      scaleY: layer.scaleY,
      flipX: layer.flipX,
      flipY: layer.flipY,
      padding: 8,
      borderColor: "#3370ff",
      cornerColor: "#ffffff",
      cornerStrokeColor: "#3370ff",
      cornerSize: 12,
      transparentCorners: false,
      lockScalingFlip: true,
      minScaleLimit: 0.01,
    });
    text.setControlsVisibility({
      tl: false,
      tr: false,
      bl: false,
      br: false,
      mt: false,
      mb: false,
      ml: true,
      mr: true,
      mtr: true,
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
    flipX: layer.flipX,
    flipY: layer.flipY,
    lockScalingFlip: true,
    minScaleLimit: 0.01,
    strokeLineCap: "round",
    strokeLineJoin: "round",
  });
  const metadata = path as unknown as FabricCanvasObject;
  metadata.kind = "layer";
  metadata.layerType = "drawing";
  metadata.layerId = layer.id;
  metadata.drawingTool = layer.tool;
  if (layer.tool === "eraser") {
    (path as unknown as { globalCompositeOperation: string }).globalCompositeOperation =
      "destination-out";
  }
  return path;
}

function serializeCanvas(
  canvas: Canvas,
  document: EditorDocument,
  cropDraft: CropRect | null,
): EditorDocument {
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
      width?: number;
      fill?: string;
      fontFamily?: string;
      fontWeight?: string | number;
      path?: SerializablePath;
      stroke?: string;
      strokeWidth?: number;
      opacity?: number;
      flipX?: boolean;
      flipY?: boolean;
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
        width: metadata.width ?? 120,
        color: typeof metadata.fill === "string" ? metadata.fill : "#ffffff",
        fontFamily: metadata.fontFamily ?? "Inter, PingFang SC, Microsoft YaHei, sans-serif",
        fontWeight: String(metadata.fontWeight ?? "600"),
        angle: metadata.angle ?? 0,
        scaleX: metadata.scaleX ?? 1,
        scaleY: metadata.scaleY ?? 1,
        flipX: metadata.flipX ?? false,
        flipY: metadata.flipY ?? false,
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
        flipX: metadata.flipX ?? false,
        flipY: metadata.flipY ?? false,
        angle: metadata.angle ?? 0,
      });
    }
  }
  return normalizeEditorDocument({
    ...document,
    layers,
    crop:
      cropDraft && !document.crop
        ? normalizeCrop(cropDraft, document.canvas.width, document.canvas.height)
        : document.crop,
  });
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
  return path.map((command) =>
    command.map((value, index) => {
      if (index === 0 || typeof value === "string") return value;
      const number = Number(value);
      return number - (index % 2 === 1 ? minX : minY);
    }),
  );
}

function rectToCrop(
  object: {
    left?: number;
    top?: number;
    width?: number;
    height?: number;
    scaleX?: number;
    scaleY?: number;
  },
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
