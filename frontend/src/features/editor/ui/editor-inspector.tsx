"use client";

import { DeleteOutlined, SwapOutlined } from "@ant-design/icons";
import { Button, ColorPicker, Empty, Input, InputNumber, Slider, Tabs, Typography } from "antd";
import type { AdjustmentKey, EditorAdjustments, EditorLayer } from "@/features/editor/model/types";
import { adjustmentRange } from "@/features/editor/model/document";

interface EditorInspectorProps {
  adjustments: EditorAdjustments;
  selectedLayer: EditorLayer | null;
  onAdjustmentPreview: (key: AdjustmentKey, value: number) => void;
  onAdjustmentCommit: (key: AdjustmentKey, value: number) => void;
  onLayerChange: (id: string, patch: Partial<EditorLayer>) => void;
  onLayerDelete: (id: string) => void;
}

const adjustmentItems: Array<{ key: AdjustmentKey; label: string }> = [
  { key: "exposure", label: "曝光" },
  { key: "brightness", label: "亮度" },
  { key: "contrast", label: "对比度" },
  { key: "highlights", label: "高光" },
  { key: "shadows", label: "阴影" },
  { key: "saturation", label: "饱和度" },
  { key: "vibrance", label: "自然饱和度" },
  { key: "temperature", label: "色温" },
  { key: "tint", label: "色调" },
  { key: "sharpness", label: "锐度" },
  { key: "fade", label: "褪色" },
  { key: "vignette", label: "暗角" },
  { key: "enhance", label: "增强" },
  { key: "dehaze", label: "去雾" },
];

export function EditorInspector({
  adjustments,
  selectedLayer,
  onAdjustmentPreview,
  onAdjustmentCommit,
  onLayerChange,
  onLayerDelete,
}: EditorInspectorProps) {
  return (
    <Tabs
      className="editor-inspector"
      defaultActiveKey="adjustments"
      items={[
        {
          key: "adjustments",
          label: "图片调节",
          children: (
            <div className="editor-adjustments">
              {adjustmentItems.map((item) => {
                const range = adjustmentRange(item.key);
                return (
                  <div className="editor-adjustment-row" key={item.key}>
                    <span>{item.label}</span>
                    <Slider
                      ariaLabelForHandle={item.label}
                      min={range.min}
                      max={range.max}
                      step={1}
                      marks={adjustmentMarks(range.min)}
                      value={adjustments[item.key]}
                      onChange={(value) => onAdjustmentPreview(item.key, value)}
                      onChangeComplete={(value) => onAdjustmentCommit(item.key, value)}
                    />
                    <Typography.Text className="editor-adjustment-value">
                      {adjustments[item.key]}
                    </Typography.Text>
                  </div>
                );
              })}
            </div>
          ),
        },
        {
          key: "properties",
          label: "对象属性",
          children: selectedLayer ? (
            <LayerProperties
              layer={selectedLayer}
              onChange={onLayerChange}
              onDelete={onLayerDelete}
            />
          ) : (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="选择画布上的对象后编辑属性" />
          ),
        },
      ]}
    />
  );
}

function LayerProperties({
  layer,
  onChange,
  onDelete,
}: {
  layer: EditorLayer;
  onChange: (id: string, patch: Partial<EditorLayer>) => void;
  onDelete: (id: string) => void;
}) {
  const flipControls = (
    <div className="editor-layer-flips" aria-label="对象翻转">
      <Button
        type={layer.flipX ? "primary" : "default"}
        icon={<SwapOutlined />}
        onClick={() => onChange(layer.id, { flipX: !layer.flipX })}
      >
        水平翻转
      </Button>
      <Button
        type={layer.flipY ? "primary" : "default"}
        className="editor-flip-vertical"
        icon={<SwapOutlined />}
        onClick={() => onChange(layer.id, { flipY: !layer.flipY })}
      >
        垂直翻转
      </Button>
    </div>
  );
  if (layer.type === "text") {
    return (
      <div className="editor-text-properties">
        <label>
          内容
          <Input
            value={layer.text}
            onChange={(event) => onChange(layer.id, { text: event.target.value })}
          />
        </label>
        <label>
          字号
          <InputNumber
            min={12}
            max={240}
            value={layer.fontSize}
            onChange={(value) => onChange(layer.id, { fontSize: value ?? 32 })}
          />
        </label>
        <label>
          文本框宽度
          <InputNumber
            min={40}
            max={32768}
            value={Math.round(layer.width)}
            onChange={(value) => onChange(layer.id, { width: value ?? 120 })}
          />
        </label>
        <label>
          颜色
          <ColorPicker
            value={layer.color}
            onChange={(color) => onChange(layer.id, { color: color.toHexString() })}
          />
        </label>
        {flipControls}
        <Button danger icon={<DeleteOutlined />} onClick={() => onDelete(layer.id)}>
          删除对象
        </Button>
      </div>
    );
  }
  return (
    <div className="editor-text-properties">
      <Typography.Text type="secondary">
        绘画对象 · {layer.tool === "eraser" ? "擦除" : layer.tool === "marker" ? "马克笔" : "画笔"}
      </Typography.Text>
      <label>
        颜色
        <ColorPicker
          value={layer.color}
          disabled={layer.tool === "eraser"}
          onChange={(color) => onChange(layer.id, { color: color.toHexString() })}
        />
      </label>
      <label>
        粗细
        <InputNumber
          min={1}
          max={100}
          value={layer.size}
          onChange={(value) => onChange(layer.id, { size: value ?? 4 })}
        />
      </label>
      {flipControls}
      <Button danger icon={<DeleteOutlined />} onClick={() => onDelete(layer.id)}>
        删除对象
      </Button>
    </div>
  );
}

function adjustmentMarks(min: number): Record<number, string> {
  return min < 0 ? { [-50]: "-50", 0: "0", 50: "50" } : { 0: "0", 50: "50", 100: "100" };
}
