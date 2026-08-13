"use client";

import { CheckOutlined, CloseOutlined, EyeOutlined } from "@ant-design/icons";
import { App, Button, Empty, Input, Modal, Result, Skeleton, Space, Table, Tag } from "antd";
import type { TableColumnsType } from "antd";
import Link from "next/link";
import { useState } from "react";
import { useDecideReview, usePendingReviews, usePrototypeSession } from "@/features/prototype";
import type { PrototypePicture } from "@/features/prototype";
import { PictureImage } from "@/features/prototype/ui/picture-image";

export function AdminReviews() {
  const { message } = App.useApp();
  const [rejectingPicture, setRejectingPicture] = useState<PrototypePicture | null>(null);
  const [rejectNote, setRejectNote] = useState("");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const session = usePrototypeSession();
  const reviews = usePendingReviews(session.data?.role === "admin");
  const decision = useDecideReview();
  const selectedPicture =
    reviews.data?.find((picture) => picture.id === selectedId) ?? reviews.data?.[0] ?? null;

  const approve = (pictureId: string) => {
    decision.mutate(
      {
        pictureId,
        requestId: reviews.data?.find((item) => item.id === pictureId)?.reviewRequestId,
        decision: "approve",
      },
      {
        onSuccess: () => void message.success("审核通过，图片已公开"),
        onError: (error) => void message.error(error.message),
      },
    );
  };

  const reject = () => {
    if (!rejectingPicture || !rejectNote.trim()) return;
    decision.mutate(
      {
        pictureId: rejectingPicture.id,
        requestId: rejectingPicture.reviewRequestId,
        decision: "reject",
        note: rejectNote.trim(),
      },
      {
        onSuccess: () => {
          void message.success("已驳回公开申请");
          setRejectingPicture(null);
          setRejectNote("");
        },
        onError: (error) => void message.error(error.message),
      },
    );
  };

  const columns: TableColumnsType<PrototypePicture> = [
    {
      title: "图片",
      key: "picture",
      width: 330,
      render: (_, picture) => (
        <div className="review-picture-cell">
          <div className="review-thumbnail">
            <PictureImage alt={picture.title} src={picture.imageUrl} />
          </div>
          <div>
            <Link href={`/pictures/${picture.id}`}>{picture.title}</Link>
            <span>{picture.description}</span>
          </div>
        </div>
      ),
    },
    { title: "提交人", dataIndex: "authorName", key: "authorName", width: 110 },
    {
      title: "提交时间",
      dataIndex: "createdAt",
      key: "createdAt",
      width: 130,
      render: (value: string) => new Date(value).toLocaleDateString("zh-CN"),
    },
    {
      title: "操作",
      key: "action",
      width: 190,
      render: (_, picture) => (
        <Space>
          <Button href={`/pictures/${picture.id}`} icon={<EyeOutlined />} aria-label="查看图片" />
          <Button
            type="primary"
            icon={<CheckOutlined />}
            loading={decision.isPending && decision.variables?.pictureId === picture.id}
            onClick={() => approve(picture.id)}
          >
            通过
          </Button>
          <Button danger icon={<CloseOutlined />} onClick={() => setRejectingPicture(picture)}>
            驳回
          </Button>
        </Space>
      ),
    },
  ];

  if (session.isLoading) {
    return (
      <main className="content-shell">
        <Skeleton active paragraph={{ rows: 10 }} />
      </main>
    );
  }

  if (session.data?.role !== "admin") {
    return (
      <Result
        status="403"
        title="需要管理员权限"
        extra={
          <Button type="primary" href="/login">
            切换账号
          </Button>
        }
      />
    );
  }

  return (
    <main className="content-shell admin-shell">
      <section className="page-heading" aria-labelledby="review-title">
        <div>
          <p className="page-kicker">ADMIN</p>
          <h1 id="review-title">公开审核</h1>
          <p>处理用户提交的公开图片申请</p>
        </div>
        <div className="review-count">
          <span>待处理</span>
          <strong>{reviews.data?.length ?? 0}</strong>
        </div>
      </section>
      <div className="review-workbench">
        <section className="review-list-panel" aria-label="待审核列表">
          <div className="review-list-heading">
            <strong>待审核列表</strong>
            <span>{reviews.data?.length ?? 0} 项</span>
          </div>
          <Table
            className="review-table"
            columns={columns}
            dataSource={reviews.data ?? []}
            loading={reviews.isLoading}
            rowKey="id"
            pagination={false}
            rowClassName={(picture) =>
              picture.id === selectedPicture?.id ? "review-row-selected" : ""
            }
            onRow={(picture) => ({ onClick: () => setSelectedId(picture.id) })}
            locale={{ emptyText: <Empty description="暂无待审核申请" /> }}
            scroll={{ x: 760 }}
          />
        </section>
        <aside className="review-detail-panel" aria-label="审核详情">
          {selectedPicture ? (
            <>
              <div className="review-detail-media">
                <PictureImage alt={selectedPicture.title} priority src={selectedPicture.imageUrl} />
              </div>
              <div className="review-detail-content">
                <Tag color="gold">等待审核</Tag>
                <h2>{selectedPicture.title}</h2>
                <p>{selectedPicture.description}</p>
                <dl className="review-detail-meta">
                  <div>
                    <dt>作者</dt>
                    <dd>{selectedPicture.authorName}</dd>
                  </div>
                  <div>
                    <dt>提交时间</dt>
                    <dd>{new Date(selectedPicture.createdAt).toLocaleDateString("zh-CN")}</dd>
                  </div>
                  <div>
                    <dt>分类</dt>
                    <dd>{selectedPicture.category}</dd>
                  </div>
                  <div>
                    <dt>尺寸</dt>
                    <dd>
                      {selectedPicture.width} × {selectedPicture.height}
                    </dd>
                  </div>
                </dl>
                <div className="review-detail-actions">
                  <Button danger onClick={() => setRejectingPicture(selectedPicture)}>
                    驳回并填写原因
                  </Button>
                  <Button
                    type="primary"
                    icon={<CheckOutlined />}
                    loading={
                      decision.isPending && decision.variables?.pictureId === selectedPicture.id
                    }
                    onClick={() => approve(selectedPicture.id)}
                  >
                    审核通过
                  </Button>
                </div>
              </div>
            </>
          ) : (
            <Empty description="选择一条申请查看详情" />
          )}
        </aside>
      </div>
      <Modal
        title={`驳回“${rejectingPicture?.title ?? ""}”`}
        open={Boolean(rejectingPicture)}
        okText="确认驳回"
        okButtonProps={{ danger: true, disabled: !rejectNote.trim() }}
        confirmLoading={decision.isPending}
        cancelText="取消"
        onCancel={() => {
          setRejectingPicture(null);
          setRejectNote("");
        }}
        onOk={reject}
      >
        <label className="modal-field-label" htmlFor="reject-note">
          驳回原因
        </label>
        <Input.TextArea
          id="reject-note"
          rows={4}
          maxLength={200}
          showCount
          value={rejectNote}
          placeholder="请填写用户可见的驳回原因"
          onChange={(event) => setRejectNote(event.target.value)}
        />
      </Modal>
    </main>
  );
}
