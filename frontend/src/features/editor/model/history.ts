import { cloneDocument } from "@/features/editor/model/document";
import type { EditorDocument } from "@/features/editor/model/types";

export interface EditorHistory {
  past: EditorDocument[];
  present: EditorDocument;
  future: EditorDocument[];
}

export type HistoryAction =
  { type: "commit"; document: EditorDocument } | { type: "undo" } | { type: "redo" };

export function createEditorHistory(document: EditorDocument): EditorHistory {
  return { past: [], present: cloneDocument(document), future: [] };
}

export function editorHistoryReducer(state: EditorHistory, action: HistoryAction): EditorHistory {
  if (action.type === "commit") {
    if (documentsEqual(state.present, action.document)) return state;
    return {
      past: [...state.past.slice(-49), cloneDocument(state.present)],
      present: cloneDocument(action.document),
      future: [],
    };
  }
  if (action.type === "undo") {
    const previous = state.past[state.past.length - 1];
    if (!previous) return state;
    return {
      past: state.past.slice(0, -1),
      present: cloneDocument(previous),
      future: [cloneDocument(state.present), ...state.future],
    };
  }
  const next = state.future[0];
  if (!next) return state;
  return {
    past: [...state.past.slice(-49), cloneDocument(state.present)],
    present: cloneDocument(next),
    future: state.future.slice(1),
  };
}

function documentsEqual(first: EditorDocument, second: EditorDocument): boolean {
  return JSON.stringify(first) === JSON.stringify(second);
}
