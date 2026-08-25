"use client";

import { BellOutlined, RightOutlined } from "@ant-design/icons";
import { Alert, Badge, Button, Empty, Popover, Spin } from "antd";
import Link from "next/link";
import { useState } from "react";
import { useMarkNotificationsRead, useNotifications } from "../queries";
import { NotificationEntry } from "./notification-entry";

export function NotificationBell({ enabled = true, inverse = false }: { enabled?: boolean; inverse?: boolean }) {
  const [open, setOpen] = useState(false);
  const notifications = useNotifications(enabled);
  const markRead = useMarkNotificationsRead();
  if (!enabled) return null;

  const recent = notifications.data?.items.slice(0, 5) ?? [];
  const content = <div className="notification-popover" aria-label="最近通知">
    <div className="notification-popover-heading"><strong>最近通知</strong><span>{notifications.data?.unreadCount ?? 0} 条未读</span></div>
    <div className="notification-popover-list">
      {notifications.isLoading ? <Spin size="small" /> : notifications.isError ? <Alert type="error" showIcon message="通知加载失败" /> : recent.length ? recent.map((item) => <NotificationEntry compact item={item} key={item.id} onRead={(id) => { markRead.mutate([id]); setOpen(false); }} />) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无通知" />}
    </div>
    <Link className="notification-popover-footer" href="/notifications" onNavigate={() => setOpen(false)}>查看全部通知 <RightOutlined /></Link>
  </div>;

  return <Popover content={content} open={open} onOpenChange={setOpen} placement="bottomRight" trigger="click">
    <Button className={inverse ? "notification-bell is-inverse" : "notification-bell"} type="text" aria-label={`通知中心，${notifications.data?.unreadCount ?? 0} 条未读`} icon={<Badge count={notifications.data?.unreadCount ?? 0} overflowCount={99} size="small"><BellOutlined /></Badge>} />
  </Popover>;
}
