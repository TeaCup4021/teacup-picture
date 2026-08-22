"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { editorApi } from "@/features/editor/api/editor-api";
import { CollaborationConnection } from "@/features/editor/collaboration/connection";
import type { CollaborationSession, CollaborationStatus } from "@/features/editor/collaboration/types";
import type { EditorDocument } from "@/features/editor/model/types";

export function useEditorCollaboration(pictureId: string, initial: EditorDocument) {
  const connectionRef = useRef<CollaborationConnection | null>(null);
  const [session, setSession] = useState<CollaborationSession | null>(null);
  const [document, setDocument] = useState(initial);
  const [status, setStatus] = useState<CollaborationStatus>("disabled");

  useEffect(() => {
    let disposed = false;
    void editorApi.getCollaborationSession(pictureId).then((value) => {
      if (disposed) return;
      setSession(value);
      if (!value.enabled || !value.roomEpoch || !value.wsPath) return;
      const connection = new CollaborationConnection(pictureId, value, initial);
      connection.setListeners({ onStatus: setStatus, onDocument: setDocument });
      connectionRef.current = connection;
      connection.connect();
    }).catch(() => { if (!disposed) setStatus("disabled"); });
    return () => {
      disposed = true;
      connectionRef.current?.stop();
      connectionRef.current = null;
    };
  }, [initial, pictureId]);

  const applyDocument = useCallback((next: EditorDocument, kind?: string) => {
    if (connectionRef.current) connectionRef.current.applyDocument(next, kind);
    setDocument(next);
  }, []);

  const flush = useCallback(() => connectionRef.current?.flush() ?? Promise.resolve(), []);

  return {
    enabled: Boolean(session?.enabled && connectionRef.current),
    canEdit: Boolean(session?.canEdit),
    session,
    document,
    status,
    applyDocument,
    flush,
  };
}
