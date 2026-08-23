import type { EditorDocument } from "@/features/editor/model/types";

export interface CollaborationSession {
  roomId: string | null;
  pictureId: string;
  roomEpoch: string | null;
  lastServerSeq: string;
  role: string | null;
  enabled: boolean;
  canEdit: boolean;
  wsPath: string | null;
  baselineEditorState?: EditorDocument | null;
}

export type CollaborationStatus = "disabled" | "connecting" | "connected" | "reconnecting" | "error";

export interface CollaborationSnapshot {
  editorState: EditorDocument;
  serverSeq: string;
}
