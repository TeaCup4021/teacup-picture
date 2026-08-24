export interface AnnotationBounds {
  left: number;
  top: number;
  width: number;
  height: number;
  border: number;
}

export function normalizeAnnotationPosition(clientX: number, clientY: number, bounds: AnnotationBounds) {
  const width = Math.max(1, bounds.width - bounds.border * 2);
  const height = Math.max(1, bounds.height - bounds.border * 2);
  return {
    x: clamp((clientX - bounds.left - bounds.border) / width),
    y: clamp((clientY - bounds.top - bounds.border) / height),
  };
}

function clamp(value: number) {
  return Math.max(0, Math.min(1, value));
}
