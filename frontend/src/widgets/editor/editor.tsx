"use client";

import {
  useCallback,
  useDeferredValue,
  useEffect,
  useMemo,
  useReducer,
  useRef,
  useState,
} from "react";
import { ArrowLeftOutlined } from "@ant-design/icons";
import {
  Alert,
  App,
  Button,
  ColorPicker,
  Form,
  Input,
  InputNumber,
  Modal,
  Result,
  Segmented,
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
  useDeleteEditorDraft,
  useEditorBaseImage,
  useEditorDraft,
  useEditorPicture,
  useEditorVersions,
  useRestorePictureVersion,
  useSaveEditorResult,
  useSaveEditorDraft,
} from "@/features/editor/model/queries";
import type {
  RestoreVersionInput,
  SaveDraftInput,
  SaveEditorResultInput,
} from "@/features/editor/model/queries";
import {
  cloneDocument,
  createEmptyDocument,
  flipDocument,
  removeLayer,
  rotateDocument,
  setAdjustment,
  setCrop,
  updateLayer,
} from "@/features/editor/model/document";
import { exportEditorDocument } from "@/features/editor/model/render";
import { createEditorHistory, editorHistoryReducer } from "@/features/editor/model/history";
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
  EditorSaveMode,
  EditorSaveResult,
  EditorTool,
  PictureVersionDetail,
  PictureVersionSummary,
} from "@/features/editor/model/types";

interface EditorProps {
  pictureId: string;
}

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
  const saveEditorResult = useSaveEditorResult(pictureId);
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
      saveEditorResult={saveEditorResult}
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
  saveEditorResult: UseMutationResult<EditorSaveResult, Error, SaveEditorResultInput>;
  restoreVersion: UseMutationResult<PictureVersionDetail, Error, RestoreVersionInput>;
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
  saveEditorResult,
  restoreVersion,
  onRetryVersions,
}: EditorWorkspaceProps) {
  const { message } = App.useApp();
  const router = useRouter();
  const [history, dispatchHistory] = useReducer(
    editorHistoryReducer,
    initialDocument,
    createEditorHistory,
  );
  const document = history.present;
  const [viewZoom, setViewZoom] = useState(1);
  const [tool, setTool] = useState<EditorTool>("select");
  const [strokeColor, setStrokeColor] = useState("#3370ff");
  const [strokeSize, setStrokeSize] = useState(6);
  const [textColor, setTextColor] = useState("#ffffff");
  const [fontSize, setFontSize] = useState(32);
  const [selectedLayerId, setSelectedLayerId] = useState<string | null>(null);
  const [versionPanelOpen, setVersionPanelOpen] = useState(false);
  const [saveModalOpen, setSaveModalOpen] = useState(false);
  const [saveMode, setSaveMode] = useState<EditorSaveMode>("replace");
  const [copyName, setCopyName] = useState(() => defaultCopyName(picture.title));
  const [saveState, setSaveState] = useState<"idle" | "dirty" | "saving" | "saved" | "error">(
    "idle",
  );
  const [exitModalOpen, setExitModalOpen] = useState(false);
  const [exitAction, setExitAction] = useState<"save" | "discard" | null>(null);
  const [baseline] = useState<EditorDocument>(() => cloneDocument(initialDocument));
  const [baselineHadDraft] = useState(initialDraft !== null);
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

  const commit = useCallback((next: EditorDocument) => {
    setAdjustmentPreview(null);
    setSessionTouched(true);
    documentRef.current = cloneDocument(next);
    dispatchHistory({ type: "commit", document: next });
    setSaveState("dirty");
  }, []);

  const selectedLayer = useMemo(
    () => document.layers.find((layer) => layer.id === selectedLayerId) ?? null,
    [document.layers, selectedLayerId],
  );

  function undo() {
    const previous = history.past[history.past.length - 1];
    if (!previous) return;
    setSessionTouched(true);
    setAdjustmentPreview(null);
    documentRef.current = cloneDocument(previous);
    dispatchHistory({ type: "undo" });
    setSelectedLayerId(null);
    setSaveState("dirty");
  }

  function redo() {
    const next = history.future[0];
    if (!next) return;
    setSessionTouched(true);
    setAdjustmentPreview(null);
    documentRef.current = cloneDocument(next);
    dispatchHistory({ type: "redo" });
    setSelectedLayerId(null);
    setSaveState("dirty");
  }

  useEffect(() => {
    const handleHistoryShortcut = (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey) || event.altKey || event.key.toLowerCase() !== "z")
        return;
      const target = event.target as HTMLElement | null;
      if (target?.matches("input, textarea, [contenteditable='true']")) return;
      event.preventDefault();
      if (event.shiftKey) redo();
      else undo();
    };
    window.addEventListener("keydown", handleHistoryShortcut);
    return () => window.removeEventListener("keydown", handleHistoryShortcut);
  });

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

  function openSaveDialog() {
    setSaveMode("replace");
    setCopyName(defaultCopyName(picture.title));
    setSaveModalOpen(true);
  }

  async function handleSave() {
    if (!image) {
      message.error("原图尚未加载完成");
      return;
    }
    if (saveMode === "copy" && !copyName.trim()) {
      message.error("请输入新图片名称");
      return;
    }
    autosaveSuspendedRef.current = true;
    const snapshot = cloneDocument(effectiveDocument);
    try {
      await persistSnapshot(snapshot, true);
      const preview = await exportEditorDocument(snapshot, image, "image/png");
      const result = await saveEditorResult.mutateAsync({
        preview,
        mode: saveMode,
        name: copyName.trim(),
        expectedRevision: revisionRef.current,
      });
      setSessionTouched(false);
      setSaveState("saved");
      setSaveModalOpen(false);
      message.success(saveMode === "replace" ? "已替换当前图片" : "已另存为新图片");
      router.push(`/pictures/${result.pictureId}`);
    } catch (error) {
      message.error(errorMessage(error, "图片保存失败"));
    } finally {
      autosaveSuspendedRef.current = false;
    }
  }

  async function handleRestore(versionId: string) {
    autosaveSuspendedRef.current = true;
    try {
      await persistSnapshot(effectiveDocument, true);
      await restoreVersion.mutateAsync({
        versionId,
        expectedRevision: revisionRef.current,
      });
      setSessionTouched(false);
      setVersionPanelOpen(false);
      setSaveState("saved");
      message.success("已恢复为当前版本");
      router.push(`/pictures/${picture.id}`);
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
              type="default"
              icon={<ArrowLeftOutlined />}
              aria-label="退出编辑"
              loading={exitAction !== null}
              onClick={handleExit}
            >
              退出
            </Button>
          </Tooltip>
          <span className="editor-brand-mark">茶</span>
          <div className="editor-title-copy">
            <Typography.Text strong>{picture.title}</Typography.Text>
            <Typography.Text className="editor-save-state">{saveLabel}</Typography.Text>
          </div>
        </div>
        <EditorToolbar
          zoom={viewZoom}
          canUndo={history.past.length > 0}
          canRedo={history.future.length > 0}
          onUndo={undo}
          onRedo={redo}
          onRotate={(delta) => commit(rotateDocument(document, delta))}
          onFlip={(axis) => commit(flipDocument(document, axis))}
          onZoom={(delta) => setViewZoom((value) => Math.min(4, Math.max(0.25, value + delta)))}
          onFitZoom={() => setViewZoom(1)}
          onSave={openSaveDialog}
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
          viewZoom={viewZoom}
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
            selectedLayer={selectedLayer}
            onAdjustmentPreview={handleAdjustmentPreview}
            onAdjustmentCommit={handleAdjustmentCommit}
            onLayerChange={handleLayerChange}
            onLayerDelete={handleLayerDelete}
          />
        </aside>
      </div>

      <div className="editor-mobile-status">
        <Space size={8}>
          <span className="editor-status-dot" />
          {saveLabel}
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
        title="保存图片"
        open={saveModalOpen}
        okText="确认保存"
        cancelText="取消"
        confirmLoading={saveEditorResult.isPending}
        closable={!saveEditorResult.isPending}
        maskClosable={!saveEditorResult.isPending}
        onCancel={() => setSaveModalOpen(false)}
        onOk={() => void handleSave()}
      >
        <Form layout="vertical">
          <Form.Item label="保存方式">
            <Segmented
              block
              options={[
                { label: "替换当前图片", value: "replace" },
                { label: "另存为新图片", value: "copy" },
              ]}
              value={saveMode}
              onChange={(value) => setSaveMode(value as EditorSaveMode)}
            />
          </Form.Item>
          {saveMode === "replace" ? (
            <Alert
              type="warning"
              showIcon
              message="当前图片将被编辑结果替换"
              description="系统会自动保留历史版本；如图片已公开或正在审核，将恢复为私有状态。"
            />
          ) : (
            <Form.Item label="新图片名称" htmlFor="editor-copy-name" required>
              <Input
                id="editor-copy-name"
                autoFocus
                value={copyName}
                onChange={(event) => setCopyName(event.target.value)}
                maxLength={128}
                showCount
              />
            </Form.Item>
          )}
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

function defaultCopyName(sourceName: string): string {
  const suffix = " - 副本";
  const base = sourceName.trim() || "未命名图片";
  return `${base.slice(0, 128 - suffix.length)}${suffix}`;
}

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}
