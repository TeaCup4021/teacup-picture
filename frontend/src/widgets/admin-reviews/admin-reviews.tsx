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
  const session = usePrototypeSession();
  const reviews = usePendingReviews(session.data?.role === "admin");
  const decision = useDecideReview();

  const approve = (pictureId: string) => {
    decision.mutate(
      { pictureId, requestId: reviews.data?.find((item) => item.id === pictureId)?.reviewRequestId, decision: "approve" },
      {
        onSuccess: () => void message.success("审核通过，图片已公开"),
        onError: (error) => void message.error(error.message),
      },
    );
  };

  const reject = () => {
    if (!rejectingPicture || !rejectNote.trim()) return;
    decision.mutate(
      { pictureId: rejectingPicture.id, requestId: rejectingPicture.reviewRequestId, decision: "reject", note: rejectNote.trim() },
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
      width: 360,
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
    { title: "提交人", dataIndex: "authorName", key: "authorName", width: 120 },
    {
      title: "分类与标签",
      key: "metadata",
      render: (_, picture) => (
        <Space size={[4, 4]} wrap>
          <Tag>{picture.category}</Tag>
          {picture.tags.map((tag) => (
            <Tag key={tag}>{tag}</Tag>
          ))}
        </Space>
      ),
    },
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
      width: 210,
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
      <Table
        className="review-table"
        columns={columns}
        dataSource={reviews.data ?? []}
        loading={reviews.isLoading}
        rowKey="id"
        pagination={false}
        locale={{ emptyText: <Empty description="暂无待审核申请" /> }}
        scroll={{ x: 980 }}
      />
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
