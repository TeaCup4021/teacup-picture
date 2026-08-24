"use client";

import { CopyOutlined, DeleteOutlined, LinkOutlined, ReloadOutlined } from "@ant-design/icons";
import { App, Button, Form, Input, Modal, Popconfirm, Select, Space, Switch, Typography } from "antd";
import { useState } from "react";
import { useActiveShare, useShareActions } from "../queries";

export function ShareDialog({ open, pictureId, onClose }: { open: boolean; pictureId: string; onClose: () => void }) {
  const { message } = App.useApp(); const active = useActiveShare(pictureId, open); const actions = useShareActions(pictureId);
  const [duration, setDuration] = useState("7"); const [customExpiry, setCustomExpiry] = useState(""); const [passwordEnabled, setPasswordEnabled] = useState(false); const [password, setPassword] = useState(""); const [newPath, setNewPath] = useState<string | null>(null);
  const customExpiryValid = duration !== "custom" || (Boolean(customExpiry) && !Number.isNaN(Date.parse(customExpiry)));
  const generate = (regenerate: boolean) => {
    const mutation = regenerate ? actions.regenerate : actions.create;
    const expiresAt = duration === "permanent" ? undefined : duration === "custom" ? new Date(customExpiry).toISOString() : new Date(Date.now() + Number(duration) * 86400000).toISOString();
    mutation.mutate({ expiresAt, password: passwordEnabled ? password : undefined }, { onSuccess: (value) => { setNewPath(value.sharePath ?? null); void message.success(regenerate ? "新链接已生成，旧链接已失效" : "分享链接已生成"); }, onError: (error) => void message.error(error.message) });
  };
  const fullUrl = newPath && typeof window !== "undefined" ? `${window.location.origin}${newPath}` : null;
  return <Modal open={open} onCancel={onClose} footer={null} title={<Space><LinkOutlined />链接分享</Space>} destroyOnHidden>
    <div className="share-dialog-body">
      {active.data ? <div className="active-share-summary"><strong>当前分享链接有效</strong><span>{active.data.expiresAt ? `有效至 ${new Date(active.data.expiresAt).toLocaleString("zh-CN")}` : "永久有效"}</span><span>{active.data.passwordProtected ? "已设置访问密码" : "无访问密码"}</span></div> : null}
      <Form layout="vertical">
        <Form.Item label="有效期"><Select value={duration} onChange={setDuration} options={[{ value: "1", label: "1 天" }, { value: "7", label: "7 天" }, { value: "30", label: "30 天" }, { value: "custom", label: "自定义时间" }, { value: "permanent", label: "永久有效" }]} />{duration === "custom" ? <Input aria-label="自定义分享失效时间" type="datetime-local" value={customExpiry} onChange={(event) => setCustomExpiry(event.target.value)} status={customExpiry && !customExpiryValid ? "error" : undefined} /> : null}</Form.Item>
        <Form.Item label="访问密码"><Space direction="vertical" style={{ width: "100%" }}><Switch checked={passwordEnabled} onChange={setPasswordEnabled} checkedChildren="开启" unCheckedChildren="关闭" />{passwordEnabled ? <Input.Password aria-label="分享访问密码" minLength={4} maxLength={72} value={password} onChange={(event) => setPassword(event.target.value)} placeholder="4 到 72 位" /> : null}</Space></Form.Item>
      </Form>
      {fullUrl ? <div className="share-link-result"><Typography.Text copyable={{ text: fullUrl, onCopy: () => void message.success("链接已复制") }}>{fullUrl}</Typography.Text><Button icon={<CopyOutlined />} onClick={() => navigator.clipboard.writeText(fullUrl)}>复制链接</Button></div> : null}
      <Space wrap>
        {!active.data ? <Button type="primary" icon={<LinkOutlined />} loading={actions.create.isPending} disabled={!customExpiryValid || (passwordEnabled && password.length < 4)} onClick={() => generate(false)}>生成链接</Button> : <Popconfirm title="重新生成后旧链接立即失效" onConfirm={() => generate(true)}><Button type="primary" icon={<ReloadOutlined />} loading={actions.regenerate.isPending} disabled={!customExpiryValid || (passwordEnabled && password.length < 4)}>重新生成</Button></Popconfirm>}
        {active.data ? <Popconfirm title="撤销后访问者将立即无法打开链接" onConfirm={() => actions.revoke.mutate(active.data!.id, { onSuccess: () => { setNewPath(null); void message.success("分享已撤销"); } })}><Button danger icon={<DeleteOutlined />} loading={actions.revoke.isPending}>撤销分享</Button></Popconfirm> : null}
      </Space>
      <Typography.Paragraph type="secondary">出于安全原因，完整链接只在生成时显示。关闭后如需再次复制，请重新生成。</Typography.Paragraph>
    </div>
  </Modal>;
}
