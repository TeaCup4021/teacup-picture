"use client";

import { AppstoreAddOutlined, PlusOutlined, TeamOutlined } from "@ant-design/icons";
import { App, Button, Empty, Form, Input, Modal, Progress, Result, Skeleton, Tag } from "antd";
import Link from "next/link";
import { useState } from "react";
import { useCreateTeamSpace, useTeamSpaces } from "@/features/team";

const roleLabel = { owner: "所有者", admin: "管理员", editor: "编辑者", viewer: "浏览者" } as const;

export function TeamSpaces() {
  const { message } = App.useApp();
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<{ name: string }>();
  const spaces = useTeamSpaces();
  const create = useCreateTeamSpace();

  const submit = ({ name }: { name: string }) => create.mutate(name, { onSuccess: () => { setOpen(false); form.resetFields(); void message.success("团队空间已创建"); }, onError: (error) => void message.error(error.message) });

  return (
    <main className="content-shell team-shell">
      <section className="page-heading">
        <div><p className="page-kicker">TEAM SPACES</p><h1>团队空间</h1><p>管理你拥有或参与的团队图片资产。</p></div>
        <Button type="primary" size="large" icon={<PlusOutlined />} onClick={() => setOpen(true)}>创建团队</Button>
      </section>
      {spaces.isLoading ? <Skeleton active paragraph={{ rows: 8 }} /> : spaces.isError ? <Result status="error" title="团队空间加载失败" extra={<Button onClick={() => void spaces.refetch()}>重试</Button>} /> : spaces.data?.items.length ? (
        <div className="team-space-list">
          {spaces.data.items.map((space) => {
            const sizePercent = space.maxSize ? Math.min(100, Math.round(space.totalSize * 100 / space.maxSize)) : 0;
            return <Link className="team-space-row" href={`/spaces/${space.id}`} key={space.id}>
              <span className="team-space-icon"><TeamOutlined /></span>
              <span className="team-space-main"><strong>{space.name}</strong><span>{space.memberCount} 位成员 · {space.totalCount} 张图片</span></span>
              <Tag>{roleLabel[space.role]}</Tag>
              <span className="team-space-usage"><Progress percent={sizePercent} size="small" showInfo={false} /><small>{sizePercent}% 空间已用</small></span>
            </Link>;
          })}
        </div>
      ) : <Empty image={<AppstoreAddOutlined className="team-empty-icon" />} description="还没有团队空间"><Button type="primary" onClick={() => setOpen(true)}>创建第一个团队</Button></Empty>}
      <Modal title="创建团队空间" open={open} onCancel={() => setOpen(false)} footer={null} destroyOnHidden>
        <Form form={form} layout="vertical" onFinish={submit} requiredMark={false}>
          <Form.Item label="团队名称" name="name" rules={[{ required: true, message: "请输入团队名称" }, { max: 30, message: "团队名称不能超过 30 个字符" }]}><Input autoFocus placeholder="例如：品牌设计组" /></Form.Item>
          <Button block type="primary" htmlType="submit" loading={create.isPending}>创建团队</Button>
        </Form>
      </Modal>
    </main>
  );
}
