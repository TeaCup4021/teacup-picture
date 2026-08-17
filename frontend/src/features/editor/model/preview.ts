const PREVIEW_OVERSAMPLE = 1.25;
const MAX_PREVIEW_PIXELS = 720_000;

export function calculateEditorPreviewScale(
  outputWidth: number,
  outputHeight: number,
  availableWidth?: number,
  availableHeight?: number,
): number {
  const width = Math.max(1, outputWidth);
  const height = Math.max(1, outputHeight);
  const pixelScale = Math.sqrt(MAX_PREVIEW_PIXELS / (width * height));
  const widthScale =
    availableWidth && availableWidth > 0 ? (availableWidth * PREVIEW_OVERSAMPLE) / width : 1;
  const heightScale =
    availableHeight && availableHeight > 0 ? (availableHeight * PREVIEW_OVERSAMPLE) / height : 1;
  return Math.min(1, pixelScale, widthScale, heightScale);
}
