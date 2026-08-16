"use client";

import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  EditOutlined,
  SendOutlined,
} from "@ant-design/icons";
import { Alert, App, Button, Descriptions, Result, Skeleton, Space, Tag } from "antd";
import Link from "next/link";
import { usePrototypePicture, usePrototypeSession, useSubmitReview } from "@/features/prototype";
import { PictureImage } from "@/features/prototype/ui/picture-image";
import { PublishStatusTag } from "@/features/prototype/ui/publish-status-tag";

export function PictureDetail({ pictureId }: Readonly<{ pictureId: string }>) {
  const { message } = App.useApp();
  const picture = usePrototypePicture(pictureId);
  const session = usePrototypeSession();
  const submitReview = useSubmitReview();

  if (picture.isLoading) {
    return (
      <main className="content-shell detail-loading">
        <Skeleton active paragraph={{ rows: 10 }} />
      </main>
    );
  }

  if (!picture.data) {
    return (
      <Result status="404" title="图片不存在" extra={<Button href="/">返回公开图库</Button>} />
    );
  }

  const item = picture.data;
  const isOwner = session.data?.id === item.authorId;
  const canEdit = isOwner || session.data?.role === "admin";
  const canSubmit =
    isOwner && (item.publishStatus === "not_requested" || item.publishStatus === "rejected");

  const handleSubmitReview = () => {
    submitReview.mutate(item.id, {
      onSuccess: () => void message.success("已提交公开审核"),
      onError: (error) => void message.error(error.message),
    });
  };

  return (
    <main className="content-shell detail-shell">
      <div className="detail-back-row">
        <Link href={isOwner ? "/spaces/personal" : "/"}>
          <ArrowLeftOutlined /> {isOwner ? "返回个人空间" : "返回公开图库"}
        </Link>
      </div>
      <div className="detail-layout">
        <section className="detail-media" aria-label={item.title}>
          <div style={{ aspectRatio: `${item.width} / ${item.height}` }}>
            <PictureImage alt={item.title} priority src={item.imageUrl} />
          </div>
        </section>
        <aside className="detail-panel">
          <div className="detail-title-row">
            <div>
              <p className="page-kicker">PICTURE</p>
              <h1>{item.title}</h1>
            </div>
            {isOwner || session.data?.role === "admin" ? (
              <PublishStatusTag status={item.publishStatus} />
            ) : null}
          </div>
          <p className="detail-description">{item.description}</p>
          <div className="detail-author-row">
            <span className="detail-avatar">{item.authorName.slice(0, 1)}</span>
            <div>
              <strong>{item.authorName}</strong>
              <span>{new Date(item.createdAt).toLocaleDateString("zh-CN")}</span>
            </div>
          </div>
          <Space size={8} wrap>
            {item.tags.map((tag) => (
              <Tag key={tag}>{tag}</Tag>
            ))}
          </Space>
          <Descriptions className="detail-metadata" column={1} size="small" colon={false}>
            <Descriptions.Item label="分类">{item.category}</Descriptions.Item>
            <Descriptions.Item label="尺寸">
              {item.width} × {item.height}
            </Descriptions.Item>
            <Descriptions.Item label="创建时间">
              {new Date(item.createdAt).toLocaleDateString("zh-CN")}
            </Descriptions.Item>
          </Descriptions>
          {item.publishStatus === "pending" && isOwner ? (
            <Alert
              type="warning"
              showIcon
              icon={<ClockCircleOutlined />}
              title="正在等待管理员审核"
            />
          ) : null}
          {item.publishStatus === "approved" && isOwner ? (
            <Alert
              type="success"
              showIcon
              icon={<CheckCircleOutlined />}
              title="图片已进入公开图库"
            />
          ) : null}
          {item.publishStatus === "rejected" && isOwner ? (
            <Alert type="error" showIcon title="公开申请未通过" description={item.reviewNote} />
          ) : null}
          {canEdit || canSubmit ? (
            <div className="detail-actions">
              {canEdit ? (
                <Button
                  block
                  size="large"
                  type="primary"
                  icon={<EditOutlined />}
                  href={`/editor/${item.id}`}
                >
                  编辑图片
                </Button>
              ) : null}
              {canSubmit ? (
                <Button
                  block
                  size="large"
                  icon={<SendOutlined />}
                  loading={submitReview.isPending}
                  onClick={handleSubmitReview}
                >
                  提交公开审核
                </Button>
              ) : null}
            </div>
          ) : null}
        </aside>
      </div>
    </main>
  );
}
