export type EditorTool = "select" | "crop" | "pen" | "marker" | "eraser" | "text";

export type DrawingTool = "pen" | "marker" | "eraser";

export interface Point {
  x: number;
  y: number;
}

/** A JSON-safe Fabric Path representation. */
export type SerializablePath = Array<Array<string | number>>;

export interface CropRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface DrawingLayer {
  id: string;
  type: "drawing";
  tool: DrawingTool;
  color: string;
  size: number;
  opacity: number;
  path: SerializablePath;
  left: number;
  top: number;
  scaleX: number;
  scaleY: number;
  flipX: boolean;
  flipY: boolean;
  angle: number;
}

export interface TextLayer {
  id: string;
  type: "text";
  text: string;
  left: number;
  top: number;
  fontSize: number;
  width: number;
  color: string;
  fontFamily: string;
  fontWeight: string;
  angle: number;
  scaleX: number;
  scaleY: number;
  flipX: boolean;
  flipY: boolean;
}

export type EditorLayer = DrawingLayer | TextLayer;

export type AdjustmentKey =
  | "exposure"
  | "brightness"
  | "contrast"
  | "highlights"
  | "shadows"
  | "saturation"
  | "vibrance"
  | "temperature"
  | "tint"
  | "sharpness"
  | "fade"
  | "vignette"
  | "enhance"
  | "dehaze";

export type EditorAdjustments = Record<AdjustmentKey, number>;

export interface EditorStateV3 {
  schemaVersion: 3;
  canvas: {
    width: number;
    height: number;
  };
  transform: {
    rotation: number;
    scale: number;
    flipX: boolean;
    flipY: boolean;
  };
  crop: CropRect | null;
  adjustments: EditorAdjustments;
  layers: EditorLayer[];
}

/** Domain name retained so API/query code remains readable during the migration. */
export type EditorDocument = EditorStateV3;

export interface EditorDraft {
  editorState: EditorDocument;
  updatedAt: string | null;
  revision: string;
}

export type EditorSaveMode = "replace" | "copy";

export interface EditorSaveResult {
  mode: EditorSaveMode;
  pictureId: string;
}

export interface EditorAuthor {
  id: string;
  name: string;
  avatarUrl?: string | null;
}

export interface PictureVersionSummary {
  id: string;
  versionNumber: number;
  name: string;
  note: string | null;
  sourceType: "original" | "user_save" | "restore" | "ai_generate" | "ai_outpaint" | "team_confirm";
  parentVersionId: string | null;
  width: number;
  height: number;
  thumbnailUrl: string;
  creator: EditorAuthor;
  createdAt: string;
}

export interface PictureVersionDetail extends PictureVersionSummary {
  previewUrl: string;
  editorState: EditorDocument;
}

export interface VersionListResponse {
  items: PictureVersionSummary[];
}
