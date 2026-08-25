"use client";

import { CheckOutlined, CloseOutlined } from "@ant-design/icons";
import { App, Avatar, Button, Empty, List, Result, Skeleton } from "antd";
import { NotificationEntry, useInvitations, useMarkNotificationsRead, useNotifications, useRespondInvitation } from "@/features/team";

export function NotificationCenter() {
  const { message } = App.useApp(); const invitations = useInvitations(); const notifications = useNotifications(); const respond = useRespondInvitation(); const markRead = useMarkNotificationsRead();
  if (notifications.isLoading || invitations.isLoading) return <main className="content-shell"><Skeleton active paragraph={{ rows: 10 }} /></main>;
  if (notifications.isError || invitations.isError) return <Result status="error" title="通知加载失败" />;
  const pending = (invitations.data?.items ?? []).filter((item) => item.status === "pending");
  return <main className="content-shell notifications-shell"><section className="page-heading"><div><p className="page-kicker">NOTIFICATIONS</p><h1>通知中心</h1><p>查看评论互动、位置批注和团队动态。</p></div>{(notifications.data?.unreadCount ?? 0) > 0 ? <Button icon={<CheckOutlined />} loading={markRead.isPending} onClick={() => markRead.mutate(undefined)}>全部标为已读</Button> : null}</section>
    {pending.length ? <section className="notification-section"><h2>待处理邀请</h2><List dataSource={pending} renderItem={(item) => <List.Item actions={[<Button key="accept" type="primary" icon={<CheckOutlined />} onClick={() => respond.mutate({ id: item.id, accept: true }, { onSuccess: () => void message.success("已加入团队") })}>接受</Button>, <Button key="reject" icon={<CloseOutlined />} onClick={() => respond.mutate({ id: item.id, accept: false })}>拒绝</Button>]}><List.Item.Meta avatar={<Avatar src={item.inviter.avatarUrl}>{item.inviter.name.slice(0, 1)}</Avatar>} title={`${item.inviter.name} 邀请你加入 ${item.space.name}`} description={`角色：${item.role === "editor" ? "编辑者" : "浏览者"} · 有效至 ${new Date(item.expiresAt).toLocaleString("zh-CN")}`} /></List.Item>} /></section> : null}
    <section className="notification-section"><h2>全部通知</h2>{notifications.data?.items.length ? <div className="notification-list">{notifications.data.items.map((item) => <NotificationEntry item={item} key={item.id} onRead={(id) => markRead.mutate([id])} />)}</div> : <Empty description="暂无通知" />}</section>
  </main>;
}
