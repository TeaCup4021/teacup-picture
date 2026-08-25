import type { NotificationItem } from "./types";

const numericId = /^[1-9][0-9]*$/;
const publicId = /^[A-Za-z0-9_-]{1,64}$/;

function payloadText(item: NotificationItem, key: string): string | undefined {
  const value = item.payload[key];
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

export function notificationHref(item: NotificationItem): string | null {
  if (item.resourceType === "picture_comment") {
    const pictureId = payloadText(item, "pictureId");
    const rootId = payloadText(item, "rootId") ?? item.resourceId ?? undefined;
    const commentId = payloadText(item, "commentId") ?? item.resourceId ?? undefined;
    if (!pictureId || !rootId || !commentId || !numericId.test(pictureId) || !numericId.test(rootId) || !numericId.test(commentId)) return null;

    const params = new URLSearchParams({ thread: rootId, comment: commentId });
    const sharePublicId = payloadText(item, "sharePublicId");
    if (sharePublicId && publicId.test(sharePublicId)) return `/shares/${sharePublicId}?${params.toString()}`;
    return `/pictures/${pictureId}?${params.toString()}`;
  }

  if (item.resourceType === "space") {
    const spaceId = payloadText(item, "spaceId") ?? item.resourceId ?? undefined;
    return spaceId && numericId.test(spaceId) ? `/spaces/${spaceId}` : null;
  }

  return null;
}

export function notificationTitle(item: NotificationItem): string {
  const annotation = payloadText(item, "kind") === "annotation";
  switch (item.type) {
    case "picture_comment_created": return annotation ? "在你的图片中添加了位置批注" : "评论了你的图片";
    case "picture_comment_reply": return annotation ? "回复了你的批注" : "回复了你的评论";
    case "picture_comment_mention": return "在图片讨论中提及了你";
    case "picture_annotation_resolved": return "解决了你的批注";
    case "picture_annotation_reopened": return "重新打开了你的批注";
    case "space_invitation": return "邀请你加入团队";
    case "space_invitation_accepted": return "接受了团队邀请";
    case "space_invitation_rejected": return "拒绝了团队邀请";
    case "space_member_role_changed": return "更新了你的团队角色";
    case "space_member_removed": return "将你移出团队空间";
    case "space_ownership_transferred": return "向你转让了团队所有权";
    default: return "发送了一条系统通知";
  }
}

export function notificationSummary(item: NotificationItem): string {
  const pictureName = payloadText(item, "pictureName");
  const excerpt = payloadText(item, "excerpt");
  if (pictureName && excerpt) return `${pictureName} · ${excerpt}`;
  if (pictureName) return pictureName;
  return payloadText(item, "spaceName") ?? "查看通知详情";
}

export function isDiscussionNotification(item: NotificationItem): boolean {
  return item.resourceType === "picture_comment";
}

export function isAnnotationNotification(item: NotificationItem): boolean {
  return payloadText(item, "kind") === "annotation";
}
