"use client";

import {
  DownloadOutlined,
  EditOutlined,
  FontSizeOutlined,
  FullscreenOutlined,
  HighlightOutlined,
  HistoryOutlined,
  RedoOutlined,
  RotateLeftOutlined,
  RotateRightOutlined,
  ScissorOutlined,
  SaveOutlined,
  SelectOutlined,
  SwapOutlined,
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
  onFlip: (axis: "horizontal" | "vertical") => void;
  onZoom: (delta: number) => void;
  onFitZoom: () => void;
  onSave: () => void;
  onOpenVersions: () => void;
  onDownload: () => void;
}

const tools: Array<{ value: EditorTool; label: string; icon: ReactNode }> = [
  { value: "select", label: "选择", icon: <SelectOutlined /> },
  { value: "crop", label: "裁切", icon: <ScissorOutlined /> },
  { value: "pen", label: "画笔", icon: <EditOutlined /> },
  { value: "marker", label: "马克笔", icon: <HighlightOutlined /> },
  { value: "eraser", label: "擦除", icon: <EraserIcon /> },
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
            type={tool === item.value && item.value !== "eraser" ? "primary" : "text"}
            className={
              item.value === "eraser" && tool === item.value
                ? "editor-eraser-tool is-active"
                : item.value === "eraser"
                  ? "editor-eraser-tool"
                  : undefined
            }
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
  onFlip,
  onZoom,
  onFitZoom,
  onSave,
  onOpenVersions,
  onDownload,
}: EditorToolbarProps) {
  return (
    <div className="editor-toolbar" role="toolbar" aria-label="画布命令">
      <div className="editor-tool-group">
        <Tooltip title="向左旋转">
          <Button
            type="text"
            icon={<RotateLeftOutlined />}
            aria-label="向左旋转"
            onClick={() => onRotate(-90)}
          />
        </Tooltip>
        <Tooltip title="向右旋转">
          <Button
            type="text"
            icon={<RotateRightOutlined />}
            aria-label="向右旋转"
            onClick={() => onRotate(90)}
          />
        </Tooltip>
        <Tooltip title="水平翻转">
          <Button
            type="text"
            icon={<SwapOutlined />}
            aria-label="水平翻转画布"
            onClick={() => onFlip("horizontal")}
          />
        </Tooltip>
        <Tooltip title="垂直翻转">
          <Button
            type="text"
            className="editor-flip-vertical"
            icon={<SwapOutlined />}
            aria-label="垂直翻转画布"
            onClick={() => onFlip("vertical")}
          />
        </Tooltip>
      </div>

      <div className="editor-zoom-control" aria-label="画布缩放">
        <Tooltip title="缩小">
          <Button
            type="text"
            icon={<ZoomOutOutlined />}
            aria-label="缩小"
            onClick={() => onZoom(-0.1)}
          />
        </Tooltip>
        <Tooltip title="适应窗口">
          <Button
            type="text"
            icon={<FullscreenOutlined />}
            aria-label="适应窗口"
            onClick={onFitZoom}
          >
            {Math.round(zoom * 100)}%
          </Button>
        </Tooltip>
        <Tooltip title="放大">
          <Button
            type="text"
            icon={<ZoomInOutlined />}
            aria-label="放大"
            onClick={() => onZoom(0.1)}
          />
        </Tooltip>
      </div>

      <div className="editor-tool-group">
        <Tooltip title="撤销">
          <Button
            type="text"
            icon={<UndoOutlined />}
            aria-label="撤销"
            disabled={!canUndo}
            onClick={onUndo}
          />
        </Tooltip>
        <Tooltip title="重做">
          <Button
            type="text"
            icon={<RedoOutlined />}
            aria-label="重做"
            disabled={!canRedo}
            onClick={onRedo}
          />
        </Tooltip>
      </div>

      <div className="editor-toolbar-spacer" />

      <Space size={8} className="editor-header-actions">
        <Tooltip title="版本历史">
          <Button icon={<HistoryOutlined />} aria-label="版本历史" onClick={onOpenVersions}>
            版本历史
          </Button>
        </Tooltip>
        <Tooltip title="导出">
          <Button icon={<DownloadOutlined />} aria-label="导出" onClick={onDownload}>
            导出
          </Button>
        </Tooltip>
        <Tooltip title="保存图片">
          <Button type="primary" icon={<SaveOutlined />} aria-label="保存图片" onClick={onSave}>
            保存
          </Button>
        </Tooltip>
      </Space>
    </div>
  );
}

function EraserIcon() {
  return <span className="editor-eraser-icon" aria-hidden="true" />;
}
