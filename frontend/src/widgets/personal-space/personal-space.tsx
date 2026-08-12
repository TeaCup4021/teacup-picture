"use client";

import { CloudUploadOutlined, SendOutlined } from "@ant-design/icons";
import { App, Button, Progress, Result, Segmented, Skeleton } from "antd";
import { useMemo, useState } from "react";
import { usePersonalPictures, usePrototypeSession, useSubmitReview } from "@/features/prototype";
import type { PublishStatus } from "@/features/prototype";
import { PictureTile } from "@/features/prototype/ui/picture-tile";

type SpaceFilter = "all" | PublishStatus;

const filters: Array<{ label: string; value: SpaceFilter }> = [
  { label: "全部", value: "all" },
  { label: "私有", value: "not_requested" },
  { label: "待审核", value: "pending" },
  { label: "已公开", value: "approved" },
  { label: "已驳回", value: "rejected" },
  { label: "已撤回", value: "withdrawn" },
];

export function PersonalSpace() {
  const { message } = App.useApp();
  const [filter, setFilter] = useState<SpaceFilter>("all");
  const session = usePrototypeSession();
  const pictures = usePersonalPictures(Boolean(session.data));
  const submitReview = useSubmitReview();

  const filteredPictures = useMemo(
    () =>
      filter === "all"
        ? (pictures.data ?? [])
        : (pictures.data ?? []).filter((picture) => picture.publishStatus === filter),
    [filter, pictures.data],
  );

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
        title="登录后查看个人空间"
        extra={
          <Button type="primary" href="/login">
            去登录
          </Button>
        }
      />
    );
  }

  const total = pictures.data?.length ?? 0;
  const publicCount =
    pictures.data?.filter((picture) => picture.publishStatus === "approved").length ?? 0;
  const pendingCount =
    pictures.data?.filter((picture) => picture.publishStatus === "pending").length ?? 0;
  const storagePercent = Math.min(100, Math.round(total * 3.4));

  const handleSubmit = (pictureId: string) => {
    submitReview.mutate(pictureId, {
      onSuccess: () => void message.success("已提交公开审核"),
      onError: (error) => void message.error(error.message),
    });
  };

  return (
    <main className="content-shell">
      <section className="page-heading space-heading" aria-labelledby="space-title">
        <div>
          <p className="page-kicker">PERSONAL SPACE</p>
          <h1 id="space-title">{session.data.displayName}的个人空间</h1>
          <p>默认个人空间 · 普通版</p>
        </div>
        <Button type="primary" size="large" icon={<CloudUploadOutlined />} href="/upload">
          上传图片
        </Button>
      </section>
      <section className="space-summary" aria-label="空间概览">
        <div>
          <span>图片总数</span>
          <strong>{total}</strong>
        </div>
        <div>
          <span>审核中</span>
          <strong>{pendingCount}</strong>
        </div>
        <div>
          <span>已公开</span>
          <strong>{publicCount}</strong>
        </div>
        <div className="storage-summary">
          <span>空间容量</span>
          <strong>{Math.max(1, Math.round(total * 3.4))} MB / 100 MB</strong>
          <Progress percent={storagePercent} showInfo={false} size="small" />
        </div>
      </section>
      <div className="space-toolbar">
        <Segmented
          options={filters}
          value={filter}
          onChange={(value) => setFilter(value as SpaceFilter)}
        />
        <span>{filteredPictures.length} 张图片</span>
      </div>
      {pictures.isLoading ? (
        <Skeleton active paragraph={{ rows: 8 }} />
      ) : filteredPictures.length > 0 ? (
        <div className="personal-grid">
          {filteredPictures.map((picture) => {
            const canSubmit =
              picture.publishStatus === "not_requested" || picture.publishStatus === "rejected";
            return (
              <PictureTile
                key={picture.id}
                picture={picture}
                showStatus
                action={
                  canSubmit ? (
                    <Button
                      type="link"
                      icon={<SendOutlined />}
                      loading={submitReview.isPending && submitReview.variables === picture.id}
                      onClick={() => handleSubmit(picture.id)}
                    >
                      提交审核
                    </Button>
                  ) : undefined
                }
              />
            );
          })}
        </div>
      ) : (
        <Result title="当前筛选下没有图片" />
      )}
    </main>
  );
}
