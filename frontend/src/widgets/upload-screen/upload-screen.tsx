"use client";

import { InboxOutlined, LinkOutlined, UploadOutlined } from "@ant-design/icons";
import { Alert, App, Button, Form, Input, Result, Segmented, Select, Skeleton, Upload } from "antd";
import type { UploadProps } from "antd";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { m1Api, usePrototypeSession, usePrototypeUpload } from "@/features/prototype";
import { PictureImage } from "@/features/prototype/ui/picture-image";

interface UploadFormValues {
  title: string;
  description?: string;
  category: string;
  tags?: string;
  url?: string;
}

interface PreviewState {
  src: string;
  width: number;
  height: number;
}

function readPicture(file: File): Promise<PreviewState> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error("无法读取图片"));
    reader.onload = () => {
      const src = String(reader.result);
      const image = new window.Image();
      image.onerror = () => reject(new Error("图片格式无法识别"));
      image.onload = () => resolve({ src, width: image.naturalWidth, height: image.naturalHeight });
      image.src = src;
    };
    reader.readAsDataURL(file);
  });
}

export function normalizePictureUrl(value: string): string {
  let normalized = value.trim().replace(/[，。；、]+$/u, "");
  const marker = /https?:\/\//gi;
  marker.exec(normalized);
  let nextMarker = marker.exec(normalized);
  while (nextMarker?.index !== undefined) {
    const first = normalized.slice(0, nextMarker.index);
    const remainder = normalized.slice(nextMarker.index);
    if (first === remainder) {
      normalized = first;
      break;
    }
    nextMarker = marker.exec(normalized);
  }
  return normalized;
}

export function UploadScreen() {
  const { message } = App.useApp();
  const [form] = Form.useForm<UploadFormValues>();
  const [mode, setMode] = useState<"local" | "url">("local");
  const [preview, setPreview] = useState<PreviewState | null>(null);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [urlPreviewLoading, setUrlPreviewLoading] = useState(false);
  const session = usePrototypeSession();
  const upload = usePrototypeUpload();
  const router = useRouter();

  const beforeUpload: UploadProps["beforeUpload"] = async (file) => {
    setFileError(null);
    if (!(["image/jpeg", "image/png", "image/webp"] as string[]).includes(file.type)) {
      setFileError("仅支持 JPEG、PNG 和 WebP 图片");
      return Upload.LIST_IGNORE;
    }
    if (file.size > 20 * 1024 * 1024) {
      setFileError("单张图片不能超过 20 MB");
      return Upload.LIST_IGNORE;
    }
    try {
      const nextPreview = await readPicture(file);
      setPreview(nextPreview);
      setSelectedFile(file);
      if (!form.getFieldValue("title")) {
        form.setFieldValue("title", file.name.replace(/\.[^.]+$/, ""));
      }
    } catch (error) {
      setFileError(error instanceof Error ? error.message : "图片读取失败");
    }
    return false;
  };

  const handleModeChange = (nextMode: string | number) => {
    setMode(nextMode as "local" | "url");
    setPreview(null);
    setSelectedFile(null);
    setFileError(null);
  };

  const handleUrlPreview = async () => {
    const url = normalizePictureUrl(form.getFieldValue("url") ?? "");
    if (!url) {
      setFileError("请输入图片 URL");
      return;
    }
    setFileError(null);
    setUrlPreviewLoading(true);
    try {
      form.setFieldValue("url", url);
      setPreview(await m1Api.previewPictureUrl(url));
    } catch (error) {
      setPreview(null);
      setFileError(error instanceof Error ? error.message : "图片 URL 无法预览");
    } finally {
      setUrlPreviewLoading(false);
    }
  };

  const handleSubmit = (values: UploadFormValues) => {
    if (!preview) {
      setFileError(mode === "local" ? "请选择要上传的图片" : "请先预览图片 URL");
      return;
    }
    upload.mutate(
      {
        title: values.title,
        description: values.description?.trim() || "暂无描述",
        file: mode === "local" ? selectedFile ?? undefined : undefined,
        imageUrl: mode === "url" ? normalizePictureUrl(values.url ?? "") : undefined,
        category: values.category,
        tags: values.tags
          ? values.tags
              .split(/[，,]/)
              .map((tag) => tag.trim())
              .filter(Boolean)
          : [],
      },
      {
        onSuccess: (picture) => {
          void message.success("图片已保存到个人空间");
          router.push(`/pictures/${picture.id}`);
        },
        onError: (error) => void message.error(error.message),
      },
    );
  };

  if (session.isLoading) {
    return (
      <main className="content-shell">
        <Skeleton active paragraph={{ rows: 10 }} />
      </main>
    );
  }

  if (!session.data || session.data.role !== "user") {
    return (
      <Result
        status="403"
        title="登录普通用户账号后上传图片"
        extra={
          <Button type="primary" href="/login">
            去登录
          </Button>
        }
      />
    );
  }

  return (
    <main className="content-shell upload-shell">
      <section className="page-heading" aria-labelledby="upload-title">
        <div>
          <p className="page-kicker">UPLOAD</p>
          <h1 id="upload-title">上传图片</h1>
          <p>图片默认保存到个人空间</p>
        </div>
      </section>
      <div className="upload-layout">
        <section className="upload-form-panel">
          <Segmented
            block
            value={mode}
            options={[
              { label: "本地图片", value: "local", icon: <UploadOutlined /> },
              { label: "图片 URL", value: "url", icon: <LinkOutlined /> },
            ]}
            onChange={handleModeChange}
          />
          <Form
            form={form}
            layout="vertical"
            requiredMark={false}
            initialValues={{ category: "摄影" }}
            onFinish={handleSubmit}
          >
            <div className="upload-source-control">
              {mode === "local" ? (
                <Upload.Dragger
                  accept="image/jpeg,image/png,image/webp"
                  beforeUpload={beforeUpload}
                  maxCount={1}
                >
                  <p className="ant-upload-drag-icon">
                    <InboxOutlined />
                  </p>
                  <p className="ant-upload-text">点击或拖拽图片到此处</p>
                  <p className="ant-upload-hint">JPEG、PNG、WebP，最大 20 MB</p>
                </Upload.Dragger>
              ) : (
                <Form.Item
                  label="图片 URL"
                  name="url"
                  rules={[{ type: "url", message: "请输入有效的图片 URL" }]}
                >
                  <Input.Search
                    enterButton="预览"
                    placeholder="https://example.com/picture.jpg"
                    onSearch={handleUrlPreview}
                    loading={urlPreviewLoading}
                  />
                </Form.Item>
              )}
            </div>
            {fileError ? <Alert type="error" showIcon title={fileError} /> : null}
            <Form.Item
              label="图片名称"
              name="title"
              rules={[
                { required: true, message: "请输入图片名称" },
                { max: 80, message: "图片名称不能超过 80 个字符" },
              ]}
            >
              <Input placeholder="给图片起一个名称" />
            </Form.Item>
            <Form.Item label="简介" name="description">
              <Input.TextArea
                rows={3}
                maxLength={300}
                showCount
                placeholder="补充图片内容或创作背景"
              />
            </Form.Item>
            <div className="form-row">
              <Form.Item label="分类" name="category">
                <Select
                  options={["摄影", "风景", "人物", "建筑", "静物", "设计"].map((value) => ({
                    value,
                    label: value,
                  }))}
                />
              </Form.Item>
              <Form.Item label="标签" name="tags">
                <Input placeholder="使用逗号分隔" />
              </Form.Item>
            </div>
            <Button
              block
              size="large"
              type="primary"
              htmlType="submit"
              icon={<UploadOutlined />}
              loading={upload.isPending}
            >
              保存到个人空间
            </Button>
          </Form>
        </section>
        <aside className="upload-preview" aria-label="图片预览">
          {preview ? (
            <div style={{ aspectRatio: `${preview.width} / ${preview.height}` }}>
              <PictureImage
                alt="待上传图片预览"
                fallbackSrc=""
                onError={() => setFileError("预览图片加载失败，请检查 URL 是否仍然可访问")}
                src={preview.src}
              />
            </div>
          ) : (
            <div className="preview-placeholder">
              <UploadOutlined />
              <span>图片预览</span>
            </div>
          )}
        </aside>
      </div>
    </main>
  );
}
