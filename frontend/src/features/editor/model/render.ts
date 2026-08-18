import { clamp } from "@/features/editor/model/document";
import type {
  EditorAdjustments,
  EditorDocument,
  EditorLayer,
  SerializablePath,
  TextLayer,
} from "@/features/editor/model/types";

type ImageSource = CanvasImageSource | null;

/**
 * The single renderer used for editor previews and exported version assets.
 * Fabric owns interaction; this renderer owns deterministic pixels.
 */
export function renderEditorDocument(
  document: EditorDocument,
  image: ImageSource,
  outputScale = 1,
): HTMLCanvasElement {
  const crop = document.crop ?? {
    x: 0,
    y: 0,
    width: document.canvas.width,
    height: document.canvas.height,
  };
  const source = renderAdjustedImage(document, image, crop, outputScale);
  const context = source.getContext("2d");
  if (!context) return source;

  const drawingSurface = documentForSize(source.width, source.height);
  const drawingContext = drawingSurface.getContext("2d");
  if (drawingContext) {
    drawingContext.scale(outputScale, outputScale);
    for (const layer of document.layers) {
      if (layer.type === "drawing") drawLayer(drawingContext, layer, crop);
    }
    context.drawImage(drawingSurface, 0, 0);
  }

  context.save();
  context.scale(outputScale, outputScale);
  for (const layer of document.layers) {
    if (layer.type === "text") drawLayer(context, layer, crop);
  }
  context.restore();

  const rotated = rotateAndScale(
    source,
    document.transform.rotation,
    document.transform.scale,
    document.transform.flipX,
    document.transform.flipY,
  );
  return rotated;
}

export function renderAdjustedImage(
  document: EditorDocument,
  image: ImageSource,
  cropOverride?: { x: number; y: number; width: number; height: number } | null,
  outputScale = 1,
): HTMLCanvasElement {
  const crop = cropOverride ?? {
    x: 0,
    y: 0,
    width: document.canvas.width,
    height: document.canvas.height,
  };
  const source = documentForSize(
    Math.max(1, Math.round(crop.width * outputScale)),
    Math.max(1, Math.round(crop.height * outputScale)),
  );
  const context = source.getContext("2d", { willReadFrequently: true });
  if (!context) return source;
  context.clearRect(0, 0, source.width, source.height);
  if (image) drawAdjustedImage(context, image, document, crop, outputScale);
  return source;
}

export async function exportEditorDocument(
  document: EditorDocument,
  image: ImageSource,
  mimeType = "image/png",
): Promise<Blob> {
  const canvas = renderEditorDocument(document, image, 1);
  return new Promise<Blob>((resolve, reject) => {
    canvas.toBlob(
      (blob) => {
        if (blob) resolve(blob);
        else reject(new Error("图片导出失败"));
      },
      mimeType,
      0.95,
    );
  });
}

function drawAdjustedImage(
  context: CanvasRenderingContext2D,
  image: CanvasImageSource,
  document: EditorDocument,
  crop: { x: number; y: number; width: number; height: number },
  scale: number,
): void {
  const adjustments = document.adjustments;
  context.drawImage(
    image,
    crop.x,
    crop.y,
    crop.width,
    crop.height,
    0,
    0,
    crop.width * scale,
    crop.height * scale,
  );

  const pixels = context.getImageData(0, 0, context.canvas.width, context.canvas.height);
  applyColorAdjustments(pixels.data, adjustments);

  context.putImageData(pixels, 0, 0);
  if (adjustments.sharpness !== 0) applySharpness(context, adjustments.sharpness / 100);
  if (adjustments.vignette !== 0) drawVignette(context, adjustments.vignette / 100);
}

/** Pure color stage shared by browser rendering and deterministic unit tests. */
export function applyColorAdjustments(
  data: Uint8ClampedArray,
  adjustments: EditorAdjustments,
): void {
  const exposure = Math.pow(2, adjustments.exposure / 100);
  const brightness = adjustments.brightness * 1.6;
  const enhance = adjustments.enhance / 100;
  const dehaze = adjustments.dehaze / 100;
  const contrast = 1 + adjustments.contrast / 100 + enhance * 0.28 + dehaze * 0.5;
  const saturation = 1 + adjustments.saturation / 100 + enhance * 0.18 + dehaze * 0.12;
  const vibrance = adjustments.vibrance / 100;
  const temperature = adjustments.temperature / 100;
  const tint = adjustments.tint / 100;

  for (let index = 0; index < data.length; index += 4) {
    let red = data[index] ?? 0;
    let green = data[index + 1] ?? 0;
    let blue = data[index + 2] ?? 0;
    const sourceLuminance = (red * 0.299 + green * 0.587 + blue * 0.114) / 255;

    red = red * exposure + brightness;
    green = green * exposure + brightness;
    blue = blue * exposure + brightness;
    red = (red - 128) * contrast + 128;
    green = (green - 128) * contrast + 128;
    blue = (blue - 128) * contrast + 128;

    const gray = red * 0.299 + green * 0.587 + blue * 0.114;
    const colorRange = (Math.max(red, green, blue) - Math.min(red, green, blue)) / 255;
    const vibranceFactor = 1 + vibrance * (1 - clamp(colorRange, 0, 1));
    const colorFactor = Math.max(0, saturation * vibranceFactor);
    red = gray + (red - gray) * colorFactor;
    green = gray + (green - gray) * colorFactor;
    blue = gray + (blue - gray) * colorFactor;

    const shadowWeight = clamp(1 - sourceLuminance * 2, 0, 1);
    const highlightWeight = clamp((sourceLuminance - 0.45) * 1.8, 0, 1);
    const shadowDelta = adjustments.shadows * 1.8 * shadowWeight;
    const highlightDelta = adjustments.highlights * 1.8 * highlightWeight;
    red += shadowDelta + highlightDelta + temperature * 22 + tint * 10;
    green += shadowDelta + highlightDelta - tint * 7;
    blue += shadowDelta + highlightDelta - temperature * 22 + tint * 10;

    if (dehaze > 0) {
      const blackPoint = 18 * dehaze;
      red = (red - blackPoint) / (1 - blackPoint / 255);
      green = (green - blackPoint) / (1 - blackPoint / 255);
      blue = (blue - blackPoint) / (1 - blackPoint / 255);
    }

    if (adjustments.fade > 0) {
      const fade = adjustments.fade / 100;
      red = red * (1 - fade) + 218 * fade;
      green = green * (1 - fade) + 220 * fade;
      blue = blue * (1 - fade) + 224 * fade;
    }

    data[index] = clamp(red, 0, 255);
    data[index + 1] = clamp(green, 0, 255);
    data[index + 2] = clamp(blue, 0, 255);
  }
}

function applySharpness(context: CanvasRenderingContext2D, strength: number): void {
  const width = context.canvas.width;
  const height = context.canvas.height;
  const source = context.getImageData(0, 0, width, height);
  const output = context.createImageData(width, height);
  const amount = clamp(Math.abs(strength), 0, 1) * 0.85;
  const sign = strength >= 0 ? 1 : -1;
  const sample = (x: number, y: number, channel: number): number => {
    const xx = clamp(x, 0, width - 1);
    const yy = clamp(y, 0, height - 1);
    return source.data[(yy * width + xx) * 4 + channel] ?? 0;
  };
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const offset = (y * width + x) * 4;
      for (let channel = 0; channel < 3; channel += 1) {
        const center = sample(x, y, channel);
        const neighbors =
          (sample(x - 1, y, channel) +
            sample(x + 1, y, channel) +
            sample(x, y - 1, channel) +
            sample(x, y + 1, channel)) /
          4;
        output.data[offset + channel] = clamp(
          center + sign * amount * (center - neighbors),
          0,
          255,
        );
      }
      output.data[offset + 3] = source.data[offset + 3] ?? 255;
    }
  }
  context.putImageData(output, 0, 0);
}

function drawLayer(
  context: CanvasRenderingContext2D,
  layer: EditorLayer,
  crop: { x: number; y: number },
): void {
  context.save();
  context.translate(layer.left - crop.x, layer.top - crop.y);
  context.rotate((layer.angle * Math.PI) / 180);
  const bounds = layerBounds(layer);
  if (layer.flipX) context.translate(bounds.width * layer.scaleX, 0);
  if (layer.flipY) context.translate(0, bounds.height * layer.scaleY);
  context.scale(layer.scaleX * (layer.flipX ? -1 : 1), layer.scaleY * (layer.flipY ? -1 : 1));
  if (layer.type === "text") drawText(context, layer);
  else
    drawPath(context, layer.path, layer.color, layer.size, layer.opacity, layer.tool === "eraser");
  context.restore();
}

function drawText(context: CanvasRenderingContext2D, layer: TextLayer): void {
  context.font = `${layer.fontWeight} ${layer.fontSize}px ${layer.fontFamily}`;
  context.fillStyle = layer.color;
  context.textBaseline = "top";
  const lines = wrapText(context, layer.text, layer.width);
  const lineHeight = layer.fontSize * 1.16;
  lines.forEach((line, index) => context.fillText(line, 0, index * lineHeight));
}

function drawPath(
  context: CanvasRenderingContext2D,
  path: SerializablePath,
  color: string,
  size: number,
  opacity: number,
  eraser: boolean,
): void {
  if (path.length === 0) return;
  context.globalCompositeOperation = eraser ? "destination-out" : "source-over";
  context.globalAlpha = clamp(opacity, 0, 1);
  context.strokeStyle = color;
  context.lineWidth = size;
  context.lineCap = "round";
  context.lineJoin = "round";
  context.beginPath();
  for (const command of path) {
    const [type, first, second] = command;
    if (type === "M") context.moveTo(Number(first), Number(second));
    else if (type === "L") context.lineTo(Number(first), Number(second));
    else if (type === "Q")
      context.quadraticCurveTo(
        Number(first),
        Number(second),
        Number(command[3]),
        Number(command[4]),
      );
    else if (type === "C")
      context.bezierCurveTo(
        Number(first),
        Number(second),
        Number(command[3]),
        Number(command[4]),
        Number(command[5]),
        Number(command[6]),
      );
  }
  context.stroke();
}

function drawVignette(context: CanvasRenderingContext2D, strength: number): void {
  const { width, height } = context.canvas;
  const radius = Math.hypot(width, height) / 2;
  const gradient = context.createRadialGradient(
    width / 2,
    height / 2,
    radius * 0.35,
    width / 2,
    height / 2,
    radius,
  );
  if (strength > 0) {
    gradient.addColorStop(0, "rgba(0,0,0,0)");
    gradient.addColorStop(1, `rgba(0,0,0,${clamp(strength, 0, 1) * 0.55})`);
  } else {
    gradient.addColorStop(0, "rgba(255,255,255,0)");
    gradient.addColorStop(1, `rgba(255,255,255,${clamp(-strength, 0, 1) * 0.4})`);
  }
  context.fillStyle = gradient;
  context.fillRect(0, 0, width, height);
}

function rotateAndScale(
  source: HTMLCanvasElement,
  rotation: number,
  scale: number,
  flipX: boolean,
  flipY: boolean,
): HTMLCanvasElement {
  const radians = (rotation * Math.PI) / 180;
  const output = documentForSize(
    Math.max(
      1,
      Math.ceil(
        (Math.abs(Math.cos(radians)) * source.width + Math.abs(Math.sin(radians)) * source.height) *
          scale,
      ),
    ),
    Math.max(
      1,
      Math.ceil(
        (Math.abs(Math.sin(radians)) * source.width + Math.abs(Math.cos(radians)) * source.height) *
          scale,
      ),
    ),
  );
  const context = output.getContext("2d");
  if (!context) return output;
  context.translate(output.width / 2, output.height / 2);
  context.rotate(radians);
  context.scale(scale * (flipX ? -1 : 1), scale * (flipY ? -1 : 1));
  context.drawImage(source, -source.width / 2, -source.height / 2);
  return output;
}

function wrapText(context: CanvasRenderingContext2D, text: string, width: number): string[] {
  const result: string[] = [];
  for (const paragraph of text.split("\n")) {
    if (!paragraph) {
      result.push("");
      continue;
    }
    let line = "";
    for (const character of Array.from(paragraph)) {
      const candidate = line + character;
      if (line && context.measureText(candidate).width > width) {
        result.push(line);
        line = character;
      } else {
        line = candidate;
      }
    }
    result.push(line);
  }
  return result.length ? result : [""];
}

function layerBounds(layer: EditorLayer): { width: number; height: number } {
  if (layer.type === "text") {
    const lineCount = Math.max(
      1,
      Math.ceil((Math.max(1, Array.from(layer.text).length) * layer.fontSize * 0.62) / layer.width),
    );
    return { width: layer.width, height: lineCount * layer.fontSize * 1.16 };
  }
  let maxX = 1;
  let maxY = 1;
  for (const command of layer.path) {
    for (let index = 1; index < command.length; index += 2) {
      maxX = Math.max(maxX, Number(command[index]) || 0);
      maxY = Math.max(maxY, Number(command[index + 1]) || 0);
    }
  }
  return { width: maxX + layer.size, height: maxY + layer.size };
}

function documentForSize(width: number, height: number): HTMLCanvasElement {
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  return canvas;
}
