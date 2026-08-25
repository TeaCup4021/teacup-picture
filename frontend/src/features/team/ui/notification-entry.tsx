"use client";

import { BellOutlined, EnvironmentOutlined, MessageOutlined, TeamOutlined } from "@ant-design/icons";
import { Avatar } from "antd";
import Link from "next/link";
import type { NotificationItem } from "../model/types";
import { isAnnotationNotification, isDiscussionNotification, notificationHref, notificationSummary, notificationTitle } from "../model/notification";

export function NotificationEntry({ item, compact = false, onRead }: {
  item: NotificationItem;
  compact?: boolean;
  onRead: (id: string) => void;
}) {
  const href = notificationHref(item) ?? "/notifications";
  const icon = isAnnotationNotification(item)
    ? <EnvironmentOutlined />
    : isDiscussionNotification(item)
      ? <MessageOutlined />
      : item.resourceType === "space" || item.resourceType === "space_invitation"
        ? <TeamOutlined />
        : <BellOutlined />;

  return <Link
    className={`${compact ? "notification-entry is-compact" : "notification-entry"}${item.readAt ? "" : " is-unread"}`}
    href={href}
    onNavigate={() => { if (!item.readAt) onRead(item.id); }}
  >
    <Avatar size={compact ? 32 : 38} src={item.actor?.avatarUrl} icon={item.actor ? undefined : icon}>
      {item.actor?.name?.slice(0, 1)}
    </Avatar>
    <span className="notification-entry-content">
      <span className="notification-entry-heading">
        <strong>{item.actor?.name ? `${item.actor.name} ${notificationTitle(item)}` : notificationTitle(item)}</strong>
        {!item.readAt ? <i aria-label="未读" /> : null}
      </span>
      <span>{notificationSummary(item)}</span>
      <small>{new Date(item.createdAt).toLocaleString("zh-CN")}</small>
    </span>
  </Link>;
}
