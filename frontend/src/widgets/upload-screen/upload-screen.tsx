"use client";

import { CloseOutlined, InboxOutlined, LinkOutlined, UploadOutlined } from "@ant-design/icons";
import { Alert, App, Button, Form, Input, Progress, Result, Segmented, Select, Skeleton, Upload } from "antd";
import type { UploadProps } from "antd";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { m1Api, usePrototypeSession, usePrototypeUpload } from "@/features/prototype";
import { PictureImage } from "@/features/prototype/ui/picture-image";

// 表单数据结构：标题、描述、分类、标签、URL
interface UploadFormValues {
  title: string;
  description?: string;
  category: string;
  tags?: string;
  url?: string;
}

// 预览状态结构：图片地址(src)、宽、高
interface PreviewState {
  src: string;
  width: number;
  height: number;
  previewToken?: string;
}

const MAX_IMAGE_DIMENSION = 16_384;
const MAX_IMAGE_PIXELS = 40_000_000;

// Create a browser-local URL instead of a base64 copy of the original file.
function readPicture(file: File): Promise<PreviewState> {
  return new Promise((resolve, reject) => {
    const src = URL.createObjectURL(file);
    const image = new window.Image();
    image.onerror = () => {
      URL.revokeObjectURL(src);
      reject(new Error("图片格式无法识别"));
    };
    image.onload = () => resolve({ src, width: image.naturalWidth, height: image.naturalHeight });
    image.src = src;
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

export function UploadScreen({ spaceId }: { spaceId?: string }) {
  const { message } = App.useApp();
  const [form] = Form.useForm<UploadFormValues>();
  const [mode, setMode] = useState<"local" | "url">("local");
  const [preview, setPreview] = useState<PreviewState | null>(null);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [urlPreviewLoading, setUrlPreviewLoading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState<number | null>(null);
  const previewRef = useRef<PreviewState | null>(null);
  const uploadAbortRef = useRef<AbortController | null>(null);
  const session = usePrototypeSession();
  const upload = usePrototypeUpload();
  const router = useRouter();

  const replacePreview = useCallback((nextPreview: PreviewState | null) => {
    if (previewRef.current?.src) URL.revokeObjectURL(previewRef.current.src);
    previewRef.current = nextPreview;
    setPreview(nextPreview);
  }, []);

  useEffect(() => () => {
    if (previewRef.current?.src) URL.revokeObjectURL(previewRef.current.src);
  }, []);

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
      if (nextPreview.width > MAX_IMAGE_DIMENSION || nextPreview.height > MAX_IMAGE_DIMENSION) {
        URL.revokeObjectURL(nextPreview.src);
        throw new Error(`图片最长边不能超过 ${MAX_IMAGE_DIMENSION} 像素`);
      }
      if (nextPreview.width * nextPreview.height > MAX_IMAGE_PIXELS) {
        URL.revokeObjectURL(nextPreview.src);
        throw new Error("图片总像素不能超过 4000 万");
      }
      replacePreview(nextPreview);
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
    replacePreview(null);
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
      replacePreview(await m1Api.previewPictureUrl(url));
    } catch (error) {
      replacePreview(null);
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
    const controller = new AbortController();
    uploadAbortRef.current = controller;
    setUploadProgress(mode === "local" ? 0 : null);
    upload.mutate(
      {
        title: values.title,
        description: values.description?.trim() || "暂无描述",
        file: mode === "local" ? selectedFile ?? undefined : undefined,
        imageUrl: mode === "url" ? normalizePictureUrl(values.url ?? "") : undefined,
        previewToken: mode === "url" ? preview.previewToken : undefined,
        category: values.category,
        tags: values.tags
          ? values.tags
              .split(/[，,]/)
              .map((tag) => tag.trim())
              .filter(Boolean)
          : [],
        spaceId,
        signal: controller.signal,
        onUploadProgress: ({ loaded, total }) => {
          if (!total) return;
          setUploadProgress(Math.min(100, Math.round((loaded / total) * 100)));
        },
      },
      {
        onSuccess: (picture) => {
          void message.success(spaceId ? "图片已保存到团队空间" : "图片已保存到个人空间");
          router.push(spaceId ? `/spaces/${spaceId}` : `/pictures/${picture.id}`);
        },
        onError: (error) => {
          if (!controller.signal.aborted) void message.error(error.message);
        },
        onSettled: () => {
          if (uploadAbortRef.current === controller) uploadAbortRef.current = null;
          setUploadProgress(null);
        },
      },
    );
  };

  const handleCancelUpload = () => {
    uploadAbortRef.current?.abort();
    void message.info("已取消上传");
  };

  if (session.isLoading) {
    return (
      <main className="content-shell">
        <Skeleton active paragraph={{ rows: 10 }} />
      </main>
    );
  }

  if (!session.data) {
    return (
      <Result
        status="403"
        title="登录后上传图片"
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
          <p>{spaceId ? "图片将保存到当前团队空间" : "图片默认保存到个人空间"}</p>
        </div>
      </section>
      <div className="upload-layout">
        <section className="upload-form-panel">
          <Segmented
            block
            disabled={upload.isPending}
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
                  disabled={upload.isPending}
                  showUploadList={false}
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
                    onChange={() => {
                      if (preview?.previewToken) replacePreview(null);
                    }}
                    loading={urlPreviewLoading}
                  />
                </Form.Item>
              )}
            </div>
            {fileError ? <Alert type="error" showIcon title={fileError} /> : null}
            {upload.isPending ? (
              <div className="upload-progress-actions" aria-live="polite">
                {mode === "local" ? (
                  <Progress percent={uploadProgress ?? 0} size="small" />
                ) : (
                  <span>正在导入图片 URL</span>
                )}
                <Button danger icon={<CloseOutlined />} onClick={handleCancelUpload}>
                  取消
                </Button>
              </div>
            ) : null}
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
              {spaceId ? "保存到团队空间" : "保存到个人空间"}
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
