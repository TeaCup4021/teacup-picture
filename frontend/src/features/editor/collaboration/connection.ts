import * as Y from "yjs";
import { IndexeddbPersistence } from "y-indexeddb";
import { readEditorDocument, REMOTE_ORIGIN, writeEditorDocument } from "@/features/editor/collaboration/document";
import type { CollaborationSession, CollaborationStatus } from "@/features/editor/collaboration/types";
import type { EditorDocument } from "@/features/editor/model/types";

interface PendingUpdate {
  operationId: string;
  update: Uint8Array;
}

interface CollaborationMessage {
  type: string;
  operationId?: string;
  serverSeq?: string;
  roomEpoch?: string;
  updates?: Array<Record<string, unknown>>;
  yjsUpdate?: string;
  [key: string]: unknown;
}

export class CollaborationConnection {
  readonly doc: Y.Doc;
  private readonly persistence: IndexeddbPersistence;
  private readonly session: CollaborationSession;
  private readonly pending = new Map<string, PendingUpdate>();
  private socket: WebSocket | null = null;
  private stopped = false;
  private reconnectTimer: number | null = null;
  private serverSeq: string;
  private statusValue: CollaborationStatus = "connecting";
  private statusListener: ((status: CollaborationStatus) => void) | null = null;
  private documentListener: ((document: EditorDocument) => void) | null = null;

  constructor(pictureId: string, session: CollaborationSession, initial: EditorDocument) {
    this.session = session;
    this.serverSeq = session.lastServerSeq;
    this.doc = new Y.Doc();
    this.persistence = new IndexeddbPersistence(`teacup-picture:${pictureId}:${session.roomEpoch}`, this.doc);
    if (this.doc.getMap("metadata").size === 0) writeEditorDocument(this.doc, initial, null);
    this.doc.on("update", (update: Uint8Array, origin: unknown) => {
      this.documentListener?.(readEditorDocument(this.doc));
      if (origin === REMOTE_ORIGIN || origin === null) return;
      const operationId = createId();
      this.pending.set(operationId, { operationId, update });
      this.sendPending(operationId);
    });
  }

  setListeners(listeners: { onStatus?: (status: CollaborationStatus) => void; onDocument?: (document: EditorDocument) => void }): void {
    this.statusListener = listeners.onStatus ?? null;
    this.documentListener = listeners.onDocument ?? null;
    this.documentListener?.(readEditorDocument(this.doc));
  }

  connect(): void {
    this.stopped = false;
    this.setStatus("connecting");
    const baseUrl = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://127.0.0.1:8123/api/v1";
    const url = new URL(this.session.wsPath ?? "", baseUrl);
    url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
    this.socket = new WebSocket(url.toString());
    this.socket.onopen = () => {
      this.setStatus("connected");
      this.send({ type: "hello", roomEpoch: this.session.roomEpoch, lastServerSeq: this.serverSeq });
    };
    this.socket.onmessage = (event) => this.handleMessage(JSON.parse(String(event.data)) as CollaborationMessage);
    this.socket.onerror = () => this.setStatus("error");
    this.socket.onclose = () => {
      if (this.stopped) return;
      this.setStatus("reconnecting");
      this.reconnectTimer = window.setTimeout(() => this.connect(), 1_000);
    };
  }

  stop(): void {
    this.stopped = true;
    if (this.reconnectTimer !== null) window.clearTimeout(this.reconnectTimer);
    this.socket?.close();
    void this.persistence.destroy();
    this.doc.destroy();
  }

  applyDocument(document: EditorDocument, kind = "document.patch"): void {
    writeEditorDocument(this.doc, document, kind);
  }

  undo(): void {
    // Field-level writes are intentionally grouped by Yjs transactions; callers can replace this with
    // a scoped UndoManager once gesture-level events are emitted by Fabric.
    this.applyDocument(readEditorDocument(this.doc), "undo");
  }

  async flush(): Promise<void> {
    if (this.pending.size === 0) return;
    await new Promise<void>((resolve) => {
      const timer = window.setInterval(() => {
        if (this.pending.size === 0) { window.clearInterval(timer); resolve(); }
      }, 25);
      window.setTimeout(() => { window.clearInterval(timer); resolve(); }, 5_000);
    });
  }

  get status(): CollaborationStatus { return this.statusValue; }
  get lastServerSeq(): string { return this.serverSeq; }

  private handleMessage(message: CollaborationMessage): void {
    if (message.type === "welcome") {
      this.serverSeq = message.serverSeq ?? this.serverSeq;
      for (const record of message.updates ?? []) this.applyRemoteRecord(record);
      for (const operationId of this.pending.keys()) this.sendPending(operationId);
      return;
    }
    if (message.type === "update") { this.applyRemoteRecord(message); return; }
    if (message.type === "ack" && message.operationId) {
      this.pending.delete(message.operationId);
      if (message.serverSeq) this.serverSeq = maxSeq(this.serverSeq, message.serverSeq);
      return;
    }
    if (message.type === "error") this.setStatus("error");
  }

  private applyRemoteRecord(record: Record<string, unknown>): void {
    const encoded = typeof record.yjsUpdate === "string" ? record.yjsUpdate : "";
    if (!encoded) return;
    const seq = String(record.serverSeq ?? this.serverSeq);
    this.serverSeq = maxSeq(this.serverSeq, seq);
    Y.applyUpdate(this.doc, fromBase64(encoded), REMOTE_ORIGIN);
  }

  private sendPending(operationId: string): void {
    const pending = this.pending.get(operationId);
    if (!pending || !this.socket || this.socket.readyState !== WebSocket.OPEN) return;
    this.send({ type: "update", roomEpoch: this.session.roomEpoch, operationId, gestureId: operationId,
      kind: "document.patch", targetId: null, changedFields: ["editorState"], phase: "commit",
      yjsUpdate: toBase64(pending.update) });
  }

  private send(message: Record<string, unknown>): void { this.socket?.send(JSON.stringify(message)); }

  private setStatus(status: CollaborationStatus): void {
    this.statusValue = status;
    this.statusListener?.(status);
  }
}

function createId(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) return crypto.randomUUID();
  return `op-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function toBase64(value: Uint8Array): string {
  let binary = "";
  value.forEach((part) => { binary += String.fromCharCode(part); });
  return btoa(binary);
}

function fromBase64(value: string): Uint8Array {
  const binary = atob(value);
  return Uint8Array.from(binary, (part) => part.charCodeAt(0));
}

function maxSeq(left: string, right: string): string {
  try { return BigInt(right) > BigInt(left) ? right : left; } catch { return right; }
}
