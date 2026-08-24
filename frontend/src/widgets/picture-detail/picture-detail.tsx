"use client";

import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  EditOutlined,
  DownloadOutlined,
  ShareAltOutlined,
  StopOutlined,
  SendOutlined,
} from "@ant-design/icons";
import { Alert, App, Button, Descriptions, Popconfirm, Result, Skeleton, Space, Tag, Tooltip } from "antd";
import Link from "next/link";
import { useState, type MouseEvent } from "react";
import { usePrototypePicture, usePrototypeSession, useSubmitReview } from "@/features/prototype";
import { PictureImage } from "@/features/prototype/ui/picture-image";
import { PublishStatusTag } from "@/features/prototype/ui/publish-status-tag";
import { interactionKeys, interactionsApi, normalizeAnnotationPosition, usePictureComments } from "@/features/interactions";
import { CommentPanel } from "@/features/interactions/ui/comment-panel";
import { ShareDialog } from "@/features/interactions/ui/share-dialog";

export function PictureDetail({ pictureId }: Readonly<{ pictureId: string }>) {
  const { message } = App.useApp();
  const picture = usePrototypePicture(pictureId);
  const session = usePrototypeSession();
  const submitReview = useSubmitReview();
  const comments = usePictureComments(pictureId, Boolean(session.data));
  const [shareOpen, setShareOpen] = useState(false);
  const [selectingAnchor, setSelectingAnchor] = useState(false);
  const [anchor, setAnchor] = useState<{ x: number; y: number } | null>(null);

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
  const canShare = Boolean(session.data && item.permissions.includes("picture:share"));
  const canSubmit =
    isOwner && (item.publishStatus === "not_requested" || item.publishStatus === "rejected");

  const handleSubmitReview = () => {
    submitReview.mutate(item.id, {
      onSuccess: () => void message.success("已提交公开审核"),
      onError: (error) => void message.error(error.message),
    });
  };

  const placeAnchor = (event: MouseEvent<HTMLDivElement>) => {
    if (!selectingAnchor) return;
    const rect = event.currentTarget.getBoundingClientRect();
    const border = Number.parseFloat(getComputedStyle(event.currentTarget).borderLeftWidth) || 0;
    setAnchor(normalizeAnnotationPosition(event.clientX, event.clientY, { left: rect.left, top: rect.top, width: rect.width, height: rect.height, border }));
    setSelectingAnchor(false);
  };

  const currentAnnotations = (comments.data?.items ?? []).filter((comment) => comment.kind === "annotation" && !comment.deleted && comment.pictureVersionId === item.currentVersionId && comment.x != null && comment.y != null);

  return (
    <main className="content-shell detail-shell">
      <div className="detail-back-row">
        <Link href={isOwner ? "/spaces/personal" : "/"}>
          <ArrowLeftOutlined /> {isOwner ? "返回个人空间" : "返回公开图库"}
        </Link>
      </div>
      <div className="detail-layout">
        <section className="detail-media" aria-label={item.title}>
          <div className={selectingAnchor ? "detail-image-frame is-placing-annotation" : "detail-image-frame"} style={{ aspectRatio: `${item.width} / ${item.height}` }} onClick={placeAnchor}>
            <PictureImage alt={item.title} priority src={item.imageUrl} />
            {currentAnnotations.map((comment, index) => <Tooltip title={comment.body} key={comment.id}><button className={comment.resolved ? "annotation-pin is-resolved" : "annotation-pin"} style={{ left: `${comment.x! * 100}%`, top: `${comment.y! * 100}%` }} aria-label={`批注 ${index + 1}`}>{index + 1}</button></Tooltip>)}
            {anchor ? <span className="annotation-pin is-pending" style={{ left: `${anchor.x * 100}%`, top: `${anchor.y * 100}%` }} aria-label="待提交批注位置">+</span> : null}
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
          <div className="detail-interaction-actions">
            {session.data ? <Button icon={<DownloadOutlined />} onClick={() => interactionsApi.downloadPicture(item.id, item.visibility === "public", item.title).catch((error: Error) => void message.error(error.message))}>下载</Button> : <Button icon={<DownloadOutlined />} href="/login">登录后下载</Button>}
            {canShare ? <Button icon={<ShareAltOutlined />} onClick={() => setShareOpen(true)}>分享</Button> : null}
            {canShare && item.publishStatus === "approved" ? <Popconfirm title="撤回后图片将立即离开公开图库" onConfirm={() => interactionsApi.withdrawPublication(item.id).then(() => { void message.success("已撤回公开状态"); void picture.refetch(); }).catch((error: Error) => void message.error(error.message))}><Button danger icon={<StopOutlined />}>撤回公开</Button></Popconfirm> : null}
          </div>
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
      <CommentPanel pictureId={item.id} currentVersionId={item.currentVersionId ?? comments.data?.currentVersionId ?? undefined} comments={comments.data} loading={comments.isLoading} error={comments.isError} authenticated={Boolean(session.data)} refreshKey={interactionKeys.comments(item.id, session.data ? "private" : "public")} pendingAnchor={anchor} onRequestAnchor={() => setSelectingAnchor(true)} onClearAnchor={() => { setAnchor(null); setSelectingAnchor(false); }} onLoadMore={() => void comments.fetchNextPage()} loadingMore={comments.isFetchingNextPage} />
      {canShare ? <ShareDialog open={shareOpen} pictureId={item.id} onClose={() => setShareOpen(false)} /> : null}
    </main>
  );
}
