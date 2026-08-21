"use client";

import { BgColorsOutlined, HistoryOutlined, SendOutlined } from "@ant-design/icons";
import {
  Alert,
  App,
  Button,
  Form,
  Input,
  Progress,
  Result,
  Segmented,
  Select,
  Skeleton,
  Slider,
} from "antd";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import {
  useAiModels,
  useAiQuotas,
  useCreateAiTask,
  canUseAi,
  type AiBackground,
  type AiOutputFormat,
  type AiTaskType,
} from "@/features/ai";
import { usePersonalPictures, usePrototypeSession } from "@/features/prototype";
import { PictureImage } from "@/features/prototype/ui/picture-image";

interface FormValues {
  modelCode: string;
  prompt: string;
  ratio: string;
  quality: string;
  background: AiBackground;
  outputFormat: AiOutputFormat;
  outputCompression?: number;
  sourcePictureId?: string;
  referencePictureId?: string;
}

const modeOptions = [{ label: "AI 绘图", value: "generate", icon: <BgColorsOutlined /> }];

const backgroundLabels: Record<AiBackground, string> = {
  auto: "自动",
  opaque: "实色",
  transparent: "透明",
};

const outputFormatLabels: Record<AiOutputFormat, string> = {
  png: "PNG",
  jpeg: "JPEG",
  webp: "WebP",
};

function newIdempotencyKey() {
  return (
    globalThis.crypto?.randomUUID?.() ?? `ai-${Date.now()}-${Math.random().toString(16).slice(2)}`
  );
}

export function AiCreate() {
  const { message } = App.useApp();
  const [form] = Form.useForm<FormValues>();
  const router = useRouter();
  const [mode, setMode] = useState<AiTaskType>("generate");
  const session = usePrototypeSession();
  const enabled = canUseAi(session.data?.role);
  const models = useAiModels(enabled);
  const quotas = useAiQuotas(enabled);
  const pictures = usePersonalPictures(enabled);
  const create = useCreateAiTask();
  const watchedModelCode = Form.useWatch("modelCode", form);
  const watchedSourcePictureId = Form.useWatch("sourcePictureId", form);
  const watchedBackground = Form.useWatch("background", form);
  const watchedOutputFormat = Form.useWatch("outputFormat", form);
  const compatibleModels = useMemo(
    () => (models.data ?? []).filter((model) => model.capabilities.includes(mode)),
    [mode, models.data],
  );
  const selectedModel = compatibleModels.find((model) => model.code === watchedModelCode);
  const selectedSource = pictures.data?.find((picture) => picture.id === watchedSourcePictureId);

  useEffect(() => {
    const model = selectedModel ?? compatibleModels[0];
    if (!model) return;
    const currentRatio = form.getFieldValue("ratio");
    const currentQuality = form.getFieldValue("quality");
    const currentBackground = form.getFieldValue("background");
    const currentOutputFormat = form.getFieldValue("outputFormat");
    form.setFieldsValue({
      modelCode: model.code,
      ratio: model.ratios.includes(currentRatio) ? currentRatio : model.ratios[0],
      quality: model.qualities.includes(currentQuality) ? currentQuality : model.qualities[0],
      background: model.backgrounds.includes(currentBackground)
        ? currentBackground
        : model.backgrounds[0],
      outputFormat: model.outputFormats.includes(currentOutputFormat)
        ? currentOutputFormat
        : model.outputFormats[0],
    });
  }, [compatibleModels, form, selectedModel]);

  useEffect(() => {
    if (watchedBackground === "transparent" && watchedOutputFormat === "jpeg") {
      form.setFieldValue("outputFormat", "png");
      return;
    }
    if (watchedOutputFormat === "png") {
      form.setFieldValue("outputCompression", undefined);
    } else if (watchedOutputFormat && form.getFieldValue("outputCompression") == null) {
      form.setFieldValue("outputCompression", 90);
    }
  }, [form, watchedBackground, watchedOutputFormat]);

  if (session.isLoading) {
    return (
      <main className="content-shell">
        <Skeleton active paragraph={{ rows: 12 }} />
      </main>
    );
  }
  if (!enabled) {
    return (
      <Result
        status="403"
        title="登录账号后使用 AI 创作"
        extra={
          <Button type="primary" href="/login">
            去登录
          </Button>
        }
      />
    );
  }

  const activeQuota = quotas.data?.quotas.find((quota) => quota.taskType === mode);
  const submit = (values: FormValues) => {
    create.mutate(
      { type: mode, ...values, idempotencyKey: newIdempotencyKey() },
      {
        onSuccess: (task) => {
          void message.success("AI 任务已创建");
          router.push(`/ai/tasks?task=${task.id}`);
        },
        onError: (error) => void message.error(error.message),
      },
    );
  };

  return (
    <main className="content-shell ai-create-shell">
      <section className="page-heading ai-page-heading" aria-labelledby="ai-create-title">
        <div>
          <p className="page-kicker">AI STUDIO</p>
          <h1 id="ai-create-title">AI 创作</h1>
          <p>生成结果自动保存到个人空间</p>
        </div>
        <Button href="/ai/tasks" icon={<HistoryOutlined />}>
          任务中心
        </Button>
      </section>
      <div className="ai-create-layout">
        <section className="ai-control-panel" aria-label="AI 创作参数">
          <Segmented
            block
            options={modeOptions}
            value={mode}
            onChange={(value) => {
              setMode(value as AiTaskType);
              form.resetFields(["sourcePictureId", "referencePictureId"]);
            }}
          />
          {activeQuota ? (
            <div className="ai-quota-block">
              <div>
                <span>今日剩余</span>
                <strong>
                  {activeQuota.remaining} / {activeQuota.dailyLimit}
                </strong>
              </div>
              <Progress
                percent={Math.round(
                  ((activeQuota.used + activeQuota.reserved) / activeQuota.dailyLimit) * 100,
                )}
                showInfo={false}
                size="small"
              />
            </div>
          ) : (
            <Skeleton active paragraph={{ rows: 1 }} />
          )}
          {models.isError || quotas.isError ? (
            <Alert
              type="error"
              showIcon
              title="AI 配置加载失败"
              action={
                <Button
                  size="small"
                  onClick={() => {
                    void models.refetch();
                    void quotas.refetch();
                  }}
                >
                  重试
                </Button>
              }
            />
          ) : null}
          <Form form={form} layout="vertical" requiredMark={false} onFinish={submit}>
            {mode === "outpaint" ? (
              <Form.Item
                label="待扩图片"
                name="sourcePictureId"
                rules={[{ required: true, message: "请选择待扩图片" }]}
              >
                <Select
                  showSearch
                  optionFilterProp="label"
                  placeholder="从个人空间选择"
                  options={(pictures.data ?? []).map((picture) => ({
                    value: picture.id,
                    label: picture.title,
                  }))}
                />
              </Form.Item>
            ) : null}
            <Form.Item
              label="模型"
              name="modelCode"
              rules={[{ required: true, message: "请选择模型" }]}
            >
              <Select
                options={compatibleModels.map((model) => ({
                  value: model.code,
                  label: `${model.name} · ${model.quotaCost} 次额度`,
                }))}
              />
            </Form.Item>
            <div className="form-row">
              <Form.Item label="图片比例" name="ratio" rules={[{ required: true }]}>
                <Select
                  options={(selectedModel?.ratios ?? []).map((value) => ({ value, label: value }))}
                />
              </Form.Item>
              <Form.Item label="清晰度" name="quality" rules={[{ required: true }]}>
                <Select
                  options={(selectedModel?.qualities ?? []).map((value) => ({
                    value,
                    label: value === "hd" ? "高清" : "标准",
                  }))}
                />
              </Form.Item>
            </div>
            <div className="form-row ai-output-row">
              <Form.Item label="背景" name="background" rules={[{ required: true }]}>
                <Select
                  options={(selectedModel?.backgrounds ?? []).map((value) => ({
                    label: backgroundLabels[value],
                    value,
                  }))}
                />
              </Form.Item>
              <Form.Item label="输出格式" name="outputFormat" rules={[{ required: true }]}>
                <Select
                  options={(selectedModel?.outputFormats ?? [])
                    .filter((value) => watchedBackground !== "transparent" || value !== "jpeg")
                    .map((value) => ({ label: outputFormatLabels[value], value }))}
                />
              </Form.Item>
            </div>
            {selectedModel?.supportsOutputCompression &&
            watchedOutputFormat &&
            watchedOutputFormat !== "png" ? (
              <Form.Item
                label={`${watchedOutputFormat.toUpperCase()} 质量`}
                name="outputCompression"
                rules={[{ required: true }]}
              >
                <Slider min={0} max={100} tooltip={{ formatter: (value) => `${value}%` }} />
              </Form.Item>
            ) : null}
            {selectedModel?.supportsReference && mode === "generate" ? (
              <Form.Item label="参考图片" name="referencePictureId">
                <Select
                  allowClear
                  showSearch
                  optionFilterProp="label"
                  placeholder="可选"
                  options={(pictures.data ?? []).map((picture) => ({
                    value: picture.id,
                    label: picture.title,
                  }))}
                />
              </Form.Item>
            ) : null}
            <Form.Item
              label="提示词"
              name="prompt"
              rules={[
                { required: true, message: "请输入提示词" },
                { max: 2000, message: "最多 2000 个字符" },
              ]}
            >
              <Input.TextArea
                rows={7}
                maxLength={2000}
                showCount
                placeholder={
                  mode === "generate"
                    ? "描述画面主体、环境、光线和风格"
                    : "描述希望补充到画面边缘的内容"
                }
              />
            </Form.Item>
            <Button
              block
              type="primary"
              size="large"
              htmlType="submit"
              icon={<SendOutlined />}
              loading={create.isPending}
              disabled={!compatibleModels.length || activeQuota?.remaining === 0}
            >
              创建任务
            </Button>
          </Form>
        </section>
        <section
          className="ai-canvas"
          aria-label={mode === "outpaint" ? "待扩图片预览" : "AI 创作画布"}
        >
          {mode === "outpaint" && selectedSource ? (
            <div className="ai-source-preview">
              <PictureImage alt={selectedSource.title} src={selectedSource.imageUrl} />
            </div>
          ) : (
            <div className="ai-canvas-empty">
              <BgColorsOutlined />
              <strong>{mode === "generate" ? "创作画布" : "选择待扩图片"}</strong>
              <span>
                {mode === "generate"
                  ? "任务提交后可在任务中心查看生成状态"
                  : "原图会保留，扩图结果另存为新图片"}
              </span>
            </div>
          )}
        </section>
      </div>
    </main>
  );
}
