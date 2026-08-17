"use client";

import { DeleteOutlined, DownOutlined, DragOutlined, UpOutlined } from "@ant-design/icons";
import {
  Button,
  ColorPicker,
  Empty,
  Input,
  InputNumber,
  List,
  Slider,
  Tabs,
  Typography,
} from "antd";
import type { AdjustmentKey, EditorAdjustments, EditorLayer } from "@/features/editor/model/types";
import { adjustmentRange } from "@/features/editor/model/document";

interface EditorInspectorProps {
  adjustments: EditorAdjustments;
  layers: EditorLayer[];
  selectedLayer: EditorLayer | null;
  onAdjustmentPreview: (key: AdjustmentKey, value: number) => void;
  onAdjustmentCommit: (key: AdjustmentKey, value: number) => void;
  onLayerSelect: (id: string) => void;
  onLayerChange: (id: string, patch: Partial<EditorLayer>) => void;
  onLayerDelete: (id: string) => void;
  onLayerMove: (id: string, direction: "up" | "down") => void;
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
  layers,
  selectedLayer,
  onAdjustmentPreview,
  onAdjustmentCommit,
  onLayerSelect,
  onLayerChange,
  onLayerDelete,
  onLayerMove,
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
          key: "layers",
          label: `图层 ${layers.length}`,
          children:
            layers.length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="使用文字或画笔创建图层" />
            ) : (
              <List
                className="editor-layer-list"
                dataSource={[...layers].reverse()}
                renderItem={(layer, index) => (
                  <List.Item
                    className={
                      selectedLayer?.id === layer.id
                        ? "editor-layer-item is-selected"
                        : "editor-layer-item"
                    }
                    onClick={() => onLayerSelect(layer.id)}
                    actions={[
                      <Button
                        key="up"
                        type="text"
                        size="small"
                        icon={<UpOutlined />}
                        aria-label="图层上移"
                        disabled={index === 0}
                        onClick={(event) => {
                          event.stopPropagation();
                          onLayerMove(layer.id, "up");
                        }}
                      />,
                      <Button
                        key="down"
                        type="text"
                        size="small"
                        icon={<DownOutlined />}
                        aria-label="图层下移"
                        disabled={index === layers.length - 1}
                        onClick={(event) => {
                          event.stopPropagation();
                          onLayerMove(layer.id, "down");
                        }}
                      />,
                    ]}
                  >
                    <List.Item.Meta
                      avatar={
                        <span className="editor-layer-icon">
                          <DragOutlined />
                        </span>
                      }
                      title={
                        layer.type === "text"
                          ? layer.text || "文字"
                          : layer.tool === "eraser"
                            ? "擦除笔迹"
                            : "涂鸦笔迹"
                      }
                      description={layer.type === "text" ? "文字图层" : "绘画图层"}
                    />
                  </List.Item>
                )}
              />
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
          颜色
          <ColorPicker
            value={layer.color}
            onChange={(color) => onChange(layer.id, { color: color.toHexString() })}
          />
        </label>
        <Button danger icon={<DeleteOutlined />} onClick={() => onDelete(layer.id)}>
          删除图层
        </Button>
      </div>
    );
  }
  return (
    <div className="editor-text-properties">
      <Typography.Text type="secondary">
        绘画图层 · {layer.tool === "eraser" ? "擦除" : layer.tool === "marker" ? "马克笔" : "画笔"}
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
      <Button danger icon={<DeleteOutlined />} onClick={() => onDelete(layer.id)}>
        删除图层
      </Button>
    </div>
  );
}
