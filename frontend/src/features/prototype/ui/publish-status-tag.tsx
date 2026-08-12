import { Tag } from "antd";
import type { PublishStatus } from "@/features/prototype";

const statusConfiguration: Record<PublishStatus, { color?: string; label: string }> = {
  not_requested: { label: "私有" },
  pending: { color: "gold", label: "待审核" },
  approved: { color: "green", label: "已公开" },
  rejected: { color: "red", label: "已驳回" },
  withdrawn: { color: "default", label: "已撤回" },
};

export function PublishStatusTag({ status }: Readonly<{ status: PublishStatus }>) {
  const configuration = statusConfiguration[status];
  return <Tag color={configuration.color}>{configuration.label}</Tag>;
}
