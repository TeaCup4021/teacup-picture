import * as Y from "yjs";
import { IndexeddbPersistence } from "y-indexeddb";
import { patchEditorDocument, readEditorDocument, REMOTE_ORIGIN, writeEditorDocument } from "@/features/editor/collaboration/document";
import type { CollaborationSession, CollaborationStatus } from "@/features/editor/collaboration/types";
import type { EditorDocument } from "@/features/editor/model/types";

const STROKE_PREVIEW_ORIGIN = "teacup-stroke-preview";

interface PendingUpdate {
  operationId: string;
  update: Uint8Array;
  kind: string;
  targetId: string | null;
  changedFields: string[];
  gestureId: string;
  phase: "preview" | "commit";
  lockToken?: string;
}

interface CollaborationMessage {
  type: string;
  operationId?: string;
  serverSeq?: string;
  roomEpoch?: string;
  updates?: Array<Record<string, unknown>>;
  snapshotYjsState?: string;
  snapshotServerSeq?: string;
  presence?: string[];
  yjsUpdate?: string;
  baselineEditorState?: EditorDocument;
  requestId?: string;
  targetId?: string;
  lockToken?: string;
  [key: string]: unknown;
}

export class CollaborationConnection {
  readonly doc: Y.Doc;
  private readonly undoManager: Y.UndoManager;
  private readonly persistence: IndexeddbPersistence;
  private readonly session: CollaborationSession;
  private readonly pending = new Map<string, PendingUpdate>();
  private readonly ready: Promise<void>;
  private socket: WebSocket | null = null;
  private stopped = false;
  private reconnectTimer: number | null = null;
  private serverSeq: string;
  private statusValue: CollaborationStatus = "connecting";
  private statusListener: ((status: CollaborationStatus) => void) | null = null;
  private documentListener: ((document: EditorDocument) => void) | null = null;
  private historyListener: (() => void) | null = null;
  private presenceListener: ((count: number) => void) | null = null;
  private readonly actors = new Set<string>();
  private readonly lockTokens = new Map<string, string>();
  private readonly lockRequests = new Map<string, string>();
  private readonly lockRenewTimers = new Map<string, number>();
  private awarenessPayload: Record<string, unknown> = {};
  private nextOperation = { kind: "document.patch", targetId: null as string | null, changedFields: ["editorState"], gestureId: createId(), phase: "commit" as "preview" | "commit" };

  constructor(pictureId: string, session: CollaborationSession, initial: EditorDocument) {
    this.session = session;
    this.serverSeq = session.lastServerSeq;
    this.doc = new Y.Doc();
    this.persistence = new IndexeddbPersistence(`teacup-picture:${pictureId}:${session.roomEpoch}`, this.doc);
    this.undoManager = new Y.UndoManager([
      this.doc.getMap("canvas"), this.doc.getMap("transform"), this.doc.getMap("crop"),
      this.doc.getMap("adjustments"), this.doc.getMap("layers"), this.doc.getArray("layerOrder"),
    ], {
      captureTimeout: 500,
      trackedOrigins: new Set(["document.patch", "layer.patch", "layer.delete", "adjustment.set", "crop.commit", "document.transform"]),
    });
    this.ready = this.persistence.whenSynced.then(() => {
      if (this.doc.getMap("metadata").size === 0) writeEditorDocument(this.doc, session.baselineEditorState ?? initial, null);
    });
    this.doc.on("update", (update: Uint8Array, origin: unknown) => {
      if (origin !== STROKE_PREVIEW_ORIGIN) this.documentListener?.(readEditorDocument(this.doc));
      if (origin === REMOTE_ORIGIN || origin === null) return;
      const operationId = createId();
      const operation = this.nextOperation;
      this.pending.set(operationId, { operationId, update, ...operation });
      this.sendPending(operationId);
    });
  }

  setListeners(listeners: { onStatus?: (status: CollaborationStatus) => void; onDocument?: (document: EditorDocument) => void; onHistory?: () => void; onPresence?: (count: number) => void }): void {
    this.statusListener = listeners.onStatus ?? null;
    this.documentListener = listeners.onDocument ?? null;
    this.historyListener = listeners.onHistory ?? null;
    this.presenceListener = listeners.onPresence ?? null;
    void this.ready.then(() => this.documentListener?.(readEditorDocument(this.doc)));
  }

  connect(): void {
    void this.ready.then(() => {
      if (this.stopped) return;
      this.stopped = false;
      this.setStatus("connecting");
      const baseUrl = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://127.0.0.1:8123/api/v1";
      const url = new URL(this.session.wsPath ?? "", baseUrl);
      url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
      this.socket = new WebSocket(url.toString());
      this.socket.onopen = () => {
        this.setStatus("connected");
        // Start from zero so a fresh tab receives the durable log. Yjs de-duplicates
        // updates that are already present in IndexedDB.
        this.send({ type: "hello", roomEpoch: this.session.roomEpoch, lastServerSeq: "0" });
        this.sendAwareness();
      };
      this.socket.onmessage = (event) => this.handleMessage(JSON.parse(String(event.data)) as CollaborationMessage);
      this.socket.onerror = () => this.setStatus("error");
      this.socket.onclose = () => {
        if (this.stopped) return;
        this.setStatus("reconnecting");
        this.reconnectTimer = window.setTimeout(() => this.connect(), 1_000);
      };
    });
  }

  stop(): void {
    this.stopped = true;
    if (this.reconnectTimer !== null) window.clearTimeout(this.reconnectTimer);
    this.socket?.close();
    for (const timer of this.lockRenewTimers.values()) window.clearInterval(timer);
    this.lockRenewTimers.clear();
    this.doc.destroy();
  }

  applyDocument(document: EditorDocument, kind = "document.patch", targetId: string | null = null, changedFields = ["editorState"], phase: "preview" | "commit" = "commit", gestureId = createId()): void {
    this.nextOperation = { kind, targetId, changedFields, phase, gestureId };
    patchEditorDocument(this.doc, document, kind);
  }

  applyStrokeChunk(document: EditorDocument, targetId: string): void {
    this.nextOperation = { kind: "stroke.chunk", targetId, changedFields: [`layers.${targetId}.path`], phase: "preview", gestureId: targetId };
    patchEditorDocument(this.doc, document, STROKE_PREVIEW_ORIGIN);
  }

  undo(): void {
    this.nextOperation = { kind: "undo", targetId: null, changedFields: ["editorState"], phase: "commit", gestureId: createId() };
    this.undoManager.undo();
    this.historyListener?.();
  }

  redo(): void {
    this.nextOperation = { kind: "redo", targetId: null, changedFields: ["editorState"], phase: "commit", gestureId: createId() };
    this.undoManager.redo();
    this.historyListener?.();
  }

  get canUndo(): boolean { return this.undoManager.canUndo(); }
  get canRedo(): boolean { return this.undoManager.canRedo(); }

  setAwareness(payload: Record<string, unknown>): void {
    this.awarenessPayload = payload;
    this.sendAwareness();
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

  encodeState(): string {
    const update = Y.encodeStateAsUpdate(this.doc);
    let binary = "";
    update.forEach((part) => { binary += String.fromCharCode(part); });
    return btoa(binary);
  }

  async checkpointHashes(editorState: EditorDocument): Promise<{ editorStateHash: string; yjsStateHash: string }> {
    return {
      editorStateHash: await sha256Hex(JSON.stringify(editorState)),
      yjsStateHash: await sha256Hex(Y.encodeStateAsUpdate(this.doc)),
    };
  }

  private handleMessage(message: CollaborationMessage): void {
    if (message.type === "welcome") {
      this.actors.clear();
      for (const actorId of message.presence ?? []) this.actors.add(actorId);
      this.presenceListener?.(this.actors.size);
      if (this.doc.getMap("metadata").size === 0 && message.baselineEditorState) writeEditorDocument(this.doc, message.baselineEditorState, null);
      if (message.snapshotYjsState) Y.applyUpdate(this.doc, fromBase64(message.snapshotYjsState), REMOTE_ORIGIN);
      this.serverSeq = maxSeq(message.snapshotServerSeq ?? this.serverSeq, message.serverSeq ?? this.serverSeq);
      for (const record of message.updates ?? []) this.applyRemoteRecord(record);
      for (const operationId of this.pending.keys()) this.sendPending(operationId);
      return;
    }
    if (message.type === "updates") {
      for (const record of message.updates ?? []) this.applyRemoteRecord(record);
      if (message.serverSeq) this.serverSeq = maxSeq(this.serverSeq, message.serverSeq);
      return;
    }
    if (message.type === "update") { this.applyRemoteRecord(message); return; }
    if (message.type === "ack" && message.operationId) {
      const completed = this.pending.get(message.operationId);
      this.pending.delete(message.operationId);
      if (message.serverSeq) this.serverSeq = maxSeq(this.serverSeq, message.serverSeq);
      if (completed?.targetId && completed.phase === "commit") this.releaseLock(completed.targetId);
      return;
    }
    if (message.type === "lock.granted" && message.requestId && message.targetId && message.lockToken) {
      this.lockRequests.delete(message.requestId);
      this.lockTokens.set(message.targetId, message.lockToken);
      const timer = window.setInterval(() => {
        if (this.socket?.readyState === WebSocket.OPEN) this.send({ type: "lock.renew", targetId: message.targetId, lockToken: message.lockToken });
      }, 5_000);
      this.lockRenewTimers.set(message.targetId, timer);
      for (const pending of this.pending.values()) if (pending.targetId === message.targetId) this.sendPending(pending.operationId);
      return;
    }
    if (message.type === "lock.denied") {
      const operationId = typeof message.operationId === "string"
        ? message.operationId
        : message.requestId ? this.lockRequests.get(message.requestId) : undefined;
      if (message.requestId) this.lockRequests.delete(message.requestId);
      if (operationId) this.rollbackPending(operationId);
      return;
    }
    if (message.type === "presence") {
      const actorId = typeof message.actorId === "string" ? message.actorId : null;
      if (actorId) {
        if (message.event === "left") this.actors.delete(actorId);
        else this.actors.add(actorId);
        this.presenceListener?.(this.actors.size);
      }
      return;
    }
    if (message.type === "awareness") return;
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
    if (pending.targetId) {
      const token = this.lockTokens.get(pending.targetId);
      if (!token) {
        const existing = [...this.lockRequests.entries()].find(([, id]) => id === operationId);
        if (!existing) {
          const requestId = createId();
          this.lockRequests.set(requestId, operationId);
          this.send({ type: "lock.acquire", requestId, targetId: pending.targetId });
        }
        return;
      }
      pending.lockToken = token;
    }
    this.send({ type: "update", roomEpoch: this.session.roomEpoch, operationId, gestureId: pending.gestureId,
      kind: pending.kind, targetId: pending.targetId, changedFields: pending.changedFields, phase: pending.phase,
      lockToken: pending.lockToken, yjsUpdate: toBase64(pending.update) });
  }

  private rollbackPending(operationId: string): void {
    if (!this.pending.delete(operationId)) return;
    this.nextOperation = { kind: "lock.rollback", targetId: null, changedFields: ["editorState"], phase: "commit", gestureId: createId() };
    this.undoManager.undo();
    this.documentListener?.(readEditorDocument(this.doc));
    this.setStatus("error");
  }

  private releaseLock(targetId: string): void {
    const token = this.lockTokens.get(targetId);
    if (token && this.socket?.readyState === WebSocket.OPEN) this.send({ type: "lock.release", targetId, lockToken: token });
    this.lockTokens.delete(targetId);
    const timer = this.lockRenewTimers.get(targetId);
    if (timer !== undefined) window.clearInterval(timer);
    this.lockRenewTimers.delete(targetId);
  }

  private send(message: Record<string, unknown>): void { this.socket?.send(JSON.stringify(message)); }

  private sendAwareness(): void {
    if (this.socket?.readyState !== WebSocket.OPEN) return;
    this.send({ type: "awareness", payload: this.awarenessPayload });
  }

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

async function sha256Hex(value: string | Uint8Array): Promise<string> {
  const bytes = typeof value === "string" ? new TextEncoder().encode(value) : value;
  const digest = await crypto.subtle.digest("SHA-256", new Uint8Array(bytes));
  return [...new Uint8Array(digest)].map((part) => part.toString(16).padStart(2, "0")).join("");
}

function maxSeq(left: string, right: string): string {
  try { return BigInt(right) > BigInt(left) ? right : left; } catch { return right; }
}
