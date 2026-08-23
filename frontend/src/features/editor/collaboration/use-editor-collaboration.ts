"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { editorApi } from "@/features/editor/api/editor-api";
import { CollaborationConnection } from "@/features/editor/collaboration/connection";
import type { CollaborationSession, CollaborationStatus } from "@/features/editor/collaboration/types";
import type { EditorDocument } from "@/features/editor/model/types";

export function useEditorCollaboration(pictureId: string, initial: EditorDocument) {
  const connectionRef = useRef<CollaborationConnection | null>(null);
  const initialRef = useRef(initial);
  const [session, setSession] = useState<CollaborationSession | null>(null);
  const [document, setDocument] = useState(initial);
  const [status, setStatus] = useState<CollaborationStatus>("disabled");
  const [historyState, setHistoryState] = useState({ canUndo: false, canRedo: false });
  const [remoteCount, setRemoteCount] = useState(0);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let disposed = false;
    void editorApi.getCollaborationSession(pictureId).then((value) => {
      if (disposed) return;
      setSession(value);
      if (!value.enabled || !value.roomEpoch || !value.wsPath) return;
      const connection = new CollaborationConnection(pictureId, value, initialRef.current);
      connection.setListeners({ onStatus: setStatus, onDocument: setDocument, onPresence: setRemoteCount, onHistory: () => setHistoryState({ canUndo: connection.canUndo, canRedo: connection.canRedo }) });
      connectionRef.current = connection;
      setReady(true);
      connection.connect();
    }).catch(() => { if (!disposed) setStatus("disabled"); });
    return () => {
      disposed = true;
      connectionRef.current?.stop();
      connectionRef.current = null;
      setReady(false);
    };
  }, [pictureId]);

  const applyDocument = useCallback((next: EditorDocument, kind?: string, targetId?: string | null, changedFields?: string[]) => {
    if (connectionRef.current) connectionRef.current.applyDocument(next, kind ?? "document.patch", targetId ?? null, changedFields ?? ["editorState"]);
    setDocument(next);
  }, []);
  const applyStrokeChunk = useCallback((next: EditorDocument, targetId: string) => {
    connectionRef.current?.applyStrokeChunk(next, targetId);
  }, []);

  const flush = useCallback(() => connectionRef.current?.flush() ?? Promise.resolve(), []);
  const undo = useCallback(() => connectionRef.current?.undo(), []);
  const redo = useCallback(() => connectionRef.current?.redo(), []);
  const checkpoint = useCallback(async (editorState: EditorDocument, expectedRevision: string | null) => {
    const connection = connectionRef.current;
    if (!connection || !session?.roomEpoch) throw new Error("协作连接尚未就绪");
    await connection.flush();
    const hashes = await connection.checkpointHashes(editorState);
    return editorApi.checkpointCollaboration(pictureId, {
      roomEpoch: session.roomEpoch,
      lastServerSeq: connection.lastServerSeq,
      yjsState: connection.encodeState(),
      ...hashes,
      editorState,
      expectedRevision,
    });
  }, [pictureId, session]);
  const setAwareness = useCallback((payload: Record<string, unknown>) => connectionRef.current?.setAwareness(payload), []);

  return {
    enabled: Boolean(session?.enabled && ready),
    canEdit: Boolean(session?.canEdit),
    session,
    document,
    status,
    applyDocument,
    applyStrokeChunk,
    flush,
    checkpoint,
    canUndo: historyState.canUndo,
    canRedo: historyState.canRedo,
    undo,
    redo,
    remoteCount,
    setAwareness,
  };
}
