"use client";

import {
  CloseCircleOutlined,
  DownloadOutlined,
  EyeOutlined,
  PlusOutlined,
  StopOutlined,
} from "@ant-design/icons";
import { Alert, App, Button, Result, Segmented, Skeleton, Table, Tag, Tooltip } from "antd";
import type { TableColumnsType } from "antd";
import { useState } from "react";
import { apiClient } from "@/api/client";
import { useAiTasks, useCancelAiTask, type AiTask, type AiTaskStatus } from "@/features/ai";
import { usePrototypeSession } from "@/features/prototype";
import { PictureImage } from "@/features/prototype/ui/picture-image";

const statusMeta: Record<AiTaskStatus, { label: string; color: string }> = {
  queued: { label: "等待中", color: "blue" },
  running: { label: "生成中", color: "processing" },
  succeeded: { label: "已完成", color: "success" },
  failed: { label: "失败", color: "error" },
  cancelled: { label: "已取消", color: "default" },
};

const filters: Array<{ label: string; value: "all" | AiTaskStatus }> = [
  { label: "全部", value: "all" },
  { label: "进行中", value: "running" },
  { label: "已完成", value: "succeeded" },
  { label: "失败", value: "failed" },
  { label: "已取消", value: "cancelled" },
];

export function AiTasks() {
  const { message } = App.useApp();
  const [filter, setFilter] = useState<"all" | AiTaskStatus>("all");
  const session = usePrototypeSession();
  const enabled = session.data?.role === "user";
  const query = useAiTasks(filter === "all" ? undefined : filter, enabled);
  const cancel = useCancelAiTask();

  if (session.isLoading)
    return (
      <main className="content-shell">
        <Skeleton active paragraph={{ rows: 12 }} />
      </main>
    );
  if (!enabled)
    return (
      <Result
        status="403"
        title="登录普通用户账号后查看 AI 任务"
        extra={
          <Button type="primary" href="/login">
            去登录
          </Button>
        }
      />
    );

  const download = async (task: AiTask) => {
    if (!task.downloadUrl) return;
    try {
      const response = await apiClient.get<Blob>(task.downloadUrl.replace(/^\/api\/v1/, ""), {
        responseType: "blob",
      });
      const url = URL.createObjectURL(response.data);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = `${task.resultPicture?.name ?? `ai-${task.id}`}.png`;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch (error) {
      void message.error(error instanceof Error ? error.message : "下载失败");
    }
  };

  const columns: TableColumnsType<AiTask> = [
    {
      title: "结果",
      dataIndex: "resultPicture",
      width: 76,
      render: (_, task) =>
        task.resultPicture ? (
          <div className="ai-task-thumb">
            <PictureImage alt={task.resultPicture.name} src={task.resultPicture.thumbnailUrl} />
          </div>
        ) : (
          <div className="ai-task-thumb is-empty">
            <CloseCircleOutlined />
          </div>
        ),
    },
    {
      title: "任务",
      dataIndex: "prompt",
      render: (_, task) => (
        <div className="ai-task-copy">
          <Tooltip title={task.prompt}>
            <strong>{task.prompt}</strong>
          </Tooltip>
          <span>
            {task.type === "generate" ? "AI 绘图" : "AI 扩图"} · {task.model.name} · {task.ratio} ·{" "}
            {task.outputFormat.toUpperCase()}
          </span>
        </div>
      ),
    },
    {
      title: "状态",
      dataIndex: "status",
      width: 110,
      render: (status: AiTaskStatus) => (
        <Tag color={statusMeta[status].color}>{statusMeta[status].label}</Tag>
      ),
    },
    {
      title: "创建时间",
      dataIndex: "createdAt",
      width: 170,
      render: (value: string) => new Date(value).toLocaleString("zh-CN"),
    },
    {
      title: "操作",
      key: "actions",
      width: 132,
      align: "right",
      render: (_, task) => (
        <div className="ai-task-actions">
          {task.resultPicture ? (
            <Tooltip title="查看图片">
              <Button
                aria-label="查看图片"
                type="text"
                icon={<EyeOutlined />}
                href={`/pictures/${task.resultPicture.id}`}
              />
            </Tooltip>
          ) : null}
          {task.downloadUrl ? (
            <Tooltip title="下载">
              <Button
                aria-label="下载结果"
                type="text"
                icon={<DownloadOutlined />}
                onClick={() => void download(task)}
              />
            </Tooltip>
          ) : null}
          {task.status === "queued" || task.status === "running" ? (
            <Tooltip title="取消任务">
              <Button
                danger
                aria-label="取消任务"
                type="text"
                icon={<StopOutlined />}
                loading={cancel.isPending && cancel.variables === task.id}
                onClick={() =>
                  cancel.mutate(task.id, {
                    onSuccess: () => void message.success("任务已取消"),
                    onError: (error) => void message.error(error.message),
                  })
                }
              />
            </Tooltip>
          ) : null}
        </div>
      ),
    },
  ];

  return (
    <main className="content-shell ai-tasks-shell">
      <section className="page-heading ai-page-heading" aria-labelledby="ai-tasks-title">
        <div>
          <p className="page-kicker">AI TASKS</p>
          <h1 id="ai-tasks-title">任务中心</h1>
          <p>任务会自动刷新，成功结果已保存到个人空间</p>
        </div>
        <Button type="primary" href="/ai/create" icon={<PlusOutlined />}>
          新建任务
        </Button>
      </section>
      <div className="ai-task-toolbar">
        <Segmented
          options={filters}
          value={filter}
          onChange={(value) => setFilter(value as "all" | AiTaskStatus)}
        />
        <span>{query.data?.page.total ?? 0} 个任务</span>
      </div>
      {query.isError ? (
        <Alert
          type="error"
          showIcon
          title="任务加载失败"
          description={query.error.message}
          action={
            <Button size="small" onClick={() => void query.refetch()}>
              重试
            </Button>
          }
        />
      ) : null}
      {query.isLoading ? (
        <Skeleton active paragraph={{ rows: 10 }} />
      ) : query.data?.items.length ? (
        <section className="ai-task-table" aria-label="AI 任务列表">
          <Table rowKey="id" columns={columns} dataSource={query.data.items} pagination={false} />
        </section>
      ) : (
        <Result
          title="还没有 AI 任务"
          extra={
            <Button type="primary" href="/ai/create">
              开始创作
            </Button>
          }
        />
      )}
      <div className="ai-task-mobile-list">
        {query.data?.items.map((task) => (
          <article key={task.id} className="ai-task-mobile-item">
            <div className="ai-task-mobile-head">
              <Tag color={statusMeta[task.status].color}>{statusMeta[task.status].label}</Tag>
              <span>{new Date(task.createdAt).toLocaleString("zh-CN")}</span>
            </div>
            <strong>{task.prompt}</strong>
            <span>
              {task.type === "generate" ? "AI 绘图" : "AI 扩图"} · {task.model.name} ·{" "}
              {task.outputFormat.toUpperCase()}
            </span>
            {task.failureReason ? (
              <Alert
                type="error"
                showIcon
                title={task.failureReason}
                description={task.quotaRefunded ? "本次额度已返还" : "本次额度未返还"}
              />
            ) : null}
            <div className="ai-task-mobile-actions">
              {task.resultPicture ? (
                <Button href={`/pictures/${task.resultPicture.id}`} icon={<EyeOutlined />}>
                  查看
                </Button>
              ) : null}
              {task.downloadUrl ? (
                <Button icon={<DownloadOutlined />} onClick={() => void download(task)}>
                  下载
                </Button>
              ) : null}
              {task.status === "queued" || task.status === "running" ? (
                <Button danger icon={<StopOutlined />} onClick={() => cancel.mutate(task.id)}>
                  取消
                </Button>
              ) : null}
            </div>
          </article>
        ))}
      </div>
    </main>
  );
}
