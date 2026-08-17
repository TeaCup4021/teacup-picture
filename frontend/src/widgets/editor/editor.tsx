"use client";

import { useCallback, useDeferredValue, useEffect, useMemo, useRef, useState } from "react";
import { ArrowLeftOutlined } from "@ant-design/icons";
import {
  App,
  Button,
  ColorPicker,
  Form,
  Input,
  InputNumber,
  Modal,
  Result,
  Skeleton,
  Slider,
  Space,
  Tooltip,
  Typography,
} from "antd";
import type { UseMutationResult } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { usePrototypeSession } from "@/features/prototype";
import type { PrototypePicture } from "@/features/prototype";
import {
  useCreatePictureVersion,
  useDeleteEditorDraft,
  useEditorBaseImage,
  useEditorDraft,
  useEditorPicture,
  useEditorVersions,
  useRestorePictureVersion,
  useSaveEditorDraft,
} from "@/features/editor/model/queries";
import type { RestoreVersionInput, SaveDraftInput } from "@/features/editor/model/queries";
import {
  cloneDocument,
  createEmptyDocument,
  removeLayer,
  rotateDocument,
  scaleDocument,
  setAdjustment,
  setCrop,
  updateLayer,
} from "@/features/editor/model/document";
import { exportEditorDocument } from "@/features/editor/model/render";
import { EditorCanvas } from "@/features/editor/ui/editor-canvas";
import { EditorInspector } from "@/features/editor/ui/editor-inspector";
import { EditorToolbar, EditorToolRail } from "@/features/editor/ui/editor-toolbar";
import { VersionPanel } from "@/features/editor/ui/version-panel";
import type {
  AdjustmentKey,
  CropRect,
  EditorDocument,
  EditorDraft,
  EditorLayer,
  EditorTool,
  PictureVersionDetail,
  PictureVersionSummary,
  RestoreVersionResult,
} from "@/features/editor/model/types";

interface EditorProps {
  pictureId: string;
}

type CreateVersionInput = {
  document: EditorDocument;
  preview: Blob;
  name: string;
  note: string;
};

type AdjustmentPreview = {
  key: AdjustmentKey;
  value: number;
};

export function Editor({ pictureId }: EditorProps) {
  const session = usePrototypeSession();
  const picture = useEditorPicture(pictureId, Boolean(session.data));
  const baseImage = useEditorBaseImage(pictureId, Boolean(picture.data));
  const draft = useEditorDraft(pictureId, Boolean(picture.data));
  const versions = useEditorVersions(pictureId, Boolean(picture.data));
  const saveDraft = useSaveEditorDraft(pictureId);
  const deleteDraft = useDeleteEditorDraft(pictureId);
  const createVersion = useCreatePictureVersion(pictureId);
  const restoreVersion = useRestorePictureVersion(pictureId);

  if (session.isLoading)
    return (
      <main className="content-shell">
        <Skeleton active paragraph={{ rows: 12 }} />
      </main>
    );
  if (!session.data)
    return (
      <Result
        status="403"
        title="登录后编辑图片"
        extra={
          <Button type="primary" href="/login">
            去登录
          </Button>
        }
      />
    );
  if (picture.isLoading || draft.isLoading)
    return (
      <main className="content-shell">
        <Skeleton active paragraph={{ rows: 12 }} />
      </main>
    );
  if (picture.isError)
    return (
      <Result
        status="error"
        title="图片读取失败"
        subTitle="请稍后重试或返回个人空间"
        extra={<Button href="/spaces/personal">返回个人空间</Button>}
      />
    );
  if (draft.isError)
    return (
      <Result
        status="error"
        title="草稿读取失败"
        subTitle="当前编辑状态未加载，避免覆盖已有内容"
        extra={<Button onClick={() => void draft.refetch()}>重试</Button>}
      />
    );
  if (!picture.data) return <Result status="404" title="图片不存在或不可见" />;
  if (baseImage.isError)
    return (
      <Result
        status="error"
        title="原图读取失败"
        subTitle="编辑器不会在原图缺失时保存空白内容"
        extra={<Button onClick={() => void baseImage.refetch()}>重试</Button>}
      />
    );

  const initialDocument =
    draft.data?.editorState ?? createEmptyDocument(picture.data.width, picture.data.height);
  return (
    <EditorWorkspace
      picture={picture.data}
      image={baseImage.data ?? null}
      imageError={baseImage.isError}
      initialDocument={initialDocument}
      initialDraft={draft.data ?? null}
      versions={versions.data ?? []}
      versionsLoading={versions.isLoading}
      versionsError={versions.isError}
      saveDraft={saveDraft}
      deleteDraft={deleteDraft}
      createVersion={createVersion}
      restoreVersion={restoreVersion}
      onRetryVersions={() => void versions.refetch()}
    />
  );
}

interface EditorWorkspaceProps {
  picture: PrototypePicture;
  image: HTMLImageElement | null;
  imageError: boolean;
  initialDocument: EditorDocument;
  initialDraft: EditorDraft | null;
  versions: PictureVersionSummary[];
  versionsLoading: boolean;
  versionsError: boolean;
  saveDraft: UseMutationResult<EditorDraft, Error, SaveDraftInput>;
  deleteDraft: UseMutationResult<void, Error, string | null>;
  createVersion: UseMutationResult<PictureVersionDetail, Error, CreateVersionInput>;
  restoreVersion: UseMutationResult<RestoreVersionResult, Error, RestoreVersionInput>;
  onRetryVersions: () => void;
}

function EditorWorkspace({
  picture,
  image,
  imageError,
  initialDocument,
  initialDraft,
  versions,
  versionsLoading,
  versionsError,
  saveDraft,
  deleteDraft,
  createVersion,
  restoreVersion,
  onRetryVersions,
}: EditorWorkspaceProps) {
  const { message } = App.useApp();
  const router = useRouter();
  const [document, setDocument] = useState<EditorDocument>(() => cloneDocument(initialDocument));
  const [past, setPast] = useState<EditorDocument[]>([]);
  const [future, setFuture] = useState<EditorDocument[]>([]);
  const [tool, setTool] = useState<EditorTool>("select");
  const [strokeColor, setStrokeColor] = useState("#3370ff");
  const [strokeSize, setStrokeSize] = useState(6);
  const [textColor, setTextColor] = useState("#ffffff");
  const [fontSize, setFontSize] = useState(32);
  const [selectedLayerId, setSelectedLayerId] = useState<string | null>(null);
  const [versionPanelOpen, setVersionPanelOpen] = useState(false);
  const [saveModalOpen, setSaveModalOpen] = useState(false);
  const [versionName, setVersionName] = useState("");
  const [versionNote, setVersionNote] = useState("");
  const [saveState, setSaveState] = useState<"idle" | "dirty" | "saving" | "saved" | "error">(
    "idle",
  );
  const [exitModalOpen, setExitModalOpen] = useState(false);
  const [exitAction, setExitAction] = useState<"save" | "discard" | null>(null);
  const [baseline, setBaseline] = useState<EditorDocument>(() => cloneDocument(initialDocument));
  const [baselineHadDraft, setBaselineHadDraft] = useState(initialDraft !== null);
  const [sessionTouched, setSessionTouched] = useState(false);
  const [adjustmentPreview, setAdjustmentPreview] = useState<AdjustmentPreview | null>(null);
  const effectiveDocument = useMemo(
    () =>
      adjustmentPreview
        ? setAdjustment(document, adjustmentPreview.key, adjustmentPreview.value)
        : document,
    [adjustmentPreview, document],
  );
  const canvasDocument = useDeferredValue(effectiveDocument);
  const documentRef = useRef(document);
  const revisionRef = useRef<string | null>(initialDraft?.revision ?? null);
  const lastSavedRef = useRef<EditorDocument | null>(
    initialDraft ? cloneDocument(initialDraft.editorState) : null,
  );
  const saveQueueRef = useRef<Promise<void>>(Promise.resolve());
  const autosaveSuspendedRef = useRef(false);
  const saveDraftRef = useRef(saveDraft.mutateAsync);
  const deleteDraftRef = useRef(deleteDraft.mutateAsync);

  useEffect(() => {
    documentRef.current = effectiveDocument;
  }, [effectiveDocument]);

  useEffect(() => {
    saveDraftRef.current = saveDraft.mutateAsync;
  }, [saveDraft.mutateAsync]);

  useEffect(() => {
    deleteDraftRef.current = deleteDraft.mutateAsync;
  }, [deleteDraft.mutateAsync]);

  const enqueueDraftOperation = useCallback((operation: () => Promise<void>): Promise<void> => {
    const result = saveQueueRef.current.then(operation);
    saveQueueRef.current = result.catch(() => undefined);
    return result;
  }, []);

  const persistSnapshot = useCallback(
    (target: EditorDocument, force = false): Promise<void> => {
      const snapshot = cloneDocument(target);
      return enqueueDraftOperation(async () => {
        if (!force && lastSavedRef.current && documentsEqual(lastSavedRef.current, snapshot))
          return;
        setSaveState("saving");
        try {
          const saved = await saveDraftRef.current({
            document: snapshot,
            expectedRevision: revisionRef.current,
          });
          revisionRef.current = saved.revision;
          lastSavedRef.current = cloneDocument(saved.editorState);
          setSaveState(documentsEqual(documentRef.current, snapshot) ? "saved" : "dirty");
        } catch (error) {
          setSaveState("error");
          throw error;
        }
      });
    },
    [enqueueDraftOperation],
  );

  useEffect(() => {
    if (!sessionTouched || autosaveSuspendedRef.current) return;
    if (lastSavedRef.current && documentsEqual(lastSavedRef.current, document)) return;
    setSaveState("dirty");
    const timer = window.setTimeout(() => {
      if (!autosaveSuspendedRef.current) void persistSnapshot(document).catch(() => undefined);
    }, 800);
    return () => window.clearTimeout(timer);
  }, [document, persistSnapshot, sessionTouched]);

  const hasSessionChanges =
    (sessionTouched || adjustmentPreview !== null) && !documentsEqual(effectiveDocument, baseline);

  useEffect(() => {
    if (
      !hasSessionChanges &&
      saveState !== "dirty" &&
      saveState !== "saving" &&
      saveState !== "error"
    )
      return;
    const handleBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", handleBeforeUnload);
    return () => window.removeEventListener("beforeunload", handleBeforeUnload);
  }, [hasSessionChanges, saveState]);

  const commit = useCallback(
    (next: EditorDocument) => {
      setAdjustmentPreview(null);
      setSessionTouched(true);
      setPast((items) => [...items.slice(-49), cloneDocument(document)]);
      setFuture([]);
      setDocument(cloneDocument(next));
      setSaveState("dirty");
    },
    [document],
  );

  const selectedLayer = useMemo(
    () => document.layers.find((layer) => layer.id === selectedLayerId) ?? null,
    [document.layers, selectedLayerId],
  );

  function undo() {
    const previous = past[past.length - 1];
    if (!previous) return;
    setSessionTouched(true);
    setPast((items) => items.slice(0, -1));
    setFuture((items) => [cloneDocument(document), ...items]);
    setDocument(cloneDocument(previous));
    setSelectedLayerId(null);
    setSaveState("dirty");
  }

  function redo() {
    const next = future[0];
    if (!next) return;
    setSessionTouched(true);
    setFuture((items) => items.slice(1));
    setPast((items) => [...items, cloneDocument(document)]);
    setDocument(cloneDocument(next));
    setSelectedLayerId(null);
    setSaveState("dirty");
  }

  function handleDocumentChange(next: EditorDocument) {
    if (JSON.stringify(next) === JSON.stringify(document)) return;
    commit(next);
  }

  function handleAdjustmentPreview(key: AdjustmentKey, value: number) {
    if (document.adjustments[key] === value) {
      setAdjustmentPreview(null);
      return;
    }
    setAdjustmentPreview({ key, value });
  }

  function handleAdjustmentCommit(key: AdjustmentKey, value: number) {
    setAdjustmentPreview(null);
    if (document.adjustments[key] === value) return;
    commit(setAdjustment(document, key, value));
  }

  function handleLayerChange(id: string, patch: Partial<EditorLayer>) {
    commit(updateLayer(document, id, patch));
  }

  function handleLayerDelete(id: string) {
    commit(removeLayer(document, id));
    setSelectedLayerId(null);
  }

  function handleLayerMove(id: string, direction: "up" | "down") {
    const index = document.layers.findIndex((layer) => layer.id === id);
    const targetIndex = direction === "up" ? index + 1 : index - 1;
    if (index < 0 || targetIndex < 0 || targetIndex >= document.layers.length) return;
    const layers = [...document.layers];
    const current = layers[index];
    const target = layers[targetIndex];
    if (!current || !target) return;
    layers[index] = target;
    layers[targetIndex] = current;
    commit({ ...document, layers });
  }

  function handleCropApply(crop: CropRect) {
    commit(setCrop(document, crop));
    setTool("select");
    message.success("裁切已应用");
  }

  function handleCropCancel() {
    setTool("select");
  }

  async function handleDownload() {
    if (!image) {
      message.error("原图尚未加载完成");
      return;
    }
    try {
      const blob = await exportEditorDocument(effectiveDocument, image, "image/png");
      const url = URL.createObjectURL(blob);
      const anchor = window.document.createElement("a");
      anchor.href = url;
      anchor.download = `teacup-${picture.id}-edited.png`;
      anchor.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 0);
    } catch (error) {
      message.error(error instanceof Error ? error.message : "导出失败");
    }
  }

  async function handleSaveVersion() {
    if (!image) {
      message.error("原图尚未加载完成");
      return;
    }
    autosaveSuspendedRef.current = true;
    const snapshot = cloneDocument(effectiveDocument);
    try {
      await persistSnapshot(snapshot, true);
      const preview = await exportEditorDocument(snapshot, image, "image/png");
      await createVersion.mutateAsync({
        document: snapshot,
        preview,
        name: versionName.trim(),
        note: versionNote.trim(),
      });
      setBaseline(cloneDocument(snapshot));
      setBaselineHadDraft(true);
      setSessionTouched(false);
      setSaveState("saved");
      message.success("已保存正式版本");
      setSaveModalOpen(false);
      setVersionName("");
      setVersionNote("");
    } catch (error) {
      message.error(errorMessage(error, "版本保存失败"));
    } finally {
      autosaveSuspendedRef.current = false;
    }
  }

  async function handleRestore(versionId: string) {
    autosaveSuspendedRef.current = true;
    try {
      await persistSnapshot(effectiveDocument, true);
      const restored = await restoreVersion.mutateAsync({
        versionId,
        expectedRevision: revisionRef.current,
      });
      const restoredDocument = cloneDocument(restored.draft.editorState);
      revisionRef.current = restored.draft.revision;
      lastSavedRef.current = cloneDocument(restoredDocument);
      setBaseline(cloneDocument(restoredDocument));
      setBaselineHadDraft(true);
      setSessionTouched(false);
      setPast((items) => [...items.slice(-49), cloneDocument(document)]);
      setFuture([]);
      setDocument(restoredDocument);
      setSelectedLayerId(null);
      setVersionPanelOpen(false);
      setSaveState("saved");
      message.success("已恢复为当前版本");
    } catch (error) {
      message.error(errorMessage(error, "版本恢复失败"));
    } finally {
      autosaveSuspendedRef.current = false;
    }
  }

  async function discardSessionAndExit() {
    autosaveSuspendedRef.current = true;
    setExitAction("discard");
    try {
      await enqueueDraftOperation(async () => {
        setSaveState("saving");
        if (baselineHadDraft) {
          const saved = await saveDraftRef.current({
            document: cloneDocument(baseline),
            expectedRevision: revisionRef.current,
          });
          revisionRef.current = saved.revision;
          lastSavedRef.current = cloneDocument(saved.editorState);
        } else {
          await deleteDraftRef.current(revisionRef.current);
          revisionRef.current = null;
          lastSavedRef.current = null;
        }
      });
      setSessionTouched(false);
      setSaveState("saved");
      router.push(`/pictures/${picture.id}`);
    } catch (error) {
      autosaveSuspendedRef.current = false;
      setSaveState("error");
      setExitAction(null);
      message.error(errorMessage(error, "放弃修改失败，请重试"));
    }
  }

  async function saveSessionAndExit() {
    autosaveSuspendedRef.current = true;
    setExitAction("save");
    try {
      await persistSnapshot(effectiveDocument, true);
      setSessionTouched(false);
      router.push(`/pictures/${picture.id}`);
    } catch (error) {
      autosaveSuspendedRef.current = false;
      setExitAction(null);
      message.error(errorMessage(error, "保存草稿失败，请重试"));
    }
  }

  function handleExit() {
    if (hasSessionChanges) {
      setExitModalOpen(true);
      return;
    }
    if (sessionTouched) {
      void discardSessionAndExit();
      return;
    }
    router.push(`/pictures/${picture.id}`);
  }

  const saveLabel =
    saveState === "saving"
      ? "保存中…"
      : saveState === "saved"
        ? "已保存"
        : saveState === "error"
          ? "保存失败"
          : saveState === "dirty"
            ? "未保存"
            : "草稿";

  return (
    <main className="editor-frame">
      <header className="editor-header">
        <div className="editor-header-title">
          <Tooltip title="退出编辑">
            <Button
              className="editor-exit-button"
              type="text"
              icon={<ArrowLeftOutlined />}
              aria-label="退出编辑"
              loading={exitAction !== null}
              onClick={handleExit}
            />
          </Tooltip>
          <span className="editor-brand-mark">茶</span>
          <div className="editor-title-copy">
            <Typography.Text strong>{picture.title}</Typography.Text>
            <Typography.Text className="editor-save-state">{saveLabel}</Typography.Text>
          </div>
        </div>
        <EditorToolbar
          zoom={document.transform.scale}
          canUndo={past.length > 0}
          canRedo={future.length > 0}
          onUndo={undo}
          onRedo={redo}
          onRotate={(delta) => commit(rotateDocument(document, delta))}
          onZoom={(delta) => commit(scaleDocument(document, document.transform.scale + delta))}
          onSaveVersion={() => setSaveModalOpen(true)}
          onOpenVersions={() => setVersionPanelOpen(true)}
          onDownload={() => void handleDownload()}
        />
      </header>

      <div className="editor-body">
        <EditorToolRail tool={tool} onToolChange={setTool} />
        <EditorCanvas
          document={canvasDocument}
          image={imageError ? null : image}
          tool={tool}
          strokeColor={strokeColor}
          strokeSize={strokeSize}
          textColor={textColor}
          fontSize={fontSize}
          selectedLayerId={selectedLayerId}
          onSelectLayer={setSelectedLayerId}
          onDocumentChange={handleDocumentChange}
          onCropApply={handleCropApply}
          onCropCancel={handleCropCancel}
        />
        <aside className="editor-side-panel">
          <div className="editor-side-heading">
            <Typography.Title level={5}>属性面板</Typography.Title>
            <Typography.Text type="secondary">{toolName(tool)}</Typography.Text>
          </div>
          {tool === "pen" || tool === "marker" || tool === "eraser" ? (
            <div className="editor-stroke-panel">
              {tool !== "eraser" ? (
                <div className="editor-control-row">
                  <span>笔刷颜色</span>
                  <ColorPicker
                    value={strokeColor}
                    onChange={(color) => setStrokeColor(color.toHexString())}
                  />
                </div>
              ) : null}
              <div className="editor-control-row">
                <span>笔刷粗细</span>
                <Typography.Text type="secondary">{strokeSize}px</Typography.Text>
              </div>
              <Slider
                ariaLabelForHandle="笔刷粗细"
                min={1}
                max={80}
                value={strokeSize}
                onChange={setStrokeSize}
              />
            </div>
          ) : null}
          {tool === "text" ? (
            <div className="editor-stroke-panel">
              <div className="editor-control-row">
                <span>文字颜色</span>
                <ColorPicker
                  value={textColor}
                  onChange={(color) => setTextColor(color.toHexString())}
                />
              </div>
              <div className="editor-control-row">
                <span>文字字号</span>
                <InputNumber
                  aria-label="文字字号"
                  min={12}
                  max={240}
                  value={fontSize}
                  onChange={(value) => setFontSize(value ?? 32)}
                />
              </div>
            </div>
          ) : null}
          <EditorInspector
            adjustments={effectiveDocument.adjustments}
            layers={document.layers}
            selectedLayer={selectedLayer}
            onAdjustmentPreview={handleAdjustmentPreview}
            onAdjustmentCommit={handleAdjustmentCommit}
            onLayerSelect={setSelectedLayerId}
            onLayerChange={handleLayerChange}
            onLayerDelete={handleLayerDelete}
            onLayerMove={handleLayerMove}
          />
        </aside>
      </div>

      <div className="editor-mobile-status">
        <Space size={8}>
          <span className="editor-status-dot" />
          {saveLabel}
          <Typography.Text type="secondary">{document.layers.length} 个图层</Typography.Text>
        </Space>
      </div>

      <VersionPanel
        open={versionPanelOpen}
        versions={versions}
        loading={versionsLoading}
        error={versionsError}
        restoringId={restoreVersion.variables?.versionId ?? null}
        onRetry={onRetryVersions}
        onClose={() => setVersionPanelOpen(false)}
        onRestore={(version) => void handleRestore(version.id)}
      />

      <Modal
        title="保存正式版本"
        open={saveModalOpen}
        okText="保存版本"
        cancelText="取消"
        confirmLoading={createVersion.isPending}
        onCancel={() => setSaveModalOpen(false)}
        onOk={() => void handleSaveVersion()}
      >
        <Form layout="vertical">
          <Form.Item label="版本名称" htmlFor="editor-version-name">
            <Input
              id="editor-version-name"
              autoFocus
              value={versionName}
              onChange={(event) => setVersionName(event.target.value)}
              placeholder="例如：春季宣传图"
              maxLength={128}
            />
          </Form.Item>
          <Form.Item label="版本说明" htmlFor="editor-version-note">
            <Input.TextArea
              id="editor-version-note"
              value={versionNote}
              onChange={(event) => setVersionNote(event.target.value)}
              rows={3}
              maxLength={512}
            />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="退出编辑？"
        open={exitModalOpen}
        closable={exitAction === null}
        maskClosable={exitAction === null}
        keyboard={exitAction === null}
        onCancel={() => setExitModalOpen(false)}
        footer={[
          <Button
            key="continue"
            disabled={exitAction !== null}
            onClick={() => setExitModalOpen(false)}
          >
            继续编辑
          </Button>,
          <Button
            key="discard"
            danger
            loading={exitAction === "discard"}
            disabled={exitAction === "save"}
            onClick={() => void discardSessionAndExit()}
          >
            不保存并退出
          </Button>,
          <Button
            key="save"
            type="primary"
            loading={exitAction === "save"}
            disabled={exitAction === "discard"}
            onClick={() => void saveSessionAndExit()}
          >
            保存草稿并退出
          </Button>,
        ]}
      >
        <Typography.Paragraph>
          保存会保留当前草稿；不保存会回退到本次进入编辑器时，或最近一次正式版本操作后的状态。
        </Typography.Paragraph>
      </Modal>
    </main>
  );
}

function toolName(tool: EditorTool): string {
  if (tool === "crop") return "裁切";
  if (tool === "pen") return "画笔";
  if (tool === "marker") return "马克笔";
  if (tool === "eraser") return "擦除";
  if (tool === "text") return "文字";
  return "图片调节";
}

function documentsEqual(first: EditorDocument, second: EditorDocument): boolean {
  return JSON.stringify(first) === JSON.stringify(second);
}

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}
