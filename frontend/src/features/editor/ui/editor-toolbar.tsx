"use client";

import {
  ClearOutlined,
  DownloadOutlined,
  EditOutlined,
  FontSizeOutlined,
  HighlightOutlined,
  HistoryOutlined,
  RedoOutlined,
  RotateLeftOutlined,
  RotateRightOutlined,
  ScissorOutlined,
  SaveOutlined,
  SelectOutlined,
  UndoOutlined,
  ZoomInOutlined,
  ZoomOutOutlined,
} from "@ant-design/icons";
import { Button, Space, Tooltip } from "antd";
import type { ReactNode } from "react";
import type { EditorTool } from "@/features/editor/model/types";

interface EditorToolbarProps {
  zoom: number;
  canUndo: boolean;
  canRedo: boolean;
  onUndo: () => void;
  onRedo: () => void;
  onRotate: (delta: number) => void;
  onZoom: (delta: number) => void;
  onSaveVersion: () => void;
  onOpenVersions: () => void;
  onDownload: () => void;
}

const tools: Array<{ value: EditorTool; label: string; icon: ReactNode }> = [
  { value: "select", label: "选择", icon: <SelectOutlined /> },
  { value: "crop", label: "裁切", icon: <ScissorOutlined /> },
  { value: "pen", label: "画笔", icon: <EditOutlined /> },
  { value: "marker", label: "马克笔", icon: <HighlightOutlined /> },
  { value: "eraser", label: "擦除", icon: <ClearOutlined /> },
  { value: "text", label: "文字", icon: <FontSizeOutlined /> },
];

export function EditorToolRail({
  tool,
  onToolChange,
}: {
  tool: EditorTool;
  onToolChange: (tool: EditorTool) => void;
}) {
  return (
    <div className="editor-tool-rail" role="toolbar" aria-label="编辑工具">
      {tools.map((item) => (
        <Tooltip title={item.label} placement="right" key={item.value}>
          <Button
            type={tool === item.value ? "primary" : "text"}
            icon={item.icon}
            aria-label={item.label}
            onClick={() => onToolChange(item.value)}
          >
            <span>{item.label}</span>
          </Button>
        </Tooltip>
      ))}
    </div>
  );
}

export function EditorToolbar({
  zoom,
  canUndo,
  canRedo,
  onUndo,
  onRedo,
  onRotate,
  onZoom,
  onSaveVersion,
  onOpenVersions,
  onDownload,
}: EditorToolbarProps) {
  return (
    <div className="editor-toolbar" role="toolbar" aria-label="画布命令">
      <div className="editor-tool-group">
        <Tooltip title="向左旋转"><Button type="text" icon={<RotateLeftOutlined />} aria-label="向左旋转" onClick={() => onRotate(-90)} /></Tooltip>
        <Tooltip title="向右旋转"><Button type="text" icon={<RotateRightOutlined />} aria-label="向右旋转" onClick={() => onRotate(90)} /></Tooltip>
      </div>

      <div className="editor-zoom-control" aria-label="画布缩放">
        <Tooltip title="缩小"><Button type="text" icon={<ZoomOutOutlined />} aria-label="缩小" onClick={() => onZoom(-0.1)} /></Tooltip>
        <span>{Math.round(zoom * 100)}%</span>
        <Tooltip title="放大"><Button type="text" icon={<ZoomInOutlined />} aria-label="放大" onClick={() => onZoom(0.1)} /></Tooltip>
      </div>

      <div className="editor-tool-group">
        <Tooltip title="撤销"><Button type="text" icon={<UndoOutlined />} aria-label="撤销" disabled={!canUndo} onClick={onUndo} /></Tooltip>
        <Tooltip title="重做"><Button type="text" icon={<RedoOutlined />} aria-label="重做" disabled={!canRedo} onClick={onRedo} /></Tooltip>
      </div>

      <div className="editor-toolbar-spacer" />

      <Space size={8} className="editor-header-actions">
        <Button icon={<HistoryOutlined />} aria-label="版本历史" onClick={onOpenVersions}>版本历史</Button>
        <Button icon={<DownloadOutlined />} aria-label="导出" onClick={onDownload}>导出</Button>
        <Button type="primary" icon={<SaveOutlined />} aria-label="保存版本" onClick={onSaveVersion}>保存版本</Button>
      </Space>
    </div>
  );
}
