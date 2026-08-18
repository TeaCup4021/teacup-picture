"use client";

import { ReloadOutlined, UndoOutlined } from "@ant-design/icons";
import {
  Button,
  Drawer,
  Empty,
  Image,
  List,
  Popconfirm,
  Result,
  Skeleton,
  Tag,
  Typography,
} from "antd";
import type { PictureVersionSummary } from "@/features/editor/model/types";

interface VersionPanelProps {
  open: boolean;
  versions: PictureVersionSummary[];
  loading: boolean;
  error: boolean;
  restoringId: string | null;
  onRetry: () => void;
  onClose: () => void;
  onRestore: (version: PictureVersionSummary) => void;
}

export function VersionPanel({
  open,
  versions,
  loading,
  error,
  restoringId,
  onRetry,
  onClose,
  onRestore,
}: VersionPanelProps) {
  return (
    <Drawer title="版本历史" size={420} open={open} onClose={onClose} destroyOnHidden>
      {loading ? (
        <Skeleton active paragraph={{ rows: 8 }} />
      ) : error ? (
        <Result
          status="error"
          title="版本读取失败"
          extra={
            <Button icon={<ReloadOutlined />} onClick={onRetry}>
              重试
            </Button>
          }
        />
      ) : versions.length === 0 ? (
        <Empty description="还没有历史版本" />
      ) : (
        <List
          dataSource={versions}
          renderItem={(version) => (
            <List.Item
              className="version-list-item"
              actions={[
                <Popconfirm
                  title="恢复此版本？"
                  description="恢复会替换当前图片并创建新历史，不会删除后续版本。"
                  okText="恢复"
                  cancelText="取消"
                  onConfirm={() => onRestore(version)}
                  key="restore"
                >
                  <Button type="link" icon={<UndoOutlined />} loading={restoringId === version.id}>
                    恢复
                  </Button>
                </Popconfirm>,
              ]}
            >
              <List.Item.Meta
                avatar={
                  <Image
                    className="version-thumbnail"
                    src={version.thumbnailUrl}
                    alt={version.name}
                    width={64}
                    height={64}
                    preview={{ src: version.thumbnailUrl }}
                  />
                }
                title={
                  <span className="version-title">
                    v{version.versionNumber} {version.name}
                    <Tag color={version.sourceType === "restore" ? "blue" : "green"}>
                      {version.sourceType === "restore"
                        ? "恢复"
                        : version.sourceType === "original"
                          ? "原图"
                          : "保存"}
                    </Tag>
                  </span>
                }
                description={
                  <Typography.Text type="secondary">
                    {formatTime(version.createdAt)} · {version.creator?.name ?? "未知用户"}
                    {version.note ? ` · ${version.note}` : ""}
                  </Typography.Text>
                }
              />
            </List.Item>
          )}
        />
      )}
    </Drawer>
  );
}

function formatTime(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN", { hour12: false });
}
